package com.cloudbridge.app.core;

/** Document ids look like "<accountId>:<path>", e.g. "a1b2c3d4:/Photos/cat.jpg". */
public final class Ids {
    private Ids() {
    }

    public static String encode(String accountId, String path) {
        return accountId + ":" + Paths.normalize(path);
    }

    public static String accountOf(String documentId) {
        int i = documentId == null ? -1 : documentId.indexOf(':');
        if (i <= 0) throw new IllegalArgumentException("Bad document id: " + documentId);
        return documentId.substring(0, i);
    }

    public static String pathOf(String documentId) {
        int i = documentId == null ? -1 : documentId.indexOf(':');
        if (i <= 0) throw new IllegalArgumentException("Bad document id: " + documentId);
        return Paths.normalize(documentId.substring(i + 1));
    }
}
