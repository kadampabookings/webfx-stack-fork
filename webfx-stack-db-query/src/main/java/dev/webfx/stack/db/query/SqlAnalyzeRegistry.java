package dev.webfx.stack.db.query;

import java.util.HashMap;
import java.util.Map;

/**
 * Process-wide "analyze on next occurrence" registry backing the /monitor slow-query drill-down.
 * <p>
 * An admin arms a tracked read statement; the next time the executor runs that exact statement it
 * captures the <em>real</em> parameters, runs {@code EXPLAIN (ANALYZE, BUFFERS)} on a separate
 * connection, and stores the plan here for the admin's poll to pick up. This lets an operator get a
 * production query plan with production parameters without direct DB access.
 * <p>
 * Client-safe on purpose (no executor / Vert.x dependency): the executor supplies the finished plan
 * text; this only holds arming state + captured results. Access is {@code synchronized} (a no-op on
 * single-threaded J2CL, consistent across the server's event loops on the JVM). A {@code volatile}
 * armed count lets the hot query path skip the map lookup entirely when nothing is armed —
 * {@link #claimIfArmed} re-checks under the lock, so a stale fast-path read is harmless.
 *
 * @author Bruno Salmon
 */
public final class SqlAnalyzeRegistry {

    /** NONE = nothing armed/captured; PENDING = armed or capturing; READY = plan captured. */
    public enum Status { NONE, PENDING, READY }

    private static final SqlAnalyzeRegistry INSTANCE = new SqlAnalyzeRegistry();

    public static SqlAnalyzeRegistry get() {
        return INSTANCE;
    }

    /** Default time an arm stays live waiting for the next occurrence of the statement. */
    public static final long DEFAULT_ARM_TTL_MILLIS = 60_000;

    /** Cap on stored results; past this, the oldest is evicted. */
    private static final int MAX_RESULTS = 32;

    // Fast-path hint read without locking (see class doc); mutated only under `this`.
    private volatile int armedCount;
    private final Map<String, Long> armedExpiry = new HashMap<>();   // statement -> expiry epoch ms
    private final Map<String, Result> results = new HashMap<>();     // statement -> captured plan / capturing marker

    private SqlAnalyzeRegistry() {}

    /** Arms a statement for analyze-on-next-occurrence, clearing any prior arm/result for it. */
    public synchronized void arm(String statement, long nowMillis, long ttlMillis) {
        armedExpiry.put(statement, nowMillis + ttlMillis);
        results.remove(statement); // discard any stale prior plan
        armedCount = armedExpiry.size();
    }

    /** Unsynchronized fast-path hint for the executor: is anything armed at all? */
    public boolean hasArmed() {
        return armedCount > 0;
    }

    /**
     * If {@code statement} is armed and unexpired, atomically disarm it and return true so exactly
     * one execution captures it; the caller then runs EXPLAIN and calls {@link #storeResult}. A
     * capturing marker is recorded so {@link #getResult} reports PENDING until the plan lands.
     */
    public synchronized boolean claimIfArmed(String statement, long nowMillis) {
        Long expiry = armedExpiry.remove(statement);
        armedCount = armedExpiry.size();
        if (expiry == null || expiry < nowMillis)
            return false; // not armed, or expired — don't capture
        results.put(statement, new Result(Status.PENDING, null, null, null, nowMillis));
        return true;
    }

    /**
     * Stores the captured EXPLAIN plan for a claimed statement, along with the parameters used and
     * the original DQL statement it was compiled from (null when the SQL wasn't DQL-derived).
     */
    public synchronized void storeResult(String statement, String dqlStatement, String parametersDisplay, String planText, long nowMillis) {
        if (results.size() >= MAX_RESULTS && !results.containsKey(statement))
            evictOldest();
        results.put(statement, new Result(Status.READY, dqlStatement, parametersDisplay, planText, nowMillis));
    }

    /** Current analyze state for a statement: READY (plan), PENDING (armed/capturing) or NONE. */
    public synchronized Result getResult(String statement, long nowMillis) {
        Result r = results.get(statement);
        if (r != null)
            return r;
        Long expiry = armedExpiry.get(statement);
        if (expiry == null)
            return NONE;
        if (expiry < nowMillis) { // arm expired without an occurrence — clean up
            armedExpiry.remove(statement);
            armedCount = armedExpiry.size();
            return NONE;
        }
        return new Result(Status.PENDING, null, null, null, expiry);
    }

    private void evictOldest() {
        String oldest = null;
        long min = Long.MAX_VALUE;
        for (Map.Entry<String, Result> e : results.entrySet())
            if (e.getValue().atMillis < min) {
                min = e.getValue().atMillis;
                oldest = e.getKey();
            }
        if (oldest != null)
            results.remove(oldest);
    }

    private static final Result NONE = new Result(Status.NONE, null, null, null, 0);

    /**
     * Immutable analyze state. PENDING while waiting for the next occurrence or capturing the plan;
     * READY carries {@code planText}, the {@code parametersDisplay} used, the {@code dqlStatement}
     * the SQL was compiled from (null when not DQL-derived) and {@code atMillis} = the capture time.
     * NONE means nothing armed/captured (or the arm expired).
     */
    public static final class Result {
        public final Status status;
        public final String dqlStatement;      // original DQL, null unless READY and DQL-derived
        public final String parametersDisplay; // null unless READY
        public final String planText;          // null unless READY
        public final long atMillis;            // capture time (READY) — for age + oldest-eviction

        Result(Status status, String dqlStatement, String parametersDisplay, String planText, long atMillis) {
            this.status = status;
            this.dqlStatement = dqlStatement;
            this.parametersDisplay = parametersDisplay;
            this.planText = planText;
            this.atMillis = atMillis;
        }
    }
}
