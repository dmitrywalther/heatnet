package ru.lct.heatnet.routing;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.refdata.PipeTable;
import ru.lct.heatnet.refdata.RestrictionRules;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class EdgeValidatorSourcePolygonTest {
    @Test
    void authorizationNeitherReadsNorPoisonsTheCoordinateCacheInEitherOrder() throws Exception {
        for (boolean allowedFirst : new boolean[]{false, true}) {
            GradeContext ctx = context("road", RestrictionRules.builtIn());
            EdgeValidator validator = new EdgeValidator(ctx);
            Coordinate source = c(0, 0), outer = c(30, 0);
            if (!allowedFirst) assertFalse(validator.check(source, outer, null, null).valid);
            EdgeValidator.EdgeCheck allowed = validator.check(source, outer, null, null, null, source);
            assertTrue(allowed.valid);
            assertEquals(8, allowed.specials.get(0).to, 1e-9);
            assertFalse(validator.check(source, outer, null, null).valid);
            EdgeValidator.EdgeCheck reversed = validator.check(outer, source, null, null, null, source);
            assertTrue(reversed.valid);
            assertEquals(22, reversed.specials.get(0).from, 1e-9);
            assertEquals(30, reversed.specials.get(0).to, 1e-9);
            assertSame(ctx.specials.get(0), reversed.rawSpecials.get(0).source);
            assertFalse(validator.check(outer, source, null, null).valid);
            assertEquals(1, ctx.staticEdgeCache.size());
        }
    }

    @Test
    void privilegeRequiresTheAuthorizedEndpointInsideRoadOrTram() throws Exception {
        EdgeValidator validator = new EdgeValidator(context("road", RestrictionRules.builtIn()));
        assertFalse(validator.check(c(0, 0), c(30, 0), null, null, null, c(0, 1)).valid);
        assertFalse(validator.check(c(6, 0), c(-30, 0), null, null, null, c(6, 0)).valid);
        EdgeValidator forbidden = new EdgeValidator(context("water", RestrictionRules.builtIn()));
        assertFalse(forbidden.check(c(0, 0), c(30, 0), null, null, null, c(0, 0)).valid);
    }

    @Test
    void unitMultiplierStillRetainsTheFullSpecialAndVerticalSource() throws Exception {
        RestrictionRules rules = RestrictionRules.builtIn().mergeYaml(new ByteArrayInputStream(
                "restrictions:\n  road:\n    k_spec: 1.0\n".getBytes(StandardCharsets.UTF_8)));
        GradeContext ctx = context("road", rules);
        EdgeValidator.EdgeCheck result = new EdgeValidator(ctx).check(c(0, 0), c(30, 0), null, null, null, c(0, 0));
        assertTrue(result.valid);
        assertEquals(1, result.specials.size());
        assertEquals(1, result.rawSpecials.size());
        assertEquals(1, result.specials.get(0).kSpec, 1e-9);
        assertEquals(8, result.specials.get(0).to, 1e-9);
        assertNotNull(result.rawSpecials.get(0).source.vertical);
    }

    @Test
    void nearbyHoleDoesNotRequireAnExtraMarginBehindTheSourceChamber() throws Exception {
        GradeContext ctx = context("road", RestrictionRules.builtIn(),
                "POLYGON ((-5 -10,10 -10,10 10,-5 10,-5 -10), (1 -2,1 2,2 2,2 -2,1 -2))");
        EdgeValidator validator = new EdgeValidator(ctx);
        Coordinate source = c(0, 0), outer = c(30, 0);
        for (boolean reverse : new boolean[]{false, true}) {
            EdgeValidator.EdgeCheck pass = validator.check(reverse ? outer : source,
                    reverse ? source : outer, null, null, null, source);
            assertTrue(pass.valid, "overlapping special intervals form one straight pass from source to exit+3m");
            assertEquals(1, pass.specials.size());
            assertEquals(reverse ? 17 : 0, pass.specials.get(0).from, 1e-9);
            assertEquals(reverse ? 30 : 13, pass.specials.get(0).to, 1e-9);
        }
        assertFalse(validator.check(source, c(12.9, 0), null, null, null, source).valid);
    }

    @Test
    void boundarySourceHeadingOutsideStillNeedsThreeMetresOfStraightPass() throws Exception {
        EdgeValidator validator = new EdgeValidator(context("road", RestrictionRules.builtIn()));
        Coordinate source = c(5, 0);
        EdgeValidator.EdgeCheck pass = validator.check(source, c(30, 0), null, null, null, source);
        assertTrue(pass.valid);
        assertEquals(3, pass.specials.get(0).to, 1e-9);
        assertFalse(validator.check(source, c(7.9, 0), null, null, null, source).valid);
    }

    private static GradeContext context(String type, RestrictionRules rules) throws Exception {
        return context(type, rules, "POLYGON ((-5 -40,5 -40,5 40,-5 40,-5 -40))");
    }

    private static GradeContext context(String type, RestrictionRules rules, String wkt) throws Exception {
        InputModel input = new InputModel();
        InputModel.Restriction r = new InputModel.Restriction();
        r.id = "polygon";
        r.restrictionType = type;
        r.utm = new WKTReader(GeoUtil.GF).read(wkt);
        input.restrictions.add(r);
        return new ObstacleIndex(input, rules).forGrade(PipeTable.byDu(50));
    }

    private static Coordinate c(double x, double y) { return new Coordinate(x, y); }
}
