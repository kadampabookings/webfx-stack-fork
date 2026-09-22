package dev.webfx.stack.db.query;

import dev.webfx.platform.console.Console;

import java.util.HashSet;
import java.util.Set;

/**
 * The check every query arriving FROM A CLIENT passes before it runs.
 *
 * <h3>What it stops, and what it does not</h3>
 *
 * <p>Two things, both of which turn reading into signing in as somebody:
 *
 * <ul>
 *   <li><b>Raw SQL.</b> A query argument with no language is executed as written, which skips the DQL compiler
 *       and everything that inspects what a query touches. Clients send DQL — the React apps always do, and the
 *       one legacy screen that did not is no longer used — so a raw statement from a client has no legitimate
 *       source left.</li>
 *   <li><b>The columns in {@link ClientReadDenyList}</b> — sign-in tokens and codes, password hashes, reset
 *       tokens — wherever in a DQL statement they appear. Enforced by an {@link Inspector} that a server module
 *       registers, because doing it properly needs the query compiler, which this module cannot depend on.</li>
 * </ul>
 *
 * <p>It is NOT read authorisation. Bookings, names and health notes stay readable to any client until that
 * exists; this closes the narrower route from reading to impersonating, which everything else about sessions
 * depends on.
 *
 * <h3>Where it is called</h3>
 *
 * <p>From every endpoint through which a client's query arrives — single query, batch and push subscription —
 * and nowhere else. Server code calls the query service directly and never passes through here, so the server
 * can still read its own tables, which it must: signing somebody in means reading their sign-in code. A guard on
 * one endpoint alone would simply be walked round through another.
 *
 * <p>Compiled into the browser too (the endpoints are registered in the GWT entry point), so it uses only the JRE
 * GWT emulates, and has no dependency on the compiler.
 *
 * @author Claude Code
 */
public final class ClientQueryGuard {

    /** Language-specific inspection — in practice, compiling DQL and checking what it touches. */
    @FunctionalInterface
    public interface Inspector {
        /** Why this query must not run for a client, or null when it may. Must not throw. */
        String refusalReason(QueryArgument argument);
    }

    /** Refusal messages returned to the client. Deliberately short and saying nothing about the schema. */
    public static final String RAW_SQL_REFUSED = "A client query must be DQL";
    public static final String UNCHECKABLE_REFUSED = "This query cannot be checked, so it was not run";

    private static volatile Inspector inspector;

    /** Query shapes already reported, so a client retrying one refused query logs one line, not thousands. */
    private static final Set<String> REPORTED = new HashSet<>();
    private static final int MAX_REPORTED = 500;

    private ClientQueryGuard() {}

    /** Installs the inspector that enforces {@link ClientReadDenyList}. Last registration wins. */
    public static void registerInspector(Inspector inspector) {
        ClientQueryGuard.inspector = inspector;
    }

    /** Why this client query must not run, or null when it may. */
    public static String refusalReason(QueryArgument argument) {
        String refusal = decide(argument);
        if (refusal != null)
            report(argument, refusal);
        return refusal;
    }

    private static String decide(QueryArgument argument) {
        if (argument == null)
            return UNCHECKABLE_REFUSED;
        if (argument.getLanguage() == null) {
            // Refused here, before any inspector is consulted — so if anyone is watching client reads, this is
            // the only place they can hear about this one. It is worth hearing: a client still sending raw SQL
            // is exactly the traffic that "how much can the inventory not describe" is measuring, and an
            // inventory silently missing it would describe a client's traffic as tidier than it is.
            if (ClientReadInspectionRegistry.isInspectingRead())
                ClientReadInspectionRegistry.notifyUndescribableStatement(argument.getStatement());
            return RAW_SQL_REFUSED;
        }
        Inspector i = inspector;
        if (i == null)
            // Fail CLOSED once something has been declared secret: a deployment that says a column must never
            // reach a client, but has nothing able to check for it, must refuse rather than quietly let it
            // through. With nothing declared there is nothing to enforce, and DQL runs as it always has.
            return ClientReadDenyList.isEmpty() ? null : UNCHECKABLE_REFUSED;
        try {
            return i.refusalReason(argument);
        } catch (Throwable e) {
            // An inspector that throws has not said yes. Refusing costs the client one query; allowing would
            // cost the one thing this exists to protect.
            Console.log("⚠️ Client query inspector failed — refusing the query: " + e);
            return UNCHECKABLE_REFUSED;
        }
    }

    /**
     * One line per query SHAPE, with string and number literals masked. The shape is what identifies which
     * screen sent it — the thing an operator needs if a legitimate one turns out to have been refused — while
     * a literal is where an email address or a name would be, and those do not belong in a log.
     */
    private static void report(QueryArgument argument, String refusal) {
        String statement = argument == null ? null : argument.getStatement();
        String shape = statement == null ? "(none)" : maskLiterals(statement);
        synchronized (REPORTED) {
            if (REPORTED.size() >= MAX_REPORTED || !REPORTED.add(shape))
                return;
        }
        Console.log("🛡 Refused a client query (" + refusal + "): " + shape);
    }

    /** The shape a refused query is logged as: tables and columns kept, string and number literals masked. */
    public static String maskLiterals(String statement) {
        // All three quote styles the DQL lexer accepts for a string literal — single, double and backtick —
        // because a name typed as "Jean Dupont" is as personal as one typed as 'Jean Dupont'.
        String masked = statement
            .replaceAll("'(?:[^']|'')*'", "'?'")
            .replaceAll("\"(?:[^\"]|\"\")*\"", "\"?\"")
            .replaceAll("`(?:[^`]|``)*`", "`?`")
            .replaceAll("\\b\\d+(?:\\.\\d+)?\\b", "?")
            .replaceAll("\\s+", " ")
            .trim();
        return masked.length() > 200 ? masked.substring(0, 200) + "…" : masked;
    }
}
