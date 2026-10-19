package ru.lct.heatnet.service;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.network.NetworkPlan;
import ru.lct.heatnet.network.PlanEdge;
import ru.lct.heatnet.routing.GeoUtil;
import ru.lct.heatnet.routing.ObstacleIndex;
import ru.lct.heatnet.routing.EdgeValidator;
import ru.lct.heatnet.routing.GradeContext;
import ru.lct.heatnet.routing.OksApproach;
import ru.lct.heatnet.refdata.PipeTable;

import static org.junit.jupiter.api.Assertions.*;

class VariantPlannerOksEntryTest {

    @Test
    void triesLongerNormalWhenTheShortestValidNormalIsUnreachable() throws Exception {
        InputModel input = new InputModel();
        restriction(input, "own", "oks", "POLYGON ((0 0,100 0,100 40,0 40,0 0))");
        // Короткий нормальный хвост свободен, но его наружный конец заперт
        // в U-кармане, который замыкается буфером собственного здания.
        restriction(input, "pocket", "park",
                "POLYGON ((-10 14,-10 26,-2 26,-2 25,-9 25,-9 15,-2 15,-2 14,-10 14))");
        chamber(input, "network", 120, 20);
        InputModel.OksPoint oks = target(input, 5, 20);
        ObstacleIndex obstacles = new ObstacleIndex(input);
        GradeContext ctx = obstacles.forGrade(PipeTable.byDu(100));
        OksApproach entries = OksApproach.build(oks, ctx, new EdgeValidator(ctx));
        assertNotNull(entries);
        assertTrue(entries.approachNodes.stream().anyMatch(a -> a.x < 0 && Math.abs(a.y - 20) < 1e-6));

        NetworkPlan plan = new VariantPlanner(input, obstacles).plan(VariantPlanner.Strategy.SHARED, null);
        assertTrue(plan.unconnected.isEmpty());
        PlanEdge edge = plan.edges.get(0);
        Coordinate selected = edge.coords.get(edge.coords.size() - 2);
        assertEquals(20, entries.internalLength(selected), 1e-6,
                "unreachable 5 m entry must yield to a reachable 20 m normal, not disconnect the OKS");
        assertEquals(5, selected.x, 1e-6);
    }

    @Test
    void comparesRouteCostBetweenEquallyShortWalls() throws Exception {
        InputModel input = new InputModel();
        restriction(input, "own", "oks", "POLYGON ((0 0,20 0,20 20,0 20,0 0))");
        chamber(input, "network", 40, 10);
        target(input, 10, 10);
        NetworkPlan plan = new VariantPlanner(input, new ObstacleIndex(input))
                .plan(VariantPlanner.Strategy.SHARED, null);
        assertTrue(plan.unconnected.isEmpty());
        PlanEdge edge = plan.edges.get(0);
        assertEquals(30, edge.length(), 1e-6, "all four walls are 10 m away; use the direct exterior route");
        assertTrue(edge.coords.get(edge.coords.size() - 2).x > 20);
    }

    @Test
    void prefersShortInteriorEntryOverCheaperTransitAcrossTheBuilding() throws Exception {
        InputModel input = new InputModel();
        restriction(input, "own", "oks", "POLYGON ((0 0,100 0,100 40,0 40,0 0))");
        chamber(input, "network", 120, 20);
        InputModel.OksPoint oks = new InputModel.OksPoint();
        oks.id = "target";
        oks.flowTph = 20;
        oks.utm = GeoUtil.GF.createPoint(new Coordinate(5, 20));
        input.oksPoints.add(oks);

        NetworkPlan plan = new VariantPlanner(input, new ObstacleIndex(input))
                .plan(VariantPlanner.Strategy.SHARED, null);
        assertTrue(plan.unconnected.isEmpty());
        PlanEdge edge = plan.edges.get(0);
        Coordinate approach = edge.coords.get(edge.coords.size() - 2);
        assertTrue(approach.x < 0,
                "a reachable 5 m entry at the left wall must precede a 95 m interior shortcut");
        assertEquals(20, approach.y, 1e-6);
        assertEquals(0, edge.coords.get(edge.coords.size() - 1).distance(oks.utm.getCoordinate()), 1e-9);
    }

    @Test
    void usesObliqueEntryWhenValidPerpendicularApproachIsInAnUnreachablePocket() throws Exception {
        InputModel input = new InputModel();
        restriction(input, "own", "oks", "POLYGON ((0 0,20 0,20 20,0 20,0 0))");
        // Единственная свободная нормаль ведёт в карман между буфером здания и U-преградой.
        restriction(input, "u", "park", "POLYGON ((7 20,7 31,13 31,13 20,12 20,12 30,8 30,8 20,7 20))");
        restriction(input, "west", "park", "POLYGON ((-10 9,-4 9,-4 11,-10 11,-10 9))");
        restriction(input, "east", "park", "POLYGON ((24 9,30 9,30 11,24 11,24 9))");
        restriction(input, "south", "park", "POLYGON ((9 -10,11 -10,11 -4,9 -4,9 -10))");
        chamber(input, "network", 40, 40);
        InputModel.OksPoint oks = new InputModel.OksPoint();
        oks.id = "target";
        oks.flowTph = 20;
        oks.utm = GeoUtil.GF.createPoint(new Coordinate(10, 10));
        input.oksPoints.add(oks);
        ObstacleIndex obstacles = new ObstacleIndex(input);
        GradeContext ctx = obstacles.forGrade(PipeTable.byDu(100));
        OksApproach approaches = OksApproach.build(oks, ctx, new EdgeValidator(ctx));
        assertNotNull(approaches);
        assertEquals(1, approaches.approachNodes.size(), "one geometrically valid normal remains");
        assertTrue(approaches.approachNodes.get(0).y > 20);
        assertFalse(approaches.fallbackApproachNodes.isEmpty());

        NetworkPlan plan = new VariantPlanner(input, obstacles).plan(VariantPlanner.Strategy.SHARED, null);
        assertTrue(plan.unconnected.isEmpty(), "unreachable normal must not disconnect an otherwise accessible OKS");
        PlanEdge edge = plan.edges.get(0);
        Coordinate before = edge.coords.get(edge.coords.size() - 2);
        assertTrue(Math.abs(before.x - 10) > 1 && Math.abs(before.y - 10) > 1,
                "the reachable entry is oblique");
        assertTrue(new EdgeValidator(ctx).check(before, oks.utm.getCoordinate(), null, "own").valid);
    }

    @Test
    void canReachFarSideNetworkWhileKeepingNearWallEntry() throws Exception {
        InputModel input = new InputModel();
        restriction(input, "own", "oks", "POLYGON ((0 0,400 0,400 40,0 40,0 0))");
        // Близкие к ближайшей стене камеры доступны только в обход длинного препятствия.
        restriction(input, "barrier", "park", "POLYGON ((-15 -500,-10 -500,-10 500,-15 500,-15 -500))");
        for (int i = 0; i < 42; i++) {
            chamber(input, "left-" + i, -25, i);
        }
        chamber(input, "right", 420, 20);
        InputModel.OksPoint oks = new InputModel.OksPoint();
        oks.id = "target";
        oks.flowTph = 20;
        oks.utm = GeoUtil.GF.createPoint(new Coordinate(10, 20));
        input.oksPoints.add(oks);

        NetworkPlan plan = new VariantPlanner(input, new ObstacleIndex(input))
                .plan(VariantPlanner.Strategy.SHARED, null);
        assertTrue(plan.unconnected.isEmpty());
        assertEquals(1, plan.edges.size());
        PlanEdge edge = plan.edges.get(0);
        assertEquals("right", edge.a.inputId,
                "a feasible long route to the nearest-side network must not hide the better far-side target");
        assertTrue(edge.length() > 410, "the route goes around the building instead of taking the interior shortcut");
        Coordinate approach = edge.coords.get(edge.coords.size() - 2);
        assertTrue(approach.x < 0, "the reachable nearest wall has a 10 m interior entry");
        assertEquals(20, approach.y, 1e-6);
        assertEquals(0, edge.coords.get(edge.coords.size() - 1).distance(oks.utm.getCoordinate()), 1e-9);
    }

    private static void restriction(InputModel input, String id, String type, String wkt) throws Exception {
        InputModel.Restriction r = new InputModel.Restriction();
        r.id = id;
        r.restrictionType = type;
        r.utm = new WKTReader(GeoUtil.GF).read(wkt);
        input.restrictions.add(r);
    }

    private static InputModel.OksPoint target(InputModel input, double x, double y) {
        InputModel.OksPoint oks = new InputModel.OksPoint();
        oks.id = "target";
        oks.flowTph = 20;
        oks.utm = GeoUtil.GF.createPoint(new Coordinate(x, y));
        input.oksPoints.add(oks);
        return oks;
    }

    private static void chamber(InputModel input, String id, double x, double y) {
        InputModel.ExistingChamber c = new InputModel.ExistingChamber();
        c.id = id;
        c.utm = GeoUtil.GF.createPoint(new Coordinate(x, y));
        input.chambers.add(c);
    }
}
