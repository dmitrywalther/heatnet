package ru.lct.heatnet.network;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.geo.GeoJsonWriter;
import ru.lct.heatnet.geo.Projection;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.refdata.PipeTable;
import ru.lct.heatnet.refdata.RestrictionRules;
import ru.lct.heatnet.routing.GeoUtil;
import ru.lct.heatnet.routing.ObstacleIndex;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A cost-neutral special pass must still be identified by its actual geometry. */
class NetworkSpecialUnitCoefficientTest {
    private static final double X = 400_000;
    private static final double Y = 6_170_000;
    private static final double EPS = 1e-6;

    @Test
    void cableAtUnitCoefficientRemainsSpecialInBaseAndGeoJsonWithoutAPricePremium() throws Exception {
        InputModel input = new InputModel();
        input.restrictions.add(line("cable", "power_cable", 50));
        VariantResult result = assemble(input, "restrictions:\n"
                + "  power_cable:\n"
                + "    k_spec: 1.0\n", false);

        assertValidConnected(result);
        assertClassification(result, 48, 52);
        assertEquals(4.0, specialLength(result), EPS);
        assertEquals(100.0 * PipeTable.byDu(50).getCostPerMeter(),
                result.segments.stream().mapToDouble(s -> s.cost).sum(), EPS,
                "K=1 changes classification, not the pipe price");
        for (VariantResult.Segment segment : result.segments) {
            assertEquals(1.0, segment.kSpec, EPS);
            assertNull(segment.depthStart);
            assertNull(segment.depthEnd);
        }
        assertGeoJson(result, 4.0);
    }

    @Test
    void unitCoefficientTramStillEnforcesDepthAndDoesNotMarkOutsideRampsSpecial() throws Exception {
        InputModel input = new InputModel();
        input.restrictions.add(polygon("tram", "tram_tracks", 45, 55));
        VariantResult result = assemble(input, "restrictions:\n"
                + "  tram_tracks:\n"
                + "    k_spec: 1.0\n"
                + "    vertical: {min_top_depth: 3.8}\n", true);

        assertValidConnected(result);
        assertClassification(result, 42, 58);
        assertEquals(16.0, specialLength(result), EPS);
        assertTrue(result.segments.stream().anyMatch(s -> !s.special
                && Math.abs(s.depthStart - s.depthEnd) > EPS),
                "The scene must include ordinary ramps outside the crossing window");
        for (VariantResult.Segment segment : result.segments) {
            assertEquals(1.0, segment.kSpec, EPS);
            if (midpoint(segment) > 42 && midpoint(segment) < 58) {
                assertEquals(3.8, segment.depthStart, EPS);
                assertEquals(3.8, segment.depthEnd, EPS);
                assertEquals(Math.round(segment.lengthM * rate(segment) * 1.08), segment.cost, EPS);
            }
            assertTrue(Math.abs(segment.depthStart - segment.depthEnd)
                    <= 0.1 * segment.lengthM + EPS, "The depth constraint must preserve maximum slope");
            assertEquals(Math.round(segment.lengthM * rate(segment)
                    * (depthFactor(segment.depthStart) + depthFactor(segment.depthEnd)) / 2),
                    segment.cost, EPS, "Only the depth surcharge remains when Kspec=1");
        }
        VariantResult.Segment root = result.segments.stream()
                .filter(s -> "source".equals(s.startNodeId)).findFirst().orElseThrow();
        assertEquals(3.0, root.depthStart, EPS);
        assertGeoJson(result, 16.0);
    }

    @Test
    void overlappingUnitAndHigherCoefficientsKeepUnionClassificationAndUseMaximum() throws Exception {
        String yaml = "restrictions:\n"
                + "  power_cable:\n"
                + "    special_margin: 6\n"
                + "    k_spec: 1.0\n"
                + "    vertical: {crossing: under, min_top_depth: 3.8}\n"
                + "  tram_tracks:\n"
                + "    k_spec: 1.75\n"
                + "    vertical: {min_top_depth: 3.8}\n"
                + "  gas_pipeline:\n"
                + "    k_spec: 1.25\n"
                + "    vertical: {crossing: under, min_top_depth: 3.8}\n";
        for (boolean depth : List.of(false, true)) {
            InputModel input = new InputModel();
            input.restrictions.add(line("cable", "power_cable", 50)); // [44,56], K=1
            input.restrictions.add(polygon("tram", "tram_tracks", 48, 52)); // [45,55], K=1.75
            input.restrictions.add(line("gas", "gas_pipeline", 50)); // [48,52], K=1.25
            VariantResult result = assemble(input, yaml, depth);

            assertValidConnected(result);
            assertClassification(result, 44, 56);
            assertEquals(12.0, specialLength(result), EPS, "Special length is the union, without double-counting");
            assertEquals(2.0, result.segments.stream().filter(s -> s.special && s.kSpec == 1.0)
                    .mapToDouble(s -> s.lengthM).sum(), EPS, "Outer cost-neutral parts must remain special");
            assertEquals(10.0, result.segments.stream().filter(s -> s.kSpec == 1.75)
                    .mapToDouble(s -> s.lengthM).sum(), EPS);
            for (VariantResult.Segment segment : result.segments) {
                double mid = midpoint(segment);
                double expectedK = mid > 45 && mid < 55 ? 1.75 : 1.0;
                assertEquals(expectedK, segment.kSpec, EPS, "Overlap uses max, not sum or product");
                double kd = depth
                        ? (depthFactor(segment.depthStart) + depthFactor(segment.depthEnd)) / 2 : 1;
                assertEquals(Math.round(segment.lengthM * rate(segment) * expectedK * kd), segment.cost, EPS);
                if (depth && mid > 44 && mid < 56) {
                    assertEquals(3.8, segment.depthStart, EPS);
                    assertEquals(3.8, segment.depthEnd, EPS);
                }
            }
            assertGeoJson(result, 12.0);
        }
    }

    private static void assertClassification(VariantResult result, double from, double to) {
        for (VariantResult.Segment segment : result.segments) {
            double a = segment.utmCoords.get(0).x - X;
            double b = segment.utmCoords.get(segment.utmCoords.size() - 1).x - X;
            double mid = (a + b) / 2;
            boolean inside = mid > from && mid < to;
            assertEquals(inside, segment.special, "Wrong pass classification on [" + a + "," + b + "]");
            if (inside) {
                assertTrue(Math.min(a, b) >= from - EPS && Math.max(a, b) <= to + EPS,
                        "A special fragment must not extend beyond the real crossing window");
            }
        }
    }

    private static void assertGeoJson(VariantResult result, double expectedSpecialLength) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new GeoJsonWriter(new Projection()).write(List.of(result), out);
        JsonNode document = new ObjectMapper().readTree(out.toByteArray());
        Map<String, VariantResult.Segment> byId = new HashMap<>();
        result.segments.forEach(s -> byId.put(s.id, s));
        double special = 0, ordinary = 0;
        int seen = 0;
        for (JsonNode feature : document.path("features")) {
            JsonNode props = feature.path("properties");
            if (!"heat_network".equals(props.path("object_type").asText())) continue;
            VariantResult.Segment segment = byId.get(props.path("id").asText());
            assertNotNull(segment);
            assertEquals(segment.special ? "special" : "base", props.path("laying_method").asText());
            if ("special".equals(props.path("laying_method").asText())) special += props.path("length").asDouble();
            else ordinary += props.path("length").asDouble();
            seen++;
        }
        assertEquals(result.segments.size(), seen);
        assertEquals(expectedSpecialLength, special, EPS);
        assertEquals(100.0 - expectedSpecialLength, ordinary, EPS);
    }

    private static void assertValidConnected(VariantResult result) {
        assertTrue(result.isValid(), () -> "Unexpected diagnostics: " + result.diagnostics);
        assertTrue(result.unconnectedOksIds.isEmpty());
        assertEquals(100.0, result.newNetworkLength, EPS);
        assertTrue(result.segments.stream().anyMatch(s -> "consumer".equals(s.endNodeId)));
    }

    private static double midpoint(VariantResult.Segment s) {
        return (s.utmCoords.get(0).x + s.utmCoords.get(s.utmCoords.size() - 1).x) / 2 - X;
    }

    private static double rate(VariantResult.Segment s) {
        return PipeTable.byDu(s.diameter).getCostPerMeter();
    }

    private static double depthFactor(double h) {
        return 1.0 + 0.1 * Math.max(0, h - 3);
    }

    private static double specialLength(VariantResult result) {
        return result.segments.stream().filter(s -> s.special).mapToDouble(s -> s.lengthM).sum();
    }

    private static InputModel.Restriction line(String id, String type, double x) {
        InputModel.Restriction r = new InputModel.Restriction();
        r.id = id;
        r.restrictionType = type;
        r.utm = GeoUtil.GF.createLineString(new Coordinate[]{at(x, -100), at(x, 100)});
        return r;
    }

    private static InputModel.Restriction polygon(String id, String type, double from, double to) {
        InputModel.Restriction r = new InputModel.Restriction();
        r.id = id;
        r.restrictionType = type;
        r.utm = GeoUtil.GF.createPolygon(new Coordinate[]{at(from, -100), at(to, -100),
                at(to, 100), at(from, 100), at(from, -100)});
        return r;
    }

    private static Coordinate at(double x, double y) {
        return new Coordinate(X + x, Y + y);
    }

    private static VariantResult assemble(InputModel input, String yaml, boolean depth) throws Exception {
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
        plan.addEdge(source, oks, List.of(source.utm, oks.utm));
        return new NetworkAssembler(new ObstacleIndex(input, rules)).assemble(
                plan, depth ? "d1" : "v1", "shared", depth);
    }
}
