package ru.lct.heatnet.network;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.routing.GeoUtil;
import ru.lct.heatnet.routing.ObstacleIndex;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NetworkTurnValidationTest {
    @Test
    void assemblyRejectsUnsupportedTurnCreatedByRemovingAJunctionBranch() {
        NetworkPlan plan = junctionPlan(-50, 100);
        assertTrue(NetworkGeometryValidator.isValid(plan));
        NetworkPlan trial = plan.copy();
        assertTrue(trial.removeBranch("removed"));
        assertEquals(1, trial.edges.size());
        PlanEdge joined = trial.edges.get(0);
        assertEquals(3, joined.coords.size());
        assertTrue(GeoUtil.turnAngleDeg(joined.coords.get(0), joined.coords.get(1),
                joined.coords.get(2)) > 116);
        assertTrue(assemble(trial).clearanceViolated,
                "a 1-opt trial must not be accepted merely because each original branch was straight");
        assertEquals(3, plan.edges.size(), "checking the trial must leave the previous network intact");
    }

    @Test
    void orthogonalAndAcuteMergedRoutesRemainAllowed() {
        for (double x : new double[]{0, 100}) {
            NetworkPlan plan = junctionPlan(x, 100);
            assertTrue(plan.removeBranch("removed"));
            assertFalse(assemble(plan).clearanceViolated,
                    "a legal turn must not lose the useful removal of a redundant chamber");
        }
    }

    @Test
    void invalidInteriorTurnIsRejectedInEitherCoordinateDirection() {
        for (boolean reverse : new boolean[]{false, true}) {
            NetworkPlan plan = new NetworkPlan();
            PlanNode a = add(plan, PlanNode.Kind.EXISTING_CHAMBER, "root", -100, 0);
            PlanNode b = add(plan, PlanNode.Kind.OKS, "target", -50, 100);
            List<Coordinate> coords = reverse ? List.of(b.utm, new Coordinate(0, 0), a.utm)
                    : List.of(a.utm, new Coordinate(0, 0), b.utm);
            plan.addEdge(reverse ? b : a, reverse ? a : b, coords);
            assertTrue(assemble(plan).clearanceViolated);
        }
    }

    @Test
    void aDegreeTwoNodeCannotHideAnUnsupportedContinuousTurn() {
        for (PlanNode.Kind kind : new PlanNode.Kind[]{PlanNode.Kind.NEW_CHAMBER, PlanNode.Kind.TECHNICAL_NODE}) {
            NetworkPlan plan = new NetworkPlan();
            PlanNode a = add(plan, PlanNode.Kind.EXISTING_CHAMBER, "root", -100, 0);
            PlanNode middle = add(plan, kind, "middle", 0, 0);
            PlanNode b = add(plan, PlanNode.Kind.OKS, "target", -50, 100);
            plan.addEdge(a, middle, List.of(a.utm, middle.utm));
            plan.addEdge(middle, b, List.of(middle.utm, b.utm));
            assertTrue(assemble(plan).clearanceViolated,
                    "changing the segmentation or keeping a redundant chamber cannot legalize the bend");
        }
    }

    @Test
    void twoNewBranchesAtASourcePipeSplitAreNotATransitBend() {
        NetworkPlan plan = new NetworkPlan();
        PlanNode source = add(plan, PlanNode.Kind.NEW_CHAMBER, "split", 0, 0);
        source.splitPipeDu = 400;
        PlanNode a = add(plan, PlanNode.Kind.OKS, "west", -100, 0);
        PlanNode b = add(plan, PlanNode.Kind.OKS, "northwest", -50, 100);
        plan.addEdge(source, a, List.of(source.utm, a.utm));
        plan.addEdge(source, b, List.of(source.utm, b.utm));
        assertFalse(assemble(plan).clearanceViolated,
                "two existing source directions plus two new branches form a four-way chamber");
    }

    private NetworkPlan junctionPlan(double endX, double endY) {
        NetworkPlan plan = new NetworkPlan();
        PlanNode root = add(plan, PlanNode.Kind.EXISTING_CHAMBER, "root", -100, 0);
        PlanNode junction = add(plan, PlanNode.Kind.NEW_CHAMBER, "junction", 0, 0);
        PlanNode kept = add(plan, PlanNode.Kind.OKS, "kept", endX, endY);
        PlanNode removed = add(plan, PlanNode.Kind.OKS, "removed", 100, -100);
        plan.addEdge(root, junction, List.of(root.utm, junction.utm));
        plan.addEdge(junction, kept, List.of(junction.utm, kept.utm));
        plan.addEdge(junction, removed, List.of(junction.utm, removed.utm));
        plan.tieInsToExisting.put(root, 1);
        return plan;
    }

    private PlanNode add(NetworkPlan plan, PlanNode.Kind kind, String id, double x, double y) {
        PlanNode n = new PlanNode(kind, new Coordinate(x, y));
        n.inputId = id;
        if (kind == PlanNode.Kind.OKS) n.oksFlow = 1.0;
        return plan.addNode(n);
    }

    private VariantResult assemble(NetworkPlan plan) {
        return new NetworkAssembler(new ObstacleIndex(new InputModel())).assemble(plan, "v1", "test");
    }
}
