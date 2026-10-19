package ru.lct.heatnet.routing;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.refdata.PipeTable;
import ru.lct.heatnet.refdata.RestrictionRules;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class EdgeValidatorAlongRouteMarginTest {
    private static final double SQRT2 = Math.sqrt(2);
    private static final String STRIP = "POLYGON ((-5 -50,5 -50,5 50,-5 50,-5 -50))";

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void diagonalCrossingAddsThreeMetresAlongRouteInBothCachedDirections(String type) throws Exception {
        GradeContext context = context(type, 50, STRIP, null);
        EdgeValidator validator = new EdgeValidator(context);
        Coordinate a = c(-20, -20), b = c(30, 30);
        assertWindow(validator.check(a, b, null, null), 15 * SQRT2 - 3, 25 * SQRT2 + 3);
        EdgeValidator.EdgeCheck reverse = validator.check(b, a, null, null);
        assertWindow(reverse, 25 * SQRT2 - 3, 35 * SQRT2 + 3);
        assertEquals(1, context.staticEdgeCache.size());
        assertSame(context.specials.get(0), reverse.rawSpecials.get(0).source);
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void exactlyThreeMetresBeforeAndAfterIsEnoughAtFortyFiveAndNinetyDegrees(String type) throws Exception {
        for (double angle : new double[]{45, 90}) {
            EdgeValidator validator = new EdgeValidator(context(type, 50, STRIP, null));
            double dx = Math.sin(Math.toRadians(angle)), dy = Math.cos(Math.toRadians(angle));
            Coordinate a = c(-5 - 3 * dx, -5 * dy / dx - 3 * dy);
            Coordinate b = c(5 + 3 * dx, 5 * dy / dx + 3 * dy);
            assertWindow(validator.check(a, b, null, null), 0, a.distance(b));
            assertWindow(validator.check(b, a, null, null), 0, a.distance(b));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void missingEitherMarginStillRejectsCrossing(String type) throws Exception {
        EdgeValidator validator = new EdgeValidator(context(type, 50, STRIP, null));
        Coordinate fullA = c(-5 - 3 / SQRT2, -5 - 3 / SQRT2);
        Coordinate fullB = c(5 + 3 / SQRT2, 5 + 3 / SQRT2);
        Coordinate shortA = c(-5 - 2.999 / SQRT2, -5 - 2.999 / SQRT2);
        Coordinate shortB = c(5 + 2.999 / SQRT2, 5 + 2.999 / SQRT2);
        assertFalse(validator.check(shortA, fullB, null, null).valid);
        assertFalse(validator.check(fullA, shortB, null, null).valid);
        assertFalse(validator.check(fullB, shortA, null, null).valid);
        assertFalse(validator.check(shortB, fullA, null, null).valid);
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void lowAngleAndNearParallelPassRemainForbidden(String type) throws Exception {
        EdgeValidator validator = new EdgeValidator(context(type, 50, STRIP, null));
        assertFalse(validator.check(c(-20, -34.64), c(20, 34.64), null, null).valid);
        assertFalse(validator.check(c(6, -20), c(6, 20), null, null).valid);
    }

    @Test
    void nearbyHoleMergesPricingButRetainsBothObstacleWindows() throws Exception {
        GradeContext context = context("road", 50,
                "POLYGON ((-5 -50,5 -50,5 50,-5 50,-5 -50),(-1 -20,-1 20,1 20,1 -20,-1 -20))", null);
        EdgeValidator.EdgeCheck result = new EdgeValidator(context).check(c(-20, -20), c(30, 30), null, null);
        assertTrue(result.valid);
        assertEquals(2, result.rawSpecials.size());
        assertEquals(1, result.specials.size());
        assertEquals(15 * SQRT2 - 3, result.specials.get(0).from, 1e-8);
        assertEquals(25 * SQRT2 + 3, result.specials.get(0).to, 1e-8);
    }

    @Test
    void yamlMarginAndUnitCoefficientRetainMarkingAndDepthWindow() throws Exception {
        GradeContext context = context("road", 50, STRIP,
                "restrictions:\n  road:\n    special_margin: 5.0\n    k_spec: 1.0\n");
        EdgeValidator.EdgeCheck result = new EdgeValidator(context).check(c(-20, -20), c(30, 30), null, null);
        assertWindow(result, 15 * SQRT2 - 5, 25 * SQRT2 + 5);
        assertEquals(1.0, result.specials.get(0).kSpec);
        assertNotNull(result.rawSpecials.get(0).source.vertical);
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void ordinaryTailOutsideSpecialMustStillRespectDuClearance(String type) throws Exception {
        EdgeValidator validator = new EdgeValidator(context(type, 400, STRIP, null));
        // For DU400, the pair half-width + clearance exceeds 3*sin(45deg).
        assertFalse(validator.check(c(-20, -20), c(30, 30), null, null).valid);
        assertTrue(validator.check(c(-20, 0), c(30, 0), null, null).valid);
        // A source chamber does not exempt an ordinary tail beyond its special window.
        assertFalse(validator.check(c(0, 0), c(30, 30), null, null, null, c(0, 0)).valid);
    }

    @Test
    void largeYamlClearanceCannotBeWaivedByAnyCoreContact() throws Exception {
        EdgeValidator validator = new EdgeValidator(context("road", 50, STRIP,
                "restrictions:\n  road:\n    min_distance: 5.0\n"));
        assertFalse(validator.check(c(-20, 0), c(30, 0), null, null).valid);
    }

    private static void assertWindow(EdgeValidator.EdgeCheck result, double from, double to) {
        assertTrue(result.valid);
        assertEquals(1, result.specials.size());
        assertEquals(1, result.rawSpecials.size());
        assertEquals(from, result.specials.get(0).from, 1e-8);
        assertEquals(to, result.specials.get(0).to, 1e-8);
        assertEquals(from, result.rawSpecials.get(0).from, 1e-8);
        assertEquals(to, result.rawSpecials.get(0).to, 1e-8);
    }

    private static GradeContext context(String type, int du, String wkt, String yaml) throws Exception {
        InputModel input = new InputModel();
        InputModel.Restriction restriction = new InputModel.Restriction();
        restriction.id = "crossing";
        restriction.restrictionType = type;
        restriction.utm = new WKTReader(GeoUtil.GF).read(wkt);
        input.restrictions.add(restriction);
        RestrictionRules rules = RestrictionRules.builtIn();
        if (yaml != null) rules = rules.mergeYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        return new ObstacleIndex(input, rules).forGrade(PipeTable.byDu(du));
    }

    private static Coordinate c(double x, double y) { return new Coordinate(x, y); }
}
