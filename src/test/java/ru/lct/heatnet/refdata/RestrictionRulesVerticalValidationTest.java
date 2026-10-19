package ru.lct.heatnet.refdata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Неверный YAML должен остановить загрузку, а не ослаблять окно глубины. */
class RestrictionRulesVerticalValidationTest {

    private static final List<String> NUMERIC_FIELDS = List.of(
            "min_top_depth", "clearance", "obstacle_top_depth", "obstacle_height");

    private static RestrictionRules merge(String yaml) throws Exception {
        return RestrictionRules.builtIn().mergeYaml(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    private static void rejects(String yaml, String type, String field) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> merge(yaml), yaml);
        assertTrue(error.getMessage().contains(type),
                "The error must identify the rule type: " + error.getMessage());
        assertTrue(error.getMessage().contains(field),
                "The error must identify the invalid field: " + error.getMessage());
    }

    @Test
    void rejectsUnknownOrNonStringCrossingInsteadOfChoosingAboveOrBelow() {
        List<Executable> cases = new ArrayList<>();
        for (String value : List.of("undre", "UNDER", "above", "null", "3")) {
            cases.add(() -> rejects("restrictions:\n"
                    + "  tram_tracks:\n"
                    + "    vertical: {crossing: " + value + ", min_top_depth: 3.8}\n",
                    "tram_tracks", "vertical.crossing"));
        }
        assertAll(cases);
    }

    @Test
    void rejectsNullScalarAndArrayVerticalInsteadOfUsingDefaultFields() {
        List<Executable> cases = new ArrayList<>();
        for (String value : List.of("null", "not_an_object", "3.8", "[]")) {
            cases.add(() -> rejects("restrictions:\n"
                    + "  power_cable:\n"
                    + "    vertical: " + value + "\n",
                    "power_cable", "vertical"));
        }
        assertAll(cases);
    }

    @Test
    void rejectsEverySuppliedNegativeVerticalDimension() {
        List<Executable> cases = new ArrayList<>();
        for (String field : NUMERIC_FIELDS) {
            cases.add(() -> rejects("restrictions:\n"
                    + "  power_cable:\n"
                    + "    vertical: {" + field + ": -0.1}\n",
                    "power_cable", "vertical." + field));
        }
        assertAll(cases);
    }

    @Test
    void rejectsNonNumericVerticalDimensionsInsteadOfCoercingThem() {
        List<Executable> cases = new ArrayList<>();
        for (String field : NUMERIC_FIELDS) {
            for (String value : List.of("'3.8'", "unknown", "null", "true")) {
                cases.add(() -> rejects("restrictions:\n"
                        + "  power_cable:\n"
                        + "    vertical: {" + field + ": " + value + "}\n",
                        "power_cable", "vertical." + field));
            }
        }
        assertAll(cases);
    }

    @Test
    void rejectsNonFiniteNumbersBeforeTheRulesCanReachTheEngine() {
        List<Executable> cases = new ArrayList<>();
        for (String field : NUMERIC_FIELDS) {
            for (String value : List.of(".NaN", ".inf", "-.inf", "1e400")) {
                cases.add(() -> {
                    String yaml = "restrictions:\n"
                            + "  power_cable:\n"
                            + "    vertical: {" + field + ": " + value + "}\n";
                    Exception error = assertThrows(Exception.class, () -> merge(yaml), yaml);
                    // Jackson may reject the non-finite token before parseRule gets a JsonNode.
                    assertTrue(error instanceof IOException || error instanceof IllegalArgumentException,
                            "Expected a YAML parse error or a vertical-rule validation error: " + error);
                    if (error instanceof IllegalArgumentException) {
                        assertTrue(error.getMessage().contains("power_cable"));
                        assertTrue(error.getMessage().contains("vertical." + field));
                    }
                });
            }
        }
        assertAll(cases);
    }

    @Test
    void acceptsCanonicalCrossingsAndZeroInactiveDimensions() throws Exception {
        RestrictionRules rules = merge("restrictions:\n"
                + "  tram_tracks:\n"
                + "    vertical:\n"
                + "      crossing: under\n"
                + "      min_top_depth: 3.8\n"
                + "      clearance: 0\n"
                + "      obstacle_top_depth: 0\n"
                + "      obstacle_height: 0\n"
                + "  power_cable:\n"
                + "    vertical:\n"
                + "      crossing: above_or_below\n"
                + "      min_top_depth: 0\n"
                + "      clearance: 0.7\n"
                + "      obstacle_top_depth: 2.9\n"
                + "      obstacle_height: 0.3\n");
        RestrictionRule.Vertical tram = rules.forType("tram_tracks").getVertical();
        RestrictionRule.Vertical cable = rules.forType("power_cable").getVertical();
        assertTrue(tram.underOnly);
        assertEquals(3.8, tram.minTopDepthM, 1e-9);
        assertFalse(cable.underOnly);
        assertEquals(0.7, cable.clearanceM, 1e-9);
        assertEquals(2.9, cable.obstacleTopDepthM, 1e-9);
        assertEquals(0.3, cable.obstacleHeightM, 1e-9);
    }

    @Test
    void alsoValidatesTheSeparateHeatNetworkCrossingSection() {
        rejects("heat_network_crossing:\n"
                + "  vertical: {clearance: -0.1}\n",
                "heat_network", "vertical.clearance");
    }
}
