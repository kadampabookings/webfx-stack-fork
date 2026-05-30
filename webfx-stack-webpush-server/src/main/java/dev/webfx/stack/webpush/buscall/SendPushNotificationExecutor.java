package dev.webfx.stack.webpush.buscall;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.service.SingleServiceProvider;
import dev.webfx.stack.authn.AuthenticationService;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import dev.webfx.stack.webpush.WebPushPayload;
import dev.webfx.stack.webpush.WebPushResult;
import dev.webfx.stack.webpush.WebPushServerService;
import dev.webfx.stack.webpush.WebPushSubscription;
import dev.webfx.stack.webpush.spi.WebPushSubscriptionStore;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Server-side executor for the {@code SendPushNotification} BO operation.
 *
 * <h2>Flow</h2>
 * <ol>
 *   <li>Reject if the caller is not authenticated.</li>
 *   <li>Look up the caller's email — needed for {@code testSendToSelf} and
 *       useful for the log line either way.</li>
 *   <li>Delegate to {@link WebPushSubscriptionStore#findRecipients} with the
 *       opaque target object and the email filter; the store impl translates
 *       the target into a DB query against its own entity model.</li>
 *   <li>Loop, calling {@link WebPushServerService#send} for each subscription,
 *       and aggregate the result variants into the response.</li>
 * </ol>
 *
 * <h2>Authorization</h2>
 * <p>V1 only requires authentication; the per-role gate is enforced on the
 * client by hiding the BO page behind {@code hasPermission("SendPushNotification")}.
 * A proper server-side role check is the V2 follow-up — needs the host's
 * authorization layer to define the corresponding Operation.
 *
 * @author Bruno Salmon
 */
final class SendPushNotificationExecutor {

    private SendPushNotificationExecutor() {}

    static Future<SendPushNotificationResult> execute(SendPushNotificationArgument arg) {
        if (ThreadLocalStateHolder.getUserId() == null) {
            return Future.failedFuture("Not authenticated");
        }
        return AuthenticationService.getUserClaims()
                .compose(claims -> runForCaller(arg, claims == null ? null : claims.email()));
    }

    private static Future<SendPushNotificationResult> runForCaller(
            SendPushNotificationArgument arg, String callerEmail) {
        String emailFilter = null;
        if (arg.testSendToSelf()) {
            if (callerEmail == null || callerEmail.isBlank()) {
                // Test-send requires the caller's email — without it we'd
                // accidentally broadcast. Fail loudly rather than silently
                // sending nothing.
                return Future.failedFuture("testSendToSelf=true but caller has no email");
            }
            emailFilter = callerEmail;
        }
        WebPushSubscriptionStore store = getStore();
        if (store == null) {
            return Future.failedFuture("No WebPushSubscriptionStore registered");
        }
        final String finalEmailFilter = emailFilter;
        return store.findRecipients(arg.target(), emailFilter)
                .compose(subs -> sendAll(subs, arg, callerEmail, finalEmailFilter));
    }

    private static Future<SendPushNotificationResult> sendAll(
            List<WebPushSubscription> subs,
            SendPushNotificationArgument arg,
            String callerEmail,
            String emailFilter) {
        int targeted = subs.size();
        if (targeted == 0) {
            Console.log("[SendPushNotification] caller=" + callerEmail
                    + " target=" + arg.target()
                    + " test=" + (emailFilter != null) + " — no recipients");
            return Future.succeededFuture(new SendPushNotificationResult(0, 0, 0, 0));
        }

        WebPushPayload payload = new WebPushPayload(
                arg.title(), arg.body(), arg.url(),
                // tag = stable per-target so multiple sends to the same audience
                // collapse on the device rather than stacking.
                "push-" + System.identityHashCode(arg.target()),
                /*ttlSeconds=*/86400,
                WebPushPayload.Urgency.NORMAL);

        List<Future<WebPushResult>> sends = new ArrayList<>(targeted);
        for (WebPushSubscription sub : subs) {
            sends.add(WebPushServerService.send(sub, payload));
        }

        return Future.all(sends).map(cf -> {
            int succeeded = 0, expired = 0, failed = 0;
            for (int i = 0; i < sends.size(); i++) {
                WebPushResult r = cf.resultAt(i);
                if (r instanceof WebPushResult.Success)              succeeded++;
                else if (r instanceof WebPushResult.SubscriptionExpired) expired++;
                else                                                  failed++;
            }
            SendPushNotificationResult result = new SendPushNotificationResult(
                    targeted, succeeded, expired, failed);
            Console.log("[SendPushNotification] caller=" + callerEmail
                    + " target=" + arg.target()
                    + " test=" + (emailFilter != null)
                    + " → " + result);
            return result;
        });
    }

    private static WebPushSubscriptionStore getStore() {
        // SPI class extracted to a local — referencing it twice inline causes
        // webfx-cli to emit duplicate `uses` directives in module-info.java.
        Class<WebPushSubscriptionStore> spi = WebPushSubscriptionStore.class;
        return SingleServiceProvider.getProvider(spi, () -> ServiceLoader.load(spi),
                SingleServiceProvider.NotFoundPolicy.RETURN_NULL);
    }
}
