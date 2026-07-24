package forge.ai.simulation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Instrumentation and ablation switch for the three NON-spell-choice channels where the
 * simulation AI ("the searcher") can decide differently from the heuristic AI.
 *
 * <p>The searcher and the heuristic differ in only a handful of {@code AiController} places.
 * One of them is spell choice ({@code chooseSpellAbilityToPlay}); the other three are
 * modal-mode selection, sacrifice selection, and hidden-origin fetch selection. Splitting
 * those two groups apart is what the channel-ablation experiment measures: if the searcher's
 * edge survives with these three forced to heuristic, the edge lives in spell choice.
 *
 * <p>Note that the modal and sacrifice call sites gate on {@code simPicker != null}, not on
 * {@code useSimulation}, and {@code simPicker} is always constructed — so a heuristic pilot
 * already runs them. They are behaviour-identical for a heuristic pilot because the picker
 * falls through to the same heuristic call when it holds no plan; only the counters below
 * distinguish them.
 *
 * <p><b>Default is off.</b> With {@code FORGE_SIM_ABLATE_CHANNELS} unset every branch is
 * exactly what it was before this class existed, so normal runs are unaffected.
 *
 * <p>Environment:
 * <ul>
 *   <li>{@code FORGE_SIM_ABLATE_CHANNELS} — comma list of {@code modes,sacrifice,fetch},
 *       or {@code all}. Unset or empty ablates nothing. An unrecognised name is FATAL:
 *       a typo must never quietly produce an un-ablated arm that looks like an ablated one.</li>
 *   <li>{@code FORGE_SIM_CHANNEL_STATS} — directory for per-JVM counter snapshots. Unset
 *       keeps the counters in memory and writes nothing.</li>
 * </ul>
 *
 * <p>Counters are per-channel and written as one {@code sim_channels_&lt;pid&gt;.json} per JVM
 * (truncate-and-rewrite, so a killed JVM still leaves its last snapshot behind):
 * <ul>
 *   <li>{@code total} — every call to the {@code AiController} method, including calls made
 *       inside the searcher's own simulated game copies.</li>
 *   <li>{@code sim_branch} — calls that took the simulation branch. Ablation forces this to
 *       zero, which is exactly the validity check: an arm that claims to be ablated and
 *       reports a non-zero {@code sim_branch} is invalid.</li>
 *   <li>{@code plan_decided} — calls the searcher's <i>plan</i> actually answered in a real
 *       game (as opposed to falling through to the heuristic). This is the number that says
 *       whether a channel carries enough traffic for ablating it to mean anything.</li>
 * </ul>
 */
public final class SimChannelStats {

    /** The three non-spell-choice channels the searcher can decide. */
    public enum Channel {
        MODES("modes"),
        SACRIFICE("sacrifice"),
        FETCH("fetch");

        private final String key;

        Channel(final String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    private static final int CHANNELS = Channel.values().length;

    private static final AtomicLong[] TOTAL = newCounters();
    private static final AtomicLong[] SIM_BRANCH = newCounters();
    private static final AtomicLong[] PLAN_DECIDED = newCounters();
    private static final boolean[] ABLATED = new boolean[CHANNELS];

    private static final Path STATS_DIR;

    /**
     * Snapshots are time-throttled rather than count-throttled, and the shutdown hook is a
     * backstop rather than the mechanism: these JVMs are routinely force-killed on a wedge,
     * so a counter that is only durable at exit is a counter that gets lost exactly when the
     * run is interesting. At most one small rewrite every {@link #FLUSH_INTERVAL_NANOS}.
     */
    private static final long FLUSH_INTERVAL_NANOS = 2_000_000_000L;
    private static final AtomicLong LAST_FLUSH_NANOS = new AtomicLong(Long.MIN_VALUE);

    static {
        final String spec = System.getenv()
                .getOrDefault("FORGE_SIM_ABLATE_CHANNELS", "").trim().toLowerCase(Locale.ROOT);
        for (final String raw : spec.split(",")) {
            final String token = raw.trim();
            if (token.isEmpty()) {
                continue;
            }
            if ("all".equals(token)) {
                Arrays.fill(ABLATED, true);
                continue;
            }
            boolean matched = false;
            for (final Channel channel : Channel.values()) {
                if (channel.key.equals(token)) {
                    ABLATED[channel.ordinal()] = true;
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                throw new IllegalStateException("FORGE_SIM_ABLATE_CHANNELS: unknown channel '"
                        + token + "' (expected any of modes, sacrifice, fetch, all)");
            }
        }

        final String dir = System.getenv().getOrDefault("FORGE_SIM_CHANNEL_STATS", "").trim();
        if (dir.isEmpty()) {
            STATS_DIR = null;
        } else {
            STATS_DIR = Paths.get(dir);
            try {
                Files.createDirectories(STATS_DIR);
            } catch (final IOException e) {
                throw new IllegalStateException("FORGE_SIM_CHANNEL_STATS is not writable: " + dir, e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(SimChannelStats::flush));
        }
    }

    private SimChannelStats() {
    }

    private static AtomicLong[] newCounters() {
        final AtomicLong[] counters = new AtomicLong[Channel.values().length];
        for (int i = 0; i < counters.length; i++) {
            counters[i] = new AtomicLong();
        }
        return counters;
    }

    /** True when this channel is forced down its heuristic path for the whole JVM. */
    public static boolean ablated(final Channel channel) {
        return ABLATED[channel.ordinal()];
    }

    /** Record one call to a channel and which branch it took. */
    public static void recordCall(final Channel channel, final boolean simBranch) {
        TOTAL[channel.ordinal()].incrementAndGet();
        if (simBranch) {
            SIM_BRANCH[channel.ordinal()].incrementAndGet();
        }
        maybeFlush();
    }

    private static void maybeFlush() {
        if (STATS_DIR == null) {
            return;
        }
        final long now = System.nanoTime();
        final long last = LAST_FLUSH_NANOS.get();
        if (now - last >= FLUSH_INTERVAL_NANOS && LAST_FLUSH_NANOS.compareAndSet(last, now)) {
            flush();
        }
    }

    /** Record that the searcher's stored plan supplied this channel's answer in a real game. */
    public static void recordPlanDecision(final Channel channel) {
        PLAN_DECIDED[channel.ordinal()].incrementAndGet();
        maybeFlush();
    }

    /** One-line provenance for the run log; also forces class init so a bad env var fails at boot. */
    public static String describeAblation() {
        final StringBuilder sb = new StringBuilder();
        for (final Channel channel : Channel.values()) {
            if (ABLATED[channel.ordinal()]) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(channel.key);
            }
        }
        final String ablated = sb.length() == 0 ? "none (searcher behaviour unchanged)" : sb.toString();
        return "ablated=" + ablated + " stats="
                + (STATS_DIR == null ? "off" : STATS_DIR.toString());
    }

    /** Write this JVM's cumulative snapshot, replacing the previous one. */
    public static synchronized void flush() {
        if (STATS_DIR == null) {
            return;
        }
        final long pid = ProcessHandle.current().pid();
        final StringBuilder sb = new StringBuilder(384);
        sb.append("{\"pid\":").append(pid);
        for (final Channel channel : Channel.values()) {
            final int i = channel.ordinal();
            sb.append(",\"").append(channel.key).append("_total\":").append(TOTAL[i].get())
              .append(",\"").append(channel.key).append("_sim_branch\":").append(SIM_BRANCH[i].get())
              .append(",\"").append(channel.key).append("_plan_decided\":").append(PLAN_DECIDED[i].get())
              .append(",\"").append(channel.key).append("_ablated\":").append(ABLATED[i]);
        }
        sb.append("}\n");
        try {
            Files.write(STATS_DIR.resolve("sim_channels_" + pid + ".json"),
                    sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (final IOException e) {
            System.err.println("SimChannelStats: could not write snapshot: " + e);
        }
    }
}
