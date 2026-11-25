package dev.webfx.stack.push.server.spi.impl.simple;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.com.bus.Bus;
import dev.webfx.stack.com.bus.DeliveryOptions;
import dev.webfx.stack.push.server.UnresponsivePushClientListener;
import dev.webfx.stack.push.server.spi.PushServerServiceProvider;

/**
 * Whether pushing to a run id reaches the connection that claimed it.
 *
 * <p>Companion to {@link FamilyClaimCheck}, and the same fact read the other way round. That one settles who
 * may SAY which family a client belongs to; this one answers whether a given connection IS that client, for
 * callers that must not push to a run id somebody else has named.
 *
 * <p><b>Why anything needs to ask.</b> A push normally follows its cause by milliseconds, and over that
 * distance a self-chosen run id is no great risk. The browser-approved device login breaks the assumption:
 * it mints a session for a run id named five minutes earlier, while a person reads a code and decides. Over
 * five minutes, "the client said this run id" stops being good enough — the id is {@code Math.random}, it
 * travels in ordinary messages, and nothing authenticates it.
 *
 * <p>Every case below is written as the failure it prevents, and the last two are the ones that would be
 * invisible: a provider that does not track ownership must answer NO rather than nothing, and ownership
 * must not drift to whoever asked most recently.
 *
 * <p>No test framework, for the reason recorded in webfx-stack-authz-core. Run from main(); it exits
 * non-zero while the issue stands.
 *
 * @author Claude Code
 */
public class ClientOwnershipCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); } else { fail++; System.out.println("  FAIL " + what); }
    }

    public static void main(String[] args) {
        SimplePushServerServiceProvider provider = new SimplePushServerServiceProvider();

        System.out.println("a run id nobody has registered belongs to nobody:");
        check("an unknown run id is owned by no session",
              !provider.isClientOwnedBySession("run-unknown", "session-a"));

        System.out.println("registered but not yet owned is still NO:");
        // Real and brief: a client that has connected but not completed a live tick. Fail closed - a caller
        // asking this question needs a fact, and "not yet" is not one.
        provider.getOrCreatePushClientInfo("run-1");
        check("an entry with no owner recorded answers no",
              !provider.isClientOwnedBySession("run-1", "session-a"));

        System.out.println("a client that has only ever SPOKEN can be owned - no push required:");
        // The refusal this prevents, met on staging. push() used to be the only path that created an entry,
        // so a connection nobody had pushed to had none - and with no entry a claim has nothing to attach
        // to, so isClientOwnedBySession answered no for a good client. A fresh CLI asking to be approved on
        // its first message raced the server's first push to it and was refused "try again in a moment",
        // which could never help: trying again does not make a push happen. Liveness creates the entry now.
        provider.clientIsLive("run-live-only");
        provider.setClientSessionFamily("run-live-only", "family-live", "session-live");
        check("a live tick alone is enough to be owned",
              provider.isClientOwnedBySession("run-live-only", "session-live"));
        check("and it is owned by THAT session, not by any other",
              !provider.isClientOwnedBySession("run-live-only", "session-other"));
        // The order the interceptor actually uses is clientIsLive then setClientSessionFamily, so the claim
        // lands on the first message. Nothing here may be read as "any runId in any message is owned".
        check("a runId that has never been live is still owned by nobody",
              !provider.isClientOwnedBySession("run-never-seen", "session-live"));

        System.out.println("once claimed, its owner is recognised and nobody else is:");
        provider.setClientSessionFamily("run-1", "family-1", "session-a");
        check("the claiming session owns it", provider.isClientOwnedBySession("run-1", "session-a"));
        check("ANOTHER session does not — this is the whole point",
              !provider.isClientOwnedBySession("run-1", "session-b"));

        System.out.println("ownership does not drift to whoever asked last:");
        // The attack shape: an attacker's own genuine session naming somebody else's run id. The family
        // claim is refused, and ownership must be unmoved afterwards - not merely refused at the time.
        provider.setClientSessionFamily("run-1", "family-attacker", "session-b");
        check("after a refused claim, the original session still owns it",
              provider.isClientOwnedBySession("run-1", "session-a"));
        check("and the claimant still does not",
              !provider.isClientOwnedBySession("run-1", "session-b"));

        System.out.println("an absent argument proves nothing:");
        check("a null session is not an owner", !provider.isClientOwnedBySession("run-1", null));
        check("a null run id has no owner", !provider.isClientOwnedBySession(null, "session-a"));

        System.out.println("a provider that does not track ownership refuses rather than abstains:");
        // Every other provider inherits the interface default. If it answered true, or threw, a caller
        // gated on this would be gated on nothing at all.
        PushServerServiceProvider bare = new PushServerServiceProvider() {
            @Override
            public <T> Future<T> push(String clientServiceAddress, Object javaArgument, DeliveryOptions options,
                                      Bus bus, Object clientRunId) {
                return Future.failedFuture("not implemented");
            }
            @Override public void clientIsLive(Object clientRunId) {}
            @Override public void addUnresponsivePushClientListener(UnresponsivePushClientListener listener) {}
            @Override public void removeUnresponsivePushClientListener(UnresponsivePushClientListener listener) {}
        };
        check("the SPI default is no, for any run id and any session",
              !bare.isClientOwnedBySession("run-1", "session-a") && !bare.isClientOwnedBySession(null, null));

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        // Explicit, like every sibling check, and now load-bearing: clientIsLive() schedules this client's
        // first ping, which boots the platform scheduler and its non-daemon threads. Returning from main()
        // left the JVM alive with every assertion already passed - a green run that never ends.
        System.exit(fail == 0 ? 0 : 1);
    }
}
