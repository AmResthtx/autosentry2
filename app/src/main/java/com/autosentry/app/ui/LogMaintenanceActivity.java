package com.autosentry.app.ui;

import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
import com.autosentry.app.maintenance.PhotoStorage;
import com.autosentry.app.maintenance.ServiceItemType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Logs a maintenance/upgrade event with photo evidence attached — receipts,
 * part photos, install shots, timestamped alongside the odometer reading.
 * This is the record a seller points to at resale and a buyer can actually
 * trust, and it's the gap CarFax leaves wide open for anyone who does their
 * own work: CarFax only sees what a dealer or shop reported, so a driveway
 * oil change, a DIY suspension upgrade, or a swapped bumper never shows up
 * there no matter how well-documented it was in reality.
 */
public class LogMaintenanceActivity extends AppCompatActivity {
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final List<File> pendingPhotos = new ArrayList<>();

    private AppDatabase db;
    private Spinner spinnerType;
    private EditText editTitle, editNotes;
    private TextView textOdometer;
    private LinearLayout layoutPhotoThumbnails;
    private double currentOdometer = 0;

    private File pendingCaptureFile;
    private ActivityResultLauncher<Uri> cameraLauncher;
    private ActivityResultLauncher<String> galleryLauncher;
    private ActivityResultLauncher<String> cameraPermissionLauncher;

    private static final String[] TYPE_LABELS;
    private static final String[] TYPE_KEYS;
    static {
        ServiceItemType[] scheduled = ServiceItemType.values();
        TYPE_LABELS = new String[scheduled.length + 2];
        TYPE_KEYS = new String[scheduled.length + 2];
        for (int i = 0; i < scheduled.length; i++) {
            TYPE_KEYS[i] = scheduled[i].name();
            TYPE_LABELS[i] = niceLabel(scheduled[i].name());
        }
        TYPE_KEYS[scheduled.length] = "UPGRADE";
        TYPE_LABELS[scheduled.length] = "Upgrade / Modification";
        TYPE_KEYS[scheduled.length + 1] = "OTHER";
        TYPE_LABELS[scheduled.length + 1] = "Other";
    }

    private static String niceLabel(String enumName) {
        String[] words = enumName.split("_");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(w.substring(0, 1)).append(w.substring(1).toLowerCase());
        }
        return sb.toString();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_maintenance);
        db = AppDatabase.getInstance(this);

        spinnerType = findViewById(R.id.spinnerType);
        editTitle = findViewById(R.id.editTitle);
        editNotes = findViewById(R.id.editNotes);
        textOdometer = findViewById(R.id.textOdometer);
        layoutPhotoThumbnails = findViewById(R.id.layoutPhotoThumbnails);
        Button buttonTakePhoto = findViewById(R.id.buttonTakePhoto);
        Button buttonPickPhoto = findViewById(R.id.buttonPickPhoto);
        Button buttonSaveEvent = findViewById(R.id.buttonSaveEvent);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, TYPE_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerType.setAdapter(adapter);

        registerLaunchers();

        buttonTakePhoto.setOnClickListener(v -> launchCamera());
        buttonPickPhoto.setOnClickListener(v -> galleryLauncher.launch("image/*"));
        buttonSaveEvent.setOnClickListener(v -> saveEvent());

        ioExecutor.execute(() -> {
            VehicleProfile profile = db.vehicleProfileDao().getSync();
            currentOdometer = profile != null ? profile.odometerMiles : 0;
            runOnUiThread(() -> textOdometer.setText(String.format("Odometer: %.1f mi", currentOdometer)));
        });
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

    private void saveEvent() {
        int typeIndex = spinnerType.getSelectedItemPosition();
        String type = TYPE_KEYS[typeIndex];
        String title = editTitle.getText().toString().trim();
        String notes = editNotes.getText().toString().trim();

        if (type.equals("UPGRADE") || type.equals("OTHER")) {
            if (title.isEmpty()) {
                editTitle.setError("Give it a short title");
                return;
            }
        }

        List<File> photosToSave = new ArrayList<>(pendingPhotos);
        ioExecutor.execute(() -> {
            MaintenanceEvent event = new MaintenanceEvent();
            event.type = type;
            event.title = title.isEmpty() ? null : title;
            event.notes = notes.isEmpty() ? null : notes;
            event.timestamp = System.currentTimeMillis();
            event.odometerAtEvent = currentOdometer;
            long eventId = db.maintenanceDao().insert(event);

            for (File photo : photosToSave) {
                MaintenanceAttachment attachment = new MaintenanceAttachment();
                attachment.maintenanceEventId = eventId;
                attachment.filePath = photo.getAbsolutePath();
                attachment.timestamp = System.currentTimeMillis();
                attachment.caption = null;
                db.maintenanceAttachmentDao().insert(attachment);
            }

            runOnUiThread(() -> {
                Toast.makeText(this, "Logged" + (photosToSave.isEmpty() ? "" : " with " + photosToSave.size() + " photo(s)"), Toast.LENGTH_SHORT).show();
                finish();
            });
        });
    }
}
