package ru.lct.heatnet.network;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Polygon;
import ru.lct.heatnet.geo.GeoJsonWriter;
import ru.lct.heatnet.geo.Projection;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.refdata.PipeTable;
import ru.lct.heatnet.refdata.RestrictionRules;
import ru.lct.heatnet.routing.GeoUtil;
import ru.lct.heatnet.routing.ObstacleIndex;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Technical appendix, section 4: every boundary of an overlap starts a new
 * segment, even when the maximum price coefficient is unchanged.
 */
class NetworkOverlappingSpecialTest {
    private static final double X = 400_000;
    private static final double Y = 6_170_000;
    private static final double EPS = 1e-6;

    @Test
    void equalRoadAndTramCoefficientsKeepAllOverlapBoundariesInBothDirections() throws Exception {
        for (boolean depth : List.of(false, true)) {
            for (boolean reverse : List.of(false, true)) {
                InputModel input = new InputModel();
                input.restrictions.add(polygon("road", "road", 40, 50));
                input.restrictions.add(polygon("tram", "tram_tracks", 50, 60));
                List<Window> windows = List.of(new Window(37, 53, 1.6), new Window(47, 63, 1.6));
                String yaml = "restrictions:\n"
                        + "  road: {k_spec: 1.6, vertical: {min_top_depth: 3.4}}\n"
                        + "  tram_tracks: {k_spec: 1.6, vertical: {min_top_depth: 3.8}}\n";
                VariantResult result = assemble(input, yaml, depth, reverse);
                evidence("equal-" + depth + "-" + reverse, result);
                assertCrossings(result, windows, depth);
                if (depth) {
                    assertDepthAtLeast(result, 37, 53, 3.4);
                    assertDepthAtLeast(result, 47, 63, 3.8);
                }
            }
        }
    }

    @Test
    void nestedCableBoundariesSurviveHigherLowerEqualAndUnitPriceCoefficients() throws Exception {
        for (double cableK : List.of(1.0, 1.6, 2.0)) {
            for (boolean depth : List.of(false, true)) {
                InputModel input = new InputModel();
                input.restrictions.add(polygon("road", "road", 40, 60));
                input.restrictions.add(line("cable", "power_cable", 50));
                String yaml = "restrictions:\n"
                        + "  road: {k_spec: 1.6, vertical: {min_top_depth: 3.4}}\n"
                        + "  power_cable:\n"
                        + "    k_spec: " + cableK + "\n"
                        + "    vertical: {crossing: above_or_below, obstacle_top_depth: 3.0,"
                        + " obstacle_height: 0.2, clearance: 0.5}\n";
                VariantResult result = assemble(input, yaml, depth, false);
                evidence("nested-" + cableK + "-" + depth, result);
                assertCrossings(result, List.of(new Window(37, 63, 1.6),
                        new Window(48, 52, cableK)), depth);
                if (depth) {
                    assertDepthAtLeast(result, 37, 63, 3.4);
                    // The road rules out going above this cable; both raw
                    // constraints must survive regardless of which K wins.
                    assertDepthAtLeast(result, 48, 52, 3.7);
                }
            }
        }
    }

    @Test
    void fullyCostNeutralOverlapStillHasSpecialSegmentsAndInternalTechnicalNodes() throws Exception {
        for (boolean depth : List.of(false, true)) {
            InputModel input = new InputModel();
            input.restrictions.add(polygon("road", "road", 40, 50));
            input.restrictions.add(polygon("tram", "tram_tracks", 50, 60));
            VariantResult result = assemble(input, "restrictions:\n"
                    + "  road: {k_spec: 1}\n"
                    + "  tram_tracks: {k_spec: 1}\n", depth, false);
            evidence("unit-" + depth, result);
            assertCrossings(result, List.of(new Window(37, 53, 1), new Window(47, 63, 1)), depth);
            assertEquals(100 * PipeTable.byDu(50).getCostPerMeter(),
                    result.constructionCost, EPS, "No surcharge when both coefficients are one");
        }
    }

    @Test
    void coincidentAndTouchingWindowsReuseOneTechnicalNodeWithoutZeroLengthFragments() throws Exception {
        for (boolean touching : List.of(false, true)) {
            InputModel input = new InputModel();
            input.restrictions.add(polygon("road", "road", 40, 50));
            input.restrictions.add(polygon("tram", "tram_tracks", touching ? 56 : 40, touching ? 66 : 50));
            VariantResult result = assemble(input, "restrictions:\n"
                    + "  road: {k_spec: 1.6}\n"
                    + "  tram_tracks: {k_spec: 1.6}\n", false, false);
            evidence("coincident-" + touching, result);
            assertCrossings(result, List.of(new Window(37, 53, 1.6),
                    new Window(touching ? 53 : 37, touching ? 69 : 53, 1.6)), false);
            assertEquals(touching ? 3 : 2, result.techNodes.size());
        }
    }

    @Test
    void separateCrossingsOfOneMultiPolygonKeepTheirOverlapBoundaries() throws Exception {
        InputModel input = new InputModel();
        InputModel.Restriction road = polygon("road", "road", 40, 45);
        road.utm = GeoUtil.GF.createMultiPolygon(new Polygon[]{
                (Polygon) road.utm, (Polygon) polygon("unused", "road", 47, 52).utm});
        input.restrictions.add(road);
        VariantResult result = assemble(input, "restrictions:\n  road: {k_spec: 1.6}\n", false, false);
        evidence("one-multipolygon", result);
        assertCrossings(result, List.of(new Window(37, 48, 1.6), new Window(44, 55, 1.6)), false);
    }

    @Test
    void pricesUseRoundedExportedLengthsAfterEveryRequiredBoundary() throws Exception {
        InputModel input = new InputModel();
        input.restrictions.add(polygon("road", "road", 40.004, 50.003));
        input.restrictions.add(polygon("tram", "tram_tracks", 50.006, 60.002));
        VariantResult result = assemble(input, "restrictions:\n"
                + "  road: {k_spec: 1.6}\n"
                + "  tram_tracks: {k_spec: 1.6}\n", false, false);
        evidence("rounded", result);
        List<Window> windows = List.of(new Window(37.004, 53.003, 1.6),
                new Window(47.006, 63.002, 1.6));
        assertCrossings(result, windows, false);
        double[] cuts = {0, 37.004, 47.006, 53.003, 63.002, 100};
        long expected = 0;
        for (int i = 0; i + 1 < cuts.length; i++) {
            double length = Math.round((cuts[i + 1] - cuts[i]) * 100) / 100.0;
            expected += Math.round(length * PipeTable.byDu(50).getCostPerMeter()
                    * (i > 0 && i < cuts.length - 2 ? 1.6 : 1.0));
        }
        assertEquals(expected, result.constructionCost, EPS);
    }

    @Test
    void twoPassesMeetingAtAPolylineBendRemainSeparateStraightSegments() throws Exception {
        InputModel input = new InputModel();
        InputModel.Restriction first = line("first", "power_cable", 48);
        first.utm = GeoUtil.GF.createLineString(new Coordinate[]{at(48, -10), at(48, 1)});
        InputModel.Restriction second = line("second", "power_cable", 0);
        second.utm = GeoUtil.GF.createLineString(new Coordinate[]{at(49, 2), at(60, 2)});
        input.restrictions.add(first);
        input.restrictions.add(second);
        RestrictionRules rules = RestrictionRules.builtIn().mergeYaml(new ByteArrayInputStream(
                ("restrictions:\n  power_cable: {min_distance: 0, own_half_width: 0, k_spec: 1.15}\n")
                        .getBytes(StandardCharsets.UTF_8)));
        NetworkPlan plan = new NetworkPlan();
        PlanNode source = new PlanNode(PlanNode.Kind.EXISTING_CHAMBER, at(0, 0));
        source.inputId = "source";
        PlanNode oks = new PlanNode(PlanNode.Kind.OKS, at(50, 50));
        oks.inputId = "consumer";
        oks.oksFlow = 1.0;
        plan.addNode(source);
        plan.addNode(oks);
        plan.addEdge(source, oks, List.of(source.utm, at(50, 0), oks.utm));
        VariantResult result = new NetworkAssembler(new ObstacleIndex(input, rules))
                .assemble(plan, "v1", "shared");
        evidence("two-straight-passes", result);
        assertTrue(result.isValid(), () -> "Unexpected diagnostics: " + result.diagnostics);
        assertTrue(result.unconnectedOksIds.isEmpty());
        assertEquals(100, result.newNetworkLength, EPS);
        assertTrue(result.chambers.isEmpty());
        assertEquals(1, result.techNodes.stream().filter(n -> n.utm.distance(at(50, 0)) < EPS).count(),
                "The common boundary must remain a technical node even when K is unchanged");
        assertEquals(2, result.segments.stream().filter(s -> s.special).count());
        assertEquals(8, result.segments.stream().filter(s -> s.special)
                .mapToDouble(s -> s.lengthM).sum(), EPS);
        for (VariantResult.Segment s : result.segments) {
            if (s.special) {
                assertEquals(s.lengthM, s.utmCoords.get(0)
                        .distance(s.utmCoords.get(s.utmCoords.size() - 1)), EPS,
                        "Each exported special pass is straight, not a merged bend");
            }
        }
    }

    @Test
    void distinctHalfMillimetreBoundariesDoNotCreateSelfLoopsOrZeroExportedLengths() throws Exception {
        for (double delta : List.of(0.0005, 0.00001)) {
            InputModel input = new InputModel();
            input.restrictions.add(polygon("road", "road", 40, 50));
            input.restrictions.add(polygon("tram", "tram_tracks", 40 + delta, 50 + delta));
            VariantResult result = assemble(input, "restrictions:\n"
                    + "  road: {k_spec: 1.6}\n"
                    + "  tram_tracks: {k_spec: 1.6}\n", false, false);
            evidence(delta == 0.0005 ? "half-millimetre" : "ten-microns", result);
            assertTrue(result.isValid());
            for (VariantResult.Segment s : result.segments) {
                assertNotEquals(s.startNodeId, s.endNodeId,
                        "Different physical endpoints must not become the same graph node");
                assertTrue(s.lengthM > 0, "Every physically nonzero exported fragment has positive length");
            }
            assertEquals(4, result.techNodes.size());
            assertEquals(100, result.newNetworkLength, EPS);
            assertExportTopologyAndPrices(result, null);
        }
    }

    @Test
    void microscopicFirstFragmentKeepsOriginalSourceCoordinateAndNewChamberTopology() throws Exception {
        for (boolean newChamber : List.of(false, true)) {
            InputModel input = new InputModel();
            input.restrictions.add(polygon("road", "road", 3.00001, 10));
            RestrictionRules rules = RestrictionRules.builtIn();
            NetworkPlan plan = new NetworkPlan();
            PlanNode source = new PlanNode(newChamber ? PlanNode.Kind.NEW_CHAMBER
                    : PlanNode.Kind.EXISTING_CHAMBER, at(0, 0));
            source.inputId = "source";
            Coordinate originalWgs = new Projection().utmToWgs(X, Y);
            // Input WGS must be preserved exactly, even if inverse projection
            // differs in its last digits. A newly calculated chamber has no override.
            if (newChamber) source.splitPipeDu = 50;
            else source.wgs = new Coordinate(originalWgs.x + 1e-12, originalWgs.y - 1e-12);
            PlanNode oks = new PlanNode(PlanNode.Kind.OKS, at(100, 0));
            oks.inputId = "consumer";
            oks.oksFlow = 1.0;
            plan.addNode(source);
            plan.addNode(oks);
            plan.addEdge(source, oks, List.of(source.utm, oks.utm));
            VariantResult result = new NetworkAssembler(new ObstacleIndex(input, rules))
                    .assemble(plan, "v1", "shared");
            evidence("microscopic-source-" + newChamber, result);
            assertTrue(result.isValid());
            assertExportTopologyAndPrices(result, newChamber ? null : source.wgs);
        }
    }

    private static void assertExportTopologyAndPrices(VariantResult result, Coordinate originalSource)
            throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new GeoJsonWriter(new Projection()).write(List.of(result), out);
        JsonNode document = new ObjectMapper().readTree(out.toByteArray());
        Map<String, JsonNode> coordinates = new HashMap<>();
        for (JsonNode feature : document.path("features")) {
            JsonNode p = feature.path("properties");
            String type = p.path("object_type").asText();
            JsonNode xy = feature.path("geometry").path("coordinates");
            if ("heat_network".equals(type)) {
                assertTrue(p.path("length").asDouble() > 0, "No nonzero pipe rounded to zero length");
                assertNotEquals(p.path("start_node_id"), p.path("end_node_id"), "No exported self loops");
                assertNotEquals(xy.get(0), xy.get(xy.size() - 1), "Rounding must not collapse a short pipe");
                rememberCoordinate(coordinates, p.path("start_node_id").asText(), xy.get(0));
                rememberCoordinate(coordinates, p.path("end_node_id").asText(), xy.get(xy.size() - 1));
                double k = "special".equals(p.path("laying_method").asText()) ? 1.6 : 1;
                long expected = Math.round(p.path("length").asDouble()
                        * PipeTable.byDu(p.path("diameter").asInt()).getCostPerMeter() * k);
                assertEquals(expected, p.path("cost").asLong(), "Price follows the exported positive length");
            } else if ("technical_node".equals(type) || "heat_chamber".equals(type)) {
                rememberCoordinate(coordinates, p.path("id").asText(), xy);
            }
        }
        if (originalSource != null) {
            assertEquals(originalSource.x, coordinates.get("source").get(0).asDouble());
            assertEquals(originalSource.y, coordinates.get("source").get(1).asDouble());
        }
    }

    private static void rememberCoordinate(Map<String, JsonNode> coordinates, String id, JsonNode xy) {
        JsonNode previous = coordinates.putIfAbsent(id, xy);
        if (previous != null) assertEquals(previous, xy,
                "Every endpoint and node feature with ID " + id + " must have exactly the same coordinates");
    }

    private static void assertCrossings(VariantResult result, List<Window> windows, boolean depth) {
        assertTrue(result.isValid(), () -> "Unexpected diagnostics: " + result.diagnostics);
        assertTrue(result.unconnectedOksIds.isEmpty());
        assertTrue(result.segments.stream().anyMatch(s -> "consumer".equals(s.endNodeId)
                || "consumer".equals(s.startNodeId)));
        assertEquals(100, result.newNetworkLength, EPS);
        assertTrue(result.chambers.isEmpty(), "Boundary nodes must not become paid chambers");
        for (Window window : windows) {
            for (double boundary : List.of(window.from, window.to)) {
                List<VariantResult.TechNode> nodes = new ArrayList<>();
                result.techNodes.stream().filter(n -> Math.abs(n.utm.x - X - boundary) < EPS).forEach(nodes::add);
                assertEquals(1, nodes.size(), "Each raw crossing boundary needs one technical node at " + boundary);
                assertEquals(2, result.segments.stream().filter(s -> nodes.get(0).id.equals(s.startNodeId)
                        || nodes.get(0).id.equals(s.endNodeId)).count(),
                        "Both adjacent fragments must refer to the same boundary node");
            }
        }
        double unionLength = 0;
        double price = 0;
        for (VariantResult.Segment segment : result.segments) {
            double from = Math.min(x(segment, 0), x(segment, segment.utmCoords.size() - 1));
            double to = Math.max(x(segment, 0), x(segment, segment.utmCoords.size() - 1));
            assertTrue(to > from + EPS, "No zero-length fragments");
            double mid = (from + to) / 2;
            double k = 1;
            boolean special = false;
            for (Window window : windows) {
                assertFalse(window.from > from + EPS && window.from < to - EPS,
                        "A raw entry boundary must split the output");
                assertFalse(window.to > from + EPS && window.to < to - EPS,
                        "A raw exit boundary must split the output");
                if (mid > window.from && mid < window.to) {
                    special = true;
                    k = Math.max(k, window.k);
                }
            }
            assertEquals(special, segment.special);
            assertEquals(k, segment.kSpec, EPS, "Overlap price is max K, never sum or product");
            assertEquals(50, segment.diameter);
            assertEquals(1, segment.flowTph, EPS);
            double kd = 1;
            if (depth) {
                assertNotNull(segment.depthStart);
                assertNotNull(segment.depthEnd);
                assertTrue(Math.abs(segment.depthEnd - segment.depthStart) <= 0.1 * (to - from) + 0.011);
                kd = (DepthProfile.kAt(segment.depthStart) + DepthProfile.kAt(segment.depthEnd)) / 2;
            } else {
                assertNull(segment.depthStart);
                assertNull(segment.depthEnd);
            }
            long expected = Math.round(segment.lengthM * PipeTable.byDu(50).getCostPerMeter() * k * kd);
            assertEquals(expected, segment.cost, 1.0,
                    "Price is computed once per output fragment; depth rounding permits one rouble");
            price += segment.cost;
            if (special) unionLength += to - from;
        }
        assertEquals(price, result.constructionCost, EPS);
        List<Double> cuts = new ArrayList<>();
        windows.forEach(w -> { cuts.add(w.from); cuts.add(w.to); });
        cuts.sort(Double::compare);
        double expectedUnion = 0;
        for (int i = 0; i + 1 < cuts.size(); i++) {
            double mid = (cuts.get(i) + cuts.get(i + 1)) / 2;
            if (windows.stream().anyMatch(w -> mid > w.from && mid < w.to)) {
                expectedUnion += cuts.get(i + 1) - cuts.get(i);
            }
        }
        assertEquals(expectedUnion, unionLength, EPS, "Overlapping physical length is counted once");
    }

    private static void assertDepthAtLeast(VariantResult result, double from, double to, double depth) {
        for (VariantResult.Segment s : result.segments) {
            double min = Math.min(x(s, 0), x(s, s.utmCoords.size() - 1));
            double max = Math.max(x(s, 0), x(s, s.utmCoords.size() - 1));
            if (Math.min(max, to) - Math.max(min, from) > EPS) {
                assertTrue(s.depthStart >= depth - EPS && s.depthEnd >= depth - EPS,
                        "Both endpoints must respect the complete raw vertical window");
            }
        }
    }

    private static double x(VariantResult.Segment s, int i) { return s.utmCoords.get(i).x - X; }

    private static final class Window {
        final double from, to, k;
        Window(double from, double to, double k) { this.from = from; this.to = to; this.k = k; }
    }

    private static InputModel.Restriction polygon(String id, String type, double from, double to) {
        InputModel.Restriction r = new InputModel.Restriction();
        r.id = id;
        r.restrictionType = type;
        r.utm = GeoUtil.GF.createPolygon(new Coordinate[]{at(from, -100), at(to, -100),
                at(to, 100), at(from, 100), at(from, -100)});
        return r;
    }

    private static InputModel.Restriction line(String id, String type, double x) {
        InputModel.Restriction r = new InputModel.Restriction();
        r.id = id;
        r.restrictionType = type;
        r.utm = GeoUtil.GF.createLineString(new Coordinate[]{at(x, -100), at(x, 100)});
        return r;
    }

    private static Coordinate at(double x, double y) { return new Coordinate(X + x, Y + y); }

    private static VariantResult assemble(InputModel input, String yaml, boolean depth, boolean reverse)
            throws Exception {
        RestrictionRules rules = RestrictionRules.builtIn().mergeYaml(new ByteArrayInputStream(
                yaml.getBytes(StandardCharsets.UTF_8)));
        NetworkPlan plan = new NetworkPlan();
        PlanNode source = new PlanNode(PlanNode.Kind.EXISTING_CHAMBER, at(0, 0));
        source.inputId = "source";
        PlanNode oks = new PlanNode(PlanNode.Kind.OKS, at(100, 0));
        oks.inputId = "consumer";
        oks.oksFlow = 1.0;
        plan.addNode(source);
        plan.addNode(oks);
        if (reverse) plan.addEdge(oks, source, List.of(oks.utm, source.utm));
        else plan.addEdge(source, oks, List.of(source.utm, oks.utm));
        return new NetworkAssembler(new ObstacleIndex(input, rules))
                .assemble(plan, depth ? "d1" : "v1", "shared", depth);
    }

    private static void evidence(String name, VariantResult result) throws Exception {
        String dir = System.getProperty("heatnet.geometryEvidenceDir");
        if (dir == null) return;
        Path path = Path.of(dir, name + ".geojson");
        Files.createDirectories(path.getParent());
        try (OutputStream out = Files.newOutputStream(path)) {
            new GeoJsonWriter(new Projection()).write(List.of(result), out);
        }
    }
}
