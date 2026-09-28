package dev.webfx.stack.orm.expression.terms.function;

import dev.webfx.extras.type.Type;

/**
 * A zero-argument term whose value only the SERVER can supply — the caller's identity, above all.
 *
 * <p>Written like {@code CURRENT_DATE} and parsed the same way, but it cannot compile to a SQL keyword the
 * way that one does: the database knows today's date and does not know who is asking, since reads run on a
 * pooled connection under one role.
 *
 * <p><b>It compiles to a bound parameter, and it must never compile to a literal.</b> Compiled SQL is cached
 * by the statement's TEXT, and two callers send identical text — so a literal would bake the first caller's
 * identity into the cached SQL and serve it to the second. That is not a degraded check, it is one member's
 * data handed to another. The parameter is numbered above the caller's own {@code $n}, and its value is
 * appended at execution.
 *
 * <p>It is a TERM and not a parameter for a reason worth keeping: a parameter arrives through the channel the
 * client fills, so its safety would rest on the server always overwriting whatever the client sent. A term
 * has no slot for a client to fill, so forging it is not something that can be attempted.
 *
 * @author Claude Code
 */
public class ServerParameterFunction<T> extends Function<T> {

    /** Reserved prefix for these names, so an executor can tell a server-supplied value from a caller's. */
    public static final String PARAMETER_NAME_PREFIX = "caller.";

    private final String parameterName;

    public ServerParameterFunction(String name, String parameterName, Type returnType) {
        // evaluable=false: no client can work this out for itself, so it is compiled into SQL wherever it
        // appears, including a select list. keyword=true: written bare, with no parentheses.
        super(name, returnType, false, true);
        this.parameterName = parameterName;
    }

    /** The reserved name under which the server supplies this term's value at execution. */
    public String getParameterName() {
        return parameterName;
    }
}
