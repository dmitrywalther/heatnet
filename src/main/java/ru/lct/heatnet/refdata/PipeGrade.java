package ru.lct.heatnet.refdata;

/**
 * Строка таблицы 1 технического приложения: условный диаметр, пропускная
 * способность, предельная длина, стоимость строительства и расчётный габарит.
 */
public final class PipeGrade {

    private final int du;                 // условный диаметр, мм
    private final double capacityTph;     // пропускная способность, т/ч
    private final double limitLengthM;    // предельная длина непрерывной части, м
    private final double costPerMeter;    // новое строительство, руб./м
    private final double pairWidthM;      // расчётная ширина пары, м
    private final double heightM;         // расчётная высота, м

    public PipeGrade(int du, double capacityTph, double limitLengthM,
                     double costPerMeter, double pairWidthM, double heightM) {
        this.du = du;
        this.capacityTph = capacityTph;
        this.limitLengthM = limitLengthM;
        this.costPerMeter = costPerMeter;
        this.pairWidthM = pairWidthM;
        this.heightM = heightM;
    }

    public int getDu() {
        return du;
    }

    public double getCapacityTph() {
        return capacityTph;
    }

    public double getLimitLengthM() {
        return limitLengthM;
    }

    public double getCostPerMeter() {
        return costPerMeter;
    }

    public double getPairWidthM() {
        return pairWidthM;
    }

    public double getHalfWidthM() {
        return pairWidthM / 2.0;
    }

    public double getHeightM() {
        return heightM;
    }
}
