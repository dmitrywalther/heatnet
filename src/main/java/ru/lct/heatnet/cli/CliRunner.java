package ru.lct.heatnet.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ru.lct.heatnet.geo.GeoJsonReader;
import ru.lct.heatnet.geo.Projection;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.network.VariantResult;
import ru.lct.heatnet.network.VariantDiagnostic;
import ru.lct.heatnet.refdata.RestrictionRules;
import ru.lct.heatnet.service.TracingEngine;
import ru.lct.heatnet.service.TracingResult;
import ru.lct.heatnet.service.ResultFilesWriter;
import ru.lct.heatnet.service.ResultDiagnosticIO;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * CLI-режим для локального запуска без БД:
 * java -jar heatnet.jar --spring.profiles.active=cli --input=in.geojson --output=out.geojson
 */
@Component
@Profile("cli")
public class CliRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CliRunner.class);

    @Override
    public void run(ApplicationArguments args) throws Exception {
        System.exit(execute(args));
    }

    /** Возвращает код завершения; run передаёт его процессу. */
    public int execute(ApplicationArguments args) throws Exception {
        if (args.containsOption("export-connection")) return ExportConnectionRunner.execute(args);
        List<String> input = args.getOptionValues("input");
        List<String> output = args.getOptionValues("output");
        if (input == null || input.isEmpty() || output == null || output.isEmpty()) {
            log.error("Использование: --input=<входной GeoJSON> --output=<файл результата>");
            return 2;
        }
        Path in = Paths.get(input.get(0));
        Path out = Paths.get(output.get(0));
        List<String> rulesArg = args.getOptionValues("rules");
        ru.lct.heatnet.refdata.RestrictionRules rules = ru.lct.heatnet.refdata.RestrictionRules
                .load(rulesArg == null || rulesArg.isEmpty() ? null : Paths.get(rulesArg.get(0)));

        long start = System.currentTimeMillis();
        Projection projection = new Projection();
        InputModel model;
        try (InputStream is = new BufferedInputStream(Files.newInputStream(in))) {
            model = new GeoJsonReader(projection).read(is);
        }
        log.info("Входной набор: участков сети={}, камер={}, точек ОКС={}, ограничений={}",
                model.pipes.size(), model.chambers.size(), model.oksPoints.size(),
                model.restrictions.size());

        TracingResult result = calculate(model, rules);

        // дополнительный режим с учётом глубины — отдельный файл
        String base = out.getFileName().toString().replaceAll("\\.geojson$", "");
        Path depthOut = out.resolveSibling(base + "-depth.geojson");
        // пояснительная записка рядом с результатом
        Path report = out.resolveSibling(base + "-report.pdf");
        boolean available = ResultFilesWriter.write(model, projection, result, out, depthOut, report);
        for (VariantDiagnostic d : result.diagnostics) {
            log.warn("Исключён {} ({}): {} — {}", d.variantId, d.mode, d.code, d.message);
        }
        if (!available) {
            log.error("{} Диагностика: {}", ResultDiagnosticIO.NO_VALID_BASE_MESSAGE,
                    ResultDiagnosticIO.sidecarPath(out));
            return 3;
        }
        if (!result.depthVariants.isEmpty()) {
            log.info("Режим с учётом глубины: {}", depthOut);
        } else {
            log.warn(ResultDiagnosticIO.NO_DEPTH_MESSAGE);
        }
        log.info("Пояснительная записка: {}", report);
        for (VariantResult v : result.variants) {
            log.info("{} [{}]: rank={}, стоимость={} руб., длина={} м, score={}, не подключено: {}",
                    v.variantId, v.strategyName, v.rank,
                    String.format("%,.0f", v.calculatedCost), v.newNetworkLength, v.score,
                    v.unconnectedOksIds.isEmpty() ? "нет" : v.unconnectedOksIds);
        }
        for (VariantResult v : result.depthVariants) {
            log.info("[глубина] {} [{}]: rank={}, стоимость={} руб., score={}",
                    v.variantId, v.strategyName, v.rank,
                    String.format("%,.0f", v.calculatedCost), v.score);
        }
        log.info("Результат записан в {} за {} мс", out, System.currentTimeMillis() - start);
        return 0;
    }

    protected TracingResult calculate(InputModel model, RestrictionRules rules) {
        return new TracingEngine(rules).run(model);
    }
}
