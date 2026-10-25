package com.cloudbridge.app.core;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** AWS Signature Version 4 for S3 (header based). */
final class S3Signer {
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US).withZone(ZoneOffset.UTC);

    static final class Result {
        final String authorization;
        final String amzDate;
        /** Sorted, percent-encoded query string to append to the URL (may be empty). */
        final String canonicalQuery;

        Result(String authorization, String amzDate, String canonicalQuery) {
            this.authorization = authorization;
            this.amzDate = amzDate;
            this.canonicalQuery = canonicalQuery;
        }
    }

    private S3Signer() {
    }

    /**
     * @param host           exactly the Host header that will be sent (with :port if not the default)
     * @param canonicalUri   the already percent-encoded request path that will be sent
     * @param query          raw (unencoded) query parameters
     * @param extraHeaders   additional headers to sign (e.g. x-amz-copy-source, range)
     * @param payloadHash    lower-case hex SHA-256 of the request body
     */
    static Result sign(String method, String host, String canonicalUri, Map<String, String> query,
                       Map<String, String> extraHeaders, String payloadHash,
                       String accessKey, String secretKey, String region, long epochMillis) {
        String amzDate = AMZ_DATE.format(Instant.ofEpochMilli(epochMillis));
        String dateStamp = amzDate.substring(0, 8);

        TreeMap<String, String> encodedQuery = new TreeMap<>();
        for (Map.Entry<String, String> e : query.entrySet()) {
            encodedQuery.put(Paths.encodeSegment(e.getKey()), Paths.encodeSegment(e.getValue()));
        }
        StringBuilder canonicalQuery = new StringBuilder();
        for (Map.Entry<String, String> e : encodedQuery.entrySet()) {
            if (canonicalQuery.length() > 0) canonicalQuery.append('&');
            canonicalQuery.append(e.getKey()).append('=').append(e.getValue());
        }

        TreeMap<String, String> headers = new TreeMap<>();
        headers.put("host", host);
        headers.put("x-amz-content-sha256", payloadHash);
        headers.put("x-amz-date", amzDate);
        for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
            headers.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().trim().replaceAll("\\s+", " "));
        }
        StringBuilder canonicalHeaders = new StringBuilder();
        StringBuilder signedHeaders = new StringBuilder();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            canonicalHeaders.append(e.getKey()).append(':').append(e.getValue()).append('\n');
            if (signedHeaders.length() > 0) signedHeaders.append(';');
            signedHeaders.append(e.getKey());
        }

        String canonicalRequest = method + "\n" + canonicalUri + "\n" + canonicalQuery + "\n"
                + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;

        String scope = dateStamp + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + Crypto.sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));

        byte[] kDate = Crypto.hmacSha256(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] kRegion = Crypto.hmacSha256(kDate, region);
        byte[] kService = Crypto.hmacSha256(kRegion, "s3");
        byte[] kSigning = Crypto.hmacSha256(kService, "aws4_request");
        String signature = Crypto.hex(Crypto.hmacSha256(kSigning, stringToSign));

        String authorization = "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
        return new Result(authorization, amzDate, canonicalQuery.toString());
    }
}
