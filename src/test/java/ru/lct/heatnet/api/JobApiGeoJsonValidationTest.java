package ru.lct.heatnet.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.lct.heatnet.job.JobEntity;
import ru.lct.heatnet.job.JobRepository;
import ru.lct.heatnet.job.JobService;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.refdata.RestrictionRules;
import ru.lct.heatnet.service.TracingResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Настоящие controller/service и дисковые файлы; БД заменена хранилищем сущностей в памяти.
 * processAsync вызывается без Spring-прокси синхронно, чтобы проверить итог отказа без ожиданий.
 */
class JobApiGeoJsonValidationTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"diameter", "flow_tph", "id"})
    void keepsAcceptedJobLifecycleAndReportsInvalidFieldBeforeCalculation(String field)
            throws Exception {
        Map<String, JobEntity> stored = new HashMap<>();
        JobRepository repository = mock(JobRepository.class);
        when(repository.save(any(JobEntity.class))).thenAnswer(call -> {
            JobEntity job = call.getArgument(0);
            stored.put(job.getId(), job);
            return job;
        });
        when(repository.findById(anyString())).thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));
        AtomicInteger calculations = new AtomicInteger();
        JobService service = new JobService(repository, directory.toString(), "") {
            @Override protected TracingResult calculate(InputModel model, RestrictionRules rules) {
                calculations.incrementAndGet();
                throw new IllegalStateException("ENGINE_WAS_CALLED");
            }
        };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new JobController(service)).build();
        String input = "{\"type\":\"FeatureCollection\",\"features\":["
                + "{\"type\":\"Feature\",\"properties\":{\"id\":\"p1\",\"object_type\":\"heat_network\",\"diameter\":300},"
                + "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[37.600,55.750],[37.600,55.753]]}},"
                + "{\"type\":\"Feature\",\"properties\":{\"id\":\"o1\",\"object_type\":\"oks_connection_point\",\"flow_tph\":20},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6016,55.7515]}}]}";
        if ("diameter".equals(field)) input = input.replace("\"diameter\":300", "\"diameter\":50.5");
        else if ("flow_tph".equals(field)) input = input.replace("\"flow_tph\":20", "\"flow_tph\":-1");
        else input = input.replace("\"o1\"", "\"p1\"");
        byte[] created = mvc.perform(multipart("/api/v1/tracing/jobs")
                        .file(new MockMultipartFile("file", "input.geojson", "application/geo+json",
                                input.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsByteArray();
        String jobId = mapper.readTree(created).path("job_id").asText();
        assertFalse(jobId.isEmpty());
        JsonNode body = mapper.readTree(mvc.perform(get("/api/v1/tracing/jobs/" + jobId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());

        assertEquals("ERROR", body.path("status").asText());
        assertTrue(body.path("error").asText().contains("features["), body.toString());
        assertTrue(body.path("error").asText().contains(field), body.toString());
        assertEquals(0, calculations.get(), "Ошибочный GeoJSON не должен попасть в движок");
        for (String availability : List.of("result_available", "depth_available", "report_available")) {
            assertFalse(body.path(availability).asBoolean(), availability);
        }
        for (String endpoint : List.of("result", "result-depth", "report")) {
            mvc.perform(get("/api/v1/tracing/jobs/" + jobId + "/" + endpoint))
                    .andExpect(status().isConflict());
        }
        assertNotNull(service.get(jobId).getFinishedAt());
        assertNull(service.get(jobId).getResultPath());
        assertNull(service.get(jobId).getDepthResultPath());
        assertNull(service.get(jobId).getReportPath());
        // Вход сохраняется для диагностики, файлов расчёта нет.
        assertEquals(input, Files.readString(directory.resolve(jobId + "-input.geojson")));
        for (String suffix : List.of("-result.geojson", "-result-depth.geojson", "-report.pdf",
                "-result-diagnostics.json")) {
            assertFalse(Files.exists(directory.resolve(jobId + suffix)), suffix);
        }
    }
}
