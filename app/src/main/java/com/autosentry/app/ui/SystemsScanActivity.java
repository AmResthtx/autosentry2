package com.autosentry.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.autosentry.app.R;
import com.autosentry.app.obd.SystemsScan;
import com.autosentry.app.service.TrackingService;
import com.autosentry.app.settings.AppSettings;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Truck Systems: runs TruckProbe through TrackingService and shows the report
 * of everything this truck answers. The last report is kept on the phone so
 * it can be shared later (Share sends the text file anywhere).
 */
public class SystemsScanActivity extends AppCompatActivity {
    private static final long REFRESH_MS = 500L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = this::refresh;

    private TextView textStatus, textReport;
    private Button buttonRun;
    private String shownReport = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_systems_scan);
        setTitle(R.string.truck_systems);

        textStatus = findViewById(R.id.textScanStatus);
        textReport = findViewById(R.id.textReport);
        buttonRun = findViewById(R.id.buttonRunScan);
        Button buttonShare = findViewById(R.id.buttonShareReport);

        buttonRun.setOnClickListener(v -> {
            if (SystemsScan.busy) {
                SystemsScan.cancel = true;
                return;
            }
            if (!AppSettings.hasObdAdapterConfigured(this)) {
                Toast.makeText(this, "Pair your OBD adapter on the Dashboard tab first", Toast.LENGTH_LONG).show();
                return;
            }
            SystemsScan.request();
            if (!TrackingService.isRunning) {
                try {
                    startForegroundService(new Intent(this, TrackingService.class));
                } catch (RuntimeException e) {
                    Toast.makeText(this, "Couldn't start tracking: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });
        buttonShare.setOnClickListener(v -> shareReport());
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refreshTask);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refreshTask);
    }

    private void refresh() {
        boolean busy = SystemsScan.busy;
        buttonRun.setText(busy ? getString(R.string.stop_scan) : getString(R.string.run_systems_scan));
        if (busy) {
            String progress = SystemsScan.progress;
            textStatus.setText(SystemsScan.cancel ? "Stopping…" : (progress != null ? progress : "Scanning…"));
        } else if (SystemsScan.message != null) {
            textStatus.setText(SystemsScan.message);
        } else if (!TrackingService.isRunning) {
            textStatus.setText("Tracking is off. Run Scan turns it on.");
        } else {
            textStatus.setText("Ready. Key on; the engine can be off or idling.");
        }

        String report = SystemsScan.lastReport;
        if (report == null) report = readSavedReport();
        if (report == null) report = "No scan yet.";
        if (!report.equals(shownReport)) {
            shownReport = report;
            textReport.setText(report);
        }
        handler.postDelayed(refreshTask, REFRESH_MS);
    }

    private File reportFile() {
        return new File(getFilesDir(), SystemsScan.REPORT_FILE);
    }

    private String readSavedReport() {
        File file = reportFile();
        if (!file.exists()) return null;
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private void shareReport() {
        File file = reportFile();
        if (!file.exists()) {
            Toast.makeText(this, "Run a scan first", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "Share truck systems report"));
    }
}
