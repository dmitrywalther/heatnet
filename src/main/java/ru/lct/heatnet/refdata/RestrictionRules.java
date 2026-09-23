package ru.lct.heatnet.refdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Справочник правил пространственных ограничений.
 *
 * <p>Встроенные значения соответствуют таблице 2 технического приложения.
 * Необязательный YAML-файл расширяет или переопределяет их без изменения кода:
 * город может добавить собственные типы ограничений (например, разные классы
 * дорог) или скорректировать отступы и коэффициенты. Формат — см.
 * config/rules-example.yml в репозитории.</p>
 */
public final class RestrictionRules {

    private static final Logger log = LoggerFactory.getLogger(RestrictionRules.class);

    private final Map<String, RestrictionRule> rules;
    private final RestrictionRule unknownDefault;
    private final RestrictionRule heatNetworkCrossing;

    private RestrictionRules(Map<String, RestrictionRule> rules,
                             RestrictionRule unknownDefault,
                             RestrictionRule heatNetworkCrossing) {
        this.rules = rules;
        this.unknownDefault = unknownDefault;
        this.heatNetworkCrossing = heatNetworkCrossing;
    }

    /** Встроенный справочник — таблица 2 техприложения. */
    public static RestrictionRules builtIn() {
        Map<String, RestrictionRule> map = new HashMap<>();
        List<RestrictionRule.DuThreshold> oksThresholds = List.of(
                new RestrictionRule.DuThreshold(499, 5.0),
                new RestrictionRule.DuThreshold(800, 7.0),
                new RestrictionRule.DuThreshold(Integer.MAX_VALUE, 9.0));
        put(map, new RestrictionRule("oks", RestrictionRule.Mode.FORBIDDEN,
                5.0, oksThresholds, null, 0, 1.0, 0));
        put(map, forbidden("park", 1.0));
        put(map, forbidden("social_area", 1.0));
        put(map, forbidden("prohibited_site", 1.0));
        put(map, forbidden("water", 1.0));
        put(map, forbidden("railway", 1.0));
        put(map, new RestrictionRule("road", RestrictionRule.Mode.SPECIAL,
                1.5, null, 45.0, 3.0, 1.60, 0,
                new RestrictionRule.Vertical(true, 1.0, 0, 0, 0)));
        put(map, new RestrictionRule("tram_tracks", RestrictionRule.Mode.SPECIAL,
                1.5, null, 45.0, 3.0, 1.75, 0,
                new RestrictionRule.Vertical(true, 1.2, 0, 0, 0)));
        put(map, new RestrictionRule("gas_pipeline", RestrictionRule.Mode.SPECIAL,
                2.0, null, null, 2.0, 1.25, 0.20,
                new RestrictionRule.Vertical(false, 0, 0.2, 2.8, 0.40)));
        put(map, new RestrictionRule("power_cable", RestrictionRule.Mode.SPECIAL,
                2.0, null, null, 2.0, 1.15, 0.10,
                new RestrictionRule.Vertical(false, 0, 0.5, 2.7, 0.20)));

        RestrictionRule unknown = forbidden("unknown", 1.0);
        // высота габарита существующей теплосети зависит от её ДУ и
        // подставляется по месту в ObstacleIndex
        RestrictionRule crossing = new RestrictionRule("heat_network",
                RestrictionRule.Mode.SPECIAL, 1.0, null, null, 2.0, 1.05, 0,
                new RestrictionRule.Vertical(false, 0, 0.5, 3.0, 0));
        return new RestrictionRules(map, unknown, crossing);
    }

    /** Встроенный справочник, дополненный YAML-файлом (файл может отсутствовать). */
    public static RestrictionRules load(Path yamlFile) throws IOException {
        RestrictionRules base = builtIn();
        if (yamlFile == null) {
            return base;
        }
        try (InputStream in = Files.newInputStream(yamlFile)) {
            RestrictionRules merged = base.mergeYaml(in);
            log.info("Справочник ограничений дополнен из {}: {} типов",
                    yamlFile, merged.rules.size());
            return merged;
        }
    }

    /** Слияние YAML поверх текущего справочника (для тестов — из потока). */
    public RestrictionRules mergeYaml(InputStream in) throws IOException {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        JsonNode root = yaml.readTree(in);
        Map<String, RestrictionRule> merged = new HashMap<>(rules);
        RestrictionRule unknown = unknownDefault;
        RestrictionRule crossing = heatNetworkCrossing;

        JsonNode restrictions = root.path("restrictions");
        for (Iterator<String> it = restrictions.fieldNames(); it.hasNext(); ) {
            String type = it.next();
            RestrictionRule current = "unknown_default".equals(type) ? unknown : merged.get(type);
            RestrictionRule parsed = parseRule(type, restrictions.get(type), current);
            if ("unknown_default".equals(type)) {
                unknown = parsed;
            } else {
                merged.put(type, parsed);
            }
        }
        if (root.has("heat_network_crossing")) {
            crossing = parseRule("heat_network", root.get("heat_network_crossing"), crossing);
        }
        return new RestrictionRules(merged, unknown, crossing);
    }

    private static RestrictionRule parseRule(String type, JsonNode node, RestrictionRule base) {
        RestrictionRule.Mode mode = node.has("mode")
                ? RestrictionRule.Mode.valueOf(node.get("mode").asText().toUpperCase())
                : (base != null ? base.getMode() : RestrictionRule.Mode.FORBIDDEN);
        double minDistance = node.has("min_distance")
                ? node.get("min_distance").asDouble()
                : (base != null ? base.getMinHorizontalDistanceM() : 1.0);
        Double minAngle = node.has("min_angle")
                ? (Double) node.get("min_angle").asDouble()
                : (base != null ? base.getMinCrossingAngleDeg() : null);
        double margin = node.has("special_margin")
                ? node.get("special_margin").asDouble()
                : (base != null ? base.getSpecialMarginM() : 2.0);
        double kSpec = node.has("k_spec")
                ? node.get("k_spec").asDouble()
                : (base != null ? base.getKSpec() : 1.0);
        double ownHalf = node.has("own_half_width")
                ? node.get("own_half_width").asDouble()
                : (base != null ? base.getOwnHalfWidthM() : 0);

        List<RestrictionRule.DuThreshold> thresholds = null;
        if (node.has("min_distance_by_du")) {
            thresholds = new ArrayList<>();
            for (JsonNode t : node.get("min_distance_by_du")) {
                int maxDu = t.has("max_du") ? t.get("max_du").asInt() : Integer.MAX_VALUE;
                thresholds.add(new RestrictionRule.DuThreshold(maxDu, t.get("distance").asDouble()));
            }
            thresholds.sort(Comparator.comparingInt(t -> t.maxDu));
        } else if (base != null && !node.has("min_distance")) {
            thresholds = base.getDistanceByDu();
        }

        RestrictionRule.Vertical vertical = base != null ? base.getVertical() : null;
        if (node.has("vertical")) {
            JsonNode v = node.get("vertical");
            boolean underOnly = "under".equals(v.path("crossing").asText(
                    vertical != null && vertical.underOnly ? "under" : "above_or_below"));
            vertical = new RestrictionRule.Vertical(
                    underOnly,
                    v.path("min_top_depth").asDouble(vertical != null ? vertical.minTopDepthM : 1.0),
                    v.path("clearance").asDouble(vertical != null ? vertical.clearanceM : 0.5),
                    v.path("obstacle_top_depth").asDouble(vertical != null ? vertical.obstacleTopDepthM : 3.0),
                    v.path("obstacle_height").asDouble(vertical != null ? vertical.obstacleHeightM : 0.3));
        }
        return new RestrictionRule(type, mode, minDistance, thresholds, minAngle,
                margin, kSpec, ownHalf, vertical);
    }

    private static RestrictionRule forbidden(String type, double distance) {
        return new RestrictionRule(type, RestrictionRule.Mode.FORBIDDEN,
                distance, null, null, 0, 1.0, 0);
    }

    private static void put(Map<String, RestrictionRule> map, RestrictionRule rule) {
        map.put(rule.getType(), rule);
    }

    /**
     * Правило для типа ограничения. Неизвестные типы получают консервативное
     * правило по умолчанию (настраивается ключом unknown_default) —
     * сервис не падает на расширенных наборах.
     */
    public RestrictionRule forType(String restrictionType) {
        RestrictionRule rule = rules.get(restrictionType);
        if (rule != null) {
            return rule;
        }
        return new RestrictionRule(restrictionType, unknownDefault.getMode(),
                unknownDefault.getMinHorizontalDistanceM(), unknownDefault.getDistanceByDu(),
                unknownDefault.getMinCrossingAngleDeg(), unknownDefault.getSpecialMarginM(),
                unknownDefault.getKSpec(), unknownDefault.getOwnHalfWidthM());
    }

    /** Пересечение существующей тепловой сети без врезки (строка таблицы 2). */
    public RestrictionRule heatNetworkCrossing() {
        return heatNetworkCrossing;
    }

    public int size() {
        return rules.size();
    }

    /** Действующий справочник в YAML — для просмотра и правки в UI. */
    public String toYaml() {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory()
                .disable(com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature.WRITE_DOC_START_MARKER));
        Map<String, Object> root = new java.util.LinkedHashMap<>();
        Map<String, Object> restrictions = new java.util.LinkedHashMap<>();
        java.util.List<String> keys = new java.util.ArrayList<>(rules.keySet());
        java.util.Collections.sort(keys);
        for (String key : keys) {
            restrictions.put(key, ruleToMap(rules.get(key)));
        }
        restrictions.put("unknown_default", ruleToMap(unknownDefault));
        // restrictions — последней секцией: правило, дописанное в конец файла
        // (типовая правка в UI), попадает внутрь неё, а не в crossing-секцию
        root.put("heat_network_crossing", ruleToMap(heatNetworkCrossing));
        root.put("restrictions", restrictions);
        try {
            return yaml.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Не удалось сериализовать справочник", e);
        }
    }

    private static Map<String, Object> ruleToMap(RestrictionRule rule) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("mode", rule.getMode() == RestrictionRule.Mode.FORBIDDEN ? "forbidden" : "special");
        if (rule.getDistanceByDu().isEmpty()) {
            map.put("min_distance", rule.getMinHorizontalDistanceM());
        } else {
            java.util.List<Map<String, Object>> thresholds = new java.util.ArrayList<>();
            for (RestrictionRule.DuThreshold t : rule.getDistanceByDu()) {
                Map<String, Object> tm = new java.util.LinkedHashMap<>();
                if (t.maxDu != Integer.MAX_VALUE) {
                    tm.put("max_du", t.maxDu);
                }
                tm.put("distance", t.distanceM);
                thresholds.add(tm);
            }
            map.put("min_distance_by_du", thresholds);
        }
        if (rule.getMinCrossingAngleDeg() != null) {
            map.put("min_angle", rule.getMinCrossingAngleDeg());
        }
        if (rule.getMode() == RestrictionRule.Mode.SPECIAL) {
            map.put("special_margin", rule.getSpecialMarginM());
            map.put("k_spec", rule.getKSpec());
        }
        if (rule.getOwnHalfWidthM() > 0) {
            map.put("own_half_width", rule.getOwnHalfWidthM());
        }
        RestrictionRule.Vertical v = rule.getVertical();
        if (v != null) {
            Map<String, Object> vm = new java.util.LinkedHashMap<>();
            vm.put("crossing", v.underOnly ? "under" : "above_or_below");
            if (v.underOnly) {
                vm.put("min_top_depth", v.minTopDepthM);
            } else {
                vm.put("clearance", v.clearanceM);
                vm.put("obstacle_top_depth", v.obstacleTopDepthM);
                vm.put("obstacle_height", v.obstacleHeightM);
            }
            map.put("vertical", vm);
        }
        return map;
    }
}
