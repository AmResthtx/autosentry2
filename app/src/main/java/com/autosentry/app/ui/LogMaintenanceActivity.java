package com.autosentry.app.ui;

import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.autosentry.app.R;
import com.autosentry.app.data.AppDatabase;
import com.autosentry.app.data.MaintenanceAttachment;
import com.autosentry.app.data.MaintenanceEvent;
import com.autosentry.app.data.VehicleProfile;
import com.autosentry.app.engine.OilLifeEngine;
import com.autosentry.app.maintenance.MaintenanceScheduleEngine;
import com.autosentry.app.maintenance.PhotoStorage;
import com.autosentry.app.maintenance.ServiceInterval;
import com.autosentry.app.maintenance.ServiceItemType;
import com.autosentry.app.maintenance.ServiceStatus;
import com.autosentry.app.settings.AppSettings;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Records one service, opened by tapping its line on the Service tab.
 *
 * For a scheduled item it first explains the job — status, why it matters,
 * parts to buy, one-time-use parts that must be new, and the "while you're in
 * there" jobs that share the same teardown (tick them to log them together).
 * Each of those lists is where a parts offer can later attach.
 *
 * Opened with "OTHER" it logs upgrades and off-schedule work instead.
 *
 * Every record can carry photo evidence — receipts, parts, install shots —
 * tied to the odometer. That's the history a buyer can trust, and the gap
 * CarFax leaves for anyone who does their own work: CarFax only sees what a
 * dealer or shop reported.
 */
public class LogMaintenanceActivity extends AppCompatActivity {
    public static final String EXTRA_SERVICE_TYPE = "service_type";

    private static final double WORTH_DOING_NOW_PERCENT = 50.0;
    private static final long MS_PER_MONTH = (long) (30.4375 * 24 * 60 * 60 * 1000);

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final List<File> pendingPhotos = new ArrayList<>();
    private final Map<ServiceItemType, CheckBox> relatedChecks = new LinkedHashMap<>();

    private AppDatabase db;
    private ServiceItemType serviceType; // null = upgrade / other work
    private ServiceInterval interval;
    private Spinner spinnerType;
    private EditText editTitle, editNotes, editMilesAgo, editMonthsAgo;
    private TextView textOdometer;
    private RadioGroup radioResult;
    private LinearLayout layoutPhotoThumbnails;
    private double currentOdometer = 0;

    private File pendingCaptureFile;
    private ActivityResultLauncher<Uri> cameraLauncher;
    private ActivityResultLauncher<String> galleryLauncher;
    private ActivityResultLauncher<String> cameraPermissionLauncher;

    private static final String[] OTHER_KEYS = {"UPGRADE", "OTHER"};
    private static final String[] OTHER_LABELS = {"Upgrade / Modification", "Other work / repair"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_maintenance);
        db = AppDatabase.getInstance(this);

        String typeExtra = getIntent().getStringExtra(EXTRA_SERVICE_TYPE);
        try {
            serviceType = typeExtra != null ? ServiceItemType.valueOf(typeExtra) : null;
        } catch (IllegalArgumentException e) {
            serviceType = null;
        }
        interval = serviceType != null ? ServiceInterval.of(serviceType) : null;

        spinnerType = findViewById(R.id.spinnerType);
        editTitle = findViewById(R.id.editTitle);
        editNotes = findViewById(R.id.editNotes);
        editMilesAgo = findViewById(R.id.editMilesAgo);
        editMonthsAgo = findViewById(R.id.editMonthsAgo);
        textOdometer = findViewById(R.id.textOdometer);
        radioResult = findViewById(R.id.radioResult);
        layoutPhotoThumbnails = findViewById(R.id.layoutPhotoThumbnails);
        Button buttonTakePhoto = findViewById(R.id.buttonTakePhoto);
        Button buttonPickPhoto = findViewById(R.id.buttonPickPhoto);
        Button buttonSaveEvent = findViewById(R.id.buttonSaveEvent);

        if (interval != null) {
            setTitle(interval.displayName);
            findViewById(R.id.layoutServiceInfo).setVisibility(View.VISIBLE);
            findViewById(R.id.layoutOtherWork).setVisibility(View.GONE);
            bindServiceInfo();
        } else {
            setTitle("Upgrade or other work");
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, OTHER_LABELS);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerType.setAdapter(adapter);
        }

        registerLaunchers();

        buttonTakePhoto.setOnClickListener(v -> launchCamera());
        buttonPickPhoto.setOnClickListener(v -> galleryLauncher.launch("image/*"));
        buttonSaveEvent.setOnClickListener(v -> saveEvent());

        loadStatus();
    }

    /** Static parts of the service card: why, source, parts lists. */
    private void bindServiceInfo() {
        ((TextView) findViewById(R.id.textServiceTitle)).setText(interval.displayName);
        ((TextView) findViewById(R.id.textServiceWhy)).setText(interval.why);
        ((TextView) findViewById(R.id.textServiceSource)).setText("Interval source: " + interval.source);

        bindBullets(R.id.labelParts, R.id.textParts, interval.parts);
        bindBullets(R.id.labelAlwaysReplace, R.id.textAlwaysReplace, interval.alwaysReplace);
        bindBullets(0, R.id.textAddOns, interval.addOnParts);

        boolean inspect = interval.action == ServiceInterval.Action.INSPECT;
        findViewById(R.id.labelResult).setVisibility(inspect ? View.VISIBLE : View.GONE);
        radioResult.setVisibility(inspect ? View.VISIBLE : View.GONE);

        boolean anyRelated = interval.whileInThere.length > 0 || interval.addOnParts.length > 0;
        findViewById(R.id.labelWhileInThere).setVisibility(anyRelated ? View.VISIBLE : View.GONE);
        findViewById(R.id.textWhileInThereHint).setVisibility(anyRelated ? View.VISIBLE : View.GONE);
    }

    private void bindBullets(int labelId, int textId, String[] items) {
        TextView text = findViewById(textId);
        if (items.length == 0) {
            text.setVisibility(View.GONE);
            if (labelId != 0) findViewById(labelId).setVisibility(View.GONE);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("•  ").append(item);
        }
        text.setText(sb.toString());
    }

    /** Odometer, this item's status, and the live status of each "while you're in there" job. */
    private void loadStatus() {
        final long trackingStart = AppSettings.getMaintenanceTrackingStart(this);
        final Set<String> hidden = AppSettings.getHiddenServiceItems(this);
        ioExecutor.execute(() -> {
            VehicleProfile profile = db.vehicleProfileDao().getSync();
            currentOdometer = profile != null ? profile.odometerMiles : 0;

            ServiceStatus status = null;
            MaintenanceEvent last = null;
            List<ServiceStatus> related = new ArrayList<>();
            if (interval != null && profile != null) {
                status = MaintenanceScheduleEngine.computeStatus(serviceType, profile, db.maintenanceDao(), trackingStart);
                last = db.maintenanceDao().getMostRecentOfType(serviceType.name());
                for (ServiceItemType type : interval.whileInThere) {
                    if (hidden.contains(type.name())) continue;
                    related.add(MaintenanceScheduleEngine.computeStatus(type, profile, db.maintenanceDao(), trackingStart));
                }
            }

            final ServiceStatus s = status;
            final MaintenanceEvent lastEvent = last;
            runOnUiThread(() -> {
                textOdometer.setText(String.format(Locale.US, "Odometer: %,.1f mi", currentOdometer));
                if (s != null) bindStatus(s, lastEvent);
                for (ServiceStatus rs : related) addRelatedCheck(rs);
            });
        });
    }

    private void bindStatus(ServiceStatus s, MaintenanceEvent lastEvent) {
        TextView statusView = findViewById(R.id.textServiceStatus);
        String state = s.overdue ? "Overdue" : (s.dueSoon ? "Due soon" : String.format(Locale.US, "%.0f%% used", s.percentOfLifeUsed));
        String remaining = s.remainingText();
        statusView.setText(state + (remaining.isEmpty() ? "" : " · " + remaining) + " · " + s.intervalText());
        statusView.setTextColor(s.overdue ? Color.parseColor("#D32F2F")
                : s.dueSoon ? Color.parseColor("#F57C00") : Color.parseColor("#43A047"));

        TextView lastView = findViewById(R.id.textServiceLast);
        if (lastEvent != null) {
            lastView.setText(String.format(Locale.US, "Last logged %s at %,.0f mi",
                    DateFormat.format("MMM d, yyyy", lastEvent.timestamp), lastEvent.odometerAtEvent));
        } else {
            lastView.setText("Not logged yet. Counting from when AutoSentry started tracking. "
                    + "If you did this before, fill in \"Done before today\" below.");
        }
    }

    private void addRelatedCheck(ServiceStatus rs) {
        CheckBox box = new CheckBox(this);
        String line = String.format(Locale.US, "%s — %.0f%% used", rs.displayName, rs.percentOfLifeUsed);
        if (rs.percentOfLifeUsed >= WORTH_DOING_NOW_PERCENT) line += " · worth doing now";
        box.setText(line);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        ((LinearLayout) findViewById(R.id.layoutWhileInThere)).addView(box);
        relatedChecks.put(rs.type, box);
    }

    private void registerLaunchers() {
        cameraLauncher = registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
            if (success && pendingCaptureFile != null) {
                addPhoto(pendingCaptureFile);
            }
        });

        galleryLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null) return;
            ioExecutor.execute(() -> {
                try {
                    File local = PhotoStorage.importFrom(this, uri);
                    runOnUiThread(() -> addPhoto(local));
                } catch (IOException e) {
                    runOnUiThread(() -> Toast.makeText(this, "Failed to import photo: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
        });

        cameraPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) launchCamera();
            else Toast.makeText(this, "Camera permission needed to take photos", Toast.LENGTH_SHORT).show();
        });
    }

    private void launchCamera() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA);
            return;
        }
        pendingCaptureFile = PhotoStorage.newCaptureFile(this);
        Uri uri = PhotoStorage.toContentUri(this, pendingCaptureFile);
        cameraLauncher.launch(uri);
    }

    private void addPhoto(File file) {
        pendingPhotos.add(file);

        int sizePx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 72, getResources().getDisplayMetrics());
        ImageView imageView = new ImageView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(sizePx, sizePx);
        params.setMarginEnd((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, getResources().getDisplayMetrics()));
        imageView.setLayoutParams(params);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageView.setImageBitmap(decodeSampledThumbnail(file, sizePx, sizePx));
        layoutPhotoThumbnails.addView(imageView);
    }

    private Bitmap decodeSampledThumbnail(File file, int reqWidth, int reqHeight) {
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

    private static int parseCount(EditText field) {
        try {
            return Math.max(0, Integer.parseInt(field.getText().toString().trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String resultLabel() {
        if (interval == null || interval.action != ServiceInterval.Action.INSPECT) return null;
        int checked = radioResult.getCheckedRadioButtonId();
        if (checked == R.id.radioResultReplaced) return "Replaced";
        if (checked == R.id.radioResultRepaired) return "Repaired / adjusted";
        return "Checked — looks good";
    }

    private void saveEvent() {
        final String type;
        final String title;
        if (serviceType != null) {
            type = serviceType.name();
            title = resultLabel(); // shown after the service name in history
        } else {
            type = OTHER_KEYS[spinnerType.getSelectedItemPosition()];
            title = editTitle.getText().toString().trim();
            if (title.isEmpty()) {
                editTitle.setError("Give it a short title");
                return;
            }
        }
        String notes = editNotes.getText().toString().trim();

        // Back-filling a job done before the app was tracking: shift its odometer/date back.
        int milesAgo = serviceType != null ? parseCount(editMilesAgo) : 0;
        int monthsAgo = serviceType != null ? parseCount(editMonthsAgo) : 0;
        long when = System.currentTimeMillis() - monthsAgo * MS_PER_MONTH;
        double odometerAt = currentOdometer - milesAgo;

        List<ServiceItemType> alsoDone = new ArrayList<>();
        for (Map.Entry<ServiceItemType, CheckBox> entry : relatedChecks.entrySet()) {
            if (entry.getValue().isChecked()) alsoDone.add(entry.getKey());
        }

        List<File> photosToSave = new ArrayList<>(pendingPhotos);
        ioExecutor.execute(() -> {
            MaintenanceEvent event = new MaintenanceEvent();
            event.type = type;
            event.title = title == null || title.isEmpty() ? null : title;
            event.notes = notes.isEmpty() ? null : notes;
            event.timestamp = when;
            event.odometerAtEvent = odometerAt;
            long eventId = db.maintenanceDao().insert(event);

            for (File photo : photosToSave) {
                MaintenanceAttachment attachment = new MaintenanceAttachment();
                attachment.maintenanceEventId = eventId;
                attachment.filePath = photo.getAbsolutePath();
                attachment.timestamp = System.currentTimeMillis();
                attachment.caption = null;
                db.maintenanceAttachmentDao().insert(attachment);
            }

            for (ServiceItemType related : alsoDone) {
                MaintenanceEvent together = new MaintenanceEvent();
                together.type = related.name();
                together.notes = "Done together with " + interval.displayName;
                together.timestamp = when;
                together.odometerAtEvent = odometerAt;
                db.maintenanceDao().insert(together);
            }

            boolean oilChanged = serviceType == ServiceItemType.OIL_FILTER || alsoDone.contains(ServiceItemType.OIL_FILTER);
            if (oilChanged) resetOilLife(milesAgo);

            int count = 1 + alsoDone.size();
            runOnUiThread(() -> {
                String msg = count == 1 ? "Saved" : "Saved " + count + " services";
                if (!photosToSave.isEmpty()) msg += " with " + photosToSave.size() + " photo(s)";
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                finish();
            });
        });
    }

    /** Oil life lives on the vehicle profile; a logged oil change restarts it. */
    private void resetOilLife(int milesAgo) {
        VehicleProfile profile = db.vehicleProfileDao().getSync();
        if (profile == null) return;
        // A back-filled change already has miles on it; charge them at the baseline rate.
        profile.oilLifePercent = OilLifeEngine.degrade(100.0, milesAgo, profile.severeDuty, 0, 0);
        profile.milesSinceOilChange = milesAgo;
        profile.engineHoursSinceOilChange = 0;
        profile.lastOilResetTimestamp = System.currentTimeMillis();
        db.vehicleProfileDao().update(profile);
    }
}
