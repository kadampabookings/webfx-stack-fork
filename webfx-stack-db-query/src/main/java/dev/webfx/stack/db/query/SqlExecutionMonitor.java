package dev.webfx.stack.db.query;

import dev.webfx.platform.async.util.AsyncQueue;

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

    /** Immutable snapshot of both kinds. Queue depths are read live from the AsyncQueues. */
    public Snapshot snapshot() {
        // Counters read under this monitor's lock; queue depths read via the queues' own locks
        // (outside this lock — there is no reverse lock ordering, but keeping the scope minimal
        // avoids holding two locks at once).
        long rc, rt, re, wc, wt, we;
        AsyncQueue rq, wq;
        synchronized (this) {
            rc = read.count;  rt = read.totalNanos;  re = read.errorCount;  rq = read.queue;
            wc = write.count; wt = write.totalNanos; we = write.errorCount; wq = write.queue;
        }
        return new Snapshot(kindSnapshot(rc, rt, re, rq), kindSnapshot(wc, wt, we, wq));
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

    /**
     * Per-kind snapshot: cumulative {@code count}/{@code totalNanos}/{@code errorCount} plus the
     * live queue gauges ({@code waiting}, {@code executing}, {@code maxConcurrency} = pool size,
     * {@code peakWaiting} high-water mark). Rates are derived client-side from poll-to-poll deltas.
     */
    public record KindSnapshot(long count, long totalNanos, long errorCount,
                               int waiting, int executing, int maxConcurrency, int peakWaiting) {}

    /** Immutable snapshot of read and write execution. */
    public record Snapshot(KindSnapshot read, KindSnapshot write) {}
}
