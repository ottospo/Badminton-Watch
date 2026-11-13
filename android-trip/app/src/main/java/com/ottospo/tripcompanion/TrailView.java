package com.ottospo.tripcompanion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;

/** Start-centered GPS trail. Drag to pan, pinch to zoom, double-tap to fit. */
public class TrailView extends View {
    private static final int GREEN = 0xFF35C759;
    private static final int RED = 0xFFE02020;
    private static final int TRAIL_BLUE = 0xFF5AC8FA;
    private static final float MARGIN_DP = 14f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final TrailViewport viewport = new TrailViewport();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private final int touchSlop;
    private long displayedTripId = -1;
    private float lastTouchX;
    private float lastTouchY;
    private boolean dragging;
    private boolean panStarted;

    public TrailView(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        viewport.zoom(detector.getScaleFactor(), detector.getFocusX(),
                                detector.getFocusY(), getWidth(), getHeight());
                        invalidate();
                        return true;
                    }
                });
        gestureDetector = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent event) {
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent event) {
                        viewport.reset();
                        invalidate();
                        return true;
                    }

                    @Override
                    public boolean onSingleTapUp(MotionEvent event) {
                        return performClick();
                    }
                });
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        TripTracker.TrailSnapshot snapshot = TripTracker.getInstance().copyTrail();
        if (displayedTripId != snapshot.tripId) {
            viewport.reset();
            displayedTripId = snapshot.tripId;
        }
        TripTracker.TrailPoint[] points = snapshot.points;
        if (points.length == 0) return;

        double originLat = snapshot.hasStart ? snapshot.startLat : points[0].lat;
        double originLng = snapshot.hasStart ? snapshot.startLng : points[0].lng;
        double metersPerLng = 111320.0 * Math.cos(Math.toRadians(originLat));
        float maxX = 0f;
        float maxY = 0f;
        for (TripTracker.TrailPoint point : points) {
            maxX = Math.max(maxX, Math.abs(xMeters(point.lng, originLng, metersPerLng)));
            maxY = Math.max(maxY, Math.abs(yMeters(point.lat, originLat)));
        }
        float width = getWidth();
        float height = getHeight();
        viewport.fit(width, height, dp(MARGIN_DP), maxX, maxY);

        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(TRAIL_BLUE);
        paint.setStrokeWidth(dp(2));
        paint.setStrokeCap(Paint.Cap.ROUND);
        path.reset();
        for (int i = 0; i < points.length; ++i) {
            float x = xMeters(points[i].lng, originLng, metersPerLng);
            float y = yMeters(points[i].lat, originLat);
            float px = viewport.screenX(x, width, height);
            float py = viewport.screenY(y, width, height);
            if (i == 0 || points[i].breakBefore) {
                path.moveTo(px, py);
            } else {
                path.lineTo(px, py);
            }
        }
        canvas.drawPath(path, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(GREEN);
        canvas.drawCircle(viewport.screenX(0, width, height),
                viewport.screenY(0, width, height), dp(4), paint);
        TripTracker.TrailPoint latest = points[points.length - 1];
        paint.setColor(RED);
        canvas.drawCircle(viewport.screenX(xMeters(latest.lng, originLng, metersPerLng),
                        width, height),
                viewport.screenY(yMeters(latest.lat, originLat), width, height), dp(4), paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                dragging = true;
                panStarted = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() == 1 && !scaleDetector.isInProgress()) {
                    float x = event.getX();
                    float y = event.getY();
                    if (dragging && !panStarted
                            && Math.hypot(x - lastTouchX, y - lastTouchY) > touchSlop) {
                        panStarted = true;
                    }
                    if (dragging && panStarted) {
                        viewport.pan(x - lastTouchX, y - lastTouchY, getWidth(), getHeight());
                        lastTouchX = x;
                        lastTouchY = y;
                        invalidate();
                    } else if (!dragging) {
                        lastTouchX = x;
                        lastTouchY = y;
                        panStarted = false;
                    }
                    dragging = true;
                } else {
                    dragging = false;
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                break;
            default:
                break;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private static float xMeters(double lng, double originLng, double metersPerLng) {
        double delta = (lng - originLng + 540.0) % 360.0 - 180.0;
        return (float) (delta * metersPerLng);
    }

    private static float yMeters(double lat, double originLat) {
        return (float) ((lat - originLat) * 111320.0);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
