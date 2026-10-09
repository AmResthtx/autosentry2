package com.autosentry.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.autosentry.app.R;

import java.util.Locale;

/**
 * An instrument-cluster dial: ticks over a 270 degree sweep, a red zone beyond the normal
 * range, an orange needle, and the value and unit under the hub. The value takes the
 * status color. Square; takes its height from its width.
 */
public class ArcGaugeView extends View {
    private static final float START_ANGLE = 135f;
    private static final float SWEEP = 270f;
    private static final int TICK_INTERVALS = 20;

    private final Paint facePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = stroke();
    private final Paint redPaint = stroke();
    private final Paint tickPaint = stroke();
    private final Paint redTickPaint = stroke();
    private final Paint needlePaint = stroke();
    private final Paint hubPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hubRingPaint = stroke();
    private final Paint valueText = text(Typeface.create("sans-serif-condensed", Typeface.BOLD));
    private final Paint unitText = text(Typeface.MONOSPACE);
    private final Paint numeralText = text(Typeface.MONOSPACE);
    private final RectF redBounds = new RectF();

    private GaugeScale scale;
    private String unit = "";
    private boolean showNumerals;
    private double value = Double.NaN;
    private String valueLabel = "--";
    private int valueColor;

    public ArcGaugeView(Context context) {
        super(context);
        facePaint.setColor(ContextCompat.getColor(context, R.color.dial_face));
        ringPaint.setColor(ContextCompat.getColor(context, R.color.dial_ring));
        redPaint.setColor(ContextCompat.getColor(context, R.color.dash_redline));
        tickPaint.setColor(ContextCompat.getColor(context, R.color.dial_tick));
        redTickPaint.setColor(ContextCompat.getColor(context, R.color.dash_crit));
        needlePaint.setColor(ContextCompat.getColor(context, R.color.dash_accent));
        needlePaint.setStrokeCap(Paint.Cap.ROUND);
        hubPaint.setColor(ContextCompat.getColor(context, R.color.dock_bg));
        hubRingPaint.setColor(ContextCompat.getColor(context, R.color.dial_ring));
        unitText.setColor(ContextCompat.getColor(context, R.color.dash_muted));
        numeralText.setColor(ContextCompat.getColor(context, R.color.dial_numeral));
        valueColor = ContextCompat.getColor(context, R.color.dash_muted);
    }

    /** {@code showNumerals} labels the major ticks (for the large dials). */
    public void configure(GaugeScale scale, String unit, boolean showNumerals) {
        this.scale = scale;
        this.unit = unit == null ? "" : unit;
        this.showNumerals = showNumerals;
        invalidate();
    }

    /** {@code value} NaN hides the needle and shows just {@code label}. */
    public void setReading(double value, String label, int color) {
        this.value = value;
        this.valueLabel = label;
        this.valueColor = color;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED || width <= 0) {
            width = Math.round(120 * getResources().getDisplayMetrics().density);
        }
        setMeasuredDimension(width, width);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float size = Math.min(getWidth(), getHeight());
        float c = size / 2f;

        ringPaint.setStrokeWidth(size * 0.016f);
        canvas.drawCircle(c, c, size * 0.47f, facePaint);
        canvas.drawCircle(c, c, size * 0.47f, ringPaint);

        if (scale != null) {
            float redFrom = scale.hasNormalRange() ? (float) scale.fraction(scale.normalMax) : 1f;
            if (redFrom < 1f) {
                redPaint.setStrokeWidth(size * 0.028f);
                float r = size * 0.435f;
                redBounds.set(c - r, c - r, c + r, c + r);
                canvas.drawArc(redBounds, START_ANGLE + SWEEP * redFrom, SWEEP * (1f - redFrom), false, redPaint);
            }
            for (int i = 0; i <= TICK_INTERVALS; i++) {
                float f = i / (float) TICK_INTERVALS;
                boolean major = i % 5 == 0;
                Paint paint = f >= redFrom && redFrom < 1f ? redTickPaint : tickPaint;
                paint.setStrokeWidth(size * (major ? 0.016f : 0.009f));
                float inner = size * (major ? 0.335f : 0.37f);
                drawRadial(canvas, c, f, inner, size * 0.405f, paint);
            }
            if (showNumerals) {
                numeralText.setTextSize(size * 0.068f);
                for (double v : scale.majorTicks()) {
                    float[] p = point(c, (float) scale.fraction(v), size * 0.265f);
                    canvas.drawText(format(v), p[0], p[1] + size * 0.024f, numeralText);
                }
            }
            if (!Double.isNaN(value)) {
                float f = (float) scale.fraction(value);
                needlePaint.setStrokeWidth(size * 0.026f);
                drawRadial(canvas, c, f, -size * 0.07f, size * 0.36f, needlePaint);
            }
        }
        hubRingPaint.setStrokeWidth(size * 0.012f);
        canvas.drawCircle(c, c, size * 0.05f, hubPaint);
        canvas.drawCircle(c, c, size * 0.05f, hubRingPaint);

        valueText.setColor(valueColor);
        valueText.setTextSize(size * 0.17f);
        float maxWidth = size * 0.6f;
        float textWidth = valueText.measureText(valueLabel);
        if (textWidth > maxWidth) valueText.setTextSize(valueText.getTextSize() * maxWidth / textWidth);
        canvas.drawText(valueLabel, c, size * 0.79f, valueText);
        unitText.setTextSize(size * 0.06f);
        canvas.drawText(unit, c, size * 0.885f, unitText);
    }

    private static float[] point(float c, float fraction, float radius) {
        double angle = Math.toRadians(START_ANGLE + SWEEP * fraction);
        return new float[]{c + (float) (radius * Math.cos(angle)), c + (float) (radius * Math.sin(angle))};
    }

    private static void drawRadial(Canvas canvas, float c, float fraction, float from, float to, Paint paint) {
        float[] a = point(c, fraction, from);
        float[] b = point(c, fraction, to);
        canvas.drawLine(a[0], a[1], b[0], b[1], paint);
    }

    private static String format(double v) {
        return Math.abs(v - Math.rint(v)) < 1e-9 ? String.valueOf((long) Math.rint(v)) : String.format(Locale.US, "%.1f", v);
    }

    private static Paint stroke() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        return p;
    }

    private static Paint text(Typeface face) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(face);
        return p;
    }
}
