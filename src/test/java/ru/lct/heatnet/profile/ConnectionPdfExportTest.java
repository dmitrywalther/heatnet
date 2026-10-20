package ru.lct.heatnet.profile;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.lct.heatnet.api.ConnectionController;
import ru.lct.heatnet.cli.CliRunner;
import ru.lct.heatnet.job.JobEntity;
import ru.lct.heatnet.job.JobService;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.refdata.RestrictionRules;
import ru.lct.heatnet.service.TracingResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ConnectionPdfExportTest {
    @TempDir Path dir;

    @Test void httpBaseDownloadIsPdfAndLeavesStoredFilesAndStatusUntouched() throws Exception {
        JobEntity job = job(10L, false);
        Path input = Path.of(job.getInputPath()), result = Path.of(job.getResultPath());
        byte[] oldInput = Files.readAllBytes(input), oldResult = Files.readAllBytes(result);
        JobService jobs = mock(JobService.class);
        when(jobs.get("sample")).thenReturn(job);
        ProfileService service = new ProfileService(jobs, "");
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String oldCardJson = mapper.writeValueAsString(service.getConnection("sample", "v1", "n:10"));
        byte[] pdf = MockMvcBuilders.standaloneSetup(new ConnectionController(service)).build()
                .perform(get("/api/v1/tracing/jobs/sample/connection.pdf")
                        .param("variant_id", "v1").param("oks_key", "n:10"))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.matchesPattern(
                        "attachment; filename=\"[A-Za-z0-9_.-]+\\.pdf\"")))
                .andReturn().getResponse().getContentAsByteArray();
        assertPdf(pdf);
        assertArrayEquals(oldInput, Files.readAllBytes(input));
        assertArrayEquals(oldResult, Files.readAllBytes(result));
        assertEquals(oldCardJson, mapper.writeValueAsString(service.getConnection("sample", "v1", "n:10")));
        assertEquals(JobEntity.Status.DONE, job.getStatus());
        verify(jobs, times(3)).get("sample");
        verifyNoMoreInteractions(jobs);
    }

    @Test void httpDepthDownloadKeepsHugeNumericOksAndUsesSavedYaml() throws Exception {
        BigDecimal id = new BigDecimal("1e400");
        JobEntity job = job(id, true);
        Path yaml = dir.resolve("sample-rules.yml");
        Files.writeString(yaml, "heat_network_crossing:\n  vertical:\n    obstacle_top_depth: 4.2\n");
        byte[] beforeYaml = Files.readAllBytes(yaml), beforeDepth = Files.readAllBytes(Path.of(job.getDepthResultPath()));
        JobService jobs = mock(JobService.class); when(jobs.get("sample")).thenReturn(job);
        byte[] pdf = mvc(jobs).perform(get("/api/v1/tracing/jobs/sample/connection.pdf")
                        .param("variant_id", "d1").param("oks_key", "n:" + id.toPlainString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        String text = assertPdf(pdf);
        assertTrue(text.contains("1E+400") || text.replaceAll("\\s+", "").contains(id.toPlainString()), text);
        assertTrue(text.contains("4,2") || text.contains("4.2"), text);
        assertArrayEquals(beforeYaml, Files.readAllBytes(yaml));
        assertArrayEquals(beforeDepth, Files.readAllBytes(Path.of(job.getDepthResultPath())));
    }

    @Test void httpRequiresOksAndReportsUnknownJobsVariantsAndOks() throws Exception {
        JobEntity job = job(10L, false);
        JobService jobs = mock(JobService.class); when(jobs.get("sample")).thenReturn(job);
        MockMvc mvc = mvc(jobs);
        mvc.perform(get("/api/v1/tracing/jobs/sample/connection.pdf").param("variant_id", "v1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/tracing/jobs/missing/connection.pdf").param("variant_id", "v1").param("oks_key", "n:10"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Задача не найдена"));
        for (String[] pair : List.of(new String[]{"v3", "n:10"}, new String[]{"d1", "n:10"}, new String[]{"v1", "n:999"}))
            mvc.perform(get("/api/v1/tracing/jobs/sample/connection.pdf").param("variant_id", pair[0]).param("oks_key", pair[1]))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONNECTION_UNAVAILABLE"));
    }

    @Test void cliExportsBaseWithoutCallingRoutingOrTouchingSourceAndResult() throws Exception {
        JobEntity job = job(10L, false);
        Path input = Path.of(job.getInputPath()), result = Path.of(job.getResultPath()), output = dir.resolve("passport.pdf");
        byte[] oldInput = Files.readAllBytes(input), oldResult = Files.readAllBytes(result);
        assertEquals(0, noCalculation().execute(args(job, "v1", "n:10", output)));
        assertPdf(Files.readAllBytes(output));
        assertArrayEquals(oldInput, Files.readAllBytes(input)); assertArrayEquals(oldResult, Files.readAllBytes(result));
        assertFalse(Files.exists(dir.resolve("passport-depth.geojson")));
        assertFalse(Files.exists(dir.resolve("passport-report.pdf")));
    }

    @Test void cliExportsDepthWithYamlAndUnconnectedOksWithoutProfile() throws Exception {
        JobEntity job = job(10L, true);
        Path rules = dir.resolve("rules.yml"); Files.writeString(rules, "heat_network_crossing:\n  vertical:\n    obstacle_top_depth: 4.2\n");
        Path output = dir.resolve("depth.pdf");
        List<String> args = arguments(job, "d1", "n:10", output);
        args.add("--rules=" + rules);
        assertEquals(0, noCalculation().execute(new DefaultApplicationArguments(args.toArray(new String[0]))));
        String text = assertPdf(Files.readAllBytes(output));
        assertTrue(text.contains("4,2") || text.contains("4.2"), text);
        Path disconnected = dir.resolve("unconnected.pdf");
        assertEquals(0, noCalculation().execute(args(job, "d1", "n:20", disconnected)));
        String unavailable = assertPdf(Files.readAllBytes(disconnected)).toLowerCase();
        assertTrue(unavailable.contains("не подключ"), unavailable);
    }

    @Test void cliMissingDuplicateAndUnknownArgumentsFailBeforeCalculationAndWriting() throws Exception {
        JobEntity job = job(10L, false); Path output = dir.resolve("passport.pdf");
        for (String required : List.of("input", "result", "variant", "oks-key", "output")) {
            List<String> args = arguments(job, "v1", "n:10", output);
            args.removeIf(s -> s.startsWith("--" + required + "="));
            assertEquals(2, noCalculation().execute(new DefaultApplicationArguments(args.toArray(new String[0]))), required);
        }
        List<String> duplicate = arguments(job, "v1", "n:10", output); duplicate.add("--variant=v3");
        assertEquals(2, noCalculation().execute(new DefaultApplicationArguments(duplicate.toArray(new String[0]))));
        assertEquals(2, noCalculation().execute(args(job, "v99", "n:10", output)));
        assertEquals(2, noCalculation().execute(args(job, "v1", "n:999", output)));
        assertFalse(Files.exists(output));
    }

    @Test void cliCannotOverwriteInputResultOrRulesEvenThroughNormalizedAlias() throws Exception {
        JobEntity job = job(10L, false);
        Path rules = dir.resolve("rules.yml"); Files.writeString(rules, "heat_network_crossing:\n  k_spec: 1\n");
        for (Path protectedPath : List.of(Path.of(job.getInputPath()), Path.of(job.getResultPath()), rules)) {
            byte[] old = Files.readAllBytes(protectedPath);
            List<String> args = arguments(job, "v1", "n:10", protectedPath.getParent().resolve(".").resolve(protectedPath.getFileName()));
            args.add("--rules=" + rules);
            assertEquals(2, noCalculation().execute(new DefaultApplicationArguments(args.toArray(new String[0]))));
            assertArrayEquals(old, Files.readAllBytes(protectedPath));
        }
    }

    private CliRunner noCalculation() {
        return new CliRunner() {
            @Override protected TracingResult calculate(InputModel input, RestrictionRules rules) {
                fail("Passport export must not invoke routing"); return null;
            }
        };
    }

    private MockMvc mvc(JobService jobs) throws Exception {
        return MockMvcBuilders.standaloneSetup(new ConnectionController(new ProfileService(jobs, ""))).build();
    }

    private DefaultApplicationArguments args(JobEntity job, String variant, String key, Path output) {
        return new DefaultApplicationArguments(arguments(job, variant, key, output).toArray(new String[0]));
    }
    private List<String> arguments(JobEntity job, String variant, String key, Path output) {
        return new ArrayList<>(List.of("--export-connection", "--input=" + job.getInputPath(),
                "--result=" + (variant.startsWith("d") ? job.getDepthResultPath() : job.getResultPath()),
                "--variant=" + variant, "--oks-key=" + key, "--output=" + output));
    }
    private String assertPdf(byte[] bytes) throws Exception {
        assertTrue(new String(bytes, 0, Math.min(8, bytes.length), StandardCharsets.US_ASCII).startsWith("%PDF-"));
        try (PDDocument document = PDDocument.load(bytes)) {
            assertTrue(document.getNumberOfPages() >= 1);
            return new PDFTextStripper().getText(document);
        }
    }

    private JobEntity job(Object id, boolean depth) throws Exception {
        ProfileSceneTest f = new ProfileSceneTest(); f.source("source", 0); f.oks(id, 30); f.segment("line", "source", id, 0, 30, 3, 3);
        ObjectNode p = (ObjectNode) f.features.get(0).get("properties");
        p.put("variant_id", depth ? "d1" : "v1").put("flow_tph", 1).put("cost", 1000.0);
        if (!depth) { p.putNull("depth_start"); p.putNull("depth_end"); }
        ObjectNode input = f.mapper.createObjectNode().put("type", "FeatureCollection");
        ArrayNode features = input.putArray("features");
        ObjectNode chamber = features.addObject().put("type", "Feature"); chamber.putObject("properties").put("id", "source").put("object_type", "heat_chamber"); chamber.set("geometry", f.point(0));
        for (Object oid : List.of(id, 20L)) {
            ObjectNode oks = features.addObject().put("type", "Feature");
            ObjectNode props = oks.putObject("properties").put("object_type", "oks_connection_point").put("flow_tph", 1);
            props.set("id", f.mapper.valueToTree(oid)); oks.set("geometry", f.point(oid.equals(id) ? 30 : 40));
        }
        ObjectNode pipe = features.addObject().put("type", "Feature");
        pipe.putObject("properties").put("id", "existing").put("object_type", "heat_network").put("diameter", 100);
        ArrayNode coords = pipe.putObject("geometry").put("type", "LineString").putArray("coordinates");
        for (double y : new double[]{-10, 10}) {
            org.locationtech.jts.geom.Coordinate c = f.at(0, y), w = f.projection.utmToWgs(c.x, c.y);
            coords.addArray().add(w.x).add(w.y);
        }
        Path inputPath = dir.resolve("sample-input.geojson"), resultPath = dir.resolve(depth ? "sample-result-depth.geojson" : "sample-result.geojson");
        Files.writeString(inputPath, f.mapper.writeValueAsString(input)); Files.writeString(resultPath, f.mapper.writeValueAsString(f.collection()));
        JobEntity job = new JobEntity(); job.setId("sample"); job.setStatus(JobEntity.Status.DONE); job.setInputPath(inputPath.toString());
        if (depth) job.setDepthResultPath(resultPath.toString()); else job.setResultPath(resultPath.toString());
        return job;
    }
}
