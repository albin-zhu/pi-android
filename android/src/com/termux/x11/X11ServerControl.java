package com.termux.x11;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import java.util.function.BooleanSupplier;

/** A bounded observer of the shared display, never an owner of its lifetime. */
public final class X11ServerControl {
    private X11ServerControl() {}

    public interface Callback { void completed(Bundle state, String error); }

    public static void request(Context context, String action, BooleanSupplier cancelled, Callback callback) {
        if (callback == null) throw new IllegalArgumentException("Callback is required");
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(() -> new Request(context.getApplicationContext(), action, cancelled, callback, handler).begin());
    }

    private static final class Request implements ServiceConnection {
        private final Context context;
        private final String action;
        private final BooleanSupplier cancelled;
        private final Callback callback;
        private final Handler handler;
        private final Messenger replies;
        private Messenger server;
        private boolean bindingAttempted, finished;
        private Bundle state = stopped();
        private final Runnable timeout;
        private final Runnable cancellation = new Runnable() {
            @Override public void run() {
                if (!finished && !checkCancelled()) handler.postDelayed(this, 100);
            }
        };

        Request(Context context, String action, BooleanSupplier cancelled, Callback callback, Handler handler) {
            this.context = context;
            this.action = action;
            this.cancelled = cancelled;
            this.callback = callback;
            this.handler = handler;
            timeout = () -> finish("Timed out waiting for X11 server "
                    + ("start".equals(action) ? "authenticated readiness and renderer connection" : "status"));
            replies = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
        }

        private static Bundle stopped() {
            Bundle state = new Bundle();
            state.putString("display", ":9");
            state.putBoolean("running", false);
            state.putBoolean("starting", false);
            state.putBoolean("ready", false);
            state.putInt("windowCount", 0);
            state.putString("status", "Stopped");
            return state;
        }

        void begin() {
            if (!"status".equals(action) && !"start".equals(action)) {
                finish("X11 server action must be status or start");
                return;
            }
            if (checkCancelled()) return;
            handler.postDelayed(timeout, "start".equals(action) ? 45000 : 5000);
            if (cancelled != null) handler.postDelayed(cancellation, 100);
            try {
                boolean start = "start".equals(action);
                if (start) {
                    X11Service.start(context);
                    state.putBoolean("starting", true);
                    state.putString("status", "Waiting for X11 display :9");
                } else if (!hasRunningService()) {
                    finish(null);
                    return;
                }
                bindingAttempted = true;
                // A query must not instantiate the service or initialize native rendering.
                boolean bound = context.bindService(new Intent(context, X11Service.class), this,
                        start ? Context.BIND_AUTO_CREATE : 0);
                if (!bound) finish(start ? "Unable to bind X11 server" : null);
            } catch (RuntimeException error) {
                finish("Unable to " + action + " X11 server: " + error.getMessage());
            }
        }
        private boolean hasRunningService() {
            // A non-auto-create bind can return true for an inactive service without
            // ever connecting. Android still exposes this application's own services.
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            java.util.List<ActivityManager.RunningServiceInfo> services = manager.getRunningServices(Integer.MAX_VALUE);
            if (services != null) for (ActivityManager.RunningServiceInfo service : services)
                if ((service.pid != 0 || service.started)
                        && context.getPackageName().equals(service.service.getPackageName())
                        && X11Service.class.getName().equals(service.service.getClassName())) return true;
            return false;
        }


        private boolean checkCancelled() {
            try {
                if (cancelled == null || !cancelled.getAsBoolean()) return false;
                finish("X11 server request cancelled");
            } catch (RuntimeException error) {
                finish("Unable to observe X11 request cancellation: " + error.getMessage());
            }
            return true;
        }

        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (finished || checkCancelled()) return;
            server = new Messenger(binder);
            try { send(X11Service.WATCH); }
            catch (RemoteException error) { finish("X11 server disconnected before status"); }
        }

        private void send(int what) throws RemoteException {
            Message message = Message.obtain(null, what, 1, 0);
            message.replyTo = replies;
            Bundle data = new Bundle();
            data.putBoolean("readOnly", true);
            message.setData(data);
            server.send(message);
        }

        private boolean receive(Message message) {
            if (finished || message.arg1 != 1 || message.what != X11Service.STATUS || checkCancelled()) return true;
            state = new Bundle(message.getData());
            if (state.getBoolean("stopping")) finish("X11 server stopped: " + state.getString("status"));
            else if ("status".equals(action) || state.getBoolean("ready")) finish(null);
            return true;
        }

        private void finish(String error) {
            if (finished) return;
            finished = true;
            handler.removeCallbacks(timeout);
            handler.removeCallbacks(cancellation);
            if (server != null) {
                try { send(X11Service.DETACH); } catch (RemoteException ignored) {}
                server = null;
            }
            if (bindingAttempted) {
                try { context.unbindService(this); } catch (IllegalArgumentException ignored) {}
                bindingAttempted = false;
            }
            callback.completed(new Bundle(state), error);
        }

        @Override public void onServiceDisconnected(ComponentName name) { finish("X11 server disconnected"); }
        @Override public void onBindingDied(ComponentName name) { finish("X11 server binding died"); }
        @Override public void onNullBinding(ComponentName name) { finish("X11 server returned no control interface"); }
    }
}
