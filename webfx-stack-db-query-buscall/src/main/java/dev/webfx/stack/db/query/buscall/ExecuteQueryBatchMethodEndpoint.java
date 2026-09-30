package dev.webfx.stack.db.query.buscall;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.platform.async.Batch;
import dev.webfx.stack.db.query.ClientQueryGuard;
import dev.webfx.stack.db.query.QueryArgument;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.query.QueryService;
import dev.webfx.stack.db.query.ServerCallerParameters;

/**
 * @author Bruno Salmon
 */
public final class ExecuteQueryBatchMethodEndpoint extends AsyncFunctionBusCallEndpoint<Batch<QueryArgument>, Batch<QueryResult>> {

    public ExecuteQueryBatchMethodEndpoint() {
        super(QueryServiceBusAddress.EXECUTE_QUERY_BATCH_METHOD_ADDRESS, batch -> {
            // Checked element by element, and the whole batch refused if any one is: a guard on the single
            // query endpoint alone would be walked round by wrapping the same statement in a batch of one.
            if (batch != null && batch.getArray() != null) {
                QueryArgument[] args = batch.getArray();
                for (QueryArgument arg : args) {
                    String refusal = ClientQueryGuard.refusalReason(arg);
                    if (refusal != null)
                        return Future.failedFuture(refusal);
                }
                // Each element separately, and on THIS thread, for the reason the single-query endpoint
                // gives: the principal is here and will not be where the values are bound. In place, since
                // the array is the server's own copy of what arrived.
                for (int i = 0; i < args.length; i++)
                    args[i] = ServerCallerParameters.attach(args[i]);
            }
            return QueryService.executeQueryBatch(batch);
        });
    }
}
