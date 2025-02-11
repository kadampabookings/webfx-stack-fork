package dev.webfx.stack.orm.entity;

import dev.webfx.platform.async.Batch;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.Arrays;
import dev.webfx.platform.util.tuples.Pair;
import dev.webfx.stack.cache.CacheEntry;
import dev.webfx.stack.db.query.QueryArgument;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.query.QueryService;
import dev.webfx.stack.orm.domainmodel.DataSourceModel;
import dev.webfx.stack.orm.domainmodel.DomainClass;
import dev.webfx.stack.orm.domainmodel.HasDataSourceModel;
import dev.webfx.stack.orm.dql.sqlcompiler.mapping.QueryRowToEntityMapping;
import dev.webfx.stack.orm.dql.sqlcompiler.sql.SqlCompiled;
import dev.webfx.stack.orm.entity.impl.DynamicEntity;
import dev.webfx.stack.orm.entity.impl.EntityStoreImpl;
import dev.webfx.stack.orm.entity.lciimpl.EntityDomainWriter;
import dev.webfx.stack.orm.entity.query_result_to_entities.QueryResultToEntitiesMapper;
import dev.webfx.stack.orm.expression.Expression;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * A store for entities that are transactionally coherent.
 *
 * @author Bruno Salmon
 */
public interface EntityStore extends HasDataSourceModel {

    EntityDomainWriter<Entity> getEntityDataWriter();

    EntityStore getUnderlyingStore();

    default DomainClass getDomainClass(Object domainClassId) {
        return EntityDomainClassIdRegistry.getDomainClass(domainClassId, getDomainModel());
    }

    // EntityId management

    default EntityId getEntityId(Object domainClassId, Object primaryKey) {
        return EntityId.create(getDomainClass(domainClassId), primaryKey);
    }

    void applyEntityIdRefactor(EntityId oldId, EntityId newId);

    default void applyEntityIdRefactor(EntityId oldId, Object newPk) {
        if (!(newPk instanceof EntityId)) {
            newPk = EntityId.create(oldId.getDomainClass(), newPk);
        }
        applyEntityIdRefactor(oldId, (EntityId) newPk);
    }


    // Entity management

    default <E extends Entity> E createEntity(Class<E> entityClass) {
        return createEntity((Object) entityClass);
    }

    default <E extends Entity> E createEntity(Object domainClassId) {
        return createEntity(EntityId.create(getDomainClass(domainClassId)));
    }

    default <E extends Entity> E createEntity(Object domainClassId, Object primaryKey) {
        return primaryKey == null ? null : createEntity(getEntityId(domainClassId, primaryKey));
    }

    <E extends Entity> E createEntity(EntityId id);

    default <E extends Entity> E getEntity(Class<E> entityClass, Object primaryKey) {
        return getEntity(entityClass, primaryKey, false);
    }

    default <E extends Entity> E getEntity(Object domainClassId, Object primaryKey) {
        return getEntity(domainClassId, primaryKey, false);
    }

    default <E extends Entity> E getEntity(EntityId entityId) {
        return getEntity(entityId, false);
    }

    default <E extends Entity> E getEntity(Class<E> entityClass, Object primaryKey, boolean includeUnderlyingStore) {
        return getEntity((Object) entityClass, primaryKey, includeUnderlyingStore);
    }

    default <E extends Entity> E getEntity(Object domainClassId, Object primaryKey, boolean includeUnderlyingStore) {
        return primaryKey == null ? null : getEntity(getEntityId(domainClassId, primaryKey), includeUnderlyingStore);
    }

    <E extends Entity> E getEntity(EntityId entityId, boolean includeUnderlyingStore);

    default <E extends Entity> E getOrCreateEntity(Class<E> entityClass, Object primaryKey) {
        return getOrCreateEntity(entityClass, primaryKey, false);
    }

    default <E extends Entity> E getOrCreateEntity(Object domainClassId, Object primaryKey) {
        return getOrCreateEntity(domainClassId, primaryKey, false);
    }

    default <E extends Entity> E getOrCreateEntity(EntityId id) {
        return getOrCreateEntity(id, false);
    }

    default <E extends Entity> E getOrCreateEntity(Class<E> entityClass, Object primaryKey, boolean includeUnderlyingStore) {
        return getOrCreateEntity((Object) entityClass, primaryKey, includeUnderlyingStore);
    }

    default <E extends Entity> E getOrCreateEntity(Object domainClassId, Object primaryKey, boolean includeUnderlyingStore) {
        return primaryKey == null ? null : getOrCreateEntity(getEntityId(domainClassId, primaryKey), includeUnderlyingStore);
    }

    default <E extends Entity> E getOrCreateEntity(EntityId id, boolean includeUnderlyingStore) {
        if (id == null)
            return null;
        E entity = getEntity(id, includeUnderlyingStore);
        if (entity == null)
            entity = createEntity(id);
        return entity;
    }

    default <E extends Entity> E copyEntity(E entity) {
        if (entity == null)
            return null;
        E copy = getOrCreateEntity(entity.getId(), false); // Ensuring the copy is in this store
        if (copy != entity)
            ((DynamicEntity) copy).copyAllFieldsFrom(entity);
        return copy;
    }


    // EntityList management

    <E extends Entity> EntityList<E> getEntityList(Object listId);

    <E extends Entity> EntityList<E> getOrCreateEntityList(Object listId);

    void clearEntityList(Object listId);


    // Expression evaluation

    default <T> T evaluateEntityExpression(Entity entity, String expression) {
        return evaluateEntityExpression(entity, entity.parseExpression(expression));
    }

    <E extends Entity, T> T evaluateEntityExpression(E entity, Expression<E> expression);

    <E extends Entity> void setEntityExpressionValue(E entity, Expression<E> expression, Object value);

    void setParameterValue(String parameterName, Object parameterValue);

    Object getParameterValue(String parameterName);

    // Query methods

    default <E extends Entity> Future<EntityList<E>> executeQuery(String dqlQuery, Object... parameters) {
        return executeListQuery(null, dqlQuery, parameters);
    }

    default <E extends Entity> Future<EntityList<E>> executeCachedQuery(CacheEntry<Pair<QueryArgument, QueryResult>> cacheEntry, Consumer<EntityList<E>> cacheListConsumer, String dqlQuery, Object... parameters) {
        return executeCachedListQuery(cacheEntry, cacheListConsumer, dqlQuery, dqlQuery, parameters);
    }

    default <E extends Entity> Future<EntityList<E>> executeListQuery(Object listId, String dqlQuery, Object... parameters) {
        return executeCachedListQuery(null, null, listId, dqlQuery, parameters);
    }

    default <E extends Entity> Future<EntityList<E>> executeCachedListQuery(CacheEntry<Pair<QueryArgument, QueryResult>> cacheEntry, Consumer<EntityList<E>> cacheListConsumer, Object listId, String dqlQuery, Object... parameters) {
        QueryArgument queryArgument = createQueryArgument(dqlQuery, parameters);
        Future<QueryResult> future = QueryService.executeQuery(queryArgument);
        QueryRowToEntityMapping queryMapping = getDataSourceModel().parseAndCompileSelect(dqlQuery).getQueryMapping();
        if (cacheEntry != null && cacheListConsumer != null) {
            try {
                Pair<QueryArgument, QueryResult> pair = cacheEntry.getValue();
                if (Objects.equals(queryArgument, pair.get1())) {
                    QueryResult rs = pair.get2();
                    if (rs != null) {
                        Console.log("Restoring cache '" + cacheEntry.getKey() + "'");
                        EntityList<E> entities = QueryResultToEntitiesMapper.mapQueryResultToEntities(rs, queryMapping, this, listId);
                        cacheListConsumer.accept(entities);
                    }
                } else
                    Console.log("Cache for '" + cacheEntry.getKey() + "' can't be used, as its argument was different: " + pair.get1());
            } catch (Exception e) {
                Console.log("WARNING: Restoring '" + cacheEntry.getKey() + "' cache failed: " + e.getMessage());
            }
        }
        return future.map(rs -> {
            EntityList<E> entities = QueryResultToEntitiesMapper.mapQueryResultToEntities(rs, queryMapping, this, listId);
            if (cacheEntry != null)
                cacheEntry.putValue(new Pair<>(queryArgument, rs));
            return entities;
        });
    }

    default <E extends Entity> Future<EntityList<E>> executeQuery(EntityStoreQuery query) {
        return executeListQuery(query.getListId(), query.getSelect(), query.getParameters());
    }

    default QueryArgument createQueryArgument(String dqlQuery, Object[] parameters) {
        return DqlQueryArgumentHelper.createQueryArgument(dqlQuery, parameters, getDataSourceModel(), null);
    }

    default Future<EntityList[]> executeQueryBatch(EntityStoreQuery... queries) {
        Future<Batch<QueryResult>> future = QueryService.executeQueryBatch(new Batch<>(Arrays.map(queries, (i, query) -> createQueryArgument(query.getSelect(), query.getParameters()), QueryArgument[]::new)));
        SqlCompiled[] sqlCompileds = Arrays.map(queries, query -> getDataSourceModel().parseAndCompileSelect(query.getSelect()), SqlCompiled[]::new);
        return future.map(batchResult -> Arrays.map(batchResult.getArray(), (i, rs) -> QueryResultToEntitiesMapper.mapQueryResultToEntities(rs, sqlCompileds[i].getQueryMapping(), this, queries[i].getListId()), EntityList[]::new));
    }

    // String report for debugging

    String getEntityClassesCountReport();


    // Factory

    static EntityStore create(DataSourceModel dataSourceModel) {
        return new EntityStoreImpl(dataSourceModel);
    }

    static EntityStore createAbove(EntityStore underlyingStore) {
        return new EntityStoreImpl(underlyingStore);
    }
}
