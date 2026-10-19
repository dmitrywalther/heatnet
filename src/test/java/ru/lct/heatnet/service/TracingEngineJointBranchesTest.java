package ru.lct.heatnet.service;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.network.NetworkAssembler;
import ru.lct.heatnet.network.NetworkPlan;
import ru.lct.heatnet.network.PlanEdge;
import ru.lct.heatnet.network.PlanNode;
import ru.lct.heatnet.network.VariantResult;
import ru.lct.heatnet.routing.GeoUtil;
import ru.lct.heatnet.routing.ObstacleIndex;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises local-improvement orchestration with real plans, geometry and prices. */
class TracingEngineJointBranchesTest {
    @Test
    void jointProbeBudgetAllowsTheLastAttemptAndStopsFurtherRouting() throws Exception {
        Fixture f = new Fixture(SecondStep.FAIL);
        Class<?> builtClass = Class.forName("ru.lct.heatnet.service.TracingEngine$BuiltVariant");
        Constructor<?> built = builtClass.getDeclaredConstructor(NetworkPlan.class, VariantResult.class);
        built.setAccessible(true);
        Object current = built.newInstance(f.start, f.initial);
        Class<?> budgetClass = Class.forName("ru.lct.heatnet.service.TracingEngine$JointBudget");
        Constructor<?> constructor = budgetClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object budget = constructor.newInstance();
        Field attempted = budgetClass.getDeclaredField("attempted");
        attempted.setAccessible(true);
        attempted.setInt(budget, 15);
        Method method = TracingEngine.class.getDeclaredMethod("tryJointReconnect", InputModel.class,
                VariantPlanner.class, NetworkAssembler.class, builtClass, NetworkPlan.class,
                NetworkPlan.class, Object.class, budgetClass);
        method.setAccessible(true);
        NetworkPlan disconnected = f.start.copy();
        assertTrue(disconnected.removeBranch(f.primary.id));
        TracingEngine engine = new TracingEngine();

        assertNull(method.invoke(engine, f.input, f.planner, f.assembler, current,
                disconnected, f.primaryTrial(), f.primary.id, budget));
        assertEquals(1, f.planner.partnerAttempts);
        assertEquals(16, attempted.getInt(budget));
        assertNull(method.invoke(engine, f.input, f.planner, f.assembler, current,
                disconnected, f.primaryTrial(), f.primary.id, budget));
        assertEquals(1, f.planner.partnerAttempts, "exhausted budget must prevent another router call");
    }

    @Test
    void acceptsBothBranchesAtomicallyWhenThePartnerRepairsTheRemovalTurn() throws Exception {
        Fixture f = new Fixture(SecondStep.SUCCESS);
        NetworkPlan invalidPrimary = f.primaryTrial();
        VariantResult first = f.assemble(invalidPrimary);
        assertTrue(first.clearanceViolated, "removing the old junction creates a 116.565 degree turn");
        assertTrue(first.calculatedCost < f.initial.calculatedCost);
        assertTrue(first.score < f.initial.score);
        NetworkPlan complete = f.completedTrial();
        VariantResult expected = f.assemble(complete);
        assertFalse(expected.clearanceViolated);
        assertTrue(expected.calculatedCost < f.initial.calculatedCost);
        assertEquals(Set.of("primary", "partner", "other"), connectedIds(complete));
        List<String> before = state(f.start);

        Outcome improved = improve(f);

        assertTrue(improved.result.calculatedCost < f.initial.calculatedCost,
                "a valid cheaper two-branch result must be considered after strict one-branch rejection");
        assertTrue(f.planner.partnerAttempts > 0, "the partner is routed against the provisional first branch");
        assertFalse(improved.result.clearanceViolated);
        assertEquals(expected.calculatedCost, improved.result.calculatedCost, 1e-6);
        assertEquals(Set.of("primary", "partner", "other"), connectedIds(improved.plan));
        assertEquals(50.0, edgeTo(improved.plan, "primary").length(), 1e-6);
        assertEquals(Math.hypot(50, 100), edgeTo(improved.plan, "partner").length(), 1e-6);
        assertEquals(before, state(f.start), "all speculative changes must stay off the input plan");
    }

    @Test
    void failedPartnerReconnectLeavesTheOriginalPlanUntouched() throws Exception {
        Fixture f = new Fixture(SecondStep.FAIL);
        List<String> before = state(f.start);

        Outcome unchanged = improve(f);

        assertTrue(f.planner.partnerAttempts > 0, "the failing second step must actually be attempted");
        assertUnchanged(f, before, unchanged);
    }

    @Test
    void aPairWithAnUnrepairedTurnCannotReplaceTheOriginalPlan() throws Exception {
        Fixture f = new Fixture(SecondStep.INVALID_TURN);
        assertTrue(f.assemble(f.completedTrial()).clearanceViolated,
                "the second reconnect returns true but leaves a real invalid polyline");
        List<String> before = state(f.start);

        Outcome unchanged = improve(f);

        assertTrue(f.planner.partnerAttempts > 0);
        assertUnchanged(f, before, unchanged);
    }

    @Test
    void losingAnUnrelatedConsumerIsRejectedEvenWhenUnconnectedIdsRemainEmpty() throws Exception {
        Fixture f = new Fixture(SecondStep.LOSE_OTHER);
        NetworkPlan incomplete = f.completedTrial();
        VariantResult misleading = f.assemble(incomplete);
        assertFalse(misleading.clearanceViolated);
        assertTrue(misleading.unconnectedOksIds.isEmpty(), "removeBranch does not maintain this bookkeeping list");
        assertEquals(Set.of("primary", "partner"), connectedIds(incomplete));
        assertTrue(misleading.calculatedCost < f.initial.calculatedCost);
        List<String> before = state(f.start);

        Outcome unchanged = improve(f);

        assertTrue(f.planner.partnerAttempts > 0);
        assertUnchanged(f, before, unchanged);
    }

    @Test
    void aLowerScoreDoesNotJustifyHigherTotalCostForThePair() throws Exception {
        Fixture f = new Fixture(SecondStep.HIGHER_COST);
        VariantResult costly = f.assemble(f.completedTrial());
        assertFalse(costly.clearanceViolated);
        assertTrue(costly.calculatedCost > f.initial.calculatedCost,
                "real additional chamber charges make this shorter network more expensive");
        assertTrue(costly.score < f.initial.score, "the real length term still improves S");
        List<String> before = state(f.start);

        Outcome unchanged = improve(f);

        assertTrue(f.planner.partnerAttempts > 0);
        assertUnchanged(f, before, unchanged);
    }

    @Test
    void anIsolatedPaidChamberCannotSurviveTheAtomicPair() throws Exception {
        Fixture f = new Fixture(SecondStep.ORPHAN_CHAMBER);
        NetworkPlan orphaned = f.completedTrial();
        VariantResult candidate = f.assemble(orphaned);
        assertFalse(candidate.clearanceViolated, "line validation alone does not inspect isolated chambers");
        assertEquals(Set.of("primary", "partner", "other"), connectedIds(orphaned));
        assertTrue(candidate.calculatedCost < f.initial.calculatedCost);
        assertTrue(orphaned.nodes.stream().anyMatch(n -> n.kind == PlanNode.Kind.NEW_CHAMBER
                && orphaned.degree(n) == 0));
        List<String> before = state(f.start);

        Outcome unchanged = improve(f);

        assertTrue(f.planner.partnerAttempts > 0);
        assertUnchanged(f, before, unchanged);
    }

    private static void assertUnchanged(Fixture f, List<String> before, Outcome outcome) {
        assertSame(f.start, outcome.plan, "an unsuccessful pair must not commit either half");
        assertEquals(f.initial.calculatedCost, outcome.result.calculatedCost, 1e-6);
        assertEquals(Set.of("primary", "partner", "other"), connectedIds(outcome.plan));
        assertEquals(before, state(f.start));
    }

    private enum SecondStep { SUCCESS, FAIL, INVALID_TURN, LOSE_OTHER, HIGHER_COST, ORPHAN_CHAMBER }

    private static final class Fixture {
        final InputModel input = new InputModel();
        final NetworkPlan start = new NetworkPlan();
        final InputModel.OksPoint primary = oks("primary", 100, -100);
        final InputModel.OksPoint partner = oks("partner", -50, 100);
        final InputModel.OksPoint other = oks("other", -150, -100);
        final SecondStep mode;
        final ObstacleIndex obstacles;
        final NetworkAssembler assembler;
        final VariantResult initial;
        final ControlledPlanner planner;
        int extraChambers;

        Fixture(SecondStep mode) {
            this.mode = mode;
            input.oksPoints.addAll(List.of(primary, partner, other));
            input.chambers.add(source("west-source", -100, 0));
            input.chambers.add(source("south-source", 100, -150));
            obstacles = new ObstacleIndex(input);
            assembler = new NetworkAssembler(obstacles);
            PlanNode root = existing(start, "west-source", -100, 0);
            PlanNode junction = start.addNode(new PlanNode(PlanNode.Kind.NEW_CHAMBER, new Coordinate(0, 0)));
            add(start, root, junction);
            add(start, junction, consumer(start, primary));
            add(start, junction, consumer(start, partner));
            add(start, root, consumer(start, other));
            start.tieInsToExisting.put(root, 2);
            initial = assemble(start);
            assertFalse(initial.clearanceViolated, "the initial three-way junction is valid");
            assertEquals(Set.of("primary", "partner", "other"), connectedIds(start));
            if (mode == SecondStep.HIGHER_COST) {
                // Find an actual price/length tradeoff using charged chambers, without
                // modifying VariantResult fields or replacing the real assembler.
                for (int count = 1; count <= 10; count++) {
                    extraChambers = count;
                    VariantResult candidate = assemble(completedTrial());
                    if (candidate.calculatedCost > initial.calculatedCost && candidate.score < initial.score) break;
                }
            }
            planner = new ControlledPlanner(this);
        }

        VariantResult assemble(NetworkPlan plan) {
            return assembler.assemble(plan, "v1", "shared");
        }

        NetworkPlan primaryTrial() {
            NetworkPlan trial = start.copy();
            assertTrue(trial.removeBranch(primary.id));
            attachPrimary(trial);
            return trial;
        }

        NetworkPlan completedTrial() {
            NetworkPlan trial = primaryTrial();
            assertTrue(trial.removeBranch(partner.id));
            assertTrue(attachPartner(trial));
            return trial;
        }

        void attachPrimary(NetworkPlan plan) {
            PlanNode source = existing(plan, "south-source", 100, -150);
            add(plan, source, consumer(plan, primary));
            plan.tieInsToExisting.put(source, 1);
        }

        boolean attachPartner(NetworkPlan plan) {
            if (mode == SecondStep.FAIL) {
                // A router may already have changed its private copy before failing.
                plan.addNode(new PlanNode(PlanNode.Kind.NEW_CHAMBER, new Coordinate(-250, 250)));
                return false;
            }
            if (mode == SecondStep.LOSE_OTHER) assertTrue(plan.removeBranch(other.id));
            PlanNode root = existing(plan, "west-source", -100, 0);
            PlanNode target = consumer(plan, partner);
            if (mode == SecondStep.INVALID_TURN) {
                plan.addEdge(root, target, List.of(root.utm, new Coordinate(0, 0), target.utm));
            } else {
                PlanNode from = root;
                int count = mode == SecondStep.HIGHER_COST ? extraChambers : 0;
                for (int i = 1; i <= count; i++) {
                    double fraction = (double) i / (count + 1);
                    PlanNode middle = plan.addNode(new PlanNode(PlanNode.Kind.NEW_CHAMBER,
                            new Coordinate(-100 + 50 * fraction, 100 * fraction)));
                    add(plan, from, middle);
                    from = middle;
                }
                add(plan, from, target);
            }
            plan.tieInsToExisting.merge(root, 1, Integer::sum);
            if (mode == SecondStep.ORPHAN_CHAMBER) {
                plan.addNode(new PlanNode(PlanNode.Kind.NEW_CHAMBER, new Coordinate(-250, 250)));
            }
            return true;
        }
    }

    /** Controls only route proposals; the production engine decides whether to commit them. */
    private static final class ControlledPlanner extends VariantPlanner {
        final Fixture fixture;
        int partnerAttempts;

        ControlledPlanner(Fixture fixture) {
            super(fixture.input, fixture.obstacles);
            this.fixture = fixture;
        }

        @Override
        public List<ReconnectCandidate> reconnectCandidates(NetworkPlan disconnected, InputModel.OksPoint oks) {
            if (!"primary".equals(oks.id) || !hasRemovalTurn(disconnected)) return List.of();
            NetworkPlan trial = disconnected.copy();
            fixture.attachPrimary(trial);
            return List.of(new ReconnectCandidate(trial, false));
        }

        @Override
        public boolean reconnect(NetworkPlan disconnected, InputModel.OksPoint oks) {
            if (!"partner".equals(oks.id) || !primaryUsesNewSource(disconnected)) return false;
            partnerAttempts++;
            return fixture.attachPartner(disconnected);
        }

        private boolean hasRemovalTurn(NetworkPlan plan) {
            PlanEdge edge = edgeTo(plan, "partner");
            if (edge == null) return false;
            for (int i = 1; i + 1 < edge.coords.size(); i++) {
                if (edge.coords.get(i).distance(new Coordinate(0, 0)) < 1e-6
                        && GeoUtil.turnAngleDeg(edge.coords.get(i - 1), edge.coords.get(i), edge.coords.get(i + 1)) > 90) {
                    return true;
                }
            }
            return false;
        }

        private boolean primaryUsesNewSource(NetworkPlan plan) {
            PlanEdge edge = edgeTo(plan, "primary");
            return edge != null && ("south-source".equals(edge.a.inputId)
                    || "south-source".equals(edge.b.inputId));
        }
    }

    private static Outcome improve(Fixture fixture) throws Exception {
        Class<?> builtClass = Class.forName("ru.lct.heatnet.service.TracingEngine$BuiltVariant");
        Constructor<?> constructor = builtClass.getDeclaredConstructor(NetworkPlan.class, VariantResult.class);
        constructor.setAccessible(true);
        Object start = constructor.newInstance(fixture.start, fixture.initial);
        Method method = TracingEngine.class.getDeclaredMethod("localImprove", InputModel.class,
                VariantPlanner.class, NetworkAssembler.class, builtClass);
        method.setAccessible(true);
        Object improved = method.invoke(new TracingEngine(), fixture.input, fixture.planner, fixture.assembler, start);
        Field plan = builtClass.getDeclaredField("plan"), result = builtClass.getDeclaredField("result");
        plan.setAccessible(true);
        result.setAccessible(true);
        return new Outcome((NetworkPlan) plan.get(improved), (VariantResult) result.get(improved));
    }

    private static final class Outcome {
        final NetworkPlan plan;
        final VariantResult result;
        Outcome(NetworkPlan plan, VariantResult result) { this.plan = plan; this.result = result; }
    }

    private static InputModel.OksPoint oks(String id, double x, double y) {
        InputModel.OksPoint point = new InputModel.OksPoint();
        point.id = id;
        point.flowTph = 1;
        point.utm = GeoUtil.GF.createPoint(new Coordinate(x, y));
        return point;
    }

    private static InputModel.ExistingChamber source(String id, double x, double y) {
        InputModel.ExistingChamber chamber = new InputModel.ExistingChamber();
        chamber.id = id;
        chamber.utm = GeoUtil.GF.createPoint(new Coordinate(x, y));
        return chamber;
    }

    private static PlanNode existing(NetworkPlan plan, String id, double x, double y) {
        PlanNode node = plan.addNode(new PlanNode(PlanNode.Kind.EXISTING_CHAMBER, new Coordinate(x, y)));
        node.inputId = id;
        return node;
    }

    private static PlanNode consumer(NetworkPlan plan, InputModel.OksPoint point) {
        PlanNode node = plan.addNode(new PlanNode(PlanNode.Kind.OKS, point.utm.getCoordinate()));
        node.inputId = point.id;
        node.oksFlow = point.flowTph;
        return node;
    }

    private static void add(NetworkPlan plan, PlanNode from, PlanNode to) {
        plan.addEdge(from, to, List.of(from.utm, to.utm));
    }

    private static PlanEdge edgeTo(NetworkPlan plan, String id) {
        for (PlanNode node : plan.nodes) {
            if (node.kind == PlanNode.Kind.OKS && id.equals(node.inputId)) {
                List<PlanEdge> incident = plan.incident(node);
                return incident.size() == 1 ? incident.get(0) : null;
            }
        }
        return null;
    }

    private static Set<Object> connectedIds(NetworkPlan plan) {
        Set<PlanNode> reached = new HashSet<>();
        ArrayDeque<PlanNode> pending = new ArrayDeque<>();
        for (PlanNode node : plan.nodes) {
            if (node.kind == PlanNode.Kind.EXISTING_CHAMBER
                    || node.kind == PlanNode.Kind.NEW_CHAMBER && node.splitPipeDu != null) {
                if (reached.add(node)) pending.add(node);
            }
        }
        while (!pending.isEmpty()) {
            PlanNode node = pending.remove();
            for (PlanEdge edge : plan.incident(node)) {
                PlanNode other = edge.other(node);
                if (reached.add(other)) pending.add(other);
            }
        }
        Set<Object> ids = new HashSet<>();
        for (PlanNode node : reached) {
            if (node.kind == PlanNode.Kind.OKS && plan.degree(node) == 1) ids.add(node.inputId);
        }
        return ids;
    }

    private static List<String> state(NetworkPlan plan) {
        List<String> values = new ArrayList<>();
        for (PlanNode node : plan.nodes) {
            values.add("node:" + node.kind + ":" + node.inputId + ":" + node.utm + ":" + node.splitPipeDu);
        }
        for (PlanEdge edge : plan.edges) values.add("edge:" + edge.a.utm + ":" + edge.b.utm + ":" + edge.coords);
        plan.tieInsToExisting.forEach((node, count) -> values.add("ties:" + node.inputId + ":" + count));
        for (InputModel.OksPoint point : plan.unconnected) values.add("unconnected:" + point.id);
        Collections.sort(values);
        return values;
    }
}
