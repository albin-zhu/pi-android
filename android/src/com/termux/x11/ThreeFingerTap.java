package com.termux.x11;

/** Allocation-free per event; pointer IDs stay stable when Android reorders indices. */
final class ThreeFingerTap {
    private final float slopSquared;
    private final int timeout;
    private final int[] ids = new int[3];
    private final float[] positions = new float[6];
    private long started;
    private int fingers;
    private boolean valid, claimed;

    ThreeFingerTap(float slop, int timeout) {
        slopSquared = slop * slop;
        this.timeout = timeout;
    }
    void start(long time, int id, float x, float y) {
        started = time; fingers = 1; valid = true; claimed = false;
        remember(0, id, x, y);
    }
    void down(int count, int id, float x, float y) {
        if (count >= 3) claimed = true;
        if (!valid) return;
        if (count != fingers + 1 || count > 3) { valid = false; return; }
        remember(fingers++, id, x, y);
    }
    private void remember(int index, int id, float x, float y) {
        ids[index] = id; positions[index * 2] = x; positions[index * 2 + 1] = y;
    }
    void move(int id, float x, float y) {
        if (!valid) return;
        for (int i = 0; i < fingers; i++) if (ids[i] == id) {
            float dx = x - positions[i * 2], dy = y - positions[i * 2 + 1];
            if (dx * dx + dy * dy > slopSquared) valid = false;
            return;
        }
    }
    void lift() { if (!claimed) valid = false; }
    boolean claimed() { return claimed; }
    boolean finish(long time) {
        long duration = time - started;
        boolean tap = valid && fingers == 3 && duration >= 0 && duration <= timeout;
        cancel();
        return tap;
    }
    void cancel() { valid = false; claimed = false; fingers = 0; }
}
