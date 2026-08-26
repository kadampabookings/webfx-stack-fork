package dev.webfx.stack.session.token;

import dev.webfx.stack.com.serial.SerialCodecManager;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;
import one.modality.crm.shared.services.authn.serial.ModalityUserPrincipalSerialCodec;
import one.modality.crm.shared.services.authn.ModalityGuestPrincipal;
import one.modality.crm.shared.services.authn.serial.ModalityGuestPrincipalSerialCodec;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Round-trips real Modality principals through mint and verify.
 *
 * <p>Two of these once failed: a principal minted with Integer ids came back with Byte ids and did not
 * equals() itself. They pass because the principals were given value equality, NOT because the check was
 * relaxed to accommodate them. Keep it that way — the "same person and account" and "support agent
 * preserved" cases are the ones that detect a principal type whose equality is by object identity, and
 * such a type breaks the authorization cache and the login-transition check without ever failing.
 *
 * <p>No test framework, for the reason recorded in webfx-stack-authz-core: this repository declares no
 * JUnit. Run from main(); it exits non-zero while the issue stands.
 */
public class PrincipalRoundTripCheck {
    static int pass=0, fail=0;
    static final long NOW = 1_000_000L;
    static void check(String w, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   "+w); } else { fail++; System.out.println("  FAIL "+w); }
    }
    public static void main(String[] a) {
        SerialCodecManager.registerSerialCodec(new ModalityUserPrincipalSerialCodec());
        SerialCodecManager.registerSerialCodec(new ModalityGuestPrincipalSerialCodec());
        SignedToken.setKeys(List.of("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));

        System.out.println("a registered user survives the round trip:");
        ModalityUserPrincipal user = new ModalityUserPrincipal(42, 7);
        String token = PrincipalToken.mint(user, NOW + 60_000);
        Object back = PrincipalToken.verify(token, NOW);
        check("comes back as ModalityUserPrincipal", back instanceof ModalityUserPrincipal);
        check("same person and account", user.equals(back));
        check("token does not expose the person id in the clear", !token.contains("42"));

        System.out.println("a support view keeps its restriction, which is the point:");
        ModalityUserPrincipal support = new ModalityUserPrincipal(42, 7, 99);
        Object supportBack = PrincipalToken.verify(PrincipalToken.mint(support, NOW + 60_000), NOW);
        check("still a support view after the round trip", ((ModalityUserPrincipal) supportBack).isSupportView());
        check("support agent preserved", support.equals(supportBack));
        check("a support view is NOT equal to the plain user", !supportBack.equals(back));

        System.out.println("a guest — the type whose verifyAuthenticated accepted anything:");
        ModalityGuestPrincipal guest = new ModalityGuestPrincipal("someone@example.com");
        Object guestBack = PrincipalToken.verify(PrincipalToken.mint(guest, NOW + 60_000), NOW);
        check("comes back as a guest", guestBack instanceof ModalityGuestPrincipal);
        check("email preserved", "someone@example.com".equals(((ModalityGuestPrincipal) guestBack).getEmail()));

        System.out.println("forgery and expiry, through the principal layer:");
        check("expired token yields no principal", PrincipalToken.verify(token, NOW + 60_000) == null);
        check("altered token yields no principal",
              PrincipalToken.verify(token.substring(0, token.length()-1) + "X", NOW) == null);
        check("a hand-written principal is not a token", PrincipalToken.verify(
              "{\"$codec\":\"ModalityUserPrincipal\",\"userPersonId\":1,\"userAccountId\":1}", NOW) == null);
        SignedToken.setKeys(List.of("ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.UTF_8)));
        check("another server's key yields no principal", PrincipalToken.verify(token, NOW) == null);

        System.out.println("\n"+pass+" passed, "+fail+" failed");
        if (fail>0) System.exit(1);
    }
}
