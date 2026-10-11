package com.autosentry.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.autosentry.app.R;

/**
 * One dashboard tile: a dark rounded square with a 240 degree dial
 * (minor and major ticks, round-number labels, a white tapered needle on a gray hub), the
 * unit above the hub, the value near the bottom and the name under it. Readings with no dial
 * get a flat tile with the value in the middle. Value and unit colors are per gauge and do
 * not mean anything; the border turns amber or red when the reading is out
 * of range. Square; takes its height from its width.
 */
public class GaugeTileView extends View {
    private static final float START_ANGLE = 150f; // the dial starts low on the left
    private static final float SWEEP = 240f;
    private static final int MINORS_BETWEEN_STOPS = 4;

    private final int[] palette;
    private final int borderColor, warnColor, critColor, tickColor, tickMajorColor, numeralColor, needleColor,
            hubColor, hubDarkColor, nameColor, mutedColor, tileTop, tileMid, tileBottom;

    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = stroke();
    private final Paint tickPaint = stroke();
    private final Paint needlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hubPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hubCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numeralText = text(Typeface.DEFAULT);
    private final Paint unitText = text(Typeface.DEFAULT);
    private final Paint valueText = text(Typeface.DEFAULT_BOLD);
    private final Paint nameText = text(Typeface.DEFAULT);
    private final RectF tileBounds = new RectF();
    private final Path needle = new Path();

    private String name = "";
    private String unit = "";
    private GaugeScale scale;
    private int valueColor;
    private double value = Double.NaN;
    private String valueLabel = "--";
    private GaugeScale.Level level = GaugeScale.Level.OK;
    private boolean unavailable;

    public GaugeTileView(Context context) {
        super(context);
        palette = new int[]{
                color(context, R.color.fs_val_red), color(context, R.color.fs_val_green),
                color(context, R.color.fs_val_orange), color(context, R.color.fs_val_teal),
                color(context, R.color.fs_val_yellow), color(context, R.color.fs_val_pink),
                color(context, R.color.fs_val_blue)};
        borderColor = color(context, R.color.fs_tile_border);
        warnColor = color(context, R.color.fs_warn);
        critColor = color(context, R.color.fs_crit);
        tickColor = color(context, R.color.fs_tick);
        tickMajorColor = color(context, R.color.fs_tick_major);
        numeralColor = color(context, R.color.fs_numeral);
        needleColor = color(context, R.color.fs_needle);
        hubColor = color(context, R.color.fs_hub);
        hubDarkColor = color(context, R.color.fs_hub_dark);
        nameColor = color(context, R.color.fs_text);
        mutedColor = color(context, R.color.fs_muted);
        tileTop = color(context, R.color.fs_tile_top);
        tileMid = color(context, R.color.fs_tile_mid);
        tileBottom = color(context, R.color.fs_tile_bottom);
        needlePaint.setColor(needleColor);
        needlePaint.setStyle(Paint.Style.FILL);
        numeralText.setColor(numeralColor);
        valueColor = palette[0];
    }

    /** A fixed color per reading from the palette, so a gauge keeps its color between launches. */
    public static int paletteIndex(int pidId) {
        return Math.abs((pidId ^ (pidId >> 3)) * 7 + (pidId >> 1)) % 7;
    }

    /** {@code scale} null draws a flat tile. */
    public void configure(String name, String unit, GaugeScale scale, int pidId) {
        this.name = name;
        this.unit = unit == null ? "" : unit;
        this.scale = scale;
        this.valueColor = palette[paletteIndex(pidId)];
        invalidate();
    }

    /** {@code value} NaN hides the needle. {@code unavailable} marks a reading the truck doesn't answer. */
    public void setReading(double value, String label, GaugeScale.Level level, boolean unavailable) {
        this.value = value;
        this.valueLabel = label;
        this.level = level;
        this.unavailable = unavailable;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED || width <= 0) {
            width = Math.round(110 * getResources().getDisplayMetrics().density);
        }
        setMeasuredDimension(width, width);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float s = Math.min(getWidth(), getHeight());
        float border = Math.max(getResources().getDisplayMetrics().density * 1.5f, s * 0.022f);
        float corner = s * 0.09f;

        backgroundPaint.setShader(new LinearGradient(0, 0, 0, s,
                new int[]{tileTop, tileMid, tileBottom}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        tileBounds.set(border / 2f, border / 2f, s - border / 2f, s - border / 2f);
        canvas.drawRoundRect(tileBounds, corner, corner, backgroundPaint);
        borderPaint.setStrokeWidth(border);
        borderPaint.setColor(level == GaugeScale.Level.CRITICAL ? critColor
                : level == GaugeScale.Level.WATCH ? warnColor : borderColor);
        canvas.drawRoundRect(tileBounds, corner, corner, borderPaint);

        if (scale != null) {
            drawDial(canvas, s);
        } else {
            drawFlat(canvas, s);
        }

        nameText.setColor(unavailable ? warnColor : nameColor);
        fit(nameText, name, s * 0.105f, s * 0.9f);
        canvas.drawText(name, s / 2f, s * 0.955f, nameText);
    }

    private void drawDial(Canvas canvas, float s) {
        float c = s / 2f;
        double[] stops = scale.stops();

        // Minor ticks first, then majors on top; the two end ticks are the longest.
        tickPaint.setColor(tickColor);
        tickPaint.setStrokeWidth(s * 0.009f);
        for (int i = 0; i + 1 < stops.length; i++) {
            for (int k = 1; k <= MINORS_BETWEEN_STOPS; k++) {
                double v = stops[i] + (stops[i + 1] - stops[i]) * k / (MINORS_BETWEEN_STOPS + 1);
                radial(canvas, c, (float) scale.fraction(v), s * 0.435f, s * 0.475f, tickPaint);
            }
        }
        tickPaint.setColor(tickMajorColor);
        for (int i = 0; i < stops.length; i++) {
            boolean end = i == 0 || i == stops.length - 1;
            tickPaint.setStrokeWidth(s * (end ? 0.022f : 0.016f));
            radial(canvas, c, (float) scale.fraction(stops[i]), s * (end ? 0.37f : 0.395f), s * 0.48f, tickPaint);
        }

        // Round-number labels inside the ticks, from one end of the scale to the other.
        numeralText.setTextSize(s * 0.105f);
        for (double v : scale.labelTicks()) {
            float[] p = point(c, (float) scale.fraction(v), s * 0.31f);
            canvas.drawText(format(v), p[0], p[1] + s * 0.035f, numeralText);
        }

        unitText.setColor(valueColor);
        unitText.setTextSize(s * 0.095f);
        canvas.drawText(unit, c, s * 0.355f, unitText);

        if (!Double.isNaN(value)) {
            drawNeedle(canvas, c, (float) scale.fraction(value), s);
        }
        drawHub(canvas, c, s);

        valueText.setColor(unavailable ? mutedColor : valueColor);
        fit(valueText, valueLabel, s * 0.15f, s * 0.62f);
        canvas.drawText(valueLabel, c, s * 0.80f, valueText);
    }

    private void drawFlat(Canvas canvas, float s) {
        float c = s / 2f;
        unitText.setColor(valueColor);
        unitText.setTextSize(s * 0.105f);
        canvas.drawText(unit, c, s * 0.22f, unitText);
        valueText.setColor(unavailable ? mutedColor : valueColor);
        fit(valueText, valueLabel, s * 0.19f, s * 0.8f);
        canvas.drawText(valueLabel, c, s * 0.52f, valueText);
    }

    /** A white wedge: wide at the hub, thin at the tip. */
    private void drawNeedle(Canvas canvas, float c, float fraction, float s) {
        double angle = Math.toRadians(START_ANGLE + SWEEP * fraction);
        float dx = (float) Math.cos(angle), dy = (float) Math.sin(angle);
        float px = -dy, py = dx; // perpendicular
        float tip = s * 0.40f, hubHalf = s * 0.024f, tipHalf = s * 0.006f;
        needle.reset();
        needle.moveTo(c + px * hubHalf, c + py * hubHalf);
        needle.lineTo(c + dx * tip + px * tipHalf, c + dy * tip + py * tipHalf);
        needle.lineTo(c + dx * tip - px * tipHalf, c + dy * tip - py * tipHalf);
        needle.lineTo(c - px * hubHalf, c - py * hubHalf);
        needle.close();
        canvas.drawPath(needle, needlePaint);
    }

    private void drawHub(Canvas canvas, float c, float s) {
        float r = s * 0.055f;
        hubPaint.setShader(new RadialGradient(c - r * 0.3f, c - r * 0.3f, r * 1.4f,
                new int[]{hubColor, hubDarkColor}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(c, c, r, hubPaint);
        hubCorePaint.setColor(hubDarkColor);
        canvas.drawCircle(c, c, r * 0.35f, hubCorePaint);
    }

    private static float[] point(float c, float fraction, float radius) {
        double angle = Math.toRadians(START_ANGLE + SWEEP * fraction);
        return new float[]{c + (float) (radius * Math.cos(angle)), c + (float) (radius * Math.sin(angle))};
    }

    private static void radial(Canvas canvas, float c, float fraction, float from, float to, Paint paint) {
        float[] a = point(c, fraction, from);
        float[] b = point(c, fraction, to);
        canvas.drawLine(a[0], a[1], b[0], b[1], paint);
    }

    /** Sets {@code size}, shrunk so the text fits {@code maxWidth}. */
    private static void fit(Paint paint, String text, float size, float maxWidth) {
        paint.setTextSize(size);
        float width = paint.measureText(text);
        if (width > maxWidth) paint.setTextSize(size * maxWidth / width);
    }

    private static String format(double v) {
        return Math.abs(v - Math.rint(v)) < 1e-9 ? String.valueOf((long) Math.rint(v))
                : String.format(java.util.Locale.US, "%.1f", v);
    }

    private static int color(Context context, int res) {
        return ContextCompat.getColor(context, res);
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
