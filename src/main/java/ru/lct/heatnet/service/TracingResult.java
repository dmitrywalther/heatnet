package ru.lct.heatnet.service;

import ru.lct.heatnet.network.VariantResult;
import ru.lct.heatnet.network.VariantDiagnostic;

import java.util.List;

/**
 * Результат расчёта: базовый двумерный режим и дополнительный режим
 * с учётом глубины. Наборы вариантов не объединяются в одно ранжирование
 * и выгружаются отдельными файлами (раздел 5 техприложения).
 */
public class TracingResult {

    public final List<VariantResult> variants;
    public final List<VariantResult> depthVariants;
    public final List<VariantDiagnostic> diagnostics;

    public TracingResult(List<VariantResult> variants, List<VariantResult> depthVariants) {
        this(variants, depthVariants, List.of());
    }

    public TracingResult(List<VariantResult> variants, List<VariantResult> depthVariants,
                         List<VariantDiagnostic> diagnostics) {
        this.variants = variants;
        this.depthVariants = depthVariants;
        this.diagnostics = List.copyOf(diagnostics);
    }
}
