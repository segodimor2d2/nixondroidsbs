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
        float[][] expected = {{3, 6}, {-6, 3}, {-3, -6}, {6, -3}};
        for (int rotation = 0; rotation < 4; rotation++) {
            GyroscopePan pan = new GyroscopePan();
            pan.movement(1_000_000_000L, 0, 0, rotation, 100, 100);
            assertArrayEquals(expected[rotation], pan.movement(1_020_000_000L, 2, 1, rotation, 100, 100), 0.001f);
        }
    }

    @Test public void sensitivityScalesMovementAndIsBounded() {
        for (float[] sample : new float[][] {{0.5f, 1.5f, 3}, {2, 6, 12}, {0, 0.3f, 0.6f}, {10, 9, 18}}) {
            GyroscopePan pan = new GyroscopePan();
            pan.setSensitivity(sample[0]);
            pan.movement(1_000_000_000L, 0, 0, 0, 100, 100);
            assertArrayEquals(new float[] {sample[1], sample[2]},
                pan.movement(1_020_000_000L, 2, 1, 0, 100, 100), 0.001f);
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
