package ru.lct.heatnet.service;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.network.NetworkPlan;
import ru.lct.heatnet.network.PlanEdge;
import ru.lct.heatnet.network.PlanNode;
import ru.lct.heatnet.refdata.PipeTable;
import ru.lct.heatnet.routing.EdgeValidator;
import ru.lct.heatnet.routing.GeoUtil;
import ru.lct.heatnet.routing.GradeContext;
import ru.lct.heatnet.routing.ObstacleIndex;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EdgeSplitGuardSourceChamberTest {
    @Test
    void sourcePermissionStaysAtTheOriginalEndAndDoesNotPermitCutsInsideItsSpecialPass() throws Exception {
        for (String type : List.of("road", "tram_tracks")) {
            for (boolean reverse : new boolean[]{false, true}) {
                Fixture f = fixture(type, reverse, true);
                assertTrue(new EdgeValidator(f.context).check(f.edge.coords.get(0),
                        f.edge.coords.get(1), Set.of("source-pipe"), null, null, f.source.utm).valid,
                        "the original source-to-outside segment is genuinely valid");

                assertFalse(EdgeSplitGuard.allows(f.edge, new Coordinate(5, 0), f.context),
                        type + ": a cut inside the polygon must not inherit source permission");
                assertFalse(EdgeSplitGuard.allows(f.edge, new Coordinate(12, 0), f.context),
                        type + ": a cut two metres outside leaves less than the required three metres");
                assertTrue(EdgeSplitGuard.allows(f.edge, new Coordinate(20, 0), f.context),
                        type + ": a cut beyond the full special pass preserves both admissible children");
            }
        }
    }

    @Test
    void onlyANewChamberOnTheOriginalPipeCanReuseTheSourceEndPermission() throws Exception {
        for (String type : List.of("road", "tram_tracks")) {
            Fixture f = fixture(type, false, false);
            // Same location and pipe exemptions are insufficient: this is an ordinary
            // newly built junction until its original-pipe role is explicitly present.
            assertNull(f.source.splitPipeDu);
            assertFalse(EdgeSplitGuard.allows(f.edge, new Coordinate(20, 0), f.context));

            f.source.splitPipeDu = 300;
            assertTrue(EdgeSplitGuard.allows(f.edge, new Coordinate(20, 0), f.context));
            assertFalse(EdgeSplitGuard.allows(f.edge, new Coordinate(12, 0), f.context));
        }
    }

    private static Fixture fixture(String type, boolean reverse, boolean existing) throws Exception {
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

        NetworkPlan plan = new NetworkPlan();
        PlanNode source = plan.addNode(new PlanNode(existing ? PlanNode.Kind.EXISTING_CHAMBER
                : PlanNode.Kind.NEW_CHAMBER, new Coordinate(0, 0)));
        source.exemptSpecialIds = Set.of("source-pipe");
        PlanNode target = plan.addNode(new PlanNode(PlanNode.Kind.OKS, new Coordinate(30, 0)));
        PlanEdge edge = reverse ? plan.addEdge(target, source, List.of(target.utm, source.utm))
                : plan.addEdge(source, target, List.of(source.utm, target.utm));
        return new Fixture(source, edge, new ObstacleIndex(input).forGrade(PipeTable.byDu(100)));
    }

    private static final class Fixture {
        final PlanNode source;
        final PlanEdge edge;
        final GradeContext context;

        Fixture(PlanNode source, PlanEdge edge, GradeContext context) {
            this.source = source;
            this.edge = edge;
            this.context = context;
        }
    }
}
