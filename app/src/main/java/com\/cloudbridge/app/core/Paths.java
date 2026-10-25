package com.cloudbridge.app.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pure-Java helpers for cloud paths ("/a/b/c.txt"), percent-encoding and MIME guessing. */
public final class Paths {
    /** Same value as DocumentsContract.Document.MIME_TYPE_DIR. */
    public static final String MIME_DIR = "vnd.android.document/directory";
    public static final String MIME_DEFAULT = "application/octet-stream";

    private static final Map<String, String> MIME = new HashMap<>();

    static {
        String[] pairs = {
                "txt", "text/plain", "log", "text/plain", "md", "text/markdown", "csv", "text/csv",
                "html", "text/html", "htm", "text/html", "css", "text/css", "js", "text/javascript",
                "json", "application/json", "xml", "application/xml", "pdf", "application/pdf",
                "zip", "application/zip", "gz", "application/gzip", "tar", "application/x-tar",
                "7z", "application/x-7z-compressed", "rar", "application/vnd.rar",
                "jpg", "image/jpeg", "jpeg", "image/jpeg", "png", "image/png", "gif", "image/gif",
                "webp", "image/webp", "bmp", "image/bmp", "svg", "image/svg+xml", "heic", "image/heic",
                "mp3", "audio/mpeg", "wav", "audio/wav", "flac", "audio/flac", "ogg", "audio/ogg",
                "m4a", "audio/mp4", "aac", "audio/aac",
                "mp4", "video/mp4", "mkv", "video/x-matroska", "webm", "video/webm",
                "avi", "video/x-msvideo", "mov", "video/quicktime", "3gp", "video/3gpp",
                "doc", "application/msword",
                "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "xls", "application/vnd.ms-excel",
                "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "ppt", "application/vnd.ms-powerpoint",
                "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "apk", "application/vnd.android.package-archive", "epub", "application/epub+zip"
        };
        for (int i = 0; i < pairs.length; i += 2) MIME.put(pairs[i], pairs[i + 1]);
    }

    private Paths() {
    }

    /** Leading slash, no trailing slash, no empty/"."/".." segments. Root is "/". */
    public static String normalize(String p) {
        if (p == null) return "/";
        List<String> out = new ArrayList<>();
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (!out.isEmpty()) out.remove(out.size() - 1);
                continue;
            }
            out.add(seg);
        }
        if (out.isEmpty()) return "/";
        StringBuilder sb = new StringBuilder();
        for (String s : out) sb.append('/').append(s);
        return sb.toString();
    }

    public static String parent(String p) {
        String n = normalize(p);
        int i = n.lastIndexOf('/');
        return i <= 0 ? "/" : n.substring(0, i);
    }

    public static String name(String p) {
        String n = normalize(p);
        return n.equals("/") ? "" : n.substring(n.lastIndexOf('/') + 1);
    }

    public static String join(String dir, String name) {
        return normalize(normalize(dir) + "/" + name);
    }

    /** Makes a user-supplied display name safe to use as a single path segment. */
    public static String sanitizeName(String name) {
        String n = name == null ? "" : name.replace('/', '_').replace('\0', '_').trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) return "_";
        return n;
    }

    public static boolean isUnder(String parent, String child) {
        String p = normalize(parent);
        String c = normalize(child);
        if (p.equals("/")) return !c.equals("/");
        return c.startsWith(p + "/");
    }

    /** Percent-encodes one path segment: only A-Z a-z 0-9 - . _ ~ stay as they are (RFC 3986 unreserved). */
    public static String encodeSegment(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length + 8);
        for (byte value : bytes) {
            int b = value & 0xff;
            if ((b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9')
                    || b == '-' || b == '.' || b == '_' || b == '~') {
                sb.append((char) b);
            } else {
                sb.append('%');
                sb.append("0123456789ABCDEF".charAt(b >> 4));
                sb.append("0123456789ABCDEF".charAt(b & 15));
            }
        }
        return sb.toString();
    }

    /** Encodes a normalized path segment by segment, keeping the "/" separators. */
    public static String encodePath(String p) {
        String n = normalize(p);
        if (n.equals("/")) return "/";
        StringBuilder sb = new StringBuilder();
        for (String seg : n.substring(1).split("/")) sb.append('/').append(encodeSegment(seg));
        return sb.toString();
    }

    /** Percent-decodes as UTF-8. A '+' stays a '+'. Malformed escapes are kept literally. */
    public static String decode(String s) {
        if (s.indexOf('%') < 0) return s;
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() && hexVal(s.charAt(i + 1)) >= 0 && hexVal(s.charAt(i + 2)) >= 0) {
                out.write(hexVal(s.charAt(i + 1)) * 16 + hexVal(s.charAt(i + 2)));
                i += 3;
            } else {
                int cp = s.codePointAt(i);
                byte[] b = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
                out.write(b, 0, b.length);
                i += Character.charCount(cp);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int hexVal(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    public static String mimeFor(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return MIME_DEFAULT;
        String m = MIME.get(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
        return m == null ? MIME_DEFAULT : m;
    }
}
