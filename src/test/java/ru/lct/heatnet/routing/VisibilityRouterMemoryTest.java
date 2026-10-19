package ru.lct.heatnet.routing;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import ru.lct.heatnet.refdata.PipeTable;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VisibilityRouterMemoryTest {
    @Test
    void rejectedEdgesDoNotAccumulateQuadraticRoutingAllocations() {
        java.lang.management.ThreadMXBean management = ManagementFactory.getThreadMXBean();
        assumeTrue(management instanceof ThreadMXBean, "Requires HotSpot allocation counters");
        ThreadMXBean allocation = (ThreadMXBean) management;
        assumeTrue(allocation.isThreadAllocatedMemorySupported()
                && allocation.isThreadAllocatedMemoryEnabled(), "Thread allocation counters unavailable");

        List<Coordinate> graph = new ArrayList<>();
        List<Coordinate> starts = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            starts.add(new Coordinate(0, i));
            graph.add(new Coordinate(10, i));
        }
        GradeContext ctx = new GradeContext(PipeTable.byDu(100)) {
            @Override
            public List<Coordinate> graphNodesIn(Envelope corridor) {
                return graph;
            }
        };
        // Keep the data extent inside the first corridor: exactly one search attempt.
        Geometry extent = GeoUtil.GF.createPoint(new Coordinate(10, 100)).buffer(1);
        ctx.forbidden.add(new ForbiddenZone("extent", "test", extent,
                PreparedGeometryFactory.prepare(extent), extent));
        ctx.build();
        RejectingValidator validator = new RejectingValidator(ctx);
        VisibilityRouter router = new VisibilityRouter(ctx, validator, 89_748);
        List<TieCandidate> targets = List.of(new TieCandidate(
                TieCandidate.Kind.NEW_NETWORK_EDGE_POINT, new Coordinate(20, 100), "tie", 0, null));
        double[] costs = new double[starts.size()];

        // Fixture, validator result and first-use/JIT allocations are outside measurement.
        for (int i = 0; i < 3; i++) {
            assertNull(router.route(starts, costs, null, targets));
        }
        validator.calls = 0;
        long thread = Thread.currentThread().getId();
        long before = allocation.getThreadAllocatedBytes(thread);
        VisibilityRouter.RoutedPath path = router.route(starts, costs, null, targets);
        long allocated = allocation.getThreadAllocatedBytes(thread) - before;

        assertNull(path);
        assertEquals(256 * 257, validator.calls, "Every start must attempt all graph nodes and the target");
        assertTrue(allocated >= 0 && allocated < 2L * 1024 * 1024,
                "Routing bookkeeping exceeded 2 MiB for 513 nodes: " + allocated + " allocated bytes");
        System.out.println("VisibilityRouter allocation guard: " + allocated + " bytes, "
                + validator.calls + " edge validations");
    }

    private static final class RejectingValidator extends EdgeValidator {
        private final EdgeCheck rejected = EdgeCheck.invalid();
        int calls;

        RejectingValidator(GradeContext ctx) {
            super(ctx);
        }

        @Override
        public EdgeCheck check(Coordinate a, Coordinate b, Set<Object> exempt,
                               Object skipRestrictionId, Coordinate allowTouchAt) {
            calls++;
            return rejected;
        }
    }
}
