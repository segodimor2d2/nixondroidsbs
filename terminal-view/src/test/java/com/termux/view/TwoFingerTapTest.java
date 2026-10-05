package com.termux.view;

import org.junit.Test;
import static org.junit.Assert.*;

public class TwoFingerTapTest {
    @Test public void simultaneousTapTogglesOnlyOnce() {
        TwoFingerTap tap = new TwoFingerTap(10);
        tap.start(1000, 0, 20, 20);
        tap.add(1050, 1, 80, 20);
        tap.move(0, 21, 21);
        assertTrue(tap.finish(1150));
        assertFalse(tap.finish(1150));
    }

    @Test public void pinchSingleFingerAndLongPressDoNotToggle() {
        TwoFingerTap tap = new TwoFingerTap(10);
        tap.start(1000, 0, 20, 20);
        assertFalse(tap.finish(1100));
        tap.start(1000, 0, 20, 20);
        tap.add(1050, 1, 80, 20);
        tap.move(1, 100, 20);
        assertFalse(tap.finish(1150));
        tap.start(1000, 0, 20, 20);
        tap.add(1050, 1, 80, 20);
        assertFalse(tap.finish(1500));
    }

    @Test public void lateSecondFingerCancellationAndThirdFingerDoNotToggle() {
        TwoFingerTap tap = new TwoFingerTap(10);
        tap.start(1000, 0, 20, 20);
        tap.add(1200, 1, 80, 20);
        assertFalse(tap.finish(1250));
        tap.start(1000, 0, 20, 20);
        tap.add(1050, 1, 80, 20);
        tap.cancel();
        assertFalse(tap.finish(1150));
        tap.start(1000, 0, 20, 20);
        tap.add(1050, 1, 80, 20);
        tap.add(1060, 2, 50, 50);
        assertFalse(tap.finish(1150));
    }
}
