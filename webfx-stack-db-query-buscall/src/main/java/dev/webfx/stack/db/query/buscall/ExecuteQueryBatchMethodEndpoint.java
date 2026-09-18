package dev.webfx.stack.db.query.buscall;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.platform.async.Batch;
import dev.webfx.stack.db.query.ClientQueryGuard;
import dev.webfx.stack.db.query.QueryArgument;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.query.QueryService;

/**
 * @author Bruno Salmon
 */
public final class ExecuteQueryBatchMethodEndpoint extends AsyncFunctionBusCallEndpoint<Batch<QueryArgument>, Batch<QueryResult>> {

    public ExecuteQueryBatchMethodEndpoint() {
        super(QueryServiceBusAddress.EXECUTE_QUERY_BATCH_METHOD_ADDRESS, batch -> {
            // Checked element by element, and the whole batch refused if any one is: a guard on the single
            // query endpoint alone would be walked round by wrapping the same statement in a batch of one.
            if (batch != null && batch.getArray() != null)
                for (QueryArgument arg : batch.getArray()) {
                    String refusal = ClientQueryGuard.refusalReason(arg);
                    if (refusal != null)
                        return Future.failedFuture(refusal);
                }
            return QueryService.executeQueryBatch(batch);
        });
    }
}
