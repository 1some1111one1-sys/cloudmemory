package com.cloudbridge.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * One picture that fits the screen, can be pinched and double-tapped to zoom, dragged when zoomed, and swiped
 * sideways (when not zoomed) to ask for the next or the previous picture.
 */
final class PhotoView extends View {
    interface Listener {
        /** A single tap (the screen shows or hides its bars). */
        void onTap();

        /** True when there is a picture in that direction (+1 next, -1 previous). */
        boolean canSwipe(int dir);

        /** The picture slid away; show the next (+1) or the previous (-1) one. */
        void onSwipe(int dir);
    }

    private static final float MAX_ZOOM = 6f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector scaler;
    private final GestureDetector detector;
    private Listener listener;
    private Bitmap bmp;
    private int rotation;
    private float zoom = 1f;
    private float panX, panY;
    private float drag;
    private boolean swiped;

    PhotoView(Context c) {
        super(c);
        paint.setFilterBitmap(true);
        setClickable(true);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                if (bmp == null) return true;
                float next = Math.max(1f, Math.min(MAX_ZOOM, zoom * d.getScaleFactor()));
                zoomAround(next, d.getFocusX(), d.getFocusY());
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                if (zoom < 1.05f) resetZoom();
            }
        });
        detector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scaler.isInProgress()) return false;
                if (zoom > 1.01f) {
                    panX -= dx;
                    panY -= dy;
                    clampPan();
                    invalidate();
                } else {
                    drag -= dx;
                    setTranslationX(drag);
                }
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (zoom <= 1.01f && Math.abs(vx) > 900 && Math.abs(vx) > Math.abs(vy)) {
                    swipe(vx < 0 ? 1 : -1);
                    return true;
                }
                return false;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (bmp == null) return true;
                if (zoom > 1.01f) resetZoom();
                else zoomAround(2.5f, e.getX(), e.getY());
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (listener != null) listener.onTap();
                return true;
            }
        });
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** Shows a picture (null for none), fitted to the screen and turned the right way up. */
    void setImage(Bitmap b) {
        bmp = b;
        rotation = 0;
        resetZoom();
        drag = 0;
        setTranslationX(0);
        if (b != null) {
            setAlpha(0f);
            animate().alpha(1f).setDuration(160).start();
        }
        invalidate();
    }

    boolean hasImage() {
        return bmp != null;
    }

    /** Turns the picture a quarter turn clockwise. */
    void rotate() {
        if (bmp == null) return;
        rotation = (rotation + 90) % 360;
        resetZoom();
    }

    private void resetZoom() {
        zoom = 1f;
        panX = 0;
        panY = 0;
        invalidate();
    }

    private float shownWidth() {
        return rotation % 180 == 0 ? bmp.getWidth() : bmp.getHeight();
    }

    private float shownHeight() {
        return rotation % 180 == 0 ? bmp.getHeight() : bmp.getWidth();
    }

    private float fit() {
        return Math.min(getWidth() / shownWidth(), getHeight() / shownHeight());
    }

    /** Zooms to {@code next}, keeping the point (fx, fy) of the screen where it is. */
    private void zoomAround(float next, float fx, float fy) {
        float cx = fx - getWidth() / 2f;
        float cy = fy - getHeight() / 2f;
        float k = next / zoom;
        panX = cx - (cx - panX) * k;
        panY = cy - (cy - panY) * k;
        zoom = next;
        clampPan();
        invalidate();
    }

    private void clampPan() {
        float s = fit() * zoom;
        float maxX = Math.max(0f, (shownWidth() * s - getWidth()) / 2f);
        float maxY = Math.max(0f, (shownHeight() * s - getHeight()) / 2f);
        panX = Math.max(-maxX, Math.min(maxX, panX));
        panY = Math.max(-maxY, Math.min(maxY, panY));
    }

    private void swipe(int dir) {
        swiped = true;
        if (listener == null || !listener.canSwipe(dir)) {
            animate().translationX(0).setDuration(150).start();
            drag = 0;
            return;
        }
        final int d = dir;
        animate().translationX(dir > 0 ? -getWidth() : getWidth()).setDuration(130).withEndAction(() -> {
            drag = 0;
            setTranslationX(0);
            if (listener != null) listener.onSwipe(d);
        }).start();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) swiped = false;
        scaler.onTouchEvent(e);
        detector.onTouchEvent(e);
        if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && !swiped && drag != 0) {
            if (Math.abs(drag) > getWidth() * 0.25f) {
                swipe(drag < 0 ? 1 : -1);
            } else {
                animate().translationX(0).setDuration(150).start();
                drag = 0;
            }
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (bmp == null) return;
        float s = fit() * zoom;
        canvas.save();
        canvas.translate(getWidth() / 2f + panX, getHeight() / 2f + panY);
        canvas.rotate(rotation);
        canvas.scale(s, s);
        canvas.drawBitmap(bmp, -bmp.getWidth() / 2f, -bmp.getHeight() / 2f, paint);
        canvas.restore();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (bmp != null) {
            clampPan();
            invalidate();
        }
    }
}
