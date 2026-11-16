package com.dsh.screenwatch;

/**
 * Pure-Java frame comparison (no Android dependency), so the math can be tested on a JVM.
 * A frame is a downscaled int[] of ARGB pixels (one sample per grid cell).
 */
public final class PixelDiff {

    private PixelDiff() {
    }

    /**
     * Percentage (0..100) of cells whose largest per-channel difference exceeds the tolerance.
     */
    public static float changeRatio(int[] a, int[] b, int perChannelTolerance) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0f;
        }
        int changed = 0;
        for (int i = 0; i < a.length; i++) {
            int p = a[i];
            int q = b[i];
            int dr = Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF));
            int dg = Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF));
            int db = Math.abs((p & 0xFF) - (q & 0xFF));
            if (dr > perChannelTolerance || dg > perChannelTolerance || db > perChannelTolerance) {
                changed++;
            }
        }
        return changed * 100f / a.length;
    }

    /** Self-test: java -cp . com.dsh.screenwatch.PixelDiff */
    public static void main(String[] args) {
        int n = 48 * 48;
        int[] a = new int[n];
        int[] b = new int[n];
        java.util.Arrays.fill(a, 0xFF3366CC);
        System.arraycopy(a, 0, b, 0, n);

        // identical frames -> 0%
        check("identical", changeRatio(a, b, 24), 0f);

        // 10% of cells changed -> 10%
        int cells = n / 10;
        for (int i = 0; i < cells; i++) b[i] = 0xFFFFFFFF;
        check("10% changed", changeRatio(a, b, 24), cells * 100f / n);

        // tiny jitter (delta 5) must be ignored
        System.arraycopy(a, 0, b, 0, n);
        for (int i = 0; i < cells; i++) b[i] = 0xFF386CCF;
        check("jitter ignored", changeRatio(a, b, 24), 0f);

        // 15% threshold boundary
        System.arraycopy(a, 0, b, 0, n);
        for (int i = 0; i < n * 15 / 100; i++) b[i] = 0xFF000000;
        check("15% boundary", changeRatio(a, b, 24), (n * 15 / 100) * 100f / n);

        System.out.println("PixelDiff self-test passed");
    }

    private static void check(String name, float actual, float expected) {
        if (Math.abs(actual - expected) > 0.001f) {
            throw new AssertionError(name + ": expected " + expected + " got " + actual);
        }
        System.out.printf("ok  %-16s %6.2f%%%n", name, actual);
    }
}
