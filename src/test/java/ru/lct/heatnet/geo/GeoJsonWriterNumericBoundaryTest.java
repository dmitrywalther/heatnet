package ru.lct.heatnet.geo;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.network.VariantResult;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GeoJsonWriterNumericBoundaryTest {
    @Test
    void largeIntegerAndPreciseDecimalReferencesRemainExactJsonNumbers() throws Exception {
        BigInteger integer = new BigInteger("922337203685477580812345");
        BigDecimal decimal = new BigDecimal("0.1000000000000000001");
        VariantResult variant = variant();
        VariantResult.Segment segment = new VariantResult.Segment();
        segment.id = "v1_net_1";
        segment.startNodeId = integer;
        segment.endNodeId = decimal;
        segment.utmCoords = List.of(new Coordinate(400000, 6170000), new Coordinate(400100, 6170000));
        variant.segments.add(segment);
        variant.unconnectedOksIds.add(integer);
        variant.unconnectedOksIds.add(decimal);
        variant.unconnectedOksIds.add("0.1000000000000000001");

        JsonNode features = write(variant).get("features");
        JsonNode properties = features.get(0).get("properties");
        assertTrue(properties.get("start_node_id").isIntegralNumber());
        assertEquals(integer, properties.get("start_node_id").bigIntegerValue());
        assertTrue(properties.get("end_node_id").isNumber());
        assertEquals(0, decimal.compareTo(properties.get("end_node_id").decimalValue()));
        JsonNode ids = features.get(1).get("properties").get("unconnected_oks_ids");
        assertTrue(ids.get(0).isIntegralNumber());
        assertEquals(integer, ids.get(0).bigIntegerValue());
        assertTrue(ids.get(1).isNumber());
        assertEquals(0, decimal.compareTo(ids.get(1).decimalValue()));
        assertTrue(ids.get(2).isTextual());
        assertEquals(decimal.toPlainString(), ids.get(2).textValue());
    }

    @ParameterizedTest
    @ValueSource(doubles = {0x1p63, 1e20, 1e100})
    void finiteLargeCostsAreNotSilentlyClampedToLongMax(double cost) throws Exception {
        VariantResult variant = variant();
        variant.unconnectedPenalty = cost;
        variant.calculatedCost = cost;
        JsonNode properties = write(variant).get("features").get(0).get("properties");
        assertTrue(properties.get("calculated_cost").isNumber());
        assertEquals(0, BigDecimal.valueOf(cost).compareTo(properties.get("calculated_cost").decimalValue()));
        assertEquals(0, BigDecimal.valueOf(cost).compareTo(properties.get("unconnected_penalty").decimalValue()));
    }

    @Test
    void ordinaryIntegralCostsKeepTheExistingIntegerEncoding() throws Exception {
        VariantResult variant = variant();
        variant.calculatedCost = 262225702;
        JsonNode cost = write(variant).get("features").get(0).get("properties").get("calculated_cost");
        assertTrue(cost.isIntegralNumber());
        assertEquals(262225702L, cost.longValue());
    }

    private static VariantResult variant() {
        VariantResult result = new VariantResult();
        result.variantId = "v1";
        return result;
    }

    private static JsonNode write(VariantResult variant) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new GeoJsonWriter(new Projection()).write(List.of(variant), out);
        return new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readTree(out.toByteArray());
    }
}
