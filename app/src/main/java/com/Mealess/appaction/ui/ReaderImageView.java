package com.Mealess.appaction.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/**
 * Zoomable comic/PDF page.
 *
 * Double tap toggles between fit and 2x, pinch zooms up to 4x, dragging pans while
 * zoomed. Single taps and horizontal swipes are forwarded to the reader so paging
 * keeps working; the built-in ImageView pan/zoom mess is deliberately avoided.
 */
public class ReaderImageView extends ImageView {

    public interface Listener {
        void onTap(float x);
        void onSwipeLeft();
        void onSwipeRight();
        void onLongPress();
    }

    private final Matrix matrix = new Matrix();
    private final float[] values = new float[9];
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private Listener listener;

    private float baseScale = 1f;
    private float currentScale = 1f;
    private float lastX, lastY;
    private boolean dragging;

    public ReaderImageView(Context context) {
        this(context, null);
    }

    public ReaderImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);
        setClickable(true);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                float target = currentScale * factor;
                if (target < 1f) factor = 1f / currentScale;
                else if (target > 4f) factor = 4f / currentScale;
                if (factor == 1f) return true;
                currentScale *= factor;
                matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                applyMatrix();
                return true;
            }
        });
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (currentScale > 1.05f) return true;
                if (listener != null) listener.onTap(e.getX());
                return true;
            }

            @Override public void onLongPress(MotionEvent e) {
                if (listener != null) listener.onLongPress();
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                if (currentScale > 1.05f) {
                    resetToFit();
                } else {
                    float target = Math.min(3f, 2.2f);
                    matrix.postScale(target, target, e.getX(), e.getY());
                    currentScale *= target;
                    applyMatrix();
                }
                return true;
            }

            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                if (currentScale > 1.05f) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 70 && listener != null) {
                    if (dx < 0) listener.onSwipeLeft();
                    else listener.onSwipeRight();
                    return true;
                }
                return false;
            }
        });
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void resetToFit() {
        currentScale = 1f;
        fitMatrix();
    }

    @Override
    public void setImageBitmap(Bitmap bm) {
        super.setImageBitmap(bm);
        post(new Runnable() {
            @Override public void run() { resetToFit(); }
        });
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (currentScale <= 1.05f) fitMatrix();
    }

    private void fitMatrix() {
        Drawable d = getDrawable();
        if (d == null) return;
        int iw = d.getIntrinsicWidth();
        int ih = d.getIntrinsicHeight();
        int vw = getWidth();
        int vh = getHeight();
        if (iw <= 0 || ih <= 0 || vw <= 0 || vh <= 0) return;
        baseScale = Math.min((float) vw / iw, (float) vh / ih);
        matrix.reset();
        matrix.postScale(baseScale, baseScale);
        matrix.postTranslate((vw - iw * baseScale) / 2f, (vh - ih * baseScale) / 2f);
        setImageMatrix(matrix);
    }

    private void applyMatrix() {
        clampTranslate();
        setImageMatrix(matrix);
    }

    private void clampTranslate() {
        Drawable d = getDrawable();
        if (d == null) return;
        matrix.getValues(values);
        float scale = values[Matrix.MSCALE_X];
        float tx = values[Matrix.MTRANS_X];
        float ty = values[Matrix.MTRANS_Y];
        float w = d.getIntrinsicWidth() * scale;
        float h = d.getIntrinsicHeight() * scale;
        int vw = getWidth();
        int vh = getHeight();
        float minX = Math.min(0f, vw - w), maxX = Math.max(0f, vw - w);
        float minY = Math.min(0f, vh - h), maxY = Math.max(0f, vh - h);
        float ntx = w <= vw ? (vw - w) / 2f : Math.max(minX, Math.min(maxX, tx));
        float nty = h <= vh ? (vh - h) / 2f : Math.max(minY, Math.min(maxY, ty));
        if (ntx != tx || nty != ty) {
            values[Matrix.MTRANS_X] = ntx;
            values[Matrix.MTRANS_Y] = nty;
            matrix.setValues(values);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress() && currentScale > 1.05f) {
                    float dx = event.getX() - lastX;
                    float dy = event.getY() - lastY;
                    if (Math.abs(dx) > 2 || Math.abs(dy) > 2) {
                        dragging = true;
                        matrix.postTranslate(dx, dy);
                        applyMatrix();
                    }
                    lastX = event.getX();
                    lastY = event.getY();
                } else {
                    lastX = event.getX();
                    lastY = event.getY();
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                dragging = true;
                break;
            default:
                break;
        }
        return true;
    }
}
