package com.kellen.traffic;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Short-lived routing authorization signed by a trusted ingress. It never replaces business authentication.
 */
public record CanaryGrant(String service, String tag, Instant expiresAt) {

    public static final String HEADER = "X-Traffic-Canary-Grant";
    public static final String ATTACHMENT = "traffic.canary.grant";

    private static final long MAX_LIFETIME_SECONDS = 60;
    private static final Pattern LABEL = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
    private static final Pattern EPOCH = Pattern.compile("[1-9][0-9]{0,11}");
    private static final Pattern BASE64URL = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public static String issue(String service, String tag, Instant expiresAt, String signingKey) {
        if (!validLabel(service) || !validLabel(tag) || "stable".equals(tag) || expiresAt == null
                || !EPOCH.matcher(Long.toString(expiresAt.getEpochSecond())).matches() || !validKey(signingKey)) {
            throw new IllegalArgumentException("Invalid canary grant arguments");
        }
        String data = String.join(".", "v1", service, tag, Long.toString(expiresAt.getEpochSecond()));
        return data + "." + ENCODER.encodeToString(mac(data, signingKey));
    }

    public static CanaryGrant verify(String token, String signingKey, Instant now) {
        if (token == null || token.length() > 190 || !validKey(signingKey)) {
            return null;
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 5 || !"v1".equals(parts[0]) || !validLabel(parts[1]) || !validLabel(parts[2])
                || "stable".equals(parts[2]) || !EPOCH.matcher(parts[3]).matches()
                || parts[4].length() != 43 || !BASE64URL.matcher(parts[4]).matches()) {
            return null;
        }
        Instant expiry = Instant.ofEpochSecond(Long.parseLong(parts[3]));
        if (!expiry.isAfter(now) || expiry.isAfter(now.plusSeconds(MAX_LIFETIME_SECONDS))) {
            return null;
        }
        String data = token.substring(0, token.lastIndexOf('.'));
        byte[] expected = ENCODER.encode(mac(data, signingKey));
        if (!MessageDigest.isEqual(expected, parts[4].getBytes(StandardCharsets.US_ASCII))) {
            return null;
        }
        return new CanaryGrant(parts[1], parts[2], expiry);
    }

    public static boolean validLabel(String value) {
        return value != null && LABEL.matcher(value).matches();
    }

    public static boolean validKey(String value) {
        return value != null && value.length() == 43 && BASE64URL.matcher(value).matches()
                && ENCODER.encodeToString(DECODER.decode(value)).equals(value);
    }

    private static byte[] mac(String data, String key) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(DECODER.decode(key), "HmacSHA256"));
            return hmac.doFinal(data.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 is unavailable", ex);
        }
    }
}
