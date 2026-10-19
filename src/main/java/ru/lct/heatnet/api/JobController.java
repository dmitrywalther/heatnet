package ru.lct.heatnet.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ru.lct.heatnet.job.JobEntity;
import ru.lct.heatnet.job.JobService;
import ru.lct.heatnet.service.ResultDiagnosticIO;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/**
 * REST API сервиса трассировки: загрузка входного GeoJSON, статус расчёта,
 * выгрузка результата.
 */
@RestController
@org.springframework.context.annotation.Profile("!cli")
@RequestMapping("/api/v1/tracing")
@Tag(name = "tracing", description = "Моделирование трасс подключения к тепловым сетям")
public class JobController {

    private static final long MAX_RULES_BYTES = 1024L * 1024L;

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @Operation(summary = "Загрузить входной GeoJSON и запустить расчёт",
            description = "Принимает FeatureCollection в WGS 84 (до 3 ГБ). Необязательное "
                    + "поле rules — YAML-переопределение справочника ограничений для этой "
                    + "задачи. Возвращает id задачи.")
    @PostMapping(value = "/jobs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> createJob(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "rules", required = false) MultipartFile rules)
            throws IOException {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Файл пуст");
        }
        if (rules != null && rules.getSize() > MAX_RULES_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                    "code", "RULES_TOO_LARGE", "message", "Размер YAML со справочником не должен превышать 1 МиБ."));
        }
        String rulesYaml = rules == null || rules.isEmpty()
                ? null
                : new String(rules.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
        JobEntity job;
        try (InputStream in = file.getInputStream()) {
            job = jobService.createJob(in, rulesYaml);
        }
        try {
            jobService.processAsync(job.getId());
        } catch (RejectedExecutionException e) {
            jobService.markSubmissionRejected(job.getId());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "job_id", job.getId(), "status", JobEntity.Status.ERROR,
                    "code", "TRACING_QUEUE_FULL", "message", JobService.QUEUE_REJECTED_MESSAGE));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("job_id", job.getId());
        body.put("status", job.getStatus());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    @Operation(summary = "Статус задачи расчёта")
    @GetMapping("/jobs/{id}")
    public Map<String, Object> status(@PathVariable String id) throws IOException {
        JobEntity job = require(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("job_id", job.getId());
        body.put("status", job.getStatus());
        body.put("created_at", job.getCreatedAt());
        body.put("started_at", job.getStartedAt());
        body.put("finished_at", job.getFinishedAt());
        if (job.getErrorMessage() != null) {
            body.put("error", job.getErrorMessage());
        }
        if (job.getSummaryJson() != null) {
            body.put("variants", RawJson.parse(job.getSummaryJson()));
        }
        body.put("diagnostics", jobService.diagnostics(job));
        body.put("result_available", available(job, job.getResultPath()));
        body.put("depth_available", available(job, job.getDepthResultPath()));
        body.put("report_available", available(job, job.getReportPath()));
        return body;
    }

    @Operation(summary = "Выгрузить результат (GeoJSON, WGS 84)")
    @GetMapping(value = "/jobs/{id}/result")
    public ResponseEntity<?> result(@PathVariable String id) throws IOException {
        JobEntity job = require(id);
        if (!available(job, job.getResultPath())) {
            return unavailable(job, "RESULT_UNAVAILABLE", "Результат недоступен: статус задачи " + job.getStatus());
        }
        FileSystemResource resource = new FileSystemResource(job.getResultPath());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"heatnet-result.geojson\"")
                .contentType(MediaType.parseMediaType("application/geo+json"))
                .body(resource);
    }

    @Operation(summary = "Результат дополнительного режима с учётом глубины (GeoJSON)",
            description = "Отдельный набор вариантов с атрибутами depth_start/depth_end и Kгл.")
    @GetMapping(value = "/jobs/{id}/result-depth")
    public ResponseEntity<?> resultDepth(@PathVariable String id) throws IOException {
        JobEntity job = require(id);
        if (!available(job, job.getDepthResultPath())) {
            boolean rejected = job.getStatus() == JobEntity.Status.DONE && job.getDepthResultPath() == null;
            return unavailable(job, "DEPTH_RESULT_UNAVAILABLE", rejected
                    ? ResultDiagnosticIO.NO_DEPTH_MESSAGE
                    : "Результат режима с глубиной недоступен: статус задачи " + job.getStatus());
        }
        FileSystemResource resource = new FileSystemResource(job.getDepthResultPath());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"heatnet-result-depth.geojson\"")
                .contentType(MediaType.parseMediaType("application/geo+json"))
                .body(resource);
    }

    @Operation(summary = "Пояснительная записка к расчёту (PDF)",
            description = "Карты вариантов, разбивка стоимости, таблицы участков, методика.")
    @GetMapping(value = "/jobs/{id}/report")
    public ResponseEntity<?> report(@PathVariable String id) throws IOException {
        JobEntity job = require(id);
        if (!available(job, job.getReportPath())) {
            return unavailable(job, "REPORT_UNAVAILABLE", "Отчёт недоступен: статус задачи " + job.getStatus());
        }
        FileSystemResource resource = new FileSystemResource(job.getReportPath());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"heatnet-report.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(resource);
    }

    private static boolean available(JobEntity job, String path) {
        return job.getStatus() == JobEntity.Status.DONE && path != null
                && Files.isRegularFile(Paths.get(path));
    }

    private ResponseEntity<Map<String, Object>> unavailable(JobEntity job, String code, String message)
            throws IOException {
        if (ResultDiagnosticIO.NO_VALID_BASE_MESSAGE.equals(job.getErrorMessage())) {
            code = "NO_VALID_BASE_VARIANT";
            message = job.getErrorMessage();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("diagnostics", jobService.diagnostics(job));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    private JobEntity require(String id) {
        JobEntity job = jobService.get(id);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Задача не найдена: " + id);
        }
        return job;
    }
}
