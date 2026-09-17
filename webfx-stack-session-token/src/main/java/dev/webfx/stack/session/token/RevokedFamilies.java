package dev.webfx.stack.session.token;

import dev.webfx.platform.console.Console;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The session families known to have been revoked recently, so a token naming one can be refused on the
 * spot rather than at its next renewal.
 *
 * <h3>Why this exists at all, when renewal already refuses a revoked family</h3>
 *
 * <p>Because rotation cannot see the case anyone would call theft. Reuse detection fires when two holders
 * of one family both present tokens and one turns out to be behind; a stolen laptop whose owner never
 * touches that device again produces no second holder, no race, and no conflicting generation. The thief
 * renews unopposed. Deliberate revocation is the control that covers it — and a revocation that only
 * lands at the next renewal leaves the thief a whole access window, which is the entire exposure in the
 * case where the machine was taken unlocked and signed in.
 *
 * <h3>Why it does not violate "no database on the hot path"</h3>
 *
 * <p>What that rule avoids is the round TRIP, not the check. The token already carries its family id, and
 * a hash lookup beside an HMAC we compute anyway costs nothing measurable. That is what makes this the
 * one form of per-message revocation check the design can afford — and the reason it is permanent rather
 * than something switched on during an emergency. A control that has to be switched on after the incident
 * is off for the hours between the theft and somebody noticing, which is most of the exposure.
 *
 * <h3>Lossy on purpose</h3>
 *
 * <p><b>This set makes revocation prompt. The renewal check makes it certain.</b> It may be stale, it may
 * be missing entries after a restart, and an instance that never manages to poll still refuses every
 * revoked family at its next renewal. Nothing here is enforcement, which is precisely what allows it to
 * be cheap, to be dropped, and to be wrong in the safe direction.
 *
 * <h3>Why it is small</h3>
 *
 * <p>Every logout now revokes a family, and a member's absolute bound is a year out, so loading every
 * revoked family would accumulate a year of logouts to answer a question about the last few minutes. Only
 * recent revocations are kept: past {@link #RETENTION_MILLIS} a token of that family has long since had
 * to renew and been refused, so an old entry answers nothing the renewal path did not already answer.
 *
 * @author Claude Code
 */
public final class RevokedFamilies {

    /**
     * How long a revocation is worth remembering here.
     *
     * <p>Far longer than what it must cover — one access window, after which renewal has refused every
     * token of that family anyway — and no longer than an instance can RE-LEARN after a restart. That
     * second bound is the binding one: the store deletes a revoked row a day after it was revoked, so an
     * instance that came back with a longer window would hold entries its sibling could never obtain, and
     * the same token would be refused on sight by one task and not the other. Keeping the two in step
     * costs nothing, since a day is already about fifty access windows.
     */
    public static final long RETENTION_MILLIS = 24 * 60 * 60 * 1000L; // one day — see the store's retention sweep

    /**
     * What is known about one revoked family: when it was revoked, and whether its refusal has been
     * reported yet.
     *
     * <p>The flag is here rather than at the point of refusal because a client that ignores the logout —
     * a background tab, a media heartbeat, a reconnect loop — presents the same dead token on every
     * message. Logging each one would bury the identity log in the aftermath of exactly the bulk
     * revocation somebody is reading it to confirm.
     */
    private static final class Revoked {
        final long revokedAtMillis;
        volatile boolean refusalReported;

        Revoked(long revokedAtMillis) {
            this.revokedAtMillis = revokedAtMillis;
        }
    }

    /** family id → what is known about its revocation. */
    private static final Map<String, Revoked> REVOKED = new ConcurrentHashMap<>();

    private RevokedFamilies() {}

    /**
     * Whether this family is known here to be revoked. False for an unknown one, INCLUDING a family
     * revoked on another instance that this one has not polled yet — see the class note on being lossy.
     */
    public static boolean isRevoked(String familyId) {
        return familyId != null && REVOKED.containsKey(familyId);
    }

    /**
     * True the FIRST time a family's refusal is worth a log line, false every time after — see
     * {@link Revoked}. A family this set has forgotten answers true again, which is the harmless
     * direction: one line, not a flood.
     */
    public static boolean shouldReportRefusal(String familyId) {
        Revoked revoked = familyId == null ? null : REVOKED.get(familyId);
        if (revoked == null || revoked.refusalReported)
            return revoked == null;
        revoked.refusalReported = true;
        return true;
    }

    /** Records a revocation, whether this instance performed it or learned of it from the store. */
    public static void note(String familyId, long revokedAtMillis) {
        if (familyId != null)
            REVOKED.putIfAbsent(familyId, new Revoked(revokedAtMillis));
    }

    /**
     * Records a batch, and answers how many of them were new — which is what a poll has to report, since
     * an overlapping window re-reads the revocations it already knows on every pass.
     */
    public static int noteAll(Collection<SessionFamilyStore.Revocation> revocations) {
        int added = 0;
        for (SessionFamilyStore.Revocation revocation : revocations) {
            // Guarded here and not only in the store: this map throws on a null key, and the poll that
            // feeds it is the one loop that must not die — a store returning a row with no id would
            // otherwise end prompt revocation on this instance until the next deploy.
            if (revocation == null || revocation.familyId() == null)
                continue;
            if (REVOKED.putIfAbsent(revocation.familyId(), new Revoked(revocation.revokedAtMillis())) == null)
                added++;
        }
        return added;
    }

    /**
     * Drops entries revoked before {@code cutoffMillis}. Called by whatever polls the store, so the set
     * is pruned on the same clock it is filled on and nothing has to prune on the hot path.
     */
    public static void forgetOlderThan(long cutoffMillis) {
        int before = REVOKED.size();
        REVOKED.values().removeIf(revoked -> revoked.revokedAtMillis < cutoffMillis);
        int dropped = before - REVOKED.size();
        if (dropped > 0)
            Console.log("🛡 " + dropped + " revoked session family(ies) aged out of the prompt-refusal set"
                        + " — their tokens have long since been refused at renewal");
    }

    public static int size() {
        return REVOKED.size();
    }

    /**
     * Empties the set — safe at any moment, because it is a cache and never the enforcement.
     *
     * <p>Called when a store is registered: family ids belong to the store that issued them, so entries
     * from a previous one answer about sessions the new one has never heard of. In production that
     * happens once, at boot, with nothing to lose.
     */
    public static void clear() {
        REVOKED.clear();
    }
}
