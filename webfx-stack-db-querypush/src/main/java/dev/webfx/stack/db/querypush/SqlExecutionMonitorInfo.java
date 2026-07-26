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

    public SqlExecutionMonitorInfo(SqlKindMonitorInfo read, SqlKindMonitorInfo write) {
        this.read = read;
        this.write = write;
    }

    public SqlKindMonitorInfo getRead() { return read; }
    public SqlKindMonitorInfo getWrite() { return write; }
}
