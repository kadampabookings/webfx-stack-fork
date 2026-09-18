package dev.webfx.stack.orm.dql.query.interceptor;

import dev.webfx.stack.db.query.ClientReadDenyList;
import dev.webfx.stack.orm.domainmodel.DomainClass;
import dev.webfx.stack.orm.domainmodel.DomainField;
import dev.webfx.stack.orm.dql.sqlcompiler.lci.CompilerDomainModelReader;
import dev.webfx.stack.orm.expression.Expression;

/**
 * The compiler's view of the domain model, with the client-secret tables and columns removed from it.
 *
 * <p>This is where the check can be exhaustive rather than hopeful. The compiler states, in its own interface,
 * that every time it needs the domain model it asks this reader — and every table name in the SQL it emits
 * (FROM, every join, common-table entities, parameter references) and every column (selected, filtered, sorted,
 * joined on, or reached through a dot path from some unrelated root) is obtained from one of three methods here.
 * So a query that touches a denied column cannot be compiled through this reader at all, however it is phrased,
 * and nothing had to predict the phrasing. A traversal of the parsed statement, by contrast, would be only as
 * complete as its author's memory of every construct the grammar allows.
 *
 * <p>Only the methods that put a NAME into the SQL are checked. The other two tell the compiler how to navigate
 * — which class a foreign key points at, which fields to load by default — and emit nothing on their own; any
 * column they lead to is still named through a checked method.
 *
 * @author Claude Code
 */
final class DenyingCompilerDomainModelReader implements CompilerDomainModelReader {

    private final CompilerDomainModelReader delegate;

    DenyingCompilerDomainModelReader(CompilerDomainModelReader delegate) {
        this.delegate = delegate;
    }

    @Override
    public String getDomainClassSqlTableName(Object domainClass) {
        String table = delegate.getDomainClassSqlTableName(domainClass);
        if (ClientReadDenyList.isTableDenied(table))
            throw new ClientReadDeniedException(table, null);
        return table;
    }

    @Override
    public String getDomainClassPrimaryKeySqlColumnName(Object domainClass) {
        String column = delegate.getDomainClassPrimaryKeySqlColumnName(domainClass);
        String table = delegate.getDomainClassSqlTableName(domainClass);
        if (ClientReadDenyList.isColumnDenied(table, column))
            throw new ClientReadDeniedException(table, column);
        return column;
    }

    @Override
    public String getSymbolSqlColumnName(Object symbolDomainClass, Expression symbol) {
        String column = delegate.getSymbolSqlColumnName(symbolDomainClass, symbol);
        String table = tableOf(symbolDomainClass, symbol);
        if (ClientReadDenyList.isColumnDenied(table, column))
            throw new ClientReadDeniedException(table, column);
        return column;
    }

    @Override
    public Object getSymbolForeignDomainClass(Object symbolDomainClass, Expression symbol, boolean required) {
        return delegate.getSymbolForeignDomainClass(symbolDomainClass, symbol, required);
    }

    @Override
    public Expression getDomainClassDefaultForeignFields(Object domainClass) {
        return delegate.getDomainClassDefaultForeignFields(domainClass);
    }

    /**
     * The table a column belongs to. Taken from the field itself where there is one — a persistent field knows its
     * own class, which is more certain than the class the compiler happens to pass beside it — and from that class
     * otherwise (a primary key, which is not a field).
     */
    private String tableOf(Object symbolDomainClass, Expression symbol) {
        if (symbol instanceof DomainField field) {
            DomainClass fieldClass = field.getDomainClass();
            if (fieldClass != null)
                return fieldClass.getSqlTableName();
        }
        return symbolDomainClass == null ? null : delegate.getDomainClassSqlTableName(symbolDomainClass);
    }
}
