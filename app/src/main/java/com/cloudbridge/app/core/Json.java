package com.cloudbridge.app.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader/quoter so the core code stays plain Java (org.json exists on Android but not in the
 * JDK test run). Objects become Map, arrays List, numbers Long or Double, plus String, Boolean and null.
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    /** @throws IllegalArgumentException when the text is not valid JSON. */
    public static Object parse(String text) {
        Json p = new Json(text);
        p.skipWs();
        Object v = p.value(0);
        p.skipWs();
        if (p.i != p.s.length()) throw p.error("Unexpected trailing data");
        return v;
    }

    /** Parses a JSON object, or throws CloudException (the service sent something unexpected). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text, String what) throws CloudException {
        try {
            Object v = parse(text);
            if (v instanceof Map) return (Map<String, Object>) v;
        } catch (IllegalArgumentException ignored) {
        }
        throw new CloudException(502, what + " sent a reply that CloudBridge could not read");
    }

    public static String quote(String v) {
        StringBuilder sb = new StringBuilder(v.length() + 2).append('"');
        for (int k = 0; k < v.length(); k++) {
            char c = v.charAt(k);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    // ---- typed accessors that never throw

    public static String str(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof String ? (String) v : null;
    }

    /** Numbers may arrive as JSON numbers or as strings (Drive sends "size" as a string). */
    public static long num(Map<String, Object> m, String key, long fallback) {
        Object v = m == null ? null : m.get(key);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try {
                return Long.parseLong(((String) v).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof List ? (List<Object>) v : new ArrayList<>();
    }

    // ---- parser

    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException(msg + " at " + i);
    }

    private void skipWs() {
        while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++;
    }

    private Object value(int depth) {
        if (depth > 64) throw error("Nested too deeply");
        if (i >= s.length()) throw error("Unexpected end");
        char c = s.charAt(i);
        if (c == '{') return object(depth);
        if (c == '[') return array(depth);
        if (c == '"') return string();
        if (s.startsWith("true", i)) {
            i += 4;
            return Boolean.TRUE;
        }
        if (s.startsWith("false", i)) {
            i += 5;
            return Boolean.FALSE;
        }
        if (s.startsWith("null", i)) {
            i += 4;
            return null;
        }
        return number();
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        skipWs();
        if (i < s.length() && s.charAt(i) == '}') {
            i++;
            return m;
        }
        while (true) {
            skipWs();
            if (i >= s.length() || s.charAt(i) != '"') throw error("Expected a key");
            String key = string();
            skipWs();
            if (i >= s.length() || s.charAt(i) != ':') throw error("Expected ':'");
            i++;
            skipWs();
            m.put(key, value(depth + 1));
            skipWs();
            if (i >= s.length()) throw error("Unexpected end");
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw error("Expected ',' or '}'");
        }
    }

    private List<Object> array(int depth) {
        List<Object> l = new ArrayList<>();
        i++;
        skipWs();
        if (i < s.length() && s.charAt(i) == ']') {
            i++;
            return l;
        }
        while (true) {
            skipWs();
            l.add(value(depth + 1));
            skipWs();
            if (i >= s.length()) throw error("Unexpected end");
            char c = s.charAt(i++);
            if (c == ']') return l;
            if (c != ',') throw error("Expected ',' or ']'");
        }
    }

    private String string() {
        i++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw error("Unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (i >= s.length()) throw error("Unterminated escape");
            char e = s.charAt(i++);
            switch (e) {
                case '"':
                case '\\':
                case '/':
                    sb.append(e);
                    break;
                case 'b':
                    sb.append('\b');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'u':
                    if (i + 4 > s.length()) throw error("Bad \\u escape");
                    try {
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("Bad \\u escape");
                    }
                    i += 4;
                    break;
                default:
                    throw error("Bad escape");
            }
        }
    }

    private Object number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw error("Unexpected character");
        String t = s.substring(start, i);
        try {
            if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) return Long.parseLong(t);
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            throw error("Bad number");
        }
    }
}
