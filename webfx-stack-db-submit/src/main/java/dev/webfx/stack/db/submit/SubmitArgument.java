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

    public SubmitArgument(SubmitArgument originalArgument, Object dataSourceId, DataScope dataScope, boolean returnGeneratedKeys, String language, String statement, Object[] parameters) {
        this(originalArgument, dataSourceId, dataScope, returnGeneratedKeys, language, statement, parameters, STANDARD_PRIORITY);
    }

    public SubmitArgument(SubmitArgument originalArgument, Object dataSourceId, DataScope dataScope, boolean returnGeneratedKeys, String language, String statement, Object[] parameters, int priority) {
        this.originalArgument = originalArgument;
        this.dataSourceId = dataSourceId;
        this.dataScope = dataScope;
        this.returnGeneratedKeys = returnGeneratedKeys;
        this.language = language;
        this.statement = statement;
        this.parameters = parameters;
        this.priority = priority;
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

    @Override
    public String toString() {
        return "SubmitArgument('" + statement + (parameters == null ? "'" : "', " + Arrays.toString(parameters)) + ')';
    }

    public static SubmitArgumentBuilder builder() {
        return new SubmitArgumentBuilder();
    }

}
