package dev.webfx.stack.orm.dql.query.interceptor;

import dev.webfx.stack.db.query.ClientReadInspectionRegistry.ReadShape;
import dev.webfx.stack.orm.domainmodel.DomainClass;
import dev.webfx.stack.orm.domainmodel.DataSourceModel;
import dev.webfx.stack.orm.dql.sqlcompiler.ExpressionSqlCompiler;
import dev.webfx.stack.orm.dql.sqlcompiler.lci.CompilerDomainModelReader;
import dev.webfx.stack.orm.dql.sqlcompiler.sql.dbms.DbmsSqlSyntax;
import dev.webfx.stack.orm.expression.Expression;
import dev.webfx.stack.orm.expression.terms.Alias;
import dev.webfx.stack.orm.expression.terms.And;
import dev.webfx.stack.orm.expression.terms.Constant;
import dev.webfx.stack.orm.expression.terms.Dot;
import dev.webfx.stack.orm.expression.terms.DqlStatement;
import dev.webfx.stack.orm.expression.terms.Equals;
import dev.webfx.stack.orm.expression.terms.ExpressionArray;
import dev.webfx.stack.orm.expression.terms.IdExpression;
import dev.webfx.stack.orm.expression.terms.In;
import dev.webfx.stack.orm.expression.terms.ParameterReference;
import dev.webfx.stack.orm.expression.terms.Select;
import dev.webfx.stack.orm.expression.terms.Symbol;
import dev.webfx.stack.orm.expression.terms.Union;
import dev.webfx.stack.orm.expression.terms.WithSelect;
import dev.webfx.stack.orm.expression.terms.function.Call;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Describes one client read: what it is rooted at, what it reaches, and what its WHERE ties down.
 *
 * <p>Step 0 of the read-authorization plan. It answers questions of FACT and none of judgement — whether the
 * facts add up to a query scoped to its caller is the application's to decide, because only the application
 * knows what an ownership predicate is called in this domain.
 *
 * <h3>Why this never decides anything</h3>
 *
 * <p>It is driven from {@link DqlClientQueryInspector} on a pass of its own, after the verdict and separately
 * from it, and it swallows everything it cannot read. A description that could fail a query would make turning
 * observation on a change of behaviour, which is the one thing step 0 must not be.
 *
 * <h3>What "bound" means here, and the three ways it could lie</h3>
 *
 * <p>Everything below reports what a statement GUARANTEES about the rows it can return, never what it merely
 * mentions. Three asymmetries follow from that, and each was a defect before it was a rule:
 *
 * <ul>
 *   <li><b>Conjunctions are descended, disjunctions are not.</b> {@code A and B} matches at most the rows
 *       {@code A} alone would, so a binding conjunct binds the whole of it. {@code A or B} matches at least
 *       those and possibly every row, so {@code person = $1 or true} guarantees nothing while looking exactly
 *       like a scoped query. This applies to FUNCTION names as much as to fields: an ownership predicate behind
 *       an OR is not an ownership predicate, and reporting one would file the most dangerous shape there is as
 *       a safe one.</li>
 *   <li><b>A union guarantees only what EVERY branch guarantees.</b> A union returns the rows of all its
 *       branches, so {@code select … where person = $1 union select … from Document} is unscoped however tightly
 *       its first branch is written. Branch facts are therefore INTERSECTED. {@link Union} exposes no
 *       statement-level WHERE for exactly this reason, and its own comment warns that taking the first branch's
 *       would mislead a consumer — this is that consumer.</li>
 *   <li><b>A guard is a function in CONJUNCT position</b>, not a function anywhere. {@code accountCanAccess…(…)}
 *       as a conjunct restricts the rows; the same name inside an argument, a disjunction or a select list does
 *       not.</li>
 * </ul>
 *
 * @author Claude Code
 */
final class DqlClientReadShaper {

    private DqlClientReadShaper() {}

    /** The facts one branch of a statement guarantees about its rows. */
    private record BranchFacts(Set<String> boundFields, Set<String> guardFunctions, boolean hasWhere) {}

    /**
     * The shape of this statement, or null when it could not be described.
     *
     * <p>Null is not a verdict. A statement this cannot read is one the guard decides about on its own terms —
     * usually by refusing it — and the caller reports it as undescribable so that the inventory says how much of
     * the traffic it is failing to cover rather than quietly overstating itself.
     */
    static ReadShape shapeOf(DataSourceModel dataSourceModel, String statement, boolean compileExpressions) {
        DqlStatement<?> parsed;
        try {
            parsed = dataSourceModel.parseStatement(statement);
        } catch (RuntimeException e) {
            return null;
        }
        String kind = parsed instanceof Union ? "union" : parsed instanceof WithSelect ? "with"
            : parsed instanceof Select ? "select" : null;
        if (kind == null) // an insert, update or delete sent to the query endpoint: not a read to describe
            return null;
        Set<String> tables = new TreeSet<>();
        try {
            compileCollecting(dataSourceModel, parsed, compileExpressions, tables);
        } catch (RuntimeException e) {
            // It parsed and would not compile. The tables collected up to that point are a partial answer, and a
            // partial answer presented as a whole one is worse than none: report it as undescribable instead.
            return null;
        }
        List<Select<?>> branches = branchesOf(parsed);
        BranchFacts guaranteed = intersect(branches);
        return new ReadShape(
            entityNameOf(dataSourceModel, branches.isEmpty() ? null : branches.get(0)),
            kind,
            tables.toArray(new String[0]),
            guaranteed.boundFields().toArray(new String[0]),
            guaranteed.guardFunctions().toArray(new String[0]),
            guaranteed.hasWhere());
    }

    /**
     * Every select whose rows can reach the result.
     *
     * <p>A CTE's body is deliberately NOT one of them: its rows reach the result only through the main select,
     * which is where the constraint that matters is applied. Its tables are collected by the compilation
     * regardless, which is the part a description must not miss.
     */
    private static List<Select<?>> branchesOf(DqlStatement<?> parsed) {
        List<Select<?>> branches = new ArrayList<>();
        if (parsed instanceof Union<?> union) {
            branches.add(union.getFirstSelect());
            for (Object[] branch : union.getUnions())
                if (branch != null && branch.length > 1 && branch[1] instanceof Select<?> select)
                    branches.add(select);
        } else if (parsed instanceof WithSelect<?> with) {
            branches.add(with.getMainSelect());
        } else if (parsed instanceof Select<?> select) {
            branches.add(select);
        }
        return branches;
    }

    /** What every branch guarantees — so one unconstrained branch leaves the statement guaranteeing nothing. */
    private static BranchFacts intersect(List<Select<?>> branches) {
        Set<String> fields = null, functions = null;
        boolean hasWhere = !branches.isEmpty();
        for (Select<?> branch : branches) {
            Set<String> branchFields = new TreeSet<>(), branchFunctions = new TreeSet<>();
            Expression<?> where = branch == null ? null : branch.getWhere();
            collectGuarantees(where, "", branchFields, branchFunctions,
                              branch == null ? null : branch.getDomainClassAlias());
            hasWhere &= where != null;
            if (fields == null) {
                fields = branchFields;
                functions = branchFunctions;
            } else {
                fields.retainAll(branchFields);
                functions.retainAll(branchFunctions);
            }
        }
        return new BranchFacts(fields == null ? new TreeSet<>() : fields,
                               functions == null ? new TreeSet<>() : functions, hasWhere);
    }

    /** The compilation the guard performs, with the collecting reader in place of the denying one. */
    private static void compileCollecting(DataSourceModel dataSourceModel, DqlStatement<?> parsed,
                                          boolean compileExpressions, Set<String> tables) {
        CompilerDomainModelReader reader =
            new CollectingCompilerDomainModelReader(dataSourceModel.getCompilerDomainModelReader(), tables);
        DbmsSqlSyntax syntax = dataSourceModel.getDbmsSqlSyntax();
        if (parsed instanceof WithSelect<?> withSelect)
            ExpressionSqlCompiler.compileWithSelect(withSelect, syntax, true, true, compileExpressions, reader);
        else if (parsed instanceof Union<?> union)
            ExpressionSqlCompiler.compileUnion(union, syntax, true, true, compileExpressions, reader);
        else if (parsed instanceof Select<?> select)
            ExpressionSqlCompiler.compileSelect(select, syntax, true, true, compileExpressions, reader);
    }

    private static String entityNameOf(DataSourceModel dataSourceModel, Select<?> select) {
        if (select == null)
            return null;
        try {
            Object domainClass = select.getDomainClass();
            if (domainClass instanceof DomainClass resolved)
                return resolved.getName();
            DomainClass resolved = dataSourceModel.getDomainModel().getClass(domainClass);
            return resolved == null ? null : resolved.getName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * What one WHERE guarantees: the field paths it ties to a value, and the functions it uses as guards.
     *
     * <p>Descends conjunctions and the {@code fk.(…)} form, and nothing else. The prefix carries the dot path
     * accumulated on the way in, so {@code document.(event = $1)} is recorded as {@code document.event} rather
     * than as a bare {@code event} that could belong to any table in the statement.
     *
     * <p>Paths and never values: {@code cart.uuid}, not the uuid. The uuid is a capability and the address in
     * {@code lower(email) = $1} is somebody's; neither belongs in an inventory, and the column name answers the
     * question an inventory is asking anyway.
     */
    private static void collectGuarantees(Expression<?> where, String prefix,
                                          Set<String> fields, Set<String> functions, String rootAlias) {
        if (where == null)
            return;
        if (where instanceof And<?> and) {
            collectGuarantees(and.getLeft(), prefix, fields, functions, rootAlias);
            collectGuarantees(and.getRight(), prefix, fields, functions, rootAlias);
            return;
        }
        if (where instanceof Dot<?> dot) {
            // `fk.(a and b)` — the left is the path, the right is a condition on the target. Any other dot is a
            // column reference, which the equality cases below read.
            String left = pathOf(dot.getLeft(), rootAlias);
            if (left != null)
                collectGuarantees(dot.getRight(), left.isEmpty() ? prefix : prefix + left + ".",
                                  fields, functions, rootAlias);
            return;
        }
        if (where instanceof Call<?> call) {
            // A function in CONJUNCT position is a boolean guard on the rows — which is where an ownership
            // predicate lives. Its arguments are deliberately not descended: a guard inside an argument guards
            // nothing.
            String name = call.getFunctionName();
            if (name != null && !name.isEmpty())
                functions.add(name);
            return;
        }
        if (where instanceof Equals<?> equals) {
            addIfBinding(equals.getLeft(), equals.getRight(), prefix, fields, rootAlias);
            addIfBinding(equals.getRight(), equals.getLeft(), prefix, fields, rootAlias);
            return;
        }
        if (where instanceof In<?> in && isValueList(in.getRight())) {
            String path = pathOf(in.getLeft(), rootAlias);
            if (path != null && !path.isEmpty())
                fields.add(prefix + path);
        }
    }

    private static void addIfBinding(Expression<?> column, Expression<?> value, String prefix, Set<String> into,
                                     String rootAlias) {
        if (!isValue(value))
            return;
        String path = pathOf(column, rootAlias);
        if (path != null && !path.isEmpty())
            into.add(prefix + path);
    }

    /**
     * The dotted name of a column, or null for anything that is not one.
     *
     * <p>Deliberately not any dot: a dot may carry an arbitrary expression ({@code fk.(a or b)}), and
     * {@code fk.(a or b) = true} binds nothing while looking exactly like an ordinary equality.
     *
     * <p>A single-argument function around a column IS read through, so {@code lower(email) = $1} is recorded
     * as binding {@code email}. That is the shape the front office's email lookup sends, and recording it as
     * binding nothing would have hidden the one read in the inventory that turns an address into a name.
     * {@code coalesce(a, b)} is not read through — its argument is a list, not a column.
     *
     * <p><b>The statement's own alias resolves to the empty path, not to null</b>, which is what makes
     * {@code select … from DocumentLine dl where dl.document.person = $1} describable at all. An alias is a local
     * name for the root the statement already names, so {@code dl.document.person} and {@code document.person}
     * are the same column and must produce the same path, or an inventory would file the same read under two
     * shapes depending on whether its author happened to declare an alias — and, worse, report the aliased one
     * as guaranteeing nothing. Any OTHER alias keeps its name as a step, because it is not this root.
     */
    private static String pathOf(Expression<?> expression, String rootAlias) {
        if (expression instanceof IdExpression)
            return "id";
        if (expression instanceof Alias<?> alias) {
            String name = alias.getName();
            if (name == null || name.isEmpty())
                return null;
            return name.equals(rootAlias) ? "" : name;
        }
        if (expression instanceof Call<?> call)
            return pathOf(call.getOperand(), rootAlias);
        if (expression instanceof Symbol<?> symbol) {
            String name = symbol.getName();
            return name == null || name.isEmpty() ? null : name;
        }
        if (expression instanceof Dot<?> dot) {
            String left = pathOf(dot.getLeft(), rootAlias), right = pathOf(dot.getRight(), rootAlias);
            if (left == null || right == null)
                return null;
            return left.isEmpty() ? right : right.isEmpty() ? left : left + "." + right;
        }
        return null;
    }

    /** A literal or a {@code $n}, judged by SHAPE and never by the value a parameter happens to hold. */
    private static boolean isValue(Expression<?> expression) {
        return expression instanceof Constant || expression instanceof ParameterReference;
    }

    /** {@code in ($1,$2)} or {@code in (1,2)} — never {@code in (select …)}, which guarantees nothing here. */
    private static boolean isValueList(Expression<?> expression) {
        if (isValue(expression))
            return true;
        if (!(expression instanceof ExpressionArray<?> array))
            return false;
        Expression<?>[] elements = array.getExpressions();
        if (elements == null || elements.length == 0)
            return false;
        for (Expression<?> element : elements)
            if (!isValue(element))
                return false;
        return true;
    }
}
