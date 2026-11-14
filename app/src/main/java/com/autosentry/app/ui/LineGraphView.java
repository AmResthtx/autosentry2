package com.autosentry.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import com.autosentry.app.obd.ReadingHistory;

/**
 * One reading drawn over time. The line is drawn only between points that were actually
 * read: where the truck gave no answer for a few seconds the line breaks, so a gap in the
 * data looks like a gap and not like a steady value.
 */
public class LineGraphView extends View {
    /** Points further apart than this are not joined. */
    private static final long GAP_MS = 5000L;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint();
    private final Paint titleText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint smallText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valueText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float density;

    private String title = "";
    private String valueLabel = "--";
    private String note = "";
    private String minLabel = "", maxLabel = "";
    private ReadingHistory.Series series;
    private long windowStart, windowEnd;

    public LineGraphView(Context context, int lineColor, int textColor, int mutedColor, int gridColor) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2 * density);
        line.setColor(lineColor);
        grid.setColor(gridColor);
        grid.setStrokeWidth(density);
        titleText.setColor(textColor);
        titleText.setTextSize(14 * density);
        smallText.setColor(mutedColor);
        smallText.setTextSize(11 * density);
        valueText.setColor(lineColor);
        valueText.setTextSize(18 * density);
        valueText.setTextAlign(Paint.Align.RIGHT);
    }

    public void setTitle(String title) {
        this.title = title;
    }

    /**
     * @param note shown under the title: why there is no line ("truck didn't answer"), or how
     *             far to trust the reading
     */
    public void setData(ReadingHistory.Series series, long windowStart, long windowEnd, String valueLabel,
                        String minLabel, String maxLabel, String note) {
        this.series = series;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.valueLabel = valueLabel;
        this.minLabel = minLabel;
        this.maxLabel = maxLabel;
        this.note = note == null ? "" : note;
        // Everything here is drawn, not text views, so a screen reader needs it spelled out.
        StringBuilder spoken = new StringBuilder(title).append(", ").append(valueLabel);
        if (series != null && series.size() > 0) spoken.append(", ").append(minLabel).append(", ").append(maxLabel);
        else spoken.append(", no readings in this window");
        if (!this.note.isEmpty()) spoken.append(". ").append(this.note);
        setContentDescription(spoken);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), Math.round(150 * density));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float pad = 8 * density;
        float left = pad, right = getWidth() - pad;
        float top = 44 * density, bottom = getHeight() - 18 * density;

        canvas.drawText(title, left, 18 * density, titleText);
        canvas.drawText(note, left, 34 * density, smallText);
        canvas.drawText(valueLabel, right, 22 * density, valueText);
        canvas.drawLine(left, top, right, top, grid);
        canvas.drawLine(left, bottom, right, bottom, grid);
        canvas.drawLine(left, (top + bottom) / 2, right, (top + bottom) / 2, grid);

        if (series == null || series.size() == 0 || windowEnd <= windowStart) {
            canvas.drawText("No readings in this window", left, (top + bottom) / 2 - 4 * density, smallText);
            return;
        }
        double min = series.min(), max = series.max();
        if (max - min < 1e-9) { // a flat line sits in the middle rather than on an edge
            min -= 1;
            max += 1;
        }
        canvas.drawText(maxLabel, left, top + 12 * density, smallText);
        canvas.drawText(minLabel, left, bottom - 3 * density, smallText);

        path.reset();
        long previous = Long.MIN_VALUE;
        for (int i = 0; i < series.size(); i++) {
            float x = left + (right - left) * (series.times[i] - windowStart) / (float) (windowEnd - windowStart);
            float y = bottom - (bottom - top) * (float) ((series.values[i] - min) / (max - min));
            if (previous == Long.MIN_VALUE || series.times[i] - previous > GAP_MS) {
                path.moveTo(x, y);
                path.lineTo(x + 1, y); // a lone point still shows
            } else {
                path.lineTo(x, y);
            }
            previous = series.times[i];
        }
        canvas.drawPath(path, line);
    }
}
