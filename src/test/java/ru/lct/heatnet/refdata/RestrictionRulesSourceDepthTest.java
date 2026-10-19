package ru.lct.heatnet.refdata;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestrictionRulesSourceDepthTest {

    private static RestrictionRules merge(RestrictionRules base, String yaml) throws Exception {
        return base.mergeYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void underCrossingYamlRoundTripPreservesSourceDepthAfterPartialOverride() throws Exception {
        RestrictionRules defaults = RestrictionRules.builtIn();
        RestrictionRules configured = merge(defaults,
                "heat_network_crossing:\n"
                + "  vertical:\n"
                + "    crossing: under\n"
                + "    min_top_depth: 3.8\n"
                + "    obstacle_top_depth: 4.2\n");
        RestrictionRules layered = merge(configured,
                "heat_network_crossing:\n"
                + "  k_spec: 1.08\n");

        assertEquals(3.0, defaults.heatNetworkCrossing().getVertical().obstacleTopDepthM, 1e-9);
        assertEquals(4.2, layered.heatNetworkCrossing().getVertical().obstacleTopDepthM, 1e-9);
        RestrictionRules restored = merge(RestrictionRules.builtIn(), layered.toYaml());

        assertEquals(4.2, restored.heatNetworkCrossing().getVertical().obstacleTopDepthM, 1e-9,
                "Source depth also fixes tie-in elevations and must survive YAML export even for under crossings");
        assertTrue(restored.heatNetworkCrossing().getVertical().underOnly);
        assertEquals(3.8, restored.heatNetworkCrossing().getVertical().minTopDepthM, 1e-9);
        assertEquals(1.08, restored.heatNetworkCrossing().getKSpec(), 1e-9);
    }
}
