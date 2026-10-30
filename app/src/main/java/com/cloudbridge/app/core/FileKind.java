package com.cloudbridge.app.core;

import java.util.Locale;

/** What kind of thing a file is, for its icon: the colour and the little label drawn on it. */
public enum FileKind {
    FOLDER("", 0xFFFFC107),
    IMAGE("IMG", 0xFF26A69A),
    VIDEO("VID", 0xFF7E57C2),
    AUDIO("MP3", 0xFFEC407A),
    PDF("PDF", 0xFFE53935),
    DOC("DOC", 0xFF1E88E5),
    SHEET("XLS", 0xFF43A047),
    SLIDES("PPT", 0xFFFB8C00),
    HTML("HTML", 0xFF1E88E5),
    ARCHIVE("ZIP", 0xFF8D6E63),
    APK("APK", 0xFF66BB6A),
    TEXT("TXT", 0xFF78909C),
    OTHER("", 0xFF90A4AE);

    public final String label;
    public final int color;

    FileKind(String label, int color) {
        this.label = label;
        this.color = color;
    }

    public static FileKind of(String name, boolean dir) {
        if (dir) return FOLDER;
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        String ext = dot >= 0 ? n.substring(dot + 1) : "";
        switch (ext) {
            case "jpg": case "jpeg": case "png": case "gif": case "webp": case "bmp": case "heic": case "heif": case "dng":
                return IMAGE;
            case "mp4": case "mkv": case "mov": case "avi": case "webm": case "3gp": case "m4v": case "ts":
                return VIDEO;
            case "mp3": case "m4a": case "aac": case "wav": case "flac": case "ogg": case "opus": case "amr":
                return AUDIO;
            case "pdf":
                return PDF;
            case "doc": case "docx": case "odt": case "rtf":
                return DOC;
            case "xls": case "xlsx": case "ods": case "csv":
                return SHEET;
            case "ppt": case "pptx": case "odp":
                return SLIDES;
            case "html": case "htm": case "xhtml":
                return HTML;
            case "zip": case "rar": case "7z": case "tar": case "gz": case "bz2": case "xz":
                return ARCHIVE;
            case "apk": case "apks": case "xapk":
                return APK;
            case "txt": case "md": case "log": case "json": case "xml": case "ini":
                return TEXT;
            default:
                return OTHER;
        }
    }

    /** True for pictures and videos, which are drawn as their own preview. */
    public boolean hasPreview() {
        return this == IMAGE || this == VIDEO;
    }
}
