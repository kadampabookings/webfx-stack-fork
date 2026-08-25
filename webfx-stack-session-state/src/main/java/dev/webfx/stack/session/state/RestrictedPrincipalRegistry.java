package dev.webfx.stack.session.state;

import java.util.function.Predicate;

/**
 * Answers two questions about an authenticated principal that only the application can answer:
 * whether a registered user account is behind it, and whether that user is allowed to look but not
 * touch.
 *
 * <p>Sibling of {@link AuditActorRegistry}, and deliberately the same shape. The stack has no idea
 * what a user IS — {@link ThreadLocalStateHolder#getUserId()} returns a generic Object — so the
 * application registers the one thing it knows, and the layer that has to act on it (the SQL submit
 * provider, refusing writes) reads it without depending on authentication at all.
 *
 * <p>Why the check lives down here rather than in whichever feature created such a session: the
 * submit provider is the single point every write passes through, whatever produced it — generic
 * DML from the client, the document service, a credential change. A restriction enforced anywhere
 * higher would have to be repeated at each of those, and the one that got forgotten would be the
 * hole. Enforcing it once, at the bottom, means no path can miss it.
 *
 * <p>Unregistered is a supported state, not a failure: nothing is restricted, exactly as before
 * anything registered. The same goes for a predicate that throws — see {@link #isCurrentUserRestricted()}
 * for why that resolves the way it does.
 *
 * @author Claude Code
 */
public final class RestrictedPrincipalRegistry {

    private static Predicate<Object> registeredUserPredicate;
    private static Predicate<Object> restrictedUserPredicate;

    private RestrictedPrincipalRegistry() {}

    /**
     * Declares how to recognise a principal that belongs to a registered user account, as opposed to
     * a guest, an anonymous caller or a principal type this application does not issue.
     *
     * <p>Exists so that callers can stop writing {@code instanceof} against an application principal
     * class. The REST image endpoints are the first: they have to know whether there is an account
     * behind an upload, and asking here is what keeps that decision out of the application's authn
     * module. Same registration rule as {@link #registerRestrictedUserPredicate} — last one wins, and
     * several gateways may register the same rule without harm, since it is about the principal TYPE
     * rather than about how the user signed in.
     */
    public static void setRegisteredUserPredicate(Predicate<Object> registeredUserPredicate) {
        RestrictedPrincipalRegistry.registeredUserPredicate = registeredUserPredicate;
    }

    /**
     * Declares how to recognise a read-only principal. Last registration wins; several gateways may
     * register the same rule without harm, since it is about the principal TYPE rather than about
     * how the user signed in.
     */
    public static void registerRestrictedUserPredicate(Predicate<Object> restrictedUserPredicate) {
        RestrictedPrincipalRegistry.restrictedUserPredicate = restrictedUserPredicate;
    }

    /**
     * Whether a registered user account is behind the principal on this thread.
     *
     * <p>Only safe where the thread-local still holds the principal, i.e. in the synchronous part of
     * a call. Past the first async hop, use {@link #isUserRegistered(Object)} with a principal
     * captured earlier — see {@link #isUserRestricted(Object)} for why.
     */
    public static boolean isCurrentUserRegistered() {
        return isUserRegistered(ThreadLocalStateHolder.getUserId());
    }

    /**
     * Whether a registered user account is behind this specific principal.
     *
     * <p>Nothing registered, or nobody on the request, answers "no", so a caller that must be a
     * registered user is refused rather than admitted. That is the conservative direction for this
     * question, but note it is the same raw value that answers "not restricted" for
     * {@link #isUserRestricted(Object)}, where the conservative direction is the opposite one. The
     * two questions share {@code testUserPredicate} and therefore share its defaults; they do not
     * share what a default costs. See that method for the case where this matters.
     *
     * @param userId the principal to judge, typically captured at request-creation time
     */
    public static boolean isUserRegistered(Object userId) {
        return testUserPredicate(registeredUserPredicate, userId);
    }

    /**
     * Whether the principal on this thread is restricted to reads.
     *
     * <p>A predicate that throws is treated as "restricted". That is the opposite of
     * {@link AuditActorRegistry#currentActorId()}, which swallows failures and returns null, and the
     * difference is deliberate: failing to name who made a change must not block the change, but
     * failing to establish that a session is allowed to write must not permit the write. When this
     * question cannot be answered, the safe answer is no.
     */
    public static boolean isCurrentUserRestricted() {
        return isUserRestricted(ThreadLocalStateHolder.getUserId());
    }

    /**
     * Whether this specific principal is restricted to reads.
     *
     * <p>Takes the principal explicitly, for callers that captured it earlier and cannot rely on the
     * thread-local still holding it. That is the normal situation once a flow has been through an
     * async database round trip: {@link ThreadLocalStateHolder} is restored when the synchronous
     * portion of the call returns, so anything continuing in a {@code .compose()} sees no principal
     * at all. A check that read the thread-local there would quietly answer "not restricted" for
     * every such flow, which is the most dangerous way for this to be wrong.
     *
     * @param userId the principal to judge, typically captured at request-creation time
     */
    public static boolean isUserRestricted(Object userId) {
        return testUserPredicate(restrictedUserPredicate, userId);
    }

    // ---------------------------------------------------------------------------------------------
    // Superseded spellings, kept only so that work already written against them still compiles.
    //
    // The accessors above gained a User infix when this class took on a second question, which broke
    // every existing call site at once. One of those call sites is in the image endpoints, a security
    // fix that is awaiting release while the endpoints it closes are still open; making that branch
    // rebase or take a fixup commit to ship would be the wrong way round. These forwarders let it
    // ship untouched.
    //
    // Delete the whole block once it has merged. There is no behaviour here, only the old names.
    // ---------------------------------------------------------------------------------------------

    /** @deprecated Renamed to {@link #registerRestrictedUserPredicate(Predicate)}. */
    @Deprecated
    public static void registerPredicate(Predicate<Object> restrictedPrincipalPredicate) {
        registerRestrictedUserPredicate(restrictedPrincipalPredicate);
    }

    /** @deprecated Renamed to {@link #isCurrentUserRestricted()}. */
    @Deprecated
    public static boolean isCurrentRestricted() {
        return isCurrentUserRestricted();
    }

    /** @deprecated Renamed to {@link #isUserRestricted(Object)}. */
    @Deprecated
    public static boolean isRestricted(Object userId) {
        return isUserRestricted(userId);
    }

    /**
     * Shared evaluation for both questions: absent predicate or absent principal answers false, and a
     * predicate that throws answers true.
     *
     * <p><b>Both defaults were chosen for the restricted question and are safe only there.</b> For
     * "is this user restricted", false-when-unknown preserves the behaviour that existed before
     * anything registered, and true-when-throwing refuses the write. For "is this user registered"
     * the throwing case inverts: it reports an unrecognised principal as a registered user, and a
     * caller gated on {@code isUserRegistered} would be admitted rather than refused.
     *
     * <p>No predicate registered today can throw — both are {@code instanceof} tests — so this is a
     * trap for the next one rather than a live defect. It wants a per-question default (the value to
     * answer when the predicate throws, passed in by the caller) before a predicate that can fail is
     * ever registered.
     */
    private static boolean testUserPredicate(Predicate<Object> predicate, Object userId) {
        if (predicate == null || userId == null)
            return false;
        try {
            return predicate.test(userId);
        } catch (Throwable e) {
            return true;
        }
    }
}
