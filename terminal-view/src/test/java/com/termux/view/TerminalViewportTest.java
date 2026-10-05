package com.termux.view;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class TerminalViewportTest {
    @Test public void zoomKeepsTerminalPointUnderFinger() {
        TerminalViewport viewport = new TerminalViewport();
        viewport.x = 300;
        viewport.y = 200;
        float columnPixel = viewport.toTerminalX(120);
        float rowPixel = viewport.toTerminalY(80);
        viewport.scale(120, 80, 2);
        assertEquals(columnPixel, viewport.toTerminalX(120), 0.001f);
        assertEquals(rowPixel, viewport.toTerminalY(80), 0.001f);
        viewport.scale(120, 80, 0.5f);
        assertEquals(300, viewport.x, 0.001f);
        assertEquals(200, viewport.y, 0.001f);
    }

    @Test public void buttonsPanInAllFourDirectionsWithoutChangingZoom() {
        TerminalViewport viewport = new TerminalViewport();
        viewport.zoom = 2f;
        viewport.pan(100, 0);
        assertEquals(50, viewport.toTerminalX(0), 0.001f);
        viewport.pan(0, 80);
        assertEquals(40, viewport.toTerminalY(0), 0.001f);
        viewport.pan(-100, 0);
        viewport.pan(0, -80);
        assertEquals(0, viewport.x, 0.001f);
        assertEquals(0, viewport.y, 0.001f);
        assertEquals(2, viewport.zoom, 0.001f);
    }

    @Test public void panStopsAtBothTerminalEdges() {
        TerminalViewport viewport = new TerminalViewport();
        viewport.x = 5000;
        viewport.y = -20;
        viewport.clamp(2000, 1600, 400, 300);
        assertEquals(1600, viewport.x, 0.001f);
        assertEquals(0, viewport.y, 0.001f);
        viewport.scale(0, 0, 0.1f);
        viewport.clamp(2000, 1600, 400, 300);
        assertEquals(0, viewport.x, 0.001f);
        assertEquals(0, viewport.y, 0.001f);
    }

    @Test public void zoomLimitsKeepCoordinateConversionFinite() {
        TerminalViewport viewport = new TerminalViewport();
        viewport.scale(0, 0, 1000);
        assertEquals(8, viewport.zoom, 0.001f);
        viewport.scale(0, 0, 0);
        assertEquals(0.1f, viewport.zoom, 0.001f);
        assertEquals(1000, viewport.toTerminalX(100), 0.001f);
    }
}
