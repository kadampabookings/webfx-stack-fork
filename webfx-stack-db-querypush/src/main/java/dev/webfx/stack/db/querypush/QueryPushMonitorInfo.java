package dev.webfx.stack.db.querypush;

/**
 * Read-only monitoring snapshot of the query-push server state, as returned by
 * {@link QueryPushService#getMonitorInfo()}: how many clients the push server is currently
 * serving, and the list of push queries with their subscription details. Meant for display
 * in an administration console.
 *
 * @author Bruno Salmon
 */
public final class QueryPushMonitorInfo {

    private final int pushClientsCount; // clients (~ browser tabs) currently registered on the push server
    private final int subscribedUsersCount; // distinct logged-in users holding at least one query-push subscription
    private final QueryStreamMonitorInfo[] queryStreams;

    public QueryPushMonitorInfo(int pushClientsCount, int subscribedUsersCount, QueryStreamMonitorInfo[] queryStreams) {
        this.pushClientsCount = pushClientsCount;
        this.subscribedUsersCount = subscribedUsersCount;
        this.queryStreams = queryStreams;
    }

    public int getPushClientsCount() {
        return pushClientsCount;
    }

    public int getSubscribedUsersCount() {
        return subscribedUsersCount;
    }

    public QueryStreamMonitorInfo[] getQueryStreams() {
        return queryStreams;
    }
}
