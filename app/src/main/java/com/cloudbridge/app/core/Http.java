package com.cloudbridge.app.core;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/** Small helpers shared by the WebDAV and S3 clients. */
final class Http {
    static final int MAX_XML = 64 * 1024 * 1024;
    private static final Pattern XML_CODE = Pattern.compile("<Code>([^<]*)</Code>");
    private static final Pattern XML_MESSAGE = Pattern.compile("<Message>([^<]*)</Message>");

    private Http() {
    }

    /**
     * Parses XML from an untrusted server: DOCTYPEs (XXE) are refused where the parser supports it, and
     * parse errors are silent. Any failure becomes a CloudException with the given user-readable message.
     */
    static Document parseXml(byte[] xml, boolean namespaceAware, String failureMessage) throws CloudException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(namespaceAware);
            try {
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (Exception ignored) {
                // Not every XML parser (e.g. Android's) supports this feature.
            }
            try {
                f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            } catch (Exception ignored) {
            }
            f.setExpandEntityReferences(false);
            DocumentBuilder builder = f.newDocumentBuilder();
            builder.setErrorHandler(new ErrorHandler() {
                @Override
                public void warning(SAXParseException e) {
                }

                @Override
                public void error(SAXParseException e) throws SAXException {
                    throw e;
                }

                @Override
                public void fatalError(SAXParseException e) throws SAXException {
                    throw e;
                }
            });
            return builder.parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new CloudException(502, failureMessage);
        }
    }

    /** Streams a 2xx response body into dest (via dest.part so a broken download never looks complete). */
    static void writeTo(HttpTransport.Response r, File dest) throws IOException {
        File part = new File(dest.getPath() + ".part");
        try (InputStream in = r.stream(); OutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            r.close();
        }
        if (dest.exists() && !dest.delete()) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new IOException("Could not replace " + dest.getName());
        }
        if (!part.renameTo(dest)) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new IOException("Could not save the downloaded file");
        }
    }

    /** Builds a readable error from a failed response and closes it. */
    static CloudException fail(HttpTransport.Response r, String what) {
        String detail = "";
        try {
            String body = new String(r.readAll(16 * 1024), java.nio.charset.StandardCharsets.UTF_8);
            Matcher code = XML_CODE.matcher(body);
            Matcher msg = XML_MESSAGE.matcher(body);
            if (code.find()) detail = code.group(1);
            if (msg.find()) detail = detail.isEmpty() ? msg.group(1) : detail + " - " + msg.group(1);
        } catch (IOException ignored) {
        }
        int status = r.code;
        r.close();
        StringBuilder sb = new StringBuilder(what).append(" failed (HTTP ").append(status).append(')');
        String hint = hint(status);
        if (!hint.isEmpty()) sb.append(": ").append(hint);
        if (!detail.isEmpty()) sb.append(" [").append(detail).append(']');
        return new CloudException(status, sb.toString());
    }

    private static String hint(int status) {
        switch (status) {
            case 301:
            case 307:
                return "wrong endpoint or region";
            case 401:
                return "check the username/password or keys";
            case 403:
                return "access denied";
            case 404:
                return "not found";
            case 405:
                return "not allowed (it may already exist)";
            case 409:
                return "conflict (a parent folder may be missing)";
            case 412:
                return "the destination already exists";
            case 507:
                return "storage is full";
            default:
                return "";
        }
    }

    static long parseHttpDate(String s) {
        if (s == null) return 0;
        try {
            return ZonedDateTime.parse(s.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    static long parseIsoDate(String s) {
        if (s == null) return 0;
        try {
            return Instant.parse(s.trim()).toEpochMilli();
        } catch (Exception e) {
            try {
                return OffsetDateTime.parse(s.trim()).toInstant().toEpochMilli();
            } catch (Exception e2) {
                return 0;
            }
        }
    }

    static long parseLong(String s, long fallback) {
        if (s == null) return fallback;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
