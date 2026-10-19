package ru.lct.heatnet.network;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

class NetworkAlongRouteMarginTest {
    private static final double SQRT2 = Math.sqrt(2), EPS = 1e-6;
    private static final double FROM = 45 * SQRT2 - 3, TO = 55 * SQRT2 + 3;

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void actualSegmentsCostsAndVerticalWindowsUseTheAlongRouteMargins(String type) throws Exception {
        for (boolean unitCoefficient : new boolean[]{false, true}) {
            for (boolean depth : new boolean[]{false, true}) {
                double k = unitCoefficient ? 1 : (type.equals("road") ? 1.6 : 1.75);
                String yaml = "restrictions:\n  " + type + ":\n    k_spec: " + k
                        + "\n    vertical: {min_top_depth: 3.8}\n";
                VariantResult result = assemble(type, yaml, depth);
                assertTrue(result.isValid(), () -> "Diagnostics: " + result.diagnostics);
                assertTrue(result.unconnectedOksIds.isEmpty());
                assertEquals(Math.round(100 * SQRT2 * 100) / 100.0, result.newNetworkLength, EPS);
                assertTrue(result.segments.stream().anyMatch(s -> "consumer".equals(s.endNodeId)));
                assertEquals(10 * SQRT2 + 6,
                        result.segments.stream().filter(s -> s.special).mapToDouble(s ->
                                s.utmCoords.get(0).distance(s.utmCoords.get(s.utmCoords.size() - 1))).sum(), EPS);
                boolean fromNode = false, toNode = false;
                for (VariantResult.Segment segment : result.segments) {
                    double a = position(segment.utmCoords.get(0));
                    double b = position(segment.utmCoords.get(segment.utmCoords.size() - 1));
                    double mid = (a + b) / 2;
                    boolean special = mid > FROM && mid < TO;
                    assertEquals(special, segment.special);
                    assertEquals(special ? k : 1, segment.kSpec, EPS);
                    fromNode |= Math.abs(a - FROM) < EPS || Math.abs(b - FROM) < EPS;
                    toNode |= Math.abs(a - TO) < EPS || Math.abs(b - TO) < EPS;
                    double kd = 1;
                    if (depth) {
                        // Exported depths/lengths are rounded to centimetres.
                        assertTrue(Math.abs(segment.depthEnd - segment.depthStart) <= .1 * segment.lengthM + .011);
                        if (special) {
                            assertEquals(3.8, segment.depthStart, EPS);
                            assertEquals(3.8, segment.depthEnd, EPS);
                        }
                        kd = (factor(segment.depthStart) + factor(segment.depthEnd)) / 2;
                    }
                    assertEquals(Math.round(segment.lengthM * PipeTable.byDu(segment.diameter).getCostPerMeter()
                            * segment.kSpec * kd), segment.cost, EPS);
                }
                assertTrue(fromNode && toNode, "Both special boundaries must be actual segment cuts");
            }
        }
    }

    private static VariantResult assemble(String type, String yaml, boolean depth) throws Exception {
        InputModel input = new InputModel();
        InputModel.Restriction restriction = new InputModel.Restriction();
        restriction.id = "street";
        restriction.restrictionType = type;
        restriction.utm = GeoUtil.GF.createPolygon(new Coordinate[]{at(45, -100), at(55, -100),
                at(55, 200), at(45, 200), at(45, -100)});
        input.restrictions.add(restriction);
        RestrictionRules rules = RestrictionRules.builtIn().mergeYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        NetworkPlan plan = new NetworkPlan();
        PlanNode source = plan.addNode(new PlanNode(PlanNode.Kind.EXISTING_CHAMBER, at(0, 0)));
        source.inputId = "source";
        PlanNode oks = plan.addNode(new PlanNode(PlanNode.Kind.OKS, at(100, 100)));
        oks.inputId = "consumer";
        oks.oksFlow = 1.0;
        plan.addEdge(source, oks, List.of(source.utm, oks.utm));
        return new NetworkAssembler(new ObstacleIndex(input, rules)).assemble(plan, depth ? "d1" : "v1", "shared", depth);
    }

    private static double factor(double h) { return 1 + .1 * Math.max(0, h - 3); }
    private static double position(Coordinate p) { return (p.x - 400000 + p.y - 6170000) / SQRT2; }
    private static Coordinate at(double x, double y) { return new Coordinate(400000 + x, 6170000 + y); }
}
