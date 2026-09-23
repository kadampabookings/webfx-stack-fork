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
    /**
     * How many times the statement reached a column that may be TESTED but not read — see
     * {@link ClientReadDenyList#denyColumnExceptEqualityMatch}. Recorded rather than refused, because whether
     * the reaching was legitimate is a question about WHERE in the statement it happened, and this reader is
     * context-free by design. {@link CapabilityColumnWalk} answers it; this only raises it.
     *
     * <p><b>A count and not a flag, and that distinction is the whole control.</b> The walk reads the statement
     * and this reads what the compiler emits, and the two come apart — a fields group, a field defined as an
     * expression and a bare foreign key's default fields all put a column into the SQL without it appearing in
     * the statement. A flag could only ever say "at least one", so a statement carrying ONE legitimate test
     * licensed every hidden read beside it. Counting lets the two be required to AGREE, which closes the class
     * rather than each construct in it.
     */
    private int capabilityColumnHits;

    DenyingCompilerDomainModelReader(CompilerDomainModelReader delegate) {
        this.delegate = delegate;
    }

    int capabilityColumnHits() {
        return capabilityColumnHits;
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
        // Asked FIRST, because isColumnDenied answers true for these too — deliberately, so that a consumer
        // which has not learned about the third category refuses one rather than handing it over.
        if (ClientReadDenyList.isCapabilityColumn(table, column)) {
            capabilityColumnHits++;
            return column;
        }
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
