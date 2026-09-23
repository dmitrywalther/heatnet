package ru.lct.heatnet.geo;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.network.VariantResult;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Потоковая выгрузка результата в GeoJSON (FeatureCollection, WGS 84)
 * по разделу 7 технического приложения.
 */
public class GeoJsonWriter {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Projection projection;

    public GeoJsonWriter(Projection projection) {
        this.projection = projection;
    }

    public void write(List<VariantResult> variants, OutputStream out) throws IOException {
        try (JsonGenerator gen = mapper.getFactory().createGenerator(out)) {
            gen.useDefaultPrettyPrinter();
            gen.writeStartObject();
            gen.writeStringField("type", "FeatureCollection");
            gen.writeArrayFieldStart("features");
            for (VariantResult v : variants) {
                writeVariant(gen, v);
            }
            gen.writeEndArray();
            gen.writeEndObject();
        }
    }

    private void writeVariant(JsonGenerator gen, VariantResult v) throws IOException {
        for (VariantResult.Segment s : v.segments) {
            gen.writeStartObject();
            gen.writeStringField("type", "Feature");
            gen.writeObjectFieldStart("properties");
            gen.writeStringField("id", s.id);
            gen.writeStringField("object_type", "heat_network");
            gen.writeStringField("variant_id", v.variantId);
            writeIdField(gen, "start_node_id", s.startNodeId);
            writeIdField(gen, "end_node_id", s.endNodeId);
            gen.writeNumberField("flow_tph", s.flowTph);
            gen.writeNumberField("diameter", s.diameter);
            gen.writeNumberField("length", s.lengthM);
            gen.writeStringField("laying_method", s.special ? "special" : "base");
            if (s.depthStart == null) {
                gen.writeNullField("depth_start");
            } else {
                gen.writeNumberField("depth_start", s.depthStart);
            }
            if (s.depthEnd == null) {
                gen.writeNullField("depth_end");
            } else {
                gen.writeNumberField("depth_end", s.depthEnd);
            }
            writeCost(gen, "cost", s.cost);
            gen.writeEndObject();

            gen.writeObjectFieldStart("geometry");
            gen.writeStringField("type", "LineString");
            gen.writeArrayFieldStart("coordinates");
            List<Coordinate> coords = s.utmCoords;
            for (int i = 0; i < coords.size(); i++) {
                Coordinate wgs;
                if (i == 0 && s.startWgs != null) {
                    wgs = s.startWgs;
                } else if (i == coords.size() - 1 && s.endWgs != null) {
                    wgs = s.endWgs;
                } else {
                    Coordinate c = coords.get(i);
                    wgs = projection.utmToWgs(c.x, c.y);
                }
                gen.writeStartArray();
                gen.writeNumber(round9(wgs.x));
                gen.writeNumber(round9(wgs.y));
                gen.writeEndArray();
            }
            gen.writeEndArray();
            gen.writeEndObject();
            gen.writeEndObject();
        }

        for (VariantResult.Chamber c : v.chambers) {
            gen.writeStartObject();
            gen.writeStringField("type", "Feature");
            gen.writeObjectFieldStart("properties");
            gen.writeStringField("id", c.id);
            gen.writeStringField("object_type", "heat_chamber");
            gen.writeStringField("variant_id", v.variantId);
            gen.writeNumberField("diameter", c.diameter);
            writeCost(gen, "cost", c.cost);
            gen.writeEndObject();
            writePointGeometry(gen, c.utm);
            gen.writeEndObject();
        }

        for (VariantResult.TechNode t : v.techNodes) {
            gen.writeStartObject();
            gen.writeStringField("type", "Feature");
            gen.writeObjectFieldStart("properties");
            gen.writeStringField("id", t.id);
            gen.writeStringField("object_type", "technical_node");
            gen.writeStringField("variant_id", v.variantId);
            gen.writeEndObject();
            writePointGeometry(gen, t.utm);
            gen.writeEndObject();
        }

        // сводка варианта
        gen.writeStartObject();
        gen.writeStringField("type", "Feature");
        gen.writeObjectFieldStart("properties");
        gen.writeStringField("id", v.variantId + "_summary");
        gen.writeStringField("object_type", "variant_summary");
        gen.writeStringField("variant_id", v.variantId);
        gen.writeNumberField("rank", v.rank);
        writeCost(gen, "construction_cost", v.constructionCost);
        writeCost(gen, "chamber_construction_cost", v.chamberConstructionCost);
        gen.writeNumberField("existing_chamber_tie_in_count", v.existingChamberTieInCount);
        writeCost(gen, "existing_chamber_tie_in_cost", v.existingChamberTieInCost);
        writeCost(gen, "unconnected_penalty", v.unconnectedPenalty);
        writeCost(gen, "calculated_cost", v.calculatedCost);
        gen.writeNumberField("new_network_length", v.newNetworkLength);
        gen.writeNumberField("score", v.score);
        gen.writeArrayFieldStart("unconnected_oks_ids");
        for (Object id : v.unconnectedOksIds) {
            writeIdValue(gen, id);
        }
        gen.writeEndArray();
        gen.writeEndObject();
        gen.writeNullField("geometry");
        gen.writeEndObject();
    }

    private void writePointGeometry(JsonGenerator gen, Coordinate utm) throws IOException {
        Coordinate wgs = projection.utmToWgs(utm.x, utm.y);
        gen.writeObjectFieldStart("geometry");
        gen.writeStringField("type", "Point");
        gen.writeArrayFieldStart("coordinates");
        gen.writeNumber(round9(wgs.x));
        gen.writeNumber(round9(wgs.y));
        gen.writeEndArray();
        gen.writeEndObject();
    }

    /** Идентификаторы сохраняют исходный JSON-тип (строка или число). */
    private void writeIdField(JsonGenerator gen, String field, Object id) throws IOException {
        gen.writeFieldName(field);
        writeIdValue(gen, id);
    }

    private void writeIdValue(JsonGenerator gen, Object id) throws IOException {
        if (id instanceof Long) {
            gen.writeNumber((Long) id);
        } else if (id instanceof Double) {
            gen.writeNumber((Double) id);
        } else if (id instanceof Integer) {
            gen.writeNumber((Integer) id);
        } else if (id == null) {
            gen.writeNull();
        } else {
            gen.writeString(String.valueOf(id));
        }
    }

    /** Целые стоимости выводятся без дробной части. */
    private void writeCost(JsonGenerator gen, String field, double value) throws IOException {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            gen.writeNumberField(field, (long) value);
        } else {
            gen.writeNumberField(field, value);
        }
    }

    private static double round9(double v) {
        return Math.round(v * 1e9) / 1e9;
    }
}
