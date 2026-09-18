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

    public static boolean isColumnDenied(String sqlTableName, String sqlColumnName) {
        if (sqlTableName == null || sqlColumnName == null)
            return false;
        return isTableDenied(sqlTableName) || deniedColumns.contains(normalise(sqlTableName) + "." + normalise(sqlColumnName));
    }

    /** True when nothing has been declared secret, so there is nothing for an inspector to enforce. */
    public static boolean isEmpty() {
        return deniedTables.isEmpty() && deniedColumns.isEmpty();
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
