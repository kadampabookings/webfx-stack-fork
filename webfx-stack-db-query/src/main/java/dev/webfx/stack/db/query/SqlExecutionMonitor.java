package dev.webfx.stack.db.query;

import dev.webfx.platform.async.util.AsyncQueue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Process-wide monitoring of local SQL execution — reads (queries / SELECT) and writes
 * (submits / INSERT-UPDATE-DELETE) tracked separately — for the /monitor page.
 * <p>
 * Holds per-kind cumulative counters (count, total time, errors) plus a reference to each
 * kind's {@link AsyncQueue} so the snapshot can read live queue depth. The concrete executor
 * ({@code VertxLocalPostgresQuerySubmitServiceProvider}) registers its queue and calls
 * {@link #record} on each completed operation; the query-push {@code getMonitorInfo()} reads
 * {@link #snapshot()}.
 * <p>
 * Client-safe on purpose (no executor / Vert.x dependency): it can sit next to the query
 * facade and be read from the monitor aggregation without coupling that to the executor. Access
 * is {@code synchronized} — a no-op on single-threaded J2CL clients (where it is never written)
 * and consistent across the server's event loops on the JVM.
 *
 * @author Bruno Salmon
 */
public final class SqlExecutionMonitor {

    /** READ = query (SELECT); WRITE = submit (INSERT/UPDATE/DELETE). */
    public enum Kind { READ, WRITE }

    private static final SqlExecutionMonitor INSTANCE = new SqlExecutionMonitor();

    public static SqlExecutionMonitor get() {
        return INSTANCE;
    }

    private final KindCounters read = new KindCounters();
    private final KindCounters write = new KindCounters();

    private SqlExecutionMonitor() {}

    private KindCounters counters(Kind kind) {
        return kind == Kind.WRITE ? write : read;
    }

    /** Registers a kind's AsyncQueue so {@link #snapshot()} can read its live depth. */
    public synchronized void registerQueue(Kind kind, AsyncQueue queue) {
        counters(kind).queue = queue;
    }

    /**
     * Records one completed SQL operation of the given kind.
     *
     * @param nanos   wall time of the operation
     * @param success whether it completed without error
     */
    public synchronized void record(Kind kind, long nanos, boolean success) {
        KindCounters c = counters(kind);
        c.count++;
        c.totalNanos += nanos;
        if (!success)
            c.errorCount++;
    }

    // ── In-flight registry + per-statement rollup (drives the /monitor drill-down and,
    //    via the stored cancel handle, a future query-cancellation feature) ──────────────

    /** Default number of top statements returned by {@link #snapshot()}. */
    public static final int DEFAULT_TOP_STATEMENTS = 20;

    /** Cap on distinct statements tracked; past this, the smallest-total entry is evicted. */
    private static final int MAX_TRACKED_STATEMENTS = 256;

    private long idSeq;
    private final Map<Long, InFlight> inFlight = new HashMap<>();
    private final Map<String, StatementStat> statementStats = new HashMap<>();

    /**
     * Registers a starting SQL execution and returns its monitor id. The {@code cancelHandle} is
     * stored opaquely — the executor supplies a client-safe {@link Runnable} cancel action (so this
     * module stays free of any Vert.x dependency), which {@link #cancel(long)} runs on request;
     * pass null when none is available.
     */
    public synchronized long onStart(Kind kind, String statement, Object cancelHandle) {
        long id = ++idSeq;
        inFlight.put(id, new InFlight(id, kind, statement, System.nanoTime(), cancelHandle));
        return id;
    }

    /** Marks a SQL execution finished: drops it from in-flight and rolls its time into the per-statement stats. */
    public synchronized void onEnd(long id) {
        InFlight f = inFlight.remove(id);
        if (f == null)
            return;
        long nanos = System.nanoTime() - f.startNanos;
        StatementStat stat = statementStats.get(f.statement);
        if (stat == null) {
            if (statementStats.size() >= MAX_TRACKED_STATEMENTS)
                evictSmallestStatement();
            statementStats.put(f.statement, stat = new StatementStat(f.kind));
        }
        stat.count++;
        stat.totalNanos += nanos;
        if (nanos > stat.maxNanos)
            stat.maxNanos = nanos;
    }

    /** Returns the opaque cancel handle for an in-flight query, or null if it already finished. */
    public synchronized Object cancelHandleOf(long id) {
        InFlight f = inFlight.get(id);
        return f == null ? null : f.cancelHandle;
    }

    /**
     * Whether {@code statement} is a read (query/SELECT) statement this monitor has seen — i.e. it
     * is in the per-statement rollup or currently in-flight as a READ. Used to gate "analyze this
     * statement" requests to statements the server actually runs, so an admin can never arm analysis
     * of arbitrary client-supplied SQL.
     */
    public synchronized boolean isKnownReadStatement(String statement) {
        if (statement == null)
            return false;
        StatementStat stat = statementStats.get(statement);
        if (stat != null)
            return stat.kind == Kind.READ;
        for (InFlight f : inFlight.values())
            if (f.kind == Kind.READ && statement.equals(f.statement))
                return true;
        return false;
    }

    /**
     * Best-effort cancellation of an in-flight query: runs its stored cancel action outside this
     * monitor's lock (the action may open a network connection — never hold the lock across it).
     * The executor supplies the handle as a client-safe {@link Runnable}, so this stays free of any
     * executor / Vert.x dependency. Returns true if an action was found and run, false if the id is
     * unknown, already finished, or had no cancel handle.
     */
    public boolean cancel(long id) {
        Object handle;
        synchronized (this) {
            InFlight f = inFlight.get(id);
            handle = f == null ? null : f.cancelHandle;
        }
        if (handle instanceof Runnable r) {
            r.run();
            return true;
        }
        return false;
    }

    private void evictSmallestStatement() {
        String smallest = null;
        long min = Long.MAX_VALUE;
        for (Map.Entry<String, StatementStat> e : statementStats.entrySet()) {
            if (e.getValue().totalNanos < min) {
                min = e.getValue().totalNanos;
                smallest = e.getKey();
            }
        }
        if (smallest != null)
            statementStats.remove(smallest);
    }

    /** Immutable snapshot with the default top-statements count. */
    public Snapshot snapshot() {
        return snapshot(DEFAULT_TOP_STATEMENTS);
    }

    /**
     * Immutable snapshot of both kinds, plus the top-N statements by total time and the
     * currently in-flight queries. Queue depths are read live from the AsyncQueues.
     */
    public Snapshot snapshot(int topStatements) {
        // Counters + registry read under this monitor's lock; queue depths read via the queues'
        // own locks (outside this lock — no reverse lock ordering, but keeps the scope minimal).
        long rc, rt, re, wc, wt, we;
        AsyncQueue rq, wq;
        List<StatementSnapshot> statements;
        List<InFlightSnapshot> flights;
        synchronized (this) {
            rc = read.count;  rt = read.totalNanos;  re = read.errorCount;  rq = read.queue;
            wc = write.count; wt = write.totalNanos; we = write.errorCount; wq = write.queue;
            long nowNanos = System.nanoTime();
            flights = new ArrayList<>(inFlight.size());
            for (InFlight f : inFlight.values()) {
                flights.add(new InFlightSnapshot(f.id, f.kind, f.statement, (nowNanos - f.startNanos) / 1_000_000L));
            }
            List<Map.Entry<String, StatementStat>> entries = new ArrayList<>(statementStats.entrySet());
            entries.sort(Comparator.comparingLong((Map.Entry<String, StatementStat> e) -> e.getValue().totalNanos).reversed());
            int limit = Math.min(Math.max(0, topStatements), entries.size());
            statements = new ArrayList<>(limit);
            for (int i = 0; i < limit; i++) {
                Map.Entry<String, StatementStat> e = entries.get(i);
                StatementStat s = e.getValue();
                statements.add(new StatementSnapshot(e.getKey(), s.kind, s.count, s.totalNanos, s.maxNanos));
            }
        }
        return new Snapshot(kindSnapshot(rc, rt, re, rq), kindSnapshot(wc, wt, we, wq), statements, flights);
    }

    private static KindSnapshot kindSnapshot(long count, long totalNanos, long errorCount, AsyncQueue q) {
        return new KindSnapshot(
            count, totalNanos, errorCount,
            q == null ? 0 : q.getWaitingCount(),
            q == null ? 0 : q.getExecutingCount(),
            q == null ? 0 : q.getExecutingQueueMaxSize(),
            q == null ? 0 : q.getPeakWaitingCount());
    }

    private static final class KindCounters {
        private long count;
        private long totalNanos;
        private long errorCount;
        private AsyncQueue queue;
    }

    /** A currently-executing SQL statement, holding the opaque cancel handle (server-only use). */
    private static final class InFlight {
        private final long id;
        private final Kind kind;
        private final String statement;
        private final long startNanos;
        private final Object cancelHandle;

        InFlight(long id, Kind kind, String statement, long startNanos, Object cancelHandle) {
            this.id = id;
            this.kind = kind;
            this.statement = statement;
            this.startNanos = startNanos;
            this.cancelHandle = cancelHandle;
        }
    }

    /** Mutable per-statement rollup. */
    private static final class StatementStat {
        private final Kind kind;
        private long count;
        private long totalNanos;
        private long maxNanos;

        StatementStat(Kind kind) {
            this.kind = kind;
        }
    }

    /**
     * Per-kind snapshot: cumulative {@code count}/{@code totalNanos}/{@code errorCount} plus the
     * live queue gauges ({@code waiting}, {@code executing}, {@code maxConcurrency} = pool size,
     * {@code peakWaiting} high-water mark). Rates are derived client-side from poll-to-poll deltas.
     */
    public record KindSnapshot(long count, long totalNanos, long errorCount,
                               int waiting, int executing, int maxConcurrency, int peakWaiting) {}

    /**
     * Per-statement snapshot (ranked by total time). {@code statement} is already parameter-free
     * ($1, $2 …), so it is the natural rollup key.
     */
    public record StatementSnapshot(String statement, Kind kind, long count, long totalNanos, long maxNanos) {}

    /** A currently-executing SQL statement, with how long it has been running. */
    public record InFlightSnapshot(long id, Kind kind, String statement, long ageMillis) {}

    /** Immutable snapshot of read/write execution, the top statements, and the in-flight queries. */
    public record Snapshot(KindSnapshot read, KindSnapshot write,
                           List<StatementSnapshot> topStatements, List<InFlightSnapshot> inFlight) {}
}
