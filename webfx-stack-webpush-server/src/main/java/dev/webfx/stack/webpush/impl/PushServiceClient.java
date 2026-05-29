package dev.webfx.stack.webpush.impl;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.fetch.Fetch;
import dev.webfx.platform.fetch.FetchOptions;
import dev.webfx.platform.fetch.Headers;
import dev.webfx.platform.fetch.Response;
import dev.webfx.stack.webpush.WebPushPayload;
import dev.webfx.stack.webpush.WebPushResult;

/**
 * POSTs an encrypted push payload to a push service endpoint and maps the
 * HTTP response into a {@link WebPushResult}.
 * <p>
 * Uses the webfx Fetch API so the underlying HTTP transport is the same one
 * the rest of the application uses (Vert.x WebClient on the server). Doesn't
 * deal with encryption, signing, or business logic — purely "build the right
 * headers, POST the bytes, classify the response".
 *
 * @author Bruno Salmon
 */
public final class PushServiceClient {

    private final VapidSigner signer;

    public PushServiceClient(VapidSigner signer) {
        this.signer = signer;
    }

    /**
     * Sends the given encrypted body to {@code endpointUrl} with all the
     * VAPID + Web Push protocol headers correctly set.
     */
    public Future<WebPushResult> post(String endpointUrl, byte[] encryptedBody, WebPushPayload payload) {
        Headers headers = Headers.create();

        // RFC 8292 — VAPID authentication. `t=` is the JWT, `k=` is the
        // server's public key (so the push service can verify the signature
        // without needing prior registration).
        String jwt = signer.signJwtFor(endpointUrl);
        headers.set("Authorization", "vapid t=" + jwt + ", k=" + signer.publicKeyBase64Url());

        // RFC 8291 — aes128gcm is the modern content encoding. The body
        // includes the salt + ephemeral public key in its header, so no
        // separate Encryption / Crypto-Key headers are needed (unlike the
        // older aesgcm encoding).
        headers.set("Content-Encoding", "aes128gcm");
        headers.set("Content-Type", "application/octet-stream");

        // RFC 8030 — TTL is mandatory. Tells the push service how long to
        // hold the message if the device is offline. Past the TTL, the
        // message is silently discarded.
        headers.set("TTL", String.valueOf(payload.ttlSeconds()));

        // Urgency is optional but helps push services prioritise delivery,
        // particularly battery-saving modes on mobile.
        headers.set("Urgency", payload.urgency().wireValue());

        // Topic is optional — same-topic messages can be coalesced by the
        // push service. We piggyback on the payload's tag for this since
        // it serves the same collapsing purpose on the receiving end.
        if (payload.tag() != null && !payload.tag().isEmpty()) {
            headers.set("Topic", payload.tag());
        }

        FetchOptions options = new FetchOptions()
                .setMethod("POST")
                .setHeaders(headers)
                .setBody(encryptedBody);

        return Fetch.fetch(endpointUrl, options)
                .map(PushServiceClient::interpret)
                .otherwise(err -> new WebPushResult.Failed(0, "Network error: " + err.getMessage()));
    }

    /**
     * Classifies the push service's HTTP response into the three semantic
     * outcomes callers care about. 410 Gone and 404 Not Found are the
     * standard signals a push service uses to say "this subscription is
     * permanently dead, stop sending to it".
     */
    private static WebPushResult interpret(Response response) {
        int status = response.status();
        if (status >= 200 && status < 300) {
            return new WebPushResult.Success(status);
        }
        if (status == 410 || status == 404) {
            return new WebPushResult.SubscriptionExpired(status);
        }
        return new WebPushResult.Failed(status, response.statusText());
    }
}
