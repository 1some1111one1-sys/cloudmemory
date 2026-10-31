package com.cloudbridge.app.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reading the answer of a DNS-over-HTTPS server (the JSON form that Cloudflare and Google offer). It is the second
 * try when the phone's own DNS cannot find a TeraBox server (some networks and DNS servers do not).
 */
public final class Doh {
    private Doh() {
    }

    /** The IPv4 addresses (record type 1) in a JSON answer; empty when there are none. */
    public static List<String> parseAddresses(String json) {
        List<String> out = new ArrayList<>();
        Object root;
        try {
            root = Json.parse(json);
        } catch (RuntimeException e) {
            return out;
        }
        if (!(root instanceof Map)) return out;
        Object answers = ((Map<?, ?>) root).get("Answer");
        if (!(answers instanceof List)) return out;
        for (Object o : (List<?>) answers) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> a = (Map<?, ?>) o;
            Object type = a.get("type");
            Object data = a.get("data");
            if (type instanceof Number && ((Number) type).intValue() == 1 && data instanceof String
                    && ((String) data).matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                out.add((String) data);
            }
        }
        return out;
    }
}
