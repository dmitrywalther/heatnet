package ru.lct.heatnet.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ru.lct.heatnet.comparison.CalculationComparison;
import ru.lct.heatnet.job.JobEntity;
import ru.lct.heatnet.job.JobService;
import ru.lct.heatnet.refdata.RestrictionRules;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/** Resolves only saved DONE jobs; external callers never supply filesystem paths. */
@Service
@Profile("!cli")
public class ComparisonService {
    private final JobService jobs;
    private final RestrictionRules serverRules;

    public ComparisonService(JobService jobs, @Value("${heatnet.rules-file:}") String rulesFile) throws IOException {
        this.jobs = jobs;
        this.serverRules = RestrictionRules.load(rulesFile == null || rulesFile.isBlank() ? null : Paths.get(rulesFile));
    }

    public Map<String,Object> compare(String beforeJob, String beforeVariant, String afterJob, String afterVariant) throws IOException {
        try {
            Saved before = saved(beforeJob, beforeVariant), after = saved(afterJob, afterVariant);
            return CalculationComparison.compare(before.input, before.result, before.rules, beforeVariant,
                    after.input, after.result, after.rules, afterVariant);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
    }

    private Saved saved(String id, String variant) throws IOException {
        if (variant == null || !variant.matches("[vd][0-9]+"))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Неизвестный вариант сравнения: " + variant);
        JobEntity job = jobs.get(id);
        if (job == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Задача не найдена: " + id);
        String resultPath = variant.startsWith("d") ? job.getDepthResultPath() : job.getResultPath();
        if (job.getStatus() != JobEntity.Status.DONE || job.getInputPath() == null || resultPath == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Сохранённый результат недоступен для задачи " + id + " и варианта " + variant);
        Path input = Paths.get(job.getInputPath()), result = Paths.get(resultPath);
        if (!Files.isRegularFile(input) || !Files.isRegularFile(result))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Файлы расчёта недоступны для задачи " + id);
        Path rules = input.resolveSibling(job.getId() + "-rules.yml");
        RestrictionRules effective = serverRules;
        if (Files.exists(rules)) {
            if (!Files.isRegularFile(rules))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Сохранённый YAML недоступен для задачи " + id);
            try (InputStream in = Files.newInputStream(rules)) { effective = serverRules.mergeYaml(in); }
        }
        return new Saved(input, result, effective);
    }

    private static final class Saved {
        final Path input, result;
        final RestrictionRules rules;
        Saved(Path input, Path result, RestrictionRules rules) {
            this.input = input; this.result = result; this.rules = rules;
        }
    }
}
