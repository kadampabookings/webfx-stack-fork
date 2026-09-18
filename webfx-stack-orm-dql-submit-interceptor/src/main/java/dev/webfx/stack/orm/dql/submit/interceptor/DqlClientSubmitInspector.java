package dev.webfx.stack.orm.dql.submit.interceptor;

import dev.webfx.stack.db.submit.ClientSubmitGuard;
import dev.webfx.stack.db.submit.ClientWriteDenyList;
import dev.webfx.stack.db.submit.SubmitArgument;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.domainmodel.DataSourceModel;
import dev.webfx.stack.orm.domainmodel.DomainClass;
import dev.webfx.stack.orm.domainmodel.DomainField;
import dev.webfx.stack.orm.expression.Expression;
import dev.webfx.stack.orm.expression.terms.Delete;
import dev.webfx.stack.orm.expression.terms.DqlStatement;
import dev.webfx.stack.orm.expression.terms.Equals;
import dev.webfx.stack.orm.expression.terms.ExpressionArray;
import dev.webfx.stack.orm.expression.terms.Insert;
import dev.webfx.stack.orm.expression.terms.Update;

/**
 * Reads a client's DQL write and refuses it when it sets a column in {@link ClientWriteDenyList}.
 *
 * <p>Judged on the domain model's resolution of every assignment — the SQL table and column each field maps to —
 * so a write cannot rephrase its way round the list with an alias or a differently spelled name. And judged
 * FAIL-CLOSED wherever the statement cannot be read field by field: an assignment whose left-hand side is not a
 * plain field, or an insert in a form that has no set clause, on a table that has any denied column, is refused,
 * since it might be setting one. A statement that will not parse at all is refused too; it would not have run.
 *
 * <p>Parses without the cache, like the read inspector: client writes are driven by user actions, in the tens per
 * second at most, and a parse the translation step repeats is cheaper than a second place for a verdict to go
 * stale.
 *
 * @author Claude Code
 */
final class DqlClientSubmitInspector implements ClientSubmitGuard.Inspector {

    @Override
    public String refusalReason(SubmitArgument argument) {
        if (ClientWriteDenyList.isEmpty())
            return null;
        DataSourceModel dataSourceModel = DataSourceModelService.getDataSourceModel(argument.getDataSourceId());
        if (dataSourceModel == null)
            return ClientSubmitGuard.UNCHECKABLE_REFUSED;
        return refusalReason(dataSourceModel, argument.getStatement());
    }

    /**
     * The decision itself, given the model to read the statement against. Package-private so the check in
     * scripts/checks/client-write-guard can drive it with the real domain model and no running server.
     */
    static String refusalReason(DataSourceModel dataSourceModel, String dqlStatement) {
        DqlStatement<Object> statement;
        try {
            statement = dataSourceModel.parseStatement(dqlStatement);
        } catch (RuntimeException e) {
            return ClientSubmitGuard.UNCHECKABLE_REFUSED;
        }
        DomainClass domainClass = statement.getDomainClass() instanceof DomainClass dc ? dc
            : dataSourceModel.getDomainModel().getClass(statement.getDomainClass());
        // A table that is nothing but credentials is closed to every write, deletes included
        if (domainClass != null && ClientWriteDenyList.isTableDenied(domainClass.getSqlTableName())
            && (statement instanceof Insert || statement instanceof Update || statement instanceof Delete))
            return ClientSubmitGuard.DENIED_REFUSED;
        if (!(statement instanceof Insert) && !(statement instanceof Update))
            return null; // a delete sets nothing; anything else is not a write
        boolean guarded = domainClass != null && ClientWriteDenyList.isTableGuarded(domainClass.getSqlTableName());
        ExpressionArray<Object> setClause = statement instanceof Update update ? update.getSetClause()
            : ((Insert<Object>) statement).getSetClause();
        if (setClause == null)
            return guarded ? ClientSubmitGuard.UNCHECKABLE_REFUSED : null;
        for (Expression<?> expression : setClause.getExpressions()) {
            if (expression instanceof Equals<?> equals && equals.getLeft() instanceof DomainField field) {
                DomainClass fieldClass = field.getDomainClass() != null ? field.getDomainClass() : domainClass;
                String table = fieldClass == null ? null : fieldClass.getSqlTableName();
                if (ClientWriteDenyList.isColumnDenied(table, field.getSqlColumnName()))
                    return ClientSubmitGuard.DENIED_REFUSED;
            } else if (guarded) {
                return ClientSubmitGuard.UNCHECKABLE_REFUSED;
            }
        }
        return null;
    }
}
