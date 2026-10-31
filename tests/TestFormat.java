package com.cloudbridge.app.core;

import java.util.Arrays;
import java.util.Calendar;
import java.util.TimeZone;

import static com.cloudbridge.app.core.TestMain.check;
import static com.cloudbridge.app.core.TestMain.eq;
import static com.cloudbridge.app.core.TestMain.run;

final class TestFormat {
    private TestFormat() {
    }

    static void runAll() {
        run("format and file kinds", TestFormat::test);
    }

    private static void test() {
                eq("bytes", "512 B", Format.size(512));
        eq("KB", "210.43 KB", Format.size(215481));
        eq("MB", "1.50 MB", Format.size(1572864));
        eq("storage GB", "12.4 GB", Format.storage((long) (12.4 * 1024 * 1024 * 1024)));
        eq("storage big", "128 GB", Format.storage(128L * 1024 * 1024 * 1024));
        TimeZone utc = TimeZone.getTimeZone("UTC");
        Calendar now = Calendar.getInstance(utc);
        now.clear();
        now.set(2026, Calendar.OCTOBER, 30, 12, 0);
        Calendar a = Calendar.getInstance(utc);
        a.clear();
        a.set(2026, Calendar.JULY, 16, 8, 0);
        Calendar b = Calendar.getInstance(utc);
        b.clear();
        b.set(2025, Calendar.JANUARY, 9, 8, 0);
        eq("this year", "16/07", Format.date(a.getTimeInMillis(), now.getTimeInMillis(), utc));
        eq("older", "09/01/2025", Format.date(b.getTimeInMillis(), now.getTimeInMillis(), utc));
        eq("unknown", "", Format.date(0, now.getTimeInMillis(), utc));
        eq("pdf", FileKind.PDF, FileKind.of("ishaan.PDF", false));
        eq("xlsx", FileKind.SHEET, FileKind.of("a.xlsx", false));
        eq("html", FileKind.HTML, FileKind.of("a.html", false));
        eq("jpg", FileKind.IMAGE, FileKind.of("IMG_1.JPG", false));
        eq("folder", FileKind.FOLDER, FileKind.of("x.pdf", true));
        eq("no ext", FileKind.OTHER, FileKind.of("README", false));
        check("images preview", FileKind.IMAGE.hasPreview() && FileKind.VIDEO.hasPreview() && !FileKind.PDF.hasPreview());
        eq("fraction", 0.25, Format.fraction(CloudQuota.ofUsedAndTotal(25, 100)));
        eq("fraction unknown", -1.0, Format.fraction(null));
        String answer = "{\"Status\":0,\"Answer\":[{\"name\":\"dm.1024terabox.com\",\"type\":5,\"data\":\"x.example.\"},"
                + "{\"name\":\"x.example\",\"type\":1,\"data\":\"13.35.1.2\"},{\"type\":1,\"data\":\"13.35.1.3\"}]}";
        eq("DNS-over-HTTPS answer", Arrays.asList("13.35.1.2", "13.35.1.3"), Doh.parseAddresses(answer));
        eq("DNS-over-HTTPS without answer", 0, Doh.parseAddresses("{\"Status\":3}").size());
        eq("DNS-over-HTTPS garbage", 0, Doh.parseAddresses("<html>").size());
    }
}
