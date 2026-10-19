package ru.lct.heatnet.service;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.network.NetworkAssembler;
import ru.lct.heatnet.network.NetworkPlan;
import ru.lct.heatnet.network.PlanNode;
import ru.lct.heatnet.network.VariantResult;
import ru.lct.heatnet.routing.GeoUtil;
import ru.lct.heatnet.routing.ObstacleIndex;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class JointBranchRepairTest {
    @Test
    void findsTheSameConflictWhenTheTurnIsSplitAcrossADegreeTwoNode() {
        for (PlanNode.Kind kind : List.of(PlanNode.Kind.TECHNICAL_NODE, PlanNode.Kind.NEW_CHAMBER)) {
            InputModel input = new InputModel();
            InputModel.OksPoint partner = point("partner", -50, 100);
            input.oksPoints.add(partner);
            NetworkPlan plan = new NetworkPlan();
            PlanNode root = node(plan, PlanNode.Kind.EXISTING_CHAMBER, -100, 0);
            PlanNode bend = node(plan, kind, 0, 0);
            add(plan, root, bend);
            add(plan, bend, consumer(plan, partner, partner.utm.getCoordinate()));

            assertTrue(JointBranchRepair.hasInvalidTurns(plan));
            assertEquals(List.of(partner), JointBranchRepair.partners(input, plan, plan.copy(), "primary"));
        }
    }

    @Test
    void findsTheTerminalThroughTechnicalAndOrdinaryTransitNodes() {
        Chain f = new Chain();
        assertTrue(JointBranchRepair.hasInvalidTurns(f.plan));

        List<InputModel.OksPoint> found = JointBranchRepair.partners(f.input, f.plan, f.plan.copy(), "primary");

        assertEquals(List.of("partner"), found.stream().map(p -> p.id).collect(Collectors.toList()));
        assertSame(f.partner, found.get(0), "the original OKS point, including its exact P, is retained");
    }

    @Test
    void doesNotTraverseAnActualBranchToChooseOneOfItsConsumers() {
        Chain f = new Chain();
        f.middle.kind = PlanNode.Kind.NEW_CHAMBER;
        InputModel.OksPoint another = point("another", -75, 50);
        f.input.oksPoints.add(another);
        add(f.plan, f.middle, consumer(f.plan, another, another.utm.getCoordinate()));
        assertEquals(3, f.plan.degree(f.middle));
        assertTrue(JointBranchRepair.hasInvalidTurns(f.plan));

        assertTrue(JointBranchRepair.partners(f.input, f.plan, f.plan.copy(), "primary").isEmpty(),
                "repairing a shared subtree would exceed a single dependent terminal branch");
    }

    @Test
    void stopsAtExistingSourcesEvenWhenTheyHaveOnlyTwoPlannedEdges() {
        for (PlanNode.Kind kind : List.of(PlanNode.Kind.EXISTING_CHAMBER, PlanNode.Kind.NEW_CHAMBER)) {
            Chain f = new Chain();
            f.middle.kind = kind;
            if (kind == PlanNode.Kind.NEW_CHAMBER) {
                f.middle.splitPipeDu = 400;
                InputModel.ExistingPipe pipe = new InputModel.ExistingPipe();
                pipe.id = "source-pipe";
                pipe.diameter = 400;
                pipe.utm = GeoUtil.segment(new Coordinate(-75, 50), new Coordinate(25, 50));
                f.input.pipes.add(pipe);
            } else {
                f.middle.inputId = "middle-source";
                InputModel.ExistingChamber source = new InputModel.ExistingChamber();
                source.id = f.middle.inputId;
                source.utm = GeoUtil.GF.createPoint(f.middle.utm);
                f.input.chambers.add(source);
            }
            assertEquals(2, f.plan.degree(f.middle));
            assertTrue(JointBranchRepair.hasInvalidTurns(f.plan));

            assertTrue(JointBranchRepair.partners(f.input, f.plan, f.plan.copy(), "primary").isEmpty(),
                    "planned degree two does not make a source node a removable transit node: " + kind);
        }
    }

    @Test
    void doesNotChooseThePrimaryAsItsOwnPartner() {
        Chain f = new Chain();
        assertTrue(JointBranchRepair.partners(f.input, f.plan, f.plan.copy(), "partner").isEmpty());
    }

    @Test
    void doesNotRepairANewUnrelatedTurnElsewhereOnTheMap() {
        Chain f = new Chain();
        NetworkPlan candidate = f.plan.copy();
        // Remove the original interior turn while retaining the same terminal chain.
        candidate.edges.get(0).coords = List.of(candidate.edges.get(0).a.utm, candidate.edges.get(0).b.utm);
        InputModel.OksPoint unrelated = point("unrelated", 950, 100);
        f.input.oksPoints.add(unrelated);
        PlanNode root = node(candidate, PlanNode.Kind.EXISTING_CHAMBER, 900, 0);
        root.inputId = "remote-source";
        PlanNode leaf = consumer(candidate, unrelated, unrelated.utm.getCoordinate());
        candidate.addEdge(root, leaf, List.of(root.utm, new Coordinate(1000, 0), leaf.utm));
        assertTrue(JointBranchRepair.hasInvalidTurns(f.plan));
        assertTrue(JointBranchRepair.hasInvalidTurns(candidate));

        assertTrue(JointBranchRepair.partners(f.input, f.plan, candidate, "primary").isEmpty(),
                "a distinct new routing defect is not the junction exposed by removing the primary");
    }

    @Test
    void aTransitCycleCannotHangPartnerDiscovery() {
        InputModel input = new InputModel();
        input.oksPoints.add(point("unrelated", 500, 500));
        NetworkPlan cycle = new NetworkPlan();
        PlanNode a = node(cycle, PlanNode.Kind.TECHNICAL_NODE, -100, 0);
        PlanNode b = node(cycle, PlanNode.Kind.TECHNICAL_NODE, -50, 100);
        PlanNode c = node(cycle, PlanNode.Kind.TECHNICAL_NODE, -150, 100);
        cycle.addEdge(a, b, List.of(a.utm, new Coordinate(0, 0), b.utm));
        add(cycle, b, c);
        add(cycle, c, a);
        assertTrue(JointBranchRepair.hasInvalidTurns(cycle));

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertTrue(
                JointBranchRepair.partners(input, cycle, cycle.copy(), "primary").isEmpty()));
    }

    @Test
    void aDegreeOneConsumerInASourceLessComponentIsStillDisconnected() {
        Connection f = new Connection();
        NetworkPlan disconnected = new NetworkPlan();
        PlanNode source = node(disconnected, PlanNode.Kind.EXISTING_CHAMBER, -100, 0);
        source.inputId = "source";
        add(disconnected, source, node(disconnected, PlanNode.Kind.TECHNICAL_NODE, -100, -100));
        PlanNode leaf = consumer(disconnected, f.target, f.target.utm.getCoordinate());
        add(disconnected, node(disconnected, PlanNode.Kind.TECHNICAL_NODE, 0, 200), leaf);
        assertEquals(1, disconnected.degree(leaf));
        assertTrue(disconnected.unconnected.isEmpty());

        assertFalse(JointBranchRepair.preservesConnections(f.input, f.current, disconnected),
                "a leaf and a clear unconnected list do not establish a path to the source");
    }

    @Test
    void movingPWithinTheSameMillimetreGridCellDoesNotPreserveTheConnection() {
        Connection f = new Connection();
        Coordinate shifted = new Coordinate(0.0001, 100);
        NetworkPlan moved = f.direct(shifted);
        assertNotNull(moved.findNode(f.target.utm.getCoordinate()), "the plan's millimetre lookup aliases the old P");

        assertFalse(JointBranchRepair.preservesConnections(f.input, f.current, moved),
                "joint replanning must preserve the actual input point, not just the lookup cell");
    }

    @Test
    void aConsumerCannotBecomeATransitNodeEvenWhenItRemainsReachable() {
        Connection f = new Connection();
        NetworkPlan transit = f.direct(f.target.utm.getCoordinate());
        PlanNode target = transit.findNode(f.target.utm.getCoordinate());
        add(transit, target, node(transit, PlanNode.Kind.TECHNICAL_NODE, 0, 200));
        assertEquals(2, transit.degree(target));

        assertFalse(JointBranchRepair.preservesConnections(f.input, f.current, transit));
    }

    @Test
    void anOrphanPaidChamberFailsPreservationEvenWithAllConsumersConnected() {
        Connection f = new Connection();
        NetworkPlan orphaned = f.direct(f.target.utm.getCoordinate());
        assertTrue(JointBranchRepair.preservesConnections(f.input, f.current, orphaned));
        node(orphaned, PlanNode.Kind.NEW_CHAMBER, 300, 300);

        assertFalse(JointBranchRepair.preservesConnections(f.input, f.current, orphaned));
    }

    private static final class Chain {
        final InputModel input = new InputModel();
        final NetworkPlan plan = new NetworkPlan();
        final InputModel.OksPoint partner = point("partner", -50, 100);
        final PlanNode middle;

        Chain() {
            input.oksPoints.add(point("primary", 100, -100));
            input.oksPoints.add(partner);
            PlanNode root = node(plan, PlanNode.Kind.EXISTING_CHAMBER, -100, 0);
            root.inputId = "source";
            middle = node(plan, PlanNode.Kind.TECHNICAL_NODE, -25, 50);
            plan.addEdge(root, middle, List.of(root.utm, new Coordinate(0, 0), middle.utm));
            PlanNode transit = node(plan, PlanNode.Kind.NEW_CHAMBER, -35, 70);
            add(plan, middle, transit);
            add(plan, transit, consumer(plan, partner, partner.utm.getCoordinate()));
        }
    }

    private static final class Connection {
        final InputModel input = new InputModel();
        final InputModel.OksPoint target = point("target", 0, 100);
        final VariantResult current;

        Connection() {
            input.oksPoints.add(target);
            InputModel.ExistingChamber source = new InputModel.ExistingChamber();
            source.id = "source";
            source.utm = GeoUtil.GF.createPoint(new Coordinate(-100, 0));
            input.chambers.add(source);
            NetworkPlan baseline = direct(target.utm.getCoordinate());
            current = new NetworkAssembler(new ObstacleIndex(input)).assemble(baseline, "v1", "shared");
            assertFalse(current.clearanceViolated);
            assertTrue(current.unconnectedOksIds.isEmpty());
            assertTrue(JointBranchRepair.preservesConnections(input, current, baseline));
        }

        NetworkPlan direct(Coordinate p) {
            NetworkPlan plan = new NetworkPlan();
            PlanNode source = node(plan, PlanNode.Kind.EXISTING_CHAMBER, -100, 0);
            source.inputId = "source";
            add(plan, source, consumer(plan, target, p));
            plan.tieInsToExisting.put(source, 1);
            return plan;
        }
    }

    private static InputModel.OksPoint point(String id, double x, double y) {
        InputModel.OksPoint point = new InputModel.OksPoint();
        point.id = id;
        point.flowTph = 1;
        point.utm = GeoUtil.GF.createPoint(new Coordinate(x, y));
        return point;
    }

    private static PlanNode node(NetworkPlan plan, PlanNode.Kind kind, double x, double y) {
        return plan.addNode(new PlanNode(kind, new Coordinate(x, y)));
    }

    private static PlanNode consumer(NetworkPlan plan, InputModel.OksPoint point, Coordinate coordinate) {
        PlanNode node = plan.addNode(new PlanNode(PlanNode.Kind.OKS, coordinate));
        node.inputId = point.id;
        node.oksFlow = point.flowTph;
        return node;
    }

    private static void add(NetworkPlan plan, PlanNode a, PlanNode b) {
        plan.addEdge(a, b, List.of(a.utm, b.utm));
    }
}
