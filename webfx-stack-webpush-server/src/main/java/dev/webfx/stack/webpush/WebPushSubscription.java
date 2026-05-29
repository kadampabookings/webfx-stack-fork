package dev.webfx.stack.webpush;

/**
 * A push subscription as received from a browser via {@code pushManager.subscribe()}.
 * Carries the addressing ({@code endpoint}) and the encryption keys ({@code p256dhKey},
 * {@code authKey}) the server needs to send an encrypted push message to this
 * specific device.
 * <p>
 * Keys are base64url-encoded (the Web Push convention), exactly as the browser
 * surfaces them via {@code PushSubscription.toJSON()}. Callers should pass them
 * through verbatim — no decoding needed before constructing this object.
 *
 * @param endpoint   the push service URL the message is POSTed to (FCM / Mozilla / APNs)
 * @param p256dhKey  the user agent's public ECDH key (base64url, ~88 chars)
 * @param authKey    the user agent's authentication secret (base64url, ~24 chars)
 * @author Bruno Salmon
 */
public record WebPushSubscription(
        String endpoint,
        String p256dhKey,
        String authKey
) {
}
