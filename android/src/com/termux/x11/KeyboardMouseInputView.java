package com.termux.x11;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Relative input surface. Touch capture belongs to each child, not to pointer indices. */
public final class KeyboardMouseInputView extends FrameLayout {
    public interface Sink {
        void pointer(float x, float y, int button, boolean down, boolean relative);
        void key(int keyCode, boolean down);
        void keyboard();
    }

    private static final int KEY = 0, MODIFIER = 1, MOUSE = 2, KEYBOARD = 3;
    private final Sink sink;
    private final float density;
    private final int slop;
    private final Touchpad pad;
    private final Control[] keys;
    private final Control left, right;

    public KeyboardMouseInputView(Context context, Sink sink) {
        super(context);
        this.sink = sink;
        density = getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setMotionEventSplittingEnabled(true);
        setClipChildren(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        pad = new Touchpad(context);
        addView(pad);
        keys = new Control[] {
                control("Esc", "Escape", KEY, KeyEvent.KEYCODE_ESCAPE),
                control("Tab", "Tab 制表键", KEY, KeyEvent.KEYCODE_TAB),
                control("Ctrl", "Control 锁定开关", MODIFIER, KeyEvent.KEYCODE_CTRL_LEFT),
                control("Alt", "Alt 锁定开关", MODIFIER, KeyEvent.KEYCODE_ALT_LEFT),
                control("Shift", "Shift 锁定开关", MODIFIER, KeyEvent.KEYCODE_SHIFT_LEFT),
                control("键盘", "显示或收起软键盘", KEYBOARD, 0),
                control("退格", "Backspace 退格", KEY, KeyEvent.KEYCODE_DEL),
                control("Enter", "Enter 回车", KEY, KeyEvent.KEYCODE_ENTER),
                control("←", "向左方向键", KEY, KeyEvent.KEYCODE_DPAD_LEFT),
                control("↓", "向下方向键", KEY, KeyEvent.KEYCODE_DPAD_DOWN),
                control("↑", "向上方向键", KEY, KeyEvent.KEYCODE_DPAD_UP),
                control("→", "向右方向键", KEY, KeyEvent.KEYCODE_DPAD_RIGHT)
        };
        left = control("左键", "鼠标左键，按住并在触控板移动可拖动", MOUSE, 1);
        right = control("右键", "鼠标右键，按住并在触控板移动可拖动", MOUSE, 3);
    }

    private Control control(String label, String description, int kind, int code) {
        Control control = new Control(getContext(), label, description, kind, code);
        addView(control);
        return control;
    }

    private int dp(float value) { return Math.round(value * density); }

    private GradientDrawable surface(int fill, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(12));
        drawable.setStroke(Math.max(1, dp(1)), stroke);
        return drawable;
    }

    private void style(TextView view) {
        view.setGravity(Gravity.CENTER);
        view.setTextColor(0xffe5eaf2);
        view.setTextSize(13);
        view.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        view.setIncludeFontPadding(false);
        view.setPadding(dp(3), dp(2), dp(3), dp(2));
        view.setFocusable(true);
        view.setClickable(true);
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[] { android.R.attr.state_pressed }, surface(0xed36506e, 0xff7098c4));
        background.addState(new int[] { android.R.attr.state_selected }, surface(0xed304861, 0xff7098c4));
        background.addState(new int[] { android.R.attr.state_focused }, surface(0xe5253447, 0xff7098c4));
        background.addState(new int[0], surface(0xd91b222d, 0x665b6c81));
        view.setBackground(background);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = resolveSize(dp(600), widthSpec);
        int columns = width >= dp(360) ? 6 : width >= dp(240) ? 4 : 3;
        int height = resolveSize(dp(216 + (12 / columns + 1) * 52), heightSpec);
        setMeasuredDimension(width, height);
        // Exact child sizes are assigned by layout; no hierarchy is rebuilt on resize.
        arrange(width, height, false);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        arrange(r - l, b - t, true);
    }

    private void place(View child, int x, int y, int width, int height, boolean layout) {
        width = Math.max(0, width);
        height = Math.max(0, height);
        if (layout) child.layout(x, y, x + width, y + height);
        else child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    private void arrange(int width, int height, boolean layout) {
        int gap = Math.min(dp(4), Math.min(width, height) / 12);
        // The activity's always-visible input selector owns the top-center 60dp.
        int availableHeight = Math.max(0, height - dp(64));
        int panelWidth = Math.max(0, Math.min(width - 2 * gap, dp(720)));
        int x = (width - panelWidth) / 2;
        if (width >= dp(560) && availableHeight < dp(300)) {
            int panelHeight = Math.max(0, Math.min(availableHeight - 2 * gap, dp(204)));
            int y = height - panelHeight - gap;
            int padWidth = panelWidth * 2 / 5;
            int row = Math.max(0, Math.min(dp(52), panelHeight / 3));
            place(pad, x, y, padWidth, Math.max(0, panelHeight - row - gap), layout);
            place(left, x, y + panelHeight - row, (padWidth - gap) / 2, row, layout);
            place(right, x + (padWidth + gap) / 2, y + panelHeight - row, (padWidth - gap) / 2, row, layout);
            int gridX = x + padWidth + gap;
            int gridWidth = Math.max(0, panelWidth - padWidth - gap);
            for (int i = 0; i < keys.length; i++) {
                int col = i % 4, line = i / 4;
                int start = col * gridWidth / 4;
                int top = line * panelHeight / 3;
                place(keys[i], gridX + start, y + top,
                        (col + 1) * gridWidth / 4 - start - gap,
                        (line + 1) * panelHeight / 3 - top - gap, layout);
            }
        } else {
            int columns = width >= dp(360) ? 6 : width >= dp(240) ? 4 : 3;
            int rows = keys.length / columns;
            int panelHeight = Math.max(0, Math.min(availableHeight - 2 * gap, dp(152 + (rows + 1) * 52)));
            int y = height - panelHeight - gap;
            int row = Math.max(0, Math.min(dp(52), panelHeight / (rows + 2)));
            int padHeight = Math.max(0, panelHeight - (rows + 1) * row - gap);
            place(pad, x, y, panelWidth, padHeight, layout);
            int mouseY = y + padHeight + gap;
            place(left, x, mouseY, (panelWidth - gap) / 2, Math.max(0, row - gap), layout);
            place(right, x + (panelWidth + gap) / 2, mouseY, (panelWidth - gap) / 2, Math.max(0, row - gap), layout);
            for (int i = 0; i < keys.length; i++) {
                int col = i % columns, line = i / columns;
                int start = col * panelWidth / columns;
                place(keys[i], x + start, mouseY + row * (line + 1),
                        (col + 1) * panelWidth / columns - start - gap, Math.max(0, row - gap), layout);
            }
        }
    }

    /** Return the actual leaf, never the transparent full-screen container. */
    View hitControl(float x, float y) {
        if (getVisibility() != VISIBLE || !isEnabled()) return null;
        for (int i = getChildCount() - 1; i >= 0; i--) {
            View child = getChildAt(i);
            if (child.getVisibility() == VISIBLE && child.isEnabled()
                    && x >= child.getLeft() && x < child.getRight()
                    && y >= child.getTop() && y < child.getBottom()) return child;
        }
        return null;
    }

    /** Idempotent; also forgets capture so late UP/MOVE events cannot synthesize input. */
    public void releaseAll() {
        if (pad == null) return;
        pad.clear();
        for (Control key : keys) key.release();
        left.release();
        right.release();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        boolean handled = super.dispatchTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) releaseAll();
        return handled;
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (oldw != 0 || oldh != 0) releaseAll();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) releaseAll();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE) releaseAll();
    }

    @Override protected void onDetachedFromWindow() {
        releaseAll();
        super.onDetachedFromWindow();
    }

    private final class Control extends TextView {
        private final int kind, code;
        private int pointers;
        private boolean outputDown, latched, clickEligible;
        private float startX, startY;

        Control(Context context, String label, String description, int kind, int code) {
            super(context);
            this.kind = kind;
            this.code = code;
            style(this);
            setText(label);
            setContentDescription(description);
            setMaxLines(1);
            setLongClickable(kind == MOUSE);
        }

        private void output(boolean down) {
            if (outputDown == down) return;
            outputDown = down;
            setSelected(down);
            if (kind == MOUSE) sink.pointer(0, 0, code, down, true);
            else sink.key(code, down);
        }

        void release() {
            pointers = 0;
            latched = clickEligible = false;
            setPressed(false);
            if (kind != KEYBOARD) output(false);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!isEnabled()) return false;
            int action = event.getActionMasked();
            int index = event.getActionIndex();
            int bit = 1 << event.getPointerId(index);
            if (action == MotionEvent.ACTION_CANCEL) {
                releaseAll();
                return true;
            }
            if (action == MotionEvent.ACTION_DOWN) {
                pointers = bit;
                startX = event.getX(index);
                startY = event.getY(index);
                clickEligible = true;
                setPressed(true);
                if (kind == MOUSE) pad.tapEligible = false;
                if (kind == KEY || kind == MOUSE) output(true);
            } else if (pointers != 0) {
                int present = 0;
                for (int i = 0; i < event.getPointerCount(); i++) present |= 1 << event.getPointerId(i);
                if ((pointers & ~present) != 0) {
                    pointers &= present;
                    clickEligible = false;
                    if (pointers == 0) {
                        setPressed(false);
                        if (kind == KEY || kind == MOUSE) output(latched);
                        return true;
                    }
                }
                // Batched motion may leave and return before the final sample.
                if (clickEligible) {
                    for (int h = 0; h <= event.getHistorySize(); h++) {
                        for (int i = 0; i < event.getPointerCount(); i++) {
                            if ((pointers & (1 << event.getPointerId(i))) == 0) continue;
                            float x = h == event.getHistorySize() ? event.getX(i) : event.getHistoricalX(i, h);
                            float y = h == event.getHistorySize() ? event.getY(i) : event.getHistoricalY(i, h);
                            if (Math.abs(x - startX) > slop || Math.abs(y - startY) > slop) clickEligible = false;
                        }
                    }
                }
                if (action == MotionEvent.ACTION_POINTER_DOWN) {
                    pointers |= bit;
                    clickEligible = false;
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
                    if ((pointers & bit) == 0) return true;
                    pointers &= ~bit;
                    if (pointers == 0) {
                        boolean click = clickEligible && event.getX(index) >= 0 && event.getX(index) < getWidth()
                                && event.getY(index) >= 0 && event.getY(index) < getHeight()
                                && Math.abs(event.getX(index) - startX) <= slop
                                && Math.abs(event.getY(index) - startY) <= slop;
                        clickEligible = false;
                        setPressed(false);
                        if (kind == KEY || kind == MOUSE) {
                            output(latched);
                            if (click) super.performClick();
                        } else if (click) performClick();
                    }
                }
            }
            return true;
        }

        @Override public boolean performClick() {
            super.performClick();
            if (!isEnabled()) return false;
            if (kind == KEYBOARD) sink.keyboard();
            else if (kind == MODIFIER) {
                latched = !latched;
                output(latched);
            } else if (!outputDown) {
                output(true);
                output(false);
            }
            return true;
        }

        @Override public boolean performLongClick() {
            if (kind != MOUSE || !isEnabled()) return super.performLongClick();
            pad.tapEligible = false;
            latched = !latched;
            output(latched || pointers != 0);
            return true;
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName(kind == MODIFIER ? "android.widget.ToggleButton" : "android.widget.Button");
            if (kind == MODIFIER || kind == MOUSE) {
                info.setCheckable(true);
                info.setChecked(outputDown);
            }
            if (kind == MOUSE) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.ACTION_LONG_CLICK, latched ? "松开鼠标键" : "按住鼠标键以拖动"));
        }
    }

    private final class Touchpad extends TextView {
        private int pointers;
        private float lastX, lastY, startX, startY;
        private long startTime;
        private boolean tapEligible, multiple;

        Touchpad(Context context) {
            super(context);
            style(this);
            setText("触控板\n单指移动 · 轻点左键 · 双指滚动");
            setTextSize(12);
            setTextColor(0xffaebdce);
            setContentDescription("相对鼠标触控板，单指移动，轻点左键，双指滚动；按住下方鼠标键可拖动");
        }

        void clear() {
            pointers = 0;
            tapEligible = multiple = false;
            setPressed(false);
        }

        private void baseline(MotionEvent event) {
            float x = 0, y = 0;
            int count = 0;
            for (int i = 0; i < event.getPointerCount(); i++) {
                if ((pointers & (1 << event.getPointerId(i))) == 0) continue;
                x += event.getX(i);
                y += event.getY(i);
                count++;
            }
            if (count != 0) { lastX = x / count; lastY = y / count; }
        }

        private void sample(MotionEvent event, int history) {
            float x = 0, y = 0;
            int count = 0, present = 0;
            for (int i = 0; i < event.getPointerCount(); i++) {
                int bit = 1 << event.getPointerId(i);
                if ((pointers & bit) == 0) continue;
                present |= bit;
                x += history < 0 ? event.getX(i) : event.getHistoricalX(i, history);
                y += history < 0 ? event.getY(i) : event.getHistoricalY(i, history);
                count++;
            }
            if (count == 0) { clear(); return; }
            x /= count;
            y /= count;
            if (present != pointers) {
                pointers = present;
                tapEligible = false;
                lastX = x;
                lastY = y;
                return;
            }
            if (Math.abs(x - startX) > slop || Math.abs(y - startY) > slop) tapEligible = false;
            float dx = x - lastX, dy = y - lastY;
            lastX = x;
            lastY = y;
            if (dx == 0 && dy == 0) return;
            if (count >= 2) {
                // 24dp of finger movement is one wheel detent; same sign as WindowView.
                sink.pointer(-dx * 120 / dp(24), -dy * 120 / dp(24), 4, false, true);
            } else if (!multiple) sink.pointer(dx, dy, 0, false, true);
        }

        private void samples(MotionEvent event) {
            for (int h = 0; h < event.getHistorySize(); h++) sample(event, h);
            sample(event, -1);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!isEnabled()) return false;
            int action = event.getActionMasked();
            int index = event.getActionIndex();
            int bit = 1 << event.getPointerId(index);
            if (action == MotionEvent.ACTION_CANCEL) {
                releaseAll();
            } else if (action == MotionEvent.ACTION_DOWN) {
                clear();
                pointers = bit;
                startX = lastX = event.getX(index);
                startY = lastY = event.getY(index);
                startTime = event.getEventTime();
                tapEligible = !left.outputDown && !right.outputDown;
                setPressed(true);
            } else if (pointers != 0) {
                if (action == MotionEvent.ACTION_POINTER_DOWN) {
                    pointers |= bit;
                    multiple = true;
                    tapEligible = false;
                    baseline(event);
                } else if (action == MotionEvent.ACTION_MOVE) {
                    samples(event);
                } else if (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP) {
                    if ((pointers & bit) == 0) return true;
                    samples(event);
                    pointers &= ~bit;
                    if (pointers == 0) {
                        boolean tap = tapEligible && !multiple
                                && event.getEventTime() - startTime <= ViewConfiguration.getLongPressTimeout();
                        clear();
                        if (tap) performClick();
                    } else {
                        tapEligible = false;
                        baseline(event);
                    }
                }
            }
            return true;
        }

        @Override public boolean performClick() {
            super.performClick();
            if (!isEnabled() || left.outputDown || right.outputDown) return false;
            left.output(true);
            left.output(false);
            return true;
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.view.View");
            info.setScrollable(true);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT);
        }

        @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (!isEnabled()) return false;
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                sink.pointer(0, action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 120 : -120, 4, false, true);
                return true;
            }
            if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId()
                    || action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId()) {
                sink.pointer(action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId() ? -120 : 120,
                        0, 4, false, true);
                return true;
            }
            return super.performAccessibilityAction(action, arguments);
        }
    }
}
