package ru.lct.heatnet.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import ru.lct.heatnet.HeatnetApplication;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JobRestartRecoveryTest {
    @TempDir Path storage;

    @Test
    void restartFailsInterruptedJobsButPreservesCompletedJobs() {
        String url = "jdbc:h2:mem:restart-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        Instant originalFinish = Instant.parse("2026-10-18T12:00:00Z");
        try (ConfigurableApplicationContext first = start(url)) {
            JobRepository repository = first.getBean(JobRepository.class);
            for (JobEntity.Status status : JobEntity.Status.values()) {
                JobEntity job = new JobEntity();
                job.setId(status.name());
                job.setStatus(status);
                job.setCreatedAt(originalFinish.minusSeconds(10));
                job.setInputPath(storage.resolve(status + "-input.geojson").toString());
                if (status != JobEntity.Status.QUEUED) job.setStartedAt(originalFinish.minusSeconds(5));
                job.setResultPath("old-result.geojson");
                job.setDepthResultPath("old-depth.geojson");
                job.setReportPath("old-report.pdf");
                job.setSummaryJson("[]");
                if (status == JobEntity.Status.DONE || status == JobEntity.Status.ERROR) {
                    job.setFinishedAt(originalFinish);
                    if (status == JobEntity.Status.ERROR) job.setErrorMessage("original error");
                }
                repository.save(job);
            }
        }
        try (ConfigurableApplicationContext second = start(url)) {
            JobRepository repository = second.getBean(JobRepository.class);
            for (JobEntity.Status status : new JobEntity.Status[]{JobEntity.Status.QUEUED, JobEntity.Status.RUNNING}) {
                JobEntity job = repository.findById(status.name()).orElseThrow();
                assertEquals(JobEntity.Status.ERROR, job.getStatus(), "Задача " + status + " не должна висеть после перезапуска");
                assertNotNull(job.getFinishedAt());
                assertTrue(job.getErrorMessage().contains("перезапуск"));
                assertTrue(job.getErrorMessage().contains("повторно"));
                assertNull(job.getResultPath());
                assertNull(job.getDepthResultPath());
                assertNull(job.getReportPath());
                assertNull(job.getSummaryJson());
                assertNotNull(job.getInputPath());
            }
            JobEntity done = repository.findById("DONE").orElseThrow();
            assertEquals(JobEntity.Status.DONE, done.getStatus());
            assertEquals(originalFinish, done.getFinishedAt());
            assertEquals("old-result.geojson", done.getResultPath());
            JobEntity failed = repository.findById("ERROR").orElseThrow();
            assertEquals("original error", failed.getErrorMessage());
            assertEquals(originalFinish, failed.getFinishedAt());
        }
    }

    private ConfigurableApplicationContext start(String url) {
        return new SpringApplicationBuilder(HeatnetApplication.class).web(WebApplicationType.NONE)
                .profiles("test").run("--spring.datasource.url=" + url,
                        "--spring.jpa.hibernate.ddl-auto=update", "--heatnet.storage-dir=" + storage,
                        "--logging.level.root=WARN");
    }
}
