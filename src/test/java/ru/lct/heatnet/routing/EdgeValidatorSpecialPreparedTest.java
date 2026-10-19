package ru.lct.heatnet.routing;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatnet.refdata.PipeTable;
import ru.lct.heatnet.routing.GeoUtil.SpecialInterval;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class EdgeValidatorSpecialPreparedTest {
    private static final Set<Object> NO_MATCHING_EXEMPTION = Set.of("unrelated-object");
    private static final String RECTANGLE = "POLYGON ((0 0,10 0,10 10,0 10,0 0))";
    private static final String CONCAVE =
            "POLYGON ((0 0,30 0,30 30,20 30,20 10,10 10,10 30,0 30,0 0))";

    @Test
    void fullChecksMatchRawPredicatesAtCrossingsTouchesHolesAndMultipleComponents() throws Exception {
        String[] cores = {
                RECTANGLE, CONCAVE,
                "POLYGON ((0 0,30 0,30 30,0 30,0 0),(10 10,10 20,20 20,20 10,10 10))",
                "MULTIPOLYGON (((0 0,10 0,10 10,0 10,0 0)),((20 0,30 0,30 10,20 10,20 0)))",
                "LINESTRING (0 0,10 10,10 30)",
                "MULTILINESTRING ((0 0,10 10),(20 0,20 30))"
        };
        List<Coordinate[]> edges = new ArrayList<>();
        for (double offset : new double[]{-3.5, -1e-8, 0, 5, 10, 10 + 1e-8, 15, 20, 30, 33.5}) {
            edges.add(edge(-10, offset, 40, offset));
            edges.add(edge(offset, -10, offset, 40));
        }
        edges.add(edge(-10, -10, 40, 40));
        edges.add(edge(-10, 22, 40, 39));
        edges.add(edge(-10, 5, 5, 5)); // endpoint inside special zone
        edges.add(edge(-10, 5, -1, 5)); // endpoint on special-zone boundary
        edges.add(edge(-10, -10, 0, 0)); // core vertex contact
        edges.add(edge(-10, -3.5, -3.5, -3.5)); // proximity corner contact

        for (String wkt : cores) {
            for (Double minAngle : new Double[]{null, 70.0}) {
                EdgeValidator prepared = validator(wkt, minAngle, false);
                EdgeValidator legacy = validator(wkt, minAngle, true);
                for (Coordinate[] edge : edges) {
                    compare(legacy, prepared, edge[0], edge[1], NO_MATCHING_EXEMPTION);
                    compare(legacy, prepared, edge[1], edge[0], NO_MATCHING_EXEMPTION);
                    compare(legacy, prepared, edge[0], edge[1], Set.of("special"));
                }
            }
        }
    }

    @Test
    void eachDisconnectedProximityPortionMustReachTheCore() throws Exception {
        Geometry core = read(CONCAVE);
        Geometry segment = GeoUtil.segment(new Coordinate(-10, 22), new Coordinate(40, 39));
        Geometry portions = segment.intersection(ObstacleIndex.buffer(core, 3.5));
        int reachingCore = 0;
        int missingCore = 0;
        for (int i = 0; i < portions.getNumGeometries(); i++) {
            Geometry portion = portions.getGeometryN(i);
            if (portion.getDimension() >= 1) {
                if (portion.intersects(core)) reachingCore++;
                else missingCore++;
            }
        }
        assertTrue(segment.intersects(core));
        assertTrue(reachingCore > 0 && missingCore > 0, "Fixture must contain both kinds of portion");
        assertFalse(validator(CONCAVE, null, false, 3.5).check(
                new Coordinate(-10, 22), new Coordinate(40, 39), NO_MATCHING_EXEMPTION, null).valid);
    }

    @Test
    void properRoadCrossingRetainsExactSpecialIntervalAndAngleRejection() throws Exception {
        EdgeValidator validator = validator(RECTANGLE, 70.0, false);
        EdgeValidator.EdgeCheck crossing = validator.check(
                new Coordinate(-10, 5), new Coordinate(20, 5), NO_MATCHING_EXEMPTION, null);
        assertTrue(crossing.valid);
        assertEquals(30.0, crossing.length);
        assertEquals(1, crossing.rawSpecials.size());
        assertEquals(9.0, crossing.rawSpecials.get(0).from);
        assertEquals(21.0, crossing.rawSpecials.get(0).to);
        assertEquals(2.5, crossing.rawSpecials.get(0).kSpec);
        assertFalse(validator.check(new Coordinate(-10, -10), new Coordinate(20, 20),
                NO_MATCHING_EXEMPTION, null).valid);
        assertTrue(validator.check(new Coordinate(-10, -10), new Coordinate(20, 20),
                Set.of("special"), null).valid);
    }

    @Test
    void detailedRoadChecksStayWithinAllocationBudget() throws Exception {
        java.lang.management.ThreadMXBean management = ManagementFactory.getThreadMXBean();
        assumeTrue(management instanceof ThreadMXBean, "Requires HotSpot allocation counters");
        ThreadMXBean allocation = (ThreadMXBean) management;
        assumeTrue(allocation.isThreadAllocatedMemorySupported()
                && allocation.isThreadAllocatedMemoryEnabled(), "Thread allocation counters unavailable");

        Coordinate[] corners = {new Coordinate(0, 0), new Coordinate(10, 0),
                new Coordinate(10, 100), new Coordinate(0, 100), new Coordinate(0, 0)};
        Coordinate[] ring = new Coordinate[2049];
        for (int side = 0; side < 4; side++) {
            for (int i = 0; i < 512; i++) {
                double fraction = i / 512.0;
                ring[side * 512 + i] = new Coordinate(
                        corners[side].x + fraction * (corners[side + 1].x - corners[side].x),
                        corners[side].y + fraction * (corners[side + 1].y - corners[side].y));
            }
        }
        ring[2048] = new Coordinate(ring[0]);
        Polygon core = GeoUtil.GF.createPolygon(ring);
        assertEquals(2049, core.getNumPoints());
        assertFalse(core.isRectangle(), "Do not accidentally use JTS's five-point rectangle fast path");
        GradeContext ctx = new GradeContext(PipeTable.byDu(100));
        // Keep clipping geometries simple to isolate repeated work on the detailed core.
        // Keep proximity inside the declared one-metre axial SPECIAL window.
        // The detailed 2049-position core remains the allocation workload.
        Geometry proximity = read("POLYGON ((-0.5 -0.5,10.5 -0.5,10.5 100.5,-0.5 100.5,-0.5 -0.5))");
        Geometry bound = read("POLYGON ((-1 -1,11 -1,11 101,-1 101,-1 -1))");
        ctx.specials.add(special(core, proximity, bound, null, false));
        ctx.build();
        EdgeValidator validator = new EdgeValidator(ctx);
        Coordinate a = new Coordinate(-5, 50.125);
        Coordinate b = new Coordinate(15, 50.125);
        for (int i = 0; i < 32; i++) {
            assertTrue(validator.check(a, b, NO_MATCHING_EXEMPTION, null).valid);
        }
        long thread = Thread.currentThread().getId();
        int valid = 0;
        int rawIntervals = 0;
        long before = allocation.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 32; i++) {
            EdgeValidator.EdgeCheck result = validator.check(a, b, NO_MATCHING_EXEMPTION, null);
            if (result.valid) valid++;
            rawIntervals += result.rawSpecials.size();
        }
        long allocated = allocation.getThreadAllocatedBytes(thread) - before;
        assertEquals(32, valid);
        assertEquals(32, rawIntervals);
        assertTrue(ctx.staticEdgeCache.isEmpty(), "Repeated edges must not bypass the actual JTS work");
        System.out.println("Detailed road allocation guard: " + allocated + " bytes for 32 checks");
        assertTrue(allocated >= 0 && allocated < 4L * 1024 * 1024,
                "Detailed road checks allocated " + allocated + " bytes, budget 4 MiB");
    }

    private static EdgeValidator validator(String wkt, Double minAngle, boolean legacy) throws Exception {
        return validator(wkt, minAngle, legacy, 0.5);
    }

    private static EdgeValidator validator(String wkt, Double minAngle, boolean legacy,
                                           double proximityDistance) throws Exception {
        Geometry geometry = read(wkt);
        GradeContext ctx = new GradeContext(PipeTable.byDu(100));
        for (int i = 0; i < geometry.getNumGeometries(); i++) {
            Geometry core = geometry.getGeometryN(i);
            Geometry proximity = ObstacleIndex.buffer(core, proximityDistance);
            Geometry bound = core instanceof Polygon ? ObstacleIndex.buffer(core, 1) : null;
            ctx.specials.add(special(core, proximity, bound, minAngle, legacy));
        }
        ctx.build();
        return new EdgeValidator(ctx);
    }

    private static SpecialObstacle special(Geometry core, Geometry proximity, Geometry bound,
                                           Double minAngle, boolean legacy) {
        PreparedGeometry prepared = PreparedGeometryFactory.prepare(core);
        // Differential oracle: real JTS raw predicate in the original operand order.
        // This wrapper is not used by the allocation fixture.
        PreparedGeometry predicate = legacy ? (PreparedGeometry) Proxy.newProxyInstance(
                PreparedGeometry.class.getClassLoader(), new Class<?>[]{PreparedGeometry.class},
                (proxy, method, args) -> method.getName().equals("intersects")
                        ? ((Geometry) args[0]).intersects(core) : method.invoke(prepared, args)) : prepared;
        return new SpecialObstacle("special", core instanceof Polygon ? "road" : "tram", 2.5,
                minAngle, 1, core instanceof Polygon, core, predicate,
                proximity, PreparedGeometryFactory.prepare(proximity),
                bound, bound == null ? null : PreparedGeometryFactory.prepare(bound), null);
    }

    private static void compare(EdgeValidator legacy, EdgeValidator prepared, Coordinate a, Coordinate b,
                                Set<Object> exempt) {
        EdgeValidator.EdgeCheck expected = legacy.check(a, b, exempt, null);
        EdgeValidator.EdgeCheck actual = prepared.check(a, b, exempt, null);
        assertEquals(expected.valid, actual.valid, "Validity for " + a + " -> " + b);
        assertEquals(expected.length, actual.length);
        compareIntervals(expected.specials, actual.specials);
        compareIntervals(expected.rawSpecials, actual.rawSpecials);
    }

    private static void compareIntervals(List<SpecialInterval> expected, List<SpecialInterval> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            SpecialInterval e = expected.get(i);
            SpecialInterval a = actual.get(i);
            assertEquals(e.from, a.from);
            assertEquals(e.to, a.to);
            assertEquals(e.kSpec, a.kSpec);
            assertEquals(e.source == null ? null : e.source.sourceId,
                    a.source == null ? null : a.source.sourceId);
        }
    }

    private static Coordinate[] edge(double ax, double ay, double bx, double by) {
        return new Coordinate[]{new Coordinate(ax, ay), new Coordinate(bx, by)};
    }

    private static Geometry read(String wkt) throws Exception {
        return new WKTReader(GeoUtil.GF).read(wkt);
    }
}
