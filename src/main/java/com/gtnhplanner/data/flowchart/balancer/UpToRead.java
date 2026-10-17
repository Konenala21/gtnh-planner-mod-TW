package com.gtnhplanner.data.flowchart.balancer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import org.ojalgo.optimisation.Variable;

import com.gtnhplanner.data.flowchart.Drawer;

/**
 * The Up to read-out (docs/design/up-to.md, "The read-out"; the website's {@code readout.ts}): why each Up to card runs
 * where it does at the committed point, {@link SolutionView#upTo()}. Every answer is a probe's optimum compared with
 * the
 * point, under the trading the point was settled under ({@link SolveContext#committedTrades}); one counted budget per
 * island ({@link #budget}, work never time), past which a pool's reason is null.
 *
 * <p>
 * The reasons are old Build's: what a card's inputs allow is weighed against what its outputs allow, and a card at full
 * speed is a bottleneck when someone downstream goes short of what it makes.
 * <ul>
 * <li>Below its capacity, with every other pool held at least at its level (free to rise, never to give way):
 * {@code capable} is the most it reaches with a free sink at every output (its inputs as they are), {@code disposal}
 * the most with every input fed freely (its outputs as they are). Outputs strictly tighter is CLOGGED (UNUSED at zero);
 * otherwise, short at its inputs (and feeding them raises it) is STARVED, the input story first as in old Build; else
 * LIMITED. {@code port} is the first port on that side whose free end alone (the other side free) raises it,
 * {@code tied} the others that do; when only several together do, the first of them and the rest.</li>
 * <li>At its capacity: BOTTLENECK when the rest of the plan would use more of it (with its cap and every push band
 * gone,
 * the Up to pools and unmet drawers it feeds do better than now; {@code wants} the least machine time that does it), or
 * when it makes what a STARVED pool's short port takes; an Exactly card a forced input still feeds on one of its output
 * networks wants more too. Else FULL.</li>
 * </ul>
 * The pools below their capacity are read first (pool order), then those at it: an at-capacity pool's reason looks at
 * who is starved.
 */
public final class UpToRead {

    /** The read-out's models per island, at the least... */
    public static final int MAX_READOUT_LPS = 96;
    /** ...and per Up to pool on it, when that comes to more (a pool below its capacity takes three to five). */
    public static final int READOUT_LPS_PER_POOL = 8;
    /** A pool within this of its capacity (in levels) is at it. */
    static final double AT_CAPACITY = 1e-6;

    private UpToRead() {}

    /** The read-out's budget for a context: counted from its pools, never from time. */
    public static int budget(final int pools) {
        return Math.max(MAX_READOUT_LPS, READOUT_LPS_PER_POOL * pools);
    }

    /** Counted work: a model runs only while the budget lasts; once one is refused, every later pool reads null. */
    private static final class Budget {

        boolean spent;
        private int left;

        Budget(final int left) {
            this.left = left;
        }

        /** The model's result, or null when the budget is spent. */
        @Nullable
        SolveResult run(final SolveContext ctx, final Handles h, final String label) {
            if (spent || left <= 0) {
                spent = true;
                return null;
            }
            left--;
            return Solver.run(ctx, h, label);
        }
    }

    /** A pool's read-out while it is worked out. */
    private static final class Read {

        final UUID node;
        final List<UUID> machines;
        final double capacity;
        final double reached;
        @Nullable
        UpToReason reason;
        @Nullable
        PortRef port;
        List<PortRef> tied = List.of();
        @Nullable
        Double wants;
        List<UUID> upstream = List.of();

        Read(final UUID node, final List<UUID> machines, final double capacity, final double reached) {
            this.node = node;
            this.machines = machines;
            this.capacity = capacity;
            this.reached = reached;
        }

        void take(final Why why) {
            reason = why.reason;
            port = why.port;
            tied = why.tied;
            wants = why.wants;
        }

        UpToReadout readout() {
            return new UpToReadout(node, machines, capacity, reached, reason, port, tied, wants, upstream);
        }
    }

    private record Why(UpToReason reason, @Nullable PortRef port, List<PortRef> tied, @Nullable Double wants) {

        static Why of(final UpToReason reason) {
            return new Why(reason, null, List.of(), null);
        }
    }

    /** One side's short port: the first that holds it back, and the others that do. */
    private record Short(PortRef port, List<PortRef> tied) {}

    /** The read-out of every Up to pool, by {@code node}; empty when the context has none. */
    public static List<UpToReadout> of(final SolveContext ctx) {
        final List<Integer> pools = ctx.upToPools;
        if (pools.isEmpty() || ctx.point() == null) return List.of();
        final double[] extents = ctx.extents();
        final Solver.Trades trades = ctx.committedTrades;
        final Budget budget = new Budget(budget(pools.size()));
        final Map<Integer, Read> read = new LinkedHashMap<>();
        for (final int p : pools) {
            final ModelData.Pool data = ctx.model.pools.get(p);
            final List<UUID> machines = new ArrayList<>();
            for (final int m : data.machines()) machines.add(ctx.model.machines.get(m).spec.id());
            read.put(p, new Read(machines.get(0), machines, data.capacity(), ctx.poolTime(p, extents)));
        }
        final List<Integer> full = new ArrayList<>();
        for (final int p : pools) if (UpTo.levelOf(ctx, p, extents) >= 1 - AT_CAPACITY) full.add(p);
        for (final int p : pools) {
            if (full.contains(p) || trades == null || budget.spent) continue;
            final Why why = holdBackOf(ctx, p, UpTo.levelOf(ctx, p, extents), trades, budget);
            if (why != null) read.get(p)
                .take(why);
        }
        final List<int[]> starved = starvedPorts(ctx, read);
        for (final int p : full) {
            if (trades == null || budget.spent) continue;
            final Why why = wantsOf(ctx, p, trades, budget, starved);
            if (why != null) read.get(p)
                .take(why);
        }
        for (final int p : pools) {
            final Read r = read.get(p);
            if (r.reason != UpToReason.STARVED || r.port == null) continue;
            r.upstream = upstreamOf(ctx, p, r.port, read);
        }
        final List<UpToReadout> out = new ArrayList<>();
        for (final Read r : read.values()) out.add(r.readout());
        out.sort(
            (a, b) -> a.node()
                .compareTo(b.node()));
        return List.copyOf(out);
    }

    /** The connected input port a reference names, or null when it is not wired. */
    @Nullable
    private static Integer inputPort(final ModelData model, final PortRef ref) {
        final Integer m = model.machineIndex.get(ref.nodeId());
        return m == null ? null : model.portLookup.get(ModelData.portKey(m, ref.portIndex(), true));
    }

    /** Each STARVED pool's short ports (its {@code port} and {@code tied}) that are wired, as {pool, port}. */
    private static List<int[]> starvedPorts(final SolveContext ctx, final Map<Integer, Read> read) {
        final List<int[]> out = new ArrayList<>();
        for (final Map.Entry<Integer, Read> e : read.entrySet()) {
            final Read r = e.getValue();
            if (r.reason != UpToReason.STARVED || r.port == null) continue;
            final List<PortRef> refs = new ArrayList<>();
            refs.add(r.port);
            refs.addAll(r.tied);
            for (final PortRef ref : refs) {
                final Integer port = inputPort(ctx.model, ref);
                if (port != null) out.add(new int[] { e.getKey(), port });
            }
        }
        return out;
    }

    /** A pool's first output on a wire network (machine, then port order), as its per-machine rate; 0 when none. */
    private static double rateInto(final SolveContext ctx, final int pool, final int network) {
        final ModelData model = ctx.model;
        for (final int m : model.pools.get(pool)
            .machines()) {
            final ModelData.Machine md = model.machines.get(m);
            for (int i = 0; i < md.outQty.length; i++) {
                final Integer o = model.portLookup.get(ModelData.portKey(m, i, false));
                if (o == null || model.portComponent[o] != network) continue;
                final double rate = model.connectedPorts.get(o)
                    .qtyPerCraft() / ctx.countOf(m, 1);
                if (rate > 0) return rate;
            }
        }
        return 0;
    }

    /**
     * Beside starved pools: null when the pool makes nothing a STARVED pool's short port takes, else the machines it
     * would take to give each such port what its pool would draw at its own capacity (beyond what it draws now).
     */
    @Nullable
    private static Double starvedDemand(final SolveContext ctx, final int pool, final List<int[]> starved) {
        final ModelData model = ctx.model;
        final double[] extents = ctx.extents();
        Double demand = null;
        for (final int[] sp : starved) {
            final int s = sp[0], port = sp[1];
            if (s == pool) continue;
            final double rate = rateInto(ctx, pool, model.portComponent[port]);
            if (!(rate > 0)) continue;
            final ModelData.ConnectedPort taker = model.connectedPorts.get(port);
            final double now = extents[taker.machine()] * taker.qtyPerCraft();
            final ModelData.Pool data = model.pools.get(s);
            final double flat = taker.qtyPerCraft() * data.capacity() / ctx.countOf(taker.machine(), 1);
            final double level = UpTo.levelOf(ctx, s, extents);
            // A shared machine's recipe at capacity: its draw now, scaled up to the pool's whole time.
            final double atCapacity = data.machines()
                .size() == 1 || !(level > AT_CAPACITY) ? flat : now / level;
            demand = (demand == null ? 0 : demand) + Math.max(0, atCapacity - now) / rate;
        }
        return demand;
    }

    // ── At its capacity: FULL or BOTTLENECK ──

    /** The machines a pool's outputs reach, wire by wire (its own only round a loop). */
    private static Set<Integer> downstreamOf(final SolveContext ctx, final int pool) {
        final ModelData model = ctx.model;
        final List<List<Integer>> outputs = new ArrayList<>();
        for (int m = 0; m < model.machines.size(); m++) outputs.add(new ArrayList<>());
        for (int p = 0; p < model.connectedPorts.size(); p++) {
            final ModelData.ConnectedPort port = model.connectedPorts.get(p);
            if (!port.input()) outputs.get(port.machine())
                .add(p);
        }
        final Set<Integer> reached = new HashSet<>();
        final List<Integer> queue = new ArrayList<>(
            model.pools.get(pool)
                .machines());
        while (!queue.isEmpty()) {
            final int m = queue.remove(queue.size() - 1);
            for (final int p : outputs.get(m)) {
                for (final int e : model.connectedPorts.get(p)
                    .edges()) {
                    final int taker = model.connectedPorts.get(
                        model.edges.get(e)
                            .dstPort())
                        .machine();
                    if (reached.add(taker)) queue.add(taker);
                }
            }
        }
        return reached;
    }

    /** An unmet drawer asking for flow, and the rate it asked for. */
    private record Unmet(int drawer, double target) {}

    /** The unmet drawers with a lower bound linked to the pool or to what it feeds, in shortfall order. */
    private static List<Unmet> unmetFed(final SolveContext ctx, final int pool, final Set<Integer> below) {
        final ModelData model = ctx.model;
        final Set<Integer> own = new HashSet<>(
            model.pools.get(pool)
                .machines());
        final List<Unmet> out = new ArrayList<>();
        for (final DrawerReadout.Shortfall shortfall : ctx.shortfalls) {
            if (shortfall.rule() != Drawer.Rule.AT_LEAST && shortfall.rule() != Drawer.Rule.EXACTLY) continue;
            if (!(shortfall.target() > 0)) continue;
            int d = -1;
            for (int i = 0; i < model.drawers.size(); i++) {
                if (model.drawers.get(i)
                    .id()
                    .equals(shortfall.drawer())) {
                    d = i;
                    break;
                }
            }
            if (d < 0) continue;
            boolean seen = false;
            for (final Unmet u : out) seen |= u.drawer() == d;
            if (seen) continue;
            boolean fed = false;
            for (final PortRef link : model.drawers.get(d)
                .links()) {
                final Integer m = model.machineIndex.get(link.nodeId());
                if (m != null && (own.contains(m) || below.contains(m))) {
                    fed = true;
                    break;
                }
            }
            if (fed) out.add(new Unmet(d, shortfall.target()));
        }
        return out;
    }

    /** A drawer's total as row terms, over {@code scale}. */
    private static Solver.Terms drawerTerms(final SolveContext ctx, final Handles h, final int d, final double scale) {
        final ModelData.DrawerRow row = ctx.model.drawers.get(d);
        final Solver.Terms t = new Solver.Terms();
        for (int i = 0; i < row.extentMachines().length; i++)
            t.add(h.extentVars()[row.extentMachines()[i]], row.extentQty()[i] / scale);
        for (final int e : row.flowEdges()) t.add(h.flowVars()[e], 1 / scale);
        for (final int p : row.externalPorts()) t.add(h.extVars()[p], 1 / scale);
        return t;
    }

    /** "Would the plan use more": its model and the objective's terms (to maximise). */
    private record Using(Handles h, Solver.Terms terms) {}

    /**
     * The model of "would the plan use more": the trading held, the pool's cap and every push band gone; the objective
     * (to maximise) is the fed pools' levels and the fed drawers' met fractions, returned as row terms.
     */
    private static Using usingModel(final SolveContext ctx, final int pool, final Solver.Trades trades,
        final List<Integer> fedPools, final List<Unmet> fedDrawers) {
        ctx.droppedPools.add(pool);
        final Handles h;
        try {
            h = Solver.tradeModel(ctx, trades.withFloors(List.of()));
        } finally {
            ctx.droppedPools.remove(pool);
        }
        final Solver.Terms terms = new Solver.Terms();
        for (final int q : fedPools) terms.addAll(Solver.levelTerms(ctx, h, q));
        for (final Unmet u : fedDrawers) {
            // Its met fraction: f in 0..1 with total / target - f >= 0.
            final Variable f = h.model()
                .addVariable("met_" + u.drawer())
                .lower(0)
                .upper(1);
            drawerTerms(ctx, h, u.drawer(), u.target()).add(f, -1)
                .row(h, "met_" + u.drawer(), 0, Double.POSITIVE_INFINITY);
            terms.add(f, 1);
        }
        return new Using(h, terms);
    }

    /** Whether a machine's count is pinned exactly: a hard pin of its own, or an exact pool. */
    private static boolean isExact(final SolveContext ctx, final int m) {
        if (!Double.isNaN(ctx.pinnedExtent[m])) return true;
        for (int p = 0; p < ctx.model.pools.size(); p++) {
            final ModelData.Pool pool = ctx.model.pools.get(p);
            if (pool.exact() && !ctx.droppedPools.contains(p)
                && pool.machines()
                    .contains(m))
                return true;
        }
        return false;
    }

    /**
     * Machines an Exactly card on one of the pool's output networks still needs: each forced input there (a wired port
     * with no drawer bringing material in) over the pool's per-machine rate of that output.
     */
    private static double forcedDemand(final SolveContext ctx, final int pool) {
        final ModelData model = ctx.model;
        final StageOutcome point = ctx.point();
        final double dust = ctx.heuristics.numerics().dust
            * ctx.solutionScale(point.extents, point.flows, point.externals);
        final Set<Integer> counted = new HashSet<>();
        double demand = 0;
        for (final int m : model.pools.get(pool)
            .machines()) {
            for (int o = 0; o < model.connectedPorts.size(); o++) {
                final ModelData.ConnectedPort out = model.connectedPorts.get(o);
                if (out.machine() != m || out.input()) continue;
                final double perMachine = out.qtyPerCraft() / ctx.countOf(m, 1);
                if (!(perMachine > 0)) continue;
                final int network = model.portComponent[o];
                for (int q = 0; q < model.connectedPorts.size(); q++) {
                    final ModelData.ConnectedPort port = model.connectedPorts.get(q);
                    if (!port.input() || model.portComponent[q] != network
                        || counted.contains(q)
                        || ctx.drawnPorts.contains(q)) continue;
                    if (!isExact(ctx, port.machine()) || !(point.externals[q] > dust)) continue;
                    counted.add(q);
                    demand += point.externals[q] / perMachine;
                }
            }
        }
        return demand;
    }

    @Nullable
    private static Why wantsOf(final SolveContext ctx, final int pool, final Solver.Trades trades, final Budget budget,
        final List<int[]> starved) {
        final StageOutcome point = ctx.point();
        final double capacity = ctx.model.pools.get(pool)
            .capacity();
        final double tol = ctx.heuristics.numerics().readoutTol;
        final Set<Integer> below = downstreamOf(ctx, pool);
        final List<Integer> fedPools = new ArrayList<>();
        for (final int q : UpTo.keptUpTo(ctx)) {
            if (q == pool) continue;
            boolean fed = false;
            for (final int m : ctx.model.pools.get(q)
                .machines()) fed |= below.contains(m);
            if (fed) fedPools.add(q);
        }
        final List<Unmet> fedDrawers = unmetFed(ctx, pool, below);
        Double wants = null;
        if (!fedPools.isEmpty() || !fedDrawers.isEmpty()) {
            double now = 0;
            for (final int q : fedPools) now += UpTo.levelOf(ctx, q, point.extents);
            for (final Unmet u : fedDrawers) now += Math
                .min(1, ctx.drawerTotal(u.drawer(), point.extents, point.flows, point.externals) / u.target());
            final Using most = usingModel(ctx, pool, trades, fedPools, fedDrawers);
            most.terms()
                .price(-1);
            final SolveResult used = budget.run(ctx, most.h(), "up to: would the plan use more");
            if (used == null) return null;
            final double best = used.isRejected() ? now
                : most.terms()
                    .value();
            if (best > now + tol * Math.max(
                1,
                most.terms()
                    .size())) {
                // The least of it that does: hold the optimum (the first model's own point keeps it, so no room),
                // then the pool's own machine time as low as it goes.
                final Using least = usingModel(ctx, pool, trades, fedPools, fedDrawers);
                least.terms()
                    .row(least.h(), "using", best, Double.POSITIVE_INFINITY);
                Solver.priceLevel(ctx, least.h(), pool, 1);
                final SolveResult takes = budget.run(ctx, least.h(), "up to: how many it would take");
                if (takes == null) return null;
                if (!takes.isRejected()) {
                    final double time = ctx.poolTime(pool, takes.point().extents);
                    if (time > capacity * (1 + tol)) wants = time;
                }
            }
        }
        if (wants == null) {
            // Someone short of what it makes: a STARVED pool's short port on one of its output networks.
            final Double shortOf = starvedDemand(ctx, pool, starved);
            if (shortOf != null) wants = capacity + shortOf;
        }
        final double forced = forcedDemand(ctx, pool);
        if (forced > 0) wants = (wants == null ? capacity : wants) + forced;
        return wants == null ? Why.of(UpToReason.FULL) : new Why(UpToReason.BOTTLENECK, null, List.of(), wants);
    }

    // ── Below its capacity: STARVED, CLOGGED, UNUSED or LIMITED ──

    /**
     * A port a probe can give a free end: a wired one by a free column ({@code port}), an unwired one by lifting its
     * capped drawers ({@code drawers}).
     */
    private record Feed(PortRef ref, @Nullable Integer port, List<Integer> drawers) {}

    /**
     * The pool's ports on one side a free end could matter at, in machine then port order: every wired one, and every
     * unwired one a capped source (inputs) or product (outputs) drawer is linked to. Other unwired ports already are
     * free.
     */
    private static List<Feed> feedsOf(final SolveContext ctx, final int pool, final boolean input) {
        final ModelData model = ctx.model;
        final List<Feed> feeds = new ArrayList<>();
        for (final int m : model.pools.get(pool)
            .machines()) {
            final ModelData.Machine md = model.machines.get(m);
            final double[] qty = input ? md.inQty : md.outQty;
            for (int i = 0; i < qty.length; i++) {
                if (!(qty[i] > 0)) continue;
                final PortRef ref = new PortRef(md.spec.id(), i, input);
                final Integer port = model.portLookup.get(ModelData.portKey(m, i, input));
                if (port != null) {
                    feeds.add(new Feed(ref, port, List.of()));
                    continue;
                }
                final List<Integer> drawers = new ArrayList<>();
                for (int d = 0; d < model.drawers.size(); d++) {
                    final ModelData.DrawerRow row = model.drawers.get(d);
                    if (row.kind() != (input ? Drawer.Kind.SOURCE : Drawer.Kind.PRODUCT)
                        || Double.isNaN(ctx.drawerUpper[d])) continue;
                    if (row.links()
                        .contains(ref)) drawers.add(d);
                }
                if (!drawers.isEmpty()) feeds.add(new Feed(ref, null, drawers));
            }
        }
        return feeds;
    }

    /**
     * The most the pool's level reaches with free ends at {@code feeds}, others held from below; {@code fallback} when
     * nothing is found; null when the budget is spent.
     */
    @Nullable
    private static Double highest(final SolveContext ctx, final int pool, final Solver.Trades held,
        final List<Feed> feeds, final String label, final Budget budget, final double fallback) {
        final Map<Integer, Double> saved = new LinkedHashMap<>();
        for (final Feed f : feeds) for (final int d : f.drawers()) saved.putIfAbsent(d, ctx.drawerUpper[d]);
        for (final int d : saved.keySet()) ctx.drawerUpper[d] = Double.NaN;
        final Handles h;
        try {
            h = Solver.tradeModel(ctx, held);
        } finally {
            for (final Map.Entry<Integer, Double> e : saved.entrySet()) ctx.drawerUpper[e.getKey()] = e.getValue();
        }
        for (final Feed f : feeds) if (f.port() != null) Solver.feedPort(ctx, h, f.port());
        Solver.priceLevel(ctx, h, pool, -1);
        final SolveResult result = budget.run(ctx, h, label);
        if (result == null) return null;
        return result.isRejected() ? fallback : UpTo.levelOf(ctx, pool, result.point().extents);
    }

    /**
     * On one side, the first of {@code feeds} whose free end alone (with every one of {@code others} free too) lifts
     * the pool past {@code past}, and the others that do; when none does alone, the first of them and the rest (they
     * are short together). Null when the budget is spent.
     */
    @Nullable
    private static Short shortPort(final SolveContext ctx, final int pool, final Solver.Trades held,
        final List<Feed> feeds, final List<Feed> others, final double past, final String label, final Budget budget) {
        if (feeds.size() == 1) return new Short(
            feeds.get(0)
                .ref(),
            List.of());
        PortRef port = null;
        final List<PortRef> tied = new ArrayList<>();
        final double tol = ctx.heuristics.numerics().readoutTol;
        for (final Feed f : feeds) {
            final List<Feed> open = new ArrayList<>(others);
            open.add(f);
            final Double reach = highest(ctx, pool, held, open, label, budget, past);
            if (reach == null) return null;
            if (!(reach > past + tol)) continue;
            if (port == null) port = f.ref();
            else tied.add(f.ref());
        }
        if (port == null) {
            final List<PortRef> rest = new ArrayList<>();
            for (int i = 1; i < feeds.size(); i++) rest.add(
                feeds.get(i)
                    .ref());
            return new Short(
                feeds.get(0)
                    .ref(),
                rest);
        }
        return new Short(port, tied);
    }

    @Nullable
    private static Why holdBackOf(final SolveContext ctx, final int pool, final double level,
        final Solver.Trades trades, final Budget budget) {
        // Every other pool at least at its level, free to rise; this one free under its cap.
        final List<Solver.Band> floors = new ArrayList<>();
        for (final Solver.Band b : trades.floors())
            if (b.pool() != pool) floors.add(new Solver.Band(b.pool(), b.lo(), 1));
        final Solver.Trades held = trades.withFloors(floors);
        final List<Feed> ins = feedsOf(ctx, pool, true);
        final List<Feed> outs = feedsOf(ctx, pool, false);
        final double tol = ctx.heuristics.numerics().readoutTol;
        // What its inputs allow (every output free) and what its outputs allow (every input fed).
        final Double capable = outs.isEmpty() ? (Double) level
            : highest(ctx, pool, held, outs, "up to: outputs free", budget, level);
        if (capable == null) return null;
        final Double disposal = ins.isEmpty() ? (Double) level
            : highest(ctx, pool, held, ins, "up to: inputs fed", budget, level);
        if (disposal == null) return null;
        // The outputs strictly tighter: CLOGGED. A tie tells the input story first, as old Build does.
        if (disposal < capable - tol) {
            final Short at = shortPort(ctx, pool, held, outs, ins, disposal, "up to: one output free", budget);
            if (at == null) return null;
            final StageOutcome point = ctx.point();
            final double dust = ctx.heuristics.numerics().dust
                * ctx.solutionScale(point.extents, point.flows, point.externals);
            boolean idle = true;
            for (final int m : ctx.model.pools.get(pool)
                .machines()) idle &= point.extents[m] <= dust;
            return new Why(idle ? UpToReason.UNUSED : UpToReason.CLOGGED, at.port(), at.tied(), null);
        }
        if (capable < 1 - tol && !ins.isEmpty()) {
            // Short at its inputs only when feeding them (its outputs free) does raise it.
            final List<Feed> all = new ArrayList<>(ins);
            all.addAll(outs);
            final Double both = outs.isEmpty() ? disposal
                : highest(ctx, pool, held, all, "up to: all free", budget, capable);
            if (both == null) return null;
            if (both > capable + tol) {
                final Short at = shortPort(ctx, pool, held, ins, outs, capable, "up to: one input fed", budget);
                if (at == null) return null;
                return new Why(UpToReason.STARVED, at.port(), at.tied(), null);
            }
        }
        return Why.of(UpToReason.LIMITED);
    }

    /** STARVED's {@code upstream}: the FULL or BOTTLENECK Up to pools making what the short input takes, by node. */
    private static List<UUID> upstreamOf(final SolveContext ctx, final int pool, final PortRef shortPort,
        final Map<Integer, Read> read) {
        final ModelData model = ctx.model;
        final Integer port = inputPort(model, shortPort);
        if (port == null) return List.of();
        final int network = model.portComponent[port];
        final List<UUID> out = new ArrayList<>();
        for (final int q : ctx.upToPools) {
            if (q == pool) continue;
            final Read r = read.get(q);
            if (r.reason != UpToReason.FULL && r.reason != UpToReason.BOTTLENECK) continue;
            final Set<Integer> members = new HashSet<>(
                model.pools.get(q)
                    .machines());
            boolean makes = false;
            for (int p = 0; p < model.connectedPorts.size() && !makes; p++) {
                final ModelData.ConnectedPort cp = model.connectedPorts.get(p);
                makes = !cp.input() && members.contains(cp.machine()) && model.portComponent[p] == network;
            }
            if (makes) out.add(r.node);
        }
        out.sort(UUID::compareTo);
        return out;
    }
}
