package ru.lct.heatnet.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.lct.heatnet.job.JobService;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Полный цикл против реального PostgreSQL (Testcontainers): загрузка →
 * асинхронный расчёт → выгрузка результата. Подтверждает работоспособность
 * основного (по ТЗ) хранилища, а не только H2 демо-профиля. На машинах без
 * Docker тест автоматически пропускается.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class JobApiPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15")
                    .withDatabaseName("heatnet")
                    .withUsername("heatnet")
                    .withPassword("heatnet");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name",
                () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

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
    void fullCycleOnRealPostgres() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "input.geojson",
                "application/geo+json", TINY_INPUT.getBytes(StandardCharsets.UTF_8));

        MvcResult created = mockMvc.perform(multipart("/api/v1/tracing/jobs").file(file))
                .andExpect(status().isAccepted())
                .andReturn();
        String jobId = mapper.readTree(created.getResponse().getContentAsString())
                .get("job_id").asText();

        for (int i = 0; i < 150 && !isFinished(jobId); i++) {
            Thread.sleep(200);
        }
        assertEquals("DONE", jobService.get(jobId).getStatus().name());

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
        assertTrue(hasSummary, "в результате должна быть сводка вариантов");

        // статус job хранится именно в PostgreSQL контейнера
        assertTrue(POSTGRES.isRunning());
    }

    private boolean isFinished(String jobId) {
        String status = jobService.get(jobId).getStatus().name();
        return "DONE".equals(status) || "ERROR".equals(status);
    }
}
