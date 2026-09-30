package dev.webfx.stack.db.query;

import dev.webfx.platform.util.Arrays;

/**
 * Resolves the terms only the server can value — {@code CALLER_PERSON} and its kin — and attaches their
 * values to a client's query before it runs.
 *
 * <p><b>Called from the ENDPOINT, synchronously.</b> The principal lives on the thread only until the first
 * async hop, and the query interceptor can go async before it binds anything (it waits for the local data
 * source on a cold server), so resolving there would read null and fail open. The endpoint is where the
 * caller is still known, and it is also where a client's query is already being checked.
 *
 * <p><b>The values are appended to the PARAMETERS, not carried beside them.</b> A push subscription is keyed
 * by its {@link QueryArgument}, whose equality compares parameter values and would ignore a field held to one
 * side — so two callers running the same statement would compare equal, share one subscription, and one would
 * be served rows fetched for the other. Putting the value where equality can see it makes them separate
 * streams by construction rather than by remembering to.
 *
 * <p>They go at the END, named, which is the shape {@code reorderNamedParameters} binds: the caller's own
 * positional block first, then names above it.
 *
 * @author Claude Code
 */
public final class ServerCallerParameters {

    /**
     * Answers who is asking. Implemented where the principal means something — the framework has no notion of
     * a person or an account — and asked only on the endpoint thread.
     */
    public interface Resolver {
        /**
         * The value for one reserved name, or null when there is no caller. Null is a legitimate answer: an
         * anonymous booker has no person, and a predicate comparing against null matches nothing, which is
         * the safe direction.
         */
        Object resolveCallerValue(String reservedName);
    }

    private static Resolver resolver;

    public static void registerResolver(Resolver resolver) {
        ServerCallerParameters.resolver = resolver;
    }

    /**
     * The names this attaches. Kept in step with the terms registered in {@code Function}; a term whose name
     * is missing here compiles to a parameter nothing supplies, and the binding seam then refuses the query
     * by name rather than letting it reach the driver.
     */
    private static final String[] RESERVED_NAMES = {"caller.person", "caller.account"};

    /**
     * True when the statement could possibly use one of these terms.
     *
     * <p>A TEXT test, deliberately, and it is the reason this is affordable at all. Attaching unconditionally
     * would make every argument caller-specific, and since a push subscription is keyed by the argument, every
     * viewer of a shared query would get a stream of their own — deduplication across clients is the thing
     * that makes push affordable, and it would collapse.
     *
     * <p>Sound in the only direction that matters: a term has to appear literally in the statement to be
     * parsed as one, so this cannot miss a statement that uses one. It CAN fire on a statement that merely
     * mentions the text inside a literal, and that costs a value nobody binds, which the binding seam drops.
     */
    private static boolean mayUseCallerTerm(String statement) {
        return statement != null && statement.contains("CALLER_");
    }

    /**
     * The argument to run: the original when no caller term can be present, otherwise a copy with the
     * resolved values appended and named.
     */
    public static QueryArgument attach(QueryArgument argument) {
        if (resolver == null || argument == null || !mayUseCallerTerm(argument.getStatement()))
            return argument;
        Object[] parameters = argument.getParameters();
        String[] names = argument.getParameterNames();
        int suppliedCount = Arrays.length(parameters);
        Object[] newParameters = new Object[suppliedCount + RESERVED_NAMES.length];
        String[] newNames = new String[Arrays.length(names) + RESERVED_NAMES.length];
        for (int i = 0; i < suppliedCount; i++)
            newParameters[i] = parameters[i];
        for (int i = 0; i < Arrays.length(names); i++)
            newNames[i] = names[i];
        for (int i = 0; i < RESERVED_NAMES.length; i++) {
            newParameters[suppliedCount + i] = resolver.resolveCallerValue(RESERVED_NAMES[i]);
            newNames[Arrays.length(names) + i] = RESERVED_NAMES[i];
        }
        return QueryArgument.builder().copy(argument).setParameters(newParameters).setParameterNames(newNames).build();
    }
}
