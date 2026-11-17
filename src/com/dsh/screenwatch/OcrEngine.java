package com.dsh.screenwatch;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Offline OCR wrapper around Tesseract4Android (chi_sim + eng models are
 * shipped in assets/tessdata and copied to filesDir on first use).
 */
final class OcrEngine {

    private static final String TAG = "ScreenWatchOcr";
    private static final String LANG = "chi_sim+eng";

    private TessBaseAPI tess;
    private boolean tried;
    private boolean ready;

    /** Loads the engine once; safe to call on every capture. */
    synchronized boolean ensureReady(Context ctx) {
        if (tried) {
            return ready;
        }
        tried = true;
        try {
            File dataDir = new File(ctx.getFilesDir(), "tessdata");
            if (!dataDir.exists() && !dataDir.mkdirs()) {
                throw new IllegalStateException("cannot create tessdata dir");
            }
            copyAsset(ctx, "tessdata/chi_sim.traineddata", new File(dataDir, "chi_sim.traineddata"));
            copyAsset(ctx, "tessdata/eng.traineddata", new File(dataDir, "eng.traineddata"));

            TessBaseAPI api = new TessBaseAPI();
            boolean ok = api.init(ctx.getFilesDir().getAbsolutePath(), LANG);
            if (!ok) {
                api.recycle();
                throw new IllegalStateException("TessBaseAPI.init returned false");
            }
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT);
            tess = api;
            ready = true;
            Log.i(TAG, "OCR ready (" + LANG + ")");
        } catch (Throwable t) {
            Log.e(TAG, "OCR init failed", t);
            ready = false;
            tess = null;
        }
        return ready;
    }

    /** Returns recognized text, or null when OCR is not available / failed. */
    synchronized String recognize(Bitmap bitmap) {
        if (tess == null) {
            return null;
        }
        try {
            tess.setImage(bitmap);
            String text = tess.getUTF8Text();
            tess.clear();
            return text == null ? "" : text;
        } catch (Throwable t) {
            Log.w(TAG, "recognize failed: " + t);
            return null;
        }
    }

    synchronized void release() {
        if (tess != null) {
            try {
                tess.recycle();
            } catch (Throwable ignored) {
            }
            tess = null;
            ready = false;
        }
    }

    private void copyAsset(Context ctx, String asset, File dst) throws Exception {
        if (dst.exists() && dst.length() > 1024) {
            return;
        }
        File tmp = new File(dst.getAbsolutePath() + ".tmp");
        try (InputStream in = ctx.getAssets().open(asset);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        if (dst.exists() && !dst.delete()) {
            throw new IllegalStateException("cannot replace " + dst);
        }
        if (!tmp.renameTo(dst)) {
            throw new IllegalStateException("cannot move " + tmp);
        }
    }
}
