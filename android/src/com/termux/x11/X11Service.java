package com.termux.x11;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.system.Os;
import android.util.Log;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Same UID, separate :x11server process: Xorg's exit() must never terminate the app. */
public final class X11Service extends Service {
    static final int WATCH = 1, ATTACH = 2, DETACH = 3, STOP = 4;
    static final int STATUS = 10, CONNECTION = 11;
    static final String ACTION_START = "com.termux.x11.START";
    static final String ACTION_STOP = "com.termux.x11.STOP";
    private static final String CHANNEL = "embedded-x11";
    private static final int NOTIFICATION = 119;
    private static final String XKB_VERSION = "xkb-fa3a8b4";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper(), this::handleMessage));
    private final Map<IBinder, Client> clients = new LinkedHashMap<>();
    private CmdEntryPoint entry;
    private String state = "Stopped";

    public static void start(Context context) {
        context.startForegroundService(new Intent(context, X11Service.class).setAction(ACTION_START));
    }
    private boolean starting, ready;
    private volatile boolean stopping;
    private boolean drainScheduled;

    // Implemented by the app's Unix-only seccomp guard, before any Xlorie code is loaded.
    static native int restrictNetwork();

    private final class Client implements IBinder.DeathRecipient {
        final Messenger reply;
        final int token;
        boolean wantsConnection;
        Client(Messenger reply, int token) { this.reply = reply; this.token = token; }
        @Override public void binderDied() { handler.post(() -> removeClient(reply.getBinder(), token)); }
    }

    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopServer();
        } else if (!stopping) {
            showNotification();
            if (!starting && !ready) startServer();
        }
        return START_NOT_STICKY;
    }

    private void showNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "X11 display", NotificationManager.IMPORTANCE_LOW));
        PendingIntent show = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, X11Service.class).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("X11 display :9")
                .setContentText("Ubuntu X11 applications · tap to show")
                .setContentIntent(show).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop display", stop).build()).build();
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION, notification);
    }

    private boolean handleMessage(Message message) {
        if (message.sendingUid != Process.myUid()) return true;
        if (message.what == STOP) { stopServer(); return true; }
        if (message.replyTo == null || stopping) return true;
        IBinder binder = message.replyTo.getBinder();
        Client client = clients.get(binder);
        if (message.what == WATCH) {
            if (client != null) binder.unlinkToDeath(client, 0);
            client = new Client(message.replyTo, message.arg1);
            try { binder.linkToDeath(client, 0); } catch (RemoteException dead) { return true; }
            clients.put(binder, client);
            sendStatus(client);
        } else if (client != null && client.token == message.arg1) {
            if (message.what == ATTACH) { client.wantsConnection = true; drainConnections(); }
            else if (message.what == DETACH) removeClient(binder, message.arg1);
        }
        return true;
    }

    private void removeClient(IBinder binder, int token) {
        Client client = clients.get(binder);
        if (client == null || client.token != token) return;
        clients.remove(binder);
        binder.unlinkToDeath(client, 0);
    }

    private void setState(String text) {
        state = text;
        for (Client client : new ArrayList<>(clients.values())) sendStatus(client);
    }

    private void sendStatus(Client client) {
        Message response = Message.obtain(null, STATUS, client.token, ready ? 1 : 0);
        Bundle data = new Bundle();
        data.putString("status", state);
        data.putBoolean("starting", starting);
        data.putBoolean("stopping", stopping);
        response.setData(data);
        try { client.reply.send(response); }
        catch (RemoteException dead) { removeClient(client.reply.getBinder(), client.token); }
    }

    private void drainConnections() {
        if (!ready || stopping) return;
        boolean pending = false;
        for (Client client : new ArrayList<>(clients.values())) {
            if (!client.wantsConnection) continue;
            if (CmdEntryPoint.connected()) { pending = true; break; }
            client.wantsConnection = false;
            // Never replace a still-live native connection: its later HUP would clear the new one.
            try (ParcelFileDescriptor fd = entry.getXConnection()) {
                if (fd == null) throw new IOException("Native renderer socket was not created");
                Message response = Message.obtain(null, CONNECTION, client.token, 0);
                Bundle data = new Bundle();
                data.putParcelable("fd", fd);
                response.setData(data);
                client.reply.send(response);
            } catch (IOException | RemoteException error) {
                Log.w("EmbeddedX11", "Renderer connection failed", error);
                removeClient(client.reply.getBinder(), client.token);
            }
            // getXConnection queues registration on Xorg's thread; let that finish before another.
            pending = true;
            break;
        }
        if (pending && !drainScheduled) {
            drainScheduled = true;
            handler.postDelayed(() -> { drainScheduled = false; drainConnections(); }, 100);
        }
    }

    private void startServer() {
        starting = true;
        setState("Preparing X11 display :9…");
        new Thread(() -> {
            try {
                File root = new File(getFilesDir(), "runtime/x11");
                File tmp = new File(root, "tmp");
                mkdir(new File(tmp, ".X11-unix"));
                // Only this Android process owns :9; leftovers are from its previous native exit.
                deleteIfPresent(new File(tmp, ".X11-unix/X9"));
                deleteIfPresent(new File(tmp, ".X9-lock"));
                File xkb = installXkb(root);
                byte[] cookie = new byte[16];
                new SecureRandom().nextBytes(cookie);
                File authority = new File(root, "Xauthority");
                writeAuthority(authority, cookie);
                handler.post(() -> startNative(tmp, xkb, authority, cookie));
            } catch (Exception error) { handler.post(() -> fail(error)); }
        }, "X11-prepare").start();
    }

    private void startNative(File tmp, File xkb, File authority, byte[] cookie) {
        if (stopping) return;
        try {
            System.loadLibrary("zfold_x11_guard");
            int error = restrictNetwork();
            if (error != 0) throw new IOException("Cannot enforce Unix-only X11 networking: " + Os.strerror(Math.abs(error)));
            // Upstream calls dirname(getenv("TMPDIR")) in-place while looking for fonts.
            // A trailing slash keeps that call from changing the actual temporary directory.
            Os.setenv("TMPDIR", tmp.getAbsolutePath() + "/", true);
            Os.setenv("XKB_CONFIG_ROOT", xkb.getAbsolutePath(), true);
            entry = new CmdEntryPoint(this);
            // JNI obtains AChoreographer and starts Xorg's thread: this call belongs on main Looper.
            if (!entry.start(new String[]{":9", "-nolisten", "tcp", "-auth", authority.getAbsolutePath(), "-noreset"}))
                throw new IOException("Native X11 initialization failed");
            setState("Starting authenticated X11 display :9…");
            new Thread(() -> awaitReady(new File(tmp, ".X11-unix/X9"), cookie), "X11-ready").start();
        } catch (Exception | LinkageError error) { fail(error); }
    }

    private void awaitReady(File socket, byte[] cookie) {
        long deadline = SystemClock.elapsedRealtime() + 20000;
        Exception last = new IOException("X11 socket did not become ready");
        while (!stopping && SystemClock.elapsedRealtime() < deadline) {
            try (LocalSocket connection = new LocalSocket()) {
                connection.connect(new LocalSocketAddress(socket.getAbsolutePath(), LocalSocketAddress.Namespace.FILESYSTEM));
                connection.setSoTimeout(1000);
                DataOutputStream out = new DataOutputStream(connection.getOutputStream());
                byte[] name = "MIT-MAGIC-COOKIE-1".getBytes(StandardCharsets.US_ASCII);
                out.writeByte('B'); out.writeByte(0); out.writeShort(11); out.writeShort(0);
                out.writeShort(name.length); out.writeShort(cookie.length); out.writeShort(0);
                out.write(name);
                for (int i = name.length; (i & 3) != 0; i++) out.writeByte(0);
                out.write(cookie); out.flush();
                if (connection.getInputStream().read() != 1) throw new IOException("X11 cookie authentication was rejected");
                handler.post(() -> {
                    if (stopping) return;
                    starting = false; ready = true;
                    setState("Running :9 · DISPLAY=:9");
                    drainConnections();
                });
                return;
            } catch (Exception error) {
                last = error;
                SystemClock.sleep(100);
            }
        }
        Exception failure = last;
        if (!stopping) handler.post(() -> fail(failure));
    }

    private void fail(Throwable error) {
        if (stopping) return;
        Log.e("EmbeddedX11", "X11 startup failed", error);
        starting = false; ready = false; stopping = true;
        setState("X11 failed: " + error.getMessage());
        finishProcess();
    }

    private void stopServer() {
        if (stopping) return;
        stopping = true; starting = false; ready = false;
        setState("Stopped");
        finishProcess();
    }

    private void finishProcess() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        // Give Xorg its normal signal teardown (including socket/lock cleanup). The deadline
        // also covers native initialization failures before its signal handlers were installed.
        handler.postDelayed(() -> {
            stopSelf();
            Process.killProcess(Process.myPid());
        }, 1000);
        if (entry != null) Process.sendSignal(Process.myPid(), 15);
        else stopSelf();
    }

    @Override public void onDestroy() {
        stopping = true;
        super.onDestroy();
        Process.killProcess(Process.myPid());
    }

    // Callbacks are deliberately informational only: neither native knocks nor repeated callbacks
    // may attach a renderer or bypass the authenticated readiness probe / Messenger ownership.
    void onNativeAnnouncement(boolean delayed) {
        Log.d("EmbeddedX11", delayed ? "Native display initializing" : "Native connection announcement");
    }
    void onNativeDocumentTag(String tag) { Log.d("EmbeddedX11", "Native display tag: " + tag); }

    private static void mkdir(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
    }
    private static void deleteIfPresent(File file) throws IOException {
        if (file.exists() && !file.delete()) throw new IOException("Cannot remove stale " + file);
    }
    private File installXkb(File root) throws IOException {
        File target = new File(root, XKB_VERSION);
        if (new File(target, ".complete").isFile()) return target;
        mkdir(target);
        String prefix = target.getCanonicalPath() + File.separator;
        try (ZipInputStream zip = new ZipInputStream(getAssets().open("x11/xkb.zip"))) {
            byte[] buffer = new byte[32768];
            ZipEntry item;
            while ((item = zip.getNextEntry()) != null) {
                File file = new File(target, item.getName());
                if (!file.getCanonicalPath().startsWith(prefix)) throw new IOException("Invalid XKB asset path");
                if (item.isDirectory()) { mkdir(file); continue; }
                mkdir(file.getParentFile());
                try (FileOutputStream out = new FileOutputStream(file)) {
                    int count;
                    while ((count = zip.read(buffer)) != -1) out.write(buffer, 0, count);
                }
            }
        }
        if (!new File(target, "rules/evdev").isFile() || !new File(target, "symbols/us").isFile())
            throw new IOException("Bundled XKB data is incomplete");
        if (!new File(target, ".complete").createNewFile()) throw new IOException("Cannot mark XKB installation complete");
        return target;
    }
    private static void writeAuthority(File file, byte[] cookie) throws Exception {
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
            Os.chmod(file.getAbsolutePath(), 0600);
            out.writeShort(65535); // FamilyWild: guest hostname need not equal the Android hostname.
            writeField(out, new byte[0]);
            writeField(out, "9".getBytes(StandardCharsets.US_ASCII));
            writeField(out, "MIT-MAGIC-COOKIE-1".getBytes(StandardCharsets.US_ASCII));
            writeField(out, cookie);
        }
    }
    private static void writeField(DataOutputStream out, byte[] value) throws IOException {
        out.writeShort(value.length); out.write(value);
    }
}
