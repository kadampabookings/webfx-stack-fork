package dev.webfx.stack.db.submit;

import dev.webfx.platform.util.Arrays;
import dev.webfx.stack.db.datascope.DataScope;

/**
 * @author Bruno Salmon
 */
public final class SubmitArgument {

    public static final int STANDARD_PRIORITY = 0;

    /**
     * Non-interactive background work that should yield to user-triggered submits.
     * Use for fire-and-forget telemetry such as media-consumption recording, analytics
     * pings, or other low-stakes writes whose latency the user never observes.
     */
    public static final int BACKGROUND_PRIORITY = -10;


    private final transient SubmitArgument originalArgument;
    private final Object dataSourceId;
    private final DataScope dataScope;
    private final boolean returnGeneratedKeys;
    private final String language;
    private final String statement;
    private final Object[] parameters;
    private final int priority; // execution priority for the server-side AsyncQueue; higher = sooner; STANDARD_PRIORITY (0) is the default
    private final boolean transactionPreamble; // true = "open this transaction with the application's preamble"; see isTransactionPreamble()

    public SubmitArgument(SubmitArgument originalArgument, Object dataSourceId, DataScope dataScope, boolean returnGeneratedKeys, String language, String statement, Object[] parameters) {
        this(originalArgument, dataSourceId, dataScope, returnGeneratedKeys, language, statement, parameters, STANDARD_PRIORITY);
    }

    public SubmitArgument(SubmitArgument originalArgument, Object dataSourceId, DataScope dataScope, boolean returnGeneratedKeys, String language, String statement, Object[] parameters, int priority) {
        this(originalArgument, dataSourceId, dataScope, returnGeneratedKeys, language, statement, parameters, priority, false);
    }

    public SubmitArgument(SubmitArgument originalArgument, Object dataSourceId, DataScope dataScope, boolean returnGeneratedKeys, String language, String statement, Object[] parameters, int priority, boolean transactionPreamble) {
        this.originalArgument = originalArgument;
        this.dataSourceId = dataSourceId;
        this.dataScope = dataScope;
        this.returnGeneratedKeys = returnGeneratedKeys;
        this.language = language;
        this.statement = statement;
        this.parameters = parameters;
        this.priority = priority;
        this.transactionPreamble = transactionPreamble;
    }

    public SubmitArgument getOriginalArgument() {
        return originalArgument;
    }

    public Object getDataSourceId() {
        return dataSourceId;
    }

    public DataScope getDataScope() {
        return dataScope;
    }

    public boolean returnGeneratedKeys() {
        return returnGeneratedKeys;
    }

    public String getLanguage() {
        return language;
    }

    public String getStatement() {
        return statement;
    }

    public Object[] getParameters() {
        return parameters;
    }

    /** Execution priority used by the server's submit queue. Higher = sooner. Default is {@link #STANDARD_PRIORITY}. */
    public int getPriority() {
        return priority;
    }

    /**
     * True when this entry is a request to open the transaction with the application's preamble,
     * rather than a statement to run. {@link #getStatement()} and {@link #getParameters()} are then
     * ignored: the server substitutes the SQL registered in
     * dev.webfx.stack.session.state.TransactionPreambleRegistry.
     *
     * <p>The split exists because "does this transaction need a preamble" and "what does the preamble
     * say" are different questions with different owners. The first is correctness, and the caller
     * knows the answer — guessing it wrong fails loudly, since the database raises when a trigger reads
     * a setting nobody made. The second is privilege: the preamble is what decides which trigger checks
     * the transaction skips, so only the server may answer it. A client used to send the SQL itself,
     * which meant it answered both.
     *
     * <p>It stays an ENTRY rather than becoming a flag on the batch because batch position is
     * load-bearing: {@link GeneratedKeyReference} resolves by index into the per-argument generated-key
     * list, so a preamble the client had not accounted for would shift every reference by one. The
     * client reserves the slot; the server replaces only its contents, leaving indices untouched.
     */
    public boolean isTransactionPreamble() {
        return transactionPreamble;
    }

    @Override
    public String toString() {
        return "SubmitArgument('" + statement + (parameters == null ? "'" : "', " + Arrays.toString(parameters)) + ')';
    }

    public static SubmitArgumentBuilder builder() {
        return new SubmitArgumentBuilder();
    }

}
