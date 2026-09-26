package ru.lct.heatnet.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.lct.heatnet.refdata.RestrictionRules;

import java.io.IOException;
import java.nio.file.Paths;

/**
 * Справочник пространственных ограничений: просмотр действующих правил.
 * Изменённый YAML передаётся вместе с задачей расчёта (поле rules).
 */
@RestController
@Profile("!cli")
@RequestMapping("/api/v1/rules")
@Tag(name = "rules", description = "Справочник пространственных ограничений")
public class RulesController {

    private final RestrictionRules rules;

    public RulesController(@Value("${heatnet.rules-file:}") String rulesFile) throws IOException {
        this.rules = RestrictionRules.load(
                rulesFile == null || rulesFile.isBlank() ? null : Paths.get(rulesFile));
    }

    @Operation(summary = "Действующий справочник ограничений (YAML)",
            description = "Встроенная таблица 2 техприложения с учётом файла переопределений. "
                    + "Отредактированный YAML можно передать вместе с задачей расчёта.")
    @GetMapping(produces = "text/yaml;charset=UTF-8")
    public ResponseEntity<String> current() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/yaml;charset=UTF-8"))
                .body(rules.toYaml());
    }

    @Operation(summary = "Действующий справочник ограничений (JSON)",
            description = "Та же структура, что и в YAML — для структурного редактора веб-интерфейса.")
    @GetMapping(value = "/json", produces = MediaType.APPLICATION_JSON_VALUE)
    public java.util.Map<String, Object> currentJson() {
        return rules.toMap();
    }
}
