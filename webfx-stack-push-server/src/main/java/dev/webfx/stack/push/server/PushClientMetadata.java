package dev.webfx.stack.push.server;

/**
 * Session facts about one currently-connected push client, for the /monitor page: its current
 * {@code userId} (the signed-in user, or a logged-out sentinel/null when not signed in), its client
 * build version, and whether it runs as an installed PWA. version/pwa may be null for a client that
 * predates that reporting (an "unknown" bucket).
 *
 * @author Bruno Salmon
 */
public final class PushClientMetadata {

    private final Object userId;
    private final String clientVersion;
    private final Boolean pwa;

    public PushClientMetadata(Object userId, String clientVersion, Boolean pwa) {
        this.userId = userId;
        this.clientVersion = clientVersion;
        this.pwa = pwa;
    }

    public Object getUserId() {
        return userId;
    }

    public String getClientVersion() {
        return clientVersion;
    }

    public Boolean getPwa() {
        return pwa;
    }
}
