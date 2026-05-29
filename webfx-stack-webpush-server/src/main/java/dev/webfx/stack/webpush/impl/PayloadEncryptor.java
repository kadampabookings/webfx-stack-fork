package dev.webfx.stack.webpush.impl;

import nl.martijndwars.webpush.AbstractPushService;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Encrypted;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.jce.spec.ECPublicKeySpec;
import org.bouncycastle.math.ec.ECPoint;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.KeyFactory;
import java.security.Security;
import java.util.Base64;

/**
 * Encrypts a push payload per RFC 8291 ({@code aes128gcm} content encoding),
 * producing the binary body that goes directly into the push request to the
 * push service.
 * <p>
 * Uses {@link AbstractPushService#encrypt} from {@code nl.martijndwars:web-push}
 * for the actual ECDH key derivation + HKDF + AES-128-GCM encryption (the
 * risky crypto). We assemble the RFC 8291 wire-format frame ourselves —
 * the library returns the salt, ephemeral public key, and ciphertext as
 * separate fields, leaving the framing to the caller.
 *
 * <h2>RFC 8291 wire format (aes128gcm)</h2>
 * <pre>
 * +--------+----+-------+------------------+
 * | salt   | rs | idlen | keyid (idlen)    |
 * | 16 B   | 4B | 1 B   | (server pub key) |
 * +--------+----+-------+------------------+ <- header
 * | ciphertext (variable)                  |
 * +----------------------------------------+
 * </pre>
 * The header is 86 bytes for our case (idlen = 65 = uncompressed P-256 point).
 *
 * @author Bruno Salmon
 */
public final class PayloadEncryptor {

    /**
     * Record size — controls the maximum chunk size of plaintext per AES-GCM
     * record. Push payloads are well below 4 KiB, so a single record suffices.
     * Must be at least 18 (RFC 8188 §2.1).
     */
    private static final int RECORD_SIZE = 4096;

    /** EC point uncompressed marker (0x04) || 32-byte X || 32-byte Y = 65 bytes. */
    private static final int UNCOMPRESSED_P256_LENGTH = 65;

    static {
        // BouncyCastle is needed for the curve operations the library performs
        // internally. Registering the provider is idempotent — calling it
        // twice is a no-op. Done in a static initialiser so the first encrypt
        // call has it available.
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

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
            ECPublicKey clientKey = parseP256dhKey(p256dhKeyBase64);
            byte[] authSecret = Base64.getUrlDecoder().decode(stripPadding(authKeyBase64));

            // The library generates an ephemeral ECDH key pair + salt internally
            // and returns the ciphertext plus the public-key and salt the
            // recipient needs to derive the same shared secret.
            Encrypted encrypted = AbstractPushService.encrypt(
                    plaintext, clientKey, authSecret, Encoding.AES128GCM);

            return assembleAes128GcmFrame(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException("Web Push payload encryption failed", e);
        }
    }

    /**
     * Combines the salt, server's ephemeral public key, and ciphertext into the
     * RFC 8291 wire frame the push service expects. The frame is what goes
     * directly into the HTTP body.
     */
    private static byte[] assembleAes128GcmFrame(Encrypted encrypted) {
        byte[] salt = encrypted.getSalt();
        byte[] keyId = uncompressedPublicKeyBytes(
                (org.bouncycastle.jce.interfaces.ECPublicKey) encrypted.getPublicKey());
        byte[] ciphertext = encrypted.getCiphertext();

        // 16 (salt) + 4 (record size BE) + 1 (key id length) + 65 (key id) + ciphertext
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                salt.length + 4 + 1 + keyId.length + ciphertext.length);
        out.write(salt, 0, salt.length);
        out.write(ByteBuffer.allocate(4).putInt(RECORD_SIZE).array(), 0, 4);
        out.write(keyId.length);
        out.write(keyId, 0, keyId.length);
        out.write(ciphertext, 0, ciphertext.length);
        return out.toByteArray();
    }

    /**
     * Encodes the ephemeral server public key as the 65-byte uncompressed form
     * RFC 8291 requires for the {@code keyid} field
     * ({@code 0x04 || X(32) || Y(32)}).
     */
    private static byte[] uncompressedPublicKeyBytes(ECPublicKey publicKey) {
        ECPoint point = publicKey.getQ();
        byte[] encoded = point.getEncoded(false); // false = uncompressed
        if (encoded.length != UNCOMPRESSED_P256_LENGTH) {
            throw new IllegalStateException(
                    "Expected uncompressed P-256 key to be 65 bytes; got " + encoded.length);
        }
        return encoded;
    }

    /**
     * Parses the subscription's {@code p256dh} key (base64url-encoded
     * 65-byte uncompressed EC point) into a BouncyCastle {@link ECPublicKey}.
     * The library's encrypt method takes the BC-specific interface (not the
     * standard {@code java.security.interfaces.ECPublicKey}), so we use BC's
     * KeyFactory rather than the default JCE one.
     */
    static ECPublicKey parseP256dhKey(String p256dhKeyBase64) {
        try {
            byte[] keyBytes = Base64.getUrlDecoder().decode(stripPadding(p256dhKeyBase64));
            if (keyBytes.length != UNCOMPRESSED_P256_LENGTH) {
                throw new IllegalArgumentException(
                        "Expected 65 bytes (uncompressed P-256 point); got " + keyBytes.length);
            }

            ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec("secp256r1");
            ECPoint point = spec.getCurve().decodePoint(keyBytes);
            ECPublicKeySpec publicKeySpec = new ECPublicKeySpec(point, spec);
            return (ECPublicKey) KeyFactory.getInstance("EC", "BC").generatePublic(publicKeySpec);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid subscription p256dh key", e);
        }
    }

    /** Some base64url encoders include padding (=); strip it to be tolerant. */
    private static String stripPadding(String base64Url) {
        int eq = base64Url.indexOf('=');
        return eq < 0 ? base64Url : base64Url.substring(0, eq);
    }
}
