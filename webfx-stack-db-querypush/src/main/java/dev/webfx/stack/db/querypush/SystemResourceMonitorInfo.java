package dev.webfx.stack.db.querypush;

/**
 * JVM resource metrics for the /monitor page — CPU and (JVM heap) memory of the Vert.x server
 * process. Read from the standard management beans on the server; a plain data holder here so it can
 * cross the wire to the back-office. All memory figures are the JVM's own heap (what in-memory data
 * such as retained push-query result sets consumes), NOT the host/container memory.
 *
 * <p>Two memory signals matter and are both carried:
 * <ul>
 *   <li>{@code heapUsed}/{@code heapMax} — the headline gauge (used vs the -Xmx / MaxRAMPercentage
 *       ceiling). Instantaneous {@code heapUsed} is sawtooth (climbs, drops at each GC).</li>
 *   <li>{@code oldGenUsedAfterGc} — old-generation occupancy right AFTER the last collection, i.e. the
 *       genuinely-retained live set with the sawtooth removed. A rising value over time is the real
 *       accumulation/leak signal.</li>
 * </ul>
 * {@code gcCount}/{@code gcTimeMillis} (cumulative since start) quantify GC pressure — the client
 * derives a rate from poll-to-poll deltas.
 *
 * @author Bruno Salmon
 */
public final class SystemResourceMonitorInfo {

    // Fraction [0,1] of CPU used by the JVM process (normalised by the container-aware processor
    // count), or -1 when not yet available. Carried on the wire as a per-mille int (no double codec).
    private final double processCpuLoad;
    private final int availableProcessors; // container-aware (cgroup-limited) vCPU count

    private final long heapUsed;           // bytes currently used on the JVM heap
    private final long heapCommitted;      // bytes committed (reserved from the OS) for the heap
    private final long heapMax;            // max heap bytes (-Xmx / MaxRAMPercentage), -1 if undefined
    private final long oldGenUsedAfterGc;  // old-gen live set after the last GC (retained data), -1 if unavailable

    private final long gcCount;            // total garbage collections since start (all collectors)
    private final long gcTimeMillis;       // total time spent in GC since start (all collectors), ms

    public SystemResourceMonitorInfo(double processCpuLoad, int availableProcessors,
                                     long heapUsed, long heapCommitted, long heapMax,
                                     long oldGenUsedAfterGc, long gcCount, long gcTimeMillis) {
        this.processCpuLoad = processCpuLoad;
        this.availableProcessors = availableProcessors;
        this.heapUsed = heapUsed;
        this.heapCommitted = heapCommitted;
        this.heapMax = heapMax;
        this.oldGenUsedAfterGc = oldGenUsedAfterGc;
        this.gcCount = gcCount;
        this.gcTimeMillis = gcTimeMillis;
    }

    public double getProcessCpuLoad() { return processCpuLoad; }
    public int getAvailableProcessors() { return availableProcessors; }
    public long getHeapUsed() { return heapUsed; }
    public long getHeapCommitted() { return heapCommitted; }
    public long getHeapMax() { return heapMax; }
    public long getOldGenUsedAfterGc() { return oldGenUsedAfterGc; }
    public long getGcCount() { return gcCount; }
    public long getGcTimeMillis() { return gcTimeMillis; }
}
