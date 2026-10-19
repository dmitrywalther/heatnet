package ru.lct.heatnet.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.lct.heatnet.network.VariantDiagnostic;
import ru.lct.heatnet.network.VariantResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Диагностика хранится рядом с результатом; схема БД заданий не меняется. */
public final class ResultDiagnosticIO {
    public static final String NO_VALID_BASE_MESSAGE =
            "Рассчитанные базовые варианты не прошли проверку допустимости. "
            + "Это не означает, что допустимое решение не существует.";
    public static final String NO_DEPTH_MESSAGE =
            "Результат режима с глубиной недоступен: ни один рассчитанный вариант "
            + "этого режима не прошёл проверку допустимости. Базовый результат доступен.";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ResultDiagnosticIO() { }

    public static Path sidecarPath(Path resultPath) {
        String name = resultPath.getFileName().toString().replaceAll("\\.geojson$", "");
        return resultPath.resolveSibling(name + "-diagnostics.json");
    }

    public static ArrayNode toJson(List<VariantDiagnostic> diagnostics) {
        ArrayNode array = MAPPER.createArrayNode();
        for (VariantDiagnostic d : diagnostics) {
            ObjectNode item = array.addObject();
            item.put("variant_id", d.variantId);
            item.put("mode", d.mode);
            item.put("code", d.code);
            item.put("message", d.message);
            ArrayNode ids = item.putArray("segment_ids");
            d.segmentIds.forEach(ids::add);
        }
        return array;
    }

    public static void write(Path resultPath, List<VariantDiagnostic> diagnostics) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("diagnostics", toJson(diagnostics));
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(sidecarPath(resultPath).toFile(), root);
    }

    public static ArrayNode read(Path resultPath) throws IOException {
        Path path = sidecarPath(resultPath);
        // Старые задания не имеют sidecar и остаются доступными.
        if (!Files.exists(path)) return MAPPER.createArrayNode();
        JsonNode root = MAPPER.readTree(path.toFile());
        if (root == null || !root.path("diagnostics").isArray()) {
            throw new IOException("Некорректный файл диагностики результата");
        }
        return (ArrayNode) root.get("diagnostics");
    }

    /** Прямые вызовы экспортёров также не должны выдавать недопустимый вариант. */
    public static void requireValidVariants(List<VariantResult> variants, boolean allowEmpty) {
        if (variants == null || (!allowEmpty && variants.isEmpty())) {
            throw new IllegalArgumentException("Нет допустимых вариантов для выгрузки");
        }
        for (VariantResult v : variants) {
            if (v == null || !v.isValid()) {
                throw new IllegalArgumentException("Недопустимый вариант нельзя выгружать: "
                        + (v == null ? "null" : v.variantId));
            }
        }
    }
}
