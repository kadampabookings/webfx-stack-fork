package dev.webfx.stack.session.token;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;

/**
 * A short string that says something the server asserted, which a client can hold but not alter.
 *
 * <p>The problem it solves: a server that hands a client a fact and reads it back has told the client
 * what to say. Signing the fact makes the round trip safe — the client stores an opaque string, echoes
 * it, and the server recovers what it originally said or nothing at all.
 *
 * <p>Format is {@code base64url(payload).expiryMillis.base64url(mac)}, where the MAC covers
 * {@code base64url(payload).expiryMillis} — so the expiry is signed too, and a client cannot postpone
 * its own deadline by editing the field it can see.
 *
 * <p>Deliberately knows nothing about identity. It signs a string; what that string means belongs to the
 * caller. That keeps this class testable without a session, a principal or a running stack, and it is why
 * the payload is not JSON: anything that must be parsed to be verified invites parsing it before it has
 * been verified.
 *
 * <p><b>Verification order matters and is not an implementation detail.</b> The MAC is checked before the
 * expiry, and the payload is returned only after both. Nothing derived from an unverified token is ever
 * handed back, so a caller cannot accidentally act on an attacker's payload.
 *
 * @author Bruno Salmon
 */
public final class SignedToken {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final char SEPARATOR = '.';

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    /**
     * The keys this server signs and accepts with. The first mints; every entry verifies.
     *
     * <p>A list rather than one key so a rotation does not sign everyone out: publish the new key at the
     * head, keep the previous one until the longest possible token has expired, then drop it. With a
     * single key a rotation invalidates every live session at once, which tends to mean rotations do not
     * happen.
     */
    private static volatile List<byte[]> keys = List.of();

    private SignedToken() {}

    /**
     * Installs the signing keys. The first is used to mint; all are accepted when verifying.
     *
     * <p>Until this is called nothing can be minted and nothing verifies — {@link #mint} throws and
     * {@link #verify} returns null. An unconfigured server refuses to issue identities rather than
     * issuing unprotected ones.
     */
    public static void setKeys(List<byte[]> signingKeys) {
        keys = signingKeys == null ? List.of() : List.copyOf(signingKeys);
    }

    public static boolean isConfigured() {
        return !keys.isEmpty();
    }

    /**
     * Signs a payload with an absolute expiry.
     *
     * @param payload      what the server is asserting; opaque here
     * @param expiryMillis when it stops being true, as epoch milliseconds
     * @throws IllegalStateException if no signing key is configured — never returns an unsigned token
     */
    public static String mint(String payload, long expiryMillis) {
        List<byte[]> currentKeys = keys;
        if (currentKeys.isEmpty())
            throw new IllegalStateException("No signing key configured — refusing to mint an unprotected token");
        String signedPart = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + SEPARATOR + expiryMillis;
        return signedPart + SEPARATOR + ENCODER.encodeToString(mac(currentKeys.get(0), signedPart));
    }

    /**
     * Returns the payload this server signed, or null for anything else.
     *
     * <p>Null covers every failure — no key, wrong shape, bad MAC, expired, undecodable — on purpose. A
     * caller has one thing to check, and cannot accidentally distinguish "wrong signature" from "expired"
     * in a way that tells an attacker which of the two they achieved.
     *
     * @param nowMillis the current time, passed in so expiry is testable without waiting for it
     */
    public static String verify(String token, long nowMillis) {
        List<byte[]> currentKeys = keys;
        if (token == null || currentKeys.isEmpty())
            return null;
        int macSeparator = token.lastIndexOf(SEPARATOR);
        if (macSeparator < 0)
            return null;
        String signedPart = token.substring(0, macSeparator);
        String presentedMac = token.substring(macSeparator + 1);
        int expirySeparator = signedPart.lastIndexOf(SEPARATOR);
        if (expirySeparator < 0)
            return null;

        byte[] presented;
        try {
            presented = DECODER.decode(presentedMac);
        } catch (IllegalArgumentException e) {
            return null;
        }

        // Signature first, always: everything below this point reads bytes the client supplied.
        boolean authentic = false;
        for (byte[] key : currentKeys)
            // Constant-time, and every key is tried even after a match — comparing with equals(), or
            // returning early, leaks through timing how much of the MAC was right, which is enough to
            // forge one byte at a time.
            authentic |= MessageDigest.isEqual(presented, mac(key, signedPart));
        if (!authentic)
            return null;

        long expiryMillis;
        try {
            expiryMillis = Long.parseLong(signedPart.substring(expirySeparator + 1));
        } catch (NumberFormatException e) {
            return null; // signed by us, but malformed: still refuse rather than guess
        }
        if (nowMillis >= expiryMillis)
            return null;

        try {
            return new String(DECODER.decode(signedPart.substring(0, expirySeparator)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static byte[] mac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // A missing HmacSHA256 or a rejected key is a configuration fault, not a bad token. Throwing
            // here would let a caller treat it as "invalid token" and carry on unauthenticated.
            throw new IllegalStateException("Cannot compute HMAC", e);
        }
    }
}
