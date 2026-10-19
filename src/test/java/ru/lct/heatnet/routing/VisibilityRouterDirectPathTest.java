package ru.lct.heatnet.routing;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import ru.lct.heatnet.refdata.CostModel;
import ru.lct.heatnet.refdata.PipeTable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VisibilityRouterDirectPathTest {
    @Test
    void directAndSearchedPathUseTheSameUnroundedScoreIncludingSpecialIntervalsAndTieCost() {
        GradeContext ctx = new GradeContext(PipeTable.byDu(100));
        Geometry core = GeoUtil.segment(new Coordinate(5, -100), new Coordinate(5, 100));
        Geometry proximity = core.buffer(1.0);
        ctx.specials.add(new SpecialObstacle("crossing", "test", 3.0, 80.0, 2.0, false,
                core, PreparedGeometryFactory.prepare(core), proximity,
                PreparedGeometryFactory.prepare(proximity), null, null, null));
        ctx.build();
        EdgeValidator validator = new EdgeValidator(ctx);
        Coordinate p = new Coordinate(10, 0);
        TieCandidate tie = new TieCandidate(TieCandidate.Kind.NEW_NETWORK_EDGE_POINT,
                new Coordinate(0, 0), "trunk", 3_000_000, null);
        EdgeValidator.EdgeCheck check = validator.check(tie.location, p, null, null, tie.location);
        assertTrue(check.valid);
        VisibilityRouter router = new VisibilityRouter(ctx, validator, 89_748);

        VisibilityRouter.RoutedPath direct = router.directPath(p, tie, check);
        VisibilityRouter.RoutedPath searched = router.route(List.of(p), new double[]{0}, null, List.of(tie));

        assertNotNull(searched);
        assertEquals(searched.scoreCost, direct.scoreCost, 1e-12);
        double cost = (10 + 4 * (3 - 1)) * 89_748 + 3_000_000;
        assertEquals(CostModel.score(cost, 10), direct.scoreCost, 1e-12);
        assertEquals(List.of(tie.location, p), direct.coords);
        assertThrows(IllegalArgumentException.class, () ->
                router.directPath(p, tie, EdgeValidator.EdgeCheck.invalid()));
    }
}
