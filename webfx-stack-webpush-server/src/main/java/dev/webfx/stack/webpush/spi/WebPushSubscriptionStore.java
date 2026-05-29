package dev.webfx.stack.webpush.spi;

import dev.webfx.platform.async.Future;

import java.time.Instant;

/**
 * Storage SPI for the rotate-subscription operation. Implementations look up a
 * subscription row by its old endpoint and update it with the new endpoint,
 * encryption keys, user agent, and last-seen timestamp.
 * <p>
 * This SPI exists so the {@code webfx-stack-webpush-server} module stays free
 * of any domain-model dependency. Each host application provides its own
 * implementation that uses its ORM / EntityStore / direct SQL to perform the
 * update on whatever entity it uses to hold push subscriptions (KBS uses
 * Modality's {@code PushSubscription} entity).
 * <p>
 * The implementation is discovered via {@link java.util.ServiceLoader}.
 *
 * @author Bruno Salmon
 */
public interface WebPushSubscriptionStore {

    /**
     * Atomically rotate a subscription identified by its old endpoint.
     * <p>
     * Implementations should:
     * <ol>
     *   <li>Find the subscription row whose endpoint equals {@code oldEndpoint}.</li>
     *   <li>If found: update {@code endpoint}, {@code p256dhKey}, {@code authKey},
     *       {@code userAgent}, and {@code lastSeenAt} in one transaction, then
     *       complete the returned Future with {@code true}.</li>
     *   <li>If not found: complete the Future with {@code false}.</li>
     *   <li>On any database error: fail the Future.</li>
     * </ol>
     *
     * @return {@code true} if a row was updated, {@code false} if no row matched.
     */
    Future<Boolean> rotate(
            String oldEndpoint,
            String newEndpoint,
            String newP256dhKey,
            String newAuthKey,
            String userAgent,
            Instant lastSeenAt
    );
}
