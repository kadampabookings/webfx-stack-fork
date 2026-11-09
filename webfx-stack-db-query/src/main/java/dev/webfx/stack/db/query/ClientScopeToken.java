package dev.webfx.stack.db.query;

/**
 * Which data scope a client's query compiles under — asked at the endpoint, where the caller still is.
 *
 * <p><b>Asked HERE and nowhere else, which is the boundary that matters.</b> The three generic query
 * endpoints are the only place a client's own statement arrives; an endpoint's internal lookup reaches the
 * query service directly and never passes this way. Deciding the scope by the thread's origin instead would
 * have scoped those lookups too — and a lookup that authorizes something by reading through the very filter
 * it is checking is not a check. {@code ClientQueryGuard} draws the same boundary for the same reason, and
 * this is deliberately the same door.
 *
 * <p><b>The token names one of a FIXED set of scopes — never the caller.</b> Compiled sql is cached per
 * (statement, token), so a token that varied per caller would grow that cache with the callers;
 * {@code ScopedCompilationCache} refuses it by name once it sees too many. Who is asking travels separately,
 * as a bound caller parameter — see {@link ServerCallerParameters}.
 *
 * <p>Null when nothing is registered, which is the state until a read-scope provider exists: every query then
 * compiles exactly as it did before scopes, and {@link QueryArgument#getScopeToken()} stays null so arguments
 * still compare equal and subscriptions still share.
 *
 * @author Claude Code
 */
public final class ClientScopeToken {

    /**
     * Answers which scope this caller reads under. Implemented where a principal means something — the
     * framework has no notion of a back office or a member — and asked only on the endpoint thread, before
     * the first async hop, for the reason {@link ServerCallerParameters} gives.
     */
    public interface Resolver {
        /** The scope for the caller on this thread, or null to compile unscoped. */
        Object tokenForCaller();
    }

    private static Resolver resolver;

    public static void registerResolver(Resolver resolver) {
        ClientScopeToken.resolver = resolver;
    }

    /**
     * The scope for the caller on this thread, or null.
     *
     * <p>A resolver that throws answers null rather than failing the query: an unscoped compile is what every
     * query did before this existed, and a scope provider that is not consulted refuses nothing — the read
     * policies are a separate arm and still apply.
     */
    public static Object forCaller() {
        Resolver r = resolver;
        if (r == null)
            return null;
        try {
            return r.tokenForCaller();
        } catch (Throwable e) {
            return null;
        }
    }

    private ClientScopeToken() {}
}
