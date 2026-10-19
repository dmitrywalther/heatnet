package ru.lct.heatnet.network;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.refdata.PipeTable;
import ru.lct.heatnet.routing.GeoUtil;
import ru.lct.heatnet.routing.ObstacleIndex;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NetworkDepthProfilesTest {
    @Test
    void startsRampBeforeNewChamberAndKeepsEveryIncidentEdgeAtOneDepth() {
        NetworkPlan plan = plan(20);
        PlanNode chamber = plan.nodes.get(1);
        PlanNode branch = node(plan, PlanNode.Kind.OKS, 20, 80, "branch");
        branch.oksFlow = 1.0;
        plan.addEdge(branch, chamber, List.of(branch.utm, chamber.utm)); // opposite orientation
        VariantResult result = assemble(plan, "power_cable", 25);
        assertTrue(result.isValid(), "a ramp may start on the incoming edge before the new chamber");
        assertTrue(result.unconnectedOksIds.isEmpty());
        checkDepths(result);
        assertTrue(result.segments.stream().filter(s -> chamber.outId.equals(s.startNodeId)
                || chamber.outId.equals(s.endNodeId)).allMatch(s ->
                (chamber.outId.equals(s.startNodeId) ? s.depthStart : s.depthEnd) < 3));
    }

    @Test
    void usesPermittedPassBelowGasWhenAboveCannotBeReachedFromExistingChamber() {
        VariantResult result = assemble(plan(0), "gas_pipeline", 6.53);
        assertTrue(result.isValid(), "3→3.4 m takes 4 m and fits; the preferred passage above does not");
        checkDepths(result);
        assertTrue(result.segments.stream().filter(s -> s.special)
                .allMatch(s -> s.depthStart >= 3.4 && s.depthEnd >= 3.4));
        assertTrue(result.segments.stream().filter(s -> "source".equals(s.startNodeId))
                .allMatch(s -> s.depthStart == 3));
        result.segments.stream().filter(s -> s.special).forEach(s ->
                assertEquals(Math.round(s.lengthM * PipeTable.byDu(s.diameter).getCostPerMeter()
                        * s.kSpec * 1.04), s.cost, 1e-9, "3.4 m depth must be priced with K=1.04"));
    }

    @Test
    void stillRejectsCrossingWhenNeitherSideIsReachableFromTheFixedTieDepth() {
        VariantResult result = assemble(plan(0), "power_cable", 5);
        assertFalse(result.isValid());
        assertFalse(result.diagnostics.isEmpty());
    }

    private static void checkDepths(VariantResult result) {
        Map<Object, Double> nodes = new HashMap<>();
        for (VariantResult.Segment s : result.segments) {
            assertTrue(Math.min(s.depthStart, s.depthEnd) >= 0.7);
            assertTrue(Math.abs(s.depthEnd - s.depthStart) <= 0.1 * s.lengthM + 0.0105 + 1e-9);
            for (int end = 0; end < 2; end++) {
                Object id = end == 0 ? s.startNodeId : s.endNodeId;
                double depth = end == 0 ? s.depthStart : s.depthEnd;
                Double old = nodes.putIfAbsent(id, depth);
                if (old != null) assertEquals(old, depth, 1e-9, "shared node " + id);
            }
        }
    }

    private static VariantResult assemble(NetworkPlan plan, String type, double x) {
        InputModel input = new InputModel();
        InputModel.Restriction crossing = new InputModel.Restriction();
        crossing.id = "crossing";
        crossing.restrictionType = type;
        crossing.utm = GeoUtil.GF.createLineString(new Coordinate[]{new Coordinate(x, -1000), new Coordinate(x, 1000)});
        input.restrictions.add(crossing);
        return new NetworkAssembler(new ObstacleIndex(input)).assemble(plan, "d3", "alternative", true);
    }

    private static NetworkPlan plan(double chamberX) {
        NetworkPlan plan = new NetworkPlan();
        PlanNode source = node(plan, PlanNode.Kind.EXISTING_CHAMBER, 0, 0, "source");
        PlanNode previous = source;
        if (chamberX > 0) {
            previous = node(plan, PlanNode.Kind.NEW_CHAMBER, chamberX, 0, "new");
            plan.addEdge(source, previous, List.of(source.utm, previous.utm));
        }
        PlanNode oks = node(plan, PlanNode.Kind.OKS, 120, 0, "consumer");
        oks.oksFlow = 1.0;
        plan.addEdge(previous, oks, List.of(previous.utm, oks.utm));
        return plan;
    }

    private static PlanNode node(NetworkPlan plan, PlanNode.Kind kind, double x, double y, String id) {
        PlanNode node = new PlanNode(kind, new Coordinate(x, y));
        node.inputId = id;
        return plan.addNode(node);
    }
}
