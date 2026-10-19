package ru.lct.heatnet.refdata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RestrictionRulesHorizontalValidationTest {
    private static final List<String> FIELDS = List.of(
            "min_distance", "special_margin", "own_half_width", "min_angle", "k_spec");

    private static RestrictionRules merge(String text) throws Exception {
        return RestrictionRules.builtIn().mergeYaml(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static void rejects(String text, String field) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> merge(text), text);
        assertTrue(error.getMessage().contains(field), error.getMessage());
    }

    @Test
    void rejectsNonNumericFieldsInsteadOfCoercingToZero() {
        List<Executable> cases = new ArrayList<>();
        for (String field : FIELDS) {
            for (String value : List.of("'3'", "oops", "null", "true", "[]", "{}")) {
                cases.add(() -> rejects("restrictions:\n  road: {" + field + ": " + value + "}\n",
                        "road." + field));
            }
        }
        assertAll(cases);
    }

    @Test
    void rejectsNegativeDimensionsAndUnsupportedCoefficientOrAngle() {
        List<Executable> cases = new ArrayList<>();
        for (String field : FIELDS) {
            cases.add(() -> rejects("restrictions:\n  road: {" + field + ": -0.1}\n", "road." + field));
        }
        cases.add(() -> rejects("restrictions:\n  road: {k_spec: 0.999}\n", "road.k_spec"));
        cases.add(() -> rejects("restrictions:\n  road: {min_angle: 90.001}\n", "road.min_angle"));
        assertAll(cases);
    }

    @Test
    void rejectsNonFiniteNumbersBeforeRouting() {
        List<Executable> cases = new ArrayList<>();
        for (String field : FIELDS) {
            for (String value : List.of(".NaN", ".inf", "-.inf", "1e400")) {
                cases.add(() -> {
                    Exception error = assertThrows(Exception.class, () -> merge(
                            "restrictions:\n  road: {" + field + ": " + value + "}\n"));
                    assertTrue(error instanceof IOException || error instanceof IllegalArgumentException);
                    if (error instanceof IllegalArgumentException) {
                        assertTrue(error.getMessage().contains("road." + field), error.getMessage());
                    }
                });
            }
        }
        assertAll(cases);
    }

    @Test
    void rejectsInvalidDocumentAndSectionShapes() {
        List<Executable> cases = new ArrayList<>();
        for (String value : List.of("", "null", "[]", "wrong", "42")) {
            cases.add(() -> rejects(value, "YAML"));
        }
        for (String value : List.of("null", "[]", "wrong", "42")) {
            cases.add(() -> rejects("restrictions: " + value, "restrictions"));
            cases.add(() -> rejects("restrictions:\n  road: " + value, "road"));
            cases.add(() -> rejects("heat_network_crossing: " + value, "heat_network"));
        }
        assertAll(cases);
    }

    @Test
    void rejectsInvalidModeWithRuleAndField() {
        List<Executable> cases = new ArrayList<>();
        for (String value : List.of("speical", "null", "true", "2", "[]")) {
            cases.add(() -> rejects("restrictions:\n  road: {mode: " + value + "}", "road.mode"));
        }
        assertAll(cases);
    }

    @Test
    void reportsUnicodeModeLookalikeWithFieldInsteadOfLeakingEnumError() {
        rejects("restrictions:\n  road: {mode: SPECİAL}", "road.mode");
    }

    @Test
    void rejectsMalformedThresholdTableAndMissingDistance() {
        List<Executable> cases = new ArrayList<>();
        for (String value : List.of("null", "{}", "1", "wrong", "[null]", "[1]",
                "[{}]", "[{max_du: 300}]")) {
            cases.add(() -> rejects("restrictions:\n  oks:\n    min_distance_by_du: " + value,
                    "oks.min_distance_by_du"));
        }
        assertAll(cases);
    }

    @Test
    void rejectsInvalidThresholdDistanceAndDuWithoutTruncation() {
        List<Executable> cases = new ArrayList<>();
        for (String value : List.of("-1", "null", "'5'", "true", "wrong", "1e400")) {
            cases.add(() -> rejects("restrictions:\n  oks:\n    min_distance_by_du:\n"
                    + "      - {max_du: 300, distance: " + value + "}", "oks.min_distance_by_du[0].distance"));
        }
        for (String value : List.of("0", "-1", "300.1", "300.0", "null", "'300'", "true",
                "2147483648", "1e400", "[]")) {
            cases.add(() -> rejects("restrictions:\n  oks:\n    min_distance_by_du:\n"
                    + "      - {max_du: " + value + ", distance: 5}", "oks.min_distance_by_du[0].max_du"));
        }
        assertAll(cases);
    }

    @Test
    void rejectsAmbiguousDuplicateThresholdsIncludingOpenEndedRows() {
        rejects("restrictions:\n  oks:\n    min_distance_by_du:\n"
                + "      - {max_du: 300, distance: 5}\n"
                + "      - {max_du: 300, distance: 7}\n", "oks.min_distance_by_du[1].max_du");
        rejects("restrictions:\n  oks:\n    min_distance_by_du:\n"
                + "      - {distance: 5}\n"
                + "      - {max_du: 2147483647, distance: 7}\n", "oks.min_distance_by_du[1].max_du");
    }

    @Test
    void validatesUnknownDefaultNewTypesAndSeparateHeatNetworkSection() {
        rejects("restrictions:\n  unknown_default: {min_distance: -1}", "unknown_default.min_distance");
        rejects("restrictions:\n  custom_road: {special_margin: -1}", "custom_road.special_margin");
        rejects("heat_network_crossing: {k_spec: 0.9}", "heat_network.k_spec");
    }

    @Test
    void rejectsUnsupportedExistingNetworkOverridesInsteadOfAdvertisingIgnoredRules() {
        rejects("heat_network_crossing: {mode: forbidden}", "heat_network.mode");
        rejects("heat_network_crossing: {min_angle: 45}", "heat_network.min_angle");
        rejects("heat_network_crossing: {own_half_width: 0.2}", "heat_network.own_half_width");
    }

    @Test
    void rejectsMisspelledFieldsWithoutRestrictingNewTypeNames() {
        rejects("restriction: {}", "YAML.restriction");
        rejects("restrictions:\n  road: {k_specs: 2}", "road.k_specs");
        rejects("restrictions:\n  road: {vertical: {min_depth: 2}}", "road.vertical.min_depth");
        rejects("restrictions:\n  road: {min_distance_by_du: [{max_dn: 300, distance: 5}]}",
                "road.min_distance_by_du[0].max_dn");
    }

    @Test
    void rejectsDuplicateKeysAndExtraYamlDocumentsInsteadOfUsingOnlyPartOfInput() {
        for (String text : List.of("restrictions:\n  road: {k_spec: 1.5, k_spec: 1.0}",
                "restrictions: {}\nrestrictions: {road: {k_spec: 1.0}}",
                "restrictions: {}\n---\nrestrictions: {road: {min_distance: 9}}")) {
            Exception error = assertThrows(Exception.class, () -> merge(text), text);
            assertTrue(error instanceof IOException || error instanceof IllegalArgumentException);
        }
    }

    @Test
    void acceptsSupportedExistingNetworkSettingsAndCoefficientsAboveBuiltInMaximum() throws Exception {
        RestrictionRule crossing = merge("heat_network_crossing:\n  mode: special\n"
                + "  min_angle: 0\n  own_half_width: 0\n  k_spec: 2.5\n").heatNetworkCrossing();
        assertEquals(2.5, crossing.getKSpec());
        assertEquals(RestrictionRule.Mode.SPECIAL, crossing.getMode());
        assertEquals(0, crossing.getMinCrossingAngleDeg());
    }

    @Test
    void acceptsEndpointsAndPreservesPartialInheritance() throws Exception {
        RestrictionRules rules = merge("restrictions:\n"
                + "  road: {mode: SPECIAL, min_distance: 0, min_angle: 90, k_spec: 1, own_half_width: 0}\n"
                + "  power_cable: {special_margin: 0, min_angle: 0}\n"
                + "  tram_tracks: {}\n");
        RestrictionRule road = rules.forType("road");
        assertEquals(RestrictionRule.Mode.SPECIAL, road.getMode());
        assertEquals(0, road.getMinHorizontalDistanceM());
        assertEquals(90, road.getMinCrossingAngleDeg());
        assertEquals(1, road.getKSpec());
        assertEquals(3, road.getSpecialMarginM());
        assertEquals(0, rules.forType("power_cable").getSpecialMarginM());
        assertEquals(0, rules.forType("power_cable").getMinCrossingAngleDeg());
        assertEquals(1.75, rules.forType("tram_tracks").getKSpec());
        assertEquals(RestrictionRules.builtIn().toMap(), merge("{}").toMap());
    }

    @Test
    void sortsValidThresholdsAndPreservesExplicitClearingAndScalarOverride() throws Exception {
        RestrictionRules rules = merge("restrictions:\n  road:\n    min_distance_by_du:\n"
                + "      - {distance: 8}\n      - {max_du: 300, distance: 0}\n"
                + "      - {max_du: 800, distance: 6}\n"
                + "  oks: {min_distance_by_du: []}\n");
        assertEquals(0, rules.forType("road").clearanceForDu(300));
        assertEquals(6, rules.forType("road").clearanceForDu(301));
        assertEquals(8, rules.forType("road").clearanceForDu(801));
        assertTrue(rules.forType("oks").getDistanceByDu().isEmpty());
        assertEquals(5, rules.forType("oks").clearanceForDu(1000));
        assertEquals(2, merge("restrictions:\n  oks: {min_distance: 2}").forType("oks").clearanceForDu(1000));
    }

    @Test
    void exportedBuiltInAndExampleYamlRemainLoadable() throws Exception {
        RestrictionRules builtIn = RestrictionRules.builtIn();
        assertEquals(builtIn.toMap(), merge(builtIn.toYaml()).toMap());
        RestrictionRules example = RestrictionRules.load(java.nio.file.Path.of("config/rules-example.yml"));
        assertEquals(1.4, example.forType("road_local").getKSpec());
        assertEquals(example.toMap(), merge(example.toYaml()).toMap());
    }
}
