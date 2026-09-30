package dev.webfx.stack.db.querypush.buscall;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.db.query.ClientQueryGuard;
import dev.webfx.stack.db.querypush.QueryPushArgument;
import dev.webfx.stack.db.query.QueryArgument;
import dev.webfx.stack.db.query.ServerCallerParameters;
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
                // Resolved once, when the subscription is opened, while the principal is still on the thread.
                // A re-fire has no client message and therefore no caller, so the value has to be part of what
                // the subscription stores — and it is, because it goes into the parameters, which is also what
                // keys one subscription apart from another's.
                QueryArgument resolved = ServerCallerParameters.attach(arg.getQueryArgument());
                if (resolved != arg.getQueryArgument())
                    arg = QueryPushArgument.builder().copy(arg).setQueryArgument(resolved).build();
            }
            return QueryPushService.executeQueryPush(arg);
        });
    }
}
