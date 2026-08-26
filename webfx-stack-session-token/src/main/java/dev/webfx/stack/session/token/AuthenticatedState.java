package dev.webfx.stack.session.token;

import dev.webfx.platform.console.Console;
import dev.webfx.stack.session.state.StateAccessor;

/**
 * Builds the state that tells a client who it now is, with the proof attached.
 *
 * <p>Every login in this system ends the same way: the server works out the principal and pushes it to
 * the client as state. Password login does it after checking the password, magic link after redeeming
 * the token. Those are the moments a credential was actually verified, so those are the moments worth
 * signing — which is why this replaces the state construction at exactly those points and nowhere else.
 *
 * <p><b>It does not mint from session state, and that is the whole discipline.</b> Stamping a token
 * wherever a session happens to hold a userId would need no gateway changes and would cover every login
 * for free. It would also sign an assertion nobody established: the session's userId is whatever the
 * client claimed and the existing weak check waved through, so an attacker would receive a properly
 * signed token for an identity they invented. A token is worth exactly what the check behind it was
 * worth.
 *
 * @author Bruno Salmon
 */
public final class AuthenticatedState {

    /**
     * How long a minted token stays valid.
     *
     * <p>A single absolute lifetime for now, because nothing verifies tokens yet and the sliding renewal
     * designed alongside it is not built. When verification lands this becomes the outer bound rather
     * than the whole policy: renewed on server-observed activity, never extendable past this.
     */
    private static final long DEFAULT_TOKEN_LIFETIME_MILLIS = 12 * 60 * 60 * 1000L; // 12 hours

    private static boolean missingKeyAlreadyReported;

    private AuthenticatedState() {}

    /** @param principal the identity a credential check just established — never one merely claimed */
    public static Object createFor(Object principal) {
        Object state = StateAccessor.createUserIdState(principal);
        String token = mintQuietly(principal);
        return token == null ? state : StateAccessor.setUserToken(state, token);
    }

    /**
     * Mints if it can, and returns null if it cannot.
     *
     * <p>Fail-soft deliberately, and only for as long as the migration lasts. Minting throws when no
     * signing key is configured, and this sits on the login path — so a strict version would mean that
     * deploying this change to any server whose key had not been installed yet stopped every user
     * logging in. Nothing verifies tokens today, so a missing token costs nothing today.
     *
     * <p>That reverses when clients start requiring one. At the flip, a server that cannot mint cannot
     * authenticate anyone, and it should refuse to start rather than accept logins it cannot prove —
     * silently issuing identities with no proof is the failure this whole exercise exists to remove.
     */
    private static String mintQuietly(Object principal) {
        if (!SignedToken.isConfigured()) {
            if (!missingKeyAlreadyReported) { // once per server, not once per login
                missingKeyAlreadyReported = true;
                Console.log("⚠️ Logging in without an identity token: no signing key configured."
                            + " Harmless until clients require one, and a startup failure after that.");
            }
            return null;
        }
        try {
            return PrincipalToken.mint(principal, System.currentTimeMillis() + DEFAULT_TOKEN_LIFETIME_MILLIS);
        } catch (Exception e) {
            // A principal that cannot be signed must not stop someone logging in while tokens are
            // optional. Logged in full, because after the flip this becomes a login that cannot happen.
            Console.log("⚠️ Could not mint an identity token for " + principal.getClass().getSimpleName() + ": " + e);
            return null;
        }
    }
}
