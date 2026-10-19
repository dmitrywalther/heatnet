package ru.lct.heatnet.routing;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.operation.overlay.snap.SnapIfNeededOverlayOp;
import org.locationtech.jts.operation.overlayng.OverlayNG;
import org.locationtech.jts.operation.overlayng.OverlayNGRobust;
import ru.lct.heatnet.refdata.PipeTable;
import ru.lct.heatnet.routing.GeoUtil.SpecialInterval;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Clipping characterization plus exact axial SPECIAL-window expectations. */
class EdgeValidatorProximityClippingTest {
    private static final String SOURCE_ID = "road-for-clipping";
    private static final Set<Object> UNRELATED_EXEMPTION = Set.of("another-object");
    private static final double K_SPEC = 2.5;
    private static final String RECTANGLE = "POLYGON ((0 0,10 0,10 10,0 10,0 0))";
    // With margin=1 the positive fixtures must not exempt ordinary proximity tails.
    private static final String PROXIMITY = "POLYGON ((-0.5 -0.5,10.5 -0.5,10.5 10.5,-0.5 10.5,-0.5 -0.5))";
    private static final String BOUND = "POLYGON ((-1 -1,11 -1,11 11,-1 11,-1 -1))";

    @Test
    void robustClippingMatchesExplicitLegacyGeometryAndEveryPortionPredicate() throws Exception {
        List<Fixture> fixtures = new ArrayList<>();
        fixtures.add(rectangle(null));
        fixtures.add(fromCore(
                "POLYGON ((0 0,30 0,30 30,20 30,20 10,10 10,10 30,0 30,0 0))", null));
        fixtures.add(fromCore(
                "POLYGON ((0 0,30 0,30 30,0 30,0 0),(10 10,10 20,20 20,20 10,10 10))", null));
        fixtures.add(fixture(read(RECTANGLE), read(
                "MULTIPOLYGON (((-3 -3,13 -3,13 13,-3 13,-3 -3)),"
                        + "((20 5,22 7,18 7,20 5)))"), read(BOUND), null));
        fixtures.add(fixture(read("POLYGON ((0 0,10 0,10 100,0 100,0 0))"),
                detailedRectangle(-3, -3, 13, 103),
                read("POLYGON ((-1 -1,11 -1,11 101,-1 101,-1 -1))"), null));
        double[][] edges = {
                {-10, 5, 25, 5}, {-10, 15, 45, 15}, {-10, 22, 40, 39},
                {-8, 2, 2, -8}, {-10, -3, 20, -3}, {-10, 0, 20, 0},
                {-10, 10, 10, -10}, {-10, 20, 20, 20},
                {-5, 50.125, 15, 50.125}, {-10, 5, 5, 5}
        };
        for (Fixture fixture : fixtures) {
            for (double[] offset : new double[][]{{0, 0}, {414123.125, 6178123.875}}) {
                AffineTransformation translation = AffineTransformation.translationInstance(offset[0], offset[1]);
                Geometry core = translation.transform(fixture.obstacle.core);
                Geometry proximity = translation.transform(fixture.obstacle.proximityGeom);
                PreparedGeometry corePredicate = PreparedGeometryFactory.prepare(core);
                for (double[] edge : edges) {
                    for (double shift : new double[]{0, -1e-8, 1e-8}) {
                        Coordinate a = new Coordinate(offset[0] + edge[0], offset[1] + edge[1] + shift);
                        Coordinate b = new Coordinate(offset[0] + edge[2], offset[1] + edge[3] + shift);
                        assertClipParity(corePredicate, proximity, a, b);
                        assertClipParity(corePredicate, proximity, b, a);
                    }
                }
            }
        }
    }

    @Test
    void fullRoadCrossingPreservesRawSourceCostAndReversedIntervals() throws Exception {
        Fixture fixture = rectangle(70.0);
        Coordinate a = new Coordinate(-10, 5);
        Coordinate b = new Coordinate(25, 5);

        assertIntervals(fixture.check(a, b), 35, new double[][]{{9, 21}});
        assertIntervals(fixture.check(b, a), 35, new double[][]{{14, 26}});
        assertTrue(fixture.context.staticEdgeCache.isEmpty());
    }

    @Test
    void everySeparateProximityFragmentMustReachTheCore() throws Exception {
        Fixture fixture = fromCore(
                "POLYGON ((0 0,30 0,30 30,20 30,20 10,10 10,10 30,0 30,0 0))", null, 3.5);
        Coordinate a = new Coordinate(-10, 22);
        Coordinate b = new Coordinate(40, 39);
        Geometry segment = GeoUtil.segment(a, b);
        Geometry portions = segment.intersection(fixture.obstacle.proximityGeom);
        int reaching = 0;
        int missing = 0;
        for (int i = 0; i < portions.getNumGeometries(); i++) {
            Geometry part = portions.getGeometryN(i);
            if (!part.isEmpty() && part.getDimension() >= 1) {
                if (part.intersects(fixture.obstacle.core)) reaching++;
                else missing++;
            }
        }
        assertTrue(segment.intersects(fixture.obstacle.core));
        assertTrue(reaching > 0 && missing > 0, "Both kinds of proximity fragment must be exercised");

        assertRejected(fixture.check(a, b));
        assertRejected(fixture.check(b, a));
        assertNoSpecials(fixture.validator.check(a, b, Set.of(SOURCE_ID), null), a.distance(b));
        assertTrue(fixture.context.staticEdgeCache.isEmpty());
    }

    @Test
    void holeKeepsTwoDistinctSpecialIntervalsInBothDirections() throws Exception {
        Fixture fixture = fromCore(
                "POLYGON ((0 0,30 0,30 30,0 30,0 0),(10 10,10 20,20 20,20 10,10 10))", 70.0);
        Coordinate a = new Coordinate(-10, 15);
        Coordinate b = new Coordinate(45, 15);

        assertIntervals(fixture.check(a, b), 55, new double[][]{{9, 21}, {29, 41}});
        assertIntervals(fixture.check(b, a), 55, new double[][]{{14, 26}, {34, 46}});
    }

    @Test
    void proximityPointContactIsAllowedButPositiveLengthWithoutCoreIsRejected() throws Exception {
        Fixture fixture = rectangle(null);
        Coordinate a = new Coordinate(-4, 3);
        Coordinate b = new Coordinate(3, -4);
        Geometry pointContact = GeoUtil.segment(a, b).intersection(fixture.obstacle.proximityGeom);
        assertFalse(pointContact.isEmpty());
        assertEquals(0, pointContact.getDimension());
        assertFalse(GeoUtil.segment(a, b).intersects(fixture.obstacle.core));
        assertNoSpecials(fixture.check(a, b), a.distance(b));
        assertNoSpecials(fixture.check(b, a), a.distance(b));

        Coordinate alongA = new Coordinate(-10, -0.5);
        Coordinate alongB = new Coordinate(20, -0.5);
        Geometry alongBoundary = GeoUtil.segment(alongA, alongB).intersection(fixture.obstacle.proximityGeom);
        assertEquals(1, alongBoundary.getDimension());
        assertEquals(11.0, alongBoundary.getLength(), 1e-9);
        assertFalse(alongBoundary.intersects(fixture.obstacle.core));
        assertRejected(fixture.check(alongA, alongB));
        assertRejected(fixture.check(alongB, alongA));
    }

    @Test
    void coreBoundaryOverlapRetainsCurrentAngleDependentBehavior() throws Exception {
        Coordinate a = new Coordinate(-10, 0);
        Coordinate b = new Coordinate(20, 0);
        Fixture unrestricted = rectangle(null);
        Geometry contact = GeoUtil.segment(a, b).intersection(unrestricted.obstacle.core);
        assertEquals(1, contact.getDimension());
        assertEquals(10.0, contact.getLength(), 1e-9);
        // Characterization only: an unspecified angle currently permits this contact.
        assertIntervals(unrestricted.check(a, b), 30, new double[][]{{9, 21}});
        assertIntervals(unrestricted.check(b, a), 30, new double[][]{{9, 21}});

        Fixture angled = rectangle(70.0);
        assertRejected(angled.check(a, b));
        assertRejected(angled.check(b, a));
        assertNoSpecials(angled.validator.check(a, b, Set.of(SOURCE_ID), null), 30);
    }

    @Test
    void corePointContactRetainsCurrentIntervalAndAngleDecision() throws Exception {
        Coordinate a = new Coordinate(-10, 10);
        Coordinate b = new Coordinate(10, -10);
        Fixture unrestricted = rectangle(null);
        Geometry coreContact = GeoUtil.segment(a, b).intersection(unrestricted.obstacle.core);
        assertFalse(coreContact.isEmpty());
        assertEquals(0, coreContact.getDimension());
        double rootTwo = Math.sqrt(2);
        assertIntervals(unrestricted.check(a, b), 20 * rootTwo,
                new double[][]{{10 * rootTwo - 1, 10 * rootTwo + 1}});
        assertIntervals(unrestricted.check(b, a), 20 * rootTwo,
                new double[][]{{10 * rootTwo - 1, 10 * rootTwo + 1}});

        Fixture angled = rectangle(70.0);
        assertRejected(angled.check(a, b));
        assertRejected(angled.check(b, a));
    }

    @Test
    void mixedPointAndLinePortionsDoNotCreateAnExtraSpecialInterval() throws Exception {
        Fixture fixture = fixture(read(RECTANGLE), read(
                "MULTIPOLYGON (((-0.5 -0.5,10.5 -0.5,10.5 10.5,-0.5 10.5,-0.5 -0.5)),"
                        + "((20 5,22 7,18 7,20 5)))"), read(BOUND), null);
        Coordinate a = new Coordinate(-10, 5);
        Coordinate b = new Coordinate(30, 5);
        Geometry portions = GeoUtil.segment(a, b).intersection(fixture.obstacle.proximityGeom);
        boolean line = false;
        boolean point = false;
        for (int i = 0; i < portions.getNumGeometries(); i++) {
            Geometry portion = portions.getGeometryN(i);
            if (!portion.isEmpty()) {
                line |= portion.getDimension() == 1;
                point |= portion.getDimension() == 0;
            }
        }
        assertTrue(line && point, "Fixture must yield a line fragment and an isolated point");
        assertIntervals(fixture.check(a, b), 40, new double[][]{{9, 21}});
        assertIntervals(fixture.check(b, a), 40, new double[][]{{19, 31}});
    }

    @Test
    void disjointSegmentProducesNoSpecialIntervals() throws Exception {
        Fixture fixture = rectangle(70.0);
        Coordinate a = new Coordinate(-10, 20);
        Coordinate b = new Coordinate(20, 20);
        assertTrue(GeoUtil.segment(a, b).intersection(fixture.obstacle.proximityGeom).isEmpty());
        assertNoSpecials(fixture.check(a, b), 30);
        assertNoSpecials(fixture.check(b, a), 30);
    }

    @Test
    void crossingAngleToleranceAndExplicitExemptionRemainEffective() throws Exception {
        Fixture fixture = fromCore("POLYGON ((0 0,10 0,10 100,0 100,0 0))", 70.0);
        for (double crossingAngle : new double[]{69.7 - 1e-6, 69.7 + 1e-6}) {
            double theta = Math.toRadians(90 - crossingAngle);
            double slope = Math.tan(theta);
            Coordinate a = new Coordinate(-10, 50 - 15 * slope);
            Coordinate b = new Coordinate(20, 50 + 15 * slope);
            if (crossingAngle < 69.7) {
                assertRejected(fixture.check(a, b));
                assertRejected(fixture.check(b, a));
            } else {
                double scale = 1 / Math.cos(theta);
                assertIntervals(fixture.check(a, b), 30 * scale,
                        new double[][]{{10 * scale - 1, 20 * scale + 1}});
                assertIntervals(fixture.check(b, a), 30 * scale,
                        new double[][]{{10 * scale - 1, 20 * scale + 1}});
            }
            assertNoSpecials(fixture.validator.check(a, b, Set.of(SOURCE_ID), null), a.distance(b));
            assertNoSpecials(fixture.validator.check(b, a, Set.of(SOURCE_ID), null), a.distance(b));
        }
        assertTrue(fixture.context.staticEdgeCache.isEmpty());
    }

    @Test
    void pointOnlyProximityTouchRemainsAllowedWithEndpointsInsideBoundZone() throws Exception {
        Fixture fixture = fixture(read(RECTANGLE),
                read("POLYGON ((-2 -2,12 -2,12 12,-2 12,-2 -2))"),
                read("POLYGON ((-3 -3,13 -3,13 13,-3 13,-3 -3))"), 70.0);
        Coordinate a = new Coordinate(-2.5, -1.5);
        Coordinate b = new Coordinate(-1.5, -2.5);
        Geometry segment = GeoUtil.segment(a, b);
        Geometry contact = segment.intersection(fixture.obstacle.proximityGeom);
        assertFalse(contact.isEmpty());
        assertEquals(0, contact.getDimension());
        assertFalse(fixture.obstacle.corePrep.intersects(segment));
        for (Coordinate endpoint : new Coordinate[]{a, b}) {
            Geometry point = GeoUtil.GF.createPoint(endpoint);
            assertTrue(fixture.obstacle.boundZone.contains(point));
            assertFalse(fixture.obstacle.proximity.covers(point));
        }

        // A graph-node buffer is not a prohibition when the core is not reached.
        assertNoSpecials(fixture.check(a, b), Math.sqrt(2));
        assertNoSpecials(fixture.check(b, a), Math.sqrt(2));
        assertTrue(fixture.context.staticEdgeCache.isEmpty());
    }

    @Test
    void rejectedInsufficientAxisMarginAvoidsDetailedClippingAllocation() throws Exception {
        java.lang.management.ThreadMXBean management = ManagementFactory.getThreadMXBean();
        assumeTrue(management instanceof ThreadMXBean, "Requires HotSpot allocation counters");
        ThreadMXBean allocation = (ThreadMXBean) management;
        assumeTrue(allocation.isThreadAllocatedMemorySupported()
                && allocation.isThreadAllocatedMemoryEnabled(), "Thread allocation counters unavailable");

        Polygon proximity = detailedRectangle(-3, -3, 13, 103);
        assertEquals(2049, proximity.getNumPoints());
        assertFalse(proximity.isRectangle());
        Fixture fixture = fixture(read("POLYGON ((0 0,10 0,10 100,0 100,0 0))"), proximity,
                read("POLYGON ((-1 -1,11 -1,11 101,-1 101,-1 -1))"), null);
        Coordinate a = new Coordinate(-0.9, 50.125);
        Coordinate b = new Coordinate(15, 50.125);
        assertTrue(fixture.obstacle.corePrep.intersects(GeoUtil.segment(a, b)));
        Geometry endpoint = GeoUtil.GF.createPoint(a);
        assertEquals(0.9, fixture.obstacle.core.distance(endpoint), 1e-9,
                "The straight axis reaches the core before the required margin=1");
        for (int i = 0; i < 32; i++) {
            assertRejected(i % 2 == 0 ? fixture.check(a, b) : fixture.check(b, a));
        }

        int rejectedWithEmptyIntervals = 0;
        long thread = Thread.currentThread().getId();
        long before = allocation.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 32; i++) {
            EdgeValidator.EdgeCheck result = i % 2 == 0 ? fixture.check(a, b) : fixture.check(b, a);
            if (!result.valid && result.rawSpecials.isEmpty() && result.specials.isEmpty()) {
                rejectedWithEmptyIntervals++;
            }
        }
        long allocated = allocation.getThreadAllocatedBytes(thread) - before;
        assertEquals(32, rejectedWithEmptyIntervals);
        assertTrue(fixture.context.staticEdgeCache.isEmpty(), "Rejects must not come from the static edge cache");
        System.out.println("Rejected insufficient axis margin allocation guard: " + allocated + " bytes for 32 checks");
        // A distinct, predeclared contract for a known-invalid edge; the original 4 MiB guard is unchanged.
        assertTrue(allocated >= 0 && allocated < 1024L * 1024,
                "Insufficient axis margin rejects allocated " + allocated + " bytes, budget 1 MiB");
    }

    @Test
    void detailedProximityChecksStayWithinPredeclaredAllocationBudget() throws Exception {
        java.lang.management.ThreadMXBean management = ManagementFactory.getThreadMXBean();
        assumeTrue(management instanceof ThreadMXBean, "Requires HotSpot allocation counters");
        ThreadMXBean allocation = (ThreadMXBean) management;
        assumeTrue(allocation.isThreadAllocatedMemorySupported()
                && allocation.isThreadAllocatedMemoryEnabled(), "Thread allocation counters unavailable");

        Polygon proximity = detailedRectangle(-0.5, -0.5, 10.5, 100.5);
        assertEquals(2049, proximity.getNumPoints());
        assertFalse(proximity.isRectangle(), "Do not use the five-point rectangle fast path");
        Fixture fixture = fixture(read("POLYGON ((0 0,10 0,10 100,0 100,0 0))"), proximity,
                read("POLYGON ((-1 -1,11 -1,11 101,-1 101,-1 -1))"), null);
        assertTrue(((Polygon) fixture.obstacle.core).isRectangle(), "Only proximity is detailed here");
        Coordinate a = new Coordinate(-5, 50.125);
        Coordinate b = new Coordinate(15, 50.125);
        for (int i = 0; i < 32; i++) {
            assertIntervals(fixture.check(a, b), 20, new double[][]{{4, 16}});
        }

        int valid = 0;
        int rawCount = 0;
        int mergedCount = 0;
        int wrongSourceOrCost = 0;
        double fromSum = 0;
        double toSum = 0;
        long thread = Thread.currentThread().getId();
        long before = allocation.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 32; i++) {
            EdgeValidator.EdgeCheck result = fixture.check(a, b);
            if (result.valid) valid++;
            rawCount += result.rawSpecials.size();
            mergedCount += result.specials.size();
            for (SpecialInterval interval : result.rawSpecials) {
                fromSum += interval.from;
                toSum += interval.to;
                if (interval.source == null || !SOURCE_ID.equals(interval.source.sourceId)
                        || interval.kSpec != K_SPEC) wrongSourceOrCost++;
            }
        }
        long allocated = allocation.getThreadAllocatedBytes(thread) - before;
        assertEquals(32, valid);
        assertEquals(32, rawCount);
        assertEquals(32, mergedCount);
        assertEquals(0, wrongSourceOrCost);
        assertEquals(32 * 4.0, fromSum, 1e-9);
        assertEquals(32 * 16.0, toSum, 1e-9);
        assertTrue(fixture.context.staticEdgeCache.isEmpty(), "Every measured edge must perform actual clipping");
        System.out.println("Detailed proximity allocation guard: " + allocated + " bytes for 32 checks");
        // Declared before the production candidate; do not tune after seeing its result.
        assertTrue(allocated >= 0 && allocated < 4L * 1024 * 1024,
                "Detailed proximity checks allocated " + allocated + " bytes, budget 4 MiB");
    }

    private static Fixture rectangle(Double angle) throws Exception {
        return fixture(read(RECTANGLE), read(PROXIMITY), read(BOUND), angle);
    }

    private static void assertClipParity(PreparedGeometry core, Geometry proximity, Coordinate a, Coordinate b) {
        Geometry segment = GeoUtil.segment(a, b);
        // Explicit legacy oracle: unaffected by the global JTS overlay selection.
        Geometry legacy = SnapIfNeededOverlayOp.intersection(segment, proximity);
        Geometry robust = OverlayNGRobust.overlay(segment, proximity, OverlayNG.INTERSECTION);
        assertEquals(legacy.isEmpty(), robust.isEmpty());
        assertEquals(legacy.getDimension(), robust.getDimension());
        assertEquals(legacy.getNumGeometries(), robust.getNumGeometries());
        Geometry canonicalLegacy = legacy.copy();
        Geometry canonicalRobust = robust.copy();
        canonicalLegacy.normalize();
        canonicalRobust.normalize();
        assertTrue(canonicalLegacy.equalsExact(canonicalRobust),
                () -> "Exact normalized clipping differs for " + segment
                        + ": legacy=" + legacy + ", robust=" + robust);
        assertEquals(everyLongPortionReachesCore(legacy, core), everyLongPortionReachesCore(robust, core),
                "Every linear proximity portion must independently reach the core");
    }

    private static boolean everyLongPortionReachesCore(Geometry portions, PreparedGeometry core) {
        // Mirror the existing dimensional rule, including empty geometry behavior.
        // This optimization must not silently change handling of degeneracies.
        for (int i = 0; i < portions.getNumGeometries(); i++) {
            Geometry portion = portions.getGeometryN(i);
            if (portion.getDimension() >= 1 && !core.intersects(portion)) {
                return false;
            }
        }
        return true;
    }

    private static Fixture fromCore(String wkt, Double angle) throws Exception {
        return fromCore(wkt, angle, 0.5);
    }

    private static Fixture fromCore(String wkt, Double angle, double proximityDistance) throws Exception {
        Geometry core = read(wkt);
        return fixture(core, ObstacleIndex.buffer(core, proximityDistance), ObstacleIndex.buffer(core, 1), angle);
    }

    private static Fixture fixture(Geometry core, Geometry proximity, Geometry bound, Double angle) {
        SpecialObstacle obstacle = new SpecialObstacle(SOURCE_ID, "road", K_SPEC, angle, 1, true,
                core, PreparedGeometryFactory.prepare(core), proximity, PreparedGeometryFactory.prepare(proximity),
                bound, PreparedGeometryFactory.prepare(bound), null);
        GradeContext context = new GradeContext(PipeTable.byDu(100));
        context.specials.add(obstacle);
        context.build();
        return new Fixture(context, obstacle);
    }

    private static void assertRejected(EdgeValidator.EdgeCheck check) {
        assertFalse(check.valid);
        assertTrue(check.rawSpecials.isEmpty());
        assertTrue(check.specials.isEmpty());
    }

    private static void assertNoSpecials(EdgeValidator.EdgeCheck check, double length) {
        assertTrue(check.valid);
        assertEquals(length, check.length, 1e-8);
        assertTrue(check.rawSpecials.isEmpty());
        assertTrue(check.specials.isEmpty());
    }

    private static void assertIntervals(EdgeValidator.EdgeCheck check, double length, double[][] expected) {
        assertTrue(check.valid);
        assertEquals(length, check.length, 1e-8);
        assertEquals(expected.length, check.rawSpecials.size());
        assertEquals(expected.length, check.specials.size());
        List<SpecialInterval> raw = new ArrayList<>(check.rawSpecials);
        raw.sort(Comparator.comparingDouble(interval -> interval.from));
        for (int i = 0; i < expected.length; i++) {
            SpecialInterval source = raw.get(i);
            SpecialInterval merged = check.specials.get(i);
            assertEquals(expected[i][0], source.from, 1e-8);
            assertEquals(expected[i][1], source.to, 1e-8);
            assertEquals(expected[i][0], merged.from, 1e-8);
            assertEquals(expected[i][1], merged.to, 1e-8);
            assertEquals(K_SPEC, source.kSpec);
            assertEquals(K_SPEC, merged.kSpec);
            assertNotNull(source.source);
            assertEquals(SOURCE_ID, source.source.sourceId);
            assertNull(merged.source, "Merged pricing intervals do not carry a single obstacle source");
        }
    }

    private static Polygon detailedRectangle(double minX, double minY, double maxX, double maxY) {
        Coordinate[] corners = {new Coordinate(minX, minY), new Coordinate(maxX, minY),
                new Coordinate(maxX, maxY), new Coordinate(minX, maxY), new Coordinate(minX, minY)};
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
        return GeoUtil.GF.createPolygon(ring);
    }

    private static Geometry read(String wkt) throws Exception {
        return new WKTReader(GeoUtil.GF).read(wkt);
    }

    private static final class Fixture {
        final GradeContext context;
        final SpecialObstacle obstacle;
        final EdgeValidator validator;

        Fixture(GradeContext context, SpecialObstacle obstacle) {
            this.context = context;
            this.obstacle = obstacle;
            this.validator = new EdgeValidator(context);
        }

        EdgeValidator.EdgeCheck check(Coordinate a, Coordinate b) {
            return validator.check(a, b, UNRELATED_EXEMPTION, null);
        }
    }
}
