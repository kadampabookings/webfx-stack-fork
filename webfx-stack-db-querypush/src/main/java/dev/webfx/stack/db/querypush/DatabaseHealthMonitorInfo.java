package dev.webfx.stack.db.querypush;

/**
 * Read-only database health snapshot for the /monitor "Database" drill-down, built from a couple of
 * {@code pg_stat_activity} queries: the connection count (active / idle / total vs {@code
 * max_connections}) and the list of non-idle / long-running / blocking backends. Fetched on demand
 * when the drill-down opens (NOT on the regular monitor poll), since it queries the database.
 *
 * @author Bruno Salmon
 */
public final class DatabaseHealthMonitorInfo {

    private final int maxConnections;      // server's max_connections setting (-1 if unknown)
    private final int totalConnections;    // total backends currently connected (all clients)
    private final int activeConnections;   // backends with state = 'active'
    private final int idleConnections;     // backends with state = 'idle'
    // Client backends whose state reads NULL, i.e. sessions this database role is not allowed to
    // inspect (Postgres blanks state/query/query_start unless you are a superuser or a member of
    // pg_read_all_stats / pg_monitor). Their queries CANNOT appear in activeQueries, so a non-zero
    // value here is the honest caveat on that list: "N backends exist that I cannot show you".
    private final int notInspectableConnections;
    private final ActiveDbQueryInfo[] activeQueries; // non-idle backends, worst-first

    public DatabaseHealthMonitorInfo(int maxConnections, int totalConnections, int activeConnections,
                                     int idleConnections, int notInspectableConnections,
                                     ActiveDbQueryInfo[] activeQueries) {
        this.maxConnections = maxConnections;
        this.totalConnections = totalConnections;
        this.activeConnections = activeConnections;
        this.idleConnections = idleConnections;
        this.notInspectableConnections = notInspectableConnections;
        this.activeQueries = activeQueries;
    }

    public int getMaxConnections() { return maxConnections; }
    public int getTotalConnections() { return totalConnections; }
    public int getActiveConnections() { return activeConnections; }
    public int getIdleConnections() { return idleConnections; }
    public int getNotInspectableConnections() { return notInspectableConnections; }
    public ActiveDbQueryInfo[] getActiveQueries() { return activeQueries; }
}
