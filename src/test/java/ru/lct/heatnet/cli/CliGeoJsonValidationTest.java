package ru.lct.heatnet.cli;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.geo.InputValidationException;
import ru.lct.heatnet.refdata.RestrictionRules;
import ru.lct.heatnet.service.TracingResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Проверяет настоящую загрузку GeoJSON до вызова движка и экспортёров CLI. */
class CliGeoJsonValidationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"diameter", "flow_tph", "id"})
    void rejectsInvalidGeoJsonBeforeCalculationAndOutput(String field) throws Exception {
        Path input = directory.resolve("input.geojson");
        String json = "{\"type\":\"FeatureCollection\",\"features\":["
                + "{\"type\":\"Feature\",\"properties\":{\"id\":\"p1\",\"object_type\":\"heat_network\",\"diameter\":300},"
                + "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[37.600,55.750],[37.600,55.753]]}},"
                + "{\"type\":\"Feature\",\"properties\":{\"id\":\"o1\",\"object_type\":\"oks_connection_point\",\"flow_tph\":20},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6016,55.7515]}}]}";
        if ("diameter".equals(field)) json = json.replace("\"diameter\":300", "\"diameter\":50.5");
        else if ("flow_tph".equals(field)) json = json.replace("\"flow_tph\":20", "\"flow_tph\":-1");
        else json = json.replace("\"o1\"", "\"p1\"");
        Files.writeString(input, json);
        AtomicInteger calculations = new AtomicInteger();
        CliRunner runner = new CliRunner() {
            @Override protected TracingResult calculate(InputModel model, RestrictionRules rules) {
                calculations.incrementAndGet();
                throw new IllegalStateException("ENGINE_WAS_CALLED");
            }
        };

        InputValidationException error = assertThrows(InputValidationException.class,
                () -> runner.execute(new DefaultApplicationArguments("--input=" + input,
                        "--output=" + directory.resolve("out.geojson"))));

        assertTrue(error.getMessage().contains("features["), error.getMessage());
        assertTrue(error.getMessage().contains(field), error.getMessage());
        assertEquals(0, calculations.get(), "Ошибочный GeoJSON не должен попасть в движок");
        for (String output : List.of("out.geojson", "out-depth.geojson", "out-report.pdf",
                "out-diagnostics.json")) {
            assertFalse(Files.exists(directory.resolve(output)), output);
        }
    }
}
