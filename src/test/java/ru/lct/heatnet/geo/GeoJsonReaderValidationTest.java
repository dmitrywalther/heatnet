package ru.lct.heatnet.geo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.lct.heatnet.model.InputModel;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class GeoJsonReaderValidationTest {
    private final GeoJsonReader reader = new GeoJsonReader(new Projection());

    private InputModel read(String... features) throws Exception {
        String json = "{\"type\":\"FeatureCollection\",\"features\":[" + String.join(",", features) + "]}";
        return reader.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static String feature(String id, String type, String extra) {
        String geometry = "heat_network".equals(type)
                ? "{\"type\":\"LineString\",\"coordinates\":[[37.60,55.75],[37.60,55.751]]}"
                : "{\"type\":\"Point\",\"coordinates\":[37.601,55.7505]}";
        return "{\"type\":\"Feature\",\"properties\":{\"id\":" + id
                + ",\"object_type\":\"" + type + "\"" + extra + "},\"geometry\":" + geometry + "}";
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "50.5", "1401", "2147483648", "4294967346", "1e400", "\"50\"", "null", "true"})
    void rejectsUnsupportedDiameterWithoutTruncating(String diameter) {
        InputValidationException error = assertThrows(InputValidationException.class,
                () -> read(feature("17", "heat_network", ",\"diameter\":" + diameter)));
        String message = String.join(";", error.getProblems());
        assertTrue(message.contains("features[0]"), message);
        assertTrue(message.contains("diameter"), message);
        assertTrue(message.contains("17"), message);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "50", "51", "1400", "50.0", "5e1"})
    void preservesSupportedDiameter(String diameter) throws Exception {
        assertEquals(new BigDecimal(diameter).intValueExact(),
                read(feature("1", "heat_network", ",\"diameter\":" + diameter)).pipes.get(0).diameter);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "-1e-400", "1e-400", "1e400", "1e308", "\"20\"", "null", "false"})
    void rejectsInvalidOrUnrepresentableFlow(String flow) {
        InputValidationException error = assertThrows(InputValidationException.class,
                () -> read(feature("23", "oks_connection_point", ",\"flow_tph\":" + flow)));
        String message = String.join(";", error.getProblems());
        assertTrue(message.contains("flow_tph"), message);
        assertTrue(message.contains("features[0]"), message);
        assertTrue(message.contains("23"), message);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-0.0", "0.000001", "22501.9", "22501.9001", "1e15", "1e302"})
    void keepsNonnegativeFiniteLoadsIncludingUnconnectableOnes(String flow) throws Exception {
        assertEquals(Double.parseDouble(flow) == 0 ? 0 : Double.parseDouble(flow),
                read(feature("1", "oks_connection_point", ",\"flow_tph\":" + flow)).oksPoints.get(0).flowTph);
    }

    @Test
    void rejectsOverflowOfSumOfPossiblePenalties() {
        InputValidationException error = assertThrows(InputValidationException.class, () -> read(
                feature("1", "oks_connection_point", ",\"flow_tph\":2e302"),
                feature("2", "oks_connection_point", ",\"flow_tph\":2e302")));
        assertTrue(String.join(";", error.getProblems()).contains("flow_tph"));
        assertTrue(String.join(";", error.getProblems()).contains("features[1]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false", "[]", "{}", "null"})
    void rejectsIdsOutsideStringOrNumber(String id) {
        InputValidationException error = assertThrows(InputValidationException.class,
                () -> read(feature(id, "source", "")));
        assertTrue(String.join(";", error.getProblems()).contains("id"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "1.0", "1e0"})
    void rejectsNumericDuplicatesAcrossObjectTypes(String secondId) {
        InputValidationException error = assertThrows(InputValidationException.class, () -> read(
                feature("1", "source", ""), feature(secondId, "oks_connection_point", ",\"flow_tph\":1")));
        String message = String.join(";", error.getProblems());
        assertTrue(message.contains("features[0]") && message.contains("features[1]"), message);
        assertTrue(message.contains("id"), message);
    }

    @Test
    void rejectsSameTypeDuplicateAndDoesNotLeakIdsBetweenReads() throws Exception {
        String point = feature("\"o1\"", "oks_connection_point", ",\"flow_tph\":1");
        assertThrows(InputValidationException.class, () -> read(point, point));
        assertEquals(1, read(point).oksPoints.size());
        assertEquals(1, read(point).oksPoints.size());
    }

    @Test
    void preservesDistinctExactIdsAndUninterpretedStringForms() throws Exception {
        InputModel model = read(feature("1", "source", ""), feature("\"1\"", "source", ""),
                feature("9223372036854775808", "source", ""), feature("9223372036854775809", "source", ""),
                feature("0.1000000000000000001", "source", ""), feature("0.1", "source", ""),
                feature("\"\"", "source", ""), feature("-1", "source", ""));
        assertEquals(1L, model.sources.get(0).id);
        assertEquals("1", model.sources.get(1).id);
        assertEquals(new BigInteger("9223372036854775808"), model.sources.get(2).id);
        assertEquals(new BigInteger("9223372036854775809"), model.sources.get(3).id);
        assertEquals(new BigDecimal("0.1000000000000000001"), model.sources.get(4).id);
        assertEquals(new BigDecimal("0.1"), model.sources.get(5).id);
        assertEquals("", model.sources.get(6).id);
        assertEquals(-1L, model.sources.get(7).id);
    }

    @Test
    void exactNumericIdDoesNotDependOnDoubleRange() throws Exception {
        assertEquals(new BigDecimal("1e400"), read(feature("1e400", "source", "")).sources.get(0).id);
        assertThrows(InputValidationException.class, () -> read(
                feature("1e400", "source", ""), feature("10e399", "source", "")));
    }
}
