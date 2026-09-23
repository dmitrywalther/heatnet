package ru.lct.heatnet.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ru.lct.heatnet.geo.GeoJsonReader;
import ru.lct.heatnet.geo.GeoJsonWriter;
import ru.lct.heatnet.geo.Projection;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.network.VariantResult;
import ru.lct.heatnet.service.TracingEngine;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
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
        List<String> input = args.getOptionValues("input");
        List<String> output = args.getOptionValues("output");
        if (input == null || input.isEmpty() || output == null || output.isEmpty()) {
            log.error("Использование: --input=<входной GeoJSON> --output=<файл результата>");
            System.exit(2);
            return;
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

        ru.lct.heatnet.service.TracingResult result = new TracingEngine(rules).run(model);
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out))) {
            new GeoJsonWriter(projection).write(result.variants, os);
        }

        // дополнительный режим с учётом глубины — отдельный файл
        String base = out.getFileName().toString().replaceAll("\\.geojson$", "");
        Path depthOut = out.resolveSibling(base + "-depth.geojson");
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(depthOut))) {
            new GeoJsonWriter(projection).write(result.depthVariants, os);
        }
        log.info("Режим с учётом глубины: {}", depthOut);

        // пояснительная записка рядом с результатом
        Path report = out.resolveSibling(base + "-report.pdf");
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(report))) {
            new ru.lct.heatnet.report.PdfReportWriter(model, result.variants,
                    result.depthVariants).write(os);
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
        System.exit(0);
    }
}
