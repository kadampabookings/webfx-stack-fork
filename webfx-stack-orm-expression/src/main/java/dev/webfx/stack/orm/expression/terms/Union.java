package dev.webfx.stack.orm.expression.terms;

import dev.webfx.stack.orm.expression.CollectOptions;

import java.util.List;

/**
 * Represents a DQL union of several selects over the SAME domain class.
 * Syntax: select ... union [all] select ... [union [all] select ...]
 *
 * All branches must target the same domain class and produce the same column list — the query
 * mapping of the first branch is used to map every row of the union result back to entities.
 * Each branch is compiled parenthesized, so a branch can carry its own where/order by/limit;
 * there is no union-level order by (a trailing "order by" belongs to the last branch).
 *
 * @author Bruno Salmon
 */
public final class Union<T> extends DqlStatement<T> {

    private final Select<T> firstSelect;
    /** Each entry is {unionAll (Boolean), branchSelect (Select)} — branches unioned after the first */
    private final List<Object[]> unions;

    public Union(String definition, Select<T> firstSelect, List<Object[]> unions) {
        // where/orderBy/limit are per-branch for a union, so none is exposed at the statement level
        // (exposing the first branch's would mislead consumers such as push partition scoping)
        super(null, firstSelect.getDomainClass(), firstSelect.getDomainClassAlias(),
              definition, firstSelect.getSqlDefinition(), firstSelect.getParameterValues(),
              null, null, null);
        this.firstSelect = firstSelect;
        this.unions = unions;
    }

    public Select<T> getFirstSelect() {
        return firstSelect;
    }

    public List<Object[]> getUnions() {
        return unions;
    }

    @Override
    public void collect(CollectOptions options) {
        firstSelect.collect(options);
        for (Object[] union : unions)
            ((Select<?>) union[1]).collect(options);
    }

    @Override
    public StringBuilder toString(StringBuilder sb) {
        firstSelect.toString(sb);
        for (Object[] union : unions) {
            sb.append((Boolean) union[0] ? " union all " : " union ");
            ((Select<?>) union[1]).toString(sb);
        }
        return sb;
    }
}
