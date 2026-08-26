package dev.webfx.stack.session.token.plugin;

import dev.webfx.platform.boot.spi.ApplicationJob;
import dev.webfx.platform.conf.Config;
import dev.webfx.platform.conf.ConfigLoader;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.session.token.SignedToken;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Reads the session-token signing keys from configuration and installs them.
 *
 * <p>Kept apart from {@link SignedToken} so the primitive depends on nothing and can be exercised
 * without a configured stack — the arrangement that lets its adversarial check run from a bare main().
 * This is the half that knows about configuration; that half knows about signing.
 *
 * <p>The current key mints and verifies; a previous key, when present, verifies only. That is what makes
 * a rotation survivable: publish a new key, keep the old one until every token minted under it has
 * expired, then drop it. With a single key every live session dies at the moment of rotation, and a
 * rotation that signs out every user is one that does not get performed.
 *
 * @author Bruno Salmon
 */
public final class SessionTokenKeysInitializer implements ApplicationJob {

    private static final String CONFIG_PATH = "webfx.stack.session.token";
    /** HMAC-SHA256 territory: a key shorter than its 256-bit block buys nothing and hides that it hasn't. */
    private static final int MINIMUM_KEY_BYTES = 32;

    @Override
    public void onInit() {
        ConfigLoader.onConfigLoaded(CONFIG_PATH, this::onConfigLoaded);
    }

    private void onConfigLoaded(Config config) {
        List<byte[]> keys = new ArrayList<>(2);
        addKeyIfPresent(keys, config == null ? null : config.getString("signingKey"), "signingKey");
        addKeyIfPresent(keys, config == null ? null : config.getString("previousSigningKey"), "previousSigningKey");
        SignedToken.setKeys(keys);
        if (keys.isEmpty())
            // Loud, because the consequence is silent: with no key, identities cannot be minted, and
            // whatever depends on them falls back to whatever it does when nobody is identified.
            Console.log("⚠️ No session token signing key configured (" + CONFIG_PATH
                        + ".signingKey) — identity tokens cannot be minted or verified");
        else
            Console.log("🔑 Session token signing keys installed (" + keys.size()
                        + (keys.size() == 1 ? " key)" : " keys — a rotation is in progress)"));
    }

    /**
     * Decodes one configured key, refusing rather than weakening.
     *
     * <p>A key that is absent, unreadable or too short is dropped with a reason. It is deliberately not
     * padded, hashed or otherwise rescued into usability: silently accepting a four-character key would
     * produce a server that mints tokens, verifies them, looks entirely healthy, and protects nothing.
     * The value itself is never logged — only whether it was usable.
     */
    private void addKeyIfPresent(List<byte[]> keys, String configuredKey, String keyName) {
        if (configuredKey == null || configuredKey.isBlank())
            return;
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(configuredKey.trim());
        } catch (IllegalArgumentException e) {
            // Not base64: treat the raw text as bytes rather than refuse outright, but hold it to the
            // same length bar below, so a pasted-wrong value fails on length instead of passing quietly.
            decoded = configuredKey.trim().getBytes(StandardCharsets.UTF_8);
        }
        if (decoded.length < MINIMUM_KEY_BYTES) {
            Console.log("⚠️ Ignoring " + CONFIG_PATH + "." + keyName + ": needs at least "
                        + MINIMUM_KEY_BYTES + " bytes, got " + decoded.length);
            return;
        }
        keys.add(decoded);
    }
}
