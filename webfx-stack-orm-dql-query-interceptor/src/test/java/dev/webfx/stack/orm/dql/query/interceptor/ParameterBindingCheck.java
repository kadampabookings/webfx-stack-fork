package dev.webfx.stack.orm.dql.query.interceptor;

import dev.webfx.stack.db.query.QueryArgument;
import dev.webfx.stack.db.query.QueryArgumentBuilder;
import dev.webfx.stack.orm.dql.sqlcompiler.sql.SqlCompiled;

import java.util.Arrays;
import java.util.List;

/**
 * That the values handed to the driver line up with the slots the compiled SQL numbers.
 *
 * <p>The two shapes every caller sends today — all values named, or none of them — are here to be held
 * still, not to be changed. The third is the one that did not work: a statement carrying both the caller's
 * own {@code $n} and a name numbered above them, which is what a server-added term looks like. The old code
 * sized the array to the named parameters alone, so the positional values were not misordered but dropped.
 *
 * <p>The failing cases matter as much as the passing ones. A count that disagrees with the statement has to
 * say so rather than bind a short array, because the driver's own complaint names neither the statement nor
 * which side was wrong.
 */
public class ParameterBindingCheck {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        // Unchanged: no named parameters at all — the caller's array is already in order.
        check("pure positional", compiled(List.of(), 0), argument(new Object[]{"a", "b"}, null),
                new Object[]{"a", "b"});

        // Unchanged: every value named, nothing positional ahead of them, reordered to compiled order.
        check("pure named, reordered", compiled(List.of("event", "ref"), 0),
                argument(new Object[]{"refValue", "eventValue"}, new String[]{"ref", "event"}),
                new Object[]{"eventValue", "refValue"});

        // Unchanged: search parameters carry no names and are assumed to be in order already.
        check("named expected, none supplied", compiled(List.of("event"), 0),
                argument(new Object[]{"eventValue"}, null), new Object[]{"eventValue"});

        // The case this exists for: two positional, then one appended named value.
        check("positional then named", compiled(List.of("caller"), 2),
                argument(new Object[]{"p1", "p2", "callerValue"}, new String[]{"caller"}),
                new Object[]{"p1", "p2", "callerValue"});

        check("positional then two named, reordered", compiled(List.of("b", "a"), 1),
                argument(new Object[]{"p1", "aValue", "bValue"}, new String[]{"a", "b"}),
                new Object[]{"p1", "bValue", "aValue"});

        // Disagreements are named rather than passed to the driver.
        checkThrows("too few positional supplied", compiled(List.of("caller"), 2),
                argument(new Object[]{"p1", "callerValue"}, new String[]{"caller"}));
        checkThrows("more names than values", compiled(List.of("caller"), 0),
                argument(new Object[]{"only"}, new String[]{"caller", "other"}));
        checkThrows("expected name absent", compiled(List.of("caller"), 1),
                argument(new Object[]{"p1", "v"}, new String[]{"somethingElse"}));

        // A server-supplied term that nothing resolved is a server fault, and says so here rather than
        // reaching the driver as a bind-count mismatch naming neither the statement nor the term.
        checkThrows("server term unresolved", compiled(List.of("caller.person"), 1),
                argument(new Object[]{"p1"}, null));
        checkThrows("server term unresolved among supplied names", compiled(List.of("caller.person"), 1),
                argument(new Object[]{"p1", "v"}, new String[]{"somethingElse"}));
        // Resolved, it binds like any other trailing name.
        check("server term resolved", compiled(List.of("caller.person"), 2),
                argument(new Object[]{"p1", "p2", 4242}, new String[]{"caller.person"}),
                new Object[]{"p1", "p2", 4242});

        System.out.println(fail == 0 ? "\nALL " + pass + " PASS" : "\n" + fail + " FAILED");
        if (fail > 0)
            throw new AssertionError(fail + " parameter binding checks failed");
    }

    private static SqlCompiled compiled(List<String> parameterNames, int positionalParameterCount) {
        return new SqlCompiled("select 1", null, parameterNames, true, null, null, null, true, positionalParameterCount);
    }

    private static QueryArgument argument(Object[] parameters, String[] parameterNames) {
        QueryArgumentBuilder builder = new QueryArgumentBuilder().setStatement("select 1").setParameters(parameters);
        if (parameterNames != null)
            builder.setParameterNames(parameterNames);
        return builder.build();
    }

    private static void check(String what, SqlCompiled compiled, QueryArgument argument, Object[] expected) {
        try {
            Object[] actual = DqlQueryInterceptorInitializer.reorderNamedParameters(compiled, argument);
            if (Arrays.equals(actual, expected)) {
                pass++;
                System.out.println("  ok   " + what);
            } else {
                fail++;
                System.out.println("  FAIL " + what + " — got " + Arrays.toString(actual) + ", expected " + Arrays.toString(expected));
            }
        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL " + what + " — threw " + t);
        }
    }

    private static void checkThrows(String what, SqlCompiled compiled, QueryArgument argument) {
        try {
            Object[] actual = DqlQueryInterceptorInitializer.reorderNamedParameters(compiled, argument);
            fail++;
            System.out.println("  FAIL " + what + " — returned " + Arrays.toString(actual) + " instead of refusing");
        } catch (IllegalArgumentException e) {
            pass++;
            System.out.println("  ok   " + what);
        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL " + what + " — threw " + t.getClass().getSimpleName() + " rather than IllegalArgumentException");
        }
    }
}
