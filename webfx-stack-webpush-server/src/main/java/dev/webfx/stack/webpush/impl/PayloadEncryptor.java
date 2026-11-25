package dev.webfx.stack.webpush.impl;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Base64;

/**
 * Encrypts a push payload per RFC 8291 ({@code aes128gcm} content encoding,
 * RFC 8188), producing the binary body that goes directly into the push request
 * to the push service.
 * <p>
 * Implemented on the JDK's own crypto (SunEC ECDH, HmacSHA256 for HKDF,
 * AES/GCM) so the module needs no third-party library. It previously used
 * {@code nl.martijndwars:web-push}, which drags in an HTTP client, Netty 4.1,
 * jose4j and jcommander at runtime — none of which we used, and which carried
 * the server's open dependency alerts.
 * <p>
 * The output is one {@code aes128gcm} record: the 86-byte header
 * ({@code salt(16) || rs(4) || idlen(1)=65 || keyid = as_public(65)}) followed
 * by the ciphertext of {@code plaintext || 0x02}. Checked against the test
 * vector of RFC 8291 Appendix A.
 *
 * @author Bruno Salmon
 */
public final class PayloadEncryptor {

    /** EC point uncompressed marker (0x04) || 32-byte X || 32-byte Y = 65 bytes. */
    private static final int UNCOMPRESSED_P256_LENGTH = 65;
    private static final int COORDINATE_LENGTH = 32;
    private static final int AUTH_SECRET_LENGTH = 16;
    private static final int SALT_LENGTH = 16;
    private static final int RECORD_SIZE = 4096;
    /** Header (86) + padding delimiter (1) + GCM tag (16) must fit in one record. */
    private static final int MAX_PLAINTEXT_LENGTH = RECORD_SIZE - 86 - 1 - 16;

    private static final byte[] KEY_INFO_PREFIX = "WebPush: info\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CEK_INFO = "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NONCE_INFO = "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII);

    private static final ECParameterSpec P256 = p256Spec();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * Encrypts {@code plaintext} for the given subscription's keys and returns
     * the complete {@code aes128gcm}-formatted body ready to POST to the push
     * service.
     *
     * @param plaintext       the application payload bytes (typically JSON-encoded)
     * @param p256dhKeyBase64 the subscription's {@code p256dh} key (65-byte
     *                        uncompressed point, base64url)
     * @param authKeyBase64   the subscription's {@code auth} secret (16 bytes, base64url)
     * @return the encrypted body — directly the HTTP request body, with the
     *         {@code Content-Encoding: aes128gcm} header set by the caller
     */
    public byte[] encrypt(byte[] plaintext, String p256dhKeyBase64, String authKeyBase64) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"), SECURE_RANDOM);
            byte[] salt = new byte[SALT_LENGTH];
            SECURE_RANDOM.nextBytes(salt);
            return encrypt(plaintext, p256dhKeyBase64, authKeyBase64, generator.generateKeyPair(), salt);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Web Push payload encryption failed", e);
        }
    }

    /** Deterministic core, with the ephemeral key pair and salt supplied (tests use the RFC 8291 vector). */
    static byte[] encrypt(byte[] plaintext, String p256dhKeyBase64, String authKeyBase64, KeyPair asKeyPair, byte[] salt) {
        if (plaintext.length > MAX_PLAINTEXT_LENGTH)
            throw new IllegalArgumentException("Push payload too large: " + plaintext.length + " bytes (max " + MAX_PLAINTEXT_LENGTH + ")");
        byte[] uaPublic = decodeBase64Url(p256dhKeyBase64);
        ECPublicKey uaKey = parseP256dhKey(uaPublic);
        byte[] authSecret = decodeBase64Url(authKeyBase64);
        if (authSecret.length != AUTH_SECRET_LENGTH)
            throw new IllegalArgumentException("Expected a 16-byte auth secret; got " + authSecret.length);
        try {
            byte[] asPublic = encodeUncompressed((ECPublicKey) asKeyPair.getPublic());

            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(asKeyPair.getPrivate());
            agreement.doPhase(uaKey, true);
            byte[] ecdhSecret = agreement.generateSecret();

            // RFC 8291 §3.4: IKM = HKDF(auth_secret, ecdh_secret, "WebPush: info" 0x00 ua_public as_public, 32)
            byte[] keyInfo = concat(KEY_INFO_PREFIX, uaPublic, asPublic);
            byte[] ikm = hmacSha256(hmacSha256(authSecret, ecdhSecret), concat(keyInfo, new byte[]{1}));

            // RFC 8188 §2.2/§2.3: CEK and NONCE from PRK = HKDF-Extract(salt, IKM)
            byte[] prk = hmacSha256(salt, ikm);
            byte[] cek = truncate(hmacSha256(prk, concat(CEK_INFO, new byte[]{1})), 16);
            byte[] nonce = truncate(hmacSha256(prk, concat(NONCE_INFO, new byte[]{1})), 12);

            // Single, last record: plaintext || 0x02 (padding delimiter, no padding)
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
            byte[] ciphertext = cipher.doFinal(concat(plaintext, new byte[]{2}));

            return ByteBuffer.allocate(SALT_LENGTH + 4 + 1 + asPublic.length + ciphertext.length)
                    .put(salt)
                    .putInt(RECORD_SIZE)
                    .put((byte) asPublic.length)
                    .put(asPublic)
                    .put(ciphertext)
                    .array();
        } catch (Exception e) {
            throw new IllegalStateException("Web Push payload encryption failed", e);
        }
    }

    /**
     * Parses the subscription's {@code p256dh} key (65-byte uncompressed P-256
     * point) and checks the point lies on the curve, so a malformed or hostile
     * subscription key never reaches the ECDH computation.
     */
    static ECPublicKey parseP256dhKey(byte[] keyBytes) {
        if (keyBytes.length != UNCOMPRESSED_P256_LENGTH || keyBytes[0] != 0x04)
            throw new IllegalArgumentException("Invalid subscription p256dh key: expected a 65-byte uncompressed P-256 point");
        BigInteger x = new BigInteger(1, java.util.Arrays.copyOfRange(keyBytes, 1, 1 + COORDINATE_LENGTH));
        BigInteger y = new BigInteger(1, java.util.Arrays.copyOfRange(keyBytes, 1 + COORDINATE_LENGTH, UNCOMPRESSED_P256_LENGTH));
        BigInteger p = ((ECFieldFp) P256.getCurve().getField()).getP();
        BigInteger lhs = y.multiply(y).mod(p);
        BigInteger rhs = x.pow(3).add(P256.getCurve().getA().multiply(x)).add(P256.getCurve().getB()).mod(p);
        if (x.compareTo(p) >= 0 || y.compareTo(p) >= 0 || !lhs.equals(rhs))
            throw new IllegalArgumentException("Invalid subscription p256dh key: point is not on P-256");
        try {
            return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid subscription p256dh key", e);
        }
    }

    static byte[] encodeUncompressed(ECPublicKey key) {
        byte[] out = new byte[UNCOMPRESSED_P256_LENGTH];
        out[0] = 0x04;
        writeFixed(key.getW().getAffineX(), out, 1);
        writeFixed(key.getW().getAffineY(), out, 1 + COORDINATE_LENGTH);
        return out;
    }

    private static void writeFixed(BigInteger value, byte[] out, int offset) {
        byte[] bytes = value.toByteArray(); // big-endian, may carry a leading 0x00 or be shorter than 32
        int copy = Math.min(bytes.length, COORDINATE_LENGTH);
        System.arraycopy(bytes, bytes.length - copy, out, offset + COORDINATE_LENGTH - copy, copy);
    }

    static ECParameterSpec p256Spec() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (Exception e) {
            throw new IllegalStateException("P-256 curve unavailable", e);
        }
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] truncate(byte[] bytes, int length) {
        return java.util.Arrays.copyOf(bytes, length);
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts)
            length += part.length;
        ByteBuffer buffer = ByteBuffer.allocate(length);
        for (byte[] part : parts)
            buffer.put(part);
        return buffer.array();
    }

    /** Base64url, tolerant of padding (some encoders include '='). */
    private static byte[] decodeBase64Url(String base64Url) {
        int eq = base64Url.indexOf('=');
        try {
            return Base64.getUrlDecoder().decode(eq < 0 ? base64Url : base64Url.substring(0, eq));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid base64url value in subscription keys", e);
        }
    }
}
