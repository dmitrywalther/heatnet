package ru.lct.heatnet.refdata;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Проверяет действующие вертикальные правила, а не только их YAML-представление. */
class RestrictionRulesVerticalOverrideTest {

    private static final double EPS = 1e-9;

    private static RestrictionRules merge(RestrictionRules base, String yaml) throws Exception {
        return base.mergeYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void partialCableOverrideRetainsOtherVerticalFieldsAndUntouchedTramRule() throws Exception {
        RestrictionRules rules = merge(RestrictionRules.builtIn(),
                "restrictions:\n"
                + "  power_cable:\n"
                + "    k_spec: 1.3\n"
                + "    vertical:\n"
                + "      clearance: 0.7\n"
                + "  tram_tracks:\n"
                + "    special_margin: 5\n");

        RestrictionRule cable = rules.forType("power_cable");
        assertEquals(1.3, cable.getKSpec(), EPS);
        assertEquals(2.0, cable.getSpecialMarginM(), EPS);
        RestrictionRule.Vertical cv = cable.getVertical();
        assertNotNull(cv);
        assertFalse(cv.underOnly);
        assertEquals(0.7, cv.clearanceM, EPS);
        assertEquals(2.7, cv.obstacleTopDepthM, EPS);
        assertEquals(0.2, cv.obstacleHeightM, EPS);

        RestrictionRule tram = rules.forType("tram_tracks");
        assertEquals(5.0, tram.getSpecialMarginM(), EPS);
        assertEquals(1.75, tram.getKSpec(), EPS);
        assertNotNull(tram.getVertical());
        assertTrue(tram.getVertical().underOnly);
        assertEquals(1.2, tram.getVertical().minTopDepthM, EPS);
    }

    @Test
    void unknownTypeReceivesUnderConstraintFromUnknownDefault() throws Exception {
        RestrictionRules rules = merge(RestrictionRules.builtIn(),
                "restrictions:\n"
                + "  unknown_default:\n"
                + "    mode: special\n"
                + "    min_distance: 1.5\n"
                + "    min_angle: 45\n"
                + "    special_margin: 3\n"
                + "    k_spec: 1.75\n"
                + "    vertical: {crossing: under, min_top_depth: 3.8}\n");

        RestrictionRule unknown = rules.forType("tram_custom");
        assertEquals("tram_custom", unknown.getType());
        assertEquals(RestrictionRule.Mode.SPECIAL, unknown.getMode());
        assertEquals(3.0, unknown.getSpecialMarginM(), EPS);
        assertEquals(1.75, unknown.getKSpec(), EPS);
        assertNotNull(unknown.getVertical(),
                "unknown_default.vertical must reach the rule used by ObstacleIndex");
        assertTrue(unknown.getVertical().underOnly);
        assertEquals(3.8, unknown.getVertical().minTopDepthM, EPS);

        // An explicit built-in rule must not be replaced by the unknown fallback.
        assertEquals(1.2, rules.forType("tram_tracks").getVertical().minTopDepthM, EPS);
    }

    @Test
    void unknownTypeReceivesBothSidesAndPartialUpdatesOfUnknownDefault() throws Exception {
        RestrictionRules first = merge(RestrictionRules.builtIn(),
                "restrictions:\n"
                + "  unknown_default:\n"
                + "    mode: special\n"
                + "    vertical:\n"
                + "      crossing: above_or_below\n"
                + "      clearance: 0.7\n"
                + "      obstacle_top_depth: 2.9\n"
                + "      obstacle_height: 0.3\n");
        RestrictionRules second = merge(first,
                "restrictions:\n"
                + "  unknown_default:\n"
                + "    vertical: {clearance: 0.8}\n");

        RestrictionRule.Vertical v = second.forType("custom_cable").getVertical();
        assertNotNull(v);
        assertFalse(v.underOnly);
        assertEquals(0.8, v.clearanceM, EPS);
        assertEquals(2.9, v.obstacleTopDepthM, EPS);
        assertEquals(0.3, v.obstacleHeightM, EPS);
        assertEquals(0.7, first.forType("custom_cable").getVertical().clearanceM, EPS,
                "merging a job override must not mutate the application defaults");
    }

    @Test
    void exportedYamlRoundTripPreservesEffectiveNamedUnknownAndHeatNetworkRules() throws Exception {
        RestrictionRules before = merge(RestrictionRules.builtIn(),
                "heat_network_crossing:\n"
                + "  vertical: {clearance: 0.65}\n"
                + "restrictions:\n"
                + "  power_cable:\n"
                + "    special_margin: 4\n"
                + "    vertical: {clearance: 0.7, obstacle_top_depth: 2.9}\n"
                + "  tram_tracks:\n"
                + "    vertical: {min_top_depth: 3.8}\n"
                + "  unknown_default:\n"
                + "    mode: special\n"
                + "    vertical: {crossing: under, min_top_depth: 3.6}\n");
        RestrictionRules after = merge(RestrictionRules.builtIn(), before.toYaml());

        assertEquals(before.toMap(), after.toMap());
        assertEquals(0.7, after.forType("power_cable").getVertical().clearanceM, EPS);
        assertEquals(2.9, after.forType("power_cable").getVertical().obstacleTopDepthM, EPS);
        assertEquals(3.8, after.forType("tram_tracks").getVertical().minTopDepthM, EPS);
        assertEquals(0.65, after.heatNetworkCrossing().getVertical().clearanceM, EPS);
        assertNotNull(after.forType("unlisted_crossing").getVertical());
        assertTrue(after.forType("unlisted_crossing").getVertical().underOnly);
        assertEquals(3.6, after.forType("unlisted_crossing").getVertical().minTopDepthM, EPS);
    }
}
