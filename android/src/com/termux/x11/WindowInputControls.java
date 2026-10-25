package com.termux.x11;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import dev.zfold.assistant.R;
import dev.zfold.assistant.Ui;

/** Controls live inside the application task; never an Android overlay window. */
final class WindowInputControls extends FrameLayout {
    interface GamepadSink { void gamepad(int buttons, int[] axes); }
    static final int NONE = 0, XBOX = 1, KEYBOARD_MOUSE = 2;
    private final WindowView display;
    private final GamepadSink gamepadSink;
    private final SharedPreferences preferences;
    private final Ui.Action selector;
    private XboxInputView xbox;
    private KeyboardMouseInputView keyboardMouse;
    private int mode;
    private boolean available, gamepadAvailable;
    private String gamepadError;
    // Android pointer IDs are 0..31. Ownership changes only on a new pointer down.
    private final View[] owners = new View[32], cancelTargets = new View[32];
    private final long[] downTimes = new long[32];
    private final int[] indices = new int[32];
    private final MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[32];
    private final MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[32];
    private MotionEvent lastTouch;
    private boolean touchStream, cancelling;
    private int routeGeneration;

    WindowInputControls(Context context, WindowView display, GamepadSink sink) {
        super(context);
        this.display = display; gamepadSink = sink;
        for (int i = 0; i < 32; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            coordinates[i] = new MotionEvent.PointerCoords();
        }
        preferences = context.getSharedPreferences("x11-input", Context.MODE_PRIVATE);
        setMotionEventSplittingEnabled(true);
        addView(display, new LayoutParams(-1, -1));
        selector = new Ui.Action(context, R.drawable.ic_keyboard, "", "选择输入布局",
                0xffe6edf3, 0xb820262c, this::showMenu);
        selector.setFocusable(false);
        selector.setTooltipText("Xbox 手柄 / 鼠标键盘");
        LayoutParams placement = new LayoutParams(Ui.dp(context, 48), Ui.dp(context, 48), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        placement.topMargin = Ui.dp(context, 8);
        addView(selector, placement);
        availability(false, false, null);
    }

    int mode() { return mode; }

    private void showMenu() {
        releaseAll();
        PopupMenu menu = new PopupMenu(getContext(), selector);
        int selected = mode == NONE ? preferences.getInt("last-layout", KEYBOARD_MOUSE) : mode;
        menu.getMenu().add(1, XBOX, 0, gamepadAvailable ? "Xbox 手柄" : "Xbox 手柄（通道未就绪）")
                .setCheckable(true).setChecked(selected == XBOX).setEnabled(gamepadAvailable);
        menu.getMenu().add(1, KEYBOARD_MOUSE, 1, "鼠标键盘")
                .setCheckable(true).setChecked(selected == KEYBOARD_MOUSE);
        if (mode != NONE) menu.getMenu().add(0, NONE, 2, "收起布局");
        menu.setOnMenuItemClickListener(item -> { setMode(item.getItemId()); return true; });
        menu.show();
    }

    void setMode(int requested) {
        if (requested != XBOX && requested != KEYBOARD_MOUSE) requested = NONE;
        releaseAll();
        if (requested == XBOX && !gamepadAvailable) {
            android.widget.Toast.makeText(getContext(), gamepadError == null ? "X11 手柄通道尚未就绪"
                    : "手柄通道不可用：" + gamepadError, android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        mode = requested;
        if (mode == XBOX && xbox == null) {
            xbox = new XboxInputView(getContext(), gamepadSink::gamepad);
            addView(xbox, getChildCount() - 1, new LayoutParams(-1, -1));
        }
        if (mode == KEYBOARD_MOUSE && keyboardMouse == null) {
            keyboardMouse = new KeyboardMouseInputView(getContext(), new KeyboardMouseInputView.Sink() {
                @Override public void pointer(float x, float y, int button, boolean down, boolean relative) {
                    if (available) display.controlPointer(x, y, button, down, relative);
                }
                @Override public void key(int code, boolean down) {
                    if (available) display.controlKey(code, down);
                }
                @Override public void keyboard() { releaseAll(); display.showKeyboard(); }
            });
            addView(keyboardMouse, getChildCount() - 1, new LayoutParams(-1, -1));
        }
        if (xbox != null) xbox.setVisibility(mode == XBOX && available ? VISIBLE : GONE);
        if (keyboardMouse != null) keyboardMouse.setVisibility(mode == KEYBOARD_MOUSE && available ? VISIBLE : GONE);
        if (mode != NONE) preferences.edit().putInt("last-layout", mode).apply();
        selector.setContentDescription("输入布局：" + (mode == XBOX ? "Xbox 手柄" : mode == KEYBOARD_MOUSE ? "鼠标键盘" : "已收起"));
        display.requestFocus();
    }

    boolean hideLayout() {
        if (mode == NONE) return false;
        setMode(NONE);
        return true;
    }

    void availability(boolean input, boolean gamepad, String error) {
        // Release while the previous availability still allows key/button-up callbacks.
        if (available && (!input || gamepadAvailable && !gamepad)) releaseAll();
        available = input; gamepadAvailable = gamepad; gamepadError = error;
        selector.setEnabled(input);
        if (xbox != null) xbox.setVisibility(input && gamepad && mode == XBOX ? VISIBLE : GONE);
        if (keyboardMouse != null) keyboardMouse.setVisibility(input && mode == KEYBOARD_MOUSE ? VISIBLE : GONE);
    }

    private View initialTarget(MotionEvent event, int index) {
        float x = event.getX(index), y = event.getY(index);
        if (selector.getVisibility() == VISIBLE && selector.isEnabled()
                && x >= selector.getLeft() && x < selector.getRight()
                && y >= selector.getTop() && y < selector.getBottom()) return selector;
        if (event.getToolType(index) == MotionEvent.TOOL_TYPE_MOUSE) return display;
        if (xbox != null && xbox.hitControl(x - xbox.getLeft(), y - xbox.getTop())) return xbox;
        if (keyboardMouse != null) {
            View child = keyboardMouse.hitControl(x - keyboardMouse.getLeft(), y - keyboardMouse.getTop());
            if (child != null) return child;
        }
        return display;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            cancelRoutes();
            touchStream = true;
        }
        // Hide/resize/blur invalidates the entire physical stream, including later pointer downs.
        if (!touchStream) return true;
        if (lastTouch != null) lastTouch.recycle();
        lastTouch = MotionEvent.obtain(event);
        int changedId = event.getPointerId(event.getActionIndex());
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            View target = initialTarget(event, event.getActionIndex());
            long downTime = event.getEventTime();
            for (int i = 0; i < owners.length; i++)
                if (owners[i] == target) { downTime = downTimes[i]; break; }
            owners[changedId] = target;
            downTimes[changedId] = downTime;
        }
        int generation = routeGeneration;
        for (int i = 0; i < event.getPointerCount(); i++) {
            View target = owners[event.getPointerId(i)];
            if (target == null) continue;
            boolean alreadySent = false;
            for (int j = 0; j < i; j++)
                if (owners[event.getPointerId(j)] == target) { alreadySent = true; break; }
            if (alreadySent) continue;
            MotionEvent split = eventForTarget(event, target, false);
            try { target.dispatchTouchEvent(split); }
            finally { split.recycle(); }
            // A selector or keyboard callback may synchronously release all input.
            if (generation != routeGeneration) return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
            owners[changedId] = null;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            touchStream = false;
            for (int i = 0; i < owners.length; i++) owners[i] = null;
            lastTouch.recycle();
            lastTouch = null;
        }
        return true;
    }

    /** Public-SDK split, preserving IDs, tool/axis data and every historical sample. */
    private MotionEvent eventForTarget(MotionEvent source, View target, boolean cancel) {
        int count = 0, changedIndex = -1;
        for (int i = 0; i < source.getPointerCount(); i++) {
            if (owners[source.getPointerId(i)] != target) continue;
            if (i == source.getActionIndex()) changedIndex = count;
            indices[count] = i;
            source.getPointerProperties(i, properties[count++]);
        }
        int action = source.getActionMasked();
        if (cancel) action = MotionEvent.ACTION_CANCEL;
        else if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            if (changedIndex < 0) action = MotionEvent.ACTION_MOVE;
            else if (count == 1) action = action == MotionEvent.ACTION_POINTER_DOWN
                    ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP;
            else action |= changedIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT;
        }
        float offsetX = 0, offsetY = 0;
        for (View child = target; child != this; child = (View) child.getParent()) {
            View parent = (View) child.getParent();
            offsetX += parent.getScrollX() - child.getLeft();
            offsetY += parent.getScrollY() - child.getTop();
        }
        int history = source.getHistorySize();
        MotionEvent result = null;
        for (int sample = 0; sample <= history; sample++) {
            for (int i = 0; i < count; i++) {
                if (sample < history) source.getHistoricalPointerCoords(indices[i], sample, coordinates[i]);
                else source.getPointerCoords(indices[i], coordinates[i]);
                coordinates[i].x += offsetX;
                coordinates[i].y += offsetY;
            }
            long time = sample < history ? source.getHistoricalEventTime(sample) : source.getEventTime();
            if (result == null) {
                result = MotionEvent.obtain(downTimes[properties[0].id], time, MotionEvent.ACTION_MOVE,
                        count, properties, coordinates, source.getMetaState(), source.getButtonState(),
                        source.getXPrecision(), source.getYPrecision(), source.getDeviceId(),
                        source.getEdgeFlags(), source.getSource(), source.getFlags());
            } else result.addBatch(time, coordinates, source.getMetaState());
        }
        result.setAction(action);
        return result;
    }

    private void cancelRoutes() {
        if (cancelling) return;
        cancelling = true;
        touchStream = false;
        routeGeneration++;
        MotionEvent source = lastTouch;
        lastTouch = null;
        int count = 0;
        for (int i = 0; i < owners.length; i++) {
            View target = owners[i];
            if (target == null) continue;
            boolean seen = false;
            for (int j = 0; j < count; j++) if (cancelTargets[j] == target) seen = true;
            if (!seen) cancelTargets[count++] = target;
        }
        try {
            for (int i = 0; i < count; i++) {
                MotionEvent cancel = eventForTarget(source, cancelTargets[i], true);
                try { cancelTargets[i].dispatchTouchEvent(cancel); }
                finally { cancel.recycle(); }
            }
        } finally {
            for (int i = 0; i < owners.length; i++) owners[i] = cancelTargets[i] = null;
            if (source != null) source.recycle();
            cancelling = false;
        }
    }

    void releaseAll() {
        cancelRoutes();
        if (xbox != null) xbox.releaseAll();
        if (keyboardMouse != null) keyboardMouse.releaseAll();
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        if (w != oldW || h != oldH) releaseAll();
        super.onSizeChanged(w, h, oldW, oldH);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) releaseAll();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        // View constructors can invoke this before fields are initialized.
        if (visibility != VISIBLE && owners != null) releaseAll();
    }

    @Override protected void onDetachedFromWindow() {
        releaseAll();
        super.onDetachedFromWindow();
    }
}
