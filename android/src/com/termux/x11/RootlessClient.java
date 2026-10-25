package com.termux.x11;

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
import java.util.concurrent.atomic.AtomicInteger;

/** One watched Binder identity per visible Activity; reconnects without owning the server. */
final class RootlessClient implements ServiceConnection {
    interface Listener { void reply(int what, Bundle data); void disconnected(); }
    private static final AtomicInteger TOKENS = new AtomicInteger();
    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Messenger replies = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private Messenger server;
    private boolean active, bound, readOnly;
    private int token;
    RootlessClient(Context context, Listener listener, boolean readOnly) {
        this.context = context; this.listener = listener; this.readOnly = readOnly;
    }
    void setReadOnly(boolean readOnly) {
        if (this.readOnly == readOnly) return;
        this.readOnly = readOnly;
        if (server != null) watch();
    }
    private void watch() {
        Bundle data = new Bundle(); data.putBoolean("readOnly", readOnly);
        send(X11Service.WATCH, data);
    }
    void start() { if (!active) { active = true; bind(); } }
    private void bind() {
        if (!active || bound) return;
        token = TOKENS.incrementAndGet();
        bound = context.bindService(new Intent(context, X11Service.class), this, Context.BIND_AUTO_CREATE);
        if (!bound) { listener.disconnected(); handler.postDelayed(this::bind, 1000); }
    }
    void stop() {
        active = false; handler.removeCallbacksAndMessages(null);
        send(X11Service.DETACH, new Bundle()); server = null;
        if (bound) { context.unbindService(this); bound = false; }
    }
    boolean send(int what, Bundle data) {
        if (server == null) return false;
        Message message = Message.obtain(null, what, token, 0);
        message.replyTo = replies; message.setData(data);
        try { server.send(message); return true; }
        catch (RemoteException error) { lost(); return false; }
    }
    private boolean receive(Message message) {
        if (active && message.arg1 == token) listener.reply(message.what, message.getData());
        return true;
    }
    @Override public void onServiceConnected(ComponentName name, IBinder binder) {
        if (!active) return;
        server = new Messenger(binder); watch();
    }
    private void lost() {
        server = null; listener.disconnected();
        if (bound) { context.unbindService(this); bound = false; }
        if (active) handler.postDelayed(this::bind, 500);
    }
    @Override public void onServiceDisconnected(ComponentName name) { lost(); }
    @Override public void onBindingDied(ComponentName name) { lost(); }
    @Override public void onNullBinding(ComponentName name) { lost(); }
}
