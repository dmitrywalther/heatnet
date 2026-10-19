package ru.lct.heatnet.service;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.network.NetworkPlan;
import ru.lct.heatnet.network.PlanEdge;
import ru.lct.heatnet.routing.GeoUtil;
import ru.lct.heatnet.routing.ObstacleIndex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariantPlannerTargetPruningTest {

    @Test
    void veryCloseFarWallTargetDoesNotShrinkOtherWallsSearchRadius() throws Exception {
        InputModel input = new InputModel();
        restriction(input, "own", "oks", "POLYGON ((0 0,400 0,400 40,0 40,0 0))");
        // Ближние камеры находятся в закрытом дворе препятствия: геометрически
        // допустимы сами точки, но из внешней области к ним не пройти.
        restriction(input, "enclosure", "park",
                "POLYGON ((-120 -20,-80 -20,-80 60,-120 60,-120 -20),"
                        + "(-115 -15,-115 55,-85 55,-85 -15,-115 -15))");
        for (int i = 0; i < 42; i++) {
            chamber(input, "enclosed-" + i, -100, i);
        }
        chamber(input, "useful-left", -200, 20);
        // Рядом с дальней стеной есть доступная камера. Её маленькое расстояние
        // не должно исключать полезную цель другой стены из списка поиска.
        chamber(input, "expensive-right", 408, 20);
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
        assertEquals("useful-left", edge.a.inputId,
                "each wall needs its own distance cutoff; the global minimum hides a useful target");
        assertTrue(edge.length() < 300,
                "the exterior detour is shorter than the approximately 398 m far-wall route");
        assertEquals(0, edge.coords.get(edge.coords.size() - 1).distance(oks.utm.getCoordinate()), 1e-9);
    }

    private static void restriction(InputModel input, String id, String type, String wkt) throws Exception {
        InputModel.Restriction r = new InputModel.Restriction();
        r.id = id;
        r.restrictionType = type;
        r.utm = new WKTReader(GeoUtil.GF).read(wkt);
        input.restrictions.add(r);
    }

    private static void chamber(InputModel input, String id, double x, double y) {
        InputModel.ExistingChamber c = new InputModel.ExistingChamber();
        c.id = id;
        c.utm = GeoUtil.GF.createPoint(new Coordinate(x, y));
        input.chambers.add(c);
    }
}
