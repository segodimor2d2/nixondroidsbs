package com.termux.app;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;

public class GyroscopePanTest {
    @Test public void firstSampleAndStationaryNoiseDoNotMoveViewport() {
        GyroscopePan pan = new GyroscopePan();
        assertArrayEquals(new float[] {0, 0}, pan.movement(1_000_000_000L, 1, 1, 0, 100, 100), 0.001f);
        assertArrayEquals(new float[] {0, 0}, pan.movement(1_020_000_000L, 0.01f, -0.01f, 0, 100, 100), 0.001f);
    }

    @Test public void movementFollowsScreenOrientation() {
        float[][] expected = {{-3, -6}, {6, -3}, {3, 6}, {-6, 3}};
        for (int rotation = 0; rotation < 4; rotation++) {
            GyroscopePan pan = new GyroscopePan();
            pan.movement(1_000_000_000L, 0, 0, rotation, 100, 100);
            assertArrayEquals(expected[rotation], pan.movement(1_020_000_000L, 2, 1, rotation, 100, 100), 0.001f);
        }
    }

    @Test public void pauseAndResetPreventJumps() {
        GyroscopePan pan = new GyroscopePan();
        pan.movement(1_000_000_000L, 0, 0, 0, 100, 100);
        assertArrayEquals(new float[] {0, 0}, pan.movement(3_000_000_000L, 2, 1, 0, 100, 100), 0.001f);
        pan.reset();
        assertArrayEquals(new float[] {0, 0}, pan.movement(3_020_000_000L, 2, 1, 0, 100, 100), 0.001f);
    }
}
