package dev.webfx.stack.db.query;

import java.util.Arrays;

/**
 * That a caller's identity reaches the query as a parameter value, and only when it is wanted.
 *
 * <p>The case worth reading is the last one. A push subscription is keyed by its QueryArgument, so if two
 * callers running the same statement produced equal arguments they would share one subscription and one
 * would be served the other's rows. Putting the value in the parameters — where equality can see it — is
 * what makes them separate, and this asserts it rather than trusting that it stayed true.
 *
 * <p>The identity case matters almost as much in the other direction: attaching to a statement that uses no
 * caller term would make every argument caller-specific and give every viewer of a shared query a stream of
 * their own, which is deduplication gone and push with it.
 */
public class ServerCallerParametersCheck {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        ServerCallerParameters.registerResolver(name ->
            "caller.person".equals(name) ? 101 : "caller.account".equals(name) ? 202 : null);

        QueryArgument plain = argument("select id from Document d where event=$1", 7);
        check("a statement naming no caller term is untouched", ServerCallerParameters.attach(plain) == plain);

        QueryArgument using = argument("select id from Document d where person=CALLER_PERSON and event=$1", 7);
        QueryArgument attached = ServerCallerParameters.attach(using);
        check("the caller's own values are appended, in reserved-name order",
              Arrays.equals(attached.getParameters(), new Object[]{7, 101, 202}));
        check("and named, so the binding seam can find them",
              Arrays.equals(attached.getParameterNames(), new String[]{"caller.person", "caller.account"}));
        check("the caller's positional values are left where they were",
              attached.getParameters()[0].equals(7));

        // Over-attaching costs a value nobody binds; under-attaching would be a term with no value, so the
        // text test is deliberately the loose one.
        QueryArgument mentions = argument("select id from Document d where ref='CALLER_PERSON'", 7);
        check("a literal mentioning the term attaches too, which is the safe direction",
              ServerCallerParameters.attach(mentions) != mentions);

        // THE property the design rests on.
        ServerCallerParameters.registerResolver(name -> "caller.person".equals(name) ? 999 : null);
        QueryArgument otherCaller = ServerCallerParameters.attach(
            argument("select id from Document d where person=CALLER_PERSON and event=$1", 7));
        check("two callers running the same statement are NOT equal arguments",
              !attached.equals(otherCaller));
        check("and do not collide in a hash map, which is how subscriptions are keyed",
              attached.hashCode() != otherCaller.hashCode());

        System.out.println(fail == 0 ? "\nALL " + pass + " PASS" : "\n" + fail + " FAILED");
        if (fail > 0)
            throw new AssertionError(fail + " caller parameter checks failed");
    }

    private static QueryArgument argument(String statement, Object... parameters) {
        return QueryArgument.builder().setDataSourceId("ds").setLanguage("DQL")
                .setStatement(statement).setParameters(parameters).build();
    }

    private static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else    { fail++; System.out.println("  FAIL " + what); }
    }
}
