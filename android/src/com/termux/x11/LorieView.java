package com.termux.x11;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.PixelFormat;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.ParcelFileDescriptor;
import android.text.Editable;
import android.text.InputType;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.util.SparseIntArray;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import dalvik.annotation.optimization.CriticalNative;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Surface and JNI ABI for the pinned Termux:X11 engine; all JNI input runs on main Looper. */
public final class LorieView extends SurfaceView implements SurfaceHolder.Callback {
    // Field name and descriptor are looked up by nativeInit, not ordinary Java reflection.
    public final MainActivity activity;
    private long nativePtr;
    private final boolean readOnly;
    private boolean attached;
    private int desktopWidth, desktopHeight;
    private GeometryMonitor geometryMonitor;
    private int mouseButtons;
    private boolean touchDown, scrolling, ctrl, alt;
    private float scrollX, scrollY;
    private int syncSerial;
    private final SparseIntArray heldKeys = new SparseIntArray();
    private final ClipboardManager clipboard;
    private final ClipboardManager.OnPrimaryClipChangedListener clipboardListener = this::announceClipboard;
    private final SpannableStringBuilder composing = new SpannableStringBuilder();
    private volatile Viewport viewport;
    private String lastClipboard;

    private static final class Viewport {
        final int x, y, width, height;
        final float left, top, sourceWidth, sourceHeight;
        Viewport(int x, int y, int width, int height, float left, float top, float sourceWidth, float sourceHeight) {
            this.x = x; this.y = y; this.width = width; this.height = height;
            this.left = left; this.top = top; this.sourceWidth = sourceWidth; this.sourceHeight = sourceHeight;
        }
    }

    public LorieView(MainActivity activity, boolean readOnly) {
        super(activity);
        this.activity = activity;
        this.readOnly = readOnly;
        clipboard = activity.getSystemService(ClipboardManager.class);
        System.loadLibrary("Xlorie");
        nativePtr = nativeInit();
        setFocusable(!readOnly);
        setFocusableInTouchMode(!readOnly);
        setPointerIcon(PointerIcon.getSystemIcon(activity, PointerIcon.TYPE_NULL));
        getHolder().setFormat(PixelFormat.OPAQUE);
        getHolder().addCallback(this);
        setRendererZoom(nativePtr, 100);
        setFollowCursorPan(nativePtr, false);
    }

    public boolean isConnected() { return nativePtr != 0 && connected(nativePtr); }
    private boolean canSendInput() { return !readOnly && attached && isConnected(); }
    int desktopWidth() { return desktopWidth; }
    int desktopHeight() { return desktopHeight; }

    void attachConnection(ParcelFileDescriptor descriptor) {
        if (nativePtr == 0) throw new IllegalStateException("Display view is destroyed");
        if (attached) disconnect();
        connect(nativePtr, descriptor.detachFd()); // Native owns/closes this descriptor and pumps it with ALooper.
        attached = true;
        setClipboardSyncEnabled(nativePtr, !readOnly, false);
        clipboard.removePrimaryClipChangedListener(clipboardListener);
        if (!readOnly) clipboard.addPrimaryClipChangedListener(clipboardListener);
        else {
            geometryMonitor = new GeometryMonitor();
            geometryMonitor.start();
        }
        updateSurface();
        sendSync(nativePtr, ++syncSerial);
    }

    void disconnect() {
        if (nativePtr == 0) return;
        releaseInput();
        attached = false;
        if (geometryMonitor != null) { geometryMonitor.close(); geometryMonitor = null; }
        desktopWidth = desktopHeight = 0;
        viewport = null;
        if (isConnected()) setClipboardSyncEnabled(nativePtr, false, false);
        clipboard.removePrimaryClipChangedListener(clipboardListener);
        connect(nativePtr, -1);
        surfaceChanged(nativePtr, null);
        ++syncSerial;
        getContext().getSystemService(InputMethodManager.class).hideSoftInputFromWindow(getWindowToken(), 0);
        resetIme();
    }

    void destroy() {
        if (nativePtr == 0) return;
        disconnect();
        surfaceChanged(nativePtr, null);
        nativeDestroy(nativePtr);
        nativePtr = 0;
        getHolder().removeCallback(this);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) { updateSurface(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { updateSurface(); }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        if (nativePtr != 0) surfaceChanged(nativePtr, null);
    }

    private void updateSurface() {
        if (nativePtr == 0 || !attached || !getHolder().getSurface().isValid()) return;
        int width = getHolder().getSurfaceFrame().width();
        int height = getHolder().getSurfaceFrame().height();
        if (width <= 0 || height <= 0) return;
        if (readOnly && (desktopWidth <= 0 || desktopHeight <= 0)) return;
        surfaceChanged(nativePtr, getHolder().getSurface());
        if (readOnly) {
            // expectedW/H must match the actual buffer; changing these only scales local rendering.
            float scale = Math.min((float) width / desktopWidth, (float) height / desktopHeight);
            int fittedWidth = Math.max(1, Math.round(desktopWidth * scale));
            int fittedHeight = Math.max(1, Math.round(desktopHeight * scale));
            setViewport(nativePtr, (width - fittedWidth) / 2, (height - fittedHeight) / 2,
                    fittedWidth, fittedHeight, desktopWidth, desktopHeight, 0);
        } else {
            setViewport(nativePtr, 0, 0, width, height, width, height, 0);
            sendWindowChange(nativePtr, width, height, 60, "Android");
        }
    }

    // Renderer thread callback; publish the exact source/destination mapping atomically for input.
    public void setRendererViewport(int x, int y, int width, int height, float left, float top, float sourceWidth, float sourceHeight) {
        if (readOnly) return;
        viewport = new Viewport(x, y, width, height, left, top, sourceWidth, sourceHeight);
    }
    public void onSyncReply(int serial) {
        if (nativePtr != 0 && attached && serial == syncSerial && (!readOnly || desktopWidth > 0))
            activity.onRendererReady();
    }

    private void movePointer(float x, float y) {
        if (!canSendInput()) return;
        Viewport v = viewport;
        if (v != null && v.width > 0 && v.height > 0) {
            x = v.left + Math.max(0, Math.min(v.width, x - v.x)) * v.sourceWidth / v.width;
            y = v.top + Math.max(0, Math.min(v.height, y - v.y)) * v.sourceHeight / v.height;
        }
        sendMouseEvent(nativePtr, x, y, 0, false, false);
    }
    private void button(int which, boolean down) { if (canSendInput()) sendMouseEvent(nativePtr, 0, 0, which, down, true); }
    void clickMouse(int which) { if (canSendInput()) { button(which, true); button(which, false); } }

    private boolean hardwareMouse(MotionEvent event) {
        return event.isFromSource(InputDevice.SOURCE_MOUSE) || event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE;
    }
    private boolean mouseEvent(MotionEvent event) {
        if (!canSendInput()) return true;
        movePointer(event.getX(), event.getY());
        int buttons = event.getActionMasked() == MotionEvent.ACTION_CANCEL ? 0 : event.getButtonState();
        int changed = buttons ^ mouseButtons;
        if ((changed & MotionEvent.BUTTON_PRIMARY) != 0) button(1, (buttons & MotionEvent.BUTTON_PRIMARY) != 0);
        if ((changed & MotionEvent.BUTTON_TERTIARY) != 0) button(2, (buttons & MotionEvent.BUTTON_TERTIARY) != 0);
        if ((changed & MotionEvent.BUTTON_SECONDARY) != 0) button(3, (buttons & MotionEvent.BUTTON_SECONDARY) != 0);
        mouseButtons = buttons;
        if (event.getActionMasked() == MotionEvent.ACTION_SCROLL)
            sendMouseEvent(nativePtr, -120 * event.getAxisValue(MotionEvent.AXIS_HSCROLL), -120 * event.getAxisValue(MotionEvent.AXIS_VSCROLL), 4, false, true);
        return true;
    }
    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (readOnly) return true;
        return hardwareMouse(event) ? mouseEvent(event) : super.onGenericMotionEvent(event);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (readOnly) return true;
        requestFocus();
        if (hardwareMouse(event)) return mouseEvent(event);
        if (!canSendInput()) return true;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scrolling = false;
                movePointer(event.getX(), event.getY());
                button(1, true); touchDown = true;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (touchDown) { button(1, false); touchDown = false; }
                scrolling = true;
                scrollX = event.getX(); scrollY = event.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                if (scrolling) {
                    sendMouseEvent(nativePtr, (scrollX - event.getX()) * 5, (scrollY - event.getY()) * 5, 4, false, true);
                    scrollX = event.getX(); scrollY = event.getY();
                } else movePointer(event.getX(), event.getY());
                break;
            case MotionEvent.ACTION_POINTER_UP:
                int remaining = event.getActionIndex() == 0 ? 1 : 0;
                scrollX = event.getX(remaining); scrollY = event.getY(remaining);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (touchDown) { button(1, false); touchDown = false; }
                scrolling = false;
                break;
        }
        return true;
    }

    boolean handleKey(KeyEvent event) {
        if (!canSendInput()) return false;
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK && event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            // Some Android mouse drivers expose the secondary button as BACK, not MotionEvent.
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            if (down) mouseButtons |= MotionEvent.BUTTON_SECONDARY;
            else mouseButtons &= ~MotionEvent.BUTTON_SECONDARY;
            button(3, down);
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE)
            return false;
        if (event.getAction() == KeyEvent.ACTION_MULTIPLE) {
            if (event.getCharacters() != null) sendText(event.getCharacters());
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN && event.getAction() != KeyEvent.ACTION_UP) return false;
        if (event.getScanCode() == 0 && (code <= 0 || code >= 304)) return false;
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (down) heldKeys.put(code, event.getScanCode()); else heldKeys.delete(code);
        return sendKeyEvent(nativePtr, event.getScanCode(), code, down);
    }
    void tapKey(int code) {
        if (!canSendInput()) return;
        sendKeyEvent(nativePtr, 0, code, true);
        sendKeyEvent(nativePtr, 0, code, false);
    }
    void setCtrl(boolean down) {
        if (readOnly) return;
        if (ctrl == down) return;
        ctrl = down;
        if (canSendInput()) sendKeyEvent(nativePtr, 0, KeyEvent.KEYCODE_CTRL_LEFT, down);
    }
    void setAlt(boolean down) {
        if (readOnly) return;
        if (alt == down) return;
        alt = down;
        if (canSendInput()) sendKeyEvent(nativePtr, 0, KeyEvent.KEYCODE_ALT_LEFT, down);
    }
    void releaseInput() {
        if (canSendInput()) {
            for (int i = 0; i < heldKeys.size(); i++) sendKeyEvent(nativePtr, heldKeys.valueAt(i), heldKeys.keyAt(i), false);
            if (touchDown || (mouseButtons & MotionEvent.BUTTON_PRIMARY) != 0) button(1, false);
            if ((mouseButtons & MotionEvent.BUTTON_TERTIARY) != 0) button(2, false);
            if ((mouseButtons & MotionEvent.BUTTON_SECONDARY) != 0) button(3, false);
        }
        heldKeys.clear(); touchDown = false; scrolling = false; mouseButtons = 0;
        setCtrl(false); setAlt(false);
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) releaseInput();
        else if (canSendInput()) announceClipboard();
    }

    private void sendText(CharSequence text) {
        if (!canSendInput() || text == null || text.length() == 0) return;
        if (ctrl || alt) {
            KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toString().toCharArray());
            if (events != null) { for (KeyEvent event : events) handleKey(event); return; }
        }
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int key = c == '\n' || c == '\r' ? KeyEvent.KEYCODE_ENTER :
                    c == '\b' ? KeyEvent.KEYCODE_DEL : c == '\t' ? KeyEvent.KEYCODE_TAB : 0;
            if (key == 0) continue;
            if (i > start) sendTextChunk(text.subSequence(start, i));
            tapKey(key);
            start = i + 1;
        }
        if (start < text.length()) sendTextChunk(text.subSequence(start, text.length()));
    }

    private void sendTextChunk(CharSequence text) {
        if (!canSendInput()) return;
        byte[] utf8 = text.toString().getBytes(StandardCharsets.UTF_8);
        // Native mbrtowc scans to a NUL; do not rely on GetByteArrayElements adding one.
        sendTextEvent(nativePtr, Arrays.copyOf(utf8, utf8.length + 1));
    }

    @Override public boolean onCheckIsTextEditor() { return !readOnly; }
    @Override public InputConnection onCreateInputConnection(EditorInfo info) {
        if (readOnly || nativePtr == 0) return null;
        info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_ACTION_NONE;
        info.initialSelStart = info.initialSelEnd = 0;
        Selection.setSelection(composing, composing.length());
        return new BaseInputConnection(this, true) {
            @Override public Editable getEditable() { return composing; }
            @Override public boolean commitText(CharSequence text, int newCursorPosition) {
                sendText(text); composing.clear(); Selection.setSelection(composing, 0); return true;
            }
            @Override public boolean finishComposingText() {
                if (composing.length() != 0) sendText(composing.toString());
                composing.clear(); Selection.setSelection(composing, 0); return true;
            }
            @Override public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                if (composing.length() != 0) return super.deleteSurroundingText(beforeLength, afterLength);
                for (int i = 0; i < beforeLength; i++) tapKey(KeyEvent.KEYCODE_DEL);
                for (int i = 0; i < afterLength; i++) tapKey(KeyEvent.KEYCODE_FORWARD_DEL);
                return true;
            }
            @Override public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
                if (composing.length() != 0) return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength);
                return deleteSurroundingText(beforeLength, afterLength);
            }
            @Override public boolean sendKeyEvent(KeyEvent event) { return handleKey(event); }
            @Override public boolean performEditorAction(int actionCode) { tapKey(KeyEvent.KEYCODE_ENTER); return true; }
        };
    }
    public void resetIme() {
        composing.clear(); Selection.setSelection(composing, 0);
        if (readOnly || nativePtr == 0) return;
        getContext().getSystemService(InputMethodManager.class).restartInput(this);
    }
    void showKeyboard() {
        if (!canSendInput()) return;
        requestFocus();
        getContext().getSystemService(InputMethodManager.class).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
    }

    private void announceClipboard() {
        if (canSendInput() && hasWindowFocus()) sendClipboardAnnounce(nativePtr);
    }
    public void setClipboardText(String text) {
        if (!canSendInput() || text == null || text.equals(lastClipboard)) return;
        lastClipboard = text;
        clipboard.setPrimaryClip(ClipData.newPlainText("X11", text));
    }
    public void requestClipboard() {
        if (!canSendInput()) return;
        ClipData clip = hasWindowFocus() ? clipboard.getPrimaryClip() : null;
        CharSequence text = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(getContext()) : "";
        sendClipboardEvent(nativePtr, text.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** One authenticated observation socket, never an input client or a second renderer. */
    private final class GeometryMonitor extends Thread {
        private final LocalSocket socket = new LocalSocket();
        private volatile boolean closed;

        GeometryMonitor() { super("X11-preview-geometry"); }

        @Override public void run() {
            try {
                File root = new File(activity.getFilesDir(), "runtime/x11");
                byte[] cookie;
                try (DataInputStream authority = new DataInputStream(new FileInputStream(new File(root, "Xauthority")))) {
                    cookie = X11Geometry.readCookie(authority);
                }
                if (closed) return;
                socket.connect(new LocalSocketAddress(new File(root, "tmp/.X11-unix/X9").getAbsolutePath(),
                        LocalSocketAddress.Namespace.FILESYSTEM));
                if (closed) return;
                socket.setSoTimeout(2000);
                DataInputStream input = new DataInputStream(socket.getInputStream());
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                int window = X11Geometry.authenticate(input, output, cookie);
                X11Geometry.watch(output, window);
                // The initial GetGeometry must arrive promptly; subsequent ConfigureNotify events may be idle.
                boolean first = true;
                while (!closed) {
                    int[] size = X11Geometry.readSize(input, window);
                    if (size == null) continue;
                    if (first) { socket.setSoTimeout(0); first = false; }
                    post(() -> {
                        if (closed || geometryMonitor != this || !attached || nativePtr == 0) return;
                        desktopWidth = size[0]; desktopHeight = size[1];
                        updateSurface();
                        activity.onRendererReady();
                    });
                }
            } catch (IOException error) {
                if (!closed) post(() -> {
                    if (closed || geometryMonitor != this || !attached || nativePtr == 0) return;
                    surfaceChanged(nativePtr, null);
                    desktopWidth = desktopHeight = 0;
                    activity.onGeometryError(error.getMessage());
                });
            } finally {
                try { socket.close(); } catch (IOException ignored) { }
            }
        }

        void close() {
            closed = true;
            try { socket.shutdownInput(); } catch (IOException ignored) { }
            try { socket.shutdownOutput(); } catch (IOException ignored) { }
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    // Complete RegisterNatives table. Keep even currently unused entries: JNI_OnLoad registers all.
    private native long nativeInit();
    private native void nativeDestroy(long ptr);
    private native void surfaceChanged(long ptr, Surface surface);
    private native void setViewport(long ptr, int x, int y, int w, int h, int expectedW, int expectedH, int hidden);
    private native void setRendererZoom(long ptr, int percent);
    private native void setZoomAnchor(long ptr, float sourceX, float sourceY, float fractionX, float fractionY);
    private native void clearZoomAnchor(long ptr);
    private native void setFollowCursorPan(long ptr, boolean enabled);
    private native long getCursorPosition(long ptr);
    private native void sendSync(long ptr, int serial);
    private native void setFiltering(long ptr, int filtering);
    private static native void connect(long ptr, int fd);
    @CriticalNative private static native boolean connected(long ptr);
    private static native void startLogcat(long ptr, int fd);
    private native void setClipboardSyncEnabled(long ptr, boolean enabled, boolean ignored);
    private native void sendClipboardAnnounce(long ptr);
    private native void sendClipboardEvent(long ptr, byte[] text);
    private native void sendWindowChange(long ptr, int width, int height, int framerate, String name);
    private native void sendMouseEvent(long ptr, float x, float y, int button, boolean down, boolean relative);
    private native void sendTouchEvent(long ptr, int action, int id, int x, int y);
    private native void sendStylusEvent(long ptr, float x, float y, int pressure, int tiltX, int tiltY, int orientation, int buttons, boolean eraser, boolean mouse);
    private native void requestStylusEnabled(long ptr, boolean enabled);
    private native void sendLockKeysState(long ptr, int state);
    private native boolean sendKeyEvent(long ptr, int scanCode, int keyCode, boolean down);
    private native void sendTextEvent(long ptr, byte[] text);
    @CriticalNative private static native boolean requestConnection(long ptr);
    public static native long getLastInputTimestamp();
    public static native void markUserActivity();
}
