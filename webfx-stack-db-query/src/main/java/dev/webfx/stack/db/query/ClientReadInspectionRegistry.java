package dev.webfx.stack.db.query;

/**
 * Who wants to be told what clients READ, and what they are told.
 *
 * <p>Registry inversion, like {@code ProtectedEntityWriteRegistry} and {@code AuditActorRegistry}: the framework
 * owns one point every client read passes ({@link ClientQueryGuard}) and the application owns the policy — here,
 * the policy of what is worth looking at and what a look means.
 *
 * <h3>This cannot refuse anything, and that is the point</h3>
 *
 * <p>The write side learned this the expensive way, and its own comment says it: an observer wearing the
 * authorizer's clothes inherits the authorizer's fail-closed rules, so the day somebody switches observation on,
 * unparseable statements start being REFUSED. <b>Observation that can refuse is not observation.</b> So this is a
 * separate registry from {@link ClientQueryGuard}'s {@link ClientQueryGuard.Inspector}, which decides; nothing
 * here returns a verdict, and the guard runs its decision whether or not anyone is watching.
 *
 * <p>It is step 0 of the read-authorization plan ({@code docs/security/read-authorization-plan.md}): learn what
 * the clients actually read before writing the rule that constrains the rest. A default-deny needs an allowlist,
 * and an allowlist built by reading the front-end source is a list of what the code CAN do rather than what it
 * does — it misses whatever is composed at runtime, and it includes call sites nobody reaches any more.
 *
 * <h3>The division of labour, which is the same one the write side draws</h3>
 *
 * <p>The framework can see the STATEMENT and nothing else: it has no idea who sent it, and it must not learn.
 * The application can see the caller, and it is also the only side that knows what an ownership predicate looks
 * like in this domain — {@code accountCanAccessPersonOrders} is a Modality name and this module must never hold
 * it. So a {@link ReadShape} reports FACTS about a statement and passes no judgement: which entity it is rooted
 * at, which tables it reaches, which fields its WHERE binds, which functions it uses as guards. Whether that
 * adds up to "scoped to its caller" is the application's question to answer.
 *
 * <p>{@link ReadInspector#isInspecting()} is asked first, synchronously, before anything is parsed — which is
 * what keeps "only client traffic" expressible without this module learning what a client is.
 *
 * <h3>Cost, stated plainly because it is the reason this is opt-in</h3>
 *
 * <p>While an inspector is inspecting, a client statement not yet seen is parsed and compiled ONE EXTRA TIME, to
 * derive its shape by a route that cannot affect the verdict. Shapes are cached by statement text, so the steady
 * state is a map lookup per query. With no inspector registered, or one that says no, a read pays one volatile
 * read.
 *
 * @author Claude Code
 */
public final class ClientReadInspectionRegistry {

    private ClientReadInspectionRegistry() {}

    /**
     * What one client read looks like, in facts rather than verdicts.
     *
     * <p><b>Nothing here is anybody's data</b>, and that is a property to preserve rather than a coincidence.
     * Every field is a NAME — of an entity, a table, a field path, a function — taken from the domain model or
     * from the statement's structure. No parameter value and no literal reaches this record, so an inventory
     * built from it cannot become a store of personal data that the erasure path does not reach. A statement's
     * literals are where an email address or a surname would be; see {@link ClientQueryGuard#maskLiterals}, which
     * is what the statement text is put through before it is reported at all.
     *
     * @param entityName    the root domain class, or null when it could not be resolved
     * @param statementKind {@code "select"}, {@code "union"} or {@code "with"}. A union's branches are all
     *                      described and then INTERSECTED, so the facts below are what every branch guarantees;
     *                      a CTE is described from its main select, which is the only place its rows are
     *                      constrained on the way out
     * @param touchedTables every table the COMPILED SQL names — the FROM, every join, every table reached
     *                      through a dot path from an unrelated root, and the tables a bare foreign key pulls in
     *                      by loading its target's default fields. Taken from the compiler's own requests for the
     *                      domain model rather than from the statement's text, for the reason the denying reader
     *                      gives: what reaches SQL is the only thing that cannot be rephrased
     * @param boundFields   the field paths the statement GUARANTEES are tied to a value — an equality or an IN,
     *                      with a column on one side and a literal or a {@code $n} on the other, reachable
     *                      through conjunctions only, and intersected across a union's branches.
     *                      <p><b>A bound field is not a scoped query.</b> {@code where removed = $1} appears here
     *                      and matches nearly every row. This says which columns COULD carry a scope, not that
     *                      any of them does
     * @param guardFunctions the names of functions used as a boolean CONJUNCT — the position a domain-specific
     *                      ownership predicate occupies — guaranteed across a union's branches. A function name
     *                      appearing in an argument, behind an OR, or in the select list is not here, because
     *                      none of those restricts a row. Reported unjudged: this module does not know which
     *                      names mean ownership, and should not
     * @param hasWhere      whether every branch has a WHERE at all. Separate from an empty {@code boundFields}
     *                      on purpose: {@code where true} has a WHERE and guarantees nothing, and the two are
     *                      worth telling apart in an inventory describing what clients send
     */
    public record ReadShape(
        String entityName,
        String statementKind,
        String[] touchedTables,
        String[] boundFields,
        String[] guardFunctions,
        boolean hasWhere
    ) {
        // NO SECOND CONSTRUCTOR. A convenience overload would save callers a couple of empty arrays, and it
        // crashes the GWT compiler while it computes permutations — this module is compiled to JavaScript for
        // both clients, and a record with more than one constructor is a shape it cannot handle.
    }

    /** Told what clients read. Cannot refuse, cannot change a verdict, must not throw. */
    public interface ReadInspector {

        /**
         * Asked before the statement is parsed: is this caller's read one you want reported?
         *
         * <p>Called on the caller's own thread, before any async hop, so an implementation may read
         * request-scoped state — the origin, the principal — that would be gone a moment later.
         */
        boolean isInspecting();

        /** One read, described. Cannot refuse it. */
        void onRead(ReadShape shape);

        /**
         * A statement the inspector was interested in and that could not be described — it did not parse, it did
         * not compile, or it was not a select at all.
         *
         * <p>Worth reporting rather than dropping: the guard refuses such a statement, so it never becomes a
         * shape, and an inventory that silently omitted them would describe a client's traffic as tidier than it
         * is. The number of them is what says whether this inventory covers all of the traffic or most of it.
         *
         * <p>The statement arrives with its literals already masked, by the framework rather than by trust: the
         * text is the sender's own, but a literal in it is where somebody's email address or surname would be.
         */
        default void onUndescribableStatement(String maskedStatement) {}
    }

    private static volatile ReadInspector inspector;

    /** Installs the inspector. Last registration wins; null removes it. */
    public static void registerReadInspector(ReadInspector readInspector) {
        inspector = readInspector;
    }

    /**
     * Whether this particular read should be described and reported — false whenever asking throws, so a broken
     * inspector observes nothing rather than failing the reads it was watching.
     */
    public static boolean isInspectingRead() {
        ReadInspector currentInspector = inspector;
        if (currentInspector == null)
            return false;
        try {
            return currentInspector.isInspecting();
        } catch (Throwable ignored) {
            // Throwable, not RuntimeException, and the breadth is the point: this is called OUTSIDE the
            // description's own catch, so anything escaping here reaches the guard — which treats a throwing
            // inspector as a refusal. An Error raised while deciding whether to WATCH a query would then refuse
            // it, which is the one thing this class promises cannot happen.
            return false;
        }
    }

    /** Never throws into the caller: watching a read must not be able to fail one. */
    public static void notifyRead(ReadShape shape) {
        ReadInspector currentInspector = inspector;
        if (currentInspector != null) {
            try {
                currentInspector.onRead(shape);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Never throws into the caller, for the same reason as {@link #notifyRead}. */
    public static void notifyUndescribableStatement(String statement) {
        ReadInspector currentInspector = inspector;
        if (currentInspector != null) {
            try {
                currentInspector.onUndescribableStatement(
                    statement == null ? "(none)" : ClientQueryGuard.maskLiterals(statement));
            } catch (Throwable ignored) {
            }
        }
    }
}
