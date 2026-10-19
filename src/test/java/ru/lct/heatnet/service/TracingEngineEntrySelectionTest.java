package ru.lct.heatnet.service;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.network.NetworkAssembler;
import ru.lct.heatnet.network.NetworkPlan;
import ru.lct.heatnet.network.VariantResult;
import ru.lct.heatnet.routing.GeoUtil;
import ru.lct.heatnet.routing.ObstacleIndex;
import ru.lct.heatnet.refdata.CostModel;

import static org.junit.jupiter.api.Assertions.*;

class TracingEngineEntrySelectionTest {

    @Test
    void ordinaryReconnectCannotTradeInvalidGeometryForScoreOrConnectionCount() {
        TracingEngine engine = new TracingEngine();
        VariantResult current = result(1_000_000, 100);
        VariantResult invalid = result(100_000, 10);
        invalid.clearanceViolated = true;
        current.unconnectedOksIds.add("missing");
        assertFalse(engine.acceptsReconnectCandidate(invalid, current, false),
                "an extra connection cannot legalize an invalid 1-opt candidate");
        current.unconnectedOksIds.clear();
        current.clearanceViolated = true;
        assertFalse(engine.acceptsReconnectCandidate(invalid, current, false),
                "an invalid current plan does not permit accepting another invalid cheaper plan");
    }

    @Test
    void additionalWallsCannotRaiseCostOrLoseValidityEvenWhenScoreImproves() {
        TracingEngine engine = new TracingEngine();
        VariantResult current = result(1_000_000, 100);
        VariantResult dearerButShorter = result(1_100_000, 50);
        assertTrue(dearerButShorter.score < current.score);
        assertFalse(engine.acceptsReconnectCandidate(dearerButShorter, current, true),
                "the extra interior metre must not be bought solely for a lower length-weighted score");
        assertTrue(engine.acceptsReconnectCandidate(dearerButShorter, current, false),
                "ordinary reconnect keeps its existing official-score comparison");

        VariantResult cheaperAndShorter = result(900_000, 80);
        assertTrue(engine.acceptsReconnectCandidate(cheaperAndShorter, current, true));
        assertFalse(engine.acceptsReconnectCandidate(result(900_000, 200), current, true),
                "lower cost alone must not override a worse official score");
        cheaperAndShorter.clearanceViolated = true;
        current.clearanceViolated = true;
        assertFalse(engine.acceptsReconnectCandidate(cheaperAndShorter, current, true),
                "an invalid additional-wall candidate stays rejected even if current is invalid too");

        VariantResult cheaperButLonger = result(900_000, 200);
        assertFalse(engine.acceptsReconnectCandidate(cheaperButLonger, current, true),
                "restored validity does not waive the extra wall's explicit score-improvement requirement");
        assertTrue(engine.acceptsReconnectCandidate(cheaperButLonger, current, false),
                "ordinary reconnect may still restore validity before comparing official scores");

        current.clearanceViolated = false;
        current.unconnectedOksIds.add("already-unconnected");
        VariantResult differentMissingOks = result(900_000, 80);
        differentMissingOks.unconnectedOksIds.add("previously-connected");
        assertFalse(engine.acceptsReconnectCandidate(differentMissingOks, current, true),
                "the additional wall must preserve previously connected consumers, not just their count");
    }

    private static VariantResult result(double cost, double length) {
        VariantResult r = new VariantResult();
        r.calculatedCost = cost;
        r.newNetworkLength = length;
        r.score = CostModel.score(cost, length);
        return r;
    }

    @Test
    void acceptsSlightlyLongerNormalWhenTheWholeNetworkIsCheaper() throws Exception {
        InputModel input = scene(5.8);
        ObstacleIndex obstacles = new ObstacleIndex(input);
        NetworkPlan shortestPlan = new VariantPlanner(input, obstacles)
                .plan(VariantPlanner.Strategy.SHARED, null);
        VariantResult shortest = new NetworkAssembler(obstacles).assemble(shortestPlan, "control", "shortest");
        assertEquals(5.0, internalLength(shortest, input), 1e-6);

        VariantResult improved = shared(new TracingEngine().run(input));

        assertTrue(improved.unconnectedOksIds.isEmpty());
        assertFalse(improved.clearanceViolated);
        assertEquals(5.8, internalLength(improved, input), 1e-6,
                "a normal only 0.8 m longer may win when the assembled network costs less");
        assertTrue(improved.calculatedCost < shortest.calculatedCost);
        assertTrue(improved.score < shortest.score);
        assertEquals(35.8, improved.newNetworkLength, 0.01);
        assertTargetPreserved(improved, input);
    }

    @Test
    void keepsTheShortEntryWhenCheaperWallExceedsTheOneMetreWindow() throws Exception {
        InputModel input = scene(6.2);

        VariantResult result = shared(new TracingEngine().run(input));

        assertTrue(result.unconnectedOksIds.isEmpty());
        assertFalse(result.clearanceViolated);
        assertEquals(5.0, internalLength(result, input), 1e-6,
                "a cheaper exterior route must not grow the interior entry beyond the configured window");
        assertTargetPreserved(result, input);
    }

    private static InputModel scene(double targetY) throws Exception {
        InputModel input = new InputModel();
        InputModel.Restriction own = new InputModel.Restriction();
        own.id = "own";
        own.restrictionType = "oks";
        own.utm = new WKTReader(GeoUtil.GF).read("POLYGON ((0 0,100 0,100 100,0 100,0 0))");
        input.restrictions.add(own);
        InputModel.OksPoint target = new InputModel.OksPoint();
        target.id = "target";
        target.flowTph = 20;
        target.utm = GeoUtil.GF.createPoint(new Coordinate(5, targetY));
        input.oksPoints.add(target);
        InputModel.ExistingChamber chamber = new InputModel.ExistingChamber();
        chamber.id = "south";
        chamber.utm = GeoUtil.GF.createPoint(new Coordinate(5, -30));
        input.chambers.add(chamber);
        return input;
    }

    private static VariantResult shared(TracingResult result) {
        return result.variants.stream().filter(v -> "v1".equals(v.variantId)).findFirst().orElseThrow();
    }

    private static double internalLength(VariantResult result, InputModel input) {
        return result.segments.stream().mapToDouble(s ->
                GeoUtil.polyline(s.utmCoords).intersection(input.restrictions.get(0).utm).getLength()).sum();
    }

    private static void assertTargetPreserved(VariantResult result, InputModel input) {
        Coordinate p = input.oksPoints.get(0).utm.getCoordinate();
        assertTrue(result.segments.stream().anyMatch(s ->
                "target".equals(s.endNodeId) && s.utmCoords.get(s.utmCoords.size() - 1).distance(p) < 1e-9
                        || "target".equals(s.startNodeId) && s.utmCoords.get(0).distance(p) < 1e-9),
                "assembler interpolation must retain the target within numerical precision");
    }
}
