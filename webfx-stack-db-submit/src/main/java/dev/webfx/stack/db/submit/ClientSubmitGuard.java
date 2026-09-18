package dev.webfx.stack.db.submit;

import dev.webfx.platform.async.Batch;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.query.ClientQueryGuard;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The check every write arriving FROM A CLIENT passes before it runs — the write-side twin of
 * {@code ClientQueryGuard}.
 *
 * <h3>What it stops</h3>
 *
 * <ul>
 *   <li><b>The columns in {@link ClientWriteDenyList}</b> — password hashes, sign-in usernames, the account's
 *       disabled flag — however a DQL statement sets them. Enforced by an {@link Inspector} a server module
 *       registers, because reading a statement properly needs the DQL parser, which this module cannot depend
 *       on.</li>
 *   <li><b>Raw statements.</b> A submit that is not DQL reaches the database as written, past every check that
 *       reads what a statement touches — including the one above, which a raw
 *       {@code update frontend_account set password=…} would otherwise simply walk round. Refused unless the
 *       application's {@link RawStatementPolicy} lists that exact statement: the React apps never send one, and
 *       the legacy clients send a couple of fixed ones an application can name.</li>
 * </ul>
 *
 * <p>It is NOT write authorisation: whose rows a client may change is a different question, answered by the
 * application's authorizer. This closes the narrower route from writing to signing in as somebody, which every
 * control on sessions and passwords depends on.
 *
 * <h3>Where it is called</h3>
 *
 * <p>From the two endpoints through which a client's writes arrive — single and batch — and nowhere else. Server
 * code calls the submit service directly and never passes through here, so the flows that must write these
 * columns (a password change, an email change) still can.
 *
 * <p>A transaction preamble with no statement is let through: the server supplies the real one.
 *
 * <p>Compiled into the browser too (the endpoints are registered in the GWT entry point), so it uses only the JRE
 * GWT emulates.
 *
 * @author Claude Code
 */
public final class ClientSubmitGuard {

    /** DQL inspection — in practice, parsing the statement and checking what it sets. */
    @FunctionalInterface
    public interface Inspector {
        /** Why this DQL write must not run for a client, or null when it may. Must not throw. */
        String refusalReason(SubmitArgument argument);
    }

    /**
     * Whether this raw statement may run for a client — answered from the statement alone, synchronously. An
     * application uses it to let through the few fixed statements its legacy clients still send; it deliberately
     * gets no say based on WHO is asking, because an answer that waited on an authorization lookup would run the
     * write after the caller's state is gone, and one that trusted a grant would be only as strong as the
     * weakest way to obtain that grant.
     */
    @FunctionalInterface
    public interface RawStatementPolicy {
        boolean mayRunRawStatement(SubmitArgument argument);
    }

    /** Refusal messages returned to the client. Deliberately short and saying nothing about the schema. */
    public static final String RAW_STATEMENT_REFUSED = "A client write must be DQL";
    public static final String UNCHECKABLE_REFUSED = "This write cannot be checked, so it was not run";
    public static final String DENIED_REFUSED = "This write is not allowed from a client";

    private static volatile Inspector inspector;
    private static volatile RawStatementPolicy rawStatementPolicy;

    /** Refusal shapes already reported, so a client retrying one refused write logs one line, not thousands. */
    private static final Set<String> REPORTED = new HashSet<>();
    private static final int MAX_REPORTED = 500;

    private ClientSubmitGuard() {}

    /** Installs the inspector that enforces {@link ClientWriteDenyList}. Last registration wins. */
    public static void registerInspector(Inspector inspector) {
        ClientSubmitGuard.inspector = inspector;
    }

    /** Installs the application's list of raw statements a client may still send. With none, no raw statement runs. */
    public static void registerRawStatementPolicy(RawStatementPolicy policy) {
        ClientSubmitGuard.rawStatementPolicy = policy;
    }

    /** Succeeds when this client write may run; fails with the refusal otherwise. Call on the caller's thread. */
    public static Future<Void> check(SubmitArgument argument) {
        return decide(argument);
    }

    /**
     * Every statement of the batch, each decided on the caller's thread before any completes — a batch is one
     * transaction, so one refused statement refuses the whole of it, and a refused write hidden behind a
     * legitimate one must not be the way through.
     */
    public static Future<Void> checkBatch(Batch<SubmitArgument> batch) {
        if (batch == null || batch.getArray() == null)
            return refused(null, UNCHECKABLE_REFUSED);
        List<Future<Void>> decisions = new ArrayList<>();
        for (SubmitArgument argument : batch.getArray())
            decisions.add(decide(argument));
        return Future.all(new ArrayList<>(decisions)).map(ignored -> null);
    }

    private static Future<Void> decide(SubmitArgument argument) {
        if (argument == null)
            return refused(null, UNCHECKABLE_REFUSED);
        // A preamble's statement is ignored and the server supplies its own — but only a provider that honours the
        // flag ignores it, so one that arrives WITH a statement is not exempt: whatever it says is judged below.
        if (argument.isTransactionPreamble() && (argument.getStatement() == null || argument.getStatement().isEmpty()))
            return Future.succeededFuture();
        String language = argument.getLanguage();
        if (language == null || !"DQL".equalsIgnoreCase(language)) {
            RawStatementPolicy policy = rawStatementPolicy;
            boolean allowed;
            try {
                allowed = policy != null && policy.mayRunRawStatement(argument);
            } catch (Throwable e) {
                allowed = false; // a policy that throws denies; it does not abstain
            }
            return allowed ? Future.succeededFuture() : refused(argument, RAW_STATEMENT_REFUSED);
        }
        Inspector i = inspector;
        if (i == null)
            // Fail CLOSED once something has been declared: a deployment that says a column must never be written
            // by a client, but has nothing able to check for it, must refuse rather than let it through.
            return ClientWriteDenyList.isEmpty() ? Future.succeededFuture() : refused(argument, UNCHECKABLE_REFUSED);
        String refusal;
        try {
            refusal = i.refusalReason(argument);
        } catch (Throwable e) {
            Console.log("⚠️ Client write inspector failed — refusing the write: " + e);
            refusal = UNCHECKABLE_REFUSED;
        }
        return refusal == null ? Future.succeededFuture() : refused(argument, refusal);
    }

    /**
     * One line per refused SHAPE, literals masked the same way the read guard masks them: the shape says which
     * screen sent it, while a literal is where somebody's email or name would be.
     */
    private static Future<Void> refused(SubmitArgument argument, String refusal) {
        String statement = argument == null ? null : argument.getStatement();
        String shape = statement == null ? "(none)" : ClientQueryGuard.maskLiterals(statement);
        String language = argument == null ? null : argument.getLanguage();
        boolean first;
        synchronized (REPORTED) {
            first = REPORTED.size() < MAX_REPORTED && REPORTED.add(refusal + "|" + shape);
        }
        if (first)
            Console.log("🛡 Refused a client write (" + refusal + ", language=" + language + "): " + shape);
        return Future.failedFuture("[NotAuthorizedError] " + refusal);
    }
}
