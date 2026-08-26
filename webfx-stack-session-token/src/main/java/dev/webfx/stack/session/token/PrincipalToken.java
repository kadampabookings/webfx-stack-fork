package dev.webfx.stack.session.token;

import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.platform.ast.json.Json;
import dev.webfx.stack.com.serial.SerialCodecManager;

/**
 * Turns an authenticated principal into a token the client can hold, and back again.
 *
 * <p>The principal is serialized with the same codecs that already carry it over the wire, so any
 * principal type an application registers is covered without this class knowing what any of them are.
 * That matters: the alternative — signing one known principal type — leaves every other type as the way
 * in, which is how the SSO principals stayed forgeable while the main one looked defended.
 *
 * <p><b>Decoding happens only after the signature holds.</b> {@code SerialCodecManager} dispatches on a
 * {@code $codec} string against a global registry with no per-field constraint, so decoding attacker-
 * supplied text chooses which class to instantiate from attacker-supplied input. Here the text has been
 * proved to be something this server wrote, so the codec id was ours too. Reversing these two steps
 * would be a far worse bug than the one this class exists to fix, and it would still pass every test
 * that only checks the happy path.
 *
 * <p><b>KNOWN ISSUE — a verified principal may not {@code equals()} the one that was minted.</b>
 * Serializing to JSON text and parsing it back does not preserve the numeric type of a field: a
 * {@code ModalityUserPrincipal} minted with {@code Integer} ids comes back with {@code Byte} ids for
 * small values, and {@code Integer.equals(Byte)} is false. Measured with both the generic and the Vert.x
 * AST factories. The existing wire path does NOT have this problem, because it passes AST objects rather
 * than text, so this is introduced by signing — which needs something byte-shaped to sign.
 *
 * <p>It matters more than a failing equality usually would. {@code ModalityUserPrincipal}'s own javadoc
 * records that the principal is "the cache key for authorizations and the value the session syncer
 * compares to detect a login transition". A principal that does not equal itself across a round trip
 * therefore means the authorization cache misses every time, and the syncer reads every message as a
 * fresh login — re-running the identity check and re-pushing the grant set on each one. It would not
 * fail; it would just quietly do all of that work forever, which is the kind of thing that is found in
 * production rather than in review.
 *
 * <p>This must be resolved before anything consumes a verified principal. The fix belongs at the layer
 * that owns what identity MEANS, not here: either the principal compares its ids by numeric value rather
 * than by {@code Object.equals}, or its codec decodes them to one canonical type. Choosing between those
 * is a change to a core Modality class with deliberate equality semantics — a support view must not equal
 * the account holder's own session — so it is not made unilaterally from inside a signing utility.
 *
 * @author Bruno Salmon
 */
public final class PrincipalToken {

    private PrincipalToken() {}

    /**
     * Mints a token asserting this principal until the given time.
     *
     * <p>Call this only where a credential has actually been verified. A token is worth exactly what the
     * check behind it was worth, and one minted from an unverified claim is a signed lie — indistinguish-
     * able from the real thing, and trusted the same way.
     */
    public static String mint(Object principal, long expiryMillis) {
        if (principal == null)
            return null;
        Object encoded = SerialCodecManager.encodeToJson(principal);
        if (!(encoded instanceof ReadOnlyAstObject))
            // A principal that serializes to a bare string or number carries no codec id, so it could not
            // be decoded back to its own type. Refuse rather than mint something unusable.
            throw new IllegalArgumentException("Principal type is not serializable to an object: " + principal.getClass());
        return SignedToken.mint(Json.formatObject((ReadOnlyAstObject) encoded), expiryMillis);
    }

    /**
     * The principal this server asserted, or null for anything it did not — forged, altered, expired,
     * malformed, or minted under a key no longer accepted.
     *
     * <p>Null for every failure, deliberately: a caller has one thing to check, and cannot accidentally
     * treat "expired" as a lesser problem than "forged" when both mean the same thing here — this
     * request has no proven identity.
     */
    public static Object verify(String token, long nowMillis) {
        String json = SignedToken.verify(token, nowMillis);
        if (json == null)
            return null;
        try {
            return SerialCodecManager.decodeFromAstObject(Json.parseObject(json));
        } catch (Exception e) {
            // Signed by us, but no longer decodable — a codec removed or renamed since it was minted.
            // Treated as no identity rather than propagated: the caller asked who this is, and the honest
            // answer is that we can no longer tell.
            return null;
        }
    }
}
