package com.autosentry.app.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.autosentry.app.R;
import com.autosentry.app.obd.LiveReadings;
import com.autosentry.app.obd.PidCatalog;
import com.autosentry.app.obd.PollPlan;
import com.autosentry.app.obd.ReadingHistory;
import com.autosentry.app.service.TrackingService;
import com.autosentry.app.settings.AppSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * Graphs of the dashboard's readings over the last 1, 5 or 20 minutes, and the button that
 * records them to a CSV file. The readings shown are the ones picked for the dashboard.
 * Graphs come from what the tracking service has read since it connected; nothing is drawn
 * for a reading the truck didn't answer.
 */
public class LiveGraphActivity extends AppCompatActivity {
    private static final long REFRESH_MS = 500L;
    private static final long[] WINDOWS_MS = {60_000L, 5 * 60_000L, 20 * 60_000L};
    private static final String[] WINDOW_LABELS = {"1 min", "5 min", "20 min"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Integer> pidIds = new ArrayList<>();
    private final List<LineGraphView> graphs = new ArrayList<>();
    private final Button[] windowButtons = new Button[WINDOWS_MS.length];
    private int window = 1;
    private Button recordButton;
    private TextView statusText;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            update();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        NavHelper.addMainMenuItem(this, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        return NavHelper.handle(this, item) || super.onOptionsItemSelected(item);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Graphs and recording");
        NavHelper.enableBack(this);

        int text = ContextCompat.getColor(this, R.color.fs_text);
        int muted = ContextCompat.getColor(this, R.color.fs_muted);
        int gridColor = ContextCompat.getColor(this, R.color.fs_divider);
        int[] lineColors = {
                ContextCompat.getColor(this, R.color.fs_val_teal), ContextCompat.getColor(this, R.color.fs_val_orange),
                ContextCompat.getColor(this, R.color.fs_val_green), ContextCompat.getColor(this, R.color.fs_val_blue),
                ContextCompat.getColor(this, R.color.fs_val_yellow), ContextCompat.getColor(this, R.color.fs_val_pink)};
        int pad = dp(12);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setPadding(pad, pad, pad, 0);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < WINDOWS_MS.length; i++) {
            final int choice = i;
            Button b = new Button(this);
            b.setText(WINDOW_LABELS[i]);
            b.setOnClickListener(v -> {
                window = choice;
                update();
            });
            windowButtons[i] = b;
            controls.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        recordButton = new Button(this);
        recordButton.setOnClickListener(v -> toggleRecording());
        controls.addView(recordButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f));
        root.addView(controls);

        statusText = new TextView(this);
        statusText.setTextColor(muted);
        statusText.setTextSize(13);
        statusText.setPadding(0, dp(6), 0, dp(6));
        root.addView(statusText);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int n = 0;
        for (int id : AppSettings.getDashboardPids(this)) {
            PidCatalog.Pid def = PidCatalog.get(id);
            if (def == null) continue;
            LineGraphView graph = new LineGraphView(this, lineColors[n++ % lineColors.length], text, muted, gridColor);
            graph.setTitle(def.label());
            pidIds.add(id);
            graphs.add(graph);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(10);
            list.addView(graph, lp);
        }
        if (graphs.isEmpty()) {
            TextView none = new TextView(this);
            none.setTextColor(muted);
            none.setText("No readings are on the dashboard. Go back and pick some with the list button.");
            list.addView(none);
        }
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresh);
    }

    private void toggleRecording() {
        if (LiveReadings.recordRequested || LiveReadings.recording) {
            LiveReadings.recordRequested = false; // the service closes and saves the file on its next pass
        } else if (!TrackingService.isRunning) {
            LiveReadings.recordStatus = "Tracking is off. Start tracking on the dashboard first.";
        } else {
            LiveReadings.recordStatus = "Recording starts with the truck's next answer.";
            LiveReadings.recordRequested = true;
        }
        update();
    }

    private void update() {
        long now = System.currentTimeMillis();
        long start = now - WINDOWS_MS[window];
        for (int i = 0; i < windowButtons.length; i++) windowButtons[i].setEnabled(i != window);
        boolean recording = LiveReadings.recordRequested || LiveReadings.recording;
        recordButton.setText(recording ? "Stop and save" : "Record to file");
        String status = LiveReadings.recordStatus;
        statusText.setText(status != null ? status
                : "Record saves the dashboard's readings once a second to a CSV file in Downloads/AutoSentry. "
                + LiveReadings.status);

        for (int i = 0; i < graphs.size(); i++) {
            int id = pidIds.get(i);
            PidCatalog.Pid def = PidCatalog.get(id);
            ReadingHistory.Series series = LiveReadings.history.since(id, start);
            Double value = LiveReadings.values.get(id);
            PollPlan.State state = LiveReadings.states.get(id);
            String note;
            if (state != null && state.unavailable()) {
                note = capitalize(state.label);
            } else if (def.trust == PidCatalog.Trust.STANDARD) {
                note = "";
            } else {
                note = def.trust.explanation;
            }
            graphs.get(i).setData(series, start, now,
                    value == null ? "--" : def.formatValue(value) + (def.unit.isEmpty() ? "" : " " + def.unit),
                    series.size() == 0 ? "" : "low " + def.formatValue(series.min()),
                    series.size() == 0 ? "" : "high " + def.formatValue(series.max()), note);
        }
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
