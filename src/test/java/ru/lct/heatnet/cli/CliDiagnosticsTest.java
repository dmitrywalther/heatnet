package ru.lct.heatnet.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.network.VariantDiagnostic;
import ru.lct.heatnet.network.VariantResult;
import ru.lct.heatnet.refdata.RestrictionRules;
import ru.lct.heatnet.service.TracingResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CliDiagnosticsTest {
    @TempDir Path directory;

    @Test void noBaseReturnsNonzeroAndLeavesOnlyDiagnosticOutput() throws Exception {
        Path input = input();
        for (String file : List.of("out.geojson", "out-depth.geojson", "out-report.pdf")) {
            Files.writeString(directory.resolve(file), "old output");
        }
        TracingResult result = new TracingResult(List.of(), List.of(), List.of(
                new VariantDiagnostic("v1", "base", "GEOMETRY_TEST", "Нарушен отступ")));
        assertEquals(3, runner(result).execute(args(input)));
        assertFalse(Files.exists(directory.resolve("out.geojson")));
        assertFalse(Files.exists(directory.resolve("out-depth.geojson")));
        assertFalse(Files.exists(directory.resolve("out-report.pdf")));
        assertEquals("GEOMETRY_TEST", new ObjectMapper().readTree(
                directory.resolve("out-diagnostics.json").toFile())
                .path("diagnostics").path(0).path("code").asText());
    }

    @Test void baseOnlyReturnsSuccessWithoutEmptyDepthFile() throws Exception {
        VariantResult base = new VariantResult();
        base.variantId = "v1";
        base.strategyName = "SHARED";
        base.rank = 1;
        TracingResult result = new TracingResult(List.of(base), List.of(), List.of(
                new VariantDiagnostic("d1", "depth", "DEPTH_TEST", "Недопустимый профиль")));
        assertEquals(0, runner(result).execute(args(input())));
        assertTrue(Files.exists(directory.resolve("out.geojson")));
        assertTrue(Files.exists(directory.resolve("out-report.pdf")));
        assertFalse(Files.exists(directory.resolve("out-depth.geojson")));
    }

    private CliRunner runner(TracingResult result) {
        return new CliRunner() {
            @Override protected TracingResult calculate(InputModel model, RestrictionRules rules) {
                return result;
            }
        };
    }

    private DefaultApplicationArguments args(Path input) {
        return new DefaultApplicationArguments("--input=" + input,
                "--output=" + directory.resolve("out.geojson"));
    }

    private Path input() throws Exception {
        Path path = directory.resolve("input.geojson");
        Files.writeString(path, "{\"type\":\"FeatureCollection\",\"features\":["
                + "{\"type\":\"Feature\",\"properties\":{\"id\":\"p1\",\"object_type\":\"heat_network\",\"diameter\":300},"
                + "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[37.600,55.750],[37.600,55.753]]}},"
                + "{\"type\":\"Feature\",\"properties\":{\"id\":\"o1\",\"object_type\":\"oks_connection_point\",\"flow_tph\":20},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6016,55.7515]}}]}");
        return path;
    }
}
