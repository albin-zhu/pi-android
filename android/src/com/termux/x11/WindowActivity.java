package com.termux.x11;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import dev.zfold.assistant.SystemWindowNavigation;
import android.view.KeyEvent;
import android.view.WindowManager;
import java.util.ArrayList;

/** A document task identifies one X window incarnation, not a second X server. */
public final class WindowActivity extends Activity implements RootlessClient.Listener {
    private RootlessClient client;
    private WindowView display;
    private WindowInputControls inputControls;
    private int restoreInputMode = -1;
    private boolean gamepadReady;
    private String gamepadError;
    private String session;
    private long window, generation, surfaceId;
    private boolean readOnly, visible, ready, attached, keyboardRequested;
    private android.content.ClipboardManager clipboard;
    private boolean clipboardListening;
    private String lastClipboard;
    private final android.content.ClipboardManager.OnPrimaryClipChangedListener clipboardListener = this::announceClipboard;

    static Intent intent(Context context, String session, Bundle window, boolean readOnly) {
        Uri identity = new Uri.Builder().scheme("x11-window").authority(session)
                .appendPath(Long.toUnsignedString(window.getLong("window")))
                .appendPath(Long.toUnsignedString(window.getLong("generation"))).build();
        return new Intent(context, WindowActivity.class).setData(identity)
                .putExtra("session", session).putExtras(window).putExtra("readOnly", readOnly)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    static void open(Context context, String session, Bundle window, boolean readOnly) {
        context.startActivity(intent(context, session, window, readOnly),
                SystemWindowNavigation.popupOptions(context));
    }

    static void openKeyboard(Context context, String session, Bundle window) {
        context.startActivity(intent(context, session, window, false).putExtra("showKeyboard", true),
                SystemWindowNavigation.popupOptions(context));
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        session = getIntent().getStringExtra("session");
        window = getIntent().getLongExtra("window", 0); generation = getIntent().getLongExtra("generation", 0);
        readOnly = getIntent().getBooleanExtra("readOnly", false);
        if (session == null) { finishAndRemoveTask(); return; }
        getWindow().setSoftInputMode(readOnly ? WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING : WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        keyboardRequested = getIntent().getBooleanExtra("showKeyboard", false) && !readOnly;
        getIntent().removeExtra("showKeyboard");
        display = new WindowView(this, readOnly);
        if (readOnly) setContentView(display);
        else {
            inputControls = new WindowInputControls(this, display, this::gamepad);
            restoreInputMode = saved == null ? -1 : saved.getInt("inputLayout", WindowInputControls.NONE);
            setContentView(inputControls);
        }
        title(getIntent().getStringExtra("title"));
        client = new RootlessClient(this, this, readOnly);
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (inputControls != null) state.putInt("inputLayout", inputControls.mode());
    }
    private void gamepad(int buttons, int[] axes) {
        if (!attached || readOnly || !gamepadReady) return;
        boolean neutral = buttons == 0;
        for (int axis : axes) if (axis != 0) neutral = false;
        if (!hasWindowFocus() && !neutral) return;
        Bundle data = new Bundle();
        data.putInt("buttons", buttons); data.putIntArray("axes", axes);
        command(X11Service.GAMEPAD, data);
    }
    private void releaseControls() {
        if (inputControls != null) inputControls.releaseAll();
    }
    private void updateControls() {
        if (inputControls == null) return;
        boolean active = attached && visible && hasWindowFocus();
        inputControls.availability(active, gamepadReady, gamepadError);
        if (active && restoreInputMode >= 0
                && (restoreInputMode != WindowInputControls.XBOX || gamepadReady || gamepadError != null)) {
            int restore = restoreInputMode; restoreInputMode = -1;
            inputControls.setMode(restore);
        }
    }
    private void title(String title) {
        if (title == null || title.isEmpty()) title = window == 0 ? "X11 :9 · 只读桌面" : "X11 应用";
        setTitle(title);
        setTaskDescription(new ActivityManager.TaskDescription(title));
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        boolean nextReadOnly = intent.getBooleanExtra("readOnly", false);
        if (nextReadOnly != readOnly) {
            detachSurface();
            setIntent(intent);
            recreate();
            return;
        }
        title(intent.getStringExtra("title"));
        setIntent(intent);
        reportPopup();
        keyboardRequested = intent.getBooleanExtra("showKeyboard", false) && !readOnly;
        intent.removeExtra("showKeyboard");
        showRequestedKeyboard();
    }
    @Override protected void onStart() { super.onStart(); visible = true; if (client != null) client.start(); }
    @Override protected void onStop() {
        SystemWindowNavigation.remember(this);
        if (!isChangingConfigurations()) {
            Bundle outcome = new Bundle(); outcome.putBoolean("visible", false);
            send(X11Service.POPUP_OUTCOME, outcome);
        }
        detachSurface(); visible = false; ready = false;
        updateControls();
        if (client != null) client.stop();
        super.onStop();
    }
    @Override protected void onResume() {
        super.onResume();
        if (hasWindowFocus() && !readOnly) command(X11Service.FOCUS, new Bundle());
        clipboardFocus(hasWindowFocus());
        reportPopup();
    }
    @Override protected void onPause() {
        releaseControls();
        clipboardFocus(false);
        if (display != null) display.releaseInput();
        super.onPause();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) releaseControls();
        if (focused && !readOnly) command(X11Service.FOCUS, new Bundle());
        clipboardFocus(focused);
        if (focused) reportPopup();
        if (focused) showRequestedKeyboard();
        updateControls();
    }
    private void reportPopup() {
        getWindow().getDecorView().post(() -> {
            if (!visible || isFinishing()) return;
            Bundle outcome = new Bundle();
            outcome.putBoolean("visible", true);
            outcome.putBoolean("popup", SystemWindowNavigation.isPopup(this));
            send(X11Service.POPUP_OUTCOME, outcome);
        });
    }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        releaseControls();
        SystemWindowNavigation.remember(this);
        reportPopup();
    }
    @Override public void onMultiWindowModeChanged(boolean multiWindow, Configuration config) {
        super.onMultiWindowModeChanged(multiWindow, config);
        reportPopup();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (display != null && display.handleKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }
    @Override public void onBackPressed() {
        if (inputControls == null || !inputControls.hideLayout()) finish();
    }
    boolean attached() { return attached; }
    void command(int what, Bundle data) {
        if (!attached || readOnly && what != X11Service.RELEASE) return;
        send(what, data);
    }
    private boolean send(int what, Bundle data) {
        data.putString("session", session); data.putLong("window", window); data.putLong("generation", generation);
        data.putLong("surfaceId", surfaceId);
        return client != null && client.send(what, data);
    }
    void attachSurface() {
        if (!visible || !ready || display == null || !display.getHolder().getSurface().isValid()) return;
        int width = display.getHolder().getSurfaceFrame().width(), height = display.getHolder().getSurfaceFrame().height();
        if (width <= 0 || height <= 0) return;
        if (attached) { releaseControls(); clipboardFocus(false); display.releaseInput(); }
        ++surfaceId;
        Bundle data = new Bundle(); data.putParcelable("surface", display.getHolder().getSurface());
        data.putInt("width", width); data.putInt("height", height); data.putBoolean("readOnly", readOnly);
        attached = send(X11Service.ATTACH, data);
        if (attached && !readOnly) {
            Bundle size = new Bundle(); size.putInt("width", width); size.putInt("height", height);
            command(X11Service.RESIZE, size);
            if (hasWindowFocus()) command(X11Service.FOCUS, new Bundle());
            clipboardFocus(hasWindowFocus());
        }
        showRequestedKeyboard();
        updateControls();
    }
    private void showRequestedKeyboard() {
        if (!keyboardRequested || readOnly || !attached || !hasWindowFocus()) return;
        display.post(() -> {
            if (!keyboardRequested || readOnly || !attached || !hasWindowFocus()) return;
            keyboardRequested = false;
            display.showKeyboard();
        });
    }
    void detachSurface() {
        if (!attached) return;
        releaseControls();
        clipboardFocus(false);
        display.releaseInput(); command(X11Service.RELEASE, new Bundle()); attached = false;
        display.resetIme();
        updateControls();
    }
    @Override public void reply(int what, Bundle data) {
        if (!session.equals(data.getString("session"))) { finishAndRemoveTask(); return; }
        if (what == X11Service.IME_RESET) {
            if (!readOnly && attached && hasWindowFocus()
                    && data.getLong("window") == window && data.getLong("generation") == generation
                    && data.getLong("surfaceId") == surfaceId) display.resetIme();
            return;
        }
        if (what == X11Service.CLIPBOARD_RECEIVED || what == X11Service.CLIPBOARD_REQUEST) {
            if (readOnly || !attached || !clipboardListening || !hasWindowFocus()
                    || data.getLong("window") != window || data.getLong("generation") != generation
                    || data.getLong("surfaceId") != surfaceId) return;
            if (what == X11Service.CLIPBOARD_RECEIVED) {
                String text = data.getString("text");
                if (text != null && !text.equals(lastClipboard)) {
                    lastClipboard = text;
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("X11", text));
                }
            } else {
                android.content.ClipData clip = clipboard.getPrimaryClip();
                CharSequence text = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(this) : "";
                Bundle reply = new Bundle(); reply.putString("text", text == null ? "" : text.toString());
                command(X11Service.CLIPBOARD_TEXT, reply);
            }
            return;
        }
        if (what == X11Service.STATUS) {
            if (data.getBoolean("stopping")) { finishAndRemoveTask(); return; }
            reportPopup();
            boolean wasReady = ready;
            boolean wasGamepadReady = gamepadReady;
            gamepadReady = data.getBoolean("gamepadReady");
            gamepadError = data.getString("gamepadError");
            if (wasGamepadReady && !gamepadReady) releaseControls();
            ready = data.getBoolean("rendererConnected") && !data.getBoolean("stopping");
            if (!ready) { clipboardFocus(false); attached = false; display.releaseInput(); }
            updateControls();
            if (ready && !wasReady) attachSurface();
        } else if (what == X11Service.WINDOWS && window != 0) {
            ArrayList<Bundle> windows = data.getParcelableArrayList("windows");
            if (windows != null) for (Bundle info : windows) {
                if (info.getLong("window") == window && info.getLong("generation") == generation) { title(info.getString("title")); return; }
            }
            finishAndRemoveTask();
        }
    }
    @Override public void disconnected() {
        releaseControls();
        clipboardFocus(false); attached = false; ready = false;
        if (display != null) display.releaseInput();
        gamepadReady = false;
        updateControls();
    }
    private void clipboardFocus(boolean focused) {
        if (readOnly) return;
        boolean enable = focused && visible && attached && hasWindowFocus();
        if (enable == clipboardListening) return;
        clipboardListening = enable;
        if (clipboard == null) clipboard = getSystemService(android.content.ClipboardManager.class);
        if (enable) clipboard.addPrimaryClipChangedListener(clipboardListener);
        else {
            clipboard.removePrimaryClipChangedListener(clipboardListener);
            lastClipboard = null;
        }
        Bundle data = new Bundle(); data.putBoolean("enabled", enable);
        command(X11Service.CLIPBOARD_ENABLED, data);
        if (enable) announceClipboard();
    }
    private void announceClipboard() {
        if (!readOnly && attached && clipboardListening && hasWindowFocus())
            command(X11Service.CLIPBOARD_ANNOUNCE, new Bundle());
    }
    @Override public void onPictureInPictureModeChanged(boolean pip, Configuration config) {
        super.onPictureInPictureModeChanged(pip, config);
        reportPopup();
    }
}
