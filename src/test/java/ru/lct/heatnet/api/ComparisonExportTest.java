package ru.lct.heatnet.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.lct.heatnet.cli.CliRunner;
import ru.lct.heatnet.geo.Projection;
import ru.lct.heatnet.job.JobEntity;
import ru.lct.heatnet.job.JobService;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.refdata.RestrictionRules;
import ru.lct.heatnet.service.TracingResult;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ComparisonExportTest {
    @TempDir Path dir;
    final ObjectMapper mapper = new ObjectMapper();
    final Projection projection = new Projection();

    @Test void httpAndCliExportTheSameExactTypedJsonAndReadablePdfWithoutRouting() throws Exception {
        BigDecimal hugeId = new BigDecimal("1e400");
        JobEntity before = job("before", hugeId, "v1", 100);
        JobEntity after = job("after", hugeId, "v1", 125);
        Map<Path,byte[]> saved = snapshot(before, after);
        MockMvc mvc = mvc(jobs(before, after));
        byte[] json = mvc.perform(get("/api/v1/tracing/comparison")
                        .param("before_job_id", "before").param("before_variant_id", "v1")
                        .param("after_job_id", "after").param("after_variant_id", "v1"))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"heatnet-comparison.json\""))
                .andReturn().getResponse().getContentAsByteArray();
        JsonNode comparison = mapper.readTree(json);
        assertEquals(1, comparison.path("schema_version").asInt());
        assertEquals(25, comparison.path("delta").path("calculated_cost").asDouble(), 1e-9);
        assertEquals("n:" + hugeId.toPlainString(), comparison.path("connections").get(0).path("oks_key").asText());
        byte[] pdf = mvc.perform(get("/api/v1/tracing/comparison.pdf")
                        .param("before_job_id", "before").param("before_variant_id", "v1")
                        .param("after_job_id", "after").param("after_variant_id", "v1"))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"heatnet-comparison.pdf\""))
                .andReturn().getResponse().getContentAsByteArray();
        assertPdf(pdf);
        Path jsonOut = dir.resolve("comparison.json");
        assertEquals(0, noRouting().execute(args(before, after, jsonOut)));
        assertEquals(comparison, mapper.readTree(jsonOut.toFile()));
        assertPdf(Files.readAllBytes(dir.resolve("comparison.pdf")));
        assertSaved(saved); assertEquals(JobEntity.Status.DONE, before.getStatus()); assertEquals(JobEntity.Status.DONE, after.getStatus());
    }

    @Test void httpRejectsMissingPendingUnknownVariantMixedModeAndBadSavedYaml() throws Exception {
        JobEntity before = job("before", 10L, "v1", 100);
        JobEntity after = job("after", 10L, "v1", 100);
        JobService jobs = jobs(before, after);
        MockMvc mvc = mvc(jobs);
        mvc.perform(get("/api/v1/tracing/comparison").param("before_job_id", "before"))
                .andExpect(status().isBadRequest());
        mvc.perform(request("missing", "v1", "after", "v1"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("COMPARISON_UNAVAILABLE"));
        after.setStatus(JobEntity.Status.RUNNING);
        mvc.perform(request("before", "v1", "after", "v1")).andExpect(status().isConflict());
        after.setStatus(JobEntity.Status.DONE);
        mvc.perform(request("before", "v1", "after", "v99")).andExpect(status().isConflict());
        JobEntity depth = job("depth", 10L, "d1", 100); when(jobs.get("depth")).thenReturn(depth);
        mvc.perform(request("before", "v1", "depth", "d1")).andExpect(status().isConflict());
        Path rules = dir.resolve("after-rules.yml"); Files.writeString(rules, "restrictions:\n  road:\n    k_spec: 0.5\n");
        mvc.perform(request("before", "v1", "after", "v1")).andExpect(status().isConflict());
    }

    @Test void cliRequiresUniqueArgumentsAndRejectsMutuallyExclusiveModesBeforeWriting() throws Exception {
        JobEntity before = job("before", 10L, "v1", 100), after = job("after", 10L, "v1", 100);
        Path output = dir.resolve("out.json");
        for (String required : List.of("before-input", "before-result", "before-variant", "after-input", "after-result", "after-variant", "output")) {
            List<String> args = arguments(before, after, output);
            args.removeIf(s -> s.startsWith("--" + required + "="));
            assertEquals(2, noRouting().execute(new DefaultApplicationArguments(args.toArray(new String[0]))), required);
        }
        List<String> duplicate = arguments(before, after, output); duplicate.add("--before-variant=v1");
        assertEquals(2, noRouting().execute(new DefaultApplicationArguments(duplicate.toArray(new String[0]))));
        List<String> both = arguments(before, after, output);
        both.addAll(List.of("--export-connection", "--input=" + before.getInputPath(), "--result=" + before.getResultPath(),
                "--variant=v1", "--oks-key=n:10"));
        assertEquals(2, noRouting().execute(new DefaultApplicationArguments(both.toArray(new String[0]))));
        assertFalse(Files.exists(output)); assertFalse(Files.exists(dir.resolve("out.pdf")));
    }

    @Test void cliProtectsEveryInputRulesOutputAndHardLinkAlias() throws Exception {
        JobEntity before = job("before", 10L, "v1", 100), after = job("after", 10L, "v1", 125);
        Path beforeRules = dir.resolve("before-rules.yml"), afterRules = dir.resolve("after-rules.yml");
        Files.writeString(beforeRules, "heat_network_crossing:\n  k_spec: 1\n");
        Files.writeString(afterRules, "heat_network_crossing:\n  k_spec: 2\n");
        List<Path> inputs = List.of(Path.of(before.getInputPath()), Path.of(before.getResultPath()),
                Path.of(after.getInputPath()), Path.of(after.getResultPath()), beforeRules, afterRules);
        for (Path source : inputs) {
            byte[] saved = Files.readAllBytes(source);
            for (boolean pdf : List.of(false, true)) {
                Path json = pdf ? dir.resolve("safe.json") : source.getParent().resolve(".").resolve(source.getFileName());
                List<String> args = arguments(before, after, json);
                args.add("--before-rules=" + beforeRules); args.add("--after-rules=" + afterRules);
                if (pdf) args.add("--pdf-output=" + source);
                assertEquals(2, noRouting().execute(new DefaultApplicationArguments(args.toArray(new String[0]))));
                assertArrayEquals(saved, Files.readAllBytes(source));
            }
        }
        Path alias = dir.resolve("input-alias.json");
        Files.createLink(alias, inputs.get(0));
        assertEquals(2, noRouting().execute(args(before, after, alias)));
        Path json = dir.resolve("same.json");
        List<String> same = arguments(before, after, json); same.add("--pdf-output=" + json);
        assertEquals(2, noRouting().execute(new DefaultApplicationArguments(same.toArray(new String[0]))));
        Files.writeString(json, "preserve");
        Path pdfAlias = dir.resolve("same-alias.pdf"); Files.createLink(pdfAlias, json);
        List<String> linked = arguments(before, after, json); linked.add("--pdf-output=" + pdfAlias);
        assertEquals(2, noRouting().execute(new DefaultApplicationArguments(linked.toArray(new String[0]))));
        assertEquals("preserve", Files.readString(json));
    }

    @Test void cliPreparesBothOutputsBeforeReplacingExistingJsonAndPreservesOldOutputsOnInvalidData() throws Exception {
        JobEntity before = job("before", 10L, "v1", 100), after = job("after", 10L, "v1", 125);
        Path json = dir.resolve("comparison.json"), pdf = dir.resolve("comparison.pdf");
        Files.writeString(json, "old json");
        Files.createDirectory(pdf);
        try { assertNotEquals(0, noRouting().execute(args(before, after, json))); }
        catch (java.io.IOException expected) { /* OS filesystem failure must leave existing JSON untouched. */ }
        assertEquals("old json", Files.readString(json)); assertTrue(Files.isDirectory(pdf));
        Files.delete(pdf); Files.writeString(pdf, "old pdf");
        List<String> invalid = arguments(before, after, json);
        invalid.replaceAll(s -> s.equals("--after-variant=v1") ? "--after-variant=v99" : s);
        assertEquals(2, noRouting().execute(new DefaultApplicationArguments(invalid.toArray(new String[0]))));
        assertEquals("old json", Files.readString(json)); assertEquals("old pdf", Files.readString(pdf));
    }

    @Test void cliSupportsExplicitPdfPathAndIndependentYamlFiles() throws Exception {
        JobEntity before = job("before", "10", "d1", 100), after = job("after", "10", "d1", 125);
        Path bRules = dir.resolve("before-rules.yml"), aRules = dir.resolve("after-rules.yml");
        Files.writeString(bRules, "heat_network_crossing:\n  vertical:\n    obstacle_top_depth: 4.2\n");
        Files.writeString(aRules, "heat_network_crossing:\n  vertical:\n    obstacle_top_depth: 5.2\n");
        Path output = dir.resolve("custom.json"), pdf = dir.resolve("separate/passport.pdf");
        List<String> args = arguments(before, after, output);
        args.add("--before-rules=" + bRules); args.add("--after-rules=" + aRules); args.add("--pdf-output=" + pdf);
        assertEquals(0, noRouting().execute(new DefaultApplicationArguments(args.toArray(new String[0]))));
        JsonNode cli = mapper.readTree(output.toFile());
        assertEquals("s:10", cli.path("connections").get(0).path("oks_key").asText());
        assertNotEquals(cli.path("before").path("effective_rules_sha256"), cli.path("after").path("effective_rules_sha256"));
        byte[] api = mvc(jobs(before, after)).perform(request("before", "d1", "after", "d1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertEquals(cli, mapper.readTree(api));
        assertPdf(Files.readAllBytes(pdf));
        assertFalse(Files.exists(dir.resolve("custom.pdf")));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String bJob, String bVariant, String aJob, String aVariant) {
        return get("/api/v1/tracing/comparison").param("before_job_id", bJob).param("before_variant_id", bVariant)
                .param("after_job_id", aJob).param("after_variant_id", aVariant);
    }
    private MockMvc mvc(JobService jobs) throws Exception {
        Class<?> serviceType = assertDoesNotThrow(() -> Class.forName("ru.lct.heatnet.api.ComparisonService"));
        Class<?> controllerType = assertDoesNotThrow(() -> Class.forName("ru.lct.heatnet.api.ComparisonController"));
        Object service = serviceType.getConstructor(JobService.class, String.class).newInstance(jobs, "");
        Object controller = controllerType.getConstructor(serviceType).newInstance(service);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }
    private JobService jobs(JobEntity before, JobEntity after) {
        JobService jobs = mock(JobService.class); when(jobs.get(before.getId())).thenReturn(before); when(jobs.get(after.getId())).thenReturn(after); return jobs;
    }
    private CliRunner noRouting() {
        return new CliRunner() {
            @Override protected TracingResult calculate(InputModel input, RestrictionRules rules) {
                fail("Comparison export must not invoke TracingEngine"); return null;
            }
        };
    }
    private DefaultApplicationArguments args(JobEntity before, JobEntity after, Path output) {
        return new DefaultApplicationArguments(arguments(before, after, output).toArray(new String[0]));
    }
    private List<String> arguments(JobEntity before, JobEntity after, Path output) {
        String bv = before.getResultPath() == null ? "d1" : "v1", av = after.getResultPath() == null ? "d1" : "v1";
        return new ArrayList<>(List.of("--compare-results", "--before-input=" + before.getInputPath(),
                "--before-result=" + result(before), "--before-variant=" + bv, "--after-input=" + after.getInputPath(),
                "--after-result=" + result(after), "--after-variant=" + av, "--output=" + output));
    }
    private String result(JobEntity job) { return job.getResultPath() == null ? job.getDepthResultPath() : job.getResultPath(); }
    private Map<Path,byte[]> snapshot(JobEntity... jobs) throws Exception {
        Map<Path,byte[]> result = new LinkedHashMap<>();
        for (JobEntity job : jobs) for (String path : List.of(job.getInputPath(), result(job))) result.put(Path.of(path), Files.readAllBytes(Path.of(path)));
        return result;
    }
    private void assertSaved(Map<Path,byte[]> before) throws Exception {
        for (Map.Entry<Path,byte[]> e : before.entrySet()) assertArrayEquals(e.getValue(), Files.readAllBytes(e.getKey()));
    }
    private void assertPdf(byte[] bytes) throws Exception {
        try (PDDocument doc = PDDocument.load(bytes)) {
            assertTrue(doc.getNumberOfPages() > 0);
            assertTrue(new PDFTextStripper().getText(doc).contains("Сравнение расчётов"));
        }
    }
    private JobEntity job(String name, Object id, String variant, double cost) throws Exception {
        ObjectNode input = mapper.createObjectNode().put("type", "FeatureCollection");
        ArrayNode inf = input.putArray("features");
        ObjectNode source = feature(inf, "source", "heat_chamber"); source.set("geometry", point(0, 0));
        ObjectNode oks = feature(inf, id, "oks_connection_point"); ((ObjectNode)oks.get("properties")).put("flow_tph", 1); oks.set("geometry", point(30, 0));
        ObjectNode pipe = feature(inf, "existing", "heat_network"); ((ObjectNode)pipe.get("properties")).put("diameter", 100); pipe.set("geometry", line(0, -10, 0, 10));
        ObjectNode output = mapper.createObjectNode().put("type", "FeatureCollection");
        ArrayNode out = output.putArray("features");
        ObjectNode segment = feature(out, name + "-line", "heat_network");
        ObjectNode sp = (ObjectNode)segment.get("properties");
        sp.put("variant_id", variant).put("start_node_id", "source").put("diameter", 100).put("flow_tph", 1)
                .put("length", 30).put("cost", cost).put("laying_method", "base");
        sp.set("end_node_id", mapper.valueToTree(id));
        if (variant.startsWith("d")) sp.put("depth_start", 3).put("depth_end", 3);
        else sp.putNull("depth_start").putNull("depth_end");
        segment.set("geometry", line(0, 0, 30, 0));
        ObjectNode summary = feature(out, name + "-summary", "variant_summary"); summary.putNull("geometry");
        ObjectNode su = (ObjectNode)summary.get("properties");
        su.put("variant_id", variant).put("construction_cost", cost).put("chamber_construction_cost", 0)
                .put("existing_chamber_tie_in_count", 1).put("existing_chamber_tie_in_cost", 0)
                .put("unconnected_penalty", 0).put("calculated_cost", cost).put("new_network_length", 30).put("score", cost / 100);
        su.putArray("unconnected_oks_ids");
        Path in = dir.resolve(name + "-input.geojson"), result = dir.resolve(name + "-result.geojson");
        Files.writeString(in, mapper.writeValueAsString(input)); Files.writeString(result, mapper.writeValueAsString(output));
        JobEntity job = new JobEntity(); job.setId(name); job.setStatus(JobEntity.Status.DONE); job.setInputPath(in.toString());
        if (variant.startsWith("d")) job.setDepthResultPath(result.toString()); else job.setResultPath(result.toString());
        return job;
    }
    private ObjectNode feature(ArrayNode features, Object id, String type) {
        ObjectNode f = features.addObject().put("type", "Feature"), p = f.putObject("properties").put("object_type", type);
        p.set("id", mapper.valueToTree(id)); return f;
    }
    private ObjectNode point(double x, double y) {
        Coordinate p = projection.utmToWgs(500000 + x, 6200000 + y);
        ObjectNode result = mapper.createObjectNode().put("type", "Point"); result.putArray("coordinates").add(p.x).add(p.y); return result;
    }
    private ObjectNode line(double x, double y, double xx, double yy) {
        ObjectNode result = mapper.createObjectNode().put("type", "LineString"); ArrayNode points = result.putArray("coordinates");
        points.add(point(x, y).get("coordinates")); points.add(point(xx, yy).get("coordinates")); return result;
    }
}
