package dev.webfx.stack.db.query.spi.impl;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.datasource.LocalDataSource;
import dev.webfx.stack.db.query.QueryArgument;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.query.spi.QueryServiceProvider;

/**
 * @author Bruno Salmon
 */
public class LocalQueryServiceProvider implements QueryServiceProvider {

    @Override
    public Future<QueryResult> executeQuery(QueryArgument argument) {
        Object dataSourceId = argument.getDataSourceId();
        String queryString = argument.getStatement();
        // Only the count, never the values — the bind values are the caller's own data (emails,
        // names, booking free text), and this line runs on every query on its way to the log file
        // and stdout, which production ships to a log store outside the database.
        Object[] parameters = argument.getParameters();
        Console.log("Query: " + queryString + (parameters == null ? "" : "\nParameters: " + parameters.length + " value(s)"));
        QueryServiceProvider localConnectedProvider = getOrCreateLocalConnectedProvider(dataSourceId);
        if (localConnectedProvider != null)
            return localConnectedProvider.executeQuery(argument);
        return executeRemoteQuery(argument);
    }

    protected QueryServiceProvider getOrCreateLocalConnectedProvider(Object dataSourceId) {
        QueryServiceProvider localConnectedProvider = LocalConnectedQueryServiceProviderRegistry.getLocalConnectedProvider(dataSourceId);
        if (localConnectedProvider == null) {
            LocalDataSource localDataSource = LocalDataSource.get(dataSourceId);
            if (localDataSource != null) {
                localConnectedProvider = createLocalConnectedProvider(localDataSource);
                LocalConnectedQueryServiceProviderRegistry.registerLocalConnectedProvider(dataSourceId, localConnectedProvider);
            }
        }
        return localConnectedProvider;
    }

    protected QueryServiceProvider createLocalConnectedProvider(LocalDataSource localDataSource) {
        throw new UnsupportedOperationException("This platform doesn't support local query service");
    }

    protected <T> Future<T> executeRemoteQuery(QueryArgument argument) {
        throw new UnsupportedOperationException("This platform doesn't support remote query service");
    }
}
