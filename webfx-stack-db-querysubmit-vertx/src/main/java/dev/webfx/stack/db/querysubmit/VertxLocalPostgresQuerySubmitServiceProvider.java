package dev.webfx.stack.db.querysubmit;

import dev.webfx.platform.async.Batch;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.async.util.AsyncQueue;
import dev.webfx.platform.shutdown.Shutdown;
import dev.webfx.platform.util.Arrays;
import dev.webfx.platform.util.vertx.VertxInstance;
import dev.webfx.stack.db.datasource.ConnectionDetails;
import dev.webfx.stack.db.datasource.LocalDataSource;
import dev.webfx.stack.db.query.QueryArgument;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.query.SqlAnalyzeRegistry;
import dev.webfx.stack.db.query.SqlExecutionMonitor;
import dev.webfx.stack.db.query.spi.QueryServiceProvider;
import dev.webfx.stack.db.submit.GeneratedKeyReference;
import dev.webfx.stack.db.submit.SubmitArgument;
import dev.webfx.stack.db.submit.SubmitResult;
import dev.webfx.stack.db.submit.listener.SubmitListenerService;
import dev.webfx.stack.db.submit.spi.SubmitServiceProvider;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import io.vertx.core.net.ClientSSLOptions;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.SslMode;
import io.vertx.sqlclient.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static dev.webfx.platform.util.vertx.VertxAsync.toVertxFuture;
import static dev.webfx.platform.util.vertx.VertxAsync.toWebfxFuture;
import static dev.webfx.stack.db.querysubmit.VertxSqlUtil.*;

/**
 * Note: the same class is used for both the QueryService and the SubmitService, so there will be 2 different instances,
 * one for each service.
 *
 * @author Bruno Salmon
 */
public class VertxLocalPostgresQuerySubmitServiceProvider implements QueryServiceProvider, SubmitServiceProvider {

    private static final int QUERY_POOL_SIZE = 20;
    private static final int SUBMIT_POOL_SIZE = 10;
    private static final boolean LOG_TIMINGS = true;
    private static final long SQL_OPERATION_WARNING_MILLIS = 5_000;
    private static final long SQL_OPERATION_TIMEOUT_MILLIS = 5 * 60 * 1000; // 5-minute timeout for SQL operations

    private final AsyncQueue asyncQueue;
    private final Pool pool;
    private final SqlExecutionMonitor.Kind monitorKind; // READ for queries, WRITE for submits

    public VertxLocalPostgresQuerySubmitServiceProvider(LocalDataSource localDataSource, boolean submit) {
        int poolSize = submit ? SUBMIT_POOL_SIZE : QUERY_POOL_SIZE;
        asyncQueue = new AsyncQueue(poolSize, "POSTGRES-" + (submit ? "SUBMIT" : "QUERY"))
            .setExecutionTimeout(SQL_OPERATION_TIMEOUT_MILLIS);
        // Register this queue for /monitor so the snapshot can read its live depth, and remember
        // the kind so completed operations are counted as reads (queries) or writes (submits).
        monitorKind = submit ? SqlExecutionMonitor.Kind.WRITE : SqlExecutionMonitor.Kind.READ;
        SqlExecutionMonitor.get().registerQueue(monitorKind, asyncQueue);

        ConnectionDetails cd = localDataSource.getLocalConnectionDetails();
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setPort(cd.getPort())
            .setHost(cd.getHost())
            .setDatabase(cd.getDatabaseName())
            .setUser(cd.getUsername())
            .setPassword(cd.getPassword());
        // Use TLS when the server offers it: AWS RDS enforces rds.force_ssl=1 so the connection must
        // be encrypted, while PREFER still falls back to plaintext for servers that don't support SSL
        // (e.g. local dev / the current LiquidWeb host) — so this is a safe universal default.
        connectOptions.setSslMode(SslMode.PREFER);
        // RDS's server certificate is signed by the Amazon RDS CA, which isn't in the JVM's default
        // trust store. Without trust config, Vert.x 5 fails cert verification and PREFER silently falls
        // back to plaintext (which RDS then rejects with "no encryption"). trustAll keeps the connection
        // encrypted without verifying the chain (≈ sslmode=require). To harden to full verification,
        // replace setTrustAll(true) with setTrustOptions(new PemTrustOptions().addCertPath("rds-ca.pem"))
        // and SslMode.VERIFY_FULL.
        connectOptions.setSslOptions(new ClientSSLOptions().setTrustAll(true));

        // Pool Options
        PoolOptions poolOptions = new PoolOptions()
            .setMaxSize(poolSize)
            .setIdleTimeout(30) // We release the connection after 30 min of inactivity (especially for remote databases)
            .setIdleTimeoutUnit(TimeUnit.MINUTES);

        // Create the pool from the data object
        log("Creating pool on server start");
        pool = PgBuilder.pool()
            .with(poolOptions)
            .connectingTo(connectOptions)
            .using(VertxInstance.getVertx())
            .build();

        // Closing properly the poll on the server shutdown
        Shutdown.addShutdownHook(e -> {
            log("Closing pool on server shutdown");
            pool.close();
        });
    }

    @Override
    public Future<QueryResult> executeQuery(QueryArgument argument) {
        return asyncQueue.addAsyncOperation(argument, argument.getPriority(), coalescingKey(argument, ThreadLocalStateHolder.getRunId()), this::executeQueryNow);
    }

    /**
     * Build the AsyncQueue coalescing key from the caller's callId and the client's runId.
     * Returns {@code null} when either is missing — bare callIds could collide across clients
     * on the shared server-side queue, so unknown clients get no coalescing rather than wrong
     * coalescing.
     */
    private static Object coalescingKey(QueryArgument argument, Object runId) {
        int callId = argument.getCallId();
        if (callId == 0 || runId == null)
            return null;
        return runId + ":" + callId;
    }

    private Future<QueryResult> executeQueryNow(QueryArgument argument) {
        long t0 = System.currentTimeMillis();
        long t0n = System.nanoTime();
        return toWebfxFuture(withConnection(pool, connection -> executeConnectionQuery(connection, argument)))
            .onFailure(e -> log("⛔️ ERROR with executeQuery(" + argument + "): " + e.getMessage()))
            .onSuccess(x -> { // Just for time report
                if (LOG_TIMINGS) {
                    long executionTimeMillis = System.currentTimeMillis() - t0;
                    log((executionTimeMillis < SQL_OPERATION_WARNING_MILLIS ? "" : "⚠️ WARNING: ") + "Query executed in " + executionTimeMillis + "ms: " + argument);
                }
            })
            .onComplete(ar -> SqlExecutionMonitor.get().record(monitorKind, System.nanoTime() - t0n, ar.succeeded()));
    }

    @Override
    public Future<Batch<QueryResult>> executeQueryBatch(Batch<QueryArgument> batch) {
        // Use the first argument's priority/callId as the batch metadata. By convention all
        // QueryArguments inside a batch originate from the same caller so they share the same
        // priority and source.
        QueryArgument[] arr = batch.getArray();
        int priority = arr.length > 0 ? arr[0].getPriority() : QueryArgument.STANDARD_PRIORITY;
        Object key = arr.length > 0 ? coalescingKey(arr[0], ThreadLocalStateHolder.getRunId()) : null;
        return asyncQueue.addAsyncOperation(batch, priority, key, this::executeQueryBatchNow);
    }

    private Future<Batch<QueryResult>> executeQueryBatchNow(Batch<QueryArgument> batch) {
        long t0 = System.currentTimeMillis();
        long t0n = System.nanoTime();
        return toWebfxFuture(withConnection(pool, connection ->
            toVertxFuture(batch.executeSerial(QueryResult[]::new, arg ->
                toWebfxFuture(executeConnectionQuery(connection, arg))))
        ))
            .onFailure(e -> log("⛔️ ERROR with executeQueryBatch(" + batch + "): " + e.getMessage()))
            .onSuccess(x -> { // Just for time report
                if (LOG_TIMINGS) {
                    long executionTimeMillis = System.currentTimeMillis() - t0;
                    log((executionTimeMillis < SQL_OPERATION_WARNING_MILLIS ? "" : "⚠️ WARNING: ") + "Query batch executed in " + executionTimeMillis + "ms");
                }
            })
            .onComplete(ar -> SqlExecutionMonitor.get().record(monitorKind, System.nanoTime() - t0n, ar.succeeded()));
    }

    private io.vertx.core.Future<QueryResult> executeConnectionQuery(SqlConnection connection, QueryArgument argument) {
        // Register this actual SQL execution in the monitor (statement + cancel handle), for the
        // /monitor in-flight list + per-statement rollup; deregister on completion.
        long monId = SqlExecutionMonitor.get().onStart(monitorKind, argument.getStatement(), pgCancelHandle(connection));
        // If an admin armed this statement for analyze, capture its real params and EXPLAIN it
        // (separate connection — the real query below is untouched). Reads only, by construction:
        // this is the query path (monitorKind == READ); the submit path never analyzes.
        maybeAnalyzeQuery(argument);
        return connection
            .preparedQuery(argument.getStatement())
            .execute(tupleFromArguments(argument.getParameters()))
            .onComplete(ar -> SqlExecutionMonitor.get().onEnd(monId))
            .map(rs -> {
                QueryResult result = VertxSqlUtil.toWebFxQueryResult(rs);
                // Echo the caller's fire sequence so the client can discard stale results when
                // multiple fires from the same source race on the network.
                result.setCallSeq(argument.getCallSeq());
                return result;
            });
    }

    /**
     * If {@code argument}'s statement was armed for analysis (via {@link SqlAnalyzeRegistry}), run
     * {@code EXPLAIN (ANALYZE, BUFFERS)} on it with these real parameters on a fresh pool connection
     * and store the plan for the /monitor poll to collect. Fire-and-forget: it never affects the
     * real query. {@code EXPLAIN ANALYZE} executes the query, but this path is reads-only so it has
     * no side effects (one extra run of the query, admin-initiated and one-shot). The statement is
     * the server's own — the arm only matches statements the server actually runs — so this never
     * runs arbitrary SQL.
     */
    private void maybeAnalyzeQuery(QueryArgument argument) {
        SqlAnalyzeRegistry registry = SqlAnalyzeRegistry.get();
        if (!registry.hasArmed()) // hot-path guard: no map lookup when nothing is armed
            return;
        String statement = argument.getStatement();
        if (!registry.claimIfArmed(statement, System.currentTimeMillis()))
            return;
        Object[] parameters = argument.getParameters();
        String parametersDisplay = parameters == null || parameters.length == 0 ? null : java.util.Arrays.toString(parameters);
        withConnection(pool, c -> c.preparedQuery("EXPLAIN (ANALYZE, BUFFERS) " + statement).execute(tupleFromArguments(parameters)))
            .onComplete(ar -> {
                String plan;
                if (ar.succeeded()) {
                    StringBuilder sb = new StringBuilder();
                    for (Row row : ar.result())
                        sb.append(row.getString(0)).append('\n');
                    plan = sb.toString();
                } else {
                    plan = "EXPLAIN failed: " + ar.cause();
                    log("⚠️ WARNING: analyze EXPLAIN failed for statement: " + statement + " — " + ar.cause());
                }
                registry.storeResult(statement, parametersDisplay, plan, System.currentTimeMillis());
            });
    }

    /**
     * Best-effort cancel action for an executing statement, stored opaquely by the monitor as the
     * in-flight query's cancel handle. We return a client-safe {@link Runnable} (not the
     * PgConnection) so the monitor module that triggers cancellation stays free of any Vert.x
     * pg-client dependency. {@code PgConnection.cancelRequest()} is out-of-band — it opens a fresh
     * connection and asks PostgreSQL to cancel the query running on this backend; it is advisory
     * (the query may finish first) and a cancelled query fails with SQLSTATE 57014, completing its
     * future exceptionally (so {@code onEnd} still runs). Returns null for non-PG connections.
     */
    private Runnable pgCancelHandle(SqlConnection connection) {
        try {
            io.vertx.pgclient.PgConnection pg = io.vertx.pgclient.PgConnection.cast(connection);
            return () -> pg.cancelRequest().onFailure(t -> log("⚠️ WARNING: query cancelRequest failed: " + t));
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public Future<SubmitResult> executeSubmit(SubmitArgument argument) {
        // Submits get priority but no source-based coalescing (sourceId=null): cancelling a pending
        // submit because a newer same-source one arrived would drop side-effecting work.
        return asyncQueue.addAsyncOperation(argument, argument.getPriority(), null, this::executeSubmitNow);
    }

    private Future<SubmitResult> executeSubmitNow(SubmitArgument argument) {
        // Note: executeIndividualSubmitWithConnection() already logs failure and timing
        long t0n = System.nanoTime();
        return toWebfxFuture(withConnection(pool, connection -> executeIndividualSubmitWithConnection(argument, connection, null)))
            .onComplete(ar -> SqlExecutionMonitor.get().record(monitorKind, System.nanoTime() - t0n, ar.succeeded()));
    }

    @Override
    public Future<Batch<SubmitResult>> executeSubmitBatch(Batch<SubmitArgument> batch) {
        // Same convention as executeQueryBatch: use the first arg's priority. No source coalescing
        // on submits — see executeSubmit() for the rationale.
        SubmitArgument[] arr = batch.getArray();
        int priority = arr.length > 0 ? arr[0].getPriority() : SubmitArgument.STANDARD_PRIORITY;
        return asyncQueue.addAsyncOperation(batch, priority, null, this::executeSubmitBatchNow);
    }

    private Future<Batch<SubmitResult>> executeSubmitBatchNow(Batch<SubmitArgument> batch) {
        // This batch may use GeneratedKeyReference instances in its parameters, which we will need to resolve during
        // the execution. To do so, we create batchIndexGeneratedKeys, which is a list of generated keys for each
        // SubmitArgument in the batch (index 0 will contain the possible generated keys from the execution of the first
        // SubmitArgument, index 1 from the second, etc...)
        List<Object[]> batchIndexGeneratedKeys = new ArrayList<>(batch.getArray().length);
        long t0 = System.currentTimeMillis();
        long t0n = System.nanoTime();
        // We embed the batch execution inside a transaction using Vert.x API (and convert the return Vert.x Future<SubmitResult> into WebFX Future<SubmitResult>)
        return toWebfxFuture(withTransaction(pool, connection ->
            // We execute the batch in a serial order (we need a couple of Vert.x <-> WebFX Future for that)
            toVertxFuture(batch.executeSerial(SubmitResult[]::new, arg -> toWebfxFuture(
                // We execute this individual submission, passing batchIndexGeneratedKeys (for GeneratedKeyReference resolution)
                executeIndividualSubmitWithConnection(arg, connection, batchIndexGeneratedKeys)
                    .map(submitResult -> { // Identity mapping, just for batchIndexGeneratedKeys management
                        // We collect the possible generated keys (if the last submission was "insert ... returning id")
                        batchIndexGeneratedKeys.add(submitResult.getGeneratedKeys());
                        return submitResult;
                    })
            )))
        )).onSuccess(x -> { // Just for time report
            if (LOG_TIMINGS) {
                long executionTimeMillis = System.currentTimeMillis() - t0;
                log((executionTimeMillis < SQL_OPERATION_WARNING_MILLIS ? "" : "⚠️ WARNING: ") + "Submit batch executed in " + executionTimeMillis + "ms");
            }
            onSuccessfulSubmitBatch(batch);
        }).onComplete(ar -> SqlExecutionMonitor.get().record(monitorKind, System.nanoTime() - t0n, ar.succeeded()));
    }

    private static void onSuccessfulSubmitBatch(Batch<SubmitArgument> batch) {
        SubmitListenerService.fireSuccessfulSubmit(batch.getArray());
    }


    private io.vertx.core.Future<SubmitResult> executeIndividualSubmitWithConnection(SubmitArgument argument, SqlConnection connection, List<Object[]> batchIndexGeneratedKeys) {
        // Register this SQL execution in the monitor (statement + cancel handle); deregister on completion.
        long monId = SqlExecutionMonitor.get().onStart(monitorKind, argument.getStatement(), pgCancelHandle(connection));
        // We get a prepared query from the connection
        PreparedQuery<RowSet<Row>> preparedQuery = connection
            .preparedQuery(argument.getStatement()); // statement can be insert, update or delete
        // We will execute the query with either a Tuple (1 row of parameters) or a List<Tuple> (several rows of parameters)
        io.vertx.core.Future<RowSet<Row>> queryExecutionFuture; // Will contain the Vert.x Future of that execution
        Object[] parameters = argument.getParameters();
        // In case there are several rows of parameters (i.e., batch of parameters)
        if (Arrays.length(parameters) == 1 && parameters[0] instanceof Batch) {
            // We get the rows of parameters from the batch. The returned array Object[] represents rows, and each row
            // contains the parameters of that row (so it's also an array of Object[])
            Object[] parametersRows = ((Batch<?>) parameters[0]).getArray();
            // For each row, we replace the GeneratedKeyReference instances with their actual generated keys (should be known as this stage)
            Arrays.forEach(parametersRows, row -> replaceGeneratedKeyReferencesWithActualGeneratedKeys((Object[]) row, batchIndexGeneratedKeys));
            // We map the row array into a Vert.x Tuple array
            Tuple[] tuples = Arrays.map(parametersRows, params -> tupleFromArguments((Object[]) params), Tuple[]::new);
            // And finally execute that batch (passing the Tuples as a list)
            queryExecutionFuture = preparedQuery.executeBatch(Arrays.asList(tuples));
        } else { // Case a single row of parameters
            // We replace the GeneratedKeyReference instances with their actual generated keys (should be known as this stage)
            replaceGeneratedKeyReferencesWithActualGeneratedKeys(parameters, batchIndexGeneratedKeys);
            // We execute that query (passing the arguments as a Vert.x Tuple)
            queryExecutionFuture = preparedQuery.execute(tupleFromArguments(parameters));
        }
        // Waiting for the completion of the previous query execution
        long t0 = System.currentTimeMillis();
        return queryExecutionFuture
            .onComplete(ar -> SqlExecutionMonitor.get().onEnd(monId))
            .onFailure(e -> log("⛔️ ERROR with executeIndividualSubmitWithConnection(" + argument + "): " + e.getMessage()))
            .map(rs -> { // on success, returns rs as a Vert.x RowSet<Row>
                if (LOG_TIMINGS) {
                    long executionTimeMillis = System.currentTimeMillis() - t0;
                    log((executionTimeMillis < SQL_OPERATION_WARNING_MILLIS ? "" : "⚠️ WARNING: ") + "Submit executed in " + executionTimeMillis + "ms: " + argument);
                }
                onSuccessfulSubmit(argument);
                // We convert that Vert.x RowSet into a WebFX SubmitResult
                return toWebFxSubmitResult(rs, argument);
            });
    }

    private static void onSuccessfulSubmit(SubmitArgument argument) {
        SubmitListenerService.fireSuccessfulSubmit(argument);
    }

    private static void replaceGeneratedKeyReferencesWithActualGeneratedKeys(Object[] parameters, List<Object[]> batchIndexGeneratedKeys) {
        if (batchIndexGeneratedKeys != null) {
            for (int i = 0, length = Arrays.length(parameters); i < length; i++) {
                Object value = parameters[i];
                if (value instanceof GeneratedKeyReference ref) {
                    // Getting the indexes (should normally refer to a previous batch already executed at this point)
                    int batchIndex = ref.getStatementBatchIndex();
                    int generatedKeyIndex = ref.getGeneratedKeyIndex();
                    // We get the actual generated key that `ref` was referring to and replace the parameter value with it
                    Object[] generatedKeys = batchIndexGeneratedKeys.get(batchIndex);
                    parameters[i] = generatedKeys[generatedKeyIndex];
                }
            }
        }
    }

    private void log(String message) {
        asyncQueue.log(message);
    }

}
