package com.dsh.screenwatch;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

/**
 * Invisible activity used by the idle floating ball / notification.
 * Shows the system screen-capture consent dialog, then starts monitoring.
 */
public class CaptureRequestActivity extends Activity {

    private static final int REQ_CAPTURE = 3001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
        } catch (Throwable t) {
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            try {
                startForegroundService(StartIntent.build(this, resultCode, data));
            } catch (Throwable ignored) {
            }
        }
        finish();
    }
}
