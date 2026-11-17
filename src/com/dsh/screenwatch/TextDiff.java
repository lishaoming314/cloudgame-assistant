package com.dsh.screenwatch;

import java.util.Locale;

/**
 * Compares two OCR results. Pure Java so it can be tested without Android.
 * The ratio is the character-level Levenshtein distance divided by the longer
 * normalized string length (0..100%).
 */
public final class TextDiff {

    private TextDiff() {
    }

    /** Keeps letters, digits and CJK characters; drops spaces and punctuation. */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || (c >= 0x4E00 && c <= 0x9FFF)) {
                b.append(c);
            }
        }
        String out = b.toString().toLowerCase(Locale.ROOT);
        return out.length() > 2000 ? out.substring(0, 2000) : out;
    }

    /** Percentage (0..100) of text that changed between two OCR results. */
    public static float changeRatio(String previous, String current) {
        String a = normalize(previous);
        String b = normalize(current);
        if (a.isEmpty() && b.isEmpty()) {
            return 0f;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return 100f;
        }
        int d = levenshtein(a, b);
        return Math.min(100f, d * 100f / Math.max(a.length(), b.length()));
    }

    static int levenshtein(String a, String b) {
        int n = a.length();
        int m = b.length();
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                int del = prev[j] + 1;
                int ins = cur[j - 1] + 1;
                int sub = prev[j - 1] + cost;
                cur[j] = Math.min(del, Math.min(ins, sub));
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[m];
    }

    /** Self-test: java -cp . com.dsh.screenwatch.TextDiff */
    public static void main(String[] args) {
        check("same", changeRatio("排队中 预计 3 分钟", "排队中 预计 3 分钟"), 0f);
        check("whitespace", changeRatio("排队中 3 分钟", "排队中3分钟"), 0f);
        check("empty->text", changeRatio("", "排队中"), 100f);
        float r = changeRatio("排队中 预计 3 分钟", "排队中 预计 4 分钟");
        System.out.printf("one digit -> %.2f%%%n", r);
        if (r <= 0f || r >= 20f) {
            throw new AssertionError("one digit change should be small but > 0");
        }
        float big = changeRatio("排队中 预计 3 分钟", "进入游戏 开始对战");
        System.out.printf("different text -> %.2f%%%n", big);
        if (big < 50f) {
            throw new AssertionError("different text should change a lot");
        }
        System.out.println("TextDiff self-test passed");
    }

    private static void check(String name, float actual, float expected) {
        if (Math.abs(actual - expected) > 0.001f) {
            throw new AssertionError(name + ": expected " + expected + " got " + actual);
        }
        System.out.printf("ok  %-14s %6.2f%%%n", name, actual);
    }
}
