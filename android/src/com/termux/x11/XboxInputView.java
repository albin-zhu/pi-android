package com.termux.x11;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

/** Transparent, pointer-ID-owned Xbox controls. The uncovered center remains an X11 target. */
public final class XboxInputView extends View {
    public interface Sink {
        /** Synchronous callback; axes is reused. LX, LY, RX, RY, LT, RT, HATX, HATY. */
        void gamepad(int buttons, int[] axes);
    }

    private static final int LS = 0, RS = 1, LT = 2, RT = 3;
    private static final int UP = 4, DOWN = 5, LEFT = 6, RIGHT = 7;
    private static final int A = 8, B = 9, X = 10, Y = 11;
    private static final int LB = 12, RB = 13, L3 = 14, R3 = 15, BACK = 16, START = 17;
    private static final int COUNT = 18, MAX_POINTERS = 32;
    private static final int[] BITS = {0, 0, 0, 0, 1, 2, 4, 8,
            0x1000, 0x2000, 0x4000, 0x8000, 0x100, 0x200, 0x40, 0x80, 0x20, 0x10};
    private static final String[] LABELS = {"L", "R", "LT", "RT", "↑", "↓", "←", "→",
            "A", "B", "X", "Y", "LB", "RB", "L3", "R3", "Back", "Start"};
    private static final String[] NAMES = {"Left analog stick", "Right analog stick",
            "Left analog trigger", "Right analog trigger", "D-pad up", "D-pad down",
            "D-pad left", "D-pad right", "A", "B", "X", "Y", "Left shoulder",
            "Right shoulder", "Left stick click", "Right stick click", "View / Back", "Menu / Start"};
    private static final int ACTION_UP = 0x01020001, ACTION_DOWN = 0x01020002;
    private static final int ACTION_LEFT = 0x01020003, ACTION_RIGHT = 0x01020004;
    private static final int ACTION_CENTER = 0x01020005;
    private static final float DEADZONE = 0.14f;

    private final Sink sink;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF[] bounds = new RectF[COUNT];
    private final RectF fill = new RectF();
    private final Rect nodeBounds = new Rect();
    private final int[] screenLocation = new int[2];
    private final int[] pointerIds = new int[MAX_POINTERS];
    private final int[] pointerControls = new int[MAX_POINTERS];
    private final float[] pointerX = new float[MAX_POINTERS], pointerY = new float[MAX_POINTERS];
    private final int[] axes = new int[8], lastAxes = new int[8], accessibleAxes = new int[8];
    private final AccessibilityManager accessibility;
    private final AccessibilityNodeProvider nodes = new ControlNodes();
    private int buttons, lastButtons, accessibleButtons;
    private int accessibilityFocus = -1, hovered = -1;
    private float scale, dpadX, dpadY;

    public XboxInputView(Context context, Sink sink) {
        super(context);
        if (sink == null) throw new IllegalArgumentException("sink is required");
        this.sink = sink;
        accessibility = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        for (int i = 0; i < COUNT; i++) bounds[i] = new RectF();
        for (int i = 0; i < MAX_POINTERS; i++) pointerIds[i] = pointerControls[i] = -1;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription("Xbox touch gamepad. Sticks and triggers are analog. "
                + "Accessible buttons toggle hold; activate again to release.");
        setFocusable(false);
    }

    /** Always publishes neutral, including when no touch was active. Safe to call repeatedly. */
    public void releaseAll() {
        for (int i = 0; i < MAX_POINTERS; i++) pointerIds[i] = pointerControls[i] = -1;
        accessibleButtons = buttons = lastButtons = 0;
        for (int i = 0; i < axes.length; i++) axes[i] = lastAxes[i] = accessibleAxes[i] = 0;
        sink.gamepad(0, axes);
        invalidate();
        accessibilityChanged(-1);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        releaseAll();
        float width = Math.max(0, w - getPaddingLeft() - getPaddingRight());
        float height = Math.max(0, h - getPaddingTop() - getPaddingBottom());
        float density = getResources().getDisplayMetrics().density;
        // Leave the parent's 48dp top-center selector clear, including in short windows.
        float centerGap = Math.min(56 * density, width * 0.5f);
        scale = Math.min(density, Math.min((width - centerGap) / 360f, height / 272f));
        float left = getPaddingLeft();
        float right = w - getPaddingRight() - 180 * scale;
        float top = h - getPaddingBottom() - 272 * scale;
        place(LT, left, top, 6, 6, 82, 50);
        place(LB, left, top, 90, 6, 174, 50);
        place(LS, left, top, 45, 62, 135, 152);
        place(L3, left, top, 114, 162, 174, 198);
        place(BACK, left, top, 114, 214, 174, 254);
        dpadX = left + 55 * scale;
        dpadY = top + 217 * scale;
        place(UP, left, top, 39, 169, 71, 201);
        place(DOWN, left, top, 39, 233, 71, 265);
        place(LEFT, left, top, 7, 201, 39, 233);
        place(RIGHT, left, top, 71, 201, 103, 233);
        place(RB, right, top, 6, 6, 90, 50);
        place(RT, right, top, 98, 6, 174, 50);
        place(Y, right, top, 70, 52, 114, 96);
        place(X, right, top, 34, 88, 78, 132);
        place(B, right, top, 106, 88, 150, 132);
        place(A, right, top, 70, 124, 114, 168);
        place(RS, right, top, 66, 174, 158, 266);
        place(R3, right, top, 6, 174, 60, 210);
        place(START, right, top, 6, 222, 60, 262);
        accessibilityChanged(-1);
    }

    private void place(int control, float x, float y, float l, float t, float r, float b) {
        bounds[control].set(x + l * scale, y + t * scale, x + r * scale, y + b * scale);
    }

    private boolean round(int control) {
        return control == LS || control == RS || control >= A && control <= Y;
    }

    private boolean contains(int control, float x, float y) {
        RectF rect = bounds[control];
        if (!rect.contains(x, y)) return false;
        if (!round(control)) return true;
        float dx = x - rect.centerX(), dy = y - rect.centerY();
        float radius = rect.width() * 0.5f;
        return dx * dx + dy * dy <= radius * radius;
    }

    private int hit(float x, float y) {
        if (scale <= 0) return -1;
        for (int i = 0; i < COUNT; i++) if (contains(i, x, y)) return i;
        // The cross's corners are diagonal targets, its center is neutral.
        if (Math.abs(x - dpadX) <= 48 * scale && Math.abs(y - dpadY) <= 48 * scale)
            return UP;
        return -1;
    }

    boolean hitControl(float x, float y) {
        return getVisibility() == VISIBLE && isEnabled() && hit(x, y) >= 0;
    }

    private boolean dpad(int control) { return control >= UP && control <= RIGHT; }

    private int slotFor(int pointerId) {
        for (int i = 0; i < MAX_POINTERS; i++) if (pointerIds[i] == pointerId) return i;
        return -1;
    }

    private void capture(MotionEvent event, int index, int control) {
        if (control < 0) return;
        if (control <= RT) {
            for (int i = 0; i < MAX_POINTERS; i++)
                if (pointerIds[i] >= 0 && pointerControls[i] == control) return;
        }
        for (int i = 0; i < MAX_POINTERS; i++) {
            if (pointerIds[i] >= 0) continue;
            pointerIds[i] = event.getPointerId(index);
            pointerControls[i] = control;
            pointerX[i] = event.getX(index);
            pointerY[i] = event.getY(index);
            if (control <= RS) {
                accessibleAxes[control * 2] = accessibleAxes[control * 2 + 1] = 0;
            } else if (control <= RT) {
                accessibleAxes[control + 2] = 0;
            } else {
                accessibleButtons &= ~(dpad(control) ? 15 : BITS[control]);
            }
            return;
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || scale <= 0) return false;
        int action = event.getActionMasked(), index = event.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN) {
            int control = hit(event.getX(index), event.getY(index));
            if (control < 0) return false;
            capture(event, index, control);
            ViewParent parent = getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            capture(event, index, hit(event.getX(index), event.getY(index)));
        } else if (action == MotionEvent.ACTION_CANCEL) {
            releaseAll();
            return true;
        }
        for (int i = 0; i < event.getPointerCount(); i++) {
            int slot = slotFor(event.getPointerId(i));
            if (slot < 0) continue;
            pointerX[slot] = event.getX(i);
            pointerY[slot] = event.getY(i);
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            int slot = slotFor(event.getPointerId(index));
            if (slot >= 0) pointerIds[slot] = pointerControls[slot] = -1;
        }
        publish();
        return true;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void stick(int control, float x, float y) {
        RectF rect = bounds[control];
        float travel = rect.width() * 0.36f;
        float dx = (x - rect.centerX()) / travel, dy = (y - rect.centerY()) / travel;
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        int axis = control * 2;
        if (distance <= DEADZONE) {
            axes[axis] = axes[axis + 1] = 0;
        } else {
            float gain = Math.min(1, (distance - DEADZONE) / (1 - DEADZONE)) / distance;
            axes[axis] = Math.round(dx * gain * 32767);
            axes[axis + 1] = Math.round(dy * gain * 32767);
        }
    }

    private void publish() {
        buttons = accessibleButtons;
        for (int i = 0; i < axes.length; i++) axes[i] = accessibleAxes[i];
        for (int i = 0; i < MAX_POINTERS; i++) {
            if (pointerIds[i] < 0) continue;
            int control = pointerControls[i];
            float x = pointerX[i], y = pointerY[i];
            if (control <= RS) {
                stick(control, x, y);
            } else if (control <= RT) {
                RectF rect = bounds[control];
                axes[control + 2] = Math.round(clamp((x - rect.left) / rect.width(), 0, 1) * 32767);
            } else if (dpad(control)) {
                float dx = (x - dpadX) / (48 * scale), dy = (y - dpadY) / (48 * scale);
                if (Math.abs(dx) <= 1.4f && Math.abs(dy) <= 1.4f) {
                    float extent = Math.max(Math.abs(dx), Math.abs(dy));
                    if (extent > 0.22f) {
                        if (Math.abs(dx) >= extent * 0.45f) buttons |= dx < 0 ? 4 : 8;
                        if (Math.abs(dy) >= extent * 0.45f) buttons |= dy < 0 ? 1 : 2;
                    }
                }
            } else if (contains(control, x, y)) {
                buttons |= BITS[control];
            }
        }
        // Opposite directions cancel, including two-finger and accessibility holds.
        if ((buttons & 3) == 3) buttons &= ~3;
        if ((buttons & 12) == 12) buttons &= ~12;
        axes[6] = ((buttons & 8) != 0 ? 1 : 0) - ((buttons & 4) != 0 ? 1 : 0);
        axes[7] = ((buttons & 2) != 0 ? 1 : 0) - ((buttons & 1) != 0 ? 1 : 0);
        boolean changed = lastButtons != buttons;
        for (int i = 0; i < axes.length; i++) if (axes[i] != lastAxes[i]) changed = true;
        if (!changed) return;
        lastButtons = buttons;
        for (int i = 0; i < axes.length; i++) lastAxes[i] = axes[i];
        sink.gamepad(buttons, axes);
        postInvalidateOnAnimation();
    }

    private boolean active(int control) {
        if (control <= RS) return axes[control * 2] != 0 || axes[control * 2 + 1] != 0;
        if (control <= RT) return axes[control + 2] != 0;
        return (buttons & BITS[control]) != 0;
    }

    private int color(int control) {
        switch (control) {
            case A: return 0xb84d795c;
            case B: return 0xb8915557;
            case X: return 0xb84d6f94;
            case Y: return 0xb8938450;
            default: return 0x98404a56;
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (scale <= 0) return;
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(14 * scale);
        for (int i = 0; i < COUNT; i++) {
            RectF rect = bounds[i];
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color(i));
            if (round(i)) canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() * 0.5f, paint);
            else canvas.drawRoundRect(rect, 7 * scale, 7 * scale, paint);
            if (i == LT || i == RT) {
                fill.set(rect.left, rect.bottom - 9 * scale,
                        rect.left + rect.width() * axes[i + 2] / 32767f, rect.bottom);
                paint.setColor(0xaabbcddd);
                canvas.drawRoundRect(fill, 4 * scale, 4 * scale, paint);
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth((accessibilityFocus == i ? 3 : 1.2f) * scale);
            paint.setColor(active(i) || accessibilityFocus == i ? 0xffedf3fa : 0x999caab8);
            if (round(i)) canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() * 0.5f, paint);
            else canvas.drawRoundRect(rect, 7 * scale, 7 * scale, paint);
            paint.setStyle(Paint.Style.FILL);
            float textX = rect.centerX(), textY = rect.centerY();
            if (i <= RS) {
                float travel = rect.width() * 0.27f;
                textX += travel * axes[i * 2] / 32767f;
                textY += travel * axes[i * 2 + 1] / 32767f;
                paint.setColor(0xc56f8295);
                canvas.drawCircle(textX, textY, rect.width() * 0.23f, paint);
            }
            paint.setColor(0xfff0f3f7);
            canvas.drawText(LABELS[i], textX, textY - (paint.ascent() + paint.descent()) * 0.5f, paint);
        }
    }

    @Override protected void onDetachedFromWindow() {
        releaseAll();
        super.onDetachedFromWindow();
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) releaseAll();
    }

    @Override public void setEnabled(boolean enabled) {
        if (!enabled) releaseAll();
        super.setEnabled(enabled);
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() { return nodes; }

    private void accessibilityEvent(int control, int type) {
        if (accessibility == null || !accessibility.isEnabled() || getParent() == null) return;
        AccessibilityEvent event = AccessibilityEvent.obtain(type);
        event.setPackageName(getContext().getPackageName());
        event.setClassName("android.widget.Button");
        event.setSource(this, control < 0 ? View.NO_ID : control + 1);
        event.setEnabled(isEnabled());
        if (control >= 0) event.setContentDescription(NAMES[control]);
        getParent().requestSendAccessibilityEvent(this, event);
    }

    private void accessibilityChanged(int control) {
        accessibilityEvent(control, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    }

    @Override public boolean dispatchHoverEvent(MotionEvent event) {
        if (accessibility == null || !accessibility.isTouchExplorationEnabled() || !isEnabled())
            return super.dispatchHoverEvent(event);
        int next = event.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT ? -1 : hit(event.getX(), event.getY());
        int previous = hovered;
        if (next != previous) {
            hovered = next;
            if (next >= 0) accessibilityEvent(next, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);
            if (previous >= 0) accessibilityEvent(previous, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
        }
        return next >= 0 || previous >= 0;
    }

    private final class ControlNodes extends AccessibilityNodeProvider {
        @Override public AccessibilityNodeInfo createAccessibilityNodeInfo(int virtualId) {
            if (virtualId == View.NO_ID) {
                AccessibilityNodeInfo host = AccessibilityNodeInfo.obtain(XboxInputView.this);
                onInitializeAccessibilityNodeInfo(host);
                for (int i = 0; i < COUNT; i++) host.addChild(XboxInputView.this, i + 1);
                return host;
            }
            int control = virtualId - 1;
            if (control < 0 || control >= COUNT) return null;
            AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
            node.setSource(XboxInputView.this, virtualId);
            node.setParent(XboxInputView.this);
            node.setPackageName(getContext().getPackageName());
            node.setClassName(control == LT || control == RT ? "android.widget.SeekBar" : "android.widget.Button");
            node.setEnabled(isEnabled());
            node.setVisibleToUser(isShown() && scale > 0);
            node.setFocusable(true);
            node.setAccessibilityFocused(accessibilityFocus == control);
            node.addAction(accessibilityFocus == control
                    ? AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                    : AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
            bounds[control].roundOut(nodeBounds);
            node.setBoundsInParent(nodeBounds);
            getLocationOnScreen(screenLocation);
            nodeBounds.offset(screenLocation[0], screenLocation[1]);
            node.setBoundsInScreen(nodeBounds);
            node.setClickable(true);
            if (control <= RS) {
                node.setContentDescription(NAMES[control] + ". Direction actions hold full deflection until centered."
                        + " X " + axes[control * 2] + ", Y " + axes[control * 2 + 1]);
                node.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_UP, "Hold up"));
                node.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_DOWN, "Hold down"));
                node.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_LEFT, "Hold left"));
                node.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_RIGHT, "Hold right"));
                node.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_CENTER, "Center stick"));
                node.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, "Center stick"));
            } else if (control <= RT) {
                node.setContentDescription(NAMES[control] + ". Slide right to pull, left to release.");
                node.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                        AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0, 100, axes[control + 2] * 100f / 32767));
                node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
                node.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                node.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
                node.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, "Release trigger"));
            } else {
                node.setContentDescription(NAMES[control] + ". Activate to hold or release.");
                node.setCheckable(true);
                node.setChecked(active(control));
                node.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,
                        active(control) ? "Release" : "Hold"));
            }
            return node;
        }

        @Override public AccessibilityNodeInfo findFocus(int focus) {
            return focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY && accessibilityFocus >= 0
                    ? createAccessibilityNodeInfo(accessibilityFocus + 1) : null;
        }

        @Override public boolean performAction(int virtualId, int action, Bundle arguments) {
            if (virtualId == View.NO_ID) return performAccessibilityAction(action, arguments);
            int control = virtualId - 1;
            if (control < 0 || control >= COUNT) return false;
            if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) {
                if (accessibilityFocus == control) return false;
                if (accessibilityFocus >= 0) accessibilityEvent(accessibilityFocus, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
                accessibilityFocus = control;
                accessibilityEvent(control, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
                invalidate();
                return true;
            }
            if (action == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS) {
                if (accessibilityFocus != control) return false;
                accessibilityFocus = -1;
                accessibilityEvent(control, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
                invalidate();
                return true;
            }
            if (!isEnabled() || !isShown()) return false;
            if (control <= RS) {
                int axis = control * 2;
                if (action != ACTION_UP && action != ACTION_DOWN && action != ACTION_LEFT
                        && action != ACTION_RIGHT && action != ACTION_CENTER
                        && action != AccessibilityNodeInfo.ACTION_CLICK) return false;
                accessibleAxes[axis] = action == ACTION_LEFT ? -32767 : action == ACTION_RIGHT ? 32767 : 0;
                accessibleAxes[axis + 1] = action == ACTION_UP ? -32767 : action == ACTION_DOWN ? 32767 : 0;
            } else if (control <= RT) {
                int axis = control + 2;
                if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()) {
                    if (arguments == null || !arguments.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)) return false;
                    float value = arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE);
                    if (Float.isNaN(value) || Float.isInfinite(value)) return false;
                    accessibleAxes[axis] = Math.round(clamp(value, 0, 100) * 32767 / 100);
                } else if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                    accessibleAxes[axis] = Math.min(32767, accessibleAxes[axis] + 3277);
                } else if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                    accessibleAxes[axis] = Math.max(0, accessibleAxes[axis] - 3277);
                } else if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                    accessibleAxes[axis] = 0;
                } else return false;
            } else if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                accessibleButtons ^= BITS[control];
            } else return false;
            publish();
            accessibilityChanged(control);
            return true;
        }
    }
}
