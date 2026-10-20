package ru.lct.heatnet.routing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.refdata.PipeTable;
import ru.lct.heatnet.refdata.RestrictionRules;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Topological contacts must not become a false permission to waive clearance. */
class EdgeValidatorPolygonContactTest {
    private static final String SQUARE =
            "POLYGON ((0 0,20 0,20 20,0 20,0 0))";
    private static final String REVERSED_SQUARE =
            "POLYGON ((0 0,0 20,20 20,20 0,0 0))";

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void isolatedCornerTouchIsNotAThroughCrossing(String type) throws Exception {
        for (String polygon : new String[]{SQUARE, REVERSED_SQUARE}) {
            EdgeValidator validator = validator(type, polygon, null);
            assertBothInvalid(validator, c(-10, 10), c(10, -10),
                    "Touching the core at (0,0) does not waive the horizontal clearance");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void cornerCrossingChecksBothIncidentBoundaryDirections(String type) throws Exception {
        for (String polygon : new String[]{SQUARE, REVERSED_SQUARE,
                "POLYGON ((20 20,0 20,0 0,20 0,20 20))"}) {
            EdgeValidator validator = validator(type, polygon, null);
            assertBothInvalid(validator, c(-10, -5), c(50, 25),
                    "At (0,0) the angles are 26.565 and 63.435 degrees; ring order must not choose one");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void diagonalThroughOppositeCornersRetainsOneAxialSpecial(String type) throws Exception {
        for (String polygon : new String[]{SQUARE, REVERSED_SQUARE}) {
            EdgeValidator validator = validator(type, polygon, null);
            Coordinate a = c(-10, -10), b = c(30, 30);
            EdgeValidator.EdgeCheck forward = validator.check(a, b, null, null);
            EdgeValidator.EdgeCheck backward = validator.check(b, a, null, null);
            for (EdgeValidator.EdgeCheck check : new EdgeValidator.EdgeCheck[]{forward, backward}) {
                assertTrue(check.valid, "Both incident boundary angles are 45 degrees");
                assertEquals(1, check.rawSpecials.size());
                assertEquals(10 * Math.sqrt(2) - 3, check.rawSpecials.get(0).from, 1e-8);
                assertEquals(30 * Math.sqrt(2) + 3, check.rawSpecials.get(0).to, 1e-8);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void followingBoundaryIsNotACrossingEvenWithZeroMinimumAngle(String type) throws Exception {
        String yaml = "restrictions:\n  " + type + ":\n    min_angle: 0\n";
        for (String polygon : new String[]{SQUARE, REVERSED_SQUARE}) {
            assertBothInvalid(validator(type, polygon, yaml), c(-10, 0), c(30, 0),
                    "A route along the boundary has no positive interval in the polygon interior");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void nearbyParallelLineStillNeedsClearance(String type) throws Exception {
        EdgeValidator validator = validator(type, SQUARE, null);
        assertBothInvalid(validator, c(-10, -0.5), c(30, -0.5),
                "No core crossing means no SPECIAL exemption");
        EdgeValidator.EdgeCheck outside = validator.check(c(-10, -10), c(30, -10), null, null);
        assertTrue(outside.valid);
        assertTrue(outside.specials.isEmpty());
        assertTrue(outside.rawSpecials.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void tinyPerturbationAroundCornerSeparatesNearMissFromRealCrossing(String type) throws Exception {
        EdgeValidator validator = validator(type, SQUARE, null);
        // The two lines differ by 0.2 mm. The exterior one still violates
        // horizontal clearance; the interior one has an actual 45-degree crossing.
        assertBothInvalid(validator, c(-10, 9.9999), c(10, -10.0001),
                "A near miss of the core cannot waive clearance");
        EdgeValidator.EdgeCheck inside = validator.check(c(-10, 10.0001), c(10, -9.9999), null, null);
        assertTrue(inside.valid);
        assertEquals(1, inside.rawSpecials.size());
        assertEquals(6 + Math.sqrt(2) * 0.0001,
                inside.rawSpecials.get(0).to - inside.rawSpecials.get(0).from, 1e-8);
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void sourceChamberOnBoundaryKeepsTheExplicitOneSidedException(String type) throws Exception {
        EdgeValidator validator = validator(type, SQUARE, null);
        Coordinate source = c(0, 10), outside = c(-10, 10);
        assertFalse(validator.check(source, outside, null, null).valid);
        EdgeValidator.EdgeCheck forward = validator.check(source, outside, null, null, null, source);
        EdgeValidator.EdgeCheck backward = validator.check(outside, source, null, null, null, source);
        assertTrue(forward.valid);
        assertTrue(backward.valid);
        assertEquals(1, forward.rawSpecials.size());
        assertEquals(0, forward.rawSpecials.get(0).from, 1e-8);
        assertEquals(3, forward.rawSpecials.get(0).to, 1e-8);
        assertEquals(7, backward.rawSpecials.get(0).from, 1e-8);
        assertEquals(10, backward.rawSpecials.get(0).to, 1e-8);
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void touchingHoleVertexFromInsideDoesNotAddCrossingOrAngleConstraint(String type) throws Exception {
        for (String hole : new String[]{"0 0,2 10,20 10,0 0", "0 0,20 10,2 10,0 0",
                "2 10,20 10,0 0,2 10"}) {
            String polygon = "POLYGON ((-20 -20,40 -20,40 30,-20 30,-20 -20),(" + hole + "))";
            EdgeValidator validator = validator(type, polygon, null);
            for (boolean sourceInside : new boolean[]{false, true}) {
                Coordinate a = c(sourceInside ? -10 : -30, 0), b = c(50, 0);
                Coordinate source = sourceInside ? a : null;
                for (boolean reverse : new boolean[]{false, true}) {
                    EdgeValidator.EdgeCheck result = validator.check(reverse ? b : a, reverse ? a : b,
                            null, null, null, source);
                    assertTrue(result.valid, "Touching the hole tip never leaves the road interior");
                    assertEquals(1, result.rawSpecials.size(),
                            "The continuous crossing must not gain fictitious boundaries around the hole tip");
                    double from = sourceInside ? 0 : 7, to = sourceInside ? 53 : 73;
                    double length = a.distance(b);
                    assertEquals(reverse ? length - to : from, result.rawSpecials.get(0).from, 1e-8);
                    assertEquals(reverse ? length - from : to, result.rawSpecials.get(0).to, 1e-8);
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void actuallyCrossingHoleRetainsItsTwoPassesAndChecksTheBoundaryAngle(String type) throws Exception {
        String polygon = "POLYGON ((-20 -20,40 -20,40 30,-20 30,-20 -20),(0 0,2 10,20 10,0 0))";
        Coordinate a = c(-30, 5), b = c(50, 5);
        assertBothInvalid(validator(type, polygon, null), a, b,
                "At a real hole exit the 26.565-degree boundary angle still applies");
        EdgeValidator relaxedAngle = validator(type, polygon,
                "restrictions:\n  " + type + ":\n    min_angle: 0\n    min_distance: 0\n");
        EdgeValidator.EdgeCheck result = relaxedAngle.check(a, b, null, null);
        assertTrue(result.valid);
        assertEquals(2, result.rawSpecials.size());
        assertEquals(7, result.rawSpecials.get(0).from, 1e-8);
        assertEquals(34, result.rawSpecials.get(0).to, 1e-8);
        assertEquals(37, result.rawSpecials.get(1).from, 1e-8);
        assertEquals(73, result.rawSpecials.get(1).to, 1e-8);
    }

    @ParameterizedTest
    @ValueSource(strings = {"prohibited_site", "water", "park", "oks"})
    void forbiddenCoreCornerAndBoundaryContactsStayForbidden(String type) throws Exception {
        EdgeValidator validator = validator(type, SQUARE, null);
        assertBothInvalid(validator, c(-10, 10), c(10, -10), "Core vertex contact");
        assertBothInvalid(validator, c(-10, 0), c(30, 0), "Boundary overlap");
        assertBothInvalid(validator, c(-10, -10), c(30, 30), "Through crossing");
    }

    @Test
    void ownOksCornerEntryNeedsOneTerminalInteriorInterval() throws Exception {
        EdgeValidator validator = validator("oks", SQUARE, null);
        assertTrue(validator.check(c(-10, -10), c(10, 10), null, "polygon").valid);
        assertTrue(validator.check(c(10, 10), c(-10, -10), null, "polygon").valid);
        assertFalse(validator.check(c(-10, 0), c(10, 0), null, "polygon").valid,
                "A stretch along the outer wall is not a single entry");
        assertFalse(validator.check(c(-10, 10), c(10, -10), null, "polygon").valid,
                "An unrelated corner touch cannot use the own-building waiver");
    }

    private static void assertBothInvalid(EdgeValidator validator, Coordinate a, Coordinate b, String message) {
        assertFalse(validator.check(a, b, null, null).valid, message + " (forward)");
        assertFalse(validator.check(b, a, null, null).valid, message + " (backward)");
    }

    private static EdgeValidator validator(String type, String wkt, String yaml) throws Exception {
        InputModel input = new InputModel();
        InputModel.Restriction restriction = new InputModel.Restriction();
        restriction.id = "polygon";
        restriction.restrictionType = type;
        Geometry geometry = new WKTReader(GeoUtil.GF).read(wkt);
        assertTrue(geometry.isValid(), "Fixture must have a valid polygon");
        restriction.utm = geometry;
        input.restrictions.add(restriction);
        RestrictionRules rules = RestrictionRules.builtIn();
        if (yaml != null) {
            rules = rules.mergeYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        }
        return new EdgeValidator(new ObstacleIndex(input, rules).forGrade(PipeTable.byDu(100)));
    }

    private static Coordinate c(double x, double y) { return new Coordinate(x, y); }
}
