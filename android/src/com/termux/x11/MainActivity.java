package com.termux.x11;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ToggleButton;
import dev.zfold.assistant.NavigableActivity;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/** Independent general-purpose X11 display; it never owns a Pi/chat terminal session. */
public final class MainActivity extends NavigableActivity {
    private static final AtomicInteger NEXT_TOKEN = new AtomicInteger();
    private final Messenger replies = new Messenger(new Handler(Looper.getMainLooper(), this::handleReply));
    private Messenger server;
    private LorieView display;
    private TextView status;
    private Button startButton, stopButton;
    private ToggleButton ctrlButton, altButton;
    private boolean bound, visible, running, stopping, attachmentRequested;
    private boolean desiredRunning = true;
    private int token;

    public static void open(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    // JNI_OnLoad registers this even though the embedded UI does not use hidden windowing APIs.
    private static native void nativeSetLaunchWindowingModeFreeform(Object options);

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!visible) return;
            server = new Messenger(binder);
            send(X11Service.WATCH);
        }
        @Override public void onServiceDisconnected(ComponentName name) { serverExited(); }
        @Override public void onBindingDied(ComponentName name) {
            serverExited();
            if (bound) { unbindService(this); bound = false; }
        }
        @Override public void onNullBinding(ComponentName name) {
            serverExited();
            status.setText("X11 service did not provide its display connection");
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) desiredRunning = savedInstanceState.getBoolean("running", true);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        startButton = button(toolbar, "Start / Show", v -> startDisplay());
        stopButton = button(toolbar, "Stop X11", v -> stopDisplay());
        button(toolbar, "Keyboard", v -> { if (display != null) display.showKeyboard(); });
        button(toolbar, "Leave view", v -> finish());
        root.addView(scroll(toolbar), new LinearLayout.LayoutParams(-1, -2));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setText("Starting X11 display :9…");
        status.setPadding(12, 4, 12, 4);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout keys = new LinearLayout(this);
        ctrlButton = modifier(keys, "Ctrl");
        altButton = modifier(keys, "Alt");
        ctrlButton.setOnCheckedChangeListener((button, checked) -> { if (display != null) display.setCtrl(checked); });
        altButton.setOnCheckedChangeListener((button, checked) -> { if (display != null) display.setAlt(checked); });
        key(keys, "Esc", KeyEvent.KEYCODE_ESCAPE);
        key(keys, "Tab", KeyEvent.KEYCODE_TAB);
        key(keys, "↑", KeyEvent.KEYCODE_DPAD_UP);
        key(keys, "↓", KeyEvent.KEYCODE_DPAD_DOWN);
        key(keys, "←", KeyEvent.KEYCODE_DPAD_LEFT);
        key(keys, "→", KeyEvent.KEYCODE_DPAD_RIGHT);
        key(keys, "Backspace", KeyEvent.KEYCODE_DEL);
        key(keys, "Enter", KeyEvent.KEYCODE_ENTER);
        button(keys, "Right click", v -> { if (display != null) display.clickMouse(3); });
        button(keys, "Middle click", v -> { if (display != null) display.clickMouse(2); });
        root.addView(scroll(keys), new LinearLayout.LayoutParams(-1, -2));
        try {
            display = new LorieView(this);
            root.addView(display, new LinearLayout.LayoutParams(-1, 0, 1));
        } catch (LinkageError error) {
            desiredRunning = false;
            status.setText("X11 native library unavailable: " + error.getMessage());
            startButton.setEnabled(false);
        }
        TextView hint = new TextView(this);
        hint.setTextColor(Color.LTGRAY);
        hint.setText("Touch to click/drag · two fingers scroll · leaving keeps X11 running");
        hint.setPadding(12, 2, 12, 4);
        root.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        token = NEXT_TOKEN.incrementAndGet();
        attachmentRequested = false;
        if (display == null) return;
        bindDisplay();
        if (desiredRunning) startDisplay();
    }

    private void bindDisplay() {
        if (!bound) bound = bindService(new Intent(this, X11Service.class), connection, Context.BIND_AUTO_CREATE);
        if (!bound) status.setText("Cannot bind X11 service");
    }

    private void startDisplay() {
        if (stopping || display == null) return;
        desiredRunning = true;
        try {
            X11Service.start(this);
            bindDisplay();
            status.setText(running ? "Running :9" : "Starting X11 display :9…");
            display.requestFocus();
            requestAttachment();
        } catch (RuntimeException error) {
            desiredRunning = false;
            status.setText("Cannot start X11: " + error.getMessage());
        }
    }

    private void stopDisplay() {
        if (stopping || display == null) return;
        desiredRunning = false;
        stopping = true;
        running = false;
        attachmentRequested = false;
        display.disconnect();
        startButton.setEnabled(false);
        stopButton.setEnabled(false);
        status.setText("Stopping X11…");
        if (server != null) send(X11Service.STOP);
        else {
            // Same explicit action used by the notification. It never starts a second Xorg thread.
            startService(new Intent(this, X11Service.class).setAction(X11Service.ACTION_STOP));
        }
    }

    private void requestAttachment() {
        if (!visible || !running || stopping || display == null || display.isConnected() || attachmentRequested || server == null) return;
        attachmentRequested = true;
        send(X11Service.ATTACH);
    }

    private void send(int command) {
        if (server == null) return;
        Message request = Message.obtain(null, command, token, 0);
        request.replyTo = replies;
        try { server.send(request); }
        catch (RemoteException error) { serverExited(); }
    }

    private boolean handleReply(Message response) {
        Bundle data = response.getData();
        data.setClassLoader(ParcelFileDescriptor.class.getClassLoader());
        ParcelFileDescriptor descriptor = response.what == X11Service.CONNECTION ? data.getParcelable("fd") : null;
        try {
            if (!visible || response.arg1 != token || display == null) return true;
            if (response.what == X11Service.STATUS) {
                running = response.arg2 == 1;
                boolean serverStopping = data.getBoolean("stopping");
                if (serverStopping) {
                    desiredRunning = false;
                    stopping = true;
                    display.disconnect();
                    attachmentRequested = false;
                }
                status.setText(data.getString("status", "X11"));
                startButton.setEnabled(!stopping && !data.getBoolean("starting"));
                stopButton.setEnabled(!stopping && (running || data.getBoolean("starting")));
                requestAttachment();
            } else if (response.what == X11Service.CONNECTION && descriptor != null) {
                attachmentRequested = false;
                if (!stopping && running && !display.isConnected()) display.attachConnection(descriptor);
            }
        } finally {
            if (descriptor != null) try { descriptor.close(); } catch (IOException ignored) { }
        }
        return true;
    }

    private void serverExited() {
        server = null;
        running = false;
        attachmentRequested = false;
        desiredRunning = false;
        if (display != null) display.disconnect();
        if (!stopping) status.setText("X11 server exited. Tap Start / Show to restart.");
        stopping = false;
        startButton.setEnabled(display != null);
        stopButton.setEnabled(false);
    }

    /** Called by native ALooper HUP handling, on the UI thread. */
    public void clientConnectedStateChanged() {
        attachmentRequested = false;
        if (visible && !stopping) {
            status.setText("X11 renderer disconnected");
            requestAttachment();
        }
    }
    void onRendererReady() {
        if (visible && running && !stopping) status.setText("Running :9 · display connected");
    }
    void clearModifiers() {
        if (ctrlButton != null) ctrlButton.setChecked(false);
        if (altButton != null) altButton.setChecked(false);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (display != null && display.handleKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onStop() {
        visible = false;
        if (display != null) display.disconnect();
        send(X11Service.DETACH);
        server = null;
        attachmentRequested = false;
        if (bound) { unbindService(connection); bound = false; }
        super.onStop();
    }
    @Override protected void onDestroy() {
        if (display != null) display.destroy();
        super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("running", desiredRunning);
        super.onSaveInstanceState(out);
    }

    private HorizontalScrollView scroll(LinearLayout row) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row);
        return scroll;
    }
    private Button button(LinearLayout row, String label, View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(label);
        button.setFocusable(false);
        button.setOnClickListener(action);
        row.addView(button, new LinearLayout.LayoutParams(-2, -2));
        return button;
    }
    private ToggleButton modifier(LinearLayout row, String label) {
        ToggleButton button = new ToggleButton(this);
        button.setTextOn(label + " ON"); button.setTextOff(label); button.setChecked(false);
        button.setFocusable(false);
        row.addView(button, new LinearLayout.LayoutParams(-2, -2));
        return button;
    }
    private void key(LinearLayout row, String label, int code) {
        button(row, label, v -> { if (display != null) display.tapKey(code); });
    }
}
