package ru.lct.heatnet.api;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.lct.heatnet.report.ComparisonPdfWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

/** JSON and PDF are two representations of the same saved-result comparison. */
@RestController
@Profile("!cli")
@RequestMapping("/api/v1/tracing")
public class ComparisonController {
    private final ComparisonService comparisons;
    public ComparisonController(ComparisonService comparisons) { this.comparisons = comparisons; }

    @Operation(summary = "Сравнить два сохранённых расчёта",
            description = "Выберите задания DONE и явные варианты одного режима. JSON содержит стоимости, изменения подключений и геометрию; нового расчёта не выполняет.")
    @GetMapping("/comparison")
    public ResponseEntity<Map<String,Object>> comparison(
            @RequestParam("before_job_id") String beforeJob, @RequestParam("before_variant_id") String beforeVariant,
            @RequestParam("after_job_id") String afterJob, @RequestParam("after_variant_id") String afterVariant) {
        try {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"heatnet-comparison.json\"")
                    .body(comparisons.compare(beforeJob, beforeVariant, afterJob, afterVariant));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Не удалось прочитать сохранённые расчёты для сравнения", e);
        }
    }

    @Operation(summary = "PDF-сравнение двух сохранённых расчётов",
            description = "Показатели, схема до/после и изменения подключений по тем же данным, что JSON-сравнение. Без повторного расчёта.")
    @GetMapping("/comparison.pdf")
    public ResponseEntity<byte[]> comparisonPdf(
            @RequestParam("before_job_id") String beforeJob, @RequestParam("before_variant_id") String beforeVariant,
            @RequestParam("after_job_id") String afterJob, @RequestParam("after_variant_id") String afterVariant) {
        try {
            Map<String,Object> comparison = comparisons.compare(beforeJob, beforeVariant, afterJob, afterVariant);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ComparisonPdfWriter.write(out, comparison);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"heatnet-comparison.pdf\"")
                    .body(out.toByteArray());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Не удалось сформировать PDF-сравнение", e);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> unavailable(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatus()).body(Map.of("code", "COMPARISON_UNAVAILABLE",
                "message", exception.getReason() == null ? "Сравнение недоступно" : exception.getReason()));
    }
}
