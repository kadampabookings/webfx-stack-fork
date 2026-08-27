package dev.webfx.stack.db.submit;

import dev.webfx.platform.async.Future;

import java.util.Map;

/**
 * Which entities may not be written without the application's say-so, and who to ask.
 *
 * <p>Registry inversion, like {@code RestrictedPrincipalRegistry} and {@code AuditActorRegistry}: the
 * framework owns one enforcement point that a caller cannot route around, and the application owns the
 * policy. The alternative — teaching the DQL layer what an authorization is — would put a dependency on
 * the authz stack into the path every write takes, to express a rule only the application knows.
 *
 * <p><b>The framework enforces the answer it is given and holds no policy of its own</b>, deliberately.
 * There is no "report only" switch here, because a leniency knob in the framework is a knob nobody can
 * see from the application that owns the rule: whether to deny or merely observe is part of the policy,
 * so it belongs with the authorizer, which can also explain itself in the log. What this class
 * guarantees is narrower and more useful — that the question is always asked.
 *
 * <p>With nothing registered, nothing is protected and every write proceeds exactly as before. That is
 * what lets this ship ahead of any policy.
 *
 * @author Bruno Salmon
 */
public final class ProtectedEntityWriteRegistry {

    private ProtectedEntityWriteRegistry() {}

    /** The three ways a DQL statement can change rows. */
    public enum WriteVerb { INSERT, UPDATE, DELETE }

    /**
     * Everything the seam could determine about one write.
     *
     * @param writtenFields the fields this statement SETS, empty for a delete
     * @param writtenValues those fields' values where they resolve to a scalar — an insert names its
     *                      owner here ({@code insert Document set person=$1}), which is how a new row's
     *                      ownership can be judged without a row existing to look up
     * @param targetId      the id of the row being changed, when the statement selects one by primary
     *                      key — and <b>null when it could not be determined</b>, which covers a delete
     *                      or update whose WHERE is anything more interesting than {@code id = value}.
     *                      <p><b>Null is not "no constraint", it is "unknown".</b> A policy that decides
     *                      by ownership must DENY on null: a statement whose target this could not read
     *                      is precisely the statement that would be used to reach somebody else's row.
     *                      Reading null as "not applicable" would make the check optional at the
     *                      attacker's discretion.
     */
    public record WriteRequest(
        String entityName,
        WriteVerb verb,
        String[] writtenFields,
        Map<String, Object> writtenValues,
        Object targetId
    ) {}

    @FunctionalInterface
    public interface WriteAuthorizer {
        /**
         * @return a future true if this write may proceed. A future false, or a failure, denies it — the
         *         caller treats anything that is not an explicit yes as no, so an authorizer that throws
         *         or times out withholds the write rather than waving it through.
         */
        Future<Boolean> isWriteAuthorized(WriteRequest request);
    }

    /** Told after a protected write has actually happened — see {@link #registerWriteObserver}. */
    @FunctionalInterface
    public interface WriteObserver {
        void onProtectedWriteSucceeded(String entityName, WriteVerb verb);
    }

    /**
     * Told when a submit arrives that is NOT DQL, and so will reach the database as written.
     *
     * <p>Reported from the framework because only the DQL layer can still tell: it inspects the
     * statement's language before translating, and afterwards a translated DQL statement and a raw one
     * are the same thing — a SQL string with no language. Judged by the application because whether a
     * raw statement matters depends on WHO sent it, which is a question about origin and identity that
     * this layer has no business knowing.
     */
    @FunctionalInterface
    public interface RawStatementObserver {
        void onNonDqlSubmit(String language, String statement);
    }

    private static volatile RawStatementObserver rawStatementObserver;

    public static void registerRawStatementObserver(RawStatementObserver observer) {
        rawStatementObserver = observer;
    }

    /** Never throws into the caller: observing a statement must not be able to fail one. */
    public static void notifyNonDqlSubmit(String language, String statement) {
        RawStatementObserver observer = rawStatementObserver;
        if (observer != null) {
            try {
                observer.onNonDqlSubmit(language, statement);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static volatile WriteAuthorizer authorizer;
    private static volatile WriteObserver observer;
    /** Lower-cased, so the pre-filter below can match a statement whatever case it was written in. */
    private static volatile String[] protectedEntityNamesLowerCase = new String[0];

    /**
     * @param protectedEntityNames the entity names whose writes must be authorized — used verbatim as a
     *                             cheap textual pre-filter, so they must be the names as they appear in
     *                             DQL, not table names
     */
    public static void registerWriteAuthorizer(WriteAuthorizer writeAuthorizer, String... protectedEntityNames) {
        String[] lowerCase = new String[protectedEntityNames.length];
        for (int i = 0; i < protectedEntityNames.length; i++)
            lowerCase[i] = protectedEntityNames[i].toLowerCase();
        protectedEntityNamesLowerCase = lowerCase;
        authorizer = writeAuthorizer;
    }

    public static boolean hasAuthorizer() {
        return authorizer != null;
    }

    /**
     * Registers something to be told when a protected write SUCCEEDS.
     *
     * <p>Separate from the authorizer because it answers a different question at a different moment:
     * the authorizer decides beforehand whether a write may happen, this reports afterwards that one
     * did. The reason it exists is caching — a rule that has just changed is exactly the rule a cached
     * decision is now wrong about, and the only component that knows the change happened is the one
     * that let it through.
     */
    public static void registerWriteObserver(WriteObserver writeObserver) {
        observer = writeObserver;
    }

    /** Never throws into the caller: a write that succeeded must not be reported as failed by its own bookkeeping. */
    public static void notifyWriteSucceeded(String entityName, WriteVerb verb) {
        WriteObserver currentObserver = observer;
        if (currentObserver != null) {
            try {
                currentObserver.onProtectedWriteSucceeded(entityName, verb);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /**
     * A cheap substring test answering "is it even worth parsing this statement?".
     *
     * <p>The authorization decision needs the parsed entity, but parsing every write to discover that
     * almost none of them touch a protected entity would put a parse on the hot path to protect a
     * handful of statements. A false positive here costs one parse; a false negative would be a hole,
     * so this errs by matching too much: any statement merely MENTIONING a protected name is parsed.
     */
    public static boolean mayTouchProtectedEntity(String dqlStatement) {
        if (authorizer == null || dqlStatement == null)
            return false;
        String lowerCase = dqlStatement.toLowerCase();
        for (String name : protectedEntityNamesLowerCase)
            if (lowerCase.contains(name))
                return true;
        return false;
    }

    /**
     * Asks the application, and fails the returned future when the answer is anything but yes.
     *
     * <p>Failing rather than returning false means a denied write propagates as an error the caller
     * already knows how to surface, and — more to the point — cannot be mistaken for a successful
     * no-op by a code path that forgot to inspect a boolean.
     */
    public static Future<Void> checkWriteAllowed(WriteRequest request) {
        WriteAuthorizer currentAuthorizer = authorizer;
        if (currentAuthorizer == null)
            return Future.succeededFuture();
        return currentAuthorizer.isWriteAuthorized(request)
            .otherwise(false) // an authorizer that fails denies; it does not abstain
            .compose(authorized -> Boolean.TRUE.equals(authorized)
                ? Future.succeededFuture()
                : Future.failedFuture("[NotAuthorizedError] Not authorized to " + request.verb() + " " + request.entityName()));
    }
}
