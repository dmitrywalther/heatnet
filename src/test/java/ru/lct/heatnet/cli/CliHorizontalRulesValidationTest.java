package ru.lct.heatnet.cli;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.DefaultApplicationArguments;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.refdata.RestrictionRules;
import ru.lct.heatnet.service.TracingResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Проверяет настоящую загрузку --rules до вызова движка и экспортёров CLI. */
class CliHorizontalRulesValidationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"k_spec, 0.5", "min_distance, 'oops'"})
    void rejectsInvalidHorizontalRuleBeforeCalculationAndOutput(String field, String value) throws Exception {
        Path input = directory.resolve("input.geojson");
        Files.writeString(input, "{\"type\":\"FeatureCollection\",\"features\":["
                + "{\"type\":\"Feature\",\"properties\":{\"id\":\"p1\",\"object_type\":\"heat_network\",\"diameter\":300},"
                + "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[37.600,55.750],[37.600,55.753]]}},"
                + "{\"type\":\"Feature\",\"properties\":{\"id\":\"o1\",\"object_type\":\"oks_connection_point\",\"flow_tph\":20},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6016,55.7515]}}]}");
        Path rulesFile = directory.resolve("rules.yml");
        Files.writeString(rulesFile, "restrictions:\n  road:\n    " + field + ": " + value + "\n");
        AtomicInteger calculations = new AtomicInteger();
        CliRunner runner = new CliRunner() {
            @Override protected TracingResult calculate(InputModel model, RestrictionRules rules) {
                calculations.incrementAndGet();
                throw new IllegalStateException("ENGINE_WAS_CALLED");
            }
        };

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> runner.execute(new DefaultApplicationArguments("--input=" + input,
                        "--rules=" + rulesFile, "--output=" + directory.resolve("out.geojson"))));

        assertTrue(error.getMessage().contains("road"), error.getMessage());
        assertTrue(error.getMessage().contains(field), error.getMessage());
        assertEquals(0, calculations.get(), "Ошибочный справочник не должен попасть в движок");
        for (String output : List.of("out.geojson", "out-depth.geojson", "out-report.pdf",
                "out-diagnostics.json")) {
            assertFalse(Files.exists(directory.resolve(output)), output);
        }
    }
}
