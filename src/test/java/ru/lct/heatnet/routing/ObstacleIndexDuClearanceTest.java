package ru.lct.heatnet.routing;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.refdata.PipeTable;
import ru.lct.heatnet.refdata.RestrictionRules;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ObstacleIndexDuClearanceTest {
    private static final String THRESHOLDS =
            "    min_distance: 2\n    min_distance_by_du:\n"
            + "      - distance: 9\n      - max_du: 100\n        distance: 6\n";

    @Test
    void cableHonoursInclusiveNewDuThresholdAndBothWidths() throws Exception {
        ObstacleIndex index = restriction("power_cable", line(),
                rules("restrictions:\n  power_cable:\n" + THRESHOLDS));
        assertParallelBoundary(index, 100, 6 + 0.10 + PipeTable.byDu(100).getHalfWidthM());
        assertParallelBoundary(index, 125, 9 + 0.10 + PipeTable.byDu(125).getHalfWidthM());
        assertSame(index.forGrade(PipeTable.byDu(100)), index.forGrade(PipeTable.byDu(100)));
        assertNotSame(index.forGrade(PipeTable.byDu(100)), index.forGrade(PipeTable.byDu(125)));
    }

    @Test
    void tramClearanceUsesDuWhileSpecialIntervalRetainsItsOwnMargin() throws Exception {
        Geometry polygon = GeoUtil.GF.createPolygon(new Coordinate[]{
                new Coordinate(0, -100), new Coordinate(10, -100),
                new Coordinate(10, 100), new Coordinate(0, 100), new Coordinate(0, -100)});
        ObstacleIndex index = restriction("tram_tracks", polygon,
                rules("restrictions:\n  tram_tracks:\n" + THRESHOLDS));
        assertParallelBoundary(index, 100, 10 + 6 + PipeTable.byDu(100).getHalfWidthM());
        assertParallelBoundary(index, 125, 10 + 9 + PipeTable.byDu(125).getHalfWidthM());
        for (int du : new int[]{100, 125}) {
            EdgeValidator.EdgeCheck check = new EdgeValidator(index.forGrade(PipeTable.byDu(du)))
                    .check(new Coordinate(-30, 0), new Coordinate(40, 0), null, null);
            assertFalse(check.valid, "a three-metre SPECIAL cannot exempt ordinary tails inside a 6/9-metre clearance");
        }
        // A consistent YAML override can provide enough straight special length.
        // Its margin is independent of the DU-dependent parallel clearance.
        ObstacleIndex widerSpecial = restriction("tram_tracks", polygon,
                rules("restrictions:\n  tram_tracks:\n    special_margin: 10\n" + THRESHOLDS));
        for (int du : new int[]{100, 125}) {
            EdgeValidator.EdgeCheck check = new EdgeValidator(widerSpecial.forGrade(PipeTable.byDu(du)))
                    .check(new Coordinate(-30, 0), new Coordinate(40, 0), null, null);
            assertTrue(check.valid, "the declared ten-metre SPECIAL covers the proximity zone");
            assertEquals(1, check.specials.size());
            assertEquals(20, check.specials.get(0).from, 1e-9);
            assertEquals(50, check.specials.get(0).to, 1e-9);
        }
    }

    @Test
    void existingNetworkUsesNewDuForThresholdAndExistingDuForOwnWidth() throws Exception {
        InputModel input = new InputModel();
        InputModel.ExistingPipe pipe = new InputModel.ExistingPipe();
        pipe.id = "old-pipe";
        pipe.diameter = 400;
        pipe.utm = (org.locationtech.jts.geom.LineString) line();
        input.pipes.add(pipe);
        ObstacleIndex index = new ObstacleIndex(input,
                rules("heat_network_crossing:\n" + THRESHOLDS));
        double oldHalf = PipeTable.byDu(400).getHalfWidthM();
        assertParallelBoundary(index, 100, 6 + oldHalf + PipeTable.byDu(100).getHalfWidthM());
        assertParallelBoundary(index, 125, 9 + oldHalf + PipeTable.byDu(125).getHalfWidthM());
        assertEquals(PipeTable.byDu(400).getHeightM(),
                index.forGrade(PipeTable.byDu(100)).specials.get(0).vertical.obstacleHeightM);
    }

    @Test
    void unknownSpecialGetsInheritedDuThresholds() throws Exception {
        RestrictionRules rules = rules("restrictions:\n  unknown_default:\n"
                + "    mode: special\n    own_half_width: 0.2\n" + THRESHOLDS);
        ObstacleIndex index = restriction("custom-cable", line(), rules);
        assertParallelBoundary(index, 100, 6 + 0.2 + PipeTable.byDu(100).getHalfWidthM());
        assertParallelBoundary(index, 125, 9 + 0.2 + PipeTable.byDu(125).getHalfWidthM());
    }

    @Test
    void explicitScalarOverrideStillReplacesInheritedThresholds() throws Exception {
        RestrictionRules thresholds = rules("restrictions:\n  power_cable:\n" + THRESHOLDS);
        RestrictionRules scalar = thresholds.mergeYaml(stream(
                "restrictions:\n  power_cable:\n    min_distance: 2\n"));
        ObstacleIndex index = restriction("power_cable", line(), scalar);
        assertParallelBoundary(index, 100, 2 + 0.10 + PipeTable.byDu(100).getHalfWidthM());
        assertParallelBoundary(index, 125, 2 + 0.10 + PipeTable.byDu(125).getHalfWidthM());
    }

    private static void assertParallelBoundary(ObstacleIndex index, int du, double boundary) {
        EdgeValidator validator = new EdgeValidator(index.forGrade(PipeTable.byDu(du)));
        // The established 1 cm geometry epsilon is unchanged. Probe on either side,
        // away from endpoints, so validity depends on parallel clearance, not crossing.
        assertFalse(validator.check(new Coordinate(boundary - 0.02, -20),
                new Coordinate(boundary - 0.02, 20), null, null).valid,
                "parallel approach inside the YAML clearance must fail for DU " + du);
        assertTrue(validator.check(new Coordinate(boundary + 0.02, -20),
                new Coordinate(boundary + 0.02, 20), null, null).valid,
                "parallel approach outside the YAML clearance must remain possible");
    }

    private static Geometry line() {
        return GeoUtil.GF.createLineString(new Coordinate[]{new Coordinate(0, -100), new Coordinate(0, 100)});
    }

    private static ObstacleIndex restriction(String type, Geometry core, RestrictionRules rules) {
        InputModel input = new InputModel();
        InputModel.Restriction restriction = new InputModel.Restriction();
        restriction.id = "obstacle";
        restriction.restrictionType = type;
        restriction.utm = core;
        input.restrictions.add(restriction);
        return new ObstacleIndex(input, rules);
    }

    private static RestrictionRules rules(String yaml) throws Exception {
        return RestrictionRules.builtIn().mergeYaml(stream(yaml));
    }

    private static ByteArrayInputStream stream(String yaml) {
        return new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    }
}
