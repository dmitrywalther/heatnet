package ru.lct.heatnet.service;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.network.NetworkPlan;
import ru.lct.heatnet.network.PlanNode;
import ru.lct.heatnet.refdata.PipeGrade;
import ru.lct.heatnet.refdata.PipeTable;
import ru.lct.heatnet.routing.EdgeValidator;
import ru.lct.heatnet.routing.GeoUtil;
import ru.lct.heatnet.routing.GradeContext;
import ru.lct.heatnet.routing.ObstacleIndex;
import ru.lct.heatnet.routing.TieCandidate;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class VariantPlannerSourceChamberDistanceTest {
    @Test
    void availableCameraSuppressesNewPipePointsThroughInclusiveTenMeters() throws Exception {
        for (String type : List.of("road", "tram_tracks")) {
            Fixture f = fixture(type, false);
            List<TieCandidate> candidates = candidates(f);

            assertTrue(hasExisting(candidates), type + ": the source camera has an available port");
            assertTrue(f.input.restrictions.get(0).utm.contains(
                    GeoUtil.GF.createPoint(new Coordinate(10, 0))));
            assertFalse(hasPipePoint(candidates, 9.9), type + ": a closer point must reuse the camera");
            assertFalse(hasPipePoint(candidates, 10.0), type + ": 'not farther than 10 m' includes 10 m");
            assertTrue(hasPipePoint(candidates, 10.1), type + ": a distinct legal point beyond 10 m remains a candidate");
        }
    }

    @Test
    void threeOccupiedPortsStillRequireExistingCameraButFourAllowAnotherSourcePoint() throws Exception {
        for (String type : List.of("road", "tram_tracks")) {
            Fixture f = fixture(type, true);
            PlanNode camera = f.plan.addNode(new PlanNode(PlanNode.Kind.EXISTING_CHAMBER,
                    new Coordinate(0, 0)));
            camera.inputId = "camera";
            addBranch(f.plan, camera, new Coordinate(0, 30));
            assertEquals(1, f.plan.degree(camera), "two source directions plus one planned branch = three ports");

            List<TieCandidate> withThreePorts = candidates(f);
            assertTrue(hasExisting(withThreePorts), type + ": the fourth port is still available");
            assertFalse(hasPipePoint(withThreePorts, 10), type + ": an available fourth port keeps the inclusive 10 m rule");

            addBranch(f.plan, camera, new Coordinate(0, -30));
            assertEquals(2, f.plan.degree(camera), "two source directions plus two planned branches = four ports");
            List<TieCandidate> withFourPorts = candidates(f);
            assertFalse(hasExisting(withFourPorts), type + ": a fifth connection is forbidden");
            assertTrue(hasPipePoint(withFourPorts, 10), type + ": the full camera does not block a distinct source point");
        }
    }

    @Test
    void refinementCannotMoveAnEligibleSampleIntoTheExistingCamerasTenMeterRadius() throws Exception {
        // No road here: its historical endpoint rejection would mask this independent
        // distance-rule failure before the source-chamber polygon permission is fixed.
        Fixture f = fixture(null, false);
        TieCandidate original = tie(20);
        List<Coordinate> coords = new ArrayList<>(List.of(original.location, new Coordinate(6, 30)));
        EdgeValidator validator = new EdgeValidator(f.context);
        assertTrue(validator.check(coords.get(1), new Coordinate(6, 0),
                original.exemptSpecialIds, null, new Coordinate(6, 0)).valid,
                "geometry genuinely allows the perpendicular foot; only the 10 m rule forbids moving the tie");

        TieCandidate refined = refine(f, original, coords, validator);

        assertSame(original, refined, "keep the valid old sample instead of bypassing an available source camera");
        assertEquals(0, coords.get(0).distance(new Coordinate(20, 0)), 1e-9,
                "a rejected refinement must not mutate the route's source endpoint");
    }

    @Test
    void refinementBeyondTenMetersStillMovesToTheValidPerpendicularFoot() throws Exception {
        Fixture f = fixture(null, false);
        TieCandidate original = tie(20);
        List<Coordinate> coords = new ArrayList<>(List.of(original.location, new Coordinate(12, 30)));

        TieCandidate refined = refine(f, original, coords, new EdgeValidator(f.context));

        assertNotSame(original, refined, "the radius guard must not disable valid source-point refinement");
        assertEquals(TieCandidate.Kind.PIPE_POINT, refined.kind);
        assertEquals(0, refined.location.distance(new Coordinate(12, 0)), 1e-9);
        assertEquals(0, coords.get(0).distance(refined.location), 1e-9);
        assertEquals(original.terminalCostRub, refined.terminalCostRub, 0);
    }

    private static Fixture fixture(String restrictionType, boolean throughCamera) throws Exception {
        InputModel input = new InputModel();
        InputModel.ExistingPipe pipe = new InputModel.ExistingPipe();
        pipe.id = "source";
        pipe.diameter = 300;
        // Explicit collinear vertices ensure 9.9/10/10.1 are real candidates,
        // independent of the regular ten-metre sampling interval.
        List<Coordinate> points = new ArrayList<>();
        if (throughCamera) points.add(new Coordinate(-40, 0));
        points.addAll(List.of(new Coordinate(0, 0), new Coordinate(9.9, 0),
                new Coordinate(10, 0), new Coordinate(10.1, 0), new Coordinate(40, 0)));
        pipe.utm = GeoUtil.polyline(points);
        input.pipes.add(pipe);

        InputModel.ExistingChamber chamber = new InputModel.ExistingChamber();
        chamber.id = "camera";
        chamber.utm = GeoUtil.GF.createPoint(new Coordinate(0, 0));
        input.chambers.add(chamber);

        if (restrictionType != null) {
            InputModel.Restriction restriction = new InputModel.Restriction();
            restriction.id = "street";
            restriction.restrictionType = restrictionType;
            restriction.utm = new WKTReader(GeoUtil.GF).read(
                    "POLYGON ((-50 -10,50 -10,50 10,-50 10,-50 -10))");
            input.restrictions.add(restriction);
        }

        ObstacleIndex obstacles = new ObstacleIndex(input);
        return new Fixture(input, new NetworkPlan(), new VariantPlanner(input, obstacles),
                obstacles.forGrade(PipeTable.byDu(100)));
    }

    private static void addBranch(NetworkPlan plan, PlanNode source, Coordinate end) {
        PlanNode target = plan.addNode(new PlanNode(PlanNode.Kind.OKS, end));
        plan.addEdge(source, target, List.of(source.utm, target.utm));
    }

    private static TieCandidate tie(double x) {
        return new TieCandidate(TieCandidate.Kind.PIPE_POINT, new Coordinate(x, 0),
                "source", 5_000_000, Set.of("source"));
    }

    @SuppressWarnings("unchecked")
    private static List<TieCandidate> candidates(Fixture f) throws Exception {
        Method method = VariantPlanner.class.getDeclaredMethod("buildCandidates", NetworkPlan.class,
                GradeContext.class, PipeGrade.class, VariantPlanner.Strategy.class, Set.class);
        method.setAccessible(true);
        return (List<TieCandidate>) method.invoke(f.planner, f.plan, f.context,
                PipeTable.byDu(100), VariantPlanner.Strategy.SHARED, Set.of());
    }

    private static TieCandidate refine(Fixture f, TieCandidate tie, List<Coordinate> coords,
                                        EdgeValidator validator) throws Exception {
        Method method = VariantPlanner.class.getDeclaredMethod("refinePipeTie", NetworkPlan.class,
                TieCandidate.class, List.class, EdgeValidator.class);
        method.setAccessible(true);
        return (TieCandidate) method.invoke(f.planner, f.plan, tie, coords, validator);
    }

    private static boolean hasExisting(List<TieCandidate> candidates) {
        return candidates.stream().anyMatch(t -> t.kind == TieCandidate.Kind.EXISTING_CHAMBER
                && "camera".equals(t.refId));
    }

    private static boolean hasPipePoint(List<TieCandidate> candidates, double x) {
        return candidates.stream().anyMatch(t -> t.kind == TieCandidate.Kind.PIPE_POINT
                && "source".equals(t.refId) && t.location.distance(new Coordinate(x, 0)) < 1e-8);
    }

    private static final class Fixture {
        final InputModel input;
        final NetworkPlan plan;
        final VariantPlanner planner;
        final GradeContext context;

        Fixture(InputModel input, NetworkPlan plan, VariantPlanner planner, GradeContext context) {
            this.input = input;
            this.plan = plan;
            this.planner = planner;
            this.context = context;
        }
    }
}
