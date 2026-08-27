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

    /**
     * Reports a submit that will not be translated, and so reaches the database exactly as written.
     *
     * <p>Called here because this is the last moment the distinction exists. Translation replaces the
     * statement and clears the language, so downstream a translated DQL statement and a hand-written SQL
     * one are indistinguishable — which is precisely why a raw statement has been able to travel this
     * far unexamined.
     */
    private static void reportIfNotDql(SubmitArgument argument) {
        String language = argument.getLanguage();
        if (language == null || !"DQL".equalsIgnoreCase(language))
            ProtectedEntityWriteRegistry.notifyNonDqlSubmit(language, argument.getStatement());
    }

    private static Future<SubmitResult> interceptAndExecuteSubmit(SubmitArgument argument, SubmitServiceProvider targetProvider) {
        reportIfNotDql(argument);
        return authorizeIfProtected(argument)
            .compose(protectedWrite -> targetProvider.executeSubmit(translateSubmit(argument))
                .onSuccess(ignored -> reportIfProtected(protectedWrite)));
    }

    private static Future<Batch<SubmitResult>> interceptAndExecuteSubmitBatch(Batch<SubmitArgument> batch, SubmitServiceProvider targetProvider) {
        // EVERY statement in the batch is authorized, not just the first. A batch is one transaction, so
        // one unauthorized statement anywhere in it is enough to make the whole thing something this
        // caller may not do — and checking only the first would make "hide it behind a legitimate write"
        // the obvious way through.
        java.util.List<ProtectedWrite> protectedWrites = new java.util.ArrayList<>();
        for (SubmitArgument argument : batch.getArray())
            reportIfNotDql(argument);
        Future<Void> authorized = Future.succeededFuture();
        for (SubmitArgument argument : batch.getArray())
            authorized = authorized.compose(ignored -> authorizeIfProtected(argument)
                .map(protectedWrite -> {
                    if (protectedWrite != null)
                        protectedWrites.add(protectedWrite);
                    return null;
                }));
        return authorized.compose(ignored -> targetProvider.executeSubmitBatch(translateBatch(batch))
            .onSuccess(result -> protectedWrites.forEach(DqlSubmitInterceptorInitializer::reportIfProtected)));
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
    /**
     * The fields a statement SETS, so a policy can distinguish the privileged column from its ordinary
     * neighbours on the same row. Empty for a delete, which sets nothing — a delete is judged on the
     * entity alone, and rightly: removing a row disposes of every field on it.
     *
     * <p>Only plain {@code field = value} assignments are reported. A computed or otherwise unreadable
     * left-hand side contributes no name, which means a policy keyed on field names would not match it —
     * so an entity whose fields are individually protected should also carry an entity-level rule, or a
     * statement this cannot read would slip past on a technicality.
     */
    private static java.util.Map<String, Object> writtenValuesOf(DqlStatement<Object> dqlStatement, Object[] parameters) {
        ExpressionArray<Object> setClause =
              dqlStatement instanceof Update ? ((Update<Object>) dqlStatement).getSetClause()
            : dqlStatement instanceof Insert ? ((Insert<Object>) dqlStatement).getSetClause()
            : null;
        if (setClause == null)
            return java.util.Collections.emptyMap();
        // Ordered, so the field list handed to a policy reads in statement order rather than hash order.
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        for (Expression<?> expression : setClause.getExpressions())
            if (expression instanceof Equals equals && equals.getLeft() instanceof DomainField field)
                // A value that is not a resolvable scalar maps to null: the FIELD was written (so a
                // field rule still fires) but its value is unknown to us, which a policy reading values
                // must treat as unknown rather than as absent.
                values.put(field.getName(), DqlScopeUtil.resolveScalarValue(equals.getRight(), parameters));
        return values;
    }

    /**
     * The id of the row a statement changes, or null when that cannot be read.
     *
     * <p>Only a WHERE that is exactly {@code id = value} yields an answer, which covers what the change
     * set layer generates ({@code update Person set … where id=$2}) and deliberately nothing cleverer.
     * Anything else — a compound condition, a subquery, a non-id predicate — returns null.
     *
     * <p>Returning null for what this cannot read is the whole safety of the thing, and only works
     * because null means UNKNOWN to a policy rather than UNCONSTRAINED. An ownership rule must refuse a
     * write whose target it cannot see: a statement shaped to defeat this extraction is exactly the
     * statement that would be used to reach somebody else's row.
     */
    private static Object targetIdOf(DqlStatement<Object> dqlStatement, Object[] parameters) {
        Expression<?> where = dqlStatement.getWhere();
        if (where instanceof Equals equals
            && equals.getLeft() instanceof DomainField field
            && "id".equals(field.getName()))
            return DqlScopeUtil.resolveScalarValue(equals.getRight(), parameters);
        return null;
    }

    private record ProtectedWrite(String entityName, ProtectedEntityWriteRegistry.WriteVerb verb) {}

    /** Reported only AFTER the write actually landed: an attempt that failed changed nothing to react to. */
    private static void reportIfProtected(ProtectedWrite protectedWrite) {
        if (protectedWrite != null)
            ProtectedEntityWriteRegistry.notifyWriteSucceeded(protectedWrite.entityName(), protectedWrite.verb());
    }

    private static Future<ProtectedWrite> authorizeIfProtected(SubmitArgument argument) {
        String statement = argument.getStatement();
        if (argument.getLanguage() == null // already SQL: not a DQL statement to reason about
            || !ProtectedEntityWriteRegistry.mayTouchProtectedEntity(statement))
            return Future.succeededFuture(null);
        Object dataSourceId = argument.getDataSourceId();
        if (!LocalDataSourceService.isDataSourceLocal(dataSourceId))
            return Future.succeededFuture(null);
        DataSourceModel dataSourceModel = DataSourceModelService.getDataSourceModel(dataSourceId);
        if (dataSourceModel == null)
            return Future.succeededFuture(null);
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
            return Future.succeededFuture(null);
        Object domainClass = dqlStatement.getDomainClass();
        DomainClass resolved = domainClass instanceof DomainClass ? (DomainClass) domainClass
            : dataSourceModel.getDomainModel().getClass(domainClass);
        String entityName = resolved.getName();
        Object[] parameters = argument.getParameters();
        java.util.Map<String, Object> writtenValues = writtenValuesOf(dqlStatement, parameters);
        ProtectedEntityWriteRegistry.WriteRequest request = new ProtectedEntityWriteRegistry.WriteRequest(
            entityName, verb,
            writtenValues.keySet().toArray(String[]::new),
            writtenValues,
            targetIdOf(dqlStatement, parameters));
        return ProtectedEntityWriteRegistry.checkWriteAllowed(request)
            .map(ignored -> new ProtectedWrite(entityName, verb));
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
