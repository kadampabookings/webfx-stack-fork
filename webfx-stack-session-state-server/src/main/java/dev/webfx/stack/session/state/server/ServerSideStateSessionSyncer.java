package dev.webfx.stack.session.state.server;

import dev.webfx.platform.async.AsyncFunction;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.tuples.Pair;
import dev.webfx.stack.authn.logout.server.LogoutPush;
import dev.webfx.stack.session.SessionService;
import dev.webfx.stack.session.isolation.IsolatedSession;
import dev.webfx.stack.session.state.LogoutUserId;
import dev.webfx.stack.session.state.SessionAccessor;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;

import java.util.Objects;

/**
 * @author Bruno Salmon
 */
public final class ServerSideStateSessionSyncer {

    private final static boolean LOG_STATES = false; // Set to true to log incoming and outgoing states on the server side

    private static AsyncFunction<Object, Object> userIdChecker;

    public static void setUserIdChecker(AsyncFunction<Object, Object> userIdChecker) {
        ServerSideStateSessionSyncer.userIdChecker = userIdChecker;
    }

    private static AsyncFunction<Void, Void> userIdAuthorizer;

    public static void setUserIdAuthorizer(AsyncFunction<Void, Void> userIdAuthorizer) {
        ServerSideStateSessionSyncer.userIdAuthorizer = userIdAuthorizer;
    }

    // ======================================== INCOMING STATE ON SERVER ========================================
    // Sync method to be used on the server side, when the server receives an incoming state from a client

    public static Future<Pair<IsolatedSession /* final server session */, Object/* final incoming state*/>> syncIncomingState(IsolatedSession serverSession, Object incomingState) {
        String incomingStateCapture = LOG_STATES ? "" + incomingState : null; // capturing state before changes for logs

        Future<IsolatedSession> sessionFuture;

        // serverSession.id <= incomingState.serverSessionId ? ONLY ON NEW SERVER SESSION
        String requestedServerSessionId = StateAccessor.getServerSessionId(incomingState);
        String serverSessionRunId = SessionAccessor.getRunId(serverSession);
        boolean isNewServerSession = serverSessionRunId == null;
        if (requestedServerSessionId != null /*&& isNewServerSession*/ && !Objects.equals(requestedServerSessionId, serverSession.id())) {
            sessionFuture = SessionService.getSessionStore().get(requestedServerSessionId)
                .compose(loadedSession -> {
                    if (loadedSession != null) {
                        serverSession.log("Swapped underlying session (session id = " + serverSession.id() + " -> " + loadedSession.id() + ")");
                        serverSession.setUnderlyingSession(loadedSession);
                    } else {
                        serverSession.log("Unable to load requested session id " + requestedServerSessionId + " -> keeping session id = " + serverSession.id());
                    }
                    return syncFixedServerSessionFromIncomingClientStateWithUserIdCheckFirst(serverSession, incomingState, false);
                });
        } else {
            sessionFuture = syncFixedServerSessionFromIncomingClientStateWithUserIdCheckFirst(serverSession, incomingState, isNewServerSession);
        }

        return sessionFuture.map(finalServerSession -> {
            // Finally, we enrich the incoming state with possible further info coming from the serverSession
            Object finalIncomingState = ServerSideStateSessionSyncer.syncIncomingClientStateFromServerSession(incomingState, finalServerSession);

            if (LOG_STATES)
                Console.log("👉👉 Incoming state: " + incomingStateCapture + " >> " + finalIncomingState);

            return new Pair<>(finalServerSession, finalIncomingState);
        });
    }

    private static Future<IsolatedSession> syncFixedServerSessionFromIncomingClientStateWithUserIdCheckFirst(IsolatedSession serverSession, Object clientState, boolean forceStore) {
        Object userId = StateAccessor.getUserId(clientState);
        Object sessionUserId = SessionAccessor.getUserId(serverSession);
        // A "public" principal is one that isn't a logged-in user: either no userId at all (never logged in),
        // or the explicit LOGOUT_USER_ID. Note the client keeps re-communicating LOGOUT_USER_ID on every
        // message, INCLUDING on a fresh app start / page reload — so on a logged-out reload the incoming userId
        // is LOGOUT_USER_ID, not null.
        boolean incomingPublic = LogoutUserId.isLogoutUserIdOrNull(userId);
        boolean sessionPublic = LogoutUserId.isLogoutUserIdOrNull(sessionUserId);
        boolean sameConnection = Objects.equals(StateAccessor.getRunId(clientState), SessionAccessor.getRunId(serverSession));
        // Skip the user-identity check when there's no genuine login transition to validate:
        //   - userId == null: the client isn't communicating any userId (the Java client sends it only on change).
        //   - no checker configured.
        //   - same connection and unchanged userId: nothing relevant changed on an existing connection. This
        //     handles clients (e.g. React) that resend all state properties on every request.
        //   - both the incoming principal AND the session are public (logged out / never logged in): the client
        //     is merely re-communicating its logged-out state (e.g. on a page reload), NOT performing a fresh
        //     logout transition — so we must NOT divert it through LogoutPush below. A real logout transition
        //     (session still holds a real user) keeps going to the full-check path so LogoutPush still runs.
        // On a new connection (runId mismatch or new session), a logged-in user still runs the full check to
        // ensure authorizations are refreshed even if the userId hasn't changed.
        if (userId == null || userIdChecker == null
                || (sameConnection && Objects.equals(userId, sessionUserId))
                || (incomingPublic && sessionPublic)) {
            // No user identity to check here. We sync the session as usual...
            Future<IsolatedSession> future = syncFixedServerSessionFromIncomingClientState(serverSession, clientState, forceStore);
            // ...but a freshly connected (or reconnected) public client must ALSO receive its authorizations.
            // The system grants authorizations to the public too (operations flagged 'public'), not only to
            // logged-in users, so those must be pushed even when there's no logged-in userId. We push only when:
            //   - it's a new connection (the client communicates a runId that differs from the one already stored
            //     in the session — on the first message of a connection the session has no runId yet), which
            //     avoids re-pushing on every subsequent message of the same connection; AND
            //   - BOTH the incoming principal and the session are public — so we never clobber a genuinely
            //     logged-in session (e.g. a Java client reconnecting without re-sending its userId) with the
            //     public authorization set. Logged-in users are handled by the full-check path below and the
            //     outgoing-state path.
            if (!sameConnection && userIdAuthorizer != null && incomingPublic && sessionPublic) {
                // The push targets the client by runId, so ensure it's set in the state we run the push with.
                if (StateAccessor.getRunId(clientState) == null)
                    StateAccessor.setRunId(clientState, SessionAccessor.getRunId(serverSession));
                if (StateAccessor.getRunId(clientState) != null) // only push when we know which client to push to
                    ThreadLocalStateHolder.runWithState(clientState, () -> userIdAuthorizer.apply(null));
            }
            return future;
        }
        // Case when the user is set => login or user switch, or logout (LOGOUT_USER_ID)
        return ThreadLocalStateHolder.runWithState(clientState, () -> userIdChecker.apply(userId))
            // If the user identity check failed (ex: no such user exception), we log out the user
            .recover(e -> Future.succeededFuture(LogoutUserId.LOGOUT_USER_ID))
            .compose(finalUserId -> {
                // Setting the new user id (should be the same as the passed on if valid, or something like "INVALID" if not)
                Console.log("️🛡 UserIdCheck: userId=" + userId + " => finalUserId = " + finalUserId);
                if (finalUserId == null) // Shouldn't arrive but just in case (the user identity check should raise an exception instead)
                    finalUserId = LogoutUserId.LOGOUT_USER_ID;
                // Memorizing the final user id in the client state
                StateAccessor.setUserId(clientState, finalUserId);
                // We continue with the normal session <-> state sync process
                Future<IsolatedSession> future = syncFixedServerSessionFromIncomingClientState(serverSession, clientState, forceStore);
                // At the same time, we do a push to the client of either the logout userId (if it's a logout), or the
                // new authorizations (if it's a login or user switch). To prepare this push, we need to ensure that the
                // userId is set in the client state (the runId is what identifies which client to push to).
                if (StateAccessor.getRunId(clientState) == null) // // if not, we set it from the server session
                    StateAccessor.setRunId(clientState, SessionAccessor.getRunId(serverSession));
                // We are now ready for the push
                ThreadLocalStateHolder.runWithState(clientState, () -> { // we specify which state to use for the push
                    // Special case: invalid user => we force a logout
                    if (LogoutUserId.isLogoutUserId(ThreadLocalStateHolder.getUserId())) {
                        LogoutPush.pushLogoutMessageToClient(); // This will push a logout userId, and subsequently push the new authorizations (see OUTGOING STATE)
                        // General case: valid user (probably a user switch from the client, or a reconnection)
                    } else if (userIdAuthorizer != null) {
                        // We ask the authorizer to push the new authorizations for that user
                        // Note: that push shouldn't contain the userId, otherwise this will create a loop (see OUTGOING STATE).
                        userIdAuthorizer.apply(null);
                    }
                });
                return future;
            });
    }

    private static Future<IsolatedSession> syncFixedServerSessionFromIncomingClientState(IsolatedSession serverSession, Object clientState, boolean forceStore) {
        // serverSession.userId <= clientState.userId ? YES IF SET, as this means the client switched user, so we memorise that info in the session
        boolean userIdChanged = SessionAccessor.changeUserId(serverSession, StateAccessor.getUserId(clientState), true);
        // serverSession.runId <= clientState.runId ? YES IF SET, as this means the client is communicating the run id, so we memorise that in the session
        String runId = StateAccessor.getRunId(clientState);
        boolean runIdChanged = SessionAccessor.changeRunId(serverSession, runId, true);
        // serverSession.backoffice <= clientState.backoffice ? YES IF SET, as this means the client is communicating the client type, so we memorise that in the session
        Boolean backoffice = StateAccessor.getBackoffice(clientState);
        boolean backofficeChanged = SessionAccessor.changeBackoffice(serverSession, backoffice, true);
        // serverSession.clientVersion / pwa <= clientState.* ? YES IF SET — invariant connection facts the
        // client sends once at (re)connection; kept in the session (source of truth for the /monitor distributions).
        boolean clientVersionChanged = SessionAccessor.changeClientVersion(serverSession, StateAccessor.getClientVersion(clientState), true);
        boolean pwaChanged = SessionAccessor.changePwa(serverSession, StateAccessor.getPwa(clientState), true);
        boolean clientProfileChanged = SessionAccessor.changeClientProfile(serverSession, StateAccessor.getClientProfile(clientState), true);
        // Since clients communicate the runId on first connection or reconnection, the sessionId must be synced in both cases (on reconnection, the session id may have changed)
        boolean sessionIdSyncedChanged = runId != null && SessionAccessor.changeServerSessionIdSynced(serverSession, false);
        if (userIdChanged || runIdChanged || backofficeChanged || clientVersionChanged || pwaChanged || clientProfileChanged || sessionIdSyncedChanged || forceStore)
            return storeServerSession(serverSession);
        return Future.succeededFuture(serverSession);
    }

    private static Object syncIncomingClientStateFromServerSession(Object clientState, IsolatedSession serverSession) {
        // clientState.serverSessionId <= serverSession.id ? ALWAYS, because this is the server session that is responsible for the session id
        clientState = StateAccessor.setServerSessionId(clientState, serverSession.id(), true);
        // clientState.userId <= serverSession.userId ? YES IF NOT SET, otherwise this means the client switched user, so we keep that info
        clientState = StateAccessor.setUserId(clientState, SessionAccessor.getUserId(serverSession), false);
        // clientState.runId <= serverSession.runId ? YES IF NOT SET, otherwise this means the client communicates it, so we keep that info
        clientState = StateAccessor.setRunId(clientState, SessionAccessor.getRunId(serverSession), false);
        // clientState.backoffice <= serverSession.backoffice ? YES IF NOT SET, otherwise this means the client communicates it, so we keep that info
        clientState = StateAccessor.setBackoffice(clientState, SessionAccessor.isBackoffice(serverSession), false);
        // clientState.clientVersion <= serverSession.clientVersion ? ALWAYS: the client sends its version once at
        // connection (kept in the session), so copy it into every call's state — lets server code read the caller's
        // client version (e.g. the /monitor Analyze capture tags the plan with the client version that ran it).
        clientState = StateAccessor.setClientVersion(clientState, SessionAccessor.getClientVersion(serverSession));
        return clientState;
    }

    private static Future<IsolatedSession> storeServerSession(IsolatedSession serverSession) {
        return serverSession.store()
            .onFailure(Console::error)
            .map(x -> serverSession);
    }

    // ======================================== OUTGOING STATE ON SERVER ========================================
    // Sync methods to be used on the server side, when the server is about to send a state generated by the server back to the client

    public static Object syncOutgoingState(Object outgoingState, IsolatedSession serverSession) {
        String outgoingStateCapture = LOG_STATES ? "" + outgoingState : null; // capturing state before changes for logs

        // serverSession.id <= outgoingState.serverSessionId ? NEVER (serverSession.id can't be changed at this point)
        // serverSession.userId <= outgoingState.userId ? YES IF SET, as this means the server switched or logged-in user, so we memorize that info in the session
        boolean userIdChanged = SessionAccessor.changeUserId(serverSession, StateAccessor.getUserId(outgoingState), true);
        // serverSession.runId <= outgoingState.runId ? NEVER, because this is info can only come from an incoming outgoingState.
        // outgoingState.sessionId <= serverSession.id ? ONLY if the client doesn't know it already
        boolean sessionIdSyncedChanged = false;
        if (!SessionAccessor.isServerSessionIdSynced(serverSession)) {
            outgoingState = StateAccessor.setServerSessionId(outgoingState, serverSession.id(), true);
            sessionIdSyncedChanged = SessionAccessor.changeServerSessionIdSynced(serverSession, true);
        }
        // outgoingState.serverRunId <= StateAccessor.getServerRunId() ? ALWAYS (non-override), so client can detect server restarts
        outgoingState = StateAccessor.setServerRunId(outgoingState, StateAccessor.getServerRunId(), false);
        // outgoingState.userId <= serverSession.userId ? NO, we communicate this info only once to the client (when the server code explicitly sets outgoingState.userId)
        // outgoingState.runId <= serverSession.runId ? NEVER (ERASED), because it's always communicated in the opposite way (client => server)
        // outgoingState.backoffice <= serverSession.backoffice ? NEVER (ERASED), because it's always communicated in the opposite way (client => server)
        outgoingState = StateAccessor.setRunId(outgoingState, null, true);
        // Authorization push management:
        // If a user id is set in that direction, this means the server switched, logged-in or logged-out the user,
        // so we need in all cases to call the authorizer to push the new authorizations to the client.
        // Note: that authorizations push shouldn't contain the user id to avoid a loop here.
        if (userIdChanged || sessionIdSyncedChanged) {
            // Delay the authorization push until AFTER the session is fully stored. This ensures that by the time
            // the client receives the push (and retries getUserDetails), the session store has committed the new
            // userId. Without this ordering, the push can arrive at the client before the store completes,
            // causing a getUserDetails race where the server still sees LOGOUT_USER_ID.
            storeServerSession(serverSession).onComplete(ar -> {
                if (userIdChanged && userIdAuthorizer != null) {
                    // It's important to set the userId and runId in ThreadLocalStateHolder before calling userIdAuthorizer
                    // because it will load the authorizations from userId and push them to the runId client.

                    // Most of the time the runId is in the server session, which matches the associated client. An exception to
                    // that rule is the magic link, where the magic link client and the login client are different. If the magic
                    // link is valid, the authorizations must be pushed to the login client and not to the magic link client
                    // associated with this session. The magic link AuthenticationGatewayProvider indicated this by setting the
                    // login client runId in the server state.

                    // Creating a new state from the session => should contain the userId, runId, and eventually other info (ex: backoffice)
                    Object state = StateAccessor.createStateFromSession(serverSession);
                    ThreadLocalStateHolder.runWithState(state, () -> userIdAuthorizer.apply(null));
                }
            });
        } else if (userIdChanged && userIdAuthorizer != null) {
            Object state = StateAccessor.createStateFromSession(serverSession);
            ThreadLocalStateHolder.runWithState(state, () -> userIdAuthorizer.apply(null));
        }

        if (LOG_STATES)
            Console.log("👈👈 Outgoing state: " + outgoingState + " << " + outgoingStateCapture);

        return outgoingState;
    }

}
