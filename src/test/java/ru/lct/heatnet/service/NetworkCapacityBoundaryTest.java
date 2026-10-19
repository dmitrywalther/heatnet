package ru.lct.heatnet.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.network.NetworkAssembler;
import ru.lct.heatnet.network.NetworkPlan;
import ru.lct.heatnet.network.PlanNode;
import ru.lct.heatnet.network.VariantResult;
import ru.lct.heatnet.refdata.CostModel;
import ru.lct.heatnet.refdata.PipeGrade;
import ru.lct.heatnet.refdata.PipeTable;
import ru.lct.heatnet.routing.GeoUtil;
import ru.lct.heatnet.routing.ObstacleIndex;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Границы таблицы 1: максимальный ДУ не является разрешением превысить её пределы. */
class NetworkCapacityBoundaryTest {
    private static final double MAX_FLOW = 22_501.9;
    private static final double MAX_LENGTH = 11_276;

    static Stream<PipeGrade> grades() {
        return PipeTable.grades().stream();
    }

    @ParameterizedTest
    @MethodSource("grades")
    void everyCatalogThresholdKeepsExactAndAdjacentFloatingPointSemantics(PipeGrade grade) {
        double capacity = grade.getCapacityTph();
        assertSame(grade, PipeTable.minGradeForFlow(Math.nextDown(capacity)));
        assertSame(grade, PipeTable.minGradeForFlow(capacity));
        PipeGrade next = PipeTable.next(grade);
        if (next != null) {
            assertSame(next, PipeTable.minGradeForFlow(Math.nextUp(capacity)));
        } else {
            TracingResult result = new TracingEngine().run(singleInput(Math.nextUp(capacity)));
            assertFalse(result.variants.isEmpty());
            assertEquals(List.of("consumer"), result.variants.get(0).unconnectedOksIds);
            assertTrue(result.variants.get(0).segments.isEmpty());
        }
    }

    @Test
    void zeroLoadStillConnectsAsItsOwnTargetWithTheMinimumGrade() {
        TracingResult result = new TracingEngine().run(singleInput(0));
        assertFalse(result.variants.isEmpty());
        VariantResult best = result.variants.get(0);
        assertTrue(best.isValid());
        assertTrue(best.unconnectedOksIds.isEmpty());
        assertFalse(best.segments.isEmpty());
        assertTrue(best.segments.stream().allMatch(s -> s.diameter == 50 && s.flowTph == 0));
        assertEquals(100, best.newNetworkLength);
        assertEquals(100 * 74_023 + 5_000_000, best.calculatedCost);
    }

    @Test
    void exactMaximumCapacityIsStillAdmissible() {
        VariantResult result = assemble(straight(MAX_FLOW, 100));
        assertTrue(result.isValid());
        assertEquals(1400, result.segments.get(0).diameter);
        assertEquals(MAX_FLOW, result.segments.get(0).flowTph);
    }

    @Test
    void overloadMustNotBeIssuedAsAnOrdinaryDu1400Segment() {
        VariantResult result = assemble(straight(MAX_FLOW + 0.01, 100));
        assertFalse(result.isValid(), "the table has no grade capable of carrying this flow");
        assertDiagnostic(result, "PIPE_CAPACITY_EXCEEDED");
    }

    @Test
    void capacityIsCheckedForTheSumOfDownstreamLoads() {
        NetworkPlan plan = new NetworkPlan();
        PlanNode source = node(plan, PlanNode.Kind.EXISTING_CHAMBER, "source", 0, 0, null);
        PlanNode fork = node(plan, PlanNode.Kind.NEW_CHAMBER, null, 100, 0, null);
        PlanNode a = node(plan, PlanNode.Kind.OKS, "A", 200, 0, 15_000.0);
        PlanNode b = node(plan, PlanNode.Kind.OKS, "B", 100, 100, 15_000.0);
        plan.addEdge(source, fork, List.of(source.utm, fork.utm));
        plan.addEdge(fork, a, List.of(fork.utm, a.utm));
        plan.addEdge(fork, b, List.of(fork.utm, b.utm));
        VariantResult result = assemble(plan);
        assertFalse(result.clearanceViolated, "this fixture has no geometry violation");
        assertFalse(result.isValid(), "individual OKS loads fit, but their shared trunk does not");
        assertDiagnostic(result, "PIPE_CAPACITY_EXCEEDED");
    }

    @Test
    void nonFiniteAggregateCannotBecomeAValidRoundedFlow() {
        VariantResult result = assemble(straight(Double.POSITIVE_INFINITY, 100));
        assertFalse(result.isValid());
        assertDiagnostic(result, "PIPE_CAPACITY_EXCEEDED");
    }

    @Test
    void exactMaximumContinuousLengthIsStillAdmissible() {
        VariantResult result = assemble(straight(MAX_FLOW, MAX_LENGTH));
        assertTrue(result.isValid());
        assertEquals(MAX_LENGTH, result.newNetworkLength);
        assertTrue(result.segments.stream().allMatch(s -> s.diameter == 1400));
    }

    @Test
    void maximumGradeMustNotSilentlyIgnoreAnExcessiveContinuousLength() {
        VariantResult result = assemble(straight(MAX_FLOW, MAX_LENGTH + 0.01));
        assertFalse(result.isValid());
        assertDiagnostic(result, "PIPE_LENGTH_LIMIT_EXCEEDED");
    }

    @Test
    void technicalSplitDoesNotResetContinuousLengthLimit() {
        NetworkPlan plan = new NetworkPlan();
        PlanNode source = node(plan, PlanNode.Kind.EXISTING_CHAMBER, "source", 0, 0, null);
        PlanNode middle = node(plan, PlanNode.Kind.TECHNICAL_NODE, null, 6000, 0, null);
        PlanNode target = node(plan, PlanNode.Kind.OKS, "consumer", MAX_LENGTH + 0.01, 0, MAX_FLOW);
        plan.addEdge(source, middle, List.of(source.utm, middle.utm));
        plan.addEdge(middle, target, List.of(middle.utm, target.utm));
        VariantResult result = assemble(plan);
        assertFalse(result.isValid());
        assertDiagnostic(result, "PIPE_LENGTH_LIMIT_EXCEEDED");
    }

    @Test
    void impossibleSingleLoadRemainsUnconnectedWhileTheOtherOksIsServed() {
        InputModel input = new InputModel();
        InputModel.ExistingChamber source = new InputModel.ExistingChamber();
        source.id = "source";
        source.utm = GeoUtil.GF.createPoint(new Coordinate(0, 0));
        input.chambers.add(source);
        input.oksPoints.add(oks("too-large", MAX_FLOW + 0.01, 100, 100));
        input.oksPoints.add(oks("ordinary", 1, 100, 0));
        TracingResult result = new TracingEngine().run(input);
        assertFalse(result.variants.isEmpty(), "a partial admissible network must remain available");
        assertFalse(result.depthVariants.isEmpty());
        for (VariantResult variant : List.of(result.variants.get(0), result.depthVariants.get(0))) {
            assertTrue(variant.isValid());
            assertEquals(List.of("too-large"), variant.unconnectedOksIds);
            assertEquals(CostModel.unconnectedPenalty(MAX_FLOW + 0.01), variant.unconnectedPenalty);
            assertTrue(variant.segments.stream().anyMatch(s -> "ordinary".equals(s.startNodeId)
                    || "ordinary".equals(s.endNodeId)));
            assertTrue(variant.segments.stream().allMatch(s -> s.flowTph <= PipeTable.byDu(s.diameter).getCapacityTph()));
        }
    }

    @Test
    void twoIndividuallySupportedLoadsUseSeparateSourceBranchesWhenTheirSumExceedsCapacity() {
        InputModel input = new InputModel();
        InputModel.ExistingChamber source = new InputModel.ExistingChamber();
        source.id = "source";
        source.utm = GeoUtil.GF.createPoint(new Coordinate(0, 0));
        input.chambers.add(source);
        input.oksPoints.add(oks("north", 15_000, 100, 40));
        input.oksPoints.add(oks("south", 15_000, 100, -40));
        TracingResult result = new TracingEngine().run(input);
        assertFalse(result.variants.isEmpty());
        VariantResult best = result.variants.get(0);
        assertTrue(best.isValid());
        assertTrue(best.unconnectedOksIds.isEmpty());
        assertTrue(best.segments.stream().allMatch(s -> s.flowTph <= PipeTable.byDu(s.diameter).getCapacityTph()),
                "a shared 30 000 t/h trunk is forbidden, but two source branches remain possible");
        assertEquals(2, best.existingChamberTieInCount);
        assertEquals(0, best.unconnectedPenalty);
        assertTrue(result.depthVariants.stream().allMatch(VariantResult::isValid));
    }

    @ParameterizedTest
    @CsvSource({"22501.800000000003, 0.1", "22501.9, 1e-12", "22501.9, 1e-20"})
    void exactSumMustNotLoseASharedBranchBecauseOfSubtractedHeadroom(double mainFlow, double addedFlow) {
        assertEquals(MAX_FLOW, mainFlow + addedFlow, "the same double sum used by the assembler fits exactly");
        assertTrue(addedFlow > MAX_FLOW - mainFlow, "this fixture exposes cancellation in subtraction");
        InputModel input = new InputModel();
        InputModel.ExistingChamber source = new InputModel.ExistingChamber();
        source.id = "source";
        source.utm = GeoUtil.GF.createPoint(new Coordinate(0, 0));
        input.chambers.add(source);
        input.oksPoints.add(oks("main", mainFlow, 500, 0));
        input.oksPoints.add(oks("small", addedFlow, 450, 40));
        // Enables aggregate checks without adding a load to the two connected branches.
        input.oksPoints.add(oks("unsupported", MAX_FLOW + 1, -100, 100));
        ObstacleIndex obstacles = new ObstacleIndex(input);
        NetworkPlan plan = new VariantPlanner(input, obstacles).plan(VariantPlanner.Strategy.SHARED,
                null, null, VariantPlanner.InsertionOrder.FLOW_DESC);
        VariantResult result = new NetworkAssembler(obstacles).assemble(plan, "v1", "shared");
        assertTrue(result.isValid());
        assertEquals(List.of("unsupported"), result.unconnectedOksIds);
        assertEquals(1, result.existingChamberTieInCount,
                "the supported small target should join the existing new trunk, not create a second source branch");
        assertEquals(1, result.chambers.size());
    }

    @Test
    void aSingleUnsupportedTargetProducesOnlyAnUnconnectedPenalty() {
        TracingResult result = new TracingEngine().run(singleInput(MAX_FLOW + 0.01));
        assertFalse(result.variants.isEmpty());
        for (VariantResult variant : List.of(result.variants.get(0), result.depthVariants.get(0))) {
            assertTrue(variant.isValid());
            assertTrue(variant.segments.isEmpty());
            assertTrue(variant.chambers.isEmpty());
            assertEquals(List.of("consumer"), variant.unconnectedOksIds);
            assertEquals(0, variant.constructionCost);
            assertEquals(CostModel.unconnectedPenalty(MAX_FLOW + 0.01), variant.calculatedCost);
        }
    }

    @Test
    void largeFinitePenaltyKeepsItsScoreInsteadOfLongSaturation() {
        NetworkPlan plan = new NetworkPlan();
        plan.unconnected.add(oks("large", 1e20, 100, 0));
        VariantResult result = assemble(plan);
        assertEquals(CostModel.unconnectedPenalty(1e20), result.unconnectedPenalty);
        assertEquals(CostModel.score(result.calculatedCost, 0), result.score);
        assertTrue(Double.isFinite(result.score));
    }

    @Test
    void rejectedCapacityCandidateIsNotRepublishedAsBaseOrDepth() {
        NetworkAssembler assembler = new NetworkAssembler(new ObstacleIndex(new InputModel()));
        NetworkPlan plan = straight(MAX_FLOW + 1, 100);
        VariantResult invalid = assembler.assemble(plan, "v1", "shared");
        TracingResult result = new TracingEngine().finalizeVariants(
                List.of(new TracingEngine.BuiltVariant(plan, invalid)), assembler);
        assertTrue(result.variants.isEmpty());
        assertTrue(result.depthVariants.isEmpty());
        assertTrue(result.diagnostics.stream().anyMatch(d -> d.code.equals("PIPE_CAPACITY_EXCEEDED")));
    }

    @Test
    void inadmissibleReconnectCannotReplaceAValidNetworkEvenWhenItsScoreIsLower() {
        VariantResult current = assemble(straight(MAX_FLOW, 100));
        VariantResult overloaded = assemble(straight(MAX_FLOW + 1, 10));
        // Simulates the final capacity diagnostic even before the assembler fix:
        // this isolates candidate selection from the separate capacity tests.
        overloaded.diagnostics.add(new ru.lct.heatnet.network.VariantDiagnostic(
                "v1", "base", "PIPE_CAPACITY_EXCEEDED", "Расход превышает пропускную способность."));
        assertTrue(current.isValid());
        assertFalse(overloaded.isValid());
        assertTrue(overloaded.score < current.score);
        assertFalse(new TracingEngine().acceptsReconnectCandidate(overloaded, current, false));
    }

    private static void assertDiagnostic(VariantResult result, String code) {
        assertTrue(result.diagnostics.stream().anyMatch(d -> d.code.equals(code)),
                "expected diagnostic " + code);
    }

    private static VariantResult assemble(NetworkPlan plan) {
        return new NetworkAssembler(new ObstacleIndex(new InputModel())).assemble(plan, "v1", "shared");
    }

    private static NetworkPlan straight(double flow, double length) {
        NetworkPlan plan = new NetworkPlan();
        PlanNode source = node(plan, PlanNode.Kind.EXISTING_CHAMBER, "source", 0, 0, null);
        PlanNode target = node(plan, PlanNode.Kind.OKS, "consumer", length, 0, flow);
        plan.addEdge(source, target, List.of(source.utm, target.utm));
        return plan;
    }

    private static PlanNode node(NetworkPlan plan, PlanNode.Kind kind, Object id,
                                 double x, double y, Double flow) {
        PlanNode node = plan.addNode(new PlanNode(kind, new Coordinate(x, y)));
        node.inputId = id;
        node.oksFlow = flow;
        return node;
    }

    private static InputModel.OksPoint oks(String id, double flow, double x, double y) {
        InputModel.OksPoint oks = new InputModel.OksPoint();
        oks.id = id;
        oks.flowTph = flow;
        oks.utm = GeoUtil.GF.createPoint(new Coordinate(x, y));
        return oks;
    }

    private static InputModel singleInput(double flow) {
        InputModel input = new InputModel();
        InputModel.ExistingChamber source = new InputModel.ExistingChamber();
        source.id = "source";
        source.utm = GeoUtil.GF.createPoint(new Coordinate(0, 0));
        input.chambers.add(source);
        input.oksPoints.add(oks("consumer", flow, 100, 0));
        return input;
    }
}
