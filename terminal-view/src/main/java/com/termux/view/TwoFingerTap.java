package com.termux.view;

/** Recognizes a short two-finger tap without confusing a pinch with a tap. */
final class TwoFingerTap {
    private final float slopSquared;
    private long started;
    private int firstId, secondId;
    private float firstX, firstY, secondX, secondY;
    private boolean candidate, secondDown;

    TwoFingerTap(float slop) { slopSquared = slop * slop; }

    void start(long time, int id, float x, float y) {
        started = time;
        firstId = id;
        firstX = x;
        firstY = y;
        candidate = true;
        secondDown = false;
    }

    void add(long time, int id, float x, float y) {
        if (secondDown || time - started > 150) candidate = false;
        secondDown = true;
        secondId = id;
        secondX = x;
        secondY = y;
    }

    void move(int id, float x, float y) {
        float dx = x - (id == firstId ? firstX : secondX);
        float dy = y - (id == firstId ? firstY : secondY);
        if ((id != firstId && id != secondId) || dx * dx + dy * dy > slopSquared) cancel();
    }

    void cancel() { candidate = false; }

    boolean finish(long time) {
        boolean tapped = candidate && secondDown && time - started <= 300;
        cancel();
        return tapped;
    }
}
