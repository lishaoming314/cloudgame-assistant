package com.dsh.screenwatch;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Transparent launcher used by the home-screen widget:
 * starts the idle floating ball, then opens the cloud-gaming app chosen by the user.
 */
public class CloudLaunchActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences p = getSharedPreferences(ScreenWatchService.PREF, MODE_PRIVATE);
        String pkg = p.getString("cloud_app_package", null);
        Intent launch = pkg == null ? null : getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) {
            startActivity(new Intent(this, AppPickerActivity.class));
            finish();
            return;
        }
        try {
            startForegroundService(new Intent(this, ScreenWatchService.class)
                    .setAction(ScreenWatchService.ACTION_IDLE));
        } catch (Throwable ignored) {
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(launch);
        } catch (Throwable t) {
            Toast.makeText(this, "无法启动该应用，请重新选择", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, AppPickerActivity.class));
        }
        finish();
    }
}
