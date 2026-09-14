package com.modernmarwar.tailoringstaff;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

final class MeasurementGuideView extends View {
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tape = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String code = "CHEST_CIRCUMFERENCE";

    MeasurementGuideView(Context context) {
        super(context);
        body.setColor(Color.rgb(75, 96, 120));
        body.setStyle(Paint.Style.STROKE);
        body.setStrokeWidth(dp(5));
        body.setStrokeCap(Paint.Cap.ROUND);
        body.setStrokeJoin(Paint.Join.ROUND);
        tape.setColor(Color.rgb(23, 105, 224));
        tape.setStyle(Paint.Style.STROKE);
        tape.setStrokeWidth(dp(7));
        tape.setStrokeCap(Paint.Cap.ROUND);
        dot.setColor(Color.rgb(220, 38, 38));
        dot.setStyle(Paint.Style.FILL);
        setBackgroundColor(Color.rgb(238, 246, 255));
    }

    void setMeasurementCode(String value) {
        code = value == null ? "" : value;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float head = h * 0.14f;
        canvas.drawCircle(cx, head, h * 0.075f, body);

        Path outline = new Path();
        outline.moveTo(cx - w * 0.15f, h * 0.29f);
        outline.quadTo(cx - w * 0.23f, h * 0.42f, cx - w * 0.18f, h * 0.62f);
        outline.lineTo(cx - w * 0.10f, h * 0.70f);
        outline.lineTo(cx - w * 0.11f, h * 0.93f);
        outline.moveTo(cx + w * 0.15f, h * 0.29f);
        outline.quadTo(cx + w * 0.23f, h * 0.42f, cx + w * 0.18f, h * 0.62f);
        outline.lineTo(cx + w * 0.10f, h * 0.70f);
        outline.lineTo(cx + w * 0.11f, h * 0.93f);
        outline.moveTo(cx - w * 0.15f, h * 0.29f);
        outline.lineTo(cx, h * 0.25f);
        outline.lineTo(cx + w * 0.15f, h * 0.29f);
        outline.moveTo(cx, h * 0.25f);
        outline.lineTo(cx, h * 0.68f);
        outline.moveTo(cx - w * 0.10f, h * 0.70f);
        outline.lineTo(cx + w * 0.10f, h * 0.70f);
        canvas.drawPath(outline, body);

        if (code.contains("CHEST")) horizontal(canvas, cx, w, h * 0.40f, 0.18f);
        else if (code.contains("SHOULDER")) horizontal(canvas, cx, w, h * 0.29f, 0.16f);
        else if (code.contains("WAIST")) horizontal(canvas, cx, w, h * 0.55f, 0.15f);
        else if (code.contains("BELLY")) horizontal(canvas, cx, w, h * 0.59f, 0.16f);
        else if (code.contains("HIP")) horizontal(canvas, cx, w, h * 0.67f, 0.17f);
        else if (code.contains("NECK")) horizontal(canvas, cx, w, h * 0.235f, 0.07f);
        else if (code.contains("BICEP")) horizontal(canvas, cx - w * 0.18f, w, h * 0.41f, 0.055f);
        else if (code.contains("WRIST")) horizontal(canvas, cx - w * 0.18f, w, h * 0.61f, 0.045f);
        else if (code.contains("THIGH")) horizontal(canvas, cx - w * 0.07f, w, h * 0.76f, 0.07f);
        else if (code.contains("KNEE")) horizontal(canvas, cx - w * 0.07f, w, h * 0.83f, 0.06f);
        else if (code.contains("CALF")) horizontal(canvas, cx - w * 0.07f, w, h * 0.88f, 0.055f);
        else if (code.contains("ANKLE")) horizontal(canvas, cx - w * 0.07f, w, h * 0.93f, 0.045f);
        else if (code.contains("ARM")) line(canvas, cx - w * 0.14f, h * 0.30f, cx - w * 0.19f, h * 0.61f);
        else if (code.contains("INSEAM")) line(canvas, cx, h * 0.70f, cx - w * 0.04f, h * 0.94f);
        else if (code.contains("OUTSEAM")) line(canvas, cx - w * 0.10f, h * 0.68f, cx - w * 0.11f, h * 0.94f);
        else if (code.contains("BACK")) line(canvas, cx, h * 0.25f, cx, h * 0.67f);
        else line(canvas, cx + w * 0.24f, h * 0.08f, cx + w * 0.24f, h * 0.94f);
    }

    private void horizontal(Canvas canvas, float cx, float width, float y, float halfRatio) {
        line(canvas, cx - width * halfRatio, y, cx + width * halfRatio, y);
    }

    private void line(Canvas canvas, float x1, float y1, float x2, float y2) {
        canvas.drawLine(x1, y1, x2, y2, tape);
        canvas.drawCircle(x1, y1, dp(6), dot);
        canvas.drawCircle(x2, y2, dp(6), dot);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
