package ru.lct.heatnet.geo;

import java.util.List;

/**
 * Диагностическая ошибка входных данных: невалидная геометрия, отсутствие
 * обязательных атрибутов, неподдерживаемый тип геометрии и т.п.
 */
public class InputValidationException extends RuntimeException {

    private final List<String> problems;

    public InputValidationException(List<String> problems) {
        super("Входной файл не прошёл проверку: " + String.join("; ", problems));
        this.problems = problems;
    }

    public List<String> getProblems() {
        return problems;
    }
}
