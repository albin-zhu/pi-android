package com.termux.x11;

/** Host-side gesture boundaries; keyboard visibility is verified on Android. */
public final class ThreeFingerTapCheck {
    private static ThreeFingerTap gesture() { return new ThreeFingerTap(8, 300); }
    private static void three(ThreeFingerTap g) {
        g.start(100, 7, 10, 20); g.down(2, 2, 30, 20); g.down(3, 19, 50, 20);
    }
    private static void require(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
    public static void main(String[] args) {
        ThreeFingerTap g = gesture();
        g.start(100, 7, 10, 20);
        require(!g.claimed() && !g.finish(150), "one finger stays application input");
        g.start(100, 7, 10, 20); g.down(2, 2, 30, 20);
        require(!g.claimed() && !g.finish(150), "two fingers stay scroll input");
        three(g); g.move(19, 54, 23); g.lift(); g.move(2, 33, 20); g.lift();
        require(g.claimed() && g.finish(250), "stationary three-finger tap with reordered IDs");
        require(!g.finish(251), "one toggle per gesture");
        three(g); g.move(2, 39, 20); g.move(2, 30, 20);
        require(!g.finish(250), "movement cannot become a tap by moving back");
        g.start(100, 7, 10, 20); g.move(7, 30, 20); g.down(2, 2, 30, 20); g.down(3, 19, 50, 20);
        require(!g.finish(250), "adding fingers during a drag does not toggle");
        three(g); require(!g.finish(401), "long hold does not toggle");
        three(g); g.down(4, 23, 70, 20);
        require(g.claimed() && !g.finish(250), "four fingers are consumed without toggling");
        three(g); g.cancel(); require(!g.finish(250), "cancel or focus loss disarms gesture");
        g.start(100, 7, 10, 20); g.down(2, 2, 30, 20); g.lift(); g.down(2, 19, 50, 20);
        require(!g.claimed() && !g.finish(250), "three successive contacts are not three simultaneous fingers");
        three(g); g.lift(); g.down(3, 23, 70, 20);
        require(!g.finish(250), "replacement finger disarms gesture");
        three(g); require(g.finish(250), "new gesture recovers after cancellation");
        System.out.println("Three-finger tap, drag, scroll, timeout, cancellation and pointer identity checks passed");
    }
}
