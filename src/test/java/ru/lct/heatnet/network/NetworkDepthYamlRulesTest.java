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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NetworkDepthYamlRulesTest {
    @Test
    void unknownPolygonGetsTheSameDepthAndPriceAsAnExplicitTramRule() throws Exception {
        String body = "    mode: special\n    min_distance: 1.5\n    min_angle: 45\n"
                + "    special_margin: 3\n    k_spec: 1.75\n"
                + "    vertical:\n      crossing: under\n      min_top_depth: 3.8\n";
        VariantResult known = assemble("tram_tracks", "tram_tracks", body, true);
        VariantResult unknown = assemble("custom_tram", "unknown_default", body, true);
        assertTrue(known.isValid());
        assertTrue(unknown.isValid());
        assertTrue(unknown.unconnectedOksIds.isEmpty());
        List<VariantResult.Segment> crossing = unknown.segments.stream()
                .filter(s -> s.special).collect(java.util.stream.Collectors.toList());
        assertFalse(crossing.isEmpty(), "the test must actually cross the polygon");
        for (VariantResult.Segment s : crossing) {
            assertEquals(3.8, s.depthStart, 1e-9, "YAML lower bound reaches the assembled profile");
            assertEquals(3.8, s.depthEnd, 1e-9);
            assertEquals(Math.round(s.lengthM * PipeTable.byDu(s.diameter).getCostPerMeter()
                    * 1.75 * 1.08), s.cost, 1e-9, "depth surcharge must not disappear for an unknown type");
        }
        assertEquals(known.constructionCost, unknown.constructionCost, 1e-9);
        assertEquals(known.newNetworkLength, unknown.newNetworkLength, 1e-9);
        assertEquals(100, unknown.newNetworkLength, 1e-9);
        assertTrue(unknown.segments.stream().filter(s -> "source".equals(s.startNodeId))
                .allMatch(s -> s.depthStart == 3));
    }

    @Test
    void unknownCableMustRespectVerticalClearanceInsteadOfStayingAtThreeMetres() throws Exception {
        String body = "    mode: special\n    min_distance: 2\n    own_half_width: 0.1\n"
                + "    special_margin: 2\n    k_spec: 1.15\n"
                + "    vertical:\n      crossing: above_or_below\n      clearance: 0.5\n"
                + "      obstacle_top_depth: 2.7\n      obstacle_height: 0.2\n";
        VariantResult result = assemble("custom_cable", "unknown_default", body, false);
        assertTrue(result.isValid());
        assertTrue(result.unconnectedOksIds.isEmpty());
        assertTrue(result.segments.stream().anyMatch(s -> s.special));
        for (VariantResult.Segment s : result.segments) {
            if (!s.special) continue;
            double above = 2.7 - 0.5 - PipeTable.byDu(s.diameter).getHeightM();
            assertTrue(Math.max(s.depthStart, s.depthEnd) <= above + 0.005
                    || Math.min(s.depthStart, s.depthEnd) >= 3.4 - 0.005,
                    "cable must have the configured clearance over the complete crossing");
        }
    }

    private VariantResult assemble(String type, String key, String body, boolean polygon) throws Exception {
        RestrictionRules rules = RestrictionRules.builtIn().mergeYaml(new ByteArrayInputStream(
                ("restrictions:\n  " + key + ":\n" + body).getBytes(StandardCharsets.UTF_8)));
        InputModel input = new InputModel();
        InputModel.Restriction restriction = new InputModel.Restriction();
        restriction.id = "crossing";
        restriction.restrictionType = type;
        restriction.utm = polygon ? GeoUtil.GF.createPolygon(new Coordinate[]{
                new Coordinate(45, -100), new Coordinate(55, -100), new Coordinate(55, 100),
                new Coordinate(45, 100), new Coordinate(45, -100)})
                : GeoUtil.GF.createLineString(new Coordinate[]{new Coordinate(50, -100), new Coordinate(50, 100)});
        input.restrictions.add(restriction);
        NetworkPlan plan = new NetworkPlan();
        PlanNode source = new PlanNode(PlanNode.Kind.EXISTING_CHAMBER, new Coordinate(0, 0));
        source.inputId = "source";
        PlanNode oks = new PlanNode(PlanNode.Kind.OKS, new Coordinate(100, 0));
        oks.inputId = "consumer";
        oks.oksFlow = 1.0;
        plan.addNode(source);
        plan.addNode(oks);
        plan.addEdge(source, oks, List.of(source.utm, oks.utm));
        return new NetworkAssembler(new ObstacleIndex(input, rules)).assemble(plan, "d1", "shared", true);
    }
}
