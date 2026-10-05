package com.termux.app;

/** Converts angular velocity to viewport movement, independent of the terminal grid. */
final class GyroscopePan {
    private long previousTimestamp;
    private float sensitivity = 1f;

    void setSensitivity(float value) {
        sensitivity = Math.max(0.1f, Math.min(3f, value));
    }

    void reset() { previousTimestamp = 0; }

    float[] movement(long timestamp, float axisX, float axisY, int rotation,
                     int width, int height) {
        long previous = previousTimestamp;
        previousTimestamp = timestamp;
        if (previous == 0 || timestamp <= previous) return new float[] {0, 0};
        float seconds = (timestamp - previous) / 1_000_000_000f;
        // Ignore gaps after suspension rather than jumping across the terminal.
        if (seconds > 0.1f) return new float[] {0, 0};
        float x = -axisY, y = -axisX;
        switch (rotation) {
            case 1: x = axisX; y = -axisY; break;
            case 2: x = axisY; y = axisX; break;
            case 3: x = -axisX; y = axisY; break;
            default: break;
        }
        // Suppress small sensor noise when the phone is still.
        if (Math.abs(x) < 0.015f) x = 0;
        if (Math.abs(y) < 0.015f) y = 0;
        return new float[] {-x * seconds * width * 1.5f * sensitivity, -y * seconds * height * 1.5f * sensitivity};
    }
}
