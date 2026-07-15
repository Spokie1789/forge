package forge.ai.simulation;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import forge.ai.AIOption;
import forge.ai.LobbyPlayerAi;
import forge.card.CardStateName;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.item.IPaperCard;
import forge.item.PaperCard;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Template cache for {@link GameCopier}: build each card ONCE from its paper script,
 * then stamp per-copy instances by deep-copying the pristine template's states.
 *
 * Why: the simulation AI clones the whole game per candidate ability, and
 * {@code Card.fromPaperCard} re-parses every ability/trigger/replacement DSL string
 * from scratch for every card of every clone — Forge's own TODO in GameCopier calls
 * it "the vast majority of GameCopier execution time". {@link forge.game.card.CardState#copyFrom}
 * is the battle-tested deep-copy (clone effects and LKI use it) that re-hosts every
 * intrinsic trait object without any parsing.
 *
 * Correctness notes:
 * - Templates are pristine (fresh from the script, never in a zone, never mutated),
 *   so copying one is equivalent to re-parsing — a far weaker requirement than the
 *   "copy a LIVE card accurately" project the old GameCopier TODO gave up on.
 *   GameCopier still applies all live state on top, exactly as before.
 * - The castable Spell for permanents/lands/auras is intentionally NOT copied:
 *   {@code CardState.updateSpellAbilities} lazily creates {@code SpellPermanent}/
 *   {@code LandAbility} per card, so each instance builds its own (same as a
 *   freshly parsed card).
 * - {@code PaperCard.equals} ignores the functional variant, so variant cards
 *   bypass the cache entirely (they take the old parse path).
 * - Any failure falls back to {@code Card.fromPaperCard} for that card and the
 *   card is negative-cached so a pathological card costs the fast path only once.
 * - Kill switch: env FORGE_SIM_CARD_CACHE=0 disables the whole cache.
 *
 * Thread-safety matches the simulation AI's own contract (single game thread per
 * JVM): the cache map is concurrent, but concurrent copyFrom reads of one template
 * are only as safe as the rest of the sim path — which is not thread-safe anyway.
 */
public final class SimCardTemplates {
    private static final boolean ENABLED = !"0".equals(System.getenv("FORGE_SIM_CARD_CACHE"))
            && !"false".equalsIgnoreCase(String.valueOf(System.getenv("FORGE_SIM_CARD_CACHE")));
    private static final int MAX_ENTRIES = 4096;

    private static final Map<PaperCard, Card> CACHE = new ConcurrentHashMap<>();
    private static final Set<PaperCard> BROKEN = ConcurrentHashMap.newKeySet();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private static final AtomicLong COPIES = new AtomicLong();
    private static final AtomicLong BUILDS = new AtomicLong();
    private static final AtomicLong FALLBACKS = new AtomicLong();

    private static volatile Game templateGame;

    private SimCardTemplates() {
    }

    /**
     * Fast-path copy of {@code origCard}'s printed characteristics into {@code newGame}.
     * Returns null when the fast path does not apply (disabled, functional variant,
     * or a previous failure on this card) — the caller falls back to fromPaperCard.
     */
    public static Card copyFor(Card origCard, Player newOwner, Game newGame) {
        if (!ENABLED) {
            return null;
        }
        IPaperCard ipc = origCard.getPaperCard();
        if (!(ipc instanceof PaperCard)) {
            return null;
        }
        PaperCard pc = (PaperCard) ipc;
        if (!IPaperCard.NO_FUNCTIONAL_VARIANT.equals(pc.getFunctionalVariant()) || BROKEN.contains(pc)) {
            return null;
        }
        try {
            if (CACHE.size() > MAX_ENTRIES) {
                CACHE.clear();
            }
            Card template = CACHE.computeIfAbsent(pc, SimCardTemplates::buildTemplate);
            Card out = instantiate(template, pc, newOwner, newGame);
            long n = COPIES.incrementAndGet();
            if (n == 1L || n % 100_000L == 0L) {
                // loud-activity contract: a run that never prints this line is NOT
                // using the fast path, whatever its env claims
                System.out.println("SimCardTemplates: " + stats());
            }
            return out;
        } catch (Throwable t) {
            BROKEN.add(pc);
            FALLBACKS.incrementAndGet();
            if (WARNED.add(pc.getName())) {
                System.err.println("SimCardTemplates: fast copy failed for '" + pc.getName()
                        + "' — falling back to fromPaperCard for this card: " + t);
            }
            return null;
        }
    }

    private static Card buildTemplate(PaperCard pc) {
        BUILDS.incrementAndGet();
        Game g = game();
        return CardFactory.getCard(pc, g.getPlayers().get(0), g.nextCardId(), g);
    }

    private static Card instantiate(Card template, PaperCard pc, Player newOwner, Game newGame) {
        Card c = new Card(newGame.nextCardId(), pc, newGame);
        c.setOwner(newOwner);
        for (CardStateName sn : template.getStates()) {
            if (!c.getStates().contains(sn)) {
                c.addAlternateState(sn, false);
            }
            c.getState(sn).copyFrom(template.getState(sn), false);
        }
        // copyFrom updates each state's keyword cache as it runs, but a split card's
        // Original cache UNIONS the LeftSplit/RightSplit keywords (Fuse, Aftermath) —
        // and Original is copied first, before the halves exist. Refresh every state
        // now that they all hold their keywords.
        for (CardStateName sn : c.getStates()) {
            c.updateKeywordsCache(c.getState(sn));
        }
        c.setState(template.getCurrentStateName(), false);
        c.setBackSide(template.isBackSide());
        c.setText(template.getSpellText());
        c.setGamePieceType(template.getGamePieceType());
        // same tail as CardFactory.getCard (writes to the now-current Original state)
        c.setSetCode(pc.getEdition());
        c.setRarity(pc.getRarity());
        c.setImageKey(pc.getImageKey(false));
        return c;
    }

    /** The never-started dummy game all templates are bound to (never in any zone). */
    private static Game game() {
        Game g = templateGame;
        if (g == null) {
            synchronized (SimCardTemplates.class) {
                g = templateGame;
                if (g == null) {
                    GameRules rules = new GameRules(GameType.Constructed);
                    RegisteredPlayer rp = new RegisteredPlayer(new Deck());
                    rp.setPlayer(new LobbyPlayerAi("SimCardTemplates", Sets.newHashSet(AIOption.USE_SIMULATION)));
                    List<RegisteredPlayer> ps = Lists.newArrayList(rp);
                    Match match = new Match(rules, ps, "SimCardTemplates");
                    templateGame = g = new Game(ps, rules, match);
                }
            }
        }
        return g;
    }

    public static String stats() {
        return "copies=" + COPIES.get() + " templates=" + BUILDS.get()
                + " fallbacks=" + FALLBACKS.get() + " enabled=" + ENABLED;
    }
}
