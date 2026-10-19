package ru.lct.heatnet.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.network.NetworkAssembler;
import ru.lct.heatnet.network.NetworkPlan;
import ru.lct.heatnet.network.PlanEdge;
import ru.lct.heatnet.network.PlanNode;
import ru.lct.heatnet.network.VariantResult;
import ru.lct.heatnet.refdata.PipeGrade;
import ru.lct.heatnet.refdata.PipeTable;
import ru.lct.heatnet.routing.EdgeValidator;
import ru.lct.heatnet.routing.GeoUtil;
import ru.lct.heatnet.routing.GradeContext;
import ru.lct.heatnet.routing.ObstacleIndex;
import ru.lct.heatnet.routing.TieCandidate;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class VariantPlannerSplitPointTest {
    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void reconnectChoosesAnotherChamberWhenAnOtherwiseValidNormalWouldBreakTheExistingPass(String type) throws Exception {
        Fixture f = road(type);
        InputModel.ExistingChamber source = new InputModel.ExistingChamber();
        source.id = f.a.inputId;
        source.utm = GeoUtil.GF.createPoint(f.a.utm);
        f.input.chambers.add(source);
        f.plan.tieInsToExisting.put(f.a, 1);
        InputModel.OksPoint target = new InputModel.OksPoint();
        target.id = "new-consumer";
        target.flowTph = 1.0;
        target.utm = GeoUtil.GF.createPoint(new Coordinate(-1.5, 20));
        f.input.oksPoints.add(target);
        restriction(f.input, "target-building", "oks", "POLYGON ((-6.5 15,3.5 15,3.5 25,-6.5 25,-6.5 15))");
        f.refresh();
        Coordinate temptingTie = new Coordinate(-1.5, 0);
        assertTrue(f.check(temptingTie, target.utm.getCoordinate(), Set.of(), "target-building").valid,
                "the incoming normal really is outside road/tram proximity, but inside the old pass's +3m margin");
        assertFalse(new NetworkAssembler(f.obstacles).assemble(f.plan, "before", "fixture").clearanceViolated);

        assertTrue(f.planner.reconnect(f.plan, target));

        assertEquals(PlanNode.Kind.EXISTING_CHAMBER, f.a.kind);
        assertEquals(source.id, f.a.inputId);
        for (InputModel.OksPoint expected : List.of(f.input.oksPoints.get(0), target)) {
            Coordinate point = expected.utm.getCoordinate();
            PlanNode consumer = f.plan.findNode(point);
            assertNotNull(consumer);
            assertEquals(PlanNode.Kind.OKS, consumer.kind);
            assertEquals(expected.id, consumer.inputId);
            assertEquals(1, f.plan.degree(consumer));
            assertEquals(0, consumer.utm.distance(point), 1e-9);
            assertTrue(reaches(f.plan, consumer, f.a), "each original P must remain connected to the source");
        }
        VariantResult result = new NetworkAssembler(f.obstacles).assemble(f.plan, "after", "fixture");
        assertFalse(result.clearanceViolated, "reconnect must preserve the existing straight special crossing");
        assertNull(f.plan.findNode(temptingTie), "the unsafe direct normal must yield to another legal chamber");
        assertTrue(result.segments.stream().anyMatch(s -> s.special
                && Math.abs(s.kSpec - (type.equals("road") ? 1.60 : 1.75)) < 1e-9));
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void sampledChamberCannotSplitTheStraightSpecialPassInsideItsThreeMetreMargin(String type) throws Exception {
        Fixture f = road(type);
        assertTrue(f.check(f.a.utm, f.b.utm, Set.of(), null).valid,
                "the uncut perpendicular special pass is genuinely valid");
        assertFalse(f.check(new Coordinate(0, 0), f.b.utm, Set.of(), null).valid,
                "cutting one metre before the core invalidates its straight margin");

        List<TieCandidate> candidates = candidates(f, f.ctx);

        assertFalse(has(candidates, f.edge, new Coordinate(0, 0)),
                "a sampled chamber inside the special-pass margin must be excluded before search");
        assertTrue(has(candidates, f.edge, new Coordinate(10, 0)),
                "a safe point beyond the same crossing remains available");
        assertEquals(1, f.plan.edges.size());
    }

    @Test
    void directNormalCannotChooseAnInteriorPointThatBreaksTheExistingRoadCrossing() throws Exception {
        Fixture f = road("road");
        assertTrue(f.check(f.a.utm, f.b.utm, Set.of(), null).valid);

        assertNull(direct(f, new Coordinate(0, 0)), "direct normals need the same split guard as sampled ties");
        assertNotNull(direct(f, new Coordinate(10, 0)), "a valid interior tie must not be tested against itself");
    }

    @Test
    void staleInvalidRoadTieIsRejectedBeforeAnyPlanMutation() throws Exception {
        Fixture f = road("road");
        List<Object> before = state(f.plan);

        assertNull(resolve(f, new Coordinate(0, 0)), "a stale/direct tie must not bypass candidate filtering");

        assertEquals(before, state(f.plan));
    }

    @Test
    void sourceApproachUsesTheClearanceContextNotTheSmallIncomingBranchCostGrade() throws Exception {
        Fixture f = source(false, 500);
        assertEquals(400, f.ctx.grade.getDu());
        Coordinate twenty = at(f, 20), thirty = at(f, 30);
        assertTrue(f.check(f.a.utm, f.b.utm, f.a.exemptSpecialIds, null).valid);
        assertFalse(f.check(twenty, f.b.utm, Set.of(), null).valid);
        assertTrue(new EdgeValidator(f.obstacles.forGrade(PipeTable.byDu(100)))
                .check(twenty, f.b.utm, Set.of(), null).valid,
                "the smaller cost grade would miss this actual trunk-clearance failure");

        List<TieCandidate> candidates = candidates(f, f.ctx);

        assertFalse(has(candidates, f.edge, twenty));
        assertTrue(has(candidates, f.edge, thirty));
        assertNull(direct(f, twenty));
    }

    @Test
    void existingLargerTrunkGradeCannotBeWeakenedByASmallerContext() throws Exception {
        Fixture f = source(false, 20);
        assertEquals(100, f.ctx.grade.getDu());
        f.edge.grade = PipeTable.byDu(400);

        assertFalse(has(candidates(f, f.ctx), f.edge, at(f, 20)),
                "known trunk DU 400 still governs its own cut when the new branch needs DU 100");
        assertNull(direct(f, at(f, 20)));
    }

    @Test
    void retryContextAboveTheDefaultClearanceGradeIsHonoured() throws Exception {
        Fixture f = source(false, 20);
        GradeContext retryContext = f.obstacles.forGrade(PipeTable.byDu(400));
        assertNull(f.edge.grade, "initial planning does not yet assign actual edge grades");

        assertFalse(has(candidates(f, retryContext), f.edge, at(f, 20)),
                "the retry context cannot silently fall back to the default DU 100");
    }

    @Test
    void reversedSourceApproachKeepsTheExemptionAtTheOriginalEndOnly() throws Exception {
        Fixture f = source(true, 500);
        Coordinate bad = new LengthIndexedLine(GeoUtil.polyline(f.edge.coords)).extractPoint(f.edge.length() - 20);
        Coordinate safe = new LengthIndexedLine(GeoUtil.polyline(f.edge.coords)).extractPoint(f.edge.length() - 30);
        assertTrue(f.check(f.a.utm, f.b.utm, f.b.exemptSpecialIds, null).valid);
        List<Object> before = state(f.plan);

        assertNull(direct(f, bad));
        assertNull(resolve(f, bad));
        assertEquals(before, state(f.plan));
        PlanNode split = resolve(f, safe);
        assertNotNull(split);
        assertTrue(split.exemptSpecialIds.isEmpty(), "the new junction must not inherit the source-pipe waiver");
        assertEquals(Set.of("source-pipe"), f.b.exemptSpecialIds);
        assertEquals(2, f.plan.edges.size());
        assertStaticChildrenValid(f);
    }

    @Test
    void acceptedSourceSplitDoesNotWaiveTheNextBranchsRealSpecialCrossing() throws Exception {
        Fixture f = source(false, 500);
        PlanNode split = resolve(f, at(f, 30));
        assertNotNull(split);
        assertStaticChildrenValid(f);
        assertTrue(split.exemptSpecialIds.isEmpty());
        EdgeValidator validator = new EdgeValidator(f.ctx);
        Coordinate far = new Coordinate(-10, split.utm.y);
        EdgeValidator.EdgeCheck crossing = validator.check(split.utm, far, split.exemptSpecialIds, null);
        assertTrue(crossing.valid);
        assertEquals(1, crossing.rawSpecials.size(), "crossing source pipe still needs a special passage");
        assertEquals(4.0, crossing.rawSpecials.get(0).to - crossing.rawSpecials.get(0).from, 1e-6);
        assertEquals(1.05, crossing.rawSpecials.get(0).kSpec, 1e-9);
    }

    @Test
    void sourceSplitOnTheForbiddenSideOfTheMillimetreSnapCannotCreateMismatchedEndpoints() throws Exception {
        Fixture f = plain(new Coordinate(-20, 0), new Coordinate(0, 20), 20);
        f.edge.coords = List.of(f.a.utm, new Coordinate(0, 0), f.b.utm);
        Coordinate justAfterCorner = new Coordinate(0, 0.0004);
        NetworkPlan proof = f.plan.copy();
        PlanNode unsafe = proof.splitEdge(proof.edges.get(0), justAfterCorner, PlanNode.Kind.NEW_CHAMBER);
        PlanEdge left = proof.edges.get(0);
        assertEquals(0.0004, left.coords.get(left.coords.size() - 1).distance(unsafe.utm), 1e-9,
                "the actual split operation drops this nearby vertex and leaves an endpoint mismatch");
        List<Object> before = state(f.plan);

        assertNull(direct(f, justAfterCorner));
        assertNull(resolve(f, justAfterCorner));
        assertEquals(before, state(f.plan));
    }

    @Test
    void safeSideOfTheMillimetreSnapUsesTheActualChangedOutgoingSegment() throws Exception {
        Fixture f = plain(new Coordinate(-20, 0), new Coordinate(0, 20), 20);
        f.edge.coords = List.of(f.a.utm, new Coordinate(0, 0), f.b.utm);
        Coordinate justBeforeCorner = new Coordinate(-0.0004, 0);

        PlanNode split = resolve(f, justBeforeCorner);

        assertNotNull(split);
        assertEquals(2, f.plan.edges.size());
        for (PlanEdge edge : f.plan.edges) {
            assertEquals(0, edge.coords.get(0).distance(edge.a.utm), 1e-6);
            assertEquals(0, edge.coords.get(edge.coords.size() - 1).distance(edge.b.utm), 1e-6);
        }
        assertEquals(List.of(split.utm, f.b.utm), f.plan.edges.get(1).coords,
                "the near corner is really removed from the outgoing child");
    }

    @Test
    void ownOksTailExemptionCannotLeakOntoTheNewChamber() throws Exception {
        Fixture f = plain(new Coordinate(-20, 0), new Coordinate(20, 0), 20);
        restriction(f.input, "own-building", "oks", "POLYGON ((15 -5,25 -5,25 5,15 5,15 -5))");
        f.b.ownRestrictionId = "own-building";
        f.refresh();
        assertTrue(f.check(f.a.utm, f.b.utm, Set.of(), "own-building").valid);
        List<Object> before = state(f.plan);

        assertNull(resolve(f, new Coordinate(10, 0)),
                "the old left segment gains no own-building exemption merely because the far end is an OKS");
        assertEquals(before, state(f.plan));
        assertNotNull(resolve(f, new Coordinate(0, 0)));
        assertStaticChildrenValid(f);
    }

    @Test
    void reversedOwnOksTailAlsoRetainsItsExemptionOnlyOnTheOriginalEnd() throws Exception {
        Fixture f = plain(new Coordinate(20, 0), new Coordinate(-20, 0), 20);
        f.a.kind = PlanNode.Kind.OKS;
        f.a.oksFlow = 20.0;
        f.a.ownRestrictionId = "own-building";
        f.b.kind = PlanNode.Kind.EXISTING_CHAMBER;
        restriction(f.input, "own-building", "oks", "POLYGON ((15 -5,25 -5,25 5,15 5,15 -5))");
        f.refresh();
        assertTrue(f.check(f.a.utm, f.b.utm, Set.of(), "own-building").valid);
        List<Object> before = state(f.plan);

        assertNull(resolve(f, new Coordinate(10, 0)));
        assertEquals(before, state(f.plan));
        assertNotNull(resolve(f, new Coordinate(0, 0)));
        assertStaticChildrenValid(f);
    }

    @SuppressWarnings("unchecked")
    private static List<TieCandidate> candidates(Fixture f, GradeContext ctx) throws Exception {
        Method m = VariantPlanner.class.getDeclaredMethod("buildCandidates", NetworkPlan.class,
                GradeContext.class, PipeGrade.class, VariantPlanner.Strategy.class, Set.class);
        m.setAccessible(true);
        return (List<TieCandidate>) m.invoke(f.planner, f.plan, ctx, PipeTable.byDu(100),
                VariantPlanner.Strategy.SHARED, Set.of());
    }

    private static TieCandidate direct(Fixture f, Coordinate cut) throws Exception {
        Method m = VariantPlanner.class.getDeclaredMethod("directTieCandidate", NetworkPlan.class,
                PlanEdge.class, Coordinate.class, PipeGrade.class);
        m.setAccessible(true);
        return (TieCandidate) m.invoke(f.planner, f.plan, f.edge, cut, PipeTable.byDu(100));
    }

    private static PlanNode resolve(Fixture f, Coordinate cut) throws Exception {
        Method m = VariantPlanner.class.getDeclaredMethod("resolveTieNode", NetworkPlan.class, TieCandidate.class);
        m.setAccessible(true);
        return (PlanNode) m.invoke(f.planner, f.plan,
                new TieCandidate(TieCandidate.Kind.NEW_NETWORK_EDGE_POINT, cut, f.edge, 5_000_000, null));
    }

    private static boolean has(List<TieCandidate> candidates, PlanEdge edge, Coordinate point) {
        return candidates.stream().anyMatch(t -> t.kind == TieCandidate.Kind.NEW_NETWORK_EDGE_POINT
                && t.refId == edge && t.location.distance(point) < 1e-6);
    }

    private static Coordinate at(Fixture f, double distance) {
        return new LengthIndexedLine(GeoUtil.polyline(f.edge.coords)).extractPoint(distance);
    }

    private static boolean reaches(NetworkPlan plan, PlanNode start, PlanNode source) {
        Set<PlanNode> visited = new java.util.HashSet<>();
        java.util.ArrayDeque<PlanNode> queue = new java.util.ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            PlanNode node = queue.removeFirst();
            if (!visited.add(node)) continue;
            if (node == source) return true;
            for (PlanEdge edge : plan.incident(node)) queue.addLast(edge.other(node));
        }
        return false;
    }

    private static void assertStaticChildrenValid(Fixture f) {
        for (PlanEdge edge : f.plan.edges) {
            for (int i = 0; i + 1 < edge.coords.size(); i++) {
                Set<Object> exempt = new java.util.HashSet<>();
                if (i == 0) exempt.addAll(edge.a.exemptSpecialIds);
                if (i == edge.coords.size() - 2) exempt.addAll(edge.b.exemptSpecialIds);
                Object own = i == 0 && edge.a.kind == PlanNode.Kind.OKS ? edge.a.ownRestrictionId
                        : i == edge.coords.size() - 2 && edge.b.kind == PlanNode.Kind.OKS ? edge.b.ownRestrictionId : null;
                assertTrue(f.check(edge.coords.get(i), edge.coords.get(i + 1), exempt, own).valid);
            }
        }
    }

    private static Fixture road(String type) throws Exception {
        Fixture f = plain(new Coordinate(-20, 0), new Coordinate(40, 0), 20);
        restriction(f.input, "street", type, "POLYGON ((1 -10,5 -10,5 10,1 10,1 -10))");
        f.refresh();
        return f;
    }

    private static Fixture source(boolean reverse, double flow) {
        Fixture f = plain(reverse ? new Coordinate(12, -112) : new Coordinate(0, 0),
                reverse ? new Coordinate(0, 0) : new Coordinate(12, -112), flow);
        PlanNode root = reverse ? f.b : f.a;
        root.kind = PlanNode.Kind.EXISTING_CHAMBER;
        root.exemptSpecialIds = Set.of("source-pipe");
        InputModel.ExistingPipe pipe = new InputModel.ExistingPipe();
        pipe.id = "source-pipe";
        pipe.diameter = 300;
        pipe.utm = GeoUtil.segment(new Coordinate(0, -200), root.utm);
        f.input.pipes.add(pipe);
        f.refresh();
        return f;
    }

    private static Fixture plain(Coordinate from, Coordinate to, double flow) {
        Fixture f = new Fixture();
        f.a = f.plan.addNode(new PlanNode(PlanNode.Kind.EXISTING_CHAMBER, from));
        f.a.inputId = "old-end-a";
        f.b = f.plan.addNode(new PlanNode(PlanNode.Kind.OKS, to));
        f.b.inputId = "existing-consumer";
        f.b.oksFlow = flow;
        f.edge = f.plan.addEdge(f.a, f.b, List.of(from, to));
        InputModel.OksPoint target = new InputModel.OksPoint();
        target.id = "existing-consumer";
        target.flowTph = flow;
        target.utm = GeoUtil.GF.createPoint(to);
        f.input.oksPoints.add(target);
        f.refresh();
        return f;
    }

    private static void restriction(InputModel input, String id, String type, String wkt) throws Exception {
        InputModel.Restriction r = new InputModel.Restriction();
        r.id = id;
        r.restrictionType = type;
        r.utm = new WKTReader(GeoUtil.GF).read(wkt);
        input.restrictions.add(r);
    }

    private static List<Object> state(NetworkPlan plan) {
        List<Object> state = new ArrayList<>();
        state.add(List.copyOf(plan.nodes));
        state.add(List.copyOf(plan.edges));
        for (PlanNode n : plan.nodes) state.add(Arrays.asList(n.kind, new Coordinate(n.utm), n.inputId,
                Set.copyOf(n.exemptSpecialIds), n.ownRestrictionId));
        for (PlanEdge e : plan.edges) state.add(Arrays.asList(e.a, e.b, new ArrayList<>(e.coords), e.grade));
        return state;
    }

    private static final class Fixture {
        final InputModel input = new InputModel();
        final NetworkPlan plan = new NetworkPlan();
        PlanNode a, b;
        PlanEdge edge;
        ObstacleIndex obstacles;
        GradeContext ctx;
        VariantPlanner planner;

        void refresh() {
            obstacles = new ObstacleIndex(input);
            double totalFlow = input.oksPoints.stream().mapToDouble(p -> p.flowTph).sum();
            ctx = obstacles.forGrade(PipeTable.byDu(Math.min(400, PipeTable.minGradeForFlow(totalFlow).getDu())));
            planner = new VariantPlanner(input, obstacles);
        }

        EdgeValidator.EdgeCheck check(Coordinate a, Coordinate b, Set<Object> exempt, Object own) {
            return new EdgeValidator(ctx).check(a, b, exempt, own);
        }
    }
}
