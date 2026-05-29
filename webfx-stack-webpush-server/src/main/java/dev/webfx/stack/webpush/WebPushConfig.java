package dev.webfx.stack.webpush;

import dev.webfx.platform.conf.Config;
import dev.webfx.platform.conf.SourcesConfig;

/**
 * Configuration for Web Push, loaded from the application's config sources
 * (typically {@code public-variables.properties} for the public key + subject,
 * and {@code secret-variables.properties} for the private key).
 * <p>
 * The keys are read from the {@code webpush.vapid} block:
 * <pre>{@code
 * webpush.vapid.publicKey  = ${VAPID_PUBLIC_KEY}
 * webpush.vapid.privateKey = ${VAPID_PRIVATE_KEY}
 * webpush.vapid.subject    = ${VAPID_SUBJECT}
 * }</pre>
 *
 * @author Bruno Salmon
 */
public final class WebPushConfig {

    private static final String CONFIG_PATH = "webpush.vapid";

    private final String publicKey;
    private final String privateKey;
    private final String subject;

    /** Construct directly — exposed for tests. Production code uses {@link #fromSourcesConfig()}. */
    public WebPushConfig(String publicKey, String privateKey, String subject) {
        this.publicKey  = publicKey;
        this.privateKey = privateKey;
        this.subject    = subject;
    }

    /**
     * Reads the configuration from {@link SourcesConfig#getSourcesRootConfig()}.
     * Returns {@code null} if the {@code webpush.vapid} block is missing or
     * the public/private keys aren't both set — the caller should treat this
     * as "Web Push not configured" and skip wiring the routes / service.
     */
    public static WebPushConfig fromSourcesConfig() {
        Config block = SourcesConfig.getSourcesRootConfig().childConfigAt(CONFIG_PATH);
        if (block == null) {
            return null;
        }
        String publicKey  = block.getString("publicKey");
        String privateKey = block.getString("privateKey");
        String subject    = block.getString("subject");
        if (isBlank(publicKey) || isBlank(privateKey)) {
            return null;
        }
        return new WebPushConfig(publicKey, privateKey, subject);
    }

    public String publicKey() {
        return publicKey;
    }

    public String privateKey() {
        return privateKey;
    }

    /**
     * The contact URI push service operators reach us through (per RFC 8292).
     * Must be a {@code mailto:} or {@code https:} URI. May be empty/null in
     * dev — the VAPID signer falls back to a placeholder in that case.
     */
    public String subject() {
        return subject;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
