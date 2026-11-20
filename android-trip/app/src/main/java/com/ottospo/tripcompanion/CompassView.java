package com.ottospo.tripcompanion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Compass dial: a port of the T-Watch-S3 firmware's compass face
 * (src/compass.cpp). The green marker shows the bearing back to the trip's
 * starting point and the red arrow the GPS course over ground (the phone,
 * like the watch, derives heading from movement — steer so they line up).
 * Redraws only when the displayed state changes, like the firmware.
 */
public class CompassView extends View {

    private static final int GRAY = 0xFF808080;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREEN = 0xFF35C759;
    private static final int RED = 0xFFE02020;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private double bearingDeg = 0.0;
    private double courseDeg = 0.0;
    private boolean hasStart = false;
    private boolean courseValid = false;

    // Last drawn state, for redraw-on-change (mirrors compass_update()).
    private double drawnBearingDeg = -1.0;
    private double drawnCourseDeg = -1.0;
    private boolean drawnHasStart = false;
    private boolean drawnCourseValid = false;

    public CompassView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** Push fresh trip state; triggers a redraw only when something changed. */
    public void setState(double bearingDeg, double courseDeg, boolean hasStart, boolean courseValid) {
        this.bearingDeg = bearingDeg;
        this.courseDeg = courseDeg;
        this.hasStart = hasStart;
        this.courseValid = courseValid;
        if (bearingDeg != drawnBearingDeg || courseDeg != drawnCourseDeg
                || hasStart != drawnHasStart || courseValid != drawnCourseValid) {
            drawnBearingDeg = bearingDeg;
            drawnCourseDeg = courseDeg;
            drawnHasStart = hasStart;
            drawnCourseValid = courseValid;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float size = Math.min(getWidth(), getHeight());
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = size / 2f * 0.9f;

        paint.setStrokeCap(Paint.Cap.ROUND);

        // Dial outline
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(GRAY);
        paint.setStrokeWidth(dp(2));
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius);
        canvas.drawOval(rect, paint);

        // Cardinal ticks, north emphasized
        for (int i = 0; i < 4; ++i) {
            boolean north = i == 0;
            paint.setColor(north ? WHITE : GRAY);
            paint.setStrokeWidth(dp(north ? 4 : 3));
            drawRadialLine(canvas, cx, cy, i * 90.0, radius - dp(10), radius - dp(2));
        }

        // Green marker: bearing back to the starting point (full radius so it
        // stays visible next to the shorter course arrow when both align)
        if (hasStart) {
            paint.setColor(GREEN);
            paint.setStrokeWidth(dp(3));
            drawRadialLine(canvas, cx, cy, bearingDeg, 0, radius - dp(5));
        }

        // Red arrow: course over ground (direction of travel)
        if (courseValid) {
            paint.setColor(RED);
            paint.setStrokeWidth(dp(3));
            float tipR = radius - dp(18);
            drawRadialLine(canvas, cx, cy, courseDeg, 0, tipR);
            drawArrowhead(canvas, cx, cy, courseDeg, tipR);
        }
    }

    // Line between two radii on the dial; 0 deg = north = up.
    private void drawRadialLine(Canvas canvas, float cx, float cy, double degrees,
                                float innerR, float outerR) {
        double rad = Math.toRadians(degrees);
        canvas.drawLine(cx + (float) (innerR * Math.sin(rad)),
                cy - (float) (innerR * Math.cos(rad)),
                cx + (float) (outerR * Math.sin(rad)),
                cy - (float) (outerR * Math.cos(rad)), paint);
    }

    private void drawArrowhead(Canvas canvas, float cx, float cy, double degrees, float tipR) {
        double rad = Math.toRadians(degrees);
        float tipX = cx + (float) (tipR * Math.sin(rad));
        float tipY = cy - (float) (tipR * Math.cos(rad));
        paint.setStrokeWidth(dp(2.5f));
        for (int side = -1; side <= 1; side += 2) {
            double back = Math.toRadians(degrees + side * 150.0);
            canvas.drawLine(tipX, tipY,
                    tipX + (float) (dp(14) * Math.sin(back)),
                    tipY - (float) (dp(14) * Math.cos(back)), paint);
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
