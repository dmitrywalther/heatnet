package ru.lct.heatnet.service;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.network.NetworkAssembler;
import ru.lct.heatnet.network.NetworkPlan;
import ru.lct.heatnet.network.PlanEdge;
import ru.lct.heatnet.network.PlanNode;
import ru.lct.heatnet.network.VariantResult;
import ru.lct.heatnet.refdata.PipeTable;
import ru.lct.heatnet.routing.EdgeValidator;
import ru.lct.heatnet.routing.GeoUtil;
import ru.lct.heatnet.routing.GradeContext;
import ru.lct.heatnet.routing.ObstacleIndex;
import ru.lct.heatnet.routing.OksApproach;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TracingEngineFullNetworkCostTest {

    @Test
    void rejectsShorterBranchWhoseFullReassemblyCostsMoreAfterChamberAndDiameterChanges() throws Exception {
        InputModel input = new InputModel();
        InputModel.Restriction own = new InputModel.Restriction();
        own.id = "own";
        own.restrictionType = "oks";
        own.utm = new WKTReader(GeoUtil.GF).read("POLYGON ((0 0,100 0,100 100,0 100,0 0))");
        input.restrictions.add(own);
        InputModel.OksPoint target = oks(input, "target", 5, 5.8, 33.14);
        InputModel.OksPoint major = oks(input, "major", -20, -120, 200);
        InputModel.OksPoint small = oks(input, "small", -50, 10, 20);
        InputModel.ExistingChamber source = new InputModel.ExistingChamber();
        source.id = "root";
        source.utm = GeoUtil.GF.createPoint(new Coordinate(80, -20));
        input.chambers.add(source);
        ObstacleIndex obstacles = new ObstacleIndex(input);
        GradeContext ctx = obstacles.forGrade(PipeTable.minGradeForFlow(253.14));
        OksApproach approach = OksApproach.build(target, ctx, new EdgeValidator(ctx));
        assertNotNull(approach);
        Coordinate west = approach.approachNodes.stream().filter(c -> c.x < 0).findFirst().orElseThrow();
        Coordinate south = approach.approachNodes.stream().filter(c -> c.y < 0).findFirst().orElseThrow();
        assertEquals(5, approach.internalLength(west), 1e-6);
        assertEquals(5.8, approach.internalLength(south), 1e-6);

        NetworkPlan baseline = new NetworkPlan();
        PlanNode root = node(baseline, PlanNode.Kind.EXISTING_CHAMBER, 80, -20);
        root.inputId = source.id;
        baseline.tieInsToExisting.put(root, 1);
        PlanNode a = node(baseline, PlanNode.Kind.NEW_CHAMBER, -20, -20);
        PlanNode oldJunction = node(baseline, PlanNode.Kind.NEW_CHAMBER, -28, 10);
        PlanNode targetNode = consumer(baseline, target);
        targetNode.ownRestrictionId = own.id;
        baseline.addEdge(root, a, List.of(root.utm, a.utm));
        connect(baseline, a, consumer(baseline, major));
        PlanEdge oldShared = connect(baseline, a, oldJunction);
        connect(baseline, oldJunction, consumer(baseline, small));
        PlanEdge oldBranch = baseline.addEdge(oldJunction, targetNode,
                List.of(oldJunction.utm, west, targetNode.utm));
        NetworkAssembler assembler = new NetworkAssembler(obstacles);
        VariantResult before = assembler.assemble(baseline, "before", "control");
        assertFalse(before.clearanceViolated);
        assertTrue(before.unconnectedOksIds.isEmpty());
        assertEquals(150, oldShared.grade.getDu());
        assertEquals(53.14, oldShared.flowTph, 1e-6);
        assertEquals(3_000_000, chamberAt(before, oldJunction.utm).cost);

        NetworkPlan alternative = baseline.copy();
        assertTrue(alternative.removeBranch(target.id));
        assertNull(alternative.findNode(oldJunction.utm), "the former branching chamber is merged away");
        PlanEdge trunk = alternative.edges.stream().filter(e ->
                e.a.kind == PlanNode.Kind.EXISTING_CHAMBER).findFirst().orElseThrow();
        PlanNode newJunction = alternative.splitEdge(trunk, new Coordinate(5, -20), PlanNode.Kind.NEW_CHAMBER);
        PlanNode retainedTarget = alternative.findNode(targetNode.utm);
        PlanEdge newBranch = alternative.addEdge(newJunction, retainedTarget,
                List.of(newJunction.utm, south, retainedTarget.utm));
        VariantResult after = assembler.assemble(alternative, "after", "extra normal");

        assertFalse(after.clearanceViolated);
        assertTrue(after.unconnectedOksIds.isEmpty());
        assertEquals(100, alternative.incident(alternative.findNode(small.utm.getCoordinate())).get(0).grade.getDu(),
                "the remaining shared branch is re-sized for the small consumer only");
        assertEquals(250, chamberAt(after, newJunction.utm).diameter,
                "a new junction is priced by the largest adjacent trunk, not by target's DU125");
        assertEquals(5_000_000, chamberAt(after, newJunction.utm).cost);
        assertEquals(before.chamberConstructionCost + 2_000_000, after.chamberConstructionCost);
        assertEquals(before.existingChamberTieInCost, after.existingChamberTieInCost);
        assertTrue(newBranch.length() < oldBranch.length());
        assertTrue(after.score < before.score, "shortening still improves official S in this scene");
        assertTrue(after.calculatedCost > before.calculatedCost,
                "full cost grows despite a shorter branch and smaller diameter elsewhere");
        TracingEngine engine = new TracingEngine();
        assertFalse(engine.acceptsReconnectCandidate(after, before, true));
        assertTrue(engine.acceptsReconnectCandidate(after, before, false));
    }

    private static VariantResult.Chamber chamberAt(VariantResult result, Coordinate c) {
        return result.chambers.stream().filter(n -> n.utm.distance(c) < 1e-6).findFirst().orElseThrow();
    }

    private static InputModel.OksPoint oks(InputModel input, String id, double x, double y, double flow) {
        InputModel.OksPoint p = new InputModel.OksPoint();
        p.id = id;
        p.utm = GeoUtil.GF.createPoint(new Coordinate(x, y));
        p.flowTph = flow;
        input.oksPoints.add(p);
        return p;
    }

    private static PlanNode node(NetworkPlan plan, PlanNode.Kind kind, double x, double y) {
        return plan.addNode(new PlanNode(kind, new Coordinate(x, y)));
    }

    private static PlanNode consumer(NetworkPlan plan, InputModel.OksPoint p) {
        PlanNode n = plan.addNode(new PlanNode(PlanNode.Kind.OKS, p.utm.getCoordinate()));
        n.inputId = p.id;
        n.oksFlow = p.flowTph;
        return n;
    }

    private static PlanEdge connect(NetworkPlan plan, PlanNode a, PlanNode b) {
        return plan.addEdge(a, b, List.of(a.utm, b.utm));
    }
}
