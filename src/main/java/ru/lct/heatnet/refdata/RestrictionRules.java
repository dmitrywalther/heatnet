package ru.lct.heatnet.refdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    private static final Set<String> RULE_FIELDS = Set.of("mode", "min_distance",
            "min_distance_by_du", "min_angle", "special_margin", "k_spec", "own_half_width", "vertical");
    private static final Set<String> VERTICAL_FIELDS = Set.of("crossing", "min_top_depth",
            "clearance", "obstacle_top_depth", "obstacle_height");

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
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
                .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        JsonNode root = yaml.readTree(in);
        validateObject(root, "YAML", Set.of("restrictions", "heat_network_crossing"));
        Map<String, RestrictionRule> merged = new HashMap<>(rules);
        RestrictionRule unknown = unknownDefault;
        RestrictionRule crossing = heatNetworkCrossing;

        JsonNode restrictions = root.path("restrictions");
        if (root.has("restrictions") && !restrictions.isObject()) {
            throw invalidRule("YAML", "restrictions", "ожидается объект правил");
        }
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
            // Геометрия исходной трубы имеет габарит по её ДУ и всегда допускает
            // специальное пересечение без углового порога. Не рекламируем no-op.
            if (crossing.getMode() != RestrictionRule.Mode.SPECIAL) {
                throw invalidRule("heat_network", "mode", "поддерживается только special");
            }
            if (crossing.getMinCrossingAngleDeg() != null && crossing.getMinCrossingAngleDeg() > 0) {
                throw invalidRule("heat_network", "min_angle", "угловое ограничение исходной теплосети не поддерживается");
            }
            if (crossing.getOwnHalfWidthM() > 0) {
                throw invalidRule("heat_network", "own_half_width", "габарит исходной трубы определяется по её ДУ");
            }
        }
        return new RestrictionRules(merged, unknown, crossing);
    }

    private static RestrictionRule parseRule(String type, JsonNode node, RestrictionRule base) {
        validateObject(node, type, RULE_FIELDS);
        RestrictionRule.Mode mode = base != null ? base.getMode() : RestrictionRule.Mode.FORBIDDEN;
        if (node.has("mode")) {
            JsonNode value = node.get("mode");
            String normalized = value.isTextual() ? value.asText().toLowerCase(Locale.ROOT) : "";
            if (!("special".equals(normalized) || "forbidden".equals(normalized))) {
                throw invalidRule(type, "mode", "ожидается forbidden или special");
            }
            mode = "special".equals(normalized) ? RestrictionRule.Mode.SPECIAL : RestrictionRule.Mode.FORBIDDEN;
        }
        double minDistance = horizontalNumber(node, "min_distance",
                base != null ? base.getMinHorizontalDistanceM() : 1.0, type, 0, Double.MAX_VALUE);
        Double minAngle = node.has("min_angle")
                ? (Double) horizontalNumber(node, "min_angle", 0, type, 0, 90)
                : (base != null ? base.getMinCrossingAngleDeg() : null);
        double margin = horizontalNumber(node, "special_margin",
                base != null ? base.getSpecialMarginM() : 2.0, type, 0, Double.MAX_VALUE);
        double kSpec = horizontalNumber(node, "k_spec",
                base != null ? base.getKSpec() : 1.0, type, 1, Double.MAX_VALUE);
        double ownHalf = horizontalNumber(node, "own_half_width",
                base != null ? base.getOwnHalfWidthM() : 0, type, 0, Double.MAX_VALUE);

        List<RestrictionRule.DuThreshold> thresholds = null;
        if (node.has("min_distance_by_du")) {
            JsonNode table = node.get("min_distance_by_du");
            if (!table.isArray()) {
                throw invalidRule(type, "min_distance_by_du", "ожидается массив порогов");
            }
            thresholds = new ArrayList<>();
            Set<Integer> limits = new HashSet<>();
            for (int i = 0; i < table.size(); i++) {
                JsonNode t = table.get(i);
                String path = type + ".min_distance_by_du[" + i + "]";
                validateObject(t, path, Set.of("max_du", "distance"));
                int maxDu = Integer.MAX_VALUE;
                if (t.has("max_du")) {
                    JsonNode limit = t.get("max_du");
                    if (!limit.isIntegralNumber() || !limit.canConvertToInt() || limit.asInt() <= 0) {
                        throw invalidRule(path, "max_du", "ожидается целое число от 1 до 2147483647");
                    }
                    maxDu = limit.asInt();
                }
                if (!limits.add(maxDu)) {
                    throw invalidRule(path, "max_du", "повторная граница диапазона ДУ");
                }
                if (!t.has("distance")) {
                    throw invalidRule(path, "distance", "обязательное поле");
                }
                thresholds.add(new RestrictionRule.DuThreshold(maxDu,
                        horizontalNumber(t, "distance", 0, path, 0, Double.MAX_VALUE)));
            }
            thresholds.sort(Comparator.comparingInt(t -> t.maxDu));
        } else if (base != null && !node.has("min_distance")) {
            thresholds = base.getDistanceByDu();
        }

        RestrictionRule.Vertical vertical = base != null ? base.getVertical() : null;
        if (node.has("vertical")) {
            JsonNode v = node.get("vertical");
            validateObject(v, type + ".vertical", VERTICAL_FIELDS);
            boolean underOnly = vertical != null && vertical.underOnly;
            if (v.has("crossing")) {
                JsonNode crossingValue = v.get("crossing");
                if (!crossingValue.isTextual() || !("under".equals(crossingValue.asText())
                        || "above_or_below".equals(crossingValue.asText()))) {
                    throw invalidRule(type, "vertical.crossing", "ожидается under или above_or_below");
                }
                underOnly = "under".equals(crossingValue.asText());
            }
            vertical = new RestrictionRule.Vertical(
                    underOnly,
                    verticalNumber(v, "min_top_depth", vertical != null ? vertical.minTopDepthM : 1.0, type),
                    verticalNumber(v, "clearance", vertical != null ? vertical.clearanceM : 0.5, type),
                    verticalNumber(v, "obstacle_top_depth", vertical != null ? vertical.obstacleTopDepthM : 3.0, type),
                    verticalNumber(v, "obstacle_height", vertical != null ? vertical.obstacleHeightM : 0.3, type));
        }
        return new RestrictionRule(type, mode, minDistance, thresholds, minAngle,
                margin, kSpec, ownHalf, vertical);
    }

    private static void validateObject(JsonNode node, String path, Set<String> fields) {
        if (node == null || !node.isObject()) {
            throw invalidRule(path, "", "ожидается объект");
        }
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            String name = it.next();
            if (!fields.contains(name)) {
                throw invalidRule(path, name, "неизвестное поле");
            }
        }
    }

    private static double horizontalNumber(JsonNode node, String field, double inherited,
                                           String path, double min, double max) {
        if (!node.has(field)) return inherited;
        JsonNode value = node.get(field);
        if (!value.isNumber() || !Double.isFinite(value.asDouble())
                || value.asDouble() < min || value.asDouble() > max) {
            String range = max == Double.MAX_VALUE ? ">= " + min : "от " + min + " до " + max;
            throw invalidRule(path, field, "ожидается конечное число " + range);
        }
        return value.asDouble();
    }

    private static double verticalNumber(JsonNode node, String field, double inherited, String type) {
        if (!node.has(field)) {
            return inherited;
        }
        JsonNode value = node.get(field);
        if (!value.isNumber() || !Double.isFinite(value.asDouble()) || value.asDouble() < 0) {
            throw invalidRule(type, "vertical." + field, "ожидается конечное неотрицательное число");
        }
        return value.asDouble();
    }

    private static IllegalArgumentException invalidRule(String type, String field, String reason) {
        return new IllegalArgumentException("Некорректное правило " + type
                + (field.isEmpty() ? "" : "." + field) + ": " + reason);
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
                unknownDefault.getKSpec(), unknownDefault.getOwnHalfWidthM(), unknownDefault.getVertical());
    }

    /** Пересечение исходной сети; obstacleTopDepth также задаёт отметки врезок в неё. */
    public RestrictionRule heatNetworkCrossing() {
        return heatNetworkCrossing;
    }

    public int size() {
        return rules.size();
    }

    /** Действующий справочник как структура — для JSON API и сериализации. */
    public Map<String, Object> toMap() {
        Map<String, Object> root = new java.util.LinkedHashMap<>();
        Map<String, Object> restrictions = new java.util.LinkedHashMap<>();
        java.util.List<String> keys = new java.util.ArrayList<>(rules.keySet());
        java.util.Collections.sort(keys);
        for (String key : keys) {
            restrictions.put(key, ruleToMap(rules.get(key), false));
        }
        restrictions.put("unknown_default", ruleToMap(unknownDefault, false));
        // restrictions — последней секцией: правило, дописанное в конец файла
        // (типовая правка в UI), попадает внутрь неё, а не в crossing-секцию
        root.put("heat_network_crossing", ruleToMap(heatNetworkCrossing, true));
        root.put("restrictions", restrictions);
        return root;
    }

    /** Действующий справочник в YAML — для просмотра и правки. */
    public String toYaml() {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory()
                .disable(com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature.WRITE_DOC_START_MARKER));
        try {
            return yaml.writeValueAsString(toMap());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Не удалось сериализовать справочник", e);
        }
    }

    private static Map<String, Object> ruleToMap(RestrictionRule rule, boolean existingNetwork) {
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
                // Для исходной сети H остаётся активной отметкой врезок и при under.
                if (existingNetwork) vm.put("obstacle_top_depth", v.obstacleTopDepthM);
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
