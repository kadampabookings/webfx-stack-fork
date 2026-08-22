package dev.webfx.stack.session.state;

import java.util.function.Predicate;

/**
 * Declares which authenticated principals are allowed to look but not touch.
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
 * anything registered. The same goes for a predicate that throws — see {@link #isCurrentRestricted()}
 * for why that resolves the way it does.
 *
 * @author Claude Code
 */
public final class RestrictedPrincipalRegistry {

    private static Predicate<Object> restrictedPrincipalPredicate;

    private RestrictedPrincipalRegistry() {}

    /**
     * Declares how to recognise a read-only principal. Last registration wins; several gateways may
     * register the same rule without harm, since it is about the principal TYPE rather than about
     * how the user signed in.
     */
    public static void registerPredicate(Predicate<Object> restrictedPrincipalPredicate) {
        RestrictedPrincipalRegistry.restrictedPrincipalPredicate = restrictedPrincipalPredicate;
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
    public static boolean isCurrentRestricted() {
        return isRestricted(ThreadLocalStateHolder.getUserId());
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
    public static boolean isRestricted(Object userId) {
        Predicate<Object> predicate = restrictedPrincipalPredicate;
        if (predicate == null || userId == null)
            return false;
        try {
            return predicate.test(userId);
        } catch (Throwable e) {
            return true;
        }
    }
}
