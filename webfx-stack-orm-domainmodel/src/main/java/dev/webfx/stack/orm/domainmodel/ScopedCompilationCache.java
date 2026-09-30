package dev.webfx.stack.orm.domainmodel;

import dev.webfx.stack.orm.dql.sqlcompiler.sql.SqlCompiled;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Compiled statements, kept per data scope and bounded in both directions.
 *
 * <p>Two dimensions, and they fail differently.
 *
 * <p><b>Statements.</b> The key is text a CLIENT chooses, so a caller minting variants grows this without
 * limit — the hazard the read inventory caps itself against in as many words. It was unbounded before scope
 * existed; scope only multiplies it. Capped crudely, by clearing, which is the same trade the client-query
 * inspector already makes for the same reason: a statement costs one extra compile to earn its place back,
 * and an eviction policy that is subtler is also one more thing to be wrong.
 *
 * <p><b>Scope tokens.</b> Bounded only if a token names one of a fixed set of scopes. Hand it something
 * caller-derived — an organization-id set, say — and the cache grows combinatorially with the callers. That
 * is a mistake in our own code, never in traffic, so it REFUSES rather than evicting: a design error that
 * throws on the first few requests is cheaper than one found later in a heap dump. It cannot fire while no
 * scope provider is registered, because an unscoped compilation has no token at all.
 *
 * <p>Not weakly or softly referenced. Weak keys would be collected almost at once — the key is a string
 * built per request and held by nothing else — so the cache would simply stop caching. Soft references clear
 * in bulk under memory pressure, which means every query recompiles at the moment the server is least able
 * to afford it.
 *
 * @author Claude Code
 */
final class ScopedCompilationCache {

    /**
     * Distinct scopes this will cache for before it concludes the token is not a named scope. Generous: a
     * system with more than this many genuine scopes has a different problem, and the number exists to catch
     * a token that varies per caller, which passes it within minutes rather than approaching it slowly.
     */
    static final int MAX_SCOPE_TOKENS = 64;

    /** Compiled statements held before the cache is cleared. */
    static final int MAX_COMPILED_STATEMENTS = 2000;

    private final Map<String, SqlCompiled> compiled = new HashMap<>();
    private final Set<String> scopeTokens = new HashSet<>();

    /** The cache key for a statement under a scope. Null token — the unscoped case — keys on the text alone. */
    static String keyOf(String statement, Object scopeToken) {
        return scopeToken == null ? statement : scopeToken + "\u0000" + statement;
    }

    SqlCompiled get(String key) {
        return compiled.get(key);
    }

    void put(String key, SqlCompiled sqlCompiled) {
        if (compiled.size() >= MAX_COMPILED_STATEMENTS)
            compiled.clear(); // crude, but bounded; a statement costs one extra compile to re-earn
        compiled.put(key, sqlCompiled);
    }

    /**
     * Records a scope this is being asked to compile for, and refuses once there are too many to be a fixed
     * set. Throws rather than returning a verdict because every caller would do the same thing with it.
     */
    void noteScopeToken(Object scopeToken) {
        if (scopeToken == null)
            return;
        String token = scopeToken.toString();
        if (scopeTokens.contains(token))
            return;
        if (scopeTokens.size() >= MAX_SCOPE_TOKENS)
            // The message carries the whole diagnosis and nothing logs it first. A refusal that needs the
            // console to be initialised fails as a ServiceConfigurationError wherever it is not — which is
            // how this check first failed its own test — and a guard whose failure path has a dependency is
            // a guard that works only where it was tried.
            throw new IllegalStateException("Too many distinct data scopes (" + MAX_SCOPE_TOKENS + "):"
                + " a scope token must name one of a FIXED set of scopes. One that varies per caller makes"
                + " the compiled-statement cache grow with the callers. Look at what is being passed as the"
                + " token, not at this cap.");
        scopeTokens.add(token);
    }

    int scopeTokenCount() {
        return scopeTokens.size();
    }

    int size() {
        return compiled.size();
    }
}
