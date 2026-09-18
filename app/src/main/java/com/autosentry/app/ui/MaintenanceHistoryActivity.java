package com.autosentry.app.ui;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.autosentry.app.R;
import com.autosentry.app.data.AppDatabase;
import com.autosentry.app.data.MaintenanceAttachment;
import com.autosentry.app.data.MaintenanceEvent;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The record a seller shows a buyer: every logged service and upgrade, with
 * whatever photo evidence (receipts, parts, install shots) was attached,
 * timestamped and tied to an odometer reading. Fills the gap CarFax leaves
 * for anyone who maintains or upgrades their own vehicle instead of going
 * through a shop that reports to it.
 */
public class MaintenanceHistoryActivity extends AppCompatActivity {
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private AppDatabase db;
    private ListView listEvents;
    private TextView textEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maintenance_history);
        db = AppDatabase.getInstance(this);
        listEvents = findViewById(R.id.listEvents);
        textEmpty = findViewById(R.id.textEmpty);
        loadEvents();
    }

    private void loadEvents() {
        ioExecutor.execute(() -> {
            List<MaintenanceEvent> events = db.maintenanceDao().getAllSync();
            runOnUiThread(() -> {
                if (events.isEmpty()) {
                    listEvents.setVisibility(View.GONE);
                    textEmpty.setVisibility(View.VISIBLE);
                    return;
                }
                listEvents.setAdapter(new EventAdapter(events));
                listEvents.setOnItemClickListener((parent, view, position, id) -> showEventDetail(events.get(position)));
            });
        });
    }

    private void showEventDetail(MaintenanceEvent event) {
        ioExecutor.execute(() -> {
            List<MaintenanceAttachment> attachments = db.maintenanceAttachmentDao().getForEvent(event.id);
            runOnUiThread(() -> {
                LinearLayout container = new LinearLayout(this);
                container.setOrientation(LinearLayout.VERTICAL);
                int pad = dp(16);
                container.setPadding(pad, pad, pad, pad);

                TextView details = new TextView(this);
                details.setText(String.format("%s\nOdometer: %.1f mi\n\n%s",
                        DateFormat.format("MMM d, yyyy h:mm a", event.timestamp),
                        event.odometerAtEvent,
                        event.notes != null ? event.notes : "(no notes)"));
                container.addView(details);

                for (MaintenanceAttachment attachment : attachments) {
                    ImageView imageView = new ImageView(this);
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, dp(240));
                    params.topMargin = dp(12);
                    imageView.setLayoutParams(params);
                    imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    imageView.setImageBitmap(decodeSampled(new File(attachment.filePath), 800, 800));
                    container.addView(imageView);
                }

                ScrollView scrollView = new ScrollView(this);
                scrollView.addView(container);

                new AlertDialog.Builder(this)
                        .setTitle(displayTitle(event))
                        .setView(scrollView)
                        .setPositiveButton("Close", null)
                        .show();
            });
        });
    }

    private static String displayTitle(MaintenanceEvent event) {
        if (event.title != null && !event.title.isEmpty()) return event.title;
        return niceLabel(event.type);
    }

    private static String niceLabel(String enumName) {
        if (enumName == null) return "Unknown";
        String[] words = enumName.split("_");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (sb.length() > 0) sb.append(' ');
            if (w.isEmpty()) continue;
            sb.append(w.substring(0, 1)).append(w.substring(1).toLowerCase());
        }
        return sb.toString();
    }

    private Bitmap decodeSampled(File file, int reqWidth, int reqHeight) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= reqWidth && bounds.outHeight / (sample * 2) >= reqHeight) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private class EventAdapter extends ArrayAdapter<MaintenanceEvent> {
        EventAdapter(List<MaintenanceEvent> events) {
            super(MaintenanceHistoryActivity.this, 0, events);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(android.R.layout.simple_list_item_2, parent, false);
            }
            MaintenanceEvent event = getItem(position);
            TextView text1 = convertView.findViewById(android.R.id.text1);
            TextView text2 = convertView.findViewById(android.R.id.text2);
            text1.setText(displayTitle(event));
            text2.setText(String.format("%s — %.1f mi",
                    DateFormat.format("MMM d, yyyy", event.timestamp), event.odometerAtEvent));
            return convertView;
        }
    }
}
