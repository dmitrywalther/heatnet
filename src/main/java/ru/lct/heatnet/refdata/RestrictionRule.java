package ru.lct.heatnet.refdata;

import java.util.List;

/**
 * Правило учёта пространственного ограничения (строка таблицы 2 технического
 * приложения). Набор правил формируется {@link RestrictionRules}: встроенные
 * значения плюс необязательное YAML-переопределение.
 */
public final class RestrictionRule {

    public enum Mode {
        /** Пересечение запрещено — объект обходится с минимальным отступом. */
        FORBIDDEN,
        /** Допускается специальный проход при выполнении условий. */
        SPECIAL
    }

    /** Порог отступа, зависящий от ДУ новой сети: действует при du <= maxDu. */
    public static final class DuThreshold {
        public final int maxDu;
        public final double distanceM;

        public DuThreshold(int maxDu, double distanceM) {
            this.maxDu = maxDu;
            this.distanceM = distanceM;
        }
    }

    /** Вертикальные условия пересечения (дополнительный режим с глубиной). */
    public static final class Vertical {
        /** true — только под объектом (дороги, трамвай); false — выше или ниже. */
        public final boolean underOnly;
        /** Для underOnly: минимальная глубина верха габарита новой сети, м. */
        public final double minTopDepthM;
        /** Минимальный вертикальный просвет между габаритами, м. */
        public final double clearanceM;
        /** Условная глубина верха габарита препятствия, м (для above/below). */
        public final double obstacleTopDepthM;
        /** Высота габарита препятствия, м (для существующей теплосети — по ДУ). */
        public final double obstacleHeightM;

        public Vertical(boolean underOnly, double minTopDepthM, double clearanceM,
                        double obstacleTopDepthM, double obstacleHeightM) {
            this.underOnly = underOnly;
            this.minTopDepthM = minTopDepthM;
            this.clearanceM = clearanceM;
            this.obstacleTopDepthM = obstacleTopDepthM;
            this.obstacleHeightM = obstacleHeightM;
        }
    }

    private final String type;
    private final Mode mode;
    private final double minHorizontalDistanceM;
    /** Пороги по ДУ (упорядочены по maxDu); если заданы — имеют приоритет. */
    private final List<DuThreshold> distanceByDu;
    private final Double minCrossingAngleDeg;
    /**
     * Полуширина границы специального участка: для полигонов — отступ за границу
     * с каждой стороны (3 м), для линейных объектов — отступ от точки пересечения
     * вдоль трассы (2 м).
     */
    private final double specialMarginM;
    private final double kSpec;
    /** Собственный расчётный габарит препятствия (полуширина), м. */
    private final double ownHalfWidthM;
    /** Вертикальные условия пересечения; null — не нормируются. */
    private final Vertical vertical;

    public RestrictionRule(String type, Mode mode, double minHorizontalDistanceM,
                           List<DuThreshold> distanceByDu, Double minCrossingAngleDeg,
                           double specialMarginM, double kSpec, double ownHalfWidthM) {
        this(type, mode, minHorizontalDistanceM, distanceByDu, minCrossingAngleDeg,
                specialMarginM, kSpec, ownHalfWidthM, null);
    }

    public RestrictionRule(String type, Mode mode, double minHorizontalDistanceM,
                           List<DuThreshold> distanceByDu, Double minCrossingAngleDeg,
                           double specialMarginM, double kSpec, double ownHalfWidthM,
                           Vertical vertical) {
        this.type = type;
        this.mode = mode;
        this.minHorizontalDistanceM = minHorizontalDistanceM;
        this.distanceByDu = distanceByDu == null ? List.of() : List.copyOf(distanceByDu);
        this.minCrossingAngleDeg = minCrossingAngleDeg;
        this.specialMarginM = specialMarginM;
        this.kSpec = kSpec;
        this.ownHalfWidthM = ownHalfWidthM;
        this.vertical = vertical;
    }

    public Vertical getVertical() {
        return vertical;
    }

    /** Минимальный горизонтальный отступ для заданного ДУ новой сети. */
    public double clearanceForDu(int du) {
        if (distanceByDu.isEmpty()) {
            return minHorizontalDistanceM;
        }
        for (DuThreshold t : distanceByDu) {
            if (du <= t.maxDu) {
                return t.distanceM;
            }
        }
        return distanceByDu.get(distanceByDu.size() - 1).distanceM;
    }

    public String getType() {
        return type;
    }

    public Mode getMode() {
        return mode;
    }

    public double getMinHorizontalDistanceM() {
        return minHorizontalDistanceM;
    }

    public List<DuThreshold> getDistanceByDu() {
        return distanceByDu;
    }

    public Double getMinCrossingAngleDeg() {
        return minCrossingAngleDeg;
    }

    public double getSpecialMarginM() {
        return specialMarginM;
    }

    public double getKSpec() {
        return kSpec;
    }

    public double getOwnHalfWidthM() {
        return ownHalfWidthM;
    }
}
