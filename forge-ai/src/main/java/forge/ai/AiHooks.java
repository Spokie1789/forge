package forge.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * Optional external decision hooks for the heuristic AI.
 *
 * <p>Forge's AI decides everything with hand-written heuristics. These hooks let an
 * embedder (e.g. a neural policy running in the same JVM) influence two decisions
 * without forking the AI itself:
 *
 * <ul>
 *   <li>{@link SpellRanker} — re-orders the legal spell/ability candidate list before
 *       {@code AiController} walks it. The AI still applies every legality and
 *       affordability check and still plays the first candidate it *can* play, so a
 *       ranker cannot make an illegal or unaffordable play; it only changes which
 *       legal play is preferred.</li>
 *   <li>{@link MulliganOracle} — answers keep-or-mulligan, replacing
 *       {@code ComputerUtil.wantMulligan}.</li>
 * </ul>
 *
 * <p>A {@link DecisionObserver} can additionally watch both decisions (for logging
 * training data) without influencing them.
 *
 * <p><b>Contract.</b> All hooks are null by default: with none registered, this class
 * is inert and the AI behaves byte-for-byte as before. Every hook invocation is
 * wrapped so that ANY exception (or a malformed result) falls back to the heuristic
 * and increments a counter — a broken or slow policy degrades play quality, it never
 * crashes or hangs a game.
 *
 * <p><b>Fallbacks are counted and loud.</b> {@link #stats()} exposes per-cause
 * counters and the first failure of each kind is logged to stderr, because an
 * embedder that silently ran on heuristics while believing it ran its model would
 * draw false conclusions from the results.
 *
 * <p>Hooks are static and games run concurrently on a thread pool, so implementations
 * MUST be thread-safe and must derive their per-game context from the {@link Player}
 * they are handed (player.getGame() is the game; the player's name identifies the
 * side).
 */
public final class AiHooks {

    /** Re-orders a legal candidate list in place. Best candidate first. */
    public interface SpellRanker {
        void rank(Player player, List<SpellAbility> candidates);
    }

    /** Answers keep (true) / mulligan (false); null means "no opinion, use the heuristic". */
    public interface MulliganOracle {
        Boolean keepHand(Player player, CardCollectionView hand, int cardsToReturn);
    }

    /** Watches decisions without influencing them (training-data capture). */
    public interface DecisionObserver {
        void onSpellChoice(Player player, List<SpellAbility> candidates,
                           SpellAbility chosen, String source);

        void onMulligan(Player player, CardCollectionView hand, int cardsToReturn,
                        boolean keep, String source);
    }

    private static volatile SpellRanker spellRanker;
    private static volatile MulliganOracle mulliganOracle;
    private static volatile DecisionObserver observer;

    private static final AtomicLong rankCalls = new AtomicLong();
    private static final AtomicLong rankFallbacks = new AtomicLong();
    private static final AtomicLong mulliganCalls = new AtomicLong();
    private static final AtomicLong mulliganFallbacks = new AtomicLong();
    private static final AtomicLong observerErrors = new AtomicLong();

    private AiHooks() {
    }

    // ── Registration (called once at startup by the embedder) ──────────────

    public static void setSpellRanker(SpellRanker ranker) {
        spellRanker = ranker;
    }

    public static void setMulliganOracle(MulliganOracle oracle) {
        mulliganOracle = oracle;
    }

    public static void setDecisionObserver(DecisionObserver obs) {
        observer = obs;
    }

    public static boolean hasSpellRanker() {
        return spellRanker != null;
    }

    public static boolean hasMulliganOracle() {
        return mulliganOracle != null;
    }

    // ── Invocation (called by the AI; always safe) ─────────────────────────

    /**
     * Let the ranker re-order the candidate list. On any failure the list is left
     * exactly as the heuristic sorted it.
     *
     * @return true if the ranker reordered the list, false if the heuristic order stands
     */
    public static boolean applySpellRanker(Player player, List<SpellAbility> candidates) {
        final SpellRanker ranker = spellRanker;
        if (ranker == null || candidates == null || candidates.size() < 2) {
            return false;
        }
        rankCalls.incrementAndGet();
        // The heuristic order is the fallback, so keep a copy: a ranker that throws
        // halfway through an in-place sort must not leave a mangled list behind.
        final List<SpellAbility> heuristicOrder = List.copyOf(candidates);
        try {
            ranker.rank(player, candidates);
            if (candidates.size() != heuristicOrder.size()
                    || !candidates.containsAll(heuristicOrder)) {
                throw new IllegalStateException(
                    "ranker must permute the candidate list, not change its contents");
            }
            return true;
        } catch (Throwable t) {
            candidates.clear();
            candidates.addAll(heuristicOrder);
            warnOnce(rankFallbacks, "spell ranker", t);
            return false;
        }
    }

    /**
     * Ask the oracle whether to keep. Returns null when there is no oracle or it
     * failed — the caller then uses the heuristic.
     */
    public static Boolean applyMulliganOracle(Player player, CardCollectionView hand,
                                              int cardsToReturn) {
        final MulliganOracle oracle = mulliganOracle;
        if (oracle == null) {
            return null;
        }
        mulliganCalls.incrementAndGet();
        try {
            return oracle.keepHand(player, hand, cardsToReturn);
        } catch (Throwable t) {
            warnOnce(mulliganFallbacks, "mulligan oracle", t);
            return null;
        }
    }

    public static void notifySpellChoice(Player player, List<SpellAbility> candidates,
                                         SpellAbility chosen, String source) {
        final DecisionObserver obs = observer;
        if (obs == null) {
            return;
        }
        try {
            obs.onSpellChoice(player, candidates, chosen, source);
        } catch (Throwable t) {
            warnOnce(observerErrors, "decision observer (spell)", t);
        }
    }

    public static void notifyMulligan(Player player, CardCollectionView hand,
                                      int cardsToReturn, boolean keep, String source) {
        final DecisionObserver obs = observer;
        if (obs == null) {
            return;
        }
        try {
            obs.onMulligan(player, hand, cardsToReturn, keep, source);
        } catch (Throwable t) {
            warnOnce(observerErrors, "decision observer (mulligan)", t);
        }
    }

    // ── Diagnostics ───────────────────────────────────────────────────────

    /** Per-cause counters. An embedder reports these so a silent fallback is visible. */
    public static Map<String, Long> stats() {
        final Map<String, Long> out = new LinkedHashMap<>();
        out.put("rank_calls", rankCalls.get());
        out.put("rank_fallbacks", rankFallbacks.get());
        out.put("mulligan_calls", mulliganCalls.get());
        out.put("mulligan_fallbacks", mulliganFallbacks.get());
        out.put("observer_errors", observerErrors.get());
        return out;
    }

    public static void resetStats() {
        rankCalls.set(0);
        rankFallbacks.set(0);
        mulliganCalls.set(0);
        mulliganFallbacks.set(0);
        observerErrors.set(0);
    }

    /** Loud on the first failure of each kind; silent (but counted) afterwards. */
    private static void warnOnce(AtomicLong counter, String what, Throwable t) {
        if (counter.getAndIncrement() == 0) {
            System.err.println("[AiHooks] " + what + " FAILED — falling back to the "
                + "heuristic for this decision and every later failure (counted in "
                + "stats()): " + t);
            t.printStackTrace();
        }
    }
}
