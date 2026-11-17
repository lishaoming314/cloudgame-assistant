package com.dsh.screenwatch;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Builds the ACTION_START intent from the saved settings (used by MainActivity and the overlay flow). */
final class StartIntent {

    private StartIntent() {
    }

    static Intent build(Context c, int resultCode, Intent data) {
        SharedPreferences p = c.getSharedPreferences(ScreenWatchService.PREF, Context.MODE_PRIVATE);
        Intent i = new Intent(c, ScreenWatchService.class)
                .setAction(ScreenWatchService.ACTION_START)
                .putExtra(ScreenWatchService.EXTRA_CODE, resultCode)
                .putExtra(ScreenWatchService.EXTRA_DATA, data)
                .putExtra(ScreenWatchService.EXTRA_THRESHOLD,
                        p.getInt(ScreenWatchService.KEY_THRESHOLD, 15))
                .putExtra(ScreenWatchService.EXTRA_INTERVAL_SEC,
                        p.getInt(ScreenWatchService.KEY_INTERVAL, 60))
                .putExtra(ScreenWatchService.EXTRA_FIRST_DELAY_SEC,
                        p.getInt("first_delay_sec", 2))
                .putExtra(ScreenWatchService.EXTRA_ALERT_MODE, p.getInt("alert_mode", 0))
                .putExtra(ScreenWatchService.EXTRA_AUTO_STOP, p.getBoolean("auto_stop", true))
                .putExtra(ScreenWatchService.EXTRA_USE_PIXEL, p.getBoolean("use_pixel", true))
                .putExtra(ScreenWatchService.EXTRA_USE_OCR, p.getBoolean("use_ocr", false))
                .putExtra(ScreenWatchService.EXTRA_REQUIRE_BOTH, p.getBoolean("require_both", true))
                .putExtra(ScreenWatchService.EXTRA_OCR_THRESHOLD, p.getInt("ocr_threshold", 30))
                .putExtra(ScreenWatchService.EXTRA_RING_SECONDS, 180)
                .putExtra(ScreenWatchService.EXTRA_COLOR,
                        p.getInt("accent_color", 0xFFFF6A00));
        String music = p.getString(ScreenWatchService.KEY_MUSIC, null);
        if (music != null && !music.isEmpty()) {
            i.putExtra(ScreenWatchService.EXTRA_MUSIC, music);
        }
        return i;
    }
}
