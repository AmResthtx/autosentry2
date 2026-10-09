package com.autosentry.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.autosentry.app.R;

/**
 * A 270 degree arc gauge: a faint green band marks the normal range, the arc fills to the
 * current value in the status color, and the value and unit sit in the middle.
 * Square; takes its height from its width.
 */
public class ArcGaugeView extends View {
    private static final float START_ANGLE = 135f;
    private static final float SWEEP = 270f;

    private final Paint trackPaint = ring();
    private final Paint bandPaint = ring();
    private final Paint valuePaint = ring();
    private final Paint valueText = text(Typeface.DEFAULT_BOLD);
    private final Paint unitText = text(Typeface.DEFAULT);
    private final Paint scaleText = text(Typeface.DEFAULT);
    private final RectF arcBounds = new RectF();

    private GaugeScale scale;
    private String unit = "";
    private boolean showScale;
    private double value = Double.NaN;
    private String valueLabel = "--";
    private int levelColor;

    public ArcGaugeView(Context context) {
        super(context);
        trackPaint.setColor(ContextCompat.getColor(context, R.color.dash_track));
        bandPaint.setColor(ContextCompat.getColor(context, R.color.dash_band));
        valueText.setColor(ContextCompat.getColor(context, R.color.dash_text));
        unitText.setColor(ContextCompat.getColor(context, R.color.dash_muted));
        scaleText.setColor(ContextCompat.getColor(context, R.color.dash_muted));
        levelColor = ContextCompat.getColor(context, R.color.dash_muted);
    }

    /** {@code showScale} adds the min and max labels under the arc ends (for the large gauges). */
    public void configure(GaugeScale scale, String unit, boolean showScale) {
        this.scale = scale;
        this.unit = unit == null ? "" : unit;
        this.showScale = showScale;
        invalidate();
    }

    /** {@code value} NaN draws just the track and {@code label}. */
    public void setReading(double value, String label, int color) {
        this.value = value;
        this.valueLabel = label;
        this.levelColor = color;
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
        float stroke = size * 0.11f;
        float inset = stroke / 2f + size * 0.04f;
        arcBounds.set(inset, inset, size - inset, size - inset);
        trackPaint.setStrokeWidth(stroke);
        bandPaint.setStrokeWidth(stroke);
        valuePaint.setStrokeWidth(stroke * 0.6f);

        canvas.drawArc(arcBounds, START_ANGLE, SWEEP, false, trackPaint);
        if (scale != null) {
            float from = (float) scale.fraction(scale.normalMin);
            float to = (float) scale.fraction(scale.normalMax);
            if (to > from) {
                canvas.drawArc(arcBounds, START_ANGLE + SWEEP * from, SWEEP * (to - from), false, bandPaint);
            }
            double fraction = scale.fraction(value);
            if (!Double.isNaN(value) && fraction > 0) {
                valuePaint.setColor(levelColor);
                canvas.drawArc(arcBounds, START_ANGLE, SWEEP * (float) fraction, false, valuePaint);
            }
        }

        float cx = size / 2f;
        valueText.setTextSize(size * 0.24f);
        float maxWidth = size * 0.62f;
        float textWidth = valueText.measureText(valueLabel);
        if (textWidth > maxWidth) valueText.setTextSize(valueText.getTextSize() * maxWidth / textWidth);
        canvas.drawText(valueLabel, cx, size * 0.54f, valueText);
        unitText.setTextSize(size * 0.1f);
        canvas.drawText(unit, cx, size * 0.68f, unitText);

        if (showScale && scale != null) {
            scaleText.setTextSize(size * 0.075f);
            canvas.drawText(format(scale.min), size * 0.22f, size * 0.97f, scaleText);
            canvas.drawText(format(scale.max), size * 0.78f, size * 0.97f, scaleText);
        }
    }

    private static String format(double v) {
        return Math.abs(v - Math.rint(v)) < 1e-9 ? String.valueOf((long) Math.rint(v)) : String.valueOf(v);
    }

    private static Paint ring() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        return p;
    }

    private static Paint text(Typeface face) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(face);
        return p;
    }
}
