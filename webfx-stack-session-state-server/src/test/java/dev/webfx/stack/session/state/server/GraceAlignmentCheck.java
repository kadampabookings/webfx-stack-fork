package dev.webfx.stack.session.state.server;

import dev.webfx.stack.session.token.SessionLifetime;

/**
 * The two graces that excuse a holder on the token a renewal has just replaced must be the same number.
 *
 * <p>There are two because the server knows two different things at two different moments.
 * {@code PENDING_DELIVERY_GRACE_MILLIS} applies when it still remembers minting the successor;
 * {@link SessionLifetime#REUSE_GRACE_MILLIS} applies when it does not — a reconnect onto a different
 * server session, an eviction from the pending map, a message landing on another instance. The HOLDER
 * is in the same position either way, so the answer must be too.
 *
 * <p>They disagreed until 2026-10-11: thirty minutes against sixty seconds. On production that ended 42
 * session families in one day, 40 different people, 39 of them behind by exactly one generation and one
 * refused fifteen milliseconds past the minute. Every one was read as a stolen token. Whether somebody
 * keeps their session should not turn on whether a hash map survived.
 *
 * <p>So this is not a tidiness check. Lowering either number alone re-creates that incident, and the
 * next person to look at a sixty-second constant beside a thirty-minute one will reasonably think the
 * long one is the mistake.
 *
 * <p>No test framework, for the reason recorded in webfx-stack-authz-core: this repository declares no
 * JUnit. Run from main(); it exits non-zero while the issue stands.
 */
public class GraceAlignmentCheck {

    private static int pass, fail;

    private static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ✓ " + what); }
        else { fail++; System.out.println("  ✗ " + what); }
    }

    public static void main(String[] args) {
        System.out.println("the two graces for a holder on the replaced token:");
        check("are the same number",
              ServerSideStateSessionSyncer.PENDING_DELIVERY_GRACE_MILLIS == SessionLifetime.REUSE_GRACE_MILLIS);
        check("and that number is one access window",
              SessionLifetime.REUSE_GRACE_MILLIS == SessionLifetime.ACCESS_WINDOW_BASE_MILLIS);

        System.out.println("long enough to cover what it is for — a second tab, not a round trip:");
        check("more than a minute, which is what it used to be and was too short",
              SessionLifetime.REUSE_GRACE_MILLIS > 60_000L);

        System.out.println("and still bounded — a grace, not an amnesty:");
        check("never longer than the back office's idle window",
              SessionLifetime.REUSE_GRACE_MILLIS <= SessionLifetime.idleWindowMillis(SessionTierForCheck.backOffice()));

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    /** Keeps the tier enum's name out of the assertions above, which are about the graces. */
    private static final class SessionTierForCheck {
        static dev.webfx.stack.session.token.SessionTier backOffice() {
            return dev.webfx.stack.session.token.SessionTier.BACK_OFFICE;
        }
    }
}
