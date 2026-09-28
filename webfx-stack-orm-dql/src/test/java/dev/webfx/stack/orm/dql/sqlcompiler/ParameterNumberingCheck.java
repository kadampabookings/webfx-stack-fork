package dev.webfx.stack.orm.dql.sqlcompiler;

import dev.webfx.stack.orm.dql.sqlcompiler.lci.mock.CompilerDomainModelReaderMock;
import dev.webfx.stack.orm.dql.sqlcompiler.sql.SqlCompiled;
import dev.webfx.stack.orm.dql.sqlcompiler.sql.dbms.PostgresSyntax;
import dev.webfx.stack.orm.expression.parser.ExpressionParser;
import dev.webfx.stack.orm.expression.parser.lci.mock.ParserDomainModelReaderMock;
import dev.webfx.stack.orm.expression.terms.Select;

/**
 * That a statement's parameters land in one numbering rather than two overlapping ones.
 *
 * <p>Named parameters used to be numbered in a counter of their own starting at 1, so {@code ?caller}
 * beside {@code event=$1} compiled to {@code $1} as well and the two shared a placeholder. No client can
 * send both — a QueryArgument names all of its values or none — so the collision was unreachable until the
 * SERVER began adding terms to a client's statement, which is what read-authorization scope injection does.
 *
 * <p>The two cases worth keeping an eye on are the ones asserting NO change: pure positional and pure named
 * are what every statement in the system is today, and this exists as much to hold them still as to fix the
 * mixed case.
 *
 * <p>The subquery case is the one that would break first under a cheaper implementation. A maximum computed
 * by walking the outer clauses would miss a {@code $n} that only a subquery mentions, and would then hand a
 * named parameter a slot already in use. It is here because it is the case a rewrite would get wrong.
 */
public class ParameterNumberingCheck {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        // Unchanged: every statement the system sends today is one of these two.
        check("pure positional", "select id from Document d where event=$1 and ref=$2",
                "d.event=$1 and d.\"ref\"=$2", "[]", 2);
        check("pure named still starts at 1", "select id from Document d where event=?event and ref=?ref",
                "d.event=$1 and d.\"ref\"=$2", "[event, ref]", 0);

        // Fixed: a name now lands past the caller's own block instead of on top of it.
        check("mixed, one positional", "select id from Document d where event=$1 and ref=?ref",
                "d.event=$1 and d.\"ref\"=$2", "[ref]", 1);
        check("mixed, two positional", "select id from Document d where event=$1 and ref=$2 and cart=?caller",
                "d.event=$1 and d.\"ref\"=$2 and d.cart=$3", "[caller]", 2);
        check("positional only inside a subquery",
                "select id from Document d where cart=?caller and id in (select document from DocumentLine where item=$1 and id=$2)",
                "d.cart=$3 and d.id in (select tt1.document from document_line as tt1 where tt1.item=$1 and tt1.id=$2)",
                "[caller]", 2);

        // The maximum is the highest index, not the count, and not the order they appear in.
        check("out of order", "select id from Document d where event=$2 and ref=$1 and cart=?caller",
                "d.event=$2 and d.\"ref\"=$1 and d.cart=$3", "[caller]", 2);

        System.out.println(fail == 0 ? "\nALL " + pass + " PASS" : "\n" + fail + " FAILED");
        if (fail > 0)
            throw new AssertionError(fail + " parameter numbering checks failed");
    }

    private static void check(String what, String dql, String expectedSqlTail, String expectedNames, int expectedPositionalCount) {
        try {
            ParserDomainModelReaderMock model = new ParserDomainModelReaderMock()
                    .declareFields("Document", "id,ref,event,person,cart")
                    .declareFields("DocumentLine", "id,document,item");
            Select<?> select = ExpressionParser.parseSelect(dql, model);
            SqlCompiled compiled = ExpressionSqlCompiler.compileSelect(
                    select, PostgresSyntax.get(), false, false, new CompilerDomainModelReaderMock());
            boolean ok = compiled.getSql().endsWith(expectedSqlTail)
                    && compiled.getParameterNames().toString().equals(expectedNames)
                    && compiled.getPositionalParameterCount() == expectedPositionalCount;
            if (ok) {
                pass++;
                System.out.println("  ok   " + what);
            } else {
                fail++;
                System.out.println("  FAIL " + what);
                System.out.println("         sql      " + compiled.getSql());
                System.out.println("         expected ..." + expectedSqlTail);
                System.out.println("         names " + compiled.getParameterNames() + " (expected " + expectedNames
                        + "), positional " + compiled.getPositionalParameterCount() + " (expected " + expectedPositionalCount + ")");
            }
        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL " + what + " — " + t);
        }
    }
}
