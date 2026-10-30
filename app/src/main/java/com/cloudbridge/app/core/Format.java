package com.cloudbridge.app.core;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** The way sizes and dates are written in the file lists ("210.43 KB", "16/07", "09/01/2025"). */
public final class Format {
    private Format() {
    }

    public static String size(long bytes) {
        if (bytes < 0) return "";
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB", "PB"};
        double v = bytes;
        int u = -1;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return String.format(Locale.US, "%.2f %s", v, units[u]);
    }

    /** Short size for storage totals: "12.4 GB". */
    public static String storage(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024L * 1024) return size(bytes);
        String[] units = {"MB", "GB", "TB", "PB"};
        double v = bytes / (1024.0 * 1024);
        int u = 0;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return String.format(Locale.US, v >= 100 || u == 0 ? "%.0f %s" : "%.1f %s", v, units[u]);
    }

    /** "16/07" for this year, "09/01/2025" for older ones, "" when unknown. */
    public static String date(long millis, long nowMillis, TimeZone tz) {
        if (millis <= 0) return "";
        Calendar c = Calendar.getInstance(tz, Locale.US);
        c.setTimeInMillis(millis);
        Calendar now = Calendar.getInstance(tz, Locale.US);
        now.setTimeInMillis(nowMillis);
        String dm = String.format(Locale.US, "%02d/%02d", c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1);
        return c.get(Calendar.YEAR) == now.get(Calendar.YEAR) ? dm : dm + "/" + c.get(Calendar.YEAR);
    }

    /** How full, 0..1, or -1 when not known. */
    public static double fraction(CloudQuota q) {
        if (q == null || q.total <= 0 || q.used < 0) return -1;
        return Math.min(1.0, (double) q.used / q.total);
    }
}
