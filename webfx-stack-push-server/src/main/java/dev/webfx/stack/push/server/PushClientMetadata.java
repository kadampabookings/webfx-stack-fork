package dev.webfx.stack.push.server;

/**
 * Invariant per-connection facts about one currently-connected push client, for the /monitor
 * distributions: its client build version and whether it runs as an installed PWA. Either may be
 * null for a client that predates this reporting (an "unknown" bucket).
 *
 * @author Bruno Salmon
 */
public final class PushClientMetadata {

    private final String clientVersion;
    private final Boolean pwa;

    public PushClientMetadata(String clientVersion, Boolean pwa) {
        this.clientVersion = clientVersion;
        this.pwa = pwa;
    }

    public String getClientVersion() {
        return clientVersion;
    }

    public Boolean getPwa() {
        return pwa;
    }
}
