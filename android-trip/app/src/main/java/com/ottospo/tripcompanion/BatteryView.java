package com.ottospo.tripcompanion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Battery indicator matching the green outline + fill widget from the
 * T-Watch-S3 firmware UI (same as the hit counter app's BatteryView).
 */
public class BatteryView extends View {

    private static final int GREEN = 0xFF35C759;

    private int percent = 0;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public BatteryView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setPercent(int percent) {
        this.percent = Math.max(0, Math.min(100, percent));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float stroke = h / 7f;
        float terminalW = w * 0.10f;
        float bodyRight = w - terminalW;

        paint.setColor(GREEN);

        // Body outline
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        rect.set(stroke / 2, stroke / 2, bodyRight - stroke / 2, h - stroke / 2);
        canvas.drawRoundRect(rect, stroke, stroke, paint);

        // Charge fill
        float innerPad = stroke * 1.5f;
        float fillMaxW = bodyRight - innerPad * 2;
        float fillW = percent > 0 ? Math.max(fillMaxW * percent / 100f, 1f) : 0f;
        paint.setStyle(Paint.Style.FILL);
        if (fillW > 0) {
            rect.set(innerPad, innerPad, innerPad + fillW, h - innerPad);
            canvas.drawRoundRect(rect, stroke, stroke, paint);
        }

        // Positive terminal nub
        float termH = h * 0.45f;
        rect.set(bodyRight, (h - termH) / 2, w, (h + termH) / 2);
        canvas.drawRoundRect(rect, stroke, stroke, paint);
    }
}
