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
import ru.lct.heatnet.routing.GeoUtil;
import ru.lct.heatnet.routing.ObstacleIndex;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VariantPlannerDirectNormalTieTest {
    @Test
    void connectsTheShortestNormalDirectlyToTheNearbyTrunkInsteadOfDiscardingItsApproach() throws Exception {
        Fixture f = new Fixture();
        PlanNode root = f.node(PlanNode.Kind.EXISTING_CHAMBER, -7.5, -50, "root", null);
        PlanNode other = f.node(PlanNode.Kind.OKS, -7.5, 150, "other", 20.0);
        f.plan.addEdge(root, other, List.of(root.utm, other.utm));
        f.plan.tieInsToExisting.put(root, 1);

        assertTrue(new VariantPlanner(f.input, f.obstacles).reconnect(f.plan, f.target));

        PlanNode target = f.plan.findNode(f.target.utm.getCoordinate());
        PlanEdge branch = f.plan.incident(target).get(0);
        assertEquals(5.0, GeoUtil.polyline(branch.coords).intersection(f.input.restrictions.get(0).utm).getLength(), 1e-6);
        assertEquals(12.5, branch.length(), 1e-6);
        PlanNode junction = branch.other(target);
        assertEquals(PlanNode.Kind.NEW_CHAMBER, junction.kind);
        assertEquals(3, f.plan.degree(junction));
        assertEquals(0, junction.utm.distance(new Coordinate(-7.5, 5.8)), 1e-6);
        assertEquals(0, target.utm.distance(f.target.utm.getCoordinate()), 1e-9);
        VariantResult assembled = new NetworkAssembler(f.obstacles).assemble(f.plan, "v1", "shared");
        assertFalse(assembled.clearanceViolated);
        assertTrue(assembled.unconnectedOksIds.isEmpty());
    }

    @Test
    void reusesAnExistingJunctionInsteadOfCreatingAnInteriorTieAtTheSameEndpoint() throws Exception {
        Fixture f = new Fixture();
        PlanNode root = f.node(PlanNode.Kind.EXISTING_CHAMBER, -7.5, -50, "root", null);
        PlanNode junction = f.node(PlanNode.Kind.NEW_CHAMBER, -7.5, 5.8, null, null);
        PlanNode other = f.node(PlanNode.Kind.OKS, -7.5, 150, "other", 20.0);
        f.plan.addEdge(root, junction, List.of(root.utm, junction.utm));
        f.plan.addEdge(junction, other, List.of(junction.utm, other.utm));
        f.plan.tieInsToExisting.put(root, 1);

        assertTrue(new VariantPlanner(f.input, f.obstacles).reconnect(f.plan, f.target));

        PlanNode target = f.plan.findNode(f.target.utm.getCoordinate());
        assertSame(junction, f.plan.incident(target).get(0).other(target));
        assertEquals(3, f.plan.degree(junction));
        assertEquals(1, f.plan.nodes.stream().filter(n -> n.kind == PlanNode.Kind.NEW_CHAMBER).count());
        assertFalse(new NetworkAssembler(f.obstacles).assemble(f.plan, "v1", "shared").clearanceViolated);
    }

    @Test
    void doesNotUseAFilledJunctionOrDisguiseItAsAnInteriorTie() throws Exception {
        Fixture f = new Fixture();
        PlanNode junction = f.node(PlanNode.Kind.NEW_CHAMBER, -7.5, 5.8, null, null);
        PlanNode root = f.node(PlanNode.Kind.EXISTING_CHAMBER, -7.5, -50, "root", null);
        PlanNode north = f.node(PlanNode.Kind.OKS, -7.5, 150, "other", 20.0);
        PlanNode west = f.node(PlanNode.Kind.OKS, -50, 5.8, "west", 1.0);
        PlanNode northwest = f.node(PlanNode.Kind.OKS, -50, 40, "northwest", 1.0);
        for (PlanNode n : List.of(root, north, west, northwest)) f.plan.addEdge(junction, n, List.of(junction.utm, n.utm));
        f.plan.tieInsToExisting.put(root, 1);

        assertTrue(new VariantPlanner(f.input, f.obstacles).reconnect(f.plan, f.target));

        assertEquals(4, f.plan.degree(junction));
        assertEquals(5.8, inside(f), 1e-6, "the short normal ends at an already full chamber");
        assertFalse(new NetworkAssembler(f.obstacles).assemble(f.plan, "v1", "shared").clearanceViolated);
    }

    @Test
    void countsTheTwoExistingPipeConnectionsAtASplitRoot() throws Exception {
        Fixture f = new Fixture();
        PlanNode root = f.node(PlanNode.Kind.EXISTING_CHAMBER, -7.5, -50, "root", null);
        PlanNode junction = f.node(PlanNode.Kind.NEW_CHAMBER, -7.5, 5.8, null, null);
        junction.splitPipeDu = 400;
        InputModel.ExistingPipe sourcePipe = new InputModel.ExistingPipe();
        sourcePipe.id = "through-source";
        sourcePipe.diameter = 400;
        sourcePipe.utm = GeoUtil.polyline(List.of(new Coordinate(-50, 5.8), junction.utm,
                new Coordinate(-50, 40)));
        f.input.pipes.add(sourcePipe);
        junction.exemptSpecialIds = java.util.Set.of(sourcePipe.id);
        PlanNode north = f.node(PlanNode.Kind.OKS, -7.5, 150, "other", 20.0);
        f.plan.addEdge(root, junction, List.of(root.utm, junction.utm));
        f.plan.addEdge(junction, north, List.of(junction.utm, north.utm));

        assertTrue(new VariantPlanner(f.input, new ObstacleIndex(f.input)).reconnect(f.plan, f.target));

        assertEquals(5.8, inside(f), 1e-6,
                "two planned + two existing connections leave no room for a direct endpoint tie");
    }

    @Test
    void doesNotBendANormalToANodeThatOnlyMatchesTheMillimetreGrid() throws Exception {
        Fixture f = new Fixture();
        PlanNode root = f.node(PlanNode.Kind.EXISTING_CHAMBER, -7.5, -50, "root", null);
        PlanNode junction = f.node(PlanNode.Kind.NEW_CHAMBER, -7.5, 5.8004, null, null);
        PlanNode north = f.node(PlanNode.Kind.OKS, -7.5, 150, "other", 20.0);
        f.plan.addEdge(root, junction, List.of(root.utm, junction.utm));
        f.plan.addEdge(junction, north, List.of(junction.utm, north.utm));

        assertTrue(new VariantPlanner(f.input, f.obstacles).reconnect(f.plan, f.target));

        assertEquals(5.8, inside(f), 1e-6);
        assertEquals(5.8004, junction.utm.y, 1e-9);
    }

    @Test
    void anotherBranchCrossingTheNormalCannotBeIgnoredByTheDeclaredTrunkTie() throws Exception {
        Fixture f = new Fixture();
        PlanNode root = f.node(PlanNode.Kind.EXISTING_CHAMBER, -7.5, -50, "root", null);
        PlanNode junction = f.node(PlanNode.Kind.NEW_CHAMBER, -7.5, 0, null, null);
        PlanNode north = f.node(PlanNode.Kind.OKS, -7.5, 150, "other", 20.0);
        PlanNode otherInside = f.node(PlanNode.Kind.OKS, 1, 8, "other-inside", 1.0);
        otherInside.ownRestrictionId = "own";
        f.plan.addEdge(root, junction, List.of(root.utm, junction.utm));
        f.plan.addEdge(junction, north, List.of(junction.utm, north.utm));
        f.plan.addEdge(junction, otherInside, List.of(junction.utm, otherInside.utm));
        f.plan.tieInsToExisting.put(root, 1);

        assertTrue(new VariantPlanner(f.input, f.obstacles).reconnect(f.plan, f.target));

        assertEquals(5.8, inside(f), 1e-6, "the complete normal crosses another consumer's terminal branch");
        assertFalse(new NetworkAssembler(f.obstacles).assemble(f.plan, "v1", "shared").clearanceViolated);
    }

    private static double inside(Fixture f) {
        PlanEdge branch = f.plan.incident(f.plan.findNode(f.target.utm.getCoordinate())).get(0);
        return GeoUtil.polyline(branch.coords).intersection(f.input.restrictions.get(0).utm).getLength();
    }

    private static class Fixture {
        final InputModel input = new InputModel();
        final NetworkPlan plan = new NetworkPlan();
        final InputModel.OksPoint target = new InputModel.OksPoint();
        final ObstacleIndex obstacles;
        Fixture() throws Exception {
            InputModel.Restriction own = new InputModel.Restriction();
            own.id = "own";
            own.restrictionType = "oks";
            own.utm = new WKTReader(GeoUtil.GF).read("POLYGON ((0 0,100 0,100 100,0 100,0 0))");
            input.restrictions.add(own);
            target.id = "target";
            target.flowTph = 20;
            target.utm = GeoUtil.GF.createPoint(new Coordinate(5, 5.8));
            input.oksPoints.add(target);
            InputModel.OksPoint other = new InputModel.OksPoint();
            other.id = "other";
            other.flowTph = 20;
            other.utm = GeoUtil.GF.createPoint(new Coordinate(-7.5, 150));
            input.oksPoints.add(other);
            InputModel.ExistingChamber root = new InputModel.ExistingChamber();
            root.id = "root";
            root.utm = GeoUtil.GF.createPoint(new Coordinate(-7.5, -50));
            input.chambers.add(root);
            obstacles = new ObstacleIndex(input);
        }
        PlanNode node(PlanNode.Kind kind, double x, double y, Object id, Double flow) {
            PlanNode node = plan.addNode(new PlanNode(kind, new Coordinate(x, y)));
            node.inputId = id;
            node.oksFlow = flow;
            return node;
        }
    }
}
