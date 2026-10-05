package com.termux.view;

/** Pixel viewport over a terminal grid; scaling never changes the grid dimensions. */
final class TerminalViewport {
    float zoom = 1f;
    float x, y;

    void scale(float focusX, float focusY, float factor) {
        float nextZoom = Math.max(0.1f, Math.min(8f, zoom * factor));
        x = (x + focusX) * nextZoom / zoom - focusX;
        y = (y + focusY) * nextZoom / zoom - focusY;
        zoom = nextZoom;
    }

    void pan(float dx, float dy) {
        x += dx;
        y += dy;
    }

    void center(float terminalWidth, float terminalHeight, float viewWidth, float viewHeight) {
        x = Math.max(0, (terminalWidth * zoom - viewWidth) / 2f);
        y = Math.max(0, (terminalHeight * zoom - viewHeight) / 2f);
    }

    void clamp(float terminalWidth, float terminalHeight, float viewWidth, float viewHeight) {
        x = Math.max(0, Math.min(x, Math.max(0, terminalWidth * zoom - viewWidth)));
        y = Math.max(0, Math.min(y, Math.max(0, terminalHeight * zoom - viewHeight)));
    }

    float toTerminalX(float screenX) { return (screenX + x) / zoom; }
    float toTerminalY(float screenY) { return (screenY + y) / zoom; }
}
