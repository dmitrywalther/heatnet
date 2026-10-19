package ru.lct.heatnet.routing;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.network.PlanNode;
import ru.lct.heatnet.refdata.CostModel;
import ru.lct.heatnet.refdata.PipeTable;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class VisibilityRouterSourceChamberTest {
    @Test
    void searchedSourceTargetRetainsTheSpecialIntervalPriceAndSeparateTieCost() throws Exception {
        for (String type : List.of("road", "tram_tracks")) {
            GradeContext context = context(type);
            double unitPrice = PipeTable.byDu(100).getCostPerMeter();
            double coefficient = "road".equals(type) ? 1.60 : 1.75;
            Coordinate target = new Coordinate(30, 0);
            VisibilityRouter router = new VisibilityRouter(context, new EdgeValidator(context), unitPrice);
            for (TieCandidate.Kind kind : List.of(TieCandidate.Kind.EXISTING_CHAMBER, TieCandidate.Kind.PIPE_POINT)) {
                TieCandidate tie = new TieCandidate(kind, new Coordinate(0, 0), "source-pipe",
                        5_000_000, Set.of("source-pipe"));

                VisibilityRouter.RoutedPath path = router.route(List.of(target), new double[]{0}, null, List.of(tie));

                assertNotNull(path, type + ": a source target inside the polygon has a legal straight exit");
                assertSame(tie, path.tie);
                assertEquals(List.of(tie.location, target), path.coords);
                assertEquals(CostModel.score((30 + 13 * (coefficient - 1)) * unitPrice + 5_000_000, 30),
                        path.scoreCost, 1e-12, "13 m special from source through boundary+3 m; tie cost is separate");
            }
        }
    }

    @Test
    void ordinaryNewJunctionAtTheSameCoordinatesDoesNotInheritASourceTargetsPermission() throws Exception {
        GradeContext context = context("road");
        VisibilityRouter router = new VisibilityRouter(context, new EdgeValidator(context),
                PipeTable.byDu(100).getCostPerMeter());
        Coordinate target = new Coordinate(30, 0);
        PlanNode ordinaryNode = new PlanNode(PlanNode.Kind.NEW_CHAMBER, new Coordinate(0, 0));
        TieCandidate ordinary = new TieCandidate(TieCandidate.Kind.NEW_NETWORK_NODE, ordinaryNode.utm,
                ordinaryNode, 0, Set.of("source-pipe"));
        assertNull(router.route(List.of(target), new double[]{0}, null, List.of(ordinary)));

        PlanNode sourceNode = new PlanNode(PlanNode.Kind.NEW_CHAMBER, new Coordinate(0, 0));
        sourceNode.splitPipeDu = 300;
        TieCandidate source = new TieCandidate(TieCandidate.Kind.NEW_NETWORK_NODE, sourceNode.utm,
                sourceNode, 0, Set.of("source-pipe"));
        assertNotNull(router.route(List.of(target), new double[]{0}, null, List.of(source)));
        assertNull(router.route(List.of(target), new double[]{0}, null, List.of(ordinary)),
                "a prior permitted target cannot make the same coordinates an ordinary admissible junction");
    }

    private static GradeContext context(String type) throws Exception {
        InputModel input = new InputModel();
        InputModel.ExistingPipe pipe = new InputModel.ExistingPipe();
        pipe.id = "source-pipe";
        pipe.diameter = 300;
        pipe.utm = GeoUtil.segment(new Coordinate(0, -30), new Coordinate(0, 30));
        input.pipes.add(pipe);
        InputModel.Restriction restriction = new InputModel.Restriction();
        restriction.id = "street";
        restriction.restrictionType = type;
        restriction.utm = new WKTReader(GeoUtil.GF).read(
                "POLYGON ((-10 -100,10 -100,10 100,-10 100,-10 -100))");
        input.restrictions.add(restriction);
        return new ObstacleIndex(input).forGrade(PipeTable.byDu(100));
    }
}
