package dev.webfx.stack.db.submit.buscall;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.platform.async.Batch;
import dev.webfx.stack.db.submit.ClientSubmitGuard;
import dev.webfx.stack.db.submit.SubmitArgument;
import dev.webfx.stack.db.submit.SubmitResult;
import dev.webfx.stack.db.submit.SubmitService;

/**
 * @author Bruno Salmon
 */
public final class ExecuteSubmitBatchMethodEndpoint extends AsyncFunctionBusCallEndpoint<Batch<SubmitArgument>, Batch<SubmitResult>> {

    public ExecuteSubmitBatchMethodEndpoint() {
        // Every statement of a client's batch passes the client guard before any of it runs — see
        // ClientSubmitGuard.checkBatch.
        super(SubmitMethodAddress.EXECUTE_SUBMIT_BATCH_METHOD_ADDRESS,
            batch -> ClientSubmitGuard.checkBatch(batch).compose(ignored -> SubmitService.executeSubmitBatch(batch)));
    }
}
