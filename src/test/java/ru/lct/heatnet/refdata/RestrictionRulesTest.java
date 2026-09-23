package ru.lct.heatnet.refdata;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RestrictionRulesTest {

    private RestrictionRules withYaml(String yaml) throws Exception {
        return RestrictionRules.builtIn().mergeYaml(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void builtInMatchesTable2() {
        RestrictionRules rules = RestrictionRules.builtIn();
        assertEquals(RestrictionRule.Mode.FORBIDDEN, rules.forType("railway").getMode());
        assertEquals(1.60, rules.forType("road").getKSpec(), 1e-9);
        assertEquals(45.0, rules.forType("road").getMinCrossingAngleDeg(), 1e-9);
        assertEquals(0.20, rules.forType("gas_pipeline").getOwnHalfWidthM(), 1e-9);
        assertEquals(1.05, rules.heatNetworkCrossing().getKSpec(), 1e-9);
        // неизвестный тип — консервативно непроходим с отступом 1 м
        assertEquals(RestrictionRule.Mode.FORBIDDEN, rules.forType("fence").getMode());
        assertEquals(1.0, rules.forType("fence").clearanceForDu(100), 1e-9);
    }

    @Test
    void yamlAddsNewTypeAndOverridesBuiltIn() throws Exception {
        RestrictionRules rules = withYaml(
                "restrictions:\n"
                + "  road_local:\n"
                + "    mode: special\n"
                + "    min_distance: 1.5\n"
                + "    min_angle: 45\n"
                + "    special_margin: 3.0\n"
                + "    k_spec: 1.40\n"
                + "  park:\n"
                + "    min_distance: 2.0\n");
        RestrictionRule local = rules.forType("road_local");
        assertEquals(RestrictionRule.Mode.SPECIAL, local.getMode());
        assertEquals(1.40, local.getKSpec(), 1e-9);
        assertEquals(45.0, local.getMinCrossingAngleDeg(), 1e-9);
        // переопределение сохраняет остальные поля встроенного правила
        RestrictionRule park = rules.forType("park");
        assertEquals(2.0, park.getMinHorizontalDistanceM(), 1e-9);
        assertEquals(RestrictionRule.Mode.FORBIDDEN, park.getMode());
        // нетронутые встроенные правила на месте
        assertEquals(1.75, rules.forType("tram_tracks").getKSpec(), 1e-9);
    }

    @Test
    void yamlConfiguresDuThresholdsAndUnknownDefault() throws Exception {
        RestrictionRules rules = withYaml(
                "restrictions:\n"
                + "  metro:\n"
                + "    mode: forbidden\n"
                + "    min_distance_by_du:\n"
                + "      - {max_du: 300, distance: 6.0}\n"
                + "      - {distance: 10.0}\n"
                + "  unknown_default:\n"
                + "    min_distance: 2.5\n");
        assertEquals(6.0, rules.forType("metro").clearanceForDu(200), 1e-9);
        assertEquals(10.0, rules.forType("metro").clearanceForDu(400), 1e-9);
        assertEquals(2.5, rules.forType("что-то новое").clearanceForDu(100), 1e-9);
    }
}
