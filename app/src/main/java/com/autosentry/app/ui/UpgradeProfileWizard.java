package com.autosentry.app.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import com.autosentry.app.data.VehicleUpgrade;

import java.util.ArrayList;
import java.util.List;

/**
 * Prompts user to enter aftermarket upgrades across multiple sessions.
 * Spaced out over time so it doesn't feel like a questionnaire.
 * Each category is a separate dialog.
 *
 * Example:
 * Session 1 (Day 1): "Do you have any fuel system upgrades?"
 * Session 2 (Day 3): "Do you have any turbo/boost upgrades?"
 * Session 3 (Day 5): "Any engine modifications?"
 * etc.
 *
 * User can skip any dialog; app assumes factory stock.
 * If user enters info, it stores confirmation timestamp and adjusts diagnostic baselines.
 */
public class UpgradeProfileWizard {

    /**
     * Categories to prompt, in suggested order.
     * Each category gets its own dialog session, spaced out over time.
     */
    public enum UpgradeCategory {
        FUEL_SYSTEM("Fuel System & Injectors", 0),           // First (most common mods)
        TURBO_BOOST("Turbocharger & Boost Control", 2),      // Day 2
        ENGINE_TUNE("Engine Tune / ECU Programming", 4),     // Day 4
        TIRES_WHEELS("Tires & Wheels", 6),                   // Day 6
        COOLING_SYSTEM("Cooling System", 8),                 // Day 8
        TRANSMISSION("Transmission & Driveline", 10),        // Day 10
        ELECTRICAL("Electrical & Battery Upgrades", 12),     // Day 12
        ENGINE_INTERNALS("Engine Internals & Cam", 14),      // Day 14
        EXHAUST_EMISSIONS("Exhaust & Emissions", 16);        // Day 16

        final String displayName;
        final int suggestedDayDelay;  // Days after first prompt

        UpgradeCategory(String displayName, int suggestedDayDelay) {
            this.displayName = displayName;
            this.suggestedDayDelay = suggestedDayDelay;
        }
    }

    private final Context context;
    private final VehicleUpgrade upgrade;
    private final OnUpgradeEntered callback;

    public interface OnUpgradeEntered {
        void onUpgradesSaved(VehicleUpgrade upgraded);
    }

    public UpgradeProfileWizard(Context context, VehicleUpgrade currentUpgrade, OnUpgradeEntered callback) {
        this.context = context;
        this.upgrade = currentUpgrade;
        this.callback = callback;
    }

    /**
     * Determines if the user should be prompted for upgrades.
     * Checks if enough time has passed since last prompt, and if the category hasn't been confirmed.
     *
     * Returns the next category to prompt, or null if user is up-to-date.
     */
    public UpgradeCategory getNextCategoryToDemand() {
        long now = System.currentTimeMillis();
        long dayMs = 24 * 60 * 60 * 1000L;

        // If user has confirmed upgrades recently, reduce frequency
        int promptFrequency = upgrade.hasConfirmedAnyUpgrade() ? 14 : 2;  // Every 2 weeks vs 2 days

        if (now - upgrade.lastUpgradeEntryPrompt < promptFrequency * dayMs) {
            return null;  // Too soon; don't bother user
        }

        if (upgrade.upgradePromptCount >= UpgradeCategory.values().length) {
            return null;  // Already prompted for all categories
        }

        // Return next unprompted category
        UpgradeCategory[] categories = UpgradeCategory.values();
        if (upgrade.upgradePromptCount < categories.length) {
            return categories[upgrade.upgradePromptCount];
        }
        return null;
    }

    /**
     * Shows a dialog for a specific upgrade category.
     * Dialog includes relevant fields and explanatory text.
     */
    public void showUpgradeCategoryDialog(UpgradeCategory category) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Help AutoSentry Adapt to Your Truck");
        builder.setCancelable(true);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(20, 20, 20, 20);

        // Explanatory text
        TextView explain = new TextView(context);
        explain.setText(
            "To give you the most accurate diagnostics and maintenance guidance, "
            + "AutoSentry needs to know about any aftermarket upgrades on your truck.\n\n"
            + "If you haven't made upgrades in this category, just tap Skip.\n\n"
            + "Category: " + category.displayName
        );
        explain.setTextSize(14);
        layout.addView(explain);

        // Category-specific fields
        switch (category) {
            case FUEL_SYSTEM:
                addFuelSystemFields(layout);
                break;
            case TURBO_BOOST:
                addTurboFields(layout);
                break;
            case TIRES_WHEELS:
                addTireFields(layout);
                break;
            case ENGINE_TUNE:
                addEngineTuneFields(layout);
                break;
            case COOLING_SYSTEM:
                addCoolingFields(layout);
                break;
            case TRANSMISSION:
                addTransmissionFields(layout);
                break;
            case ELECTRICAL:
                addElectricalFields(layout);
                break;
            case ENGINE_INTERNALS:
                addEngineInternalsFields(layout);
                break;
            case EXHAUST_EMISSIONS:
                addExhaustFields(layout);
                break;
        }

        builder.setView(layout);
        builder.setPositiveButton("Save", (dialog, which) -> {
            // Collect and save user input (implemented per category)
            upgrade.lastUpgradeEntryPrompt = System.currentTimeMillis();
            upgrade.upgradePromptCount++;
            callback.onUpgradesSaved(upgrade);
        });
        builder.setNegativeButton("Skip (Assuming Stock)", (dialog, which) -> {
            upgrade.lastUpgradeEntryPrompt = System.currentTimeMillis();
            upgrade.upgradePromptCount++;
            // Don't modify upgrade fields; assume factory stock
            callback.onUpgradesSaved(upgrade);
        });
        builder.show();
    }

    private void addFuelSystemFields(LinearLayout layout) {
        addSectionLabel(layout, "Fuel System & Injectors");
        addSpinnerField(layout, "Injector Size",
            new String[]{"Stock 140cc", "160cc", "175cc", "Custom"},
            (value) -> upgrade.injectorSize = value);
        addSpinnerField(layout, "Fuel Pump Type",
            new String[]{"Stock Mechanical", "Airdog 150", "FASS", "Other"},
            (value) -> upgrade.fuelPumpType = value);
        addTextField(layout, "Fuel System Notes (optional)",
            (value) -> upgrade.fuelSystemMods = value);
    }

    private void addTurboFields(LinearLayout layout) {
        addSectionLabel(layout, "Turbocharger & Boost");
        addSpinnerField(layout, "Turbo Type",
            new String[]{"Stock GT3782VA", "GT4088", "Twin Turbos", "Custom"},
            (value) -> upgrade.turboType = value);
        addSpinnerField(layout, "Target Boost",
            new String[]{"Stock ~18 psi", "20 psi", "25 psi", "Custom"},
            (value) -> upgrade.boostTargetPsi = value);
        addSpinnerField(layout, "Intercooler",
            new String[]{"Stock", "Upgraded Core", "Front-Mount", "Dual-Pass"},
            (value) -> upgrade.intercoolerType = value);
    }

    private void addTireFields(LinearLayout layout) {
        addSectionLabel(layout, "Tires & Wheels");
        addTextField(layout, "Tire Size (e.g., 285/75R16)",
            (value) -> {
                upgrade.tireSize = value;
                // Parse tire size to calculate diameter
                parseTireSize(value);
            });
        addTextField(layout, "Wheel Diameter (inches, e.g., 16)",
            (value) -> {
                try {
                    upgrade.tireWheelDiameterInches = Integer.parseInt(value);
                } catch (NumberFormatException ignored) {}
            });
    }

    private void addEngineTuneFields(LinearLayout layout) {
        addSectionLabel(layout, "Engine Tune & ECU");
        addSpinnerField(layout, "Tune Level",
            new String[]{"Stock", "Mild (120hp+)", "Moderate (180hp+)", "Aggressive"},
            (value) -> upgrade.engineTuneType = value);
        addSpinnerField(layout, "Tune Strategy",
            new String[]{"Stock Ford", "Economy", "Power", "Towing", "Custom"},
            (value) -> upgrade.tuneStrategy = value);
    }

    private void addCoolingFields(LinearLayout layout) {
        addSectionLabel(layout, "Cooling System");
        addSpinnerField(layout, "Cooling System Type",
            new String[]{"Stock", "Upgraded Radiator", "Electric Fan", "Dual Setup"},
            (value) -> upgrade.coolingSystemType = value);
        addSpinnerField(layout, "Thermostat",
            new String[]{"160F (Stock)", "180F", "Custom"},
            (value) -> upgrade.thermostatTemp = value);
    }

    private void addTransmissionFields(LinearLayout layout) {
        addSectionLabel(layout, "Transmission & Driveline");
        addSpinnerField(layout, "Transmission Type",
            new String[]{"Stock 4R100", "Upgraded Converter", "Custom Tune"},
            (value) -> upgrade.transmissionType = value);
        addSpinnerField(layout, "Differential Gearing",
            new String[]{"3.55 (Stock)", "4.10", "4.56", "Custom"},
            (value) -> upgrade.differentialGearing = value);
    }

    private void addElectricalFields(LinearLayout layout) {
        addSectionLabel(layout, "Electrical & Battery");
        addSpinnerField(layout, "Battery Type",
            new String[]{"Stock 1000 CCA", "Dual Battery", "Lithium", "Custom"},
            (value) -> upgrade.batterySize = value);
        addSpinnerField(layout, "Alternator Type",
            new String[]{"Stock 130A", "Upgraded 160A+", "Dual"},
            (value) -> upgrade.alternatorType = value);
    }

    private void addEngineInternalsFields(LinearLayout layout) {
        addSectionLabel(layout, "Engine Internals");
        addSpinnerField(layout, "Camshaft Type",
            new String[]{"Stock", "Comp Cams XE262", "Custom Grind"},
            (value) -> upgrade.camshaftType = value);
        addSpinnerField(layout, "Head Work",
            new String[]{"None", "Ported & Polished", "Valve Seat Work", "Custom"},
            (value) -> upgrade.headWork = value);
    }

    private void addExhaustFields(LinearLayout layout) {
        addSectionLabel(layout, "Exhaust & Emissions");
        addSpinnerField(layout, "Exhaust Modifications",
            new String[]{"Stock", "Turbo-Back", "Headers", "Custom"},
            (value) -> upgrade.exhaustModifications = value);
        addSpinnerField(layout, "Emissions Status",
            new String[]{"Stock", "EGR Deleted", "DPF Deleted", "Tuned Delete"},
            (value) -> upgrade.emissionsStatus = value);
    }

    private void addSectionLabel(LinearLayout layout, String label) {
        TextView tv = new TextView(context);
        tv.setText("\n" + label);
        tv.setTextSize(16);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        layout.addView(tv);
    }

    private void addSpinnerField(LinearLayout layout, String label, String[] options,
                                   java.util.function.Consumer<String> callback) {
        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextSize(14);
        labelView.setPadding(0, 10, 0, 5);
        layout.addView(labelView);

        Spinner spinner = new Spinner(context);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
            android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                callback.accept(options[position]);
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        layout.addView(spinner);
    }

    private void addTextField(LinearLayout layout, String label,
                               java.util.function.Consumer<String> callback) {
        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextSize(14);
        labelView.setPadding(0, 10, 0, 5);
        layout.addView(labelView);

        EditText editText = new EditText(context);
        editText.setHint(label);
        editText.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                callback.accept(editText.getText().toString());
            }
        });
        layout.addView(editText);
    }

    /**
     * Parses tire size string (e.g., "285/75R16") and extracts width, aspect ratio.
     * Calculates approximate tire diameter for speedometer correction.
     */
    private void parseTireSize(String tireSize) {
        // Format: widthMM/aspectRatio R dialInches
        // Example: 285/75R16
        try {
            String[] parts = tireSize.split("/");
            if (parts.length >= 2) {
                upgrade.tireWidthInches = Integer.parseInt(parts[0]) / 25.4;  // mm to inches
                String aspectAndDial = parts[1];  // "75R16"
                String[] aspectParts = aspectAndDial.split("R");
                if (aspectParts.length >= 1) {
                    upgrade.tireAspectRatio = Integer.parseInt(aspectParts[0]);
                }
            }
        } catch (Exception ignored) {
            // If parsing fails, leave as-is
        }
    }
}
