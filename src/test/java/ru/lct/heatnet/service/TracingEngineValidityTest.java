package ru.lct.heatnet.service;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.network.VariantResult;
import ru.lct.heatnet.network.NetworkAssembler;
import ru.lct.heatnet.network.NetworkPlan;
import ru.lct.heatnet.network.PlanNode;
import ru.lct.heatnet.routing.GeoUtil;
import ru.lct.heatnet.routing.ObstacleIndex;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class TracingEngineValidityTest {
    @Test
    void excludesDepthWhoseRealCableCrossingRequiresAnImpossibleRamp() {
        InputModel input = new InputModel();
        InputModel.ExistingChamber source = new InputModel.ExistingChamber();
        source.id = "source";
        source.utm = GeoUtil.GF.createPoint(new Coordinate(0, 0));
        input.chambers.add(source);
        InputModel.OksPoint oks = new InputModel.OksPoint();
        oks.id = "consumer";
        oks.flowTph = 1;
        oks.utm = GeoUtil.GF.createPoint(new Coordinate(100, 0));
        input.oksPoints.add(oks);
        InputModel.Restriction cable = new InputModel.Restriction();
        cable.id = "cable";
        cable.restrictionType = "power_cable";
        cable.utm = GeoUtil.GF.createLineString(new Coordinate[]{
                new Coordinate(5, -1000), new Coordinate(5, 1000)});
        input.restrictions.add(cable);

        TracingResult result = new TracingEngine().run(input);
        assertEquals(1, result.variants.size(), "the straight base route remains usable");
        VariantResult base = result.variants.get(0);
        assertEquals("v1", base.variantId);
        assertFalse(base.clearanceViolated);
        assertTrue(base.unconnectedOksIds.isEmpty());
        assertEquals(100, base.newNetworkLength, 1e-6);
        assertTrue(base.segments.stream().anyMatch(s -> s.special && s.kSpec == 1.15));
        assertTrue(result.depthVariants.isEmpty(),
                "the crossing starts 3 m from the source: reaching its shallow window from 3 m depth exceeds slope 0.1");
        assertTrue(result.diagnostics.stream().anyMatch(d -> d.variantId.equals("d1")
                && d.mode.equals("depth") && d.code.equals("DEPTH_SLOPE_EXCEEDED")
                && !d.segmentIds.isEmpty()));
    }

    @Test
    void invalidPrimaryDoesNotHideARealValidAlternativeAndItsDepthIdentity() {
        NetworkAssembler assembler = new NetworkAssembler(new ObstacleIndex(new InputModel()));
        TracingEngine.BuiltVariant bad = built(assembler, "v1", 0, true);
        TracingEngine.BuiltVariant good = built(assembler, "v3", 2000, false);
        assertTrue(bad.result.clearanceViolated, "real turn validation must reject the primary");
        assertFalse(good.result.clearanceViolated);
        TracingResult result = new TracingEngine().finalizeVariants(List.of(bad, good), assembler);
        assertEquals(List.of("v3"), ids(result.variants));
        assertEquals(List.of("d3"), ids(result.depthVariants));
        assertSame(good.result, result.variants.get(0));
        assertEquals(1, result.variants.get(0).rank);
        assertEquals(1, result.depthVariants.get(0).rank);
        assertTrue(result.diagnostics.stream().anyMatch(d -> d.variantId.equals("v1")
                && d.mode.equals("base") && d.code.equals("GEOMETRY_CONFLICT")));
    }

    @Test
    void invalidSharedCannotDisqualifyAValidAlternativeWithFewerConnections() {
        NetworkAssembler assembler = new NetworkAssembler(new ObstacleIndex(new InputModel()));
        TracingEngine.BuiltVariant invalid = built(assembler, "v1", 0, true);
        TracingEngine.BuiltVariant partial = built(assembler, "v3", 2000, false);
        InputModel.OksPoint missing = new InputModel.OksPoint();
        missing.id = "not-connected";
        missing.flowTph = 1;
        missing.utm = GeoUtil.GF.createPoint(new Coordinate(1000, 2000));
        partial.plan.unconnected.add(missing);
        VariantResult candidate = assembler.assemble(partial.plan, "v3", "alternative");
        assertFalse(invalid.result.isValid());
        assertTrue(candidate.isValid());
        assertEquals(1, candidate.unconnectedOksIds.size());
        TracingEngine engine = new TracingEngine();
        assertTrue(engine.acceptsAlternative(candidate, invalid.result, List.of(invalid.result)),
                "an inadmissible primary is not a usable completeness or duplicate reference");
        VariantResult validShared = assembler.assemble(built(assembler, "v1", 4000, false).plan, "v1", "shared");
        assertFalse(engine.acceptsAlternative(candidate, validShared, List.of(validShared)),
                "the original completeness requirement still applies to an admissible primary");
    }

    @Test
    void noAdmissibleBaseProducesAnEmptyResultWithAnExplicitReason() {
        NetworkAssembler assembler = new NetworkAssembler(new ObstacleIndex(new InputModel()));
        TracingEngine.BuiltVariant bad = built(assembler, "v1", 0, true);
        assertTrue(bad.result.clearanceViolated);
        TracingResult result = new TracingEngine().finalizeVariants(List.of(bad), assembler);
        assertTrue(result.variants.isEmpty());
        assertTrue(result.depthVariants.isEmpty(), "invalid XY must not be reissued under a depth ID");
        assertFalse(result.diagnostics.isEmpty());
        assertTrue(result.diagnostics.stream().allMatch(d -> d.variantId.equals("v1") && d.mode.equals("base")));
    }

    @Test
    void depthFailureRemovesOnlyItsOwnModeAndPreservesOtherRanks() {
        InputModel input = new InputModel();
        InputModel.Restriction cable = new InputModel.Restriction();
        cable.id = "cable";
        cable.restrictionType = "power_cable";
        cable.utm = GeoUtil.GF.createLineString(new Coordinate[]{
                new Coordinate(5, -1000), new Coordinate(5, 1000)});
        input.restrictions.add(cable);
        NetworkAssembler assembler = new NetworkAssembler(new ObstacleIndex(input));
        TracingEngine.BuiltVariant shallow = built(assembler, "v1", 0, false);
        TracingEngine.BuiltVariant flat = built(assembler, "v3", 2000, false);
        assertFalse(shallow.result.clearanceViolated);
        assertFalse(flat.result.clearanceViolated);
        TracingResult result = new TracingEngine().finalizeVariants(List.of(shallow, flat), assembler);
        assertEquals(2, result.variants.size());
        assertEquals(List.of("d3"), ids(result.depthVariants));
        assertEquals(1, result.depthVariants.get(0).rank);
        assertEquals(100, result.depthVariants.get(0).newNetworkLength, 1e-9);
        assertTrue(result.depthVariants.get(0).segments.stream().allMatch(s -> s.depthStart == 3 && s.depthEnd == 3));
        assertTrue(result.diagnostics.stream().anyMatch(d -> d.variantId.equals("d1")
                && d.code.equals("DEPTH_SLOPE_EXCEEDED")));
    }

    private static List<String> ids(List<VariantResult> results) {
        return results.stream().map(v -> v.variantId).collect(Collectors.toList());
    }

    private static TracingEngine.BuiltVariant built(NetworkAssembler assembler, String id,
                                                     double y, boolean badTurn) {
        NetworkPlan plan = new NetworkPlan();
        PlanNode source = plan.addNode(new PlanNode(PlanNode.Kind.EXISTING_CHAMBER, new Coordinate(0, y)));
        source.inputId = "source-" + y;
        PlanNode oks = plan.addNode(new PlanNode(PlanNode.Kind.OKS, new Coordinate(100, y)));
        oks.inputId = "consumer-" + y;
        oks.oksFlow = 1.0;
        plan.addEdge(source, oks, badTurn
                ? List.of(source.utm, new Coordinate(60, y), new Coordinate(40, y + 20), oks.utm)
                : List.of(source.utm, oks.utm));
        return new TracingEngine.BuiltVariant(plan,
                assembler.assemble(plan, id, id.equals("v1") ? "shared" : "alternative"));
    }
}
