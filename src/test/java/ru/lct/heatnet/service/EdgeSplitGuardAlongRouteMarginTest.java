package ru.lct.heatnet.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.network.NetworkPlan;
import ru.lct.heatnet.network.PlanEdge;
import ru.lct.heatnet.network.PlanNode;
import ru.lct.heatnet.refdata.PipeTable;
import ru.lct.heatnet.routing.GeoUtil;
import ru.lct.heatnet.routing.GradeContext;
import ru.lct.heatnet.routing.ObstacleIndex;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EdgeSplitGuardAlongRouteMarginTest {
    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void cutAtThreeMetresIsAllowedButEarlierCutCannotTurnOrBranch(String type) {
        InputModel input = new InputModel();
        InputModel.Restriction restriction = new InputModel.Restriction();
        restriction.id = "street";
        restriction.restrictionType = type;
        restriction.utm = GeoUtil.GF.createPolygon(new Coordinate[]{c(-5, -50), c(5, -50),
                c(5, 50), c(-5, 50), c(-5, -50)});
        input.restrictions.add(restriction);
        GradeContext ctx = new ObstacleIndex(input).forGrade(PipeTable.byDu(50));
        for (boolean reverse : new boolean[]{false, true}) {
            NetworkPlan plan = new NetworkPlan();
            PlanNode a = plan.addNode(new PlanNode(PlanNode.Kind.EXISTING_CHAMBER, c(-20, -20)));
            PlanNode b = plan.addNode(new PlanNode(PlanNode.Kind.OKS, c(30, 30)));
            PlanEdge edge = reverse ? plan.addEdge(b, a, List.of(b.utm, a.utm))
                    : plan.addEdge(a, b, List.of(a.utm, b.utm));
            for (double sign : new double[]{-1, 1}) {
                double boundary = sign * (5 + 3 / Math.sqrt(2));
                double inside = sign * (5 + 2.999 / Math.sqrt(2));
                assertTrue(EdgeSplitGuard.allows(edge, c(boundary, boundary), ctx));
                assertFalse(EdgeSplitGuard.allows(edge, c(inside, inside), ctx));
            }
        }
    }

    private static Coordinate c(double x, double y) { return new Coordinate(x, y); }
}
