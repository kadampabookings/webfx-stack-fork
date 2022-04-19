package dev.webfx.framework.client.orm.reactive.mapping.entities_to_grid;

import dev.webfx.framework.client.orm.reactive.mapping.dql_to_entities.ReactiveEntitiesMapper;
import dev.webfx.framework.shared.orm.entity.Entity;
import dev.webfx.framework.shared.orm.entity.EntityList;
import dev.webfx.platform.shared.async.Handler;

import java.util.List;

/**
 * @author Bruno Salmon
 */
public interface ReactiveGridMapperAPI<E extends Entity, THIS> {

    ReactiveGridMapper<E> getReactiveColumnMapper();

    default ReactiveEntitiesMapper<E> getReactiveEntitiesMapper() {
        return getReactiveColumnMapper().getReactiveEntitiesMapper();
    }

    default EntityColumn<E>[] getEntityColumns() {
        return getReactiveColumnMapper().getEntityColumns();
    }

    default EntityList<E> getCurrentEntityList() {
        return getReactiveColumnMapper().getCurrentEntities();
    }

    default List<E> getSelectedEntities() {
        return getReactiveColumnMapper().getSelectedEntities();
    }

    default E getSelectedEntity() {
        return getReactiveColumnMapper().getSelectedEntity();
    }

    default THIS autoSelectSingleRow() {
        getReactiveColumnMapper().autoSelectSingleRow();
        return (THIS) this;
    }

    default THIS setSelectedEntityHandler(Handler<E> selectedEntityHandler) {
        getReactiveColumnMapper().setSelectedEntityHandler(selectedEntityHandler);
        return (THIS) this;
    }

    default THIS setEntityColumns(String jsonArrayOrExpressionDefinition) {
        getReactiveColumnMapper().setEntityColumns(jsonArrayOrExpressionDefinition);
        return (THIS) this;
    }

    default THIS setEntityColumns(EntityColumn<E>... entityColumns) {
        getReactiveColumnMapper().setEntityColumns(entityColumns);
        return (THIS) this;
    }

    default THIS applyDomainModelRowStyle() {
        getReactiveColumnMapper().applyDomainModelRowStyle();
        return (THIS) this;
    }

}
