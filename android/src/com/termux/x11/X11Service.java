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
    static final int STATUS = 10, WINDOWS = 11, RESIZE = 12, FOCUS = 13,
            POINTER = 14, KEY = 15, TEXT = 16, CLOSE = 17, RELEASE = 18;
    static final int CLIPBOARD_ENABLED = 19, CLIPBOARD_ANNOUNCE = 20, CLIPBOARD_TEXT = 21,
            CLIPBOARD_RECEIVED = 22, CLIPBOARD_REQUEST = 23;
    static final int IME_RESET = 24, POPUP_OUTCOME = 25, GAMEPAD = 26;
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
    private final String session = java.util.UUID.randomUUID().toString();
    private final Map<Long, Bundle> windows = new LinkedHashMap<>();
    private RootlessNative renderer;
    private long rendererHandle, nextLease;
    private boolean rendererConnected;
    private volatile Client clipboardOwner;
    private volatile Client focusedClient;
    private GamepadPublisher gamepad;
    private Client gamepadOwner;
    private String gamepadError;
    private final Runnable gamepadHeartbeat = new Runnable() {
        @Override public void run() {
            if (stopping || gamepad == null) return;
            try {
                gamepad.heartbeat(SystemClock.elapsedRealtime());
                handler.postDelayed(this, 500);
            } catch (IOException error) { disableGamepad(error); }
        }
    };
    private final java.util.Set<String> announcedWindows = new java.util.HashSet<>();
    private final Map<Long, Long> generations = new LinkedHashMap<>();
    private final Map<String, Runnable> popupTimeouts = new LinkedHashMap<>();
    private static final long POPUP_TIMEOUT_MS = 8000;

    public static void start(Context context) {
        context.startForegroundService(new Intent(context, X11Service.class).setAction(ACTION_START));
    }
    private boolean starting, ready;
    private volatile boolean stopping;
    private boolean connecting, connectionRetryScheduled;

    // Implemented by the app's Unix-only seccomp guard, before any Xlorie code is loaded.
    static native int restrictNetwork();

    private final class Client implements IBinder.DeathRecipient {
        final Messenger reply;
        final int token;
        volatile long window, generation, lease, surface;
        boolean readOnly;
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
        if (message.replyTo == null) return true;
        if (stopping) {
            if (message.what == WATCH) sendStatus(new Client(message.replyTo, message.arg1));
            return true;
        }
        IBinder binder = message.replyTo.getBinder();
        Client client = clients.get(binder);
        if (message.what == WATCH) {
            if (client != null) removeClient(binder, client.token);
            client = new Client(message.replyTo, message.arg1);
            client.readOnly = message.getData().getBoolean("readOnly", true);
            try { binder.linkToDeath(client, 0); } catch (RemoteException dead) { return true; }
            clients.put(binder, client);
            sendStatus(client);
            sendWindows(client);
        } else if (client != null && client.token == message.arg1) {
            Bundle data = message.getData();
            data.setClassLoader(android.view.Surface.class.getClassLoader());
            if (message.what == DETACH) removeClient(binder, message.arg1);
            else if (session.equals(data.getString("session"))) dispatch(client, message.what, data);
        }
        return true;
    }

    private String windowKey(Bundle info) {
        return session + ":" + info.getLong("window") + ":" + info.getLong("generation");
    }

    private void openNewWindow(Bundle info) {
        String key = windowKey(info);
        info.putString("popupState", "requested");
        updateWindowNotification(info);
        Runnable timeout = () -> {
            popupTimeouts.remove(key);
            Bundle current = windows.get(info.getLong("window"));
            if (!stopping && current != null && windowKey(current).equals(key)
                    && "requested".equals(current.getString("popupState"))) {
                current.putString("popupState", "unconfirmed");
                updateWindowNotification(current);
                broadcastWindows();
            }
        };
        popupTimeouts.put(key, timeout);
        handler.postDelayed(timeout, POPUP_TIMEOUT_MS);
        try { WindowActivity.open(this, session, info, false); }
        catch (RuntimeException error) {
            Log.w("EmbeddedX11", "System popup request rejected", error);
            clearPopupTimeout(info);
            info.putString("popupState", "blocked");
            updateWindowNotification(info);
        }
    }

    private void clearPopupTimeout(Bundle info) {
        Runnable timeout = popupTimeouts.remove(windowKey(info));
        if (timeout != null) handler.removeCallbacks(timeout);
    }

    static String popupDescription(String outcome) {
        if ("requested".equals(outcome)) return "Requesting system popup…";
        if ("popup".equals(outcome)) return "System popup open";
        if ("not-popup".equals(outcome)) return "Android opened a regular window, not a popup";
        if ("blocked".equals(outcome)) return "Android rejected the popup request · tap to open";
        if ("unconfirmed".equals(outcome)) return "Popup not confirmed · tap to open";
        if ("hidden".equals(outcome)) return "Window hidden · tap to reopen";
        return "";
    }

    private void updateWindowNotification(Bundle info) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        String outcome = info.getString("popupState");
        if (stopping || "popup".equals(outcome) || "hidden".equals(outcome)) {
            manager.cancel(windowKey(info), 120);
            return;
        }
        try {
            PendingIntent open = PendingIntent.getActivity(this, 0,
                    WindowActivity.intent(this, session, info, false),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                    dev.zfold.assistant.SystemWindowNavigation.popupOptions(this));
            PendingIntent list = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_view)
                    .setContentTitle(info.getString("title", "X11 application"))
                    .setContentText(popupDescription(outcome))
                    .setContentIntent(open).setOnlyAlertOnce(true)
                    .addAction(new Notification.Action.Builder(null, "Window manager", list).build()).build();
            manager.notify(windowKey(info), 120, notification);
        } catch (RuntimeException error) { Log.w("EmbeddedX11", "Cannot post window notification", error); }
    }

    private void popupOutcome(Bundle data) {
        Bundle info = windows.get(data.getLong("window"));
        if (info == null || info.getLong("generation") != data.getLong("generation")) return;
        String outcome = !data.getBoolean("visible") ? "hidden"
                : data.getBoolean("popup") ? "popup" : "not-popup";
        clearPopupTimeout(info);
        if (outcome.equals(info.getString("popupState"))) return;
        info.putString("popupState", outcome);
        updateWindowNotification(info);
        broadcastWindows();
    }

    private void retireWindow(Bundle info) {
        clearPopupTimeout(info);
        getSystemService(NotificationManager.class).cancel(windowKey(info), 120);
        Intent intent = WindowActivity.intent(this, session, info, false);
        PendingIntent pending = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pending != null) pending.cancel();
        android.app.ActivityManager manager = getSystemService(android.app.ActivityManager.class);
        for (android.app.ActivityManager.AppTask task : manager.getAppTasks()) {
            try {
                Intent base = task.getTaskInfo().baseIntent;
                if (base != null && intent.getData().equals(base.getData())) task.finishAndRemoveTask();
            } catch (RuntimeException error) { Log.w("EmbeddedX11", "Cannot remove closed window task", error); }
        }
    }

    private void broadcastWindows() {
        for (Client client : new ArrayList<>(clients.values())) sendWindows(client);
    }

    private void removeClient(IBinder binder, int token) {
        Client client = clients.get(binder);
        if (client == null || client.token != token) return;
        clients.remove(binder);
        release(client);
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
        data.putString("display", ":9");
        data.putBoolean("running", !stopping && (starting || ready));
        data.putBoolean("ready", ready && rendererConnected && !stopping);
        data.putInt("windowCount", windows.size());
        data.putBoolean("starting", starting);
        data.putBoolean("stopping", stopping);
        response.setData(data);
        data.putString("session", session);
        data.putBoolean("rendererConnected", rendererConnected);
        data.putBoolean("gamepadReady", gamepad != null && rendererConnected && !stopping);
        if (gamepadError != null) data.putString("gamepadError", gamepadError);
        try { client.reply.send(response); }
        catch (RemoteException dead) { removeClient(client.reply.getBinder(), client.token); }
    }

    private void release(Client client) {
        disableClipboard(client);
        releaseGamepad(client);
        if (focusedClient == client) focusedClient = null;
        if (client.lease != 0 && rendererHandle != 0)
            renderer.detach(rendererHandle, client.window, client.generation, client.lease);
        client.lease = 0;
    }

    private void dispatch(Client client, int command, Bundle data) {
        long window = data.getLong("window"), generation = data.getLong("generation");
        if (command == POPUP_OUTCOME) { popupOutcome(data); return; }
        Bundle info = windows.get(window);
        if ((window != 0 && (info == null || info.getLong("generation") != generation))
                || (window == 0 && generation != 0) || !rendererConnected) return;
        long surfaceId = data.getLong("surfaceId");
        if (command == ATTACH) {
            android.view.Surface surface = data.getParcelable("surface");
            if (surface == null) return;
            try {
                if (!surface.isValid() || surfaceId <= client.surface) return;
                release(client);
                for (Client other : clients.values())
                    if (other != client && other.window == window && other.generation == generation) release(other);
                client.window = window; client.generation = generation; client.surface = surfaceId;
                client.readOnly = window == 0 || data.getBoolean("readOnly");
                client.lease = ++nextLease;
                renderer.attach(rendererHandle, window, generation, client.lease, surface,
                        dimension(data, "width"), dimension(data, "height"), client.readOnly);
            } finally { surface.release(); }
            return;
        }
        if (client.lease == 0 || client.window != window || client.generation != generation || client.surface != surfaceId) return;
        if (command == RELEASE) { release(client); return; }
        if (client.readOnly) return;
        switch (command) {
            case RESIZE: renderer.resize(rendererHandle, window, generation, dimension(data, "width"), dimension(data, "height")); break;
            case FOCUS:
                if (focusedClient != client) releaseGamepad(gamepadOwner);
                if (clipboardOwner != null && clipboardOwner != client) disableClipboard(clipboardOwner);
                focusedClient = client;
                renderer.focus(rendererHandle, window, generation);
                break;
            case POINTER: renderer.pointer(rendererHandle, window, generation, data.getFloat("x"), data.getFloat("y"),
                    data.getInt("button"), data.getBoolean("down"), data.getBoolean("relative")); break;
            case KEY: renderer.key(rendererHandle, window, generation, data.getInt("scanCode"), data.getInt("keyCode"), data.getBoolean("down")); break;
            case TEXT: String text = data.getString("text"); if (text != null) renderer.text(rendererHandle, window, generation, text); break;
            case CLOSE: renderer.closeWindow(rendererHandle, window, generation); break;
            case GAMEPAD: publishGamepad(client, data); break;
            case CLIPBOARD_ENABLED:
                if (data.getBoolean("enabled")) {
                    if (focusedClient != client) return;
                    if (clipboardOwner != null && clipboardOwner != client) disableClipboard(clipboardOwner);
                    clipboardOwner = client;
                    renderer.clipboardEnabled(rendererHandle, window, generation, true);
                } else {
                    releaseGamepad(client);
                    disableClipboard(client);
                    // Activity sends disable on blur/pause; it no longer owns focused UI callbacks.
                    if (focusedClient == client) focusedClient = null;
                }
                break;
            case CLIPBOARD_ANNOUNCE:
                if (clipboardOwner == client) renderer.clipboardAnnounce(rendererHandle, window, generation);
                break;
            case CLIPBOARD_TEXT:
                if (clipboardOwner == client) renderer.clipboardText(rendererHandle, window, generation, data.getString("text", ""));
                break;
        }
    }

    private void publishGamepad(Client client, Bundle data) {
        if (gamepad == null || focusedClient != client) return;
        try {
            if (gamepad.update(data.getInt("buttons"), data.getIntArray("axes"), SystemClock.elapsedRealtime()))
                gamepadOwner = client;
            else releaseGamepad(client);
        } catch (IOException error) { disableGamepad(error); }
    }

    private void releaseGamepad(Client client) {
        if (client == null || gamepadOwner != client) return;
        gamepadOwner = null;
        if (gamepad != null) try { gamepad.neutral(SystemClock.elapsedRealtime()); }
        catch (IOException error) { disableGamepad(error); }
    }

    private void disableGamepad(IOException error) {
        handler.removeCallbacks(gamepadHeartbeat);
        gamepadOwner = null;
        GamepadPublisher previous = gamepad;
        gamepad = null;
        if (previous != null) try { previous.close(); }
        catch (IOException closeError) { Log.w("EmbeddedX11", "Cannot close gamepad state", closeError); }
        if (error != null) {
            gamepadError = error.getMessage();
            Log.e("EmbeddedX11", "Gamepad bridge unavailable", error);
            for (Client client : new ArrayList<>(clients.values())) sendStatus(client);
        }
    }

    private void disableClipboard(Client client) {
        if (clipboardOwner != client) return;
        clipboardOwner = null;
        if (rendererHandle != 0) renderer.clipboardEnabled(rendererHandle, client.window, client.generation, false);
    }

    private void forwardCallback(int what, long window, long generation, String text) {
        Client owner = what == IME_RESET ? focusedClient : clipboardOwner;
        if (owner == null) return;
        long lease = owner.lease;
        handler.post(() -> {
            if (stopping || !rendererConnected || owner != focusedClient
                    || (what != IME_RESET && owner != clipboardOwner) || lease == 0 || owner.lease != lease
                    || owner.readOnly || owner.window != window || owner.generation != generation) return;
            Message message = Message.obtain(null, what, owner.token, 0);
            Bundle data = new Bundle();
            data.putString("session", session); data.putLong("window", window); data.putLong("generation", generation);
            data.putLong("surfaceId", owner.surface); data.putString("text", text);
            message.setData(data);
            try { owner.reply.send(message); }
            catch (RemoteException dead) { removeClient(owner.reply.getBinder(), owner.token); }
        });
    }

    private static int dimension(Bundle data, String name) { return Math.max(1, Math.min(16384, data.getInt(name))); }

    private void sendWindows(Client client) {
        Message response = Message.obtain(null, WINDOWS, client.token, 0);
        Bundle data = new Bundle();
        data.putString("session", session);
        data.putParcelableArrayList("windows", new ArrayList<>(windows.values()));
        response.setData(data);
        try { client.reply.send(response); }
        catch (RemoteException dead) { removeClient(client.reply.getBinder(), client.token); }
    }

    private void connectRenderer() {
        if (!ready || stopping || connecting || rendererConnected) return;
        // The old Xorg endpoint's HUP clears global conn_fd. Never replace it before that teardown.
        if (CmdEntryPoint.connected()) {
            if (!connectionRetryScheduled) {
                connectionRetryScheduled = true;
                handler.postDelayed(() -> {
                    connectionRetryScheduled = false;
                    connectRenderer();
                }, 100);
            }
            return;
        }
        connecting = true;
        try (ParcelFileDescriptor fd = entry.getXConnection()) {
            if (fd == null) throw new IOException("Native renderer socket was not created");
            renderer.connect(rendererHandle, fd.detachFd());
        } catch (IOException | RuntimeException error) { fail(error); }
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
            try {
                gamepad = new GamepadPublisher(new File(tmp.getParentFile(), "gamepad"),
                        Process.myPid(), SystemClock.elapsedRealtime());
                handler.postDelayed(gamepadHeartbeat, 500);
            } catch (IOException gamepadFailure) { disableGamepad(gamepadFailure); }
            entry = new CmdEntryPoint(this);
            renderer = new RootlessNative(new RootlessNative.Listener() {
                @Override public void window(long window, long generation, int width, int height, String title, boolean present) {
                    handler.post(() -> {
                        if (stopping) return;
                        Long latest = generations.get(window);
                        if (latest != null && generation < latest) return;
                        Bundle old = windows.get(window);
                        if (present) {
                            generations.put(window, generation);
                            if (old != null && old.getLong("generation") != generation) {
                                retireWindow(old);
                                for (Client client : clients.values())
                                    if (client.window == window && client.generation == old.getLong("generation")) release(client);
                                old = null;
                            }
                            boolean firstAppearance = window != 0 && announcedWindows.add(window + ":" + generation);
                            Bundle info = old == null ? new Bundle() : new Bundle(old);
                            info.putLong("window", window); info.putLong("generation", generation);
                            info.putInt("width", width); info.putInt("height", height); info.putString("title", title);
                            windows.put(window, info);
                            if (firstAppearance) openNewWindow(info);
                            else if (window != 0 && info.containsKey("popupState")) updateWindowNotification(info);
                        } else {
                            generations.put(window, generation);
                            if (old != null && old.getLong("generation") == generation) {
                                windows.remove(window);
                                retireWindow(old);
                            }
                            for (Client client : clients.values())
                                if (client.window == window && client.generation == generation) release(client);
                        }
                        broadcastWindows();
                    });
                }
                @Override public void clipboardText(long window, long generation, String text) {
                    forwardCallback(CLIPBOARD_RECEIVED, window, generation, text);
                }
                @Override public void clipboardRequest(long window, long generation) {
                    forwardCallback(CLIPBOARD_REQUEST, window, generation, null);
                }
                @Override public void imeReset(long window, long generation) {
                    forwardCallback(IME_RESET, window, generation, null);
                }
                @Override public void error(String message) { handler.post(() -> setState("X11: " + message)); }
                @Override public void connected(boolean connected) {
                    handler.post(() -> {
                        rendererConnected = connected; connecting = false;
                        if (!connected) releaseGamepad(gamepadOwner);
                        if (!stopping) {
                            setState(connected ? "Running :9 · independent application windows" : "X11 renderer disconnected");
                            if (!connected && ready) handler.postDelayed(() -> connectRenderer(), 250);
                        }
                    });
                }
            });
            rendererHandle = renderer.create();
            if (rendererHandle == 0) throw new IOException("Rootless renderer initialization failed");
            configureDisplay();
            // JNI obtains AChoreographer and starts Xorg's thread: this call belongs on main Looper.
            if (!entry.start(new String[]{":9", "-nolisten", "tcp", "-auth", authority.getAbsolutePath(), "-noreset"}))
                throw new IOException("Native X11 initialization failed");
            setState("Starting authenticated X11 display :9…");
            new Thread(() -> awaitReady(new File(tmp, ".X11-unix/X9"), cookie), "X11-ready").start();
        } catch (Exception | LinkageError error) { fail(error); }
    }

    private void configureDisplay() {
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        int edge = Math.max(metrics.widthPixels, metrics.heightPixels);
        int refreshRate = 60;
        android.hardware.display.DisplayManager manager = getSystemService(android.hardware.display.DisplayManager.class);
        android.view.Display display = manager == null ? null : manager.getDisplay(android.view.Display.DEFAULT_DISPLAY);
        if (display != null) {
            android.graphics.Point size = new android.graphics.Point();
            display.getRealSize(size);
            edge = Math.max(edge, Math.max(size.x, size.y));
            for (android.view.Display.Mode mode : display.getSupportedModes())
                edge = Math.max(edge, Math.max(mode.getPhysicalWidth(), mode.getPhysicalHeight()));
            float rate = display.getRefreshRate();
            if (Float.isFinite(rate) && rate > 0) refreshRate = Math.round(rate);
        }
        edge = Math.max(64, Math.min(8192, edge > 0 ? edge : 1280));
        refreshRate = Math.max(1, Math.min(240, refreshRate));
        // One fixed square virtual canvas trades root-buffer memory for rotation/fold headroom.
        // Activities only resize their own X windows; attaching or readonly viewing never resizes root.
        renderer.configureDisplay(rendererHandle, edge, edge, refreshRate);
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
                    connectRenderer();
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
        disableGamepad(null);
        for (Bundle info : windows.values()) retireWindow(info);
        windows.clear();
        broadcastWindows();
        if (rendererHandle != 0) {
            renderer.destroy(rendererHandle);
            rendererHandle = 0;
            rendererConnected = false;
        }
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
        disableGamepad(null);
        for (Bundle info : windows.values()) retireWindow(info);
        windows.clear();
        handler.removeCallbacksAndMessages(null);
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
