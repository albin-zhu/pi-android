package com.termux.x11;

import android.graphics.PixelFormat;
import android.text.Editable;
import android.text.InputType;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.util.SparseIntArray;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

/** Surface producer only: rendering and X input belong to the service process. */
final class WindowView extends SurfaceView implements SurfaceHolder.Callback {
    private final WindowActivity activity;
    private final boolean readOnly;
    private final ThreeFingerTap keyboardTap;
    private int mouseButtons, publishedButtons;
    private boolean touchDown, scrolling;
    private float scrollX, scrollY;
    private final SparseIntArray heldKeys = new SparseIntArray();
    private final android.util.SparseBooleanArray controlKeys = new android.util.SparseBooleanArray();
    private int controlMouseButtons;
    private final SpannableStringBuilder composing = new SpannableStringBuilder();
    WindowView(WindowActivity activity, boolean readOnly) {
        super(activity);
        this.activity = activity; this.readOnly = readOnly;
        keyboardTap = readOnly ? null : new ThreeFingerTap(
                android.view.ViewConfiguration.get(activity).getScaledTouchSlop(),
                android.view.ViewConfiguration.getDoubleTapTimeout());
        setContentDescription(readOnly ? "X11 只读画面" : "X11 应用画面，三指轻点显示或收起键盘");
        setFocusable(!readOnly); setFocusableInTouchMode(!readOnly);
        getHolder().setFormat(PixelFormat.OPAQUE);
        getHolder().addCallback(this);
    }
    private boolean canSendInput() { return !readOnly && activity.attached(); }
    @Override public void surfaceCreated(SurfaceHolder holder) { activity.attachSurface(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { activity.attachSurface(); }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { activity.detachSurface(); }
    private void movePointer(float x, float y) { sendMouseEvent(x, y, 0, false, false); }
    private void syncMouseButtons() {
        int buttons = (mouseButtons | controlMouseButtons |
                (touchDown ? MotionEvent.BUTTON_PRIMARY : 0)) & 7;
        int changed = buttons ^ publishedButtons;
        publishedButtons = buttons;
        for (int which = 1; which <= 3; which++) {
            int mask = buttonMask(which);
            if ((changed & mask) != 0) sendMouseEvent(0, 0, which, (buttons & mask) != 0, true);
        }
    }
    private static int buttonMask(int which) {
        return which == 1 ? MotionEvent.BUTTON_PRIMARY : which == 2 ? MotionEvent.BUTTON_TERTIARY
                : which == 3 ? MotionEvent.BUTTON_SECONDARY : 0;
    }
    void controlPointer(float x, float y, int which, boolean down, boolean relative) {
        if (!canSendInput()) return;
        if (which >= 1 && which <= 3) {
            int mask = buttonMask(which);
            if (down) controlMouseButtons |= mask; else controlMouseButtons &= ~mask;
            syncMouseButtons();
            return;
        }
        sendMouseEvent(x, y, which, down, relative);
    }
    void controlKey(int code, boolean down) {
        if (!canSendInput()) return;
        if (down) controlKeys.put(code, true); else controlKeys.delete(code);
        if (heldKeys.indexOfKey(code) < 0) sendKeyEvent(0, code, down);
    }
    private void sendMouseEvent(float x, float y, int button, boolean down, boolean relative) {
        if (!canSendInput()) return;
        android.os.Bundle data = new android.os.Bundle();
        data.putFloat("x", x); data.putFloat("y", y); data.putInt("button", button);
        data.putBoolean("down", down); data.putBoolean("relative", relative);
        activity.command(X11Service.POINTER, data);
    }
    private boolean sendKeyEvent(int scanCode, int keyCode, boolean down) {
        if (!canSendInput()) return false;
        android.os.Bundle data = new android.os.Bundle();
        data.putInt("scanCode", scanCode); data.putInt("keyCode", keyCode); data.putBoolean("down", down);
        activity.command(X11Service.KEY, data);
        return true;
    }
    private boolean hardwareMouse(MotionEvent event) {
        return event.isFromSource(InputDevice.SOURCE_MOUSE) || event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE;
    }
    private boolean mouseEvent(MotionEvent event) {
        if (!canSendInput()) return true;
        movePointer(event.getX(), event.getY());
        int buttons = event.getActionMasked() == MotionEvent.ACTION_CANCEL ? 0 : event.getButtonState();
        mouseButtons = buttons;
        syncMouseButtons();
        if (event.getActionMasked() == MotionEvent.ACTION_SCROLL)
            sendMouseEvent(-120 * event.getAxisValue(MotionEvent.AXIS_HSCROLL), -120 * event.getAxisValue(MotionEvent.AXIS_VSCROLL), 4, false, true);
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
        if (keyboardGesture(event)) return true;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scrolling = false;
                movePointer(event.getX(), event.getY());
                touchDown = true; syncMouseButtons();
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (touchDown) { touchDown = false; syncMouseButtons(); }
                scrolling = true;
                scrollX = event.getX(); scrollY = event.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                if (scrolling) {
                    sendMouseEvent((scrollX - event.getX()) * 5, (scrollY - event.getY()) * 5, 4, false, true);
                    scrollX = event.getX(); scrollY = event.getY();
                } else movePointer(event.getX(), event.getY());
                break;
            case MotionEvent.ACTION_POINTER_UP:
                int remaining = event.getActionIndex() == 0 ? 1 : 0;
                scrollX = event.getX(remaining); scrollY = event.getY(remaining);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (touchDown) { touchDown = false; syncMouseButtons(); }
                scrolling = false;
                break;
        }
        return true;
    }

    private boolean keyboardGesture(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN)
            keyboardTap.start(event.getEventTime(), event.getPointerId(0), event.getX(), event.getY());
        for (int i = 0; i < event.getPointerCount(); i++)
            if (event.getToolType(i) != MotionEvent.TOOL_TYPE_FINGER) keyboardTap.cancel();
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            int index = event.getActionIndex();
            keyboardTap.down(event.getPointerCount(), event.getPointerId(index), event.getX(index), event.getY(index));
        }
        for (int h = 0; h < event.getHistorySize(); h++)
            for (int i = 0; i < event.getPointerCount(); i++)
                keyboardTap.move(event.getPointerId(i), event.getHistoricalX(i, h), event.getHistoricalY(i, h));
        for (int i = 0; i < event.getPointerCount(); i++)
            keyboardTap.move(event.getPointerId(i), event.getX(i), event.getY(i));
        boolean claimed = keyboardTap.claimed();
        if (action == MotionEvent.ACTION_POINTER_UP) keyboardTap.lift();
        else if (action == MotionEvent.ACTION_CANCEL) keyboardTap.cancel();
        else if (action == MotionEvent.ACTION_UP && keyboardTap.finish(event.getEventTime())) toggleKeyboard();
        if (claimed) {
            if (touchDown) { touchDown = false; syncMouseButtons(); }
            scrolling = false;
        }
        return claimed;
    }

    private void toggleKeyboard() {
        if (!canSendInput() || !hasWindowFocus()) return;
        android.view.WindowInsets insets = getRootWindowInsets();
        android.view.WindowInsetsController controller = getWindowInsetsController();
        if (insets != null && insets.isVisible(android.view.WindowInsets.Type.ime())) {
            if (controller != null) controller.hide(android.view.WindowInsets.Type.ime());
        } else showKeyboard();
    }

    boolean handleKey(KeyEvent event) {
        if (!canSendInput()) return false;
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK && event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            // Some Android mouse drivers expose the secondary button as BACK, not MotionEvent.
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            if (down) mouseButtons |= MotionEvent.BUTTON_SECONDARY;
            else mouseButtons &= ~MotionEvent.BUTTON_SECONDARY;
            syncMouseButtons();
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
        return controlKeys.get(code) || sendKeyEvent(event.getScanCode(), code, down);
    }
    private void tapKey(int code) {
        if (!canSendInput()) return;
        sendKeyEvent(0, code, true);
        sendKeyEvent(0, code, false);
    }
    void releaseInput() {
        if (canSendInput()) {
            for (int i = 0; i < heldKeys.size(); i++) sendKeyEvent(heldKeys.valueAt(i), heldKeys.keyAt(i), false);
            for (int i = 0; i < controlKeys.size(); i++)
                if (heldKeys.indexOfKey(controlKeys.keyAt(i)) < 0) sendKeyEvent(0, controlKeys.keyAt(i), false);
            int buttons = mouseButtons | controlMouseButtons;
            if (touchDown || (buttons & MotionEvent.BUTTON_PRIMARY) != 0) sendMouseEvent(0, 0, 1, false, true);
            if ((buttons & MotionEvent.BUTTON_TERTIARY) != 0) sendMouseEvent(0, 0, 2, false, true);
            if ((buttons & MotionEvent.BUTTON_SECONDARY) != 0) sendMouseEvent(0, 0, 3, false, true);
        }
        heldKeys.clear(); touchDown = false; scrolling = false; mouseButtons = 0; publishedButtons = 0;
        controlKeys.clear(); controlMouseButtons = 0;
        if (keyboardTap != null) keyboardTap.cancel();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) releaseInput();
        else if (canSendInput()) activity.command(X11Service.FOCUS, new android.os.Bundle());
    }

    private void sendText(CharSequence text) {
        if (!canSendInput() || text == null || text.length() == 0) return;
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
        android.os.Bundle data = new android.os.Bundle();
        data.putString("text", text.toString());
        activity.command(X11Service.TEXT, data);
    }

    @Override public boolean onCheckIsTextEditor() { return !readOnly; }
    @Override public InputConnection onCreateInputConnection(EditorInfo info) {
        if (readOnly) return null;
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
        if (readOnly) return;
        getContext().getSystemService(InputMethodManager.class).restartInput(this);
    }
    void showKeyboard() {
        if (!canSendInput()) return;
        requestFocus();
        post(() -> {
            if (!canSendInput() || !hasWindowFocus()) return;
            android.view.WindowInsetsController insets = getWindowInsetsController();
            if (insets != null) insets.show(android.view.WindowInsets.Type.ime());
        });
    }

}
