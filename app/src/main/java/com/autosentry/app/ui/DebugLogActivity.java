package com.autosentry.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.autosentry.app.R;
import com.autosentry.app.util.AppLog;

import java.io.File;

/**
 * On-device view of AppLog — the whole point is being able to see what
 * broke without a PC nearby, since this tablet lives in the truck.
 * "Share" hands the raw log file to whatever app (Messages, email, etc.)
 * so it can be sent off the device for a closer look.
 */
public class DebugLogActivity extends AppCompatActivity {
    private TextView textLogContent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_debug_log);

        textLogContent = findViewById(R.id.textLogContent);
        Button buttonRefresh = findViewById(R.id.buttonRefreshLog);
        Button buttonShare = findViewById(R.id.buttonShareLog);
        Button buttonClear = findViewById(R.id.buttonClearLog);

        buttonRefresh.setOnClickListener(v -> loadLog());
        buttonShare.setOnClickListener(v -> shareLog());
        buttonClear.setOnClickListener(v -> {
            AppLog.clear(this);
            loadLog();
            Toast.makeText(this, "Log cleared", Toast.LENGTH_SHORT).show();
        });

        loadLog();
    }

    private void loadLog() {
        textLogContent.setText(AppLog.readAll(this));
    }

    private void shareLog() {
        File logFile = AppLog.getLogFile(this);
        if (!logFile.exists()) {
            Toast.makeText(this, "Nothing to share yet", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", logFile);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "Share debug log"));
    }
}
