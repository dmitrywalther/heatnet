package ru.lct.heatnet.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.refdata.CostModel;
import ru.lct.heatnet.refdata.PipeTable;
import ru.lct.heatnet.refdata.RestrictionRules;
import ru.lct.heatnet.routing.GeoUtil;
import ru.lct.heatnet.routing.ObstacleIndex;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Clarification 13: only a real source chamber may start inside a road/tram polygon. */
class NetworkSourcePolygonChamberTest {
    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void existingChamberHasOneSidedSpecialPassAndSourceDepth(String type) throws Exception {
        Fixture f = fixture(type, false, false, List.of(c(0, 0), c(30, 0)));
        assertPassAndPrice(f, 8.0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void newChamberOnExistingPipeHasTheSamePassInReverseDirection(String type) throws Exception {
        Fixture f = fixture(type, true, true, List.of(c(0, 0), c(30, 0)));
        assertPassAndPrice(f, 8.0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void obliqueSourceExitExtendsThreeMetresAlongTheRoute(String type) throws Exception {
        Fixture f = fixture(type, false, false, List.of(c(0, 0), c(30, 30)));
        assertPassAndPrice(f, 5 * Math.sqrt(2) + 3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void bendExactlyThreeMetresAfterExitIsAllowed(String type) throws Exception {
        assertPassAndPrice(fixture(type, false, false,
                List.of(c(0, 0), c(8, 0), c(30, 10))), 8);
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void bendsInsidePolygonAndBeforeExitMarginRemainForbidden(String type) throws Exception {
        for (double x : new double[]{2, 5.5, 7.9}) {
            Fixture f = fixture(type, false, false, List.of(c(0, 0), c(x, 0), c(30, 10)));
            assertFalse(f.assemble(false).isValid(), "no bend in the one-sided special pass at " + x);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void exitBelowRequiredAngleRemainsForbidden(String type) throws Exception {
        Fixture f = fixture(type, false, false, List.of(c(0, 0), c(10, 15)));
        assertFalse(f.assemble(false).isValid(), "exit angle is 33.69 degrees, below 45");
    }

    @Test
    void ordinaryNewNetworkChamberCannotClaimTheSourceException() throws Exception {
        Fixture f = fixture("road", true, false, List.of(c(0, 0), c(30, 0)));
        f.source.splitPipeDu = null;
        assertFalse(f.assemble(false).isValid(), "coincident geometry/exempt pipe IDs do not prove a source tie");
    }

    @Test
    void sourcePermissionDoesNotReachAnInteriorPointOfALongPolyline() throws Exception {
        Fixture f = fixture("road", false, false,
                List.of(c(0, 0), c(15, 0), c(2, 10), c(30, 10)));
        assertFalse(f.assemble(false).isValid(), "the second entry/bend cannot inherit source privilege");
    }

    @Test
    void sourcePermissionDoesNotSkipAnotherForbiddenObject() throws Exception {
        Fixture f = fixture("road", false, false, List.of(c(0, 0), c(30, 0)));
        restriction(f.input, "wall", "building", "POLYGON ((11 -5,13 -5,13 5,11 5,11 -5))");
        assertFalse(f.assemble(false).isValid());
    }

    @Test
    void sourceDepthInsideThePassMustStillSatisfyItsVerticalRule() throws Exception {
        Fixture f = fixture("road", false, false, List.of(c(0, 0), c(30, 0)));
        f.rules = RestrictionRules.builtIn().mergeYaml(new ByteArrayInputStream(
                "restrictions:\n  road:\n    vertical:\n      min_top_depth: 3.8\n".getBytes(StandardCharsets.UTF_8)));
        assertTrue(f.assemble(false).isValid(), "XY source exception is permitted");
        VariantResult depth = f.assemble(true);
        assertFalse(depth.isValid(), "fixed source H=3 cannot satisfy >=3.8 at its own endpoint");
        assertFalse(depth.diagnostics.isEmpty());
    }

    private static void assertPassAndPrice(Fixture f, double expectedSpecialLength) {
        for (boolean depth : new boolean[]{false, true}) {
            VariantResult result = f.assemble(depth);
            assertTrue(result.isValid(), () -> "source road/tram exit rejected: " + result.diagnostics);
            assertTrue(result.unconnectedOksIds.isEmpty());
            double special = result.segments.stream().filter(s -> s.special)
                    .mapToDouble(s -> GeoUtil.polyline(s.utmCoords).getLength()).sum();
            assertEquals(expectedSpecialLength, special, 1e-7,
                    "special pass starts at chamber; only the exit side receives +3m along the route");
            assertEquals(f.plan.edges.get(0).length(), result.newNetworkLength, 0.01);
            for (VariantResult.Segment segment : result.segments) {
                double k = segment.special ? (f.type.equals("road") ? 1.6 : 1.75) : 1;
                assertEquals(k, segment.kSpec, 1e-12);
                assertEquals(Math.round(segment.lengthM * PipeTable.byDu(segment.diameter).getCostPerMeter() * k),
                        segment.cost, 1e-9);
                if (depth) {
                    assertEquals(3.0, segment.depthStart, 1e-9);
                    assertEquals(3.0, segment.depthEnd, 1e-9);
                }
            }
            if (f.source.splitPipeDu != null) {
                assertEquals(1, result.chambers.size());
                assertEquals(CostModel.chamberCost(200), result.chambers.get(0).cost, 1e-9,
                        "road multiplier applies to pipe, never chamber");
            } else {
                assertTrue(result.chambers.isEmpty());
            }
        }
    }

    private static Fixture fixture(String type, boolean newSource, boolean reverse,
                                   List<Coordinate> coords) throws Exception {
        Fixture f = new Fixture();
        f.type = type;
        restriction(f.input, "transport", type, "POLYGON ((-5 -40,5 -40,5 40,-5 40,-5 -40))");
        InputModel.ExistingPipe pipe = new InputModel.ExistingPipe();
        pipe.id = "source-pipe";
        pipe.diameter = 200;
        pipe.utm = GeoUtil.polyline(List.of(c(0, -100), c(0, 100)));
        f.input.pipes.add(pipe);
        f.source = f.plan.addNode(new PlanNode(newSource ? PlanNode.Kind.NEW_CHAMBER
                : PlanNode.Kind.EXISTING_CHAMBER, coords.get(0)));
        f.source.inputId = "source";
        f.source.exemptSpecialIds = Set.of(pipe.id);
        if (newSource) f.source.splitPipeDu = 200;
        else f.plan.tieInsToExisting.put(f.source, 1);
        PlanNode consumer = f.plan.addNode(new PlanNode(PlanNode.Kind.OKS, coords.get(coords.size() - 1)));
        consumer.inputId = "consumer";
        consumer.oksFlow = 1.0;
        if (reverse) {
            java.util.ArrayList<Coordinate> reversed = new java.util.ArrayList<>(coords);
            java.util.Collections.reverse(reversed);
            f.plan.addEdge(consumer, f.source, reversed);
        } else f.plan.addEdge(f.source, consumer, coords);
        return f;
    }

    private static Coordinate c(double x, double y) { return new Coordinate(x, y); }

    private static void restriction(InputModel input, Object id, String type, String wkt) throws Exception {
        InputModel.Restriction r = new InputModel.Restriction();
        r.id = id;
        r.restrictionType = type;
        r.utm = new WKTReader(GeoUtil.GF).read(wkt);
        input.restrictions.add(r);
    }

    private static final class Fixture {
        final InputModel input = new InputModel();
        final NetworkPlan plan = new NetworkPlan();
        RestrictionRules rules = RestrictionRules.builtIn();
        PlanNode source;
        String type;
        VariantResult assemble(boolean depth) {
            return new NetworkAssembler(new ObstacleIndex(input, rules))
                    .assemble(plan, depth ? "d1" : "v1", "fixture", depth);
        }
    }
}
