package ru.lct.heatnet.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import ru.lct.heatnet.profile.ConnectionPassport;
import ru.lct.heatnet.profile.ProfileScene;
import ru.lct.heatnet.profile.SavedSceneReader;
import ru.lct.heatnet.refdata.RestrictionRules;

import java.io.OutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Export one saved connection without invoking TracingEngine or changing its result artifacts. */
final class ExportConnectionRunner {
    private static final Logger log = LoggerFactory.getLogger(ExportConnectionRunner.class);
    private ExportConnectionRunner() {}

    static int execute(ApplicationArguments args) throws IOException {
        try {
            String inputArg = single(args, "input", true), resultArg = single(args, "result", true);
            String variant = single(args, "variant", true), key = single(args, "oks-key", true);
            String outputArg = single(args, "output", true), rulesArg = single(args, "rules", false);
            if (!variant.matches("[vd][0-9]+")) throw new IllegalArgumentException("Неизвестный вариант: " + variant);
            Path input = Paths.get(inputArg), result = Paths.get(resultArg), output = Paths.get(outputArg).toAbsolutePath().normalize();
            Path rules = rulesArg == null ? null : Paths.get(rulesArg);
            for (Path source : new Path[]{input, result, rules}) {
                if (source != null && (output.equals(source.toAbsolutePath().normalize())
                        || Files.exists(output) && Files.exists(source) && Files.isSameFile(output, source))) {
                    throw new IllegalArgumentException("PDF нельзя записывать поверх входного GeoJSON, результата или YAML: " + source);
                }
            }
            RestrictionRules effective = RestrictionRules.load(rules);
            ProfileScene scene = SavedSceneReader.read(input, result, effective, variant, variant.startsWith("d"));
            // Validate the selection before creating directories or touching the destination.
            scene.connection(key);
            Path parent = output.getParent();
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, ".heatnet-passport-", ".pdf");
            try {
                try (OutputStream out = Files.newOutputStream(temporary)) {
                    ConnectionPassport.write(out, scene, variant, key);
                }
                try {
                    Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            log.info("Паспорт подключения ОКС записан без повторного расчёта: {}", output);
            return 0;
        } catch (IllegalArgumentException e) {
            log.error("Не удалось выгрузить паспорт подключения: {}", e.getMessage());
            log.error("Использование: --export-connection --input=<GeoJSON> --result=<сохранённый GeoJSON> "
                    + "--variant=<v1|d1> --oks-key=<типизированный ключ> --output=<PDF> [--rules=<YAML>]");
            return 2;
        }
    }

    private static String single(ApplicationArguments args, String name, boolean required) {
        List<String> values = args.getOptionValues(name);
        if (values == null && !required) return null;
        if (values == null || values.size() != 1 || values.get(0).isBlank())
            throw new IllegalArgumentException("Требуется один непустой аргумент --" + name);
        return values.get(0);
    }
}
