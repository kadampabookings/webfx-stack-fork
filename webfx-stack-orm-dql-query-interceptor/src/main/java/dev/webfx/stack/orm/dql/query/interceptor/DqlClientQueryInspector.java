package dev.webfx.stack.orm.dql.query.interceptor;

import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.datasource.LocalDataSourceService;
import dev.webfx.stack.db.query.ClientQueryGuard;
import dev.webfx.stack.db.query.ClientReadDenyList;
import dev.webfx.stack.db.query.QueryArgument;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.domainmodel.DataSourceModel;
import dev.webfx.stack.orm.dql.sqlcompiler.ExpressionSqlCompiler;
import dev.webfx.stack.orm.dql.sqlcompiler.lci.CompilerDomainModelReader;
import dev.webfx.stack.orm.dql.sqlcompiler.sql.dbms.DbmsSqlSyntax;
import dev.webfx.stack.orm.expression.terms.DqlStatement;
import dev.webfx.stack.orm.expression.terms.Select;
import dev.webfx.stack.orm.expression.terms.Union;
import dev.webfx.stack.orm.expression.terms.WithSelect;

import java.util.HashMap;
import java.util.Map;

/**
 * Enforces {@link ClientReadDenyList} on DQL: compiles each client query a second time, through a reader that
 * refuses the denied tables and columns, and lets it run only if that compilation succeeds.
 *
 * <h3>Why compile again, rather than reuse the real compilation</h3>
 *
 * <p>Because the real one is cached by statement text. The server's own code reads the secret columns — signing
 * somebody in means reading their sign-in code — and a client sending the identical text would be handed that
 * cached compilation without the reader ever being consulted. So this compiles on its own fresh parse, never
 * touches that cache, and keeps a verdict cache of its own that only it can fill.
 *
 * <h3>Mirrors the real compilation exactly, on purpose</h3>
 *
 * <p>Same statement kinds, same flags — generating the query mapping and reading default foreign fields, as the
 * interceptor does. That matters for one case in particular: selecting a bare foreign key loads the target's
 * default fields, so a looser compilation here could approve a query whose real compilation then reaches a column
 * this one never saw.
 *
 * @author Claude Code
 */
final class DqlClientQueryInspector implements ClientQueryGuard.Inspector {

    static final String DENIED_REFUSAL = "This query reads data a client may not";

    /** Statements already found clean, keyed with the deny-list version they were checked against. */
    private final Map<String, Boolean> allowed = new HashMap<>();
    private static final int MAX_ALLOWED = 5_000;

    @Override
    public String refusalReason(QueryArgument argument) {
        if (!"DQL".equalsIgnoreCase(argument.getLanguage()))
            // The interceptor treats any language at all as DQL to compile; a client naming another one has no
            // purpose this check could anticipate, so it is refused rather than guessed at.
            return ClientQueryGuard.RAW_SQL_REFUSED;
        if (ClientReadDenyList.isEmpty())
            return null; // nothing declared secret: nothing to find, and nothing worth compiling twice for
        Object dataSourceId = argument.getDataSourceId();
        if (!LocalDataSourceService.isInitialised() || !LocalDataSourceService.isDataSourceLocal(dataSourceId))
            // Before the data source is up — or for one this server does not own — there is no domain model to
            // compile against, so nothing can be vouched for.
            return ClientQueryGuard.UNCHECKABLE_REFUSED;
        String statement = argument.getStatement();
        boolean compileExpressions = !argument.isHasDqlRuntime();
        String key = ClientReadDenyList.version() + "|" + compileExpressions + "|" + statement;
        synchronized (allowed) {
            if (allowed.containsKey(key))
                return null;
        }
        DataSourceModel dataSourceModel = DataSourceModelService.getDataSourceModel(dataSourceId);
        try {
            compileForClient(dataSourceModel, statement, compileExpressions);
        } catch (ClientReadDeniedException denied) {
            // Named here and nowhere else: the operator needs to know what was reached for, the client does not.
            Console.log("🛡 Client query reached " + denied.getMessage() + " — refused");
            return DENIED_REFUSAL;
        } catch (RuntimeException e) {
            // Not a denial: the statement does not parse or compile. The real compilation would fail the same way,
            // but refusing here rather than letting it through keeps this check the only thing between a client and
            // the database — and the message is the one the real path would have returned anyway.
            return e.getMessage() != null ? e.getMessage() : ClientQueryGuard.UNCHECKABLE_REFUSED;
        }
        synchronized (allowed) {
            if (allowed.size() >= MAX_ALLOWED)
                allowed.clear(); // crude, but bounded; a clean statement costs one extra compile to re-earn
            allowed.put(key, Boolean.TRUE);
        }
        return null;
    }

    /** The dispatch DataSourceModel.compileSelectOrWithSelect performs, with the denying reader in place. */
    // Package-private so the check can drive the exact compilation the inspector uses, against a real domain model.
    static void compileForClient(DataSourceModel dataSourceModel, String statement, boolean compileExpressions) {
        CompilerDomainModelReader reader = new DenyingCompilerDomainModelReader(dataSourceModel.getCompilerDomainModelReader());
        DbmsSqlSyntax syntax = dataSourceModel.getDbmsSqlSyntax();
        DqlStatement<?> parsed = dataSourceModel.parseStatement(statement);
        if (parsed instanceof WithSelect<?> withSelect)
            ExpressionSqlCompiler.compileWithSelect(withSelect, syntax, true, true, compileExpressions, reader);
        else if (parsed instanceof Union<?> union)
            ExpressionSqlCompiler.compileUnion(union, syntax, true, true, compileExpressions, reader);
        else if (parsed instanceof Select<?> select)
            ExpressionSqlCompiler.compileSelect(select, syntax, true, true, compileExpressions, reader);
        else
            // An insert, update or delete sent to the QUERY endpoint. The real path would fail casting it; this
            // refuses it on purpose instead of relying on that.
            throw new IllegalArgumentException("Only a select may be sent as a query");
    }
}
