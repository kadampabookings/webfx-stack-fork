package dev.webfx.stack.session.token;

import dev.webfx.stack.com.serial.SerialCodecManager;
import dev.webfx.stack.session.state.LogoutUserId;
import dev.webfx.stack.session.state.StateAccessor;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;
import one.modality.crm.shared.services.authn.serial.ModalityUserPrincipalSerialCodec;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The three cases ServerSideStateSessionSyncer.applyIdentityToken must distinguish.
 *
 * <p>It mirrors that method rather than calling it, because the syncer needs a live session and bus to be
 * driven directly. Keep the two in step: if the syncer's logic changes, change this. The case worth
 * guarding hardest is the last kind — a token that does not verify must NOT fall back to the identity the
 * message claimed, or anyone could defeat the whole check by sending rubbish and being handed the old,
 * weaker path.
 *
 * <p>The no-token case is the migration path and asserts that behaviour is UNCHANGED for clients that do
 * not send one. That assertion stops being desirable at the flip, when a missing token must start being
 * refused; change it then rather than letting it quietly outlive its purpose.
 */
public class VerifyCheck {
    static int pass=0, fail=0;
    static void check(String w, boolean ok){ if(ok){pass++;System.out.println("  ok   "+w);} else {fail++;System.out.println("  FAIL "+w);} }

    // Mirrors ServerSideStateSessionSyncer.applyIdentityToken.
    static void applyIdentityToken(Object clientState) {
        String token = StateAccessor.getUserToken(clientState);
        if (token == null || token.isEmpty()) return;
        Object principal = PrincipalToken.verify(token, System.currentTimeMillis());
        StateAccessor.setUserId(clientState, principal != null ? principal : LogoutUserId.LOGOUT_USER_ID);
    }

    public static void main(String[] a) {
        SerialCodecManager.registerSerialCodec(new ModalityUserPrincipalSerialCodec());
        SignedToken.setKeys(List.of("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        ModalityUserPrincipal real = new ModalityUserPrincipal(42, 7);
        ModalityUserPrincipal impostorClaim = new ModalityUserPrincipal(1, 1);

        System.out.println("no token — the migration path, behaviour must be unchanged:");
        Object s1 = StateAccessor.createUserIdState(real);
        applyIdentityToken(s1);
        check("claimed identity is left exactly as it was", real.equals(StateAccessor.getUserId(s1)));

        System.out.println("valid token — the proven identity wins over the claim:");
        Object s2 = StateAccessor.createUserIdState(impostorClaim);
        StateAccessor.setUserToken(s2, PrincipalToken.mint(real, System.currentTimeMillis() + 60_000));
        applyIdentityToken(s2);
        check("token's principal replaces the claim", real.equals(StateAccessor.getUserId(s2)));
        check("the claimed identity is discarded", !impostorClaim.equals(StateAccessor.getUserId(s2)));

        System.out.println("token present but not valid — must NOT fall back to the claim:");
        Object s3 = StateAccessor.createUserIdState(impostorClaim);
        StateAccessor.setUserToken(s3, "not-a-real-token");
        applyIdentityToken(s3);
        check("forged token yields logged out", LogoutUserId.isLogoutUserIdOrNull(StateAccessor.getUserId(s3)));
        check("the claim is NOT honoured as a fallback", !impostorClaim.equals(StateAccessor.getUserId(s3)));

        Object s4 = StateAccessor.createUserIdState(real);
        StateAccessor.setUserToken(s4, PrincipalToken.mint(real, System.currentTimeMillis() - 1));
        applyIdentityToken(s4);
        check("expired token yields logged out, not the claim", LogoutUserId.isLogoutUserIdOrNull(StateAccessor.getUserId(s4)));

        System.out.println("after a key rotation that dropped the old key:");
        Object s5 = StateAccessor.createUserIdState(real);
        StateAccessor.setUserToken(s5, PrincipalToken.mint(real, System.currentTimeMillis() + 60_000));
        SignedToken.setKeys(List.of("ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.UTF_8)));
        applyIdentityToken(s5);
        check("token from the retired key yields logged out", LogoutUserId.isLogoutUserIdOrNull(StateAccessor.getUserId(s5)));

        System.out.println("\n"+pass+" passed, "+fail+" failed");
        if (fail>0) System.exit(1);
    }
}
