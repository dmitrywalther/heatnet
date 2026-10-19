package ru.lct.heatnet.routing;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.refdata.PipeTable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdgeValidatorOksEntryTest {

    private static final String RECTANGLE = "POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))";
    private static final String CONCAVE =
            "POLYGON ((0 0, 30 0, 30 30, 20 30, 20 10, 10 10, 10 30, 0 30, 0 0))";
    private static final String COURTYARD =
            "POLYGON ((0 0, 40 0, 40 40, 0 40, 0 0), (10 10, 10 30, 30 30, 30 10, 10 10))";

    private EdgeValidator validator(String... components) throws ParseException {
        GradeContext ctx = new GradeContext(PipeTable.byDu(100));
        for (String wkt : components) {
            Geometry core = new WKTReader(GeoUtil.GF).read(wkt);
            ctx.forbidden.add(new ForbiddenZone("own", "oks", core,
                    PreparedGeometryFactory.prepare(core.buffer(2)), core.buffer(3)));
        }
        ctx.build();
        return new EdgeValidator(ctx);
    }

    private boolean valid(EdgeValidator validator, double ax, double ay, double bx, double by) {
        return validator.check(new Coordinate(ax, ay), new Coordinate(bx, by), null, "own").valid;
    }

    @Test
    void directExteriorEntryIsAllowedInBothDirections() throws ParseException {
        EdgeValidator v = validator(RECTANGLE);
        assertTrue(valid(v, -10, 5, 5, 5));
        assertTrue(valid(v, 5, 5, -10, 5));
        assertFalse(v.check(new Coordinate(-10, 5), new Coordinate(5, 5), null, null).valid);
    }

    @Test
    void crossingAnotherWingBeforeTerminalEntryIsRejectedInBothDirections() throws ParseException {
        EdgeValidator v = validator(CONCAVE);
        assertFalse(valid(v, -10, 25, 25, 25));
        assertFalse(valid(v, 25, 25, -10, 25));
        assertTrue(valid(v, 40, 25, 25, 25));
    }

    @Test
    void crossingCourtyardBeforeReenteringBuildingIsRejected() throws ParseException {
        assertFalse(valid(validator(COURTYARD), -10, 20, 35, 20));
    }

    @Test
    void entryThroughCourtyardWallIsRejected() throws ParseException {
        assertFalse(valid(validator(COURTYARD), 20, 20, 35, 20));
    }

    @Test
    void courtyardTouchingExteriorCannotUseSharedVertexAsEntrance() throws ParseException {
        EdgeValidator v = validator(
                "POLYGON ((0 0,10 0,10 10,0 10,0 0), (0 5,5 7,5 3,0 5))");
        assertFalse(valid(v, 2, 5, 0, 5));
    }

    @Test
    void otherComponentOfSameRestrictionIsNotATransitCorridor() throws ParseException {
        EdgeValidator v = validator(RECTANGLE,
                "POLYGON ((20 0, 30 0, 30 10, 20 10, 20 0))");
        assertFalse(valid(v, -10, 5, 25, 5));
        assertTrue(valid(v, 40, 5, 25, 5));
    }

    @Test
    void multiPolygonInputRetainsSingleEntryValidationAfterIndexing() throws ParseException {
        InputModel input = new InputModel();
        InputModel.Restriction restriction = new InputModel.Restriction();
        restriction.id = "own";
        restriction.restrictionType = "oks";
        restriction.utm = new WKTReader(GeoUtil.GF).read(
                "MULTIPOLYGON (((0 0,10 0,10 10,0 10,0 0)), ((20 0,30 0,30 10,20 10,20 0)))");
        input.restrictions.add(restriction);
        EdgeValidator v = new EdgeValidator(new ObstacleIndex(input).forGrade(PipeTable.byDu(100)));
        assertFalse(valid(v, -10, 5, 25, 5));
        assertTrue(valid(v, 40, 5, 25, 5));
    }

    @Test
    void touchOfOtherComponentBeforeEntryIsRejected() throws ParseException {
        EdgeValidator v = validator(
                "POLYGON ((0 5, 5 10, 0 15, -5 10, 0 5))",
                "POLYGON ((10 0, 20 0, 20 10, 10 10, 10 0))");
        assertFalse(valid(v, -10, 5, 15, 5));
    }

    @Test
    void overlappingWallIsNotASingleEntry() throws ParseException {
        assertFalse(valid(validator(RECTANGLE), -10, 0, 5, 0));
    }

    @Test
    void singleCornerEntryIsAllowed() throws ParseException {
        assertTrue(valid(validator(RECTANGLE), -10, -10, 5, 5));
    }

    @Test
    void endpointOnExteriorBoundaryIsAllowed() throws ParseException {
        assertTrue(valid(validator(RECTANGLE), -10, 5, 0, 5));
    }

    @Test
    void bothEndpointsInsideBuildingAreNotAnExteriorEntry() throws ParseException {
        assertFalse(valid(validator(RECTANGLE), 2, 5, 8, 5));
    }

    @Test
    void endpointInClearanceZoneNeedsNoBuildingCrossing() throws ParseException {
        assertTrue(valid(validator(RECTANGLE), -10, 5, -1, 5));
        assertFalse(valid(validator(RECTANGLE), 20, 5, -1, 5));
    }

    @Test
    void endpointsInCourtyardMayConnectWithoutCrossingBuilding() throws ParseException {
        assertTrue(valid(validator(COURTYARD), 16, 20, 22, 20));
        assertFalse(valid(validator(COURTYARD), -10, 20, 20, 20));
    }
}
