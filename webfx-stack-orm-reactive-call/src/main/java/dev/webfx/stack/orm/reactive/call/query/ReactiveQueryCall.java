package dev.webfx.stack.orm.reactive.call.query;

import dev.webfx.platform.async.AsyncFunction;
import dev.webfx.platform.util.uuid.Uuid;
import dev.webfx.stack.db.query.QueryArgument;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.query.QueryService;
import dev.webfx.stack.orm.reactive.call.ReactiveCall;

/**
 * @author Bruno Salmon
 */
public class ReactiveQueryCall extends ReactiveCall<QueryArgument, QueryResult> {

    // A globally-unique id per instance — used as the QueryArgument.sourceId so the server-side
    // query queue can drop earlier pending queries from the same ReactiveQueryCall (e.g. the user
    // keeps typing in a search box and the older pending query is no longer of interest).
    private final String sourceId = "rqc-" + Uuid.randomUuid();

    public ReactiveQueryCall() {
        this(QueryService::executeQuery);
    }

    public ReactiveQueryCall(AsyncFunction<QueryArgument, QueryResult> queryFunction) {
        super(queryFunction);
    }

    public String getSourceId() {
        return sourceId;
    }

    @Override
    protected QueryArgument decorateArgument(QueryArgument argument) {
        if (argument == null)
            return null;
        // Caller-set values always win — we only fill in defaults from this ReactiveQueryCall.
        boolean needsSource = argument.getSourceId() == null;
        boolean needsPriority = argument.getPriority() == QueryArgument.STANDARD_PRIORITY
                                && getPriority() != QueryArgument.STANDARD_PRIORITY;
        if (!needsSource && !needsPriority)
            return argument;
        return QueryArgument.builder()
            .copy(argument)
            .setSourceId(needsSource ? sourceId : argument.getSourceId())
            .setPriority(needsPriority ? getPriority() : argument.getPriority())
            .build();
    }
}
