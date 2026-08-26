package dev.webfx.stack.orm.dql.submit.interceptor;

import dev.webfx.platform.async.Batch;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.boot.spi.ApplicationJob;
import dev.webfx.platform.service.SingleServiceProvider;
import dev.webfx.stack.db.datascope.DataScope;
import dev.webfx.stack.db.datascope.KeyDataScope;
import dev.webfx.stack.db.datascope.MultiKeyDataScope;
import dev.webfx.stack.db.datascope.aggregate.AggregateScope;
import dev.webfx.stack.db.datascope.aggregate.AggregateScopeBuilder;
import dev.webfx.stack.db.datascope.schema.SchemaScope;
import dev.webfx.stack.db.datascope.schema.SchemaScopeBuilder;
import dev.webfx.stack.db.datasource.LocalDataSourceService;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry;
import dev.webfx.stack.db.submit.SubmitArgument;
import dev.webfx.stack.db.submit.SubmitResult;
import dev.webfx.stack.db.submit.spi.SubmitServiceProvider;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.domainmodel.DataSourceModel;
import dev.webfx.stack.orm.domainmodel.DomainClass;
import dev.webfx.stack.orm.domainmodel.DomainField;
import dev.webfx.stack.orm.expression.Expression;
import dev.webfx.stack.orm.expression.terms.*;

import java.util.Arrays;

/**
 * @author Bruno Salmon
 */
public class DqlSubmitInterceptorInitializer implements ApplicationJob {

    @Override
    public void onInit() {
        // The purpose of this interceptor is to automatically translate DQL to SQL and compute the schema scope when
        // the submit reaches its local data source (works only with DQL)
        SingleServiceProvider.registerServiceInterceptor(SubmitServiceProvider.class, targetProvider ->
                new SubmitServiceProvider() {
                    @Override
                    public Future<SubmitResult> executeSubmit(SubmitArgument argument) {
                        return interceptAndExecuteSubmit(argument, targetProvider);
                    }
                    @Override
                    public Future<Batch<SubmitResult>> executeSubmitBatch(Batch<SubmitArgument> batch) {
                        return interceptAndExecuteSubmitBatch(batch, targetProvider);
                    }
                });
    }

    private static Future<SubmitResult> interceptAndExecuteSubmit(SubmitArgument argument, SubmitServiceProvider targetProvider) {
        return authorizeIfProtected(argument)
            .compose(ignored -> targetProvider.executeSubmit(translateSubmit(argument)));
    }

    private static Future<Batch<SubmitResult>> interceptAndExecuteSubmitBatch(Batch<SubmitArgument> batch, SubmitServiceProvider targetProvider) {
        // EVERY statement in the batch is authorized, not just the first. A batch is one transaction, so
        // one unauthorized statement anywhere in it is enough to make the whole thing something this
        // caller may not do — and checking only the first would make "hide it behind a legitimate write"
        // the obvious way through.
        Future<Void> authorized = Future.succeededFuture();
        for (SubmitArgument argument : batch.getArray())
            authorized = authorized.compose(ignored -> authorizeIfProtected(argument));
        return authorized.compose(ignored -> targetProvider.executeSubmitBatch(translateBatch(batch)));
    }

    /**
     * Authorizes a write against a protected entity, before it is translated or executed.
     *
     * <p>This sits on the DQL path deliberately: here the statement still names an ENTITY and a verb,
     * which is what a policy is written in terms of. By the time it is SQL those have become a table
     * name and a keyword, and recovering the intent from the text would be both harder and easier to
     * fool. The trade is that a statement which never becomes DQL — a raw passthrough — does not pass
     * this point at all; that door is item 7 and is closed separately.
     *
     * <p>Statements are parsed only when the registry's textual pre-filter says a protected name might
     * be involved, so the common write pays one substring scan rather than a parse.
     */
    private static Future<Void> authorizeIfProtected(SubmitArgument argument) {
        String statement = argument.getStatement();
        if (argument.getLanguage() == null // already SQL: not a DQL statement to reason about
            || !ProtectedEntityWriteRegistry.mayTouchProtectedEntity(statement))
            return Future.succeededFuture();
        Object dataSourceId = argument.getDataSourceId();
        if (!LocalDataSourceService.isDataSourceLocal(dataSourceId))
            return Future.succeededFuture();
        DataSourceModel dataSourceModel = DataSourceModelService.getDataSourceModel(dataSourceId);
        if (dataSourceModel == null)
            return Future.succeededFuture();
        DqlStatement<Object> dqlStatement;
        try {
            dqlStatement = dataSourceModel.parseStatement(statement);
        } catch (RuntimeException e) {
            // Unparseable here but possibly executable later: refuse rather than let something this
            // check could not read reach a protected entity it textually mentions.
            return Future.failedFuture("[NotAuthorizedError] Could not parse a statement naming a protected entity");
        }
        ProtectedEntityWriteRegistry.WriteVerb verb =
              dqlStatement instanceof Insert ? ProtectedEntityWriteRegistry.WriteVerb.INSERT
            : dqlStatement instanceof Update ? ProtectedEntityWriteRegistry.WriteVerb.UPDATE
            : dqlStatement instanceof Delete ? ProtectedEntityWriteRegistry.WriteVerb.DELETE
            : null;
        if (verb == null) // not a write
            return Future.succeededFuture();
        Object domainClass = dqlStatement.getDomainClass();
        DomainClass resolved = domainClass instanceof DomainClass ? (DomainClass) domainClass
            : dataSourceModel.getDomainModel().getClass(domainClass);
        return ProtectedEntityWriteRegistry.checkWriteAllowed(resolved.getName(), verb);
    }

    private static SubmitArgument translateSubmit(SubmitArgument argument) {
        String language = argument.getLanguage();
        Object dataSourceId = argument.getDataSourceId();
        if (language != null && LocalDataSourceService.isDataSourceLocal(dataSourceId)) {
            DataSourceModel dataSourceModel = DataSourceModelService.getDataSourceModel(dataSourceId);
            if (dataSourceModel != null) {
                String statement = argument.getStatement(); // can be DQL or SQL
                String sqlStatement = dataSourceModel.translateStatementIfDql(language, statement);
                if (!statement.equals(sqlStatement)) { // happens when DQL has been translated to SQL
                    //Logger.log("Translated to: " + sqlStatement);
                    argument = SubmitArgument.builder().copy(argument)
                            .setLanguage(null).setStatement(sqlStatement)
                            .addDataScope(createDataScope(statement, dataSourceModel, argument.getParameters()))
                            .build();
                }
            }
        }
        return argument;
    }

    private static Batch<SubmitArgument> translateBatch(Batch<SubmitArgument> batch) {
        return new Batch<>(Arrays.stream(batch.getArray()).map(DqlSubmitInterceptorInitializer::translateSubmit).toArray(SubmitArgument[]::new));
    }

    private static DataScope createDataScope(String dqlSubmitStatement, DataSourceModel dataSourceModel, Object[] parameters) {
        // Returning a wrapper so the scope computation can be skipped if not used later
        // (ex: if intersects method is never called or submit fails)
        return new MultiKeyDataScope() {

            private KeyDataScope[] keyDataScopes;

            @Override
            public KeyDataScope[] getKeyDataScopes() { // Called only if get used
                if (keyDataScopes == null) {
                    DqlStatement<Object> dqlStatement = dataSourceModel.parseStatement(dqlSubmitStatement);
                    DomainClass domainClass = dqlStatement.getDomainClass() instanceof DomainClass ? (DomainClass) dqlStatement.getDomainClass()
                            : dataSourceModel.getDomainModel().getClass(dqlStatement.getDomainClass());
                    Object domainClassId = domainClass.getId();
                    // Building the schema and partition (aggregate) scope. The partition
                    // rules must be SOUND for the rows' state both BEFORE and AFTER the
                    // statement, otherwise queries watching the "old" state miss their
                    // refresh (the historical rooms-drag&drop bug):
                    //  - Update: schema = SET fields; partitions from WHERE equalities on
                    //    fields NOT being modified (their values hold before and after —
                    //    a SET field's OLD value is unknown, so it contributes none).
                    //  - Insert: schema = whole class; partitions from the SET clause
                    //    (the new row's values are the only affected state).
                    //  - Delete: schema = whole class; partitions from WHERE equalities.
                    // Additionally, every written FK WIDENS the schema scope to the
                    // referenced class: PostgreSQL denormalization triggers follow FK
                    // edges (ex: document_line writes update document totals), so the
                    // parent row must be assumed touched — with its identity partition
                    // when the FK value is resolvable, so parent watchers of OTHER rows
                    // can still be skipped.
                    SchemaScopeBuilder ssb = SchemaScope.builder();
                    AggregateScopeBuilder asb = AggregateScope.builder();
                    if (dqlStatement instanceof Update) {
                        Update<Object> update = (Update<Object>) dqlStatement;
                        java.util.Set<Object> setFieldIds = new java.util.HashSet<>();
                        for (Expression<?> expression : update.getSetClause().getExpressions()) {
                            if (expression instanceof Equals && ((Equals<?>) expression).getLeft() instanceof DomainField) {
                                Equals<?> equals = (Equals<?>) expression;
                                DomainField field = (DomainField) equals.getLeft();
                                ssb.addField(field.getDomainClass().getId(), field.getId());
                                setFieldIds.add(field.getId());
                                widenToForeignParent(ssb, asb, field, equals.getRight(), parameters);
                            }
                        }
                        DqlScopeUtil.addPartitions(asb, update.getWhere(), parameters, setFieldIds, domainClassId);
                    } else { // Insert or Delete => all fields of the class are impacted
                        ssb.addClass(domainClassId);
                        if (dqlStatement instanceof Insert) {
                            ExpressionArray<Object> setClause = ((Insert<Object>) dqlStatement).getSetClause();
                            DqlScopeUtil.addPartitions(asb, setClause, parameters, null, domainClassId);
                            for (Expression<?> expression : setClause.getExpressions())
                                if (expression instanceof Equals && ((Equals<?>) expression).getLeft() instanceof DomainField)
                                    widenToForeignParent(ssb, asb, (DomainField) ((Equals<?>) expression).getLeft(), ((Equals<?>) expression).getRight(), parameters);
                        } else if (dqlStatement instanceof Delete) {
                            DqlScopeUtil.addPartitions(asb, dqlStatement.getWhere(), parameters, null, domainClassId);
                            // The deleted rows' FK values are unknown here — widen to every
                            // FK parent class of the model (no identity partitions).
                            for (DomainField field : domainClass.getFields())
                                if (field.getForeignClass() != null)
                                    ssb.addClass(field.getForeignClass().getId());
                        }
                    }
                    SchemaScope schemaScope = ssb.build();
                    AggregateScope aggregateScope = asb.build();
                    // Putting the scopes into the array
                    keyDataScopes = new KeyDataScope[] { schemaScope, aggregateScope };
                }
                return keyDataScopes;
            }
        };
    }

    /**
     * Widens the schema scope to a written FK field's referenced class (trigger
     * cascades follow FK edges), registering the parent row's identity partition
     * when the FK value is a resolvable scalar.
     */
    private static void widenToForeignParent(SchemaScopeBuilder ssb, AggregateScopeBuilder asb, DomainField field, Expression<?> right, Object[] parameters) {
        DomainClass foreignClass = field.getForeignClass();
        if (foreignClass == null)
            return;
        ssb.addClass(foreignClass.getId());
        Object parentId = DqlScopeUtil.resolveScalarValue(right, parameters);
        if (parentId != null)
            asb.addAggregate(DqlScopeUtil.idPartitionType(foreignClass.getId()), parentId);
    }
}
