package dev.webfx.stack.db.querypush;

/**
 * Read (query) and write (submit) SQL execution metrics for the /monitor page — the wire form of
 * {@code SqlExecutionMonitor.Snapshot}. Reads and writes are kept separate because they are
 * different code paths with different cost profiles (and writes drive query-push invalidation).
 *
 * @author Bruno Salmon
 */
public final class SqlExecutionMonitorInfo {

    private final SqlKindMonitorInfo read;
    private final SqlKindMonitorInfo write;
    private final StatementMonitorInfo[] topStatements;      // ranked by total time (drill-down)
    private final InFlightQueryMonitorInfo[] inFlight;       // currently executing (drill-down + cancel targets)

    public SqlExecutionMonitorInfo(SqlKindMonitorInfo read, SqlKindMonitorInfo write,
                                   StatementMonitorInfo[] topStatements, InFlightQueryMonitorInfo[] inFlight) {
        this.read = read;
        this.write = write;
        this.topStatements = topStatements;
        this.inFlight = inFlight;
    }

    public SqlKindMonitorInfo getRead() { return read; }
    public SqlKindMonitorInfo getWrite() { return write; }
    public StatementMonitorInfo[] getTopStatements() { return topStatements; }
    public InFlightQueryMonitorInfo[] getInFlight() { return inFlight; }
}
