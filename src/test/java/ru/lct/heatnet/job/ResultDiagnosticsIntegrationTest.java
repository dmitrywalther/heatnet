package ru.lct.heatnet.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.api.JobController;
import ru.lct.heatnet.geo.GeoJsonWriter;
import ru.lct.heatnet.geo.Projection;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.network.VariantDiagnostic;
import ru.lct.heatnet.network.VariantResult;
import ru.lct.heatnet.refdata.RestrictionRules;
import ru.lct.heatnet.report.PdfReportWriter;
import ru.lct.heatnet.service.TracingResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** Проверяет настоящую запись/выдачу файлов; только результат поиска задаётся явно. */
class ResultDiagnosticsIntegrationTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String INPUT = "{\"type\":\"FeatureCollection\",\"features\":["
            + "{\"type\":\"Feature\",\"properties\":{\"id\":\"p1\",\"object_type\":\"heat_network\",\"diameter\":300},"
            + "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[37.600,55.750],[37.600,55.753]]}},"
            + "{\"type\":\"Feature\",\"properties\":{\"id\":\"o1\",\"object_type\":\"oks_connection_point\",\"flow_tph\":20},"
            + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6016,55.7515]}}]}";

    @Test void allBaseRejectedIsErrorWithCausesAndNoDownloadsOrStaleFiles() throws Exception {
        Fixture f = fixture(new TracingResult(List.of(), List.of(), List.of(diagnostic("v1", "base"))));
        for (String suffix : List.of("-result.geojson", "-result-depth.geojson", "-report.pdf")) {
            Files.writeString(directory.resolve(f.job.getId() + suffix), "stale previous output");
        }
        f.service.processAsync(f.job.getId());
        assertEquals(JobEntity.Status.ERROR, f.job.getStatus());
        assertNull(f.job.getResultPath());
        assertNull(f.job.getDepthResultPath());
        assertNull(f.job.getReportPath());
        assertTrue(f.job.getErrorMessage().contains("не означает"));
        JsonNode status = statusJson(f);
        assertEquals("v1", status.path("diagnostics").path(0).path("variant_id").asText());
        assertFalse(status.path("result_available").asBoolean(true));
        for (String suffix : List.of("result", "result-depth", "report")) {
            standaloneSetup(new JobController(f.service)).build()
                    .perform(get("/api/v1/tracing/jobs/" + f.job.getId() + "/" + suffix))
                    .andExpect(status().isConflict());
        }
        assertFalse(Files.exists(directory.resolve(f.job.getId() + "-result.geojson")));
        assertFalse(Files.exists(directory.resolve(f.job.getId() + "-result-depth.geojson")));
        assertFalse(Files.exists(directory.resolve(f.job.getId() + "-report.pdf")));
        assertTrue(Files.exists(directory.resolve(f.job.getId() + "-result-diagnostics.json")));
    }

    @Test void emptyDepthKeepsBaseAndReportButExplainsDepthConflict() throws Exception {
        Fixture f = fixture(new TracingResult(List.of(variant("v1")), List.of(),
                List.of(diagnostic("d1", "depth"))));
        Files.writeString(directory.resolve(f.job.getId() + "-result-depth.geojson"), "stale depth");
        f.service.processAsync(f.job.getId());
        assertEquals(JobEntity.Status.DONE, f.job.getStatus());
        assertNull(f.job.getDepthResultPath());
        JsonNode status = statusJson(f);
        assertTrue(status.path("result_available").asBoolean());
        assertTrue(status.path("report_available").asBoolean());
        assertFalse(status.path("depth_available").asBoolean(true));
        byte[] body = standaloneSetup(new JobController(f.service)).build()
                .perform(get("/api/v1/tracing/jobs/" + f.job.getId() + "/result-depth"))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsByteArray();
        JsonNode conflict = mapper.readTree(body);
        assertEquals("DEPTH_RESULT_UNAVAILABLE", conflict.path("code").asText());
        assertEquals("DEPTH_TEST", conflict.path("diagnostics").path(0).path("code").asText());
        assertFalse(Files.exists(directory.resolve(f.job.getId() + "-result-depth.geojson")));
        try (PDDocument pdf = PDDocument.load(Path.of(f.job.getReportPath()).toFile())) {
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("Исключённые варианты"));
            assertTrue(text.contains("d1"));
            assertTrue(text.contains("Тестовая причина"));
            assertTrue(text.contains("segment-test"));
        }
    }

    @Test void partialDepthRemainsAvailableAndDiagnosticsSurviveServiceRestart() throws Exception {
        Fixture f = fixture(new TracingResult(List.of(variant("v1"), variant("v3")),
                List.of(variant("d1")), List.of(diagnostic("d3", "depth"))));
        f.service.processAsync(f.job.getId());
        assertEquals(JobEntity.Status.DONE, f.job.getStatus());
        JobService restarted = new JobService(f.repository, directory.toString(), "");
        JsonNode status = mapper.readTree(standaloneSetup(new JobController(restarted)).build()
                .perform(get("/api/v1/tracing/jobs/" + f.job.getId())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertTrue(status.path("depth_available").asBoolean());
        assertEquals("d3", status.path("diagnostics").path(0).path("variant_id").asText());
        assertEquals("segment-test", status.path("diagnostics").path(0).path("segment_ids").path(0).asText());
        JsonNode depth = mapper.readTree(Path.of(f.job.getDepthResultPath()).toFile());
        assertEquals("d3", depth.path("diagnostics").path(0).path("variant_id").asText());
        assertTrue(depth.path("features").size() > 0);
        for (JsonNode feature : depth.path("features")) {
            assertEquals("d1", feature.path("properties").path("variant_id").asText());
        }
    }

    @Test void ordinaryOutputKeepsLegacyBytesAndOldJobsNeedNoDiagnosticFile() throws Exception {
        VariantResult v = variant("v1");
        Fixture f = fixture(new TracingResult(List.of(v), List.of(variant("d1"))));
        f.service.processAsync(f.job.getId());
        ByteArrayOutputStream legacy = new ByteArrayOutputStream();
        new GeoJsonWriter(new Projection()).write(List.of(v), legacy);
        assertArrayEquals(legacy.toByteArray(), Files.readAllBytes(Path.of(f.job.getResultPath())));
        Files.deleteIfExists(directory.resolve(f.job.getId() + "-result-diagnostics.json"));
        JsonNode status = statusJson(f);
        assertTrue(status.path("diagnostics").isArray());
        assertEquals(0, status.path("diagnostics").size());
        assertTrue(status.path("result_available").asBoolean());
        assertTrue(status.path("depth_available").asBoolean());
    }

    @Test void runningJobDoesNotReadPartiallyWrittenDiagnosticFile() throws Exception {
        Fixture f = fixture(new TracingResult(List.of(variant("v1")), List.of()));
        f.job.setStatus(JobEntity.Status.RUNNING);
        Files.writeString(directory.resolve(f.job.getId() + "-result-diagnostics.json"), "{\"diagnostics\":[");
        JsonNode status = assertDoesNotThrow(() -> statusJson(f));
        assertEquals("RUNNING", status.path("status").asText());
        assertTrue(status.path("diagnostics").isArray());
        assertEquals(0, status.path("diagnostics").size());
        assertFalse(status.path("result_available").asBoolean(true));
    }

    @Test void geoJsonRejectsFlaggedVariantBeforeWritingAnyBytes() {
        VariantResult bad = variant("v3");
        bad.clearanceViolated = true;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThrows(IllegalArgumentException.class,
                () -> new GeoJsonWriter(new Projection()).write(List.of(variant("v1"), bad), out));
        assertEquals(0, out.size());
    }

    @Test void geoJsonRejectsDiagnosticOnlyVariantAndEmptyResult() {
        VariantResult bad = variant("d3");
        bad.diagnostics.add(diagnostic("d3", "depth"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThrows(IllegalArgumentException.class,
                () -> new GeoJsonWriter(new Projection()).write(List.of(bad), out));
        assertThrows(IllegalArgumentException.class,
                () -> new GeoJsonWriter(new Projection()).write(List.of(), out));
        assertEquals(0, out.size());
    }

    @Test void pdfRejectsInvalidDepthAndEmptyBaseBeforeWritingAnyBytes() {
        VariantResult bad = variant("d3");
        bad.clearanceViolated = true;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThrows(IllegalArgumentException.class,
                () -> new PdfReportWriter(new InputModel(), List.of(variant("v1")), List.of(bad)).write(out));
        assertThrows(IllegalArgumentException.class,
                () -> new PdfReportWriter(new InputModel(), List.of(), List.of()).write(out));
        assertEquals(0, out.size());
    }

    private JsonNode statusJson(Fixture f) throws Exception {
        return mapper.readTree(standaloneSetup(new JobController(f.service)).build()
                .perform(get("/api/v1/tracing/jobs/" + f.job.getId())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
    }

    private Fixture fixture(TracingResult result) throws Exception {
        Map<String, JobEntity> jobs = new HashMap<>();
        JobRepository repository = mock(JobRepository.class);
        when(repository.save(any(JobEntity.class))).thenAnswer(a -> {
            JobEntity job = a.getArgument(0);
            jobs.put(job.getId(), job);
            return job;
        });
        when(repository.findById(anyString())).thenAnswer(a -> Optional.ofNullable(jobs.get(a.getArgument(0))));
        JobService service = new JobService(repository, directory.toString(), "") {
            @Override
            protected TracingResult calculate(InputModel model, RestrictionRules rules) { return result; }
        };
        JobEntity job = service.createJob(new ByteArrayInputStream(INPUT.getBytes(StandardCharsets.UTF_8)));
        return new Fixture(repository, service, job);
    }

    private static VariantDiagnostic diagnostic(String id, String mode) {
        return new VariantDiagnostic(id, mode, mode.equals("depth") ? "DEPTH_TEST" : "GEOMETRY_TEST",
                "Тестовая причина недопустимости", List.of("segment-test"));
    }

    private static VariantResult variant(String id) {
        VariantResult v = new VariantResult();
        v.variantId = id;
        v.strategyName = "SHARED";
        v.rank = 1;
        VariantResult.Segment s = new VariantResult.Segment();
        s.id = id + "_segment";
        s.utmCoords = List.of(new Coordinate(410000, 6179000), new Coordinate(410010, 6179000));
        s.diameter = 100;
        s.lengthM = 10;
        s.kSpec = 1;
        s.flowTph = 20;
        if (id.startsWith("d")) { s.depthStart = 3.0; s.depthEnd = 3.0; }
        v.segments.add(s);
        return v;
    }

    private static final class Fixture {
        final JobRepository repository;
        final JobService service;
        final JobEntity job;
        Fixture(JobRepository repository, JobService service, JobEntity job) {
            this.repository = repository; this.service = service; this.job = job;
        }
    }
}
