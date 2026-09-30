package com.termux.view;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.termux.terminal.TerminalSession;

/** Second SBS eye: renders the source terminal directly, without owning a PTY or input connection. */
public final class TerminalMirrorView extends View {
    private TerminalView mSource;

    public TerminalMirrorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(false);
        setFocusableInTouchMode(false);
    }

    void setSource(TerminalView source) {
        mSource = source;
        invalidate();
    }

    public TerminalSession getCurrentSession() {
        return mSource == null ? null : mSource.getCurrentSession();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mSource == null) canvas.drawColor(0xFF000000);
        else mSource.renderTerminal(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mSource == null) return false;
        // Coordinates are already local to an equally sized eye. Preserve the original event.
        MotionEvent forwarded = MotionEvent.obtain(event);
        try {
            return mSource.dispatchTouchEvent(forwarded);
        } finally {
            forwarded.recycle();
        }
    }
}
