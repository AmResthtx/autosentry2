package com.autosentry.app.ui;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.autosentry.app.data.AppDatabase;
import com.autosentry.app.data.FuelFillUp;
import com.autosentry.app.data.Session;
import com.autosentry.app.data.TripPoint;
import com.autosentry.app.data.VehicleProfile;
import com.autosentry.app.fuel.FuelEconomy;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Logs pump fill-ups and shows MPG per tank. */
public class FuelLogActivity extends AppCompatActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private AppDatabase db;
    private EditText editOdometer, editGallons, editPrice;
    private CheckBox checkFull;
    private TextView textSummary;
    private LinearLayout listContainer;
    private Button dateButton;
    private Button saveButton;
    private long fillTimestamp;

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
        NavHelper.enableBack(this);
        db = AppDatabase.getInstance(this);
        setTitle("Fuel Log");
        fillTimestamp = savedInstanceState != null
                ? savedInstanceState.getLong("fillTimestamp") : System.currentTimeMillis();

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView hint = new TextView(this);
        hint.setText("Log each fill-up. Check \"Full tank\" when you filled to the pump shutoff; "
                + "MPG is calculated between full fill-ups, including any partial fills. "
                + "Check the odometer against your truck.");
        root.addView(hint);

        editOdometer = field(root, "Odometer (miles)", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        editGallons = field(root, "Gallons added", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        editPrice = field(root, "Price per gallon (optional)", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        checkFull = new CheckBox(this);
        checkFull.setText("Full tank (filled to shutoff)");
        checkFull.setChecked(true);
        root.addView(checkFull);

        dateButton = new Button(this);
        updateDateButton();
        dateButton.setOnClickListener(v -> chooseDate());
        root.addView(dateButton);

        Button tripButton = new Button(this);
        tripButton.setText("Use start of recorded trip");
        tripButton.setOnClickListener(v -> chooseTrip());
        root.addView(tripButton);

        saveButton = new Button(this);
        saveButton.setText("Save fill-up");
        saveButton.setOnClickListener(v -> save());
        root.addView(saveButton);

        textSummary = new TextView(this);
        textSummary.setPadding(0, pad, 0, pad / 2);
        textSummary.setTextSize(16);
        root.addView(textSummary);

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(listContainer);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        io.execute(() -> {
            VehicleProfile p = db.vehicleProfileDao().getSync();
            if (p != null && p.odometerMiles > 0) {
                runOnUiThread(() -> {
                    if (editOdometer.length() == 0) {
                        editOdometer.setText(String.format(Locale.US, "%.1f", p.odometerMiles));
                    }
                });
            }
        });
        refresh();
    }

    private EditText field(LinearLayout parent, String hint, int inputType) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(inputType);
        parent.addView(e);
        return e;
    }

    private static double parse(EditText e) {
        try {
            return Double.parseDouble(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }

    private void save() {
        double odo = parse(editOdometer);
        double gal = parse(editGallons);
        String priceText = editPrice.getText().toString().trim();
        double price = priceText.isEmpty() ? 0 : parse(editPrice);
        if (!Double.isFinite(odo) || !Double.isFinite(gal) || !Double.isFinite(price)
                || odo < 0 || gal <= 0 || price < 0 || editOdometer.length() == 0
                || (!priceText.isEmpty() && price <= 0)) {
            Toast.makeText(this, "Enter a valid odometer, gallons and optional price", Toast.LENGTH_SHORT).show();
            return;
        }
        FuelFillUp f = new FuelFillUp();
        f.timestamp = fillTimestamp;
        f.odometerMiles = odo;
        f.gallons = gal;
        f.fullTank = checkFull.isChecked();
        f.pricePerGallon = price;
        saveButton.setEnabled(false);
        io.execute(() -> {
            try {
                List<FuelFillUp> existing = db.fuelFillUpDao().getAllSync();
                for (FuelFillUp other : existing) {
                    if ((other.timestamp <= f.timestamp && other.odometerMiles > f.odometerMiles)
                            || (other.timestamp > f.timestamp && other.odometerMiles < f.odometerMiles)) {
                        runOnUiThread(() -> {
                            saveButton.setEnabled(true);
                            Toast.makeText(this, "Odometer conflicts with another fill-up's date",
                                    Toast.LENGTH_LONG).show();
                        });
                        return;
                    }
                }
                db.fuelFillUpDao().insert(f);
                runOnUiThread(() -> {
                    saveButton.setEnabled(true);
                    editGallons.setText("");
                    editPrice.setText("");
                    fillTimestamp = System.currentTimeMillis();
                    updateDateButton();
                    Toast.makeText(this, "Fill-up saved", Toast.LENGTH_SHORT).show();
                    if (!isFinishing() && !isDestroyed()) refresh();
                });
            } catch (RuntimeException e) {
                android.util.Log.e("FuelLog", "Could not save fill-up", e);
                runOnUiThread(() -> {
                    saveButton.setEnabled(true);
                    Toast.makeText(this, "Could not save fill-up", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void updateDateButton() {
        dateButton.setText("Fill-up date: " + DateFormat.format("MMM d, yyyy", fillTimestamp));
    }

    private void chooseDate() {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(fillTimestamp);
        DatePickerDialog dialog = new DatePickerDialog(this, (view, year, month, day) -> {
            Calendar selected = Calendar.getInstance();
            selected.set(year, month, day, 12, 0, 0);
            selected.set(Calendar.MILLISECOND, 0);
            fillTimestamp = selected.getTimeInMillis();
            updateDateButton();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        dialog.getDatePicker().setMaxDate(System.currentTimeMillis());
        dialog.show();
    }

    private void chooseTrip() {
        io.execute(() -> {
            List<Session> sessions = db.sessionDao().getAllSync();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (sessions.isEmpty()) {
                    Toast.makeText(this, "No trips recorded", Toast.LENGTH_SHORT).show();
                    return;
                }
                String[] labels = new String[sessions.size()];
                for (int i = 0; i < labels.length; i++) {
                    Session s = sessions.get(sessions.size() - 1 - i);
                    labels[i] = DateFormat.format("MMM d, yyyy h:mm a", s.startTimestamp)
                            + String.format(Locale.US, "  •  %.1f miles driven", s.distanceMiles);
                }
                new AlertDialog.Builder(this)
                        .setTitle("Trip immediately after fill-up")
                        .setItems(labels, (dialog, index) ->
                                useTrip(sessions.get(sessions.size() - 1 - index)))
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        });
    }

    private void useTrip(Session session) {
        fillTimestamp = session.startTimestamp;
        updateDateButton();
        editOdometer.setText(String.format(Locale.US, "%.1f", session.startOdometerMiles));
        io.execute(() -> {
            List<TripPoint> points = db.tripPointDao().getForSession(session.id);
            TripPoint first = null;
            for (TripPoint point : points) {
                if (point.latitude != 0 || point.longitude != 0) {
                    first = point;
                    break;
                }
            }
            TripPoint location = first;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                        .setTitle("Trip start selected")
                        .setMessage("Date and odometer are estimated from the trip start. "
                                + "Verify the odometer and date. The first GPS point may be away "
                                + "from the station or stale; it does not mark the fill-up.")
                        .setPositiveButton("OK", null);
                if (location != null) {
                    dialog.setNeutralButton("View first GPS point", (d, w) -> showPoint(location));
                }
                dialog.show();
            });
        });
    }

    private void showPoint(TripPoint point) {
        Uri uri = Uri.parse(String.format(Locale.US, "geo:%f,%f?q=%f,%f",
                point.latitude, point.longitude, point.latitude, point.longitude));
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No map app available", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putLong("fillTimestamp", fillTimestamp);
        super.onSaveInstanceState(outState);
    }

    private void refresh() {
        io.execute(() -> {
            List<FuelFillUp> fills = db.fuelFillUpDao().getAllSync();
            List<FuelEconomy.Tank> tanks = FuelEconomy.tanks(fills);
            double avg = FuelEconomy.averageMpg(tanks);

            StringBuilder s = new StringBuilder();
            if (tanks.isEmpty()) {
                s.append("No MPG yet. It needs two full fill-ups with miles driven between them.");
            } else {
                s.append(String.format(Locale.US, "Average: %.1f MPG over %d tank%s",
                        avg, tanks.size(), tanks.size() == 1 ? "" : "s"));
            }
            String summary = s.toString();

            List<String> rows = new ArrayList<>();
            for (int i = fills.size() - 1; i >= 0; i--) {
                FuelFillUp f = fills.get(i);
                StringBuilder r = new StringBuilder();
                r.append(DateFormat.format("MMM d, yyyy", f.timestamp)).append("  ")
                        .append(String.format(Locale.US, "%.0f mi, %.2f gal%s", f.odometerMiles, f.gallons,
                                f.fullTank ? "" : " (partial)"));
                for (FuelEconomy.Tank t : tanks) {
                    if (t.end.id == f.id) {
                        r.append(String.format(Locale.US, "\n  %.1f MPG (%.0f mi / %.2f gal)", t.mpg, t.miles, t.gallons));
                    }
                }
                rows.add(r.toString());
            }

            runOnUiThread(() -> {
                textSummary.setText(summary);
                listContainer.removeAllViews();
                for (int i = 0; i < rows.size(); i++) {
                    FuelFillUp f = fills.get(fills.size() - 1 - i);
                    TextView row = new TextView(this);
                    row.setText(rows.get(i));
                    row.setPadding(0, 12, 0, 12);
                    row.setOnLongClickListener(v -> {
                        confirmDelete(f);
                        return true;
                    });
                    listContainer.addView(row);
                }
                if (!rows.isEmpty()) {
                    TextView tip = new TextView(this);
                    tip.setText("Long-press an entry to delete it.");
                    tip.setVisibility(View.VISIBLE);
                    listContainer.addView(tip);
                }
            });
        });
    }

    private void confirmDelete(FuelFillUp f) {
        new AlertDialog.Builder(this)
                .setMessage("Delete this fill-up? MPG calculations for surrounding fills will change.")
                .setPositiveButton("Delete", (d, w) -> io.execute(() -> {
                    db.fuelFillUpDao().delete(f);
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()) refresh();
                    });
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }
}
