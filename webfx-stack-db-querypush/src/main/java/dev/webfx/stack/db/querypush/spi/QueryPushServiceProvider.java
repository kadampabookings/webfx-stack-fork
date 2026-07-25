package dev.webfx.stack.db.querypush.spi;

import dev.webfx.stack.db.querypush.PulseArgument;
import dev.webfx.stack.db.querypush.QueryPushArgument;
import dev.webfx.stack.db.querypush.QueryPushMonitorInfo;
import dev.webfx.platform.async.Future;

/**
 * @author Bruno Salmon
 */
public interface QueryPushServiceProvider {

    Future<Object> executeQueryPush(QueryPushArgument argument);

    void executePulse(PulseArgument argument);

    /**
     * Returns a monitoring snapshot of the query-push state (registered push clients + active
     * push queries with their subscription details), or null when not available — which is the
     * case on client-side providers, and on the server when the caller isn't a logged-in user.
     * <p>
     * Deliberately NOT a defaulted method: it must be implemented explicitly by every provider,
     * <em>including decorators</em> (e.g. the DQL scope interceptor wrapper), so a decorator that
     * forgets to delegate is a compile error rather than a silent null at runtime.
     */
    QueryPushMonitorInfo getMonitorInfo();

}
