package dev.webfx.stack.db.submit.buscall;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.db.submit.ClientSubmitGuard;
import dev.webfx.stack.db.submit.SubmitArgument;
import dev.webfx.stack.db.submit.SubmitResult;
import dev.webfx.stack.db.submit.SubmitService;

/**
 * @author Bruno Salmon
 */
public final class ExecuteSubmitMethodEndpoint extends AsyncFunctionBusCallEndpoint<SubmitArgument, SubmitResult> {

    public ExecuteSubmitMethodEndpoint() {
        // Every write arriving here was sent by a client, so it passes the client guard before it runs — see
        // ClientSubmitGuard for what that refuses, and why server code never comes through here.
        super(SubmitMethodAddress.EXECUTE_SUBMIT_METHOD_ADDRESS,
            argument -> ClientSubmitGuard.check(argument).compose(ignored -> SubmitService.executeSubmit(argument)));
    }
}
