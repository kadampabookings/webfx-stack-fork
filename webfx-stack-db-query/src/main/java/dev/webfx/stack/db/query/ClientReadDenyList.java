package dev.webfx.stack.db.query;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Tables and columns a CLIENT's query may never touch, whatever it selects, filters or joins on.
 *
 * <h3>Why names at the SQL level</h3>
 *
 * <p>Matched against the table and column names the query compiler actually emits, not against the words in
 * the query. A DQL statement can reach a column without naming its table — {@code person.frontendAccount.password}
 * reads a password from a query rooted at {@code Document} — and can touch one without selecting it, in a
 * {@code where} clause that lets a client test guesses one row at a time. What reaches SQL is the only thing
 * that cannot be rephrased, so that is what is checked. See the inspector that enforces this.
 *
 * <h3>What belongs here</h3>
 *
 * <p>Secrets whose disclosure is a way in, not merely a leak: sign-in tokens and codes, password hashes, reset
 * tokens. This is not the place for ordinary personal data, which needs a real read-authorisation model rather
 * than a list — a list long enough to cover it would be a policy nobody could review.
 *
 * <p>Plain collections and copy-on-write rather than concurrent ones, because this class is compiled into the
 * browser as well (the query endpoints that consult it are registered in the GWT entry point) and only the
 * JRE that GWT emulates is available. Entries are added at boot and read on every client query, which is the
 * access pattern copy-on-write suits anyway.
 *
 * @author Claude Code
 */
public final class ClientReadDenyList {

    private static volatile Set<String> deniedTables = Collections.emptySet();
    /** "table.column", lower-cased. */
    private static volatile Set<String> deniedColumns = Collections.emptySet();
    /** "table.column", lower-cased — testable by equality, never readable. See denyColumnExceptEqualityMatch. */
    private static volatile Set<String> capabilityColumns = Collections.emptySet();
    /** Bumped on every change, so anything that caches a verdict can tell when it went stale. */
    private static volatile int version;

    private ClientReadDenyList() {}

    /** Denies every column of this table to client queries — for tables that are nothing but secrets. */
    public static synchronized void denyTable(String sqlTableName) {
        Set<String> next = new HashSet<>(deniedTables);
        if (next.add(normalise(sqlTableName))) {
            deniedTables = Collections.unmodifiableSet(next);
            version++;
        }
    }

    /**
     * A column a client may TEST but never READ — the shape a capability token needs.
     *
     * <h3>Why a third category, and not simply a denial</h3>
     *
     * <p>A capability token is a secret the holder is supposed to present. An invitation link, a volunteer's
     * proposed dates, an arrival confirmation: the page is reached by whoever holds the link, and the server
     * finds the row by the token in it. So the column cannot be denied outright — the feature IS looking a row
     * up by it — and it cannot be left readable either, because <b>the way a capability like this is defeated is
     * not by guessing one but by asking for all of them.</b> {@code select token from Invitation} returns every
     * live token, and the tokens are random precisely so that nobody has to guess.
     *
     * <p>So: comparing it for equality against a bound parameter is allowed — as many times in one statement as
     * the caller likes, which is guessing at whatever rate the transport allows and is worth nothing against a
     * random token — and everything else is refused —
     * selecting it, ordering by it, testing it with {@code like} or a range, grouping on it. What that leaves a
     * caller is one bit per request, "does this exact token exist", which is what presenting a capability
     * legitimately tells you and is worth nothing against a random one.
     *
     * <p><b>The check is fail-closed on anything it does not recognise</b>, which is what makes a rule this
     * specific safe to state: a statement naming one of these columns in a construct the check cannot read is
     * refused rather than guessed at. The population of such statements is tiny and known, so a false refusal
     * shows up at once rather than lurking.
     */
    public static synchronized void denyColumnExceptEqualityMatch(String sqlTableName, String sqlColumnName) {
        // Declaring a column both ways would SILENTLY WEAKEN it: the reader asks isCapabilityColumn first, so
        // the testable rule would win and the absolute denial beside it would never fire. Refused at boot, where
        // it is one stack trace, rather than discovered later as a column everyone believed was unreadable.
        if (deniedColumns.contains(normalise(sqlTableName) + "." + normalise(sqlColumnName))
            || isTableDenied(sqlTableName))
            throw new IllegalStateException(
                "A column already denied to clients cannot also be declared testable: " + sqlTableName);
        Set<String> next = new HashSet<>(capabilityColumns);
        if (next.add(normalise(sqlTableName) + "." + normalise(sqlColumnName))) {
            capabilityColumns = Collections.unmodifiableSet(next);
            version++;
        }
    }

    /** Whether this column is a capability token: testable by equality, never readable. */
    public static boolean isCapabilityColumn(String sqlTableName, String sqlColumnName) {
        return sqlTableName != null && sqlColumnName != null
               && capabilityColumns.contains(normalise(sqlTableName) + "." + normalise(sqlColumnName));
    }

    /** Denies one column to client queries, leaving the rest of its table readable. */
    public static synchronized void denyColumn(String sqlTableName, String sqlColumnName) {
        Set<String> next = new HashSet<>(deniedColumns);
        if (next.add(normalise(sqlTableName) + "." + normalise(sqlColumnName))) {
            deniedColumns = Collections.unmodifiableSet(next);
            version++;
        }
    }

    public static boolean isTableDenied(String sqlTableName) {
        return sqlTableName != null && deniedTables.contains(normalise(sqlTableName));
    }

    /**
     * Whether a client may read this column at all.
     *
     * <p>True for a capability column too, deliberately: a consumer that has not learned about the third
     * category should refuse one rather than hand it over. The one place that knows better asks
     * {@link #isCapabilityColumn} FIRST, and it is the only place that may.
     */
    public static boolean isColumnDenied(String sqlTableName, String sqlColumnName) {
        if (sqlTableName == null || sqlColumnName == null)
            return false;
        return isTableDenied(sqlTableName)
               || deniedColumns.contains(normalise(sqlTableName) + "." + normalise(sqlColumnName))
               || isCapabilityColumn(sqlTableName, sqlColumnName);
    }

    /** True when nothing has been declared secret, so there is nothing for an inspector to enforce. */
    public static boolean isEmpty() {
        return deniedTables.isEmpty() && deniedColumns.isEmpty() && capabilityColumns.isEmpty();
    }

    public static int version() {
        return version;
    }

    /**
     * Lower-cased and stripped of quoting and schema, because the compiler may emit {@code public."MagicLink"}
     * where the list says {@code magic_link}. A mismatch here fails OPEN — the secret stays readable — so the
     * comparison is made as forgiving as the names allow.
     */
    private static String normalise(String name) {
        String n = name.trim().toLowerCase();
        int dot = n.lastIndexOf('.');
        if (dot >= 0)
            n = n.substring(dot + 1);
        return n.replace("\"", "");
    }
}
