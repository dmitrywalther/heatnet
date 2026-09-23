package ru.lct.heatnet.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Утилита включения ранее сериализованного JSON в ответ API. */
final class RawJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RawJson() {
    }

    static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return MAPPER.createObjectNode().put("raw", json);
        }
    }
}
