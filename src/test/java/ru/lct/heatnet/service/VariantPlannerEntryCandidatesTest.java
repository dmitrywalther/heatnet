package ru.lct.heatnet.service;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.network.NetworkPlan;
import ru.lct.heatnet.network.PlanEdge;
import ru.lct.heatnet.network.PlanNode;
import ru.lct.heatnet.refdata.PipeTable;
import ru.lct.heatnet.routing.EdgeValidator;
import ru.lct.heatnet.routing.GeoUtil;
import ru.lct.heatnet.routing.GradeContext;
import ru.lct.heatnet.routing.ObstacleIndex;
import ru.lct.heatnet.routing.OksApproach;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VariantPlannerEntryCandidatesTest {

    @Test
    void capsAdditionalWallsWithoutMutatingTheDisconnectedPlanOrSiblingCandidates() throws Exception {
        InputModel input = scene(50, 50, 200, 50);
        NetworkPlan disconnected = new NetworkPlan();
        PlanNode root = disconnected.addNode(new PlanNode(PlanNode.Kind.EXISTING_CHAMBER,
                new Coordinate(200, 50)));
        root.inputId = "root";
        PlanNode junction = disconnected.addNode(new PlanNode(PlanNode.Kind.NEW_CHAMBER,
                new Coordinate(150, 50)));
        PlanEdge trunk = disconnected.addEdge(root, junction, List.of(root.utm, junction.utm));
        disconnected.tieInsToExisting.put(root, 1);
        VariantPlanner planner = new VariantPlanner(input, new ObstacleIndex(input),
                new EntrySelectionPolicy(1.0, 1));

        List<VariantPlanner.ReconnectCandidate> candidates = planner.reconnectCandidates(
                disconnected, input.oksPoints.get(0));

        assertEquals(2, candidates.size(), "one ordinary and at most one extra wall are explored");
        assertFalse(candidates.get(0).alternativeEntry);
        assertTrue(candidates.get(1).alternativeEntry);
        assertEquals(List.of(root, junction), disconnected.nodes);
        assertEquals(List.of(trunk), disconnected.edges);
        assertEquals(1, disconnected.tieInsToExisting.get(root));
        assertSame(root, trunk.a);
        assertSame(junction, trunk.b);
        assertEquals(50.0, trunk.length(), 1e-9);
        assertEquals(0, trunk.flowTph, 1e-9);
        assertNull(trunk.grade);
        assertNull(disconnected.findNode(input.oksPoints.get(0).utm.getCoordinate()));
        for (VariantPlanner.ReconnectCandidate candidate : candidates) {
            assertNotSame(disconnected, candidate.plan);
            assertNotSame(root, candidate.plan.findNode(root.utm));
            assertNotSame(trunk, candidate.plan.edges.get(0));
        }
        PlanEdge secondTrunk = candidates.get(1).plan.edges.get(0);
        candidates.get(0).plan.edges.clear();
        assertTrue(candidates.get(1).plan.edges.contains(secondTrunk));
        assertEquals(1, disconnected.edges.size());
    }

    @Test
    void measuresTheWindowFromTheShortestNormalRatherThanThePreviouslyChosenWall() throws Exception {
        InputModel input = scene(5, 5.8, 5, -30);
        // A third exterior side lies 6.2 m from P: it is only 0.4 m longer
        // than the 5.8 m wall, but outside the absolute 5.0 + 1.0 m window.
        double diagonal = 5 + 5.8 - 6.2 * Math.sqrt(2);
        input.restrictions.get(0).utm = new WKTReader(GeoUtil.GF).read(
                "POLYGON ((0 " + diagonal + ",0 100,100 100,100 0," + diagonal + " 0,0 " + diagonal + "))");
        // Keep P's nearest wall at x=0. The tiny diagonal corner has a valid
        // normal at distance 6.2 m, so it must never be admitted through drift.
        ObstacleIndex obstacles = new ObstacleIndex(input);
        GradeContext ctx = obstacles.forGrade(PipeTable.minGradeForFlow(20));
        OksApproach approach = OksApproach.build(input.oksPoints.get(0), ctx, new EdgeValidator(ctx));
        assertNotNull(approach);
        assertTrue(approach.approachNodes.stream().anyMatch(c ->
                Math.abs(approach.internalLength(c) - 6.2) < 1e-6),
                "the excluded diagonal normal must actually exist, so the window check is not vacuous");
        VariantPlanner planner = new VariantPlanner(input, obstacles,
                new EntrySelectionPolicy(1.0, 3));
        List<VariantPlanner.ReconnectCandidate> candidates = planner.reconnectCandidates(
                new NetworkPlan(), input.oksPoints.get(0));

        assertTrue(candidates.stream().anyMatch(c -> c.alternativeEntry));
        for (VariantPlanner.ReconnectCandidate candidate : candidates) {
            double inside = candidate.plan.edges.stream().mapToDouble(e ->
                    GeoUtil.polyline(e.coords).intersection(input.restrictions.get(0).utm).getLength()).sum();
            assertTrue(inside <= 6.0 + 1e-6, "absolute shortest-normal window, actual inside=" + inside);
        }
    }

    private static InputModel scene(double x, double y, double rootX, double rootY) throws Exception {
        InputModel input = new InputModel();
        InputModel.Restriction own = new InputModel.Restriction();
        own.id = "own";
        own.restrictionType = "oks";
        own.utm = new WKTReader(GeoUtil.GF).read("POLYGON ((0 0,100 0,100 100,0 100,0 0))");
        input.restrictions.add(own);
        InputModel.OksPoint oks = new InputModel.OksPoint();
        oks.id = "target";
        oks.flowTph = 20;
        oks.utm = GeoUtil.GF.createPoint(new Coordinate(x, y));
        input.oksPoints.add(oks);
        InputModel.ExistingChamber root = new InputModel.ExistingChamber();
        root.id = "root";
        root.utm = GeoUtil.GF.createPoint(new Coordinate(rootX, rootY));
        input.chambers.add(root);
        return input;
    }
}
