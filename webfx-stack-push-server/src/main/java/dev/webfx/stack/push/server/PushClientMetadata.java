package dev.webfx.stack.push.server;

/**
 * Session facts about one currently-connected push client, for the /monitor page: its current
 * {@code userId} (the signed-in user, or a logged-out sentinel/null when not signed in), its client
 * build version, whether it runs as an installed PWA, and its compact {@code browser|os|deviceType}
 * device profile. version/pwa/clientProfile may be null for a client that predates that reporting
 * (an "unknown" bucket).
 *
 * @author Bruno Salmon
 */
public final class PushClientMetadata {

    private final Object userId;
    private final String clientVersion;
    private final Boolean pwa;
    private final String clientProfile;

    public PushClientMetadata(Object userId, String clientVersion, Boolean pwa, String clientProfile) {
        this.userId = userId;
        this.clientVersion = clientVersion;
        this.pwa = pwa;
        this.clientProfile = clientProfile;
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

    /** Compact {@code browser|os|deviceType} profile (e.g. {@code "Chrome|Windows|phone"}), or null if unknown. */
    public String getClientProfile() {
        return clientProfile;
    }
}
