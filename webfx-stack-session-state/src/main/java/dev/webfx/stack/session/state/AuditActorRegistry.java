package dev.webfx.stack.session.state;

import java.util.function.Function;

/**
 * Maps the authenticated principal to an id the database can record as the actor of a change.
 *
 * <p>The stack has no idea what a user IS — {@link ThreadLocalStateHolder#getUserId()} returns a
 * generic Object, and only the application that authenticated it can say which of its fields
 * identifies a person. So the application registers that knowledge here, once, and the layers
 * that need an actor id (the SQL submit provider, stamping it onto each write transaction for
 * audit triggers to store) read it without depending on authentication at all.
 *
 * <p>Registration belongs in {@code ServerAuthenticationGateway.boot()} — the gateway is what
 * creates the principal, so it is the one component that can interpret it.
 *
 * <p>Unregistered is a supported state, not a failure: the actor is simply not recorded, exactly
 * as before anything registered. The same is true of a resolver that throws or returns null for a
 * principal it does not recognise, such as a guest.
 *
 * @author Bruno Salmon
 */
public final class AuditActorRegistry {

    private static Function<Object, Object> resolver;

    private AuditActorRegistry() {}

    /**
     * Declares how to read an actor id from a principal. Last registration wins; several gateways
     * may register the same mapping without harm, since the mapping is about the principal TYPE
     * rather than about how the user logged in.
     */
    public static void registerResolver(Function<Object, Object> principalToActorId) {
        resolver = principalToActorId;
    }

    /**
     * The actor id for the principal on this thread, or null when there is nobody, nothing has
     * registered, or the principal is one the resolver does not recognise.
     *
     * <p>Never throws. This is called on the write path, and a change must not fail because the
     * audit could not name who made it.
     */
    public static Object currentActorId() {
        Function<Object, Object> r = resolver;
        if (r == null)
            return null;
        Object userId = ThreadLocalStateHolder.getUserId();
        if (userId == null)
            return null;
        try {
            return r.apply(userId);
        } catch (Throwable e) {
            return null;
        }
    }
}
