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
    private static final String[] RESERVED_NAMES = {"caller.person", "caller.account",
        "caller.grantedEvents", "caller.grantedOrganizations"};

    /**
     * The prefix every server-supplied name carries — the same as {@code ServerParameterFunction.PARAMETER_NAME_PREFIX},
     * which this module cannot see. Matched as a prefix rather than against {@link #RESERVED_NAMES}, so a term
     * registered later is covered before anyone remembers to list it.
     */
    private static final String RESERVED_PREFIX = "caller.";

    /** Whether any of these names is one only the server may supply (a client sending one is refused). */
    public static boolean suppliesServerName(String[] parameterNames) {
        if (parameterNames != null)
            for (String name : parameterNames)
                if (name != null && name.startsWith(RESERVED_PREFIX))
                    return true;
        return false;
    }

    /**
     * True when the statement could possibly use one of these terms.
     *
     * <p>A TEXT test, deliberately, and it is the reason this is affordable at all. Attaching unconditionally
     * would make every argument caller-specific, and since a push subscription is keyed by the argument, every
     * viewer of a shared query would get a stream of their own — deduplication across clients is the thing
     * that makes push affordable, and it would collapse.
     *
     * <p>Sound for a term the CLIENT wrote: one has to appear literally in the statement to be parsed as one.
     * It CAN fire on a statement that merely mentions the text inside a literal, and that costs a value nobody
     * binds, which the binding seam drops.
     *
     * <p><b>It is NOT sound for a term the SERVER injects.</b> A data scope condition is added by the compiler
     * and appears nowhere in this text, so a statement reading a scoped entity needs {@code CALLER_PERSON}
     * while saying nothing of the kind — the compiler emits the parameter slot and nothing fills it. That is
     * what {@link #attach(QueryArgument, Object)} asks the inspector about; this text test remains the answer
     * for an unscoped caller, where it is exactly right and costs nothing.
     */
    private static boolean mayUseCallerTerm(String statement) {
        return statement != null && statement.contains("CALLER_");
    }

    /**
     * The argument to run: the original when no caller term can be present, otherwise a copy with the
     * resolved values appended and named.
     */
    public static QueryArgument attach(QueryArgument argument) {
        return attach(argument, null);
    }

    /**
     * The argument to run, with the names this statement will need resolved and appended.
     *
     * <p><b>Which names, and why it cannot simply be all of them.</b> Appending unconditionally would make
     * every argument caller-specific, and a push subscription is keyed by its argument — so every viewer of a
     * shared query would get a stream of their own and the deduplication that makes push affordable would
     * collapse. That is the constraint {@link #mayUseCallerTerm} was built around, and it still holds.
     *
     * <p><b>With a scope token the text test is no longer sound</b>, because the condition the compiler
     * injects is not in the client's text. So the inspector is asked instead: it compiles the statement under
     * the token — a cached compile the interceptor is about to repeat — and reports the names the compiled
     * form actually binds. That is precise in both directions: a statement reading a scoped entity gets the
     * value even though it never mentions it, and a statement reading nothing scoped keeps an argument equal
     * to every other caller's, so those streams still share.
     *
     * <p>When the inspector cannot answer, every reserved name is attached rather than none. That costs
     * sharing for one statement; the other direction costs a parameter slot nothing fills, which fails the
     * query.
     */
    public static QueryArgument attach(QueryArgument argument, Object scopeToken) {
        if (resolver == null || argument == null)
            return argument;
        String[] attachNames;
        if (scopeToken == null) {
            // No scope, so nothing can be injected and the client's own text is the whole truth. Byte for
            // byte what this did before scopes existed.
            if (!mayUseCallerTerm(argument.getStatement()))
                return argument;
            attachNames = RESERVED_NAMES;
        } else {
            attachNames = ClientQueryGuard.serverCallerNamesNeeded(argument, scopeToken);
            if (attachNames == null) // could not say - attach everything rather than bind nothing
                attachNames = RESERVED_NAMES;
            else if (attachNames.length == 0) // said, and the answer is none: leave the argument shareable
                return argument;
        }
        Object[] parameters = argument.getParameters();
        String[] names = argument.getParameterNames();
        int suppliedCount = Arrays.length(parameters);
        Object[] newParameters = new Object[suppliedCount + attachNames.length];
        String[] newNames = new String[Arrays.length(names) + attachNames.length];
        for (int i = 0; i < suppliedCount; i++)
            newParameters[i] = parameters[i];
        for (int i = 0; i < Arrays.length(names); i++)
            newNames[i] = names[i];
        for (int i = 0; i < attachNames.length; i++) {
            newParameters[suppliedCount + i] = resolver.resolveCallerValue(attachNames[i]);
            newNames[Arrays.length(names) + i] = attachNames[i];
        }
        return QueryArgument.builder().copy(argument).setParameters(newParameters).setParameterNames(newNames).build();
    }
}
