package ru.lct.heatnet.network;

import java.util.List;
import java.util.Objects;

/** Причина исключения варианта; segmentIds относятся к его собственной сборке. */
public final class VariantDiagnostic {
    public final String variantId;
    /** "base" или "depth"; не зависит от номера варианта и ранга. */
    public final String mode;
    public final String code;
    public final String message;
    public final List<String> segmentIds;

    public VariantDiagnostic(String variantId, String mode, String code, String message) {
        this(variantId, mode, code, message, List.of());
    }

    public VariantDiagnostic(String variantId, String mode, String code, String message,
                             List<String> segmentIds) {
        this.variantId = Objects.requireNonNull(variantId);
        if (!"base".equals(mode) && !"depth".equals(mode)) {
            throw new IllegalArgumentException("Неизвестный режим диагностики: " + mode);
        }
        this.mode = mode;
        this.code = Objects.requireNonNull(code);
        this.message = Objects.requireNonNull(message);
        this.segmentIds = List.copyOf(segmentIds);
    }
}
