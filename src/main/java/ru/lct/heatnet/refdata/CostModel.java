package ru.lct.heatnet.refdata;

/**
 * Стоимостные правила разделов 3.2 и 6 технического приложения.
 */
public final class CostModel {

    /** Стоимость врезки в существующую тепловую камеру, руб. */
    public static final double TIE_IN_TO_EXISTING_CHAMBER_COST = 5_000_000;

    /** Весовые нормировки итогового показателя S (раздел 6). */
    public static final double SCORE_COST_WEIGHT = 0.7;
    public static final double SCORE_COST_NORM = 25_000_000;
    public static final double SCORE_LENGTH_WEIGHT = 0.3;
    public static final double SCORE_LENGTH_NORM = 100;

    private CostModel() {
    }

    /** Стоимость новой тепловой камеры по наибольшему ДУ примыкающих участков. */
    public static double chamberCost(int maxAdjacentDu) {
        if (maxAdjacentDu <= 200) {
            return 3_000_000;
        }
        if (maxAdjacentDu <= 500) {
            return 5_000_000;
        }
        if (maxAdjacentDu <= 1000) {
            return 8_000_000;
        }
        return 12_000_000;
    }

    /** Штраф за одну неподключённую точку подключения (раздел 6). */
    public static double unconnectedPenalty(double flowTph) {
        return 100_000_000 + 500_000 * flowTph;
    }

    /** Итоговый показатель варианта: чем меньше, тем лучше. */
    public static double score(double calculatedCost, double newNetworkLength) {
        return SCORE_COST_WEIGHT * (calculatedCost / SCORE_COST_NORM)
                + SCORE_LENGTH_WEIGHT * (newNetworkLength / SCORE_LENGTH_NORM);
    }
}
