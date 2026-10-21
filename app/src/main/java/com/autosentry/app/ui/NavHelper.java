package com.autosentry.app.ui;

import android.content.Intent;
import android.view.Menu;
import android.view.MenuItem;

import androidx.appcompat.app.AppCompatActivity;

/** Back arrow and "Main menu" action for secondary screens. */
final class NavHelper {
    private static final int MENU_HOME = 1001;

    private NavHelper() {}

    static void enableBack(AppCompatActivity activity) {
        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }

    static void addMainMenuItem(AppCompatActivity activity, Menu menu) {
        menu.add(Menu.NONE, MENU_HOME, Menu.NONE, "Main menu")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
    }

    /** Returns true if the item was handled. */
    static boolean handle(AppCompatActivity activity, MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            activity.getOnBackPressedDispatcher().onBackPressed();
            return true;
        }
        if (item.getItemId() == MENU_HOME) {
            Intent intent = new Intent(activity, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivity(intent);
            activity.finish();
            return true;
        }
        return false;
    }
}
