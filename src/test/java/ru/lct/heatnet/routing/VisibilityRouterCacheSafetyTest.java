package ru.lct.heatnet.routing;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import ru.lct.heatnet.refdata.PipeTable;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class VisibilityRouterCacheSafetyTest {
    @Test
    void eachUndirectedPairIsValidatedOnceWhileRoutingAroundARealObstacle() {
        GradeContext ctx = new GradeContext(PipeTable.byDu(100));
        Geometry core = GeoUtil.GF.createPolygon(new Coordinate[]{
                new Coordinate(40, -10), new Coordinate(60, -10),
                new Coordinate(60, 10), new Coordinate(40, 10), new Coordinate(40, -10)});
        ctx.forbidden.add(new ForbiddenZone("building", "building", core,
                PreparedGeometryFactory.prepare(core), core.buffer(2)));
        ctx.build();
        Set<Long> pairs = new HashSet<>();
        Map<Coordinate, Integer> nodeIds = new IdentityHashMap<>();
        EdgeValidator validator = new EdgeValidator(ctx) {
            @Override
            public EdgeCheck check(Coordinate a, Coordinate b, Set<Object> exempt,
                                   Object skipRestrictionId, Coordinate allowTouchAt) {
                // Node identity distinguishes duplicate ring-closing coordinates.
                int u = nodeIds.computeIfAbsent(a, ignored -> nodeIds.size());
                int v = nodeIds.computeIfAbsent(b, ignored -> nodeIds.size());
                long pair = ((long) Math.min(u, v) << 32) | Math.max(u, v);
                assertTrue(pairs.add(pair), "An undirected node pair was validated twice");
                return super.check(a, b, exempt, skipRestrictionId, allowTouchAt);
            }
        };
        Coordinate start = new Coordinate(0, 0);
        TieCandidate tie = tie(100, 0);
        VisibilityRouter.RoutedPath result = new VisibilityRouter(ctx, validator, 89_748)
                .route(List.of(start, new Coordinate(0, 1)), new double[]{0, 20}, null, List.of(tie));
        assertNotNull(result);
        assertTrue(result.coords.size() > 2, "The building must force a detour");
        assertEquals(tie.location, result.coords.get(0));
        assertEquals(start, result.coords.get(result.coords.size() - 1));
        assertTrue(pairs.size() > 10);
    }

    @Test
    void warmStaticCacheDoesNotBypassAnAddedNetworkBarrier() {
        GradeContext ctx = new GradeContext(PipeTable.byDu(100));
        ctx.build();
        EdgeValidator validator = new EdgeValidator(ctx);
        VisibilityRouter router = new VisibilityRouter(ctx, validator, 89_748);
        List<Coordinate> starts = List.of(new Coordinate(0, 0));
        List<TieCandidate> targets = List.of(tie(100, 0));
        VisibilityRouter.RoutedPath first = router.route(starts, new double[]{0}, null, targets);
        assertNotNull(first);
        assertFalse(ctx.staticEdgeCache.isEmpty(), "The shared static cache must still be populated");
        assertEquals(first.scoreCost, router.route(starts, new double[]{0}, null, targets).scoreCost);
        validator.addBarrier(GeoUtil.segment(new Coordinate(50, -100), new Coordinate(50, 100)));
        assertNull(router.route(starts, new double[]{0}, null, targets));
    }

    @Test
    void cappedSearchStillFallsBackToTheFullDirectEdge() {
        GradeContext ctx = new GradeContext(PipeTable.byDu(100));
        ctx.build();
        VisibilityRouter router = new VisibilityRouter(ctx, new EdgeValidator(ctx), 89_748, 0, 25);
        Coordinate start = new Coordinate(0, 0);
        TieCandidate tie = tie(100, 0);
        VisibilityRouter.RoutedPath result = router.route(List.of(start), new double[]{0}, null, List.of(tie));
        assertNotNull(result);
        assertEquals(List.of(tie.location, start), result.coords);
    }

    private static TieCandidate tie(double x, double y) {
        return new TieCandidate(TieCandidate.Kind.NEW_NETWORK_EDGE_POINT,
                new Coordinate(x, y), "trunk", 3_000_000, null);
    }
}
