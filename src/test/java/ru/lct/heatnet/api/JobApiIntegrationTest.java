package ru.lct.heatnet.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.lct.heatnet.job.JobService;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JobApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobService jobService;

    private final ObjectMapper mapper = new ObjectMapper();

    /** Минимальный корректный вход: труба, камера, одна точка ОКС. */
    private static final String TINY_INPUT = "{\n"
            + "\"type\":\"FeatureCollection\",\"features\":[\n"
            + "{\"type\":\"Feature\",\"properties\":{\"id\":\"p1\",\"object_type\":\"heat_network\",\"diameter\":300},"
            + "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[37.600,55.750],[37.600,55.753]]}},\n"
            + "{\"type\":\"Feature\",\"properties\":{\"id\":\"c1\",\"object_type\":\"heat_chamber\"},"
            + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.600,55.753]}},\n"
            + "{\"type\":\"Feature\",\"properties\":{\"id\":\"o1\",\"object_type\":\"oks_connection_point\",\"flow_tph\":20.0},"
            + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6016,55.7515]}}\n"
            + "]}";

    @Test
    void uploadProcessAndDownload() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "input.geojson",
                "application/geo+json", TINY_INPUT.getBytes(StandardCharsets.UTF_8));

        MvcResult created = mockMvc.perform(multipart("/api/v1/tracing/jobs").file(file))
                .andExpect(status().isAccepted())
                .andReturn();
        JsonNode createdBody = mapper.readTree(created.getResponse().getContentAsString());
        String jobId = createdBody.get("job_id").asText();
        assertNotNull(jobId);

        // асинхронная обработка стартует сама; дожидаемся завершения
        for (int i = 0; i < 100 && !isFinished(jobId); i++) {
            Thread.sleep(200);
        }
        assertEquals("DONE", jobService.get(jobId).getStatus().name());

        MvcResult statusResult = mockMvc.perform(get("/api/v1/tracing/jobs/" + jobId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode statusBody = mapper.readTree(statusResult.getResponse().getContentAsString());
        assertEquals("DONE", statusBody.get("status").asText());

        MvcResult depth = mockMvc.perform(get("/api/v1/tracing/jobs/" + jobId + "/result-depth"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode depthGeo = mapper.readTree(depth.getResponse().getContentAsByteArray());
        boolean hasDepthAttr = false;
        for (JsonNode f : depthGeo.get("features")) {
            if ("heat_network".equals(f.path("properties").path("object_type").asText())) {
                hasDepthAttr = f.path("properties").path("depth_start").isNumber();
                break;
            }
        }
        assertEquals(true, hasDepthAttr, "в режиме с глубиной depth_start должен быть числом");

        MvcResult report = mockMvc.perform(get("/api/v1/tracing/jobs/" + jobId + "/report"))
                .andExpect(status().isOk())
                .andReturn();
        byte[] pdf = report.getResponse().getContentAsByteArray();
        assertEquals('%', pdf[0]);
        assertEquals('P', pdf[1]);

        MvcResult download = mockMvc.perform(get("/api/v1/tracing/jobs/" + jobId + "/result"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode geo = mapper.readTree(download.getResponse().getContentAsByteArray());
        assertEquals("FeatureCollection", geo.get("type").asText());
        boolean hasSummary = false;
        for (JsonNode f : geo.get("features")) {
            if ("variant_summary".equals(f.path("properties").path("object_type").asText())) {
                hasSummary = true;
                assertEquals(0, f.path("properties").path("unconnected_oks_ids").size());
            }
        }
        assertEquals(true, hasSummary);
    }

    private boolean isFinished(String jobId) {
        String status = jobService.get(jobId).getStatus().name();
        return "DONE".equals(status) || "ERROR".equals(status);
    }
}
