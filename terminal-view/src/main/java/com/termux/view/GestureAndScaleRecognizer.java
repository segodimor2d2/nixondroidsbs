package com.termux.view;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ScaleGestureDetector;

/** A combination of {@link GestureDetector} and {@link ScaleGestureDetector}. */
final class GestureAndScaleRecognizer {

    public interface Listener {
        boolean onSingleTapUp(MotionEvent e);

        void onTwoFingerTap();

        boolean onDoubleTap(MotionEvent e);

        boolean onScroll(MotionEvent e2, float dx, float dy);

        boolean onFling(MotionEvent e, float velocityX, float velocityY);

        boolean onScale(float focusX, float focusY, float scale);

        boolean onDown(float x, float y);

        boolean onUp(MotionEvent e);

        void onLongPress(MotionEvent e);
    }

    private final GestureDetector mGestureDetector;
    private final ScaleGestureDetector mScaleDetector;
    final Listener mListener;
    boolean isAfterLongPress;
    private final TwoFingerTap mTwoFingerTap;
    private boolean mMultitouch;

    public GestureAndScaleRecognizer(Context context, Listener listener) {
        mListener = listener;
        mTwoFingerTap = new TwoFingerTap(ViewConfiguration.get(context).getScaledTouchSlop());

        mGestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                return mListener.onScroll(e2, dx, dy);
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                return mListener.onFling(e2, velocityX, velocityY);
            }

            @Override
            public boolean onDown(MotionEvent e) {
                return mListener.onDown(e.getX(), e.getY());
            }

            @Override
            public void onLongPress(MotionEvent e) {
                mListener.onLongPress(e);
                isAfterLongPress = true;
            }
        }, null, true /* ignoreMultitouch */);

        mGestureDetector.setOnDoubleTapListener(new GestureDetector.OnDoubleTapListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                return mListener.onSingleTapUp(e);
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                return mListener.onDoubleTap(e);
            }

            @Override
            public boolean onDoubleTapEvent(MotionEvent e) {
                return true;
            }
        });

        mScaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                mTwoFingerTap.cancel();
                return mListener.onScale(detector.getFocusX(), detector.getFocusY(), detector.getScaleFactor());
            }
        });
        mScaleDetector.setQuickScaleEnabled(false);
    }

    public void onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mMultitouch = false;
            isAfterLongPress = false;
            mTwoFingerTap.start(event.getEventTime(), event.getPointerId(0), event.getX(), event.getY());
        }
        // Include historical positions, so moving away and back is not treated as a tap.
        for (int h = 0; h < event.getHistorySize(); h++) {
            for (int i = 0; i < event.getPointerCount(); i++)
                mTwoFingerTap.move(event.getPointerId(i), event.getHistoricalX(i, h), event.getHistoricalY(i, h));
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            int index = event.getActionIndex();
            mTwoFingerTap.add(event.getEventTime(), event.getPointerId(index), event.getX(index), event.getY(index));
            if (!mMultitouch) {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                mGestureDetector.onTouchEvent(cancel);
                cancel.recycle();
            }
            mMultitouch = true;
        }
        for (int i = 0; i < event.getPointerCount(); i++)
            mTwoFingerTap.move(event.getPointerId(i), event.getX(i), event.getY(i));
        if (action == MotionEvent.ACTION_CANCEL) mTwoFingerTap.cancel();
        if (!mMultitouch) mGestureDetector.onTouchEvent(event);
        mScaleDetector.onTouchEvent(event);
        if (action == MotionEvent.ACTION_UP) {
            if (mMultitouch) {
                if (mTwoFingerTap.finish(event.getEventTime())) mListener.onTwoFingerTap();
            } else if (!isAfterLongPress) {
                mListener.onUp(event);
            }
        }
    }

    public boolean isInProgress() {
        return mScaleDetector.isInProgress();
    }

}
