package dev.webfx.stack.session.token;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.session.state.RestrictedPrincipalRegistry;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;

import java.util.List;

/**
 * Issues session tokens, and exchanges one for its successor.
 *
 * <p>The two things this does are the same act at different moments. {@link #mintForLogin} runs where a
 * credential was actually checked; {@link #renew} carries that check forward without re-doing it. What
 * neither of them will do is manufacture a proof from a claim: renewal takes an {@link IdentityToken},
 * which only exists once a signature has held, and there is deliberately no entry point that takes a
 * user id. Renewing on the echo path would sign an assertion nobody established.
 *
 * @author Claude Code
 */
public final class SessionTokenService {

    private SessionTokenService() {}

    /** What a renewal decided, and the token to hand the client if there is one. */
    public record TokenRenewal(Outcome outcome, String token) {

        public enum Outcome {
            /** A new token was minted; give it to the client. */
            RENEWED,
            /**
             * Nothing could be decided, so nothing changes: the client keeps the token it has and the
             * exchange is tried again on its next message. Reached when the store cannot answer — a
             * database blip must not end a session.
             */
            KEEP,
            /** This session is over: revoked, past its bound, or a retired token turned up in it. */
            ENDED
        }

        static final TokenRenewal KEEP = new TokenRenewal(Outcome.KEEP, null);
        static final TokenRenewal ENDED = new TokenRenewal(Outcome.ENDED, null);

        static TokenRenewal renewed(String token) {
            return token == null ? KEEP : new TokenRenewal(Outcome.RENEWED, token);
        }
    }

    /**
     * Mints the first token of a new session, opening a family to rotate it in.
     *
     * <p>Fail-soft in two different ways, and the difference matters. A missing signing key means no
     * token at all, which is the pre-existing migration behaviour and is handled by the caller. A store
     * that cannot open a family means a token WITHOUT one: the login succeeds, the session slides and is
     * capped as normal, but it cannot be rotated and a stolen copy of it cannot be detected. Refusing the
     * login instead would turn a bookkeeping failure into an outage; running on silently would be worse
     * than either, so it is logged as the reduction in protection that it is.
     */
    public static Future<String> mintForLogin(Object principal, boolean backofficeSession) {
        if (principal == null || !SignedToken.isConfigured())
            return Future.succeededFuture(null);
        long now = System.currentTimeMillis();
        SessionTier tier = tierForNewSession(principal, backofficeSession);
        long absoluteExpiry = now + SessionLifetime.absoluteLifetimeMillis(tier);
        SessionFamilyStore store = SessionFamilyStoreRegistry.getStore();
        if (store == null)
            return Future.succeededFuture(mintQuietly(principal, null, 0, tier, absoluteExpiry, now));
        return store.open(principal, tier, absoluteExpiry)
            .map(familyId -> mintQuietly(principal, familyId, 0, tier, absoluteExpiry, now, store))
            .otherwise(e -> {
                Console.log("⚠️ Could not open a session family — this session cannot be rotated and a stolen"
                            + " copy of its token will not be detected: " + e);
                return mintQuietly(principal, null, 0, tier, absoluteExpiry, now);
            });
    }

    /**
     * Exchanges a token that is at or past the end of its access window for a fresh one.
     *
     * <p>Renewal is keyed on this being called, and it is called from the one place every client message
     * passes through — so "activity" means traffic the server observed, never anything the client asserts
     * about being active. The front office's media heartbeat is traffic like any other, which is what
     * lets a member listening to a recording at 2am keep their session alive without touching anything.
     *
     * @param tierHint the tier to adopt if the presented token is a legacy one that names none; ignored
     *                 otherwise, because a tier that arrived inside the signature is a fact and one
     *                 derived from live client state is a claim
     */
    public static Future<TokenRenewal> renew(IdentityToken presented, SessionTier tierHint, long nowMillis) {
        if (presented == null || !SignedToken.isConfigured())
            return Future.succeededFuture(TokenRenewal.KEEP);
        SessionTier tier = presented.isLegacy() ? tierHint : presented.tier();
        SessionFamilyStore store = SessionFamilyStoreRegistry.getStore();

        // No family yet: a token minted before families existed, or by a server that had no store at the
        // time. Upgrading it opens one and carries the SAME proof forward — the signature already held,
        // so nothing is being asserted here that was not established at login.
        if (presented.familyId() == null) {
            long absoluteExpiry = presented.isLegacy()
                                  ? nowMillis + SessionLifetime.absoluteLifetimeMillis(tier)
                                  : presented.absoluteExpiryMillis();
            if (nowMillis >= absoluteExpiry)
                return Future.succeededFuture(TokenRenewal.ENDED);
            if (store == null)
                return Future.succeededFuture(TokenRenewal.renewed(
                    mintQuietly(presented.principal(), null, 0, tier, absoluteExpiry, nowMillis)));
            return store.open(presented.principal(), tier, absoluteExpiry)
                .map(familyId -> TokenRenewal.renewed(
                    mintQuietly(presented.principal(), familyId, 0, tier, absoluteExpiry, nowMillis)))
                // Same reasoning as at login: a family that cannot be opened costs rotation, not the
                // session. Sliding it forward keeps the user working and the upgrade is retried later.
                .otherwise(e -> {
                    Console.log("⚠️ Could not open a session family while upgrading a token: " + e);
                    return TokenRenewal.renewed(
                        mintQuietly(presented.principal(), null, 0, tier, absoluteExpiry, nowMillis));
                });
        }

        if (store == null) {
            // The token names a family this server cannot look up. Only a misconfiguration reaches here —
            // a deployment that had a store and lost it — and the honest response is to keep the session
            // working while saying, loudly, that the generation is no longer being checked.
            Console.log("⚠️ A token names a session family but no store is registered — rotation and reuse"
                        + " detection are NOT in effect for it");
            return Future.succeededFuture(TokenRenewal.renewed(mintQuietly(presented.principal(),
                presented.familyId(), presented.generation(), tier, presented.absoluteExpiryMillis(), nowMillis)));
        }

        return store.renew(presented.familyId(), presented.generation(), nowMillis)
            .compose(renewal -> switch (renewal.verdict()) {
                case RENEWED, CURRENT -> Future.succeededFuture(TokenRenewal.renewed(
                    mintQuietly(presented.principal(), presented.familyId(), renewal.generation(), tier,
                        renewal.absoluteExpiryMillis(), nowMillis)));
                case REUSE_DETECTED -> {
                    // Two holders, and there is no way to tell from here which one is the member. Killing
                    // the family signs both out; leaving it alive keeps the thief in. Only one of those is
                    // a decision anybody would defend afterwards.
                    Console.log("🛡 A retired identity token was presented — ending the whole session family."
                                + " A copy of it is in someone else's hands.");
                    yield revokeAndRemember(store, presented.familyId(), "reuse-detected")
                        .otherwise(e -> {
                            Console.log("⚠️ Could not record the revocation of a reused session family: " + e);
                            return null;
                        })
                        .map(ignored -> TokenRenewal.ENDED);
                }
                case ENDED -> Future.succeededFuture(TokenRenewal.ENDED);
                case UNDECIDED -> Future.succeededFuture(TokenRenewal.KEEP);
            })
            // THE DATABASE NOT ANSWERING IS NOT A VERDICT. Ending the session here would make any blip on
            // this table a mass logout, which is the failure this whole policy exists to prevent.
            .otherwise(e -> {
                Console.log("⚠️ Could not read a session family, so its token stands unchanged: " + e);
                return TokenRenewal.KEEP;
            });
    }

    /**
     * Ends the session family behind the call being handled — what a logout has to do beyond forgetting.
     *
     * <p>A logout drops the token on the device that asked for it, and until now did nothing else. The family
     * stayed live, so a COPY of that token — the exact thing rotation exists to catch — kept working for the
     * rest of its access window and stayed renewable for the whole idle window: three hours for the back
     * office, ninety days for a member. Logging out is precisely the moment somebody says "end this", and
     * ending it only on their own device is the weakest possible reading of that.
     *
     * <p>Two consequences worth knowing rather than discovering:
     *
     * <ul>
     *   <li><b>It ends the session in the user's OTHER tabs too</b>, within their access window, because tabs
     *       of one browser share one stored token and therefore one family. That is what people mean by
     *       logging out, and it is not what happened before.</li>
     *   <li><b>It does not reach their other devices</b>, which logged in separately and hold families of
     *       their own. Signing out everywhere is a different act, and the {@code revoked} column is where it
     *       will live.</li>
     * </ul>
     *
     * <p>The family is read from the state of the call, where the syncer put it after verifying the token's
     * signature — never from anything the caller supplied. A caller who could name a family could end anyone
     * else's session by guessing an id, which would make this a denial-of-service primitive rather than a
     * logout.
     *
     * <p>Fail-soft: a logout must complete even if the row cannot be written. The alternative is a user who
     * asked to be signed out and got an error instead, still signed in, which is worse than a family that
     * outlives its usefulness and is swept an hour after it expires.
     */
    public static Future<Void> revokeCurrentSessionFamily() {
        String familyId = StateAccessor.getSessionFamilyId(ThreadLocalStateHolder.getThreadLocalState());
        SessionFamilyStore store = SessionFamilyStoreRegistry.getStore();
        // No family to end: a legacy token, a client that presented none, or a deployment with no store.
        // Nothing is wrong and nothing needs saying — this is most sessions on the day this ships.
        if (familyId == null || store == null)
            return Future.succeededFuture();
        // A store that THROWS rather than returning a failed future — a service not ready yet, an interceptor
        // refusing synchronously — must fail soft too. Caught here because otherwise() only sees a failed
        // future, and an exception escaping this call skips the rest of the logout: the device is never told,
        // and its session goes on naming the user.
        Future<Void> revocation;
        try {
            revocation = revokeAndRemember(store, familyId, "user");
        } catch (RuntimeException e) {
            revocation = Future.failedFuture(e);
        }
        return revocation
            .otherwise(e -> {
                Console.log("⚠️ Logged out, but could not revoke the session family — a copy of its token stays"
                            + " usable until its access window ends: " + e);
                return null;
            });
    }

    /**
     * Ends a family in the store AND tells this instance at once, so it refuses that family's tokens
     * without waiting to poll for its own write.
     *
     * <p>Noted only when the write succeeded: a revocation the store did not record is not a revocation,
     * and refusing the family here while the other instance keeps honouring it would be the worst of both
     * — a session that works or not depending on which task holds the socket.
     */
    private static Future<Void> revokeAndRemember(SessionFamilyStore store, String familyId, String reason) {
        return store.revoke(familyId, reason)
            .onSuccess(ignored -> RevokedFamilies.note(familyId, System.currentTimeMillis()));
    }

    /**
     * Recorded distinctly from a logout, which also writes "user".
     *
     * <p>Otherwise the one column that could answer "was I signed out, or did I sign myself out?" reads
     * the same either way — and that question is exactly what somebody asks when a device they were not
     * holding lands on a login screen.
     */
    private static final String SIGNED_OUT_ELSEWHERE_REASON = "signed-out-elsewhere";

    /**
     * Ends every session of the caller except the one they are using — "sign out my other devices".
     *
     * <p><b>It takes no target, and that is the security of it.</b> Both the person and the session to
     * spare are read from the state the syncer wrote after this call's token verified. A {@code personId}
     * parameter would turn "sign out my devices" into "sign out anybody's", which for sequential ids
     * means everybody's — an unauthenticated denial of service against any member whose id can be
     * guessed, and they can all be guessed.
     *
     * <p><b>It requires a verified token, not a claim.</b> The family id is only ever set from a token
     * whose signature held, so demanding one is what stops a caller asserting somebody else's identity
     * — which a client can still do while the token flip is off — and ending their sessions with it.
     * A legacy token carries no family and is refused here too; it gets one at its next renewal, which
     * is minutes away, and nothing is lost by waiting.
     *
     * <p>Answers with the families it ended, so the caller can tell those devices at once rather than
     * leaving them to notice on their next message.
     */
    public static Future<List<String>> revokeOtherSessionsOfCurrentUser() {
        Object state = ThreadLocalStateHolder.getThreadLocalState();
        String currentFamilyId = StateAccessor.getSessionFamilyId(state);
        Object principal = StateAccessor.getUserId(state);
        SessionFamilyStore store = SessionFamilyStoreRegistry.getStore();
        if (currentFamilyId == null || principal == null)
            return Future.failedFuture("Signing out other devices needs a session this server established itself");
        // A SUPPORT VIEW MUST NOT USE THIS. Its principal carries the viewed member's person id, so the
        // store would end that member's sessions — their phone, their laptop — while the agent's own
        // session, the one spared, is the agent's. A member signed out of everything by somebody else,
        // recorded as if they had done it themselves. The read-only restriction that exists for exactly
        // this principal cannot catch it either: these statements run as the server, which is what lets
        // a support view renew its own token at all.
        //
        // Fails closed on an unanswerable question, unlike the write gate: that one is global and must
        // not brick a deployment with no predicate registered, whereas this is one control that can
        // simply refuse.
        if (RestrictedPrincipalRegistry.isUserRestrictedOrUnknown(principal))
            return Future.failedFuture("A restricted session may not end the sessions of the person it is viewing");
        if (store == null) // nothing records sessions here, so there are no others to end
            return Future.succeededFuture(List.of());
        // Guarded for the reason revokeCurrentSessionFamily is: a store that throws rather than failing
        // its future — a service not ready, an interceptor refusing on the spot — must not escape as a
        // raw stack trace from a control a person just pressed.
        Future<List<String>> revocation;
        try {
            revocation = store.revokeOtherFamilies(principal, currentFamilyId, SIGNED_OUT_ELSEWHERE_REASON);
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
        return revocation
            .map(familyIds -> {
                long now = System.currentTimeMillis();
                // Refused on sight here from now on; the other instance learns at its next poll.
                for (String familyId : familyIds)
                    RevokedFamilies.note(familyId, now);
                if (!familyIds.isEmpty())
                    // Counts only. Who it was is in the rows themselves — revoked, with a reason and a
                    // time — and naming a person in a log puts personal data somewhere with weaker
                    // controls than the table it came from.
                    Console.log("🛡 Ended " + familyIds.size() + " other session(s) at their owner's request");
                return familyIds;
            });
    }

    /**
     * Which lifetime policy a session being established right now falls under.
     *
     * <p>Decided once, at login, and signed into the token from then on. The {@code backoffice} flag it
     * uses is client-asserted, which is exactly why it is read ONCE rather than on every message: a
     * client can choose which tier its own session lands in at login — and choosing the shorter one only
     * shortens its own session — but it cannot change that choice afterwards, and cannot lengthen a
     * back-office session by starting to claim it is a front-office one. Making the read unnecessary
     * altogether is what putting the audience in the payload does next.
     *
     * <p><b>The flag is a parameter and not read from {@link ThreadLocalStateHolder} here, and that is
     * not a style choice.</b> Every gateway calls this from inside a {@code compose()} — after a
     * database round trip — and the thread-local state is restored the moment the synchronous part of
     * the call returns. Reading it here would answer "not the back office" for every login in the
     * system, silently, and give every staff session the front office's 90-day idle window and
     * year-long cap. The gateways already capture {@code runId} synchronously for the same reason; this
     * rides with it.
     */
    private static SessionTier tierForNewSession(Object principal, boolean backofficeSession) {
        // Safe to ask late: it takes the principal explicitly rather than reading the thread.
        if (RestrictedPrincipalRegistry.isUserRestricted(principal))
            return SessionTier.SUPPORT_VIEW;
        return backofficeSession ? SessionTier.BACK_OFFICE : SessionTier.FRONT_OFFICE;
    }

    /**
     * Mints, and returns null rather than throwing if it cannot.
     *
     * <p>Both callers sit on paths where an exception would cost a user their session for a reason that
     * has nothing to do with them — an unsignable principal type at login, or the same at renewal. Logged
     * in full, because either one is a defect rather than a condition.
     */
    private static String mintQuietly(Object principal, String familyId, int generation, SessionTier tier,
                                      long absoluteExpiryMillis, long nowMillis) {
        return mintQuietly(principal, familyId, generation, tier, absoluteExpiryMillis, nowMillis, null);
    }

    /**
     * @param storeToTidy the store that just opened {@code familyId}, if this mint is the only thing
     *                    standing between that row and being useful. A family whose token never existed
     *                    is a row nobody will ever present, and it holds a person id and timestamps —
     *                    personal data — until its absolute expiry, which for a member is a year away.
     *                    Revoking it hands it to the retention sweep instead. Null where nothing was
     *                    opened, or where the family is one that already exists.
     */
    private static String mintQuietly(Object principal, String familyId, int generation, SessionTier tier,
                                      long absoluteExpiryMillis, long nowMillis, SessionFamilyStore storeToTidy) {
        try {
            return PrincipalToken.mint(principal, familyId, generation, tier, absoluteExpiryMillis, nowMillis);
        } catch (Exception e) {
            Console.log("⚠️ Could not mint an identity token for " + principal.getClass().getSimpleName() + ": " + e);
            if (storeToTidy != null && familyId != null)
                revokeAndRemember(storeToTidy, familyId, "never-issued")
                    .onFailure(err -> Console.log("⚠️ Could not tidy away an unused session family: " + err));
            return null;
        }
    }
}
