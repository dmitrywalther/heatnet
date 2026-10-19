package ru.lct.heatnet.network;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.refdata.PipeTable;
import ru.lct.heatnet.refdata.RestrictionRules;
import ru.lct.heatnet.routing.GeoUtil;
import ru.lct.heatnet.routing.ObstacleIndex;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NetworkSourceDepthYamlTest {
    @Test
    void existingChamberStartsAtConfiguredDepthButNormalDepthAndTariffStayAtThree() throws Exception {
        NetworkPlan plan = plan(false, false, 100);
        VariantResult depth = assemble(plan, new InputModel(), rules(4.2), true);
        assertTrue(depth.isValid());
        assertEquals(4.2, at(depth, "source"), 1e-9);
        assertEquals(3, at(depth, "consumer"), 1e-9);
        checkProfile(depth);
        assertEquals(100, depth.newNetworkLength, 1e-9);
        assertEquals(Math.round(12 * 74023 * 1.06) + Math.round(88 * 74023),
                depth.segments.stream().mapToDouble(s -> s.cost).sum(), 1e-9);
        VariantResult base = assemble(plan, new InputModel(), rules(4.2), false);
        assertTrue(base.isValid());
        assertEquals(100 * 74023, base.segments.stream().mapToDouble(s -> s.cost).sum(), 1e-9);
    }

    @Test
    void newTieChamberUsesShallowSourceDepthEvenOnReversedEdgeWithoutDiscount() throws Exception {
        VariantResult depth = assemble(plan(true, true, 100), new InputModel(), rules(1.4), true);
        assertTrue(depth.isValid());
        Object source = depth.segments.get(depth.segments.size() - 1).endNodeId;
        assertEquals(1.4, at(depth, source), 1e-9);
        assertEquals(3, at(depth, "consumer"), 1e-9);
        assertEquals(100 * 74023, depth.segments.stream().mapToDouble(s -> s.cost).sum(), 1e-9);
        checkProfile(depth);
    }

    @Test
    void sourceRampContinuesAcrossANewBranchChamberAtOneSharedDepth() throws Exception {
        NetworkPlan plan = new NetworkPlan();
        PlanNode source = node(plan, PlanNode.Kind.EXISTING_CHAMBER, 0, 0, "source");
        PlanNode junction = node(plan, PlanNode.Kind.NEW_CHAMBER, 5, 0, "junction");
        PlanNode a = node(plan, PlanNode.Kind.OKS, 100, 0, "consumer-a");
        PlanNode b = node(plan, PlanNode.Kind.OKS, 5, 60, "consumer-b");
        a.oksFlow = b.oksFlow = 1.0;
        plan.addEdge(source, junction, List.of(source.utm, junction.utm));
        plan.addEdge(junction, a, List.of(junction.utm, a.utm));
        plan.addEdge(b, junction, List.of(b.utm, junction.utm));
        VariantResult depth = assemble(plan, new InputModel(), rules(4.2), true);
        assertTrue(depth.isValid());
        assertTrue(depth.unconnectedOksIds.isEmpty());
        assertEquals(4.2, at(depth, "source"), 1e-9);
        assertEquals(3.7, at(depth, junction.outId), 1e-9);
        checkProfile(depth);
    }

    @Test
    void theSameExistingNetworkDepthControlsTieAndNonTieCrossing() throws Exception {
        InputModel input = new InputModel();
        InputModel.ExistingPipe source = pipe("source-pipe", 0, 400);
        input.pipes.add(source);
        input.pipes.add(pipe("crossed-pipe", 50, 400));
        NetworkPlan plan = plan(false, false, 100);
        plan.nodes.get(0).exemptSpecialIds = Set.of(source.id);
        VariantResult depth = assemble(plan, input, rules(1.4), true);
        assertTrue(depth.isValid());
        assertEquals(1.4, at(depth, "source"), 1e-9);
        assertTrue(depth.segments.stream().anyMatch(s -> s.special), "actual non-tie crossing required");
        for (VariantResult.Segment s : depth.segments) if (s.special) {
            double above = 1.4 - 0.5 - PipeTable.byDu(s.diameter).getHeightM();
            double below = 1.4 + PipeTable.byDu(400).getHeightM() + 0.5;
            assertTrue(Math.max(s.depthStart, s.depthEnd) <= above + 0.005
                    || Math.min(s.depthStart, s.depthEnd) >= below - 0.005);
        }
        checkProfile(depth);
    }

    @Test
    void sourceBelowMinimumCoverRejectsDepthInsteadOfSilentlyConnectingAtThree() throws Exception {
        NetworkPlan plan = plan(false, false, 100);
        VariantResult depth = assemble(plan, new InputModel(), rules(0.5), true);
        assertFalse(depth.isValid());
        assertTrue(depth.diagnostics.stream().anyMatch(d -> "DEPTH_NETWORK_INFEASIBLE".equals(d.code)));
        assertTrue(assemble(plan, new InputModel(), rules(0.5), false).isValid());
    }

    @Test
    void aShortApproachCannotIgnoreConfiguredTieDepthToReachAnUnderpass() throws Exception {
        InputModel input = new InputModel();
        InputModel.Restriction crossing = new InputModel.Restriction();
        crossing.id = "close-tram";
        crossing.restrictionType = "tram_tracks";
        crossing.utm = GeoUtil.GF.createPolygon(new Coordinate[]{new Coordinate(5, -20),
                new Coordinate(9, -20), new Coordinate(9, 20), new Coordinate(5, 20), new Coordinate(5, -20)});
        input.restrictions.add(crossing);
        RestrictionRules rules = rules(1.4);
        NetworkPlan plan = plan(false, false, 100);
        // A 3 m underpass starts at x=2 (including its margin). From 1.4 m,
        // 16 m of ramp are required; from the former hardcoded 3 m it falsely passed.
        rules = rules.mergeYaml(stream("restrictions:\n  tram_tracks:\n    vertical:\n      min_top_depth: 3.0\n"));
        VariantResult depth = assemble(plan, input, rules, true);
        assertFalse(depth.isValid());
        assertTrue(depth.diagnostics.stream().anyMatch(d -> "DEPTH_NETWORK_INFEASIBLE".equals(d.code)));
        assertTrue(assemble(plan, input, rules, false).isValid());
    }

    @Test
    void explicitDefaultDepthIsEquivalentToBuiltInRules() throws Exception {
        VariantResult builtin = assemble(plan(false, false, 100), new InputModel(), RestrictionRules.builtIn(), true);
        VariantResult explicit = assemble(plan(false, false, 100), new InputModel(), rules(3), true);
        assertTrue(explicit.isValid());
        assertEquals(builtin.constructionCost, explicit.constructionCost);
        assertEquals(builtin.segments.size(), explicit.segments.size());
        assertEquals(3, at(explicit, "source"), 1e-9);
        checkProfile(explicit);
    }

    private static void checkProfile(VariantResult result) {
        Map<Object, Double> nodes = new HashMap<>();
        for (VariantResult.Segment s : result.segments) {
            assertTrue(Math.min(s.depthStart, s.depthEnd) >= 0.7);
            assertTrue(Math.abs(s.depthEnd - s.depthStart) <= 0.1 * s.lengthM + 0.0105);
            for (int i = 0; i < 2; i++) {
                Object id = i == 0 ? s.startNodeId : s.endNodeId;
                double h = i == 0 ? s.depthStart : s.depthEnd;
                Double old = nodes.putIfAbsent(id, h);
                if (old != null) assertEquals(old, h, 1e-9, "one depth at every shared node");
            }
        }
    }

    private static double at(VariantResult result, Object id) {
        for (VariantResult.Segment s : result.segments) {
            if (id.equals(s.startNodeId)) return s.depthStart;
            if (id.equals(s.endNodeId)) return s.depthEnd;
        }
        throw new AssertionError("missing node " + id);
    }

    private static RestrictionRules rules(double depth) throws Exception {
        return RestrictionRules.builtIn().mergeYaml(stream(
                "heat_network_crossing:\n  vertical:\n    obstacle_top_depth: " + depth + "\n"));
    }

    private static ByteArrayInputStream stream(String yaml) {
        return new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    }

    private static VariantResult assemble(NetworkPlan plan, InputModel input, RestrictionRules rules, boolean depth) {
        return new NetworkAssembler(new ObstacleIndex(input, rules)).assemble(plan, depth ? "d1" : "v1", "shared", depth);
    }

    private static NetworkPlan plan(boolean split, boolean reverse, double length) {
        NetworkPlan plan = new NetworkPlan();
        PlanNode source = node(plan, split ? PlanNode.Kind.NEW_CHAMBER : PlanNode.Kind.EXISTING_CHAMBER, 0, 0, "source");
        if (split) source.splitPipeDu = 400;
        PlanNode oks = node(plan, PlanNode.Kind.OKS, length, 0, "consumer");
        oks.oksFlow = 1.0;
        if (reverse) plan.addEdge(oks, source, List.of(oks.utm, source.utm));
        else plan.addEdge(source, oks, List.of(source.utm, oks.utm));
        return plan;
    }

    private static PlanNode node(NetworkPlan plan, PlanNode.Kind kind, double x, double y, String id) {
        PlanNode node = new PlanNode(kind, new Coordinate(x, y));
        node.inputId = id;
        return plan.addNode(node);
    }

    private static InputModel.ExistingPipe pipe(String id, double x, int du) {
        InputModel.ExistingPipe pipe = new InputModel.ExistingPipe();
        pipe.id = id;
        pipe.diameter = du;
        pipe.utm = GeoUtil.GF.createLineString(new Coordinate[]{new Coordinate(x, -100), new Coordinate(x, 100)});
        return pipe;
    }
}
