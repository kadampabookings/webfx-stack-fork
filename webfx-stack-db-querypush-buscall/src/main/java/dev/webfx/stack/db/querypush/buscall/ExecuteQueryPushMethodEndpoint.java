package dev.webfx.stack.db.querypush.buscall;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.db.query.ClientQueryGuard;
import dev.webfx.stack.db.querypush.QueryPushArgument;
import dev.webfx.stack.db.querypush.QueryPushService;

/**
 * @author Bruno Salmon
 */
public final class ExecuteQueryPushMethodEndpoint extends AsyncFunctionBusCallEndpoint<QueryPushArgument, Object> {

    public ExecuteQueryPushMethodEndpoint() {
        super(QueryPushServiceBusAddress.EXECUTE_QUERY_PUSH_METHOD_ADDRESS, arg -> {
            // A subscription is a query the server then re-runs on every relevant change, so it is checked
            // when it is opened — or re-opened with a different statement, which also arrives here. Control
            // messages (pause, resend, close) carry no query and have nothing to check.
            if (arg != null && arg.getQueryArgument() != null) {
                String refusal = ClientQueryGuard.refusalReason(arg.getQueryArgument());
                if (refusal != null)
                    return Future.failedFuture(refusal);
            }
            return QueryPushService.executeQueryPush(arg);
        });
    }
}
