package ru.lct.heatnet.geo;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.util.JsonParserDelegate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.refdata.CostModel;
import ru.lct.heatnet.refdata.PipeTable;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Потоковый разбор входного GeoJSON (FeatureCollection, WGS 84).
 *
 * <p>Файл читается через Jackson streaming API объект за объектом — файл
 * целиком в память не загружается (требование ТЗ о входных файлах до 3 ГБ).
 * Каждый отдельный Feature материализуется в дерево, конвертируется в JTS
 * и сразу проецируется в EPSG:32637.</p>
 */
public class GeoJsonReader {

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final Projection projection;

    public GeoJsonReader(Projection projection) {
        this.projection = projection;
    }

    public InputModel read(InputStream in) throws IOException {
        InputModel model = new InputModel();
        List<String> problems = new ArrayList<>();
        Map<Object, Integer> ids = new HashMap<>();

        JsonFactory factory = mapper.getFactory();
        // Jackson's tree deserializer probes isNaN before reading BigDecimal.
        // The default probe converts 1e400 to double infinity and loses its
        // original finite decimal value. Strict JSON already rejects NaN/Infinity
        // tokens, so the decimal lexeme can be read directly without that probe.
        try (JsonParser parser = new JsonParserDelegate(factory.createParser(in)) {
            @Override public boolean isNaN() { return false; }
        }) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new InputValidationException(List.of("Ожидался JSON-объект FeatureCollection"));
            }
            boolean featuresSeen = false;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.getCurrentName();
                parser.nextToken();
                if ("features".equals(field)) {
                    featuresSeen = true;
                    if (parser.currentToken() != JsonToken.START_ARRAY) {
                        throw new InputValidationException(List.of("Поле features должно быть массивом"));
                    }
                    int index = 0;
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        JsonNode feature = mapper.readTree(parser);
                        try {
                            readFeature(feature, index, model, problems, ids);
                        } catch (RuntimeException e) {
                            problems.add("features[" + index + "]: " + e.getMessage());
                        }
                        index++;
                    }
                } else {
                    parser.skipChildren();
                }
            }
            if (!featuresSeen) {
                problems.add("В файле отсутствует массив features");
            }
        }

        // Если даже сумма возможных штрафов не представима, расчёт стоимости
        // не сможет корректно обработать такой файл ни при каком маршруте.
        double possiblePenalty = 0;
        for (InputModel.OksPoint point : model.oksPoints) {
            possiblePenalty += CostModel.unconnectedPenalty(point.flowTph);
            if (!Double.isFinite(possiblePenalty)) {
                problems.add("features[" + ids.get(idKey(point.id))
                        + "]: flow_tph: сумма штрафов по нагрузкам ОКС превышает численный диапазон расчёта"
                        + " (при добавлении id=" + point.id + ")");
                break;
            }
        }

        if (!problems.isEmpty()) {
            throw new InputValidationException(problems);
        }
        return model;
    }

    private void readFeature(JsonNode feature, int index, InputModel model, List<String> problems,
                             Map<Object, Integer> ids) {
        JsonNode props = feature.path("properties");
        String objectType = props.path("object_type").asText(null);
        if (objectType == null) {
            problems.add("features[" + index + "]: отсутствует обязательный атрибут object_type");
            return;
        }
        Object id = readId(props.get("id"));
        if (id == null) {
            problems.add("features[" + index + "] (" + objectType + "): отсутствует обязательный атрибут id");
            return;
        }
        Object key = idKey(id);
        Integer first = ids.putIfAbsent(key, index);
        if (first != null) {
            problems.add("features[" + index + "] (" + objectType + "): повторный id=" + id
                    + "; впервые указан в features[" + first + "]");
            return;
        }
        model.inputIds.add(id);
        JsonNode geometryNode = feature.get("geometry");

        switch (objectType) {
            case "source": {
                Point wgs = requirePoint(geometryNode, index, objectType);
                InputModel.SourcePoint s = new InputModel.SourcePoint();
                s.id = id;
                s.name = props.path("name").asText(null);
                s.wgs = wgs.getCoordinate().copy();
                s.utm = (Point) projection.projectToUtm(wgs);
                model.sources.add(s);
                break;
            }
            case "heat_network": {
                Geometry wgs = parseGeometry(geometryNode, index, objectType);
                if (!(wgs instanceof LineString)) {
                    throw new IllegalArgumentException("heat_network должен иметь геометрию LineString, получено "
                            + wgs.getGeometryType());
                }
                JsonNode d = props.get("diameter");
                if (d == null || !d.isNumber()) {
                    throw new IllegalArgumentException("heat_network id=" + id
                            + ": отсутствует числовой атрибут diameter");
                }
                int maxDu = PipeTable.grades().get(PipeTable.grades().size() - 1).getDu();
                BigDecimal diameter = d.decimalValue();
                if (diameter.signum() <= 0 || diameter.compareTo(BigDecimal.valueOf(maxDu)) > 0
                        || diameter.stripTrailingZeros().scale() > 0) {
                    throw new IllegalArgumentException("heat_network id=" + id
                            + ": diameter должен быть целым числом от 1 до " + maxDu);
                }
                InputModel.ExistingPipe pipe = new InputModel.ExistingPipe();
                pipe.id = id;
                pipe.diameter = diameter.intValueExact();
                pipe.utm = (LineString) projection.projectToUtm(wgs);
                model.pipes.add(pipe);
                break;
            }
            case "heat_chamber": {
                Point wgs = requirePoint(geometryNode, index, objectType);
                InputModel.ExistingChamber c = new InputModel.ExistingChamber();
                c.id = id;
                c.wgs = wgs.getCoordinate().copy();
                c.utm = (Point) projection.projectToUtm(wgs);
                model.chambers.add(c);
                break;
            }
            case "oks_connection_point": {
                Point wgs = requirePoint(geometryNode, index, objectType);
                JsonNode flow = props.get("flow_tph");
                if (flow == null || !flow.isNumber()) {
                    throw new IllegalArgumentException("oks_connection_point id=" + id
                            + ": отсутствует числовой атрибут flow_tph");
                }
                BigDecimal exactFlow = flow.decimalValue();
                double flowTph = exactFlow.doubleValue();
                if (exactFlow.signum() < 0 || !Double.isFinite(flowTph)
                        || (exactFlow.signum() > 0 && flowTph == 0)
                        || !Double.isFinite(CostModel.unconnectedPenalty(flowTph))) {
                    throw new IllegalArgumentException("oks_connection_point id=" + id
                            + ": flow_tph должен быть неотрицательным конечным числом"
                            + " с представимыми нагрузкой и штрафом");
                }
                InputModel.OksPoint p = new InputModel.OksPoint();
                p.id = id;
                p.flowTph = flowTph;
                p.wgs = wgs.getCoordinate().copy();
                p.utm = (Point) projection.projectToUtm(wgs);
                model.oksPoints.add(p);
                break;
            }
            case "restriction": {
                String restrictionType = props.path("restriction_type").asText(null);
                if (restrictionType == null) {
                    throw new IllegalArgumentException("restriction id=" + id
                            + ": отсутствует обязательный атрибут restriction_type");
                }
                Geometry wgs = parseGeometry(geometryNode, index, objectType);
                if (!wgs.isValid()) {
                    // Попытка починить топологию стандартным приёмом buffer(0);
                    // если не удаётся — диагностическая ошибка.
                    Geometry fixed = wgs.buffer(0);
                    if (fixed.isEmpty() || !fixed.isValid()) {
                        throw new IllegalArgumentException("restriction id=" + id
                                + ": невалидная геометрия (" + restrictionTypeInfo(wgs) + ")");
                    }
                    wgs = fixed;
                }
                InputModel.Restriction r = new InputModel.Restriction();
                r.id = id;
                r.restrictionType = restrictionType;
                r.utm = projection.projectToUtm(wgs);
                model.restrictions.add(r);
                break;
            }
            default:
                // Неизвестные типы объектов не считаются ошибкой: дополнительные
                // свойства и объекты допускаются и игнорируются.
                break;
        }
    }

    private String restrictionTypeInfo(Geometry g) {
        return g.getGeometryType() + ", " + g.getNumPoints() + " точек";
    }

    /** Сохраняет исходный JSON-тип идентификатора (строка или число). */
    static Object readId(JsonNode idNode) {
        if (idNode == null || idNode.isNull()) {
            return null;
        }
        if (idNode.isIntegralNumber()) {
            BigInteger value = idNode.bigIntegerValue();
            return value.bitLength() < 64 ? value.longValue() : value;
        }
        if (idNode.isNumber()) {
            BigDecimal value = idNode.decimalValue().stripTrailingZeros();
            if (value.scale() <= 0 && value.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) >= 0
                    && value.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0) {
                return value.longValueExact();
            }
            return value;
        }
        if (idNode.isTextual()) return idNode.textValue();
        throw new IllegalArgumentException("id должен быть строкой или числом");
    }

    private static Object idKey(Object id) {
        return id instanceof Number ? new BigDecimal(id.toString()).stripTrailingZeros() : id;
    }

    private Point requirePoint(JsonNode geometryNode, int index, String objectType) {
        Geometry g = parseGeometry(geometryNode, index, objectType);
        if (!(g instanceof Point)) {
            throw new IllegalArgumentException(objectType + " должен иметь геометрию Point, получено "
                    + g.getGeometryType());
        }
        return (Point) g;
    }

    private Geometry parseGeometry(JsonNode node, int index, String objectType) {
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException(objectType + ": отсутствует геометрия");
        }
        String type = node.path("type").asText(null);
        JsonNode coords = node.get("coordinates");
        if (type == null || coords == null) {
            throw new IllegalArgumentException(objectType + ": геометрия без type/coordinates");
        }
        switch (type) {
            case "Point":
                return geometryFactory.createPoint(coordinate(coords));
            case "LineString":
                return geometryFactory.createLineString(coordinates(coords));
            case "MultiLineString": {
                LineString[] lines = new LineString[coords.size()];
                for (int i = 0; i < coords.size(); i++) {
                    lines[i] = geometryFactory.createLineString(coordinates(coords.get(i)));
                }
                return geometryFactory.createMultiLineString(lines);
            }
            case "Polygon":
                return polygon(coords);
            case "MultiPolygon": {
                Polygon[] polygons = new Polygon[coords.size()];
                for (int i = 0; i < coords.size(); i++) {
                    polygons[i] = polygon(coords.get(i));
                }
                return geometryFactory.createMultiPolygon(polygons);
            }
            default:
                throw new IllegalArgumentException(objectType + ": неподдерживаемый тип геометрии " + type);
        }
    }

    private Polygon polygon(JsonNode rings) {
        LinearRing shell = geometryFactory.createLinearRing(coordinates(rings.get(0)));
        LinearRing[] holes = new LinearRing[Math.max(0, rings.size() - 1)];
        for (int i = 1; i < rings.size(); i++) {
            holes[i - 1] = geometryFactory.createLinearRing(coordinates(rings.get(i)));
        }
        return geometryFactory.createPolygon(shell, holes);
    }

    private Coordinate coordinate(JsonNode pair) {
        if (!pair.isArray() || pair.size() < 2) {
            throw new IllegalArgumentException("некорректная координата: " + pair);
        }
        return new Coordinate(pair.get(0).asDouble(), pair.get(1).asDouble());
    }

    private Coordinate[] coordinates(JsonNode array) {
        Coordinate[] result = new Coordinate[array.size()];
        for (int i = 0; i < array.size(); i++) {
            result[i] = coordinate(array.get(i));
        }
        return result;
    }
}
