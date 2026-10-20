package ru.lct.heatnet.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import ru.lct.heatnet.comparison.CalculationComparison;
import ru.lct.heatnet.refdata.RestrictionRules;
import ru.lct.heatnet.report.ComparisonPdfWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/** Compares persisted results and prepares both output files before replacing either destination. */
final class CompareResultsRunner {
    private static final Logger log = LoggerFactory.getLogger(CompareResultsRunner.class);
    private CompareResultsRunner() {}

    static int execute(ApplicationArguments args) throws IOException {
        try {
            Path beforeInput = Paths.get(single(args, "before-input", true));
            Path beforeResult = Paths.get(single(args, "before-result", true));
            String beforeVariant = single(args, "before-variant", true);
            Path afterInput = Paths.get(single(args, "after-input", true));
            Path afterResult = Paths.get(single(args, "after-result", true));
            String afterVariant = single(args, "after-variant", true);
            Path output = Paths.get(single(args, "output", true)).toAbsolutePath().normalize();
            String beforeRulesArg = single(args, "before-rules", false), afterRulesArg = single(args, "after-rules", false);
            Path beforeRules = beforeRulesArg == null ? null : Paths.get(beforeRulesArg);
            Path afterRules = afterRulesArg == null ? null : Paths.get(afterRulesArg);
            if (!beforeVariant.matches("[vd][0-9]+") || !afterVariant.matches("[vd][0-9]+"))
                throw new IllegalArgumentException("Варианты должны иметь вид v1 или d1");
            if (beforeVariant.charAt(0) != afterVariant.charAt(0))
                throw new IllegalArgumentException("Сравнение базового и глубинного режимов между собой не поддерживается");
            String pdfArg = single(args, "pdf-output", false);
            if (output.getFileName() == null) throw new IllegalArgumentException("Укажите имя файла JSON");
            String name = output.getFileName().toString();
            int dot = name.lastIndexOf('.');
            Path pdfOutput = pdfArg == null
                    ? output.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + ".pdf")
                    : Paths.get(pdfArg).toAbsolutePath().normalize();
            if (same(output, pdfOutput)) throw new IllegalArgumentException("JSON и PDF должны записываться в разные файлы");
            for (Path destination : new Path[]{output, pdfOutput}) {
                if (destination.getFileName() == null || Files.exists(destination) && !Files.isRegularFile(destination))
                    throw new IllegalArgumentException("Выход должен быть обычным файлом: " + destination);
                for (Path input : new Path[]{beforeInput, beforeResult, beforeRules, afterInput, afterResult, afterRules}) {
                    if (input != null && same(destination, input))
                        throw new IllegalArgumentException("Нельзя записывать сравнение поверх входного GeoJSON, результата или YAML: " + input);
                }
            }
            Map<String,Object> comparison = CalculationComparison.compare(beforeInput, beforeResult,
                    RestrictionRules.load(beforeRules), beforeVariant, afterInput, afterResult,
                    RestrictionRules.load(afterRules), afterVariant);
            Files.createDirectories(output.getParent());
            Files.createDirectories(pdfOutput.getParent());
            Path jsonTemp = Files.createTempFile(output.getParent(), ".heatnet-comparison-", ".json"), pdfTemp = null;
            try {
                pdfTemp = Files.createTempFile(pdfOutput.getParent(), ".heatnet-comparison-", ".pdf");
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(jsonTemp.toFile(), comparison);
                try (OutputStream pdf = Files.newOutputStream(pdfTemp)) { ComparisonPdfWriter.write(pdf, comparison); }
                // Neither target changes until both serializations have completed successfully.
                replace(jsonTemp, output);
                replace(pdfTemp, pdfOutput);
            } finally {
                Files.deleteIfExists(jsonTemp);
                if (pdfTemp != null) Files.deleteIfExists(pdfTemp);
            }
            log.info("Сравнение сохранённых расчётов: JSON {}, PDF {}. Новый расчёт не выполнялся.", output, pdfOutput);
            return 0;
        } catch (IllegalArgumentException e) {
            log.error("Не удалось сравнить расчёты: {}", e.getMessage());
            log.error("Использование: --compare-results --before-input=<GeoJSON> --before-result=<GeoJSON> --before-variant=<v1|d1> "
                    + "--after-input=<GeoJSON> --after-result=<GeoJSON> --after-variant=<v1|d1> --output=<JSON> "
                    + "[--pdf-output=<PDF>] [--before-rules=<YAML>] [--after-rules=<YAML>]");
            return 2;
        }
    }

    private static boolean same(Path a, Path b) throws IOException {
        return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize())
                || Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b);
    }
    private static void replace(Path source, Path destination) throws IOException {
        try { Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING); }
    }
    private static String single(ApplicationArguments args, String name, boolean required) {
        List<String> values = args.getOptionValues(name);
        if (values == null && !required) return null;
        if (values == null || values.size() != 1 || values.get(0).isBlank())
            throw new IllegalArgumentException("Требуется один непустой аргумент --" + name);
        return values.get(0);
    }
}
