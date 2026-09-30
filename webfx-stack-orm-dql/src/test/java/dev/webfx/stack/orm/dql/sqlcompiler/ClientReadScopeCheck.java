package dev.webfx.stack.orm.dql.sqlcompiler;

import dev.webfx.stack.orm.dql.sqlcompiler.lci.mock.CompilerDomainModelReaderMock;
import dev.webfx.stack.orm.dql.sqlcompiler.sql.SqlCompiled;
import dev.webfx.stack.orm.dql.sqlcompiler.sql.dbms.PostgresSyntax;
import dev.webfx.stack.orm.expression.parser.ExpressionParser;
import dev.webfx.stack.orm.expression.parser.lci.mock.ParserDomainModelReaderMock;
import dev.webfx.stack.orm.expression.terms.DqlStatement;

/**
 * That a scope condition reaches every select in a statement, not just the outermost one.
 *
 * <p>This is the claim the mechanism rests on. Scope injected only at the top would leave a subquery as the
 * way round it, and subqueries are the reason the question came up: the read inventory shows client
 * statements reading through {@code exists(…)} and {@code in (select …)} in ninety-odd places. Injecting
 * where the compiler builds each select makes the reach structural — there is one place a select is built —
 * rather than a list of constructs someone has to keep complete.
 *
 * <p>The unscoped case is here to stay: with no provider, or no token, the SQL must be byte-identical to
 * what it was. Every statement the system sends today is that case.
 */
public class ClientReadScopeCheck {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        // A provider that scopes Document only, so the checks can also show it leaves other entities alone.
        ClientReadScope.registerProvider((domainClass, scopeToken) ->
            "Document".equals(String.valueOf(domainClass))
                ? ExpressionParser.parseExpression("organization=" + scopeToken, domainClass, model())
                : null);

        check("no token: unchanged", "select id from Document d where event=$1", null,
              "select d.id, d.id from document as d where d.event=$1");

        check("the root select is scoped", "select id from Document d where event=$1", 5,
              "select d.id, d.id from document as d where d.event=$1 and d.organization=5");

        check("a select with no where of its own is still scoped", "select id from Document d", 5,
              "select d.id, d.id from document as d where d.organization=5");

        // The one that matters: the subquery is a Document read too, and it gets the same condition.
        check("a subquery selecting the same entity is scoped as well",
              "select id from DocumentLine dl where document in (select id from Document d where event=$1)", 5,
              "select dl.id, dl.id from document_line as dl where dl.document in (select d.id from document as d where d.event=$1 and d.organization=5)");

        // And an entity the provider does not scope is left exactly as it was.
        check("an unscoped entity is untouched", "select id from DocumentLine dl where document=$1", 5,
              "select dl.id, dl.id from document_line as dl where dl.document=$1");

        // THE SECOND INJECTION POINT. A table reached through a dot path gets no select of its own, so the
        // condition added to every select never reaches it: d.person.email reads a Person through a join.
        ClientReadScope.registerProvider((domainClass, scopeToken) ->
            "Person".equals(String.valueOf(domainClass))
                ? ExpressionParser.parseExpression("organization=" + scopeToken, domainClass, model())
                : null);

        check("a table reached by a join is scoped in its ON clause",
              "select id from Document d where person.email=$1", 5,
              "select d.id, d.id from document as d join person j2 on j2.id=d.person_id and (j2.organization=5) where j2.email=$1");

        check("with no token, the join is written exactly as before",
              "select id from Document d where person.email=$1", null,
              "select d.id, d.id from document as d join person j2 on j2.id=d.person_id where j2.email=$1");

        System.out.println(fail == 0 ? "\nALL " + pass + " PASS" : "\n" + fail + " FAILED");
        if (fail > 0)
            throw new AssertionError(fail + " scope injection checks failed");
    }

    private static ParserDomainModelReaderMock model() {
        return new ParserDomainModelReaderMock()
                .declareFields("Document", "id,ref,event,person,organization")
                .declareFields("Person", "id,email,organization")
                .declareFields("DocumentLine", "id,document,item");
    }

    private static void check(String what, String dql, Object scopeToken, String expectedSql) {
        try {
            DqlStatement<?> statement = ExpressionParser.parseStatement(dql, model());
            SqlCompiled compiled = ExpressionSqlCompiler.compileForScope(
                statement, scopeToken, PostgresSyntax.get(), false, false, false, new CompilerDomainModelReaderMock());
            if (expectedSql.equals(compiled.getSql())) {
                pass++;
                System.out.println("  ok   " + what);
            } else {
                fail++;
                System.out.println("  FAIL " + what);
                System.out.println("         got      " + compiled.getSql());
                System.out.println("         expected " + expectedSql);
            }
        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL " + what + " — " + t);
        }
    }
}
