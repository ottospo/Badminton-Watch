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
import java.util.ArrayList;

/** Start-centered GPS trail. Drag to pan, pinch to zoom, double-tap to fit. */
public class TrailView extends View {
    private static final int GREEN = 0xFF35C759;
    private static final int RED = 0xFFE02020;
    private static final float MARGIN_DP = 14f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<TrailSegment> trailSegments = new ArrayList<>();
    private final TrailViewport viewport = new TrailViewport();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private final int touchSlop;
    private long displayedTripId = -1;
    private float lastTouchX;
    private float lastTouchY;
    private boolean dragging;
    private boolean panStarted;
    private TripTracker.TrailSnapshot lastDrawnSnapshot;
    private long cachedTripId = Long.MIN_VALUE;
    private int cachedPointCount = -1;
    private boolean cachedHasStart;
    private double cachedOriginLat;
    private double cachedOriginLng;
    private float cachedMaxX;
    private float cachedMaxY;
    private TripTracker.TransportMode cachedPathMode;

    private static final class TrailSegment {
        final Path path;
        final int color;

        TrailSegment(Path path, int color) {
            this.path = path;
            this.color = color;
        }
    }

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
        lastDrawnSnapshot = snapshot;
        if (displayedTripId != snapshot.tripId) {
            viewport.reset();
            displayedTripId = snapshot.tripId;
        }
        TripTracker.TrailPoint[] points = snapshot.points;
        if (points.length == 0) return;

        double originLat = snapshot.hasStart ? snapshot.startLat : points[0].lat;
        double originLng = snapshot.hasStart ? snapshot.startLng : points[0].lng;
        double metersPerLng = 111320.0 * Math.cos(Math.toRadians(originLat));
        cacheTrail(snapshot, originLat, originLng, metersPerLng);
        float width = getWidth();
        float height = getHeight();
        viewport.fit(width, height, dp(MARGIN_DP), cachedMaxX, cachedMaxY);

        float scale = viewport.scale(width, height);
        if (scale > 0f) {
            canvas.save();
            canvas.translate(width / 2f - viewport.centerX() * scale,
                    height / 2f + viewport.centerY() * scale);
            canvas.scale(scale, -scale);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2) / scale);
            paint.setStrokeCap(Paint.Cap.ROUND);
            for (TrailSegment segment : trailSegments) {
                paint.setColor(segment.color);
                canvas.drawPath(segment.path, paint);
            }
            canvas.restore();
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(GREEN);
        float startX = viewport.screenX(0, width, height);
        float startY = viewport.screenY(0, width, height);
        canvas.drawCircle(startX, startY, dp(4), paint);
        drawMarkerLabel(canvas, startX, startY,
                getContext().getString(snapshot.hasStart ? R.string.trail_start : R.string.trail_first_fix), false);
        TripTracker.TrailPoint latest = points[points.length - 1];
        paint.setColor(RED);
        float currentX = viewport.screenX(xMeters(latest.lng, originLng, metersPerLng), width, height);
        float currentY = viewport.screenY(yMeters(latest.lat, originLat), width, height);
        canvas.drawCircle(currentX, currentY, dp(4), paint);
        drawMarkerLabel(canvas, currentX, currentY, getContext().getString(R.string.trail_current), true);

        paint.setColor(0xFF000000);
        for (TripTracker.PhotoMarker marker : snapshot.photoMarkers) {
            float markerX = viewport.screenX(xMeters(marker.lng, originLng, metersPerLng),
                    width, height);
            float markerY = viewport.screenY(yMeters(marker.lat, originLat), width, height);
            canvas.drawCircle(markerX, markerY, dp(6), paint);
            paint.setColor(0xFFFFCC00);
            canvas.drawCircle(markerX, markerY, dp(4), paint);
            paint.setColor(0xFF000000);
        }
    }

    public void refreshIfChanged() {
        if (lastDrawnSnapshot != TripTracker.getInstance().copyTrail()) invalidate();
    }

    private void cacheTrail(TripTracker.TrailSnapshot snapshot, double originLat,
                            double originLng, double metersPerLng) {
        boolean sameOrigin = cachedTripId == snapshot.tripId
                && cachedHasStart == snapshot.hasStart && cachedOriginLat == originLat
                && cachedOriginLng == originLng;
        if (sameOrigin && cachedPointCount <= snapshot.points.length) {
            if (cachedPointCount < snapshot.points.length) {
                appendTrail(snapshot, cachedPointCount, originLat, originLng, metersPerLng);
            }
            return;
        }

        trailSegments.clear();
        cachedMaxX = 0f;
        cachedMaxY = 0f;
        cachedTripId = snapshot.tripId;
        cachedPointCount = 0;
        cachedHasStart = snapshot.hasStart;
        cachedOriginLat = originLat;
        cachedOriginLng = originLng;
        cachedPathMode = null;
        appendTrail(snapshot, 0, originLat, originLng, metersPerLng);
    }

    private void appendTrail(TripTracker.TrailSnapshot snapshot, int firstPoint,
                             double originLat, double originLng, double metersPerLng) {
        TripTracker.TrailPoint[] points = snapshot.points;
        int nextPoint = firstPoint;
        if (firstPoint == 0) {
            cachedPathMode = points[0].transportMode;
            Path segment = new Path();
            float x = xMeters(points[0].lng, originLng, metersPerLng);
            float y = yMeters(points[0].lat, originLat);
            segment.moveTo(x, y);
            trailSegments.add(new TrailSegment(segment, cachedPathMode.color));
            cacheBounds(x, y);
            nextPoint = 1;
        }

        for (int i = nextPoint; i < points.length; ++i) {
            TripTracker.TrailPoint point = points[i];
            float x = xMeters(point.lng, originLng, metersPerLng);
            float y = yMeters(point.lat, originLat);
            cacheBounds(x, y);
            TripTracker.TrailPoint previous = points[i - 1];
            if (point.breakBefore) {
                Path segment = new Path();
                segment.moveTo(x, y);
                cachedPathMode = point.transportMode;
                trailSegments.add(new TrailSegment(segment, cachedPathMode.color));
            } else if (point.transportMode != cachedPathMode) {
                Path segment = new Path();
                float previousX = xMeters(previous.lng, originLng, metersPerLng);
                float previousY = yMeters(previous.lat, originLat);
                segment.moveTo(previousX, previousY);
                segment.lineTo(x, y);
                cachedPathMode = point.transportMode;
                trailSegments.add(new TrailSegment(segment, cachedPathMode.color));
            } else {
                TrailSegment segment = trailSegments.get(trailSegments.size() - 1);
                segment.path.lineTo(x, y);
            }
        }
        cachedPointCount = points.length;
    }

    private void cacheBounds(float x, float y) {
        cachedMaxX = Math.max(cachedMaxX, Math.abs(x));
        cachedMaxY = Math.max(cachedMaxY, Math.abs(y));
    }

    private void drawMarkerLabel(Canvas canvas, float x, float y, String text, boolean above) {
        if (x < 0 || x > getWidth() || y < 0 || y > getHeight()) return;
        paint.setTextSize(dp(12));
        float labelX = Math.max(dp(2), Math.min(x + dp(8), getWidth() - paint.measureText(text) - dp(2)));
        float labelY = above ? Math.max(dp(12), y - dp(8)) : Math.min(getHeight() - dp(2), y + dp(16));
        canvas.drawText(text, labelX, labelY, paint);
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
