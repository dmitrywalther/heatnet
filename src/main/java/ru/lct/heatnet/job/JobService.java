package ru.lct.heatnet.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.lct.heatnet.geo.GeoJsonReader;
import ru.lct.heatnet.geo.GeoJsonWriter;
import ru.lct.heatnet.geo.InputValidationException;
import ru.lct.heatnet.geo.Projection;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.network.VariantResult;
import ru.lct.heatnet.service.TracingEngine;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Управление задачами расчёта: входной файл сохраняется во временное дисковое
 * хранилище и обрабатывается асинхронно (файлы до 3 ГБ не держатся в памяти).
 */
@Service
@org.springframework.context.annotation.Profile("!cli")
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobRepository repository;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path storageDir;
    private final ru.lct.heatnet.refdata.RestrictionRules rules;

    public JobService(JobRepository repository,
                      @Value("${heatnet.storage-dir:./data}") String storageDir,
                      @Value("${heatnet.rules-file:}") String rulesFile) throws IOException {
        this.repository = repository;
        this.storageDir = Paths.get(storageDir);
        Files.createDirectories(this.storageDir);
        this.rules = ru.lct.heatnet.refdata.RestrictionRules.load(
                rulesFile == null || rulesFile.isBlank() ? null : Paths.get(rulesFile));
    }

    /** Создаёт задачу, сохраняя входной поток на диск. */
    public JobEntity createJob(InputStream input) throws IOException {
        return createJob(input, null);
    }

    /** @param rulesYaml необязательное переопределение справочника ограничений */
    public JobEntity createJob(InputStream input, String rulesYaml) throws IOException {
        String id = UUID.randomUUID().toString();
        Path inputPath = storageDir.resolve(id + "-input.geojson");
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(inputPath))) {
            input.transferTo(out);
        }
        if (rulesYaml != null && !rulesYaml.isBlank()) {
            Files.writeString(storageDir.resolve(id + "-rules.yml"), rulesYaml);
        }
        JobEntity job = new JobEntity();
        job.setId(id);
        job.setStatus(JobEntity.Status.QUEUED);
        job.setCreatedAt(Instant.now());
        job.setInputPath(inputPath.toString());
        return repository.save(job);
    }

    @Async("tracingExecutor")
    public void processAsync(String jobId) {
        JobEntity job = repository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        job.setStatus(JobEntity.Status.RUNNING);
        job.setStartedAt(Instant.now());
        repository.save(job);
        try {
            Path resultPath = storageDir.resolve(jobId + "-result.geojson");
            Path depthPath = storageDir.resolve(jobId + "-result-depth.geojson");
            Path reportPath = storageDir.resolve(jobId + "-report.pdf");
            // пер-задачное переопределение справочника (редактор правил в UI)
            ru.lct.heatnet.refdata.RestrictionRules effectiveRules = this.rules;
            Path jobRules = storageDir.resolve(jobId + "-rules.yml");
            if (Files.exists(jobRules)) {
                try (InputStream in = Files.newInputStream(jobRules)) {
                    effectiveRules = this.rules.mergeYaml(in);
                }
                log.info("Задача {}: применено переопределение справочника ({} типов)",
                        jobId, effectiveRules.size());
            }
            ru.lct.heatnet.service.TracingResult result =
                    process(Paths.get(job.getInputPath()), resultPath, depthPath, reportPath,
                            effectiveRules);
            job.setResultPath(resultPath.toString());
            job.setDepthResultPath(depthPath.toString());
            job.setReportPath(reportPath.toString());
            job.setSummaryJson(summary(result.variants));
            job.setStatus(JobEntity.Status.DONE);
        } catch (InputValidationException e) {
            job.setStatus(JobEntity.Status.ERROR);
            job.setErrorMessage("Ошибка входных данных: " + String.join("; ", e.getProblems()));
        } catch (Exception e) {
            log.error("Job {} failed", jobId, e);
            job.setStatus(JobEntity.Status.ERROR);
            job.setErrorMessage(e.toString());
        } finally {
            job.setFinishedAt(Instant.now());
            repository.save(job);
        }
    }

    /** Синхронная обработка файла (используется и CLI-режимом). */
    public ru.lct.heatnet.service.TracingResult process(Path inputPath, Path resultPath,
                                                        Path depthPath, Path reportPath,
                                                        ru.lct.heatnet.refdata.RestrictionRules effectiveRules)
            throws IOException {
        Projection projection = new Projection();
        InputModel model;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(inputPath))) {
            model = new GeoJsonReader(projection).read(in);
        }
        log.info("Входной набор: источников={}, участков сети={}, камер={}, точек ОКС={}, ограничений={}",
                model.sources.size(), model.pipes.size(), model.chambers.size(),
                model.oksPoints.size(), model.restrictions.size());
        ru.lct.heatnet.service.TracingResult result = new TracingEngine(
                effectiveRules != null ? effectiveRules : rules).run(model);
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(resultPath))) {
            new GeoJsonWriter(projection).write(result.variants, out);
        }
        if (depthPath != null) {
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(depthPath))) {
                new GeoJsonWriter(projection).write(result.depthVariants, out);
            }
        }
        if (reportPath != null) {
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(reportPath))) {
                new ru.lct.heatnet.report.PdfReportWriter(model, result.variants,
                        result.depthVariants).write(out);
            }
        }
        return result;
    }

    private String summary(List<VariantResult> variants) throws IOException {
        ArrayNode array = mapper.createArrayNode();
        for (VariantResult v : variants) {
            ObjectNode node = array.addObject();
            node.put("variant_id", v.variantId);
            node.put("strategy", v.strategyName);
            node.put("rank", v.rank);
            node.put("construction_cost", v.constructionCost);
            node.put("calculated_cost", v.calculatedCost);
            node.put("new_network_length", v.newNetworkLength);
            node.put("score", v.score);
            node.put("unconnected_count", v.unconnectedOksIds.size());
        }
        return mapper.writeValueAsString(array);
    }

    public JobEntity get(String id) {
        return repository.findById(id).orElse(null);
    }
}
