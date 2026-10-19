package ru.lct.heatnet.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.lct.heatnet.job.JobEntity;
import ru.lct.heatnet.job.JobRepository;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:admission;DB_CLOSE_DELAY=-1",
        "heatnet.tracing.workers=1", "heatnet.tracing.queue-capacity=1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JobApiAdmissionTest {
    @Autowired MockMvc mvc;
    @Autowired JobRepository repository;
    @Autowired @Qualifier("tracingExecutor") ThreadPoolTaskExecutor executor;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fullQueueReturns503AndPersistsTerminalError() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(executor.getMaxPoolSize());
        try {
            int totalSlots = executor.getMaxPoolSize() + executor.getThreadPoolExecutor().getQueue().remainingCapacity();
            for (int i = 0; i < totalSlots; i++) {
                executor.execute(() -> {
                    started.countDown();
                    try { release.await(30, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
            }
            assertTrue(started.await(5, TimeUnit.SECONDS));
            MvcResult response = assertDoesNotThrow(() -> mvc.perform(multipart("/api/v1/tracing/jobs")
                    .file(input())).andReturn(), "Отказ пула должен стать понятным HTTP-ответом");
            assertEquals(503, response.getResponse().getStatus());
            JsonNode body = mapper.readTree(response.getResponse().getContentAsByteArray());
            assertEquals("TRACING_QUEUE_FULL", body.path("code").asText());
            assertEquals("ERROR", body.path("status").asText());
            assertFalse(body.path("message").asText().isBlank());
            JobEntity stored = repository.findById(body.path("job_id").asText()).orElseThrow();
            assertEquals(JobEntity.Status.ERROR, stored.getStatus());
            assertNotNull(stored.getFinishedAt());
            assertEquals(body.path("message").asText(), stored.getErrorMessage());
        } finally {
            release.countDown();
        }
    }

    @Test
    void oversizedRulesAreRejectedBeforePersistingJob() throws Exception {
        long before = repository.count();
        MockMultipartFile rules = new MockMultipartFile("rules", "rules.yml", "application/yaml",
                new byte[1024 * 1024 + 1]);
        MvcResult response = mvc.perform(multipart("/api/v1/tracing/jobs").file(input()).file(rules)).andReturn();
        assertEquals(413, response.getResponse().getStatus());
        JsonNode body = mapper.readTree(response.getResponse().getContentAsByteArray());
        assertEquals("RULES_TOO_LARGE", body.path("code").asText());
        assertEquals(before, repository.count());
    }

    private MockMultipartFile input() {
        return new MockMultipartFile("file", "input.geojson", "application/geo+json",
                "{\"type\":\"FeatureCollection\",\"features\":[]}".getBytes(StandardCharsets.UTF_8));
    }
}
