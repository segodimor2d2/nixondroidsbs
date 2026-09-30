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
        else {
            int save = canvas.save();
            canvas.translate(-mSource.getStereoOffsetPixels(), 0);
            mSource.renderTerminal(canvas);
            canvas.restoreToCount(save);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mSource == null) return false;
        // Undo the right-eye offset and compensate for the source's input transform.
        MotionEvent forwarded = MotionEvent.obtain(event);
        try {
            forwarded.offsetLocation(2 * mSource.getStereoOffsetPixels(), 0);
            return mSource.dispatchTouchEvent(forwarded);
        } finally {
            forwarded.recycle();
        }
    }
}
