package ru.lct.heatnet.refdata;

import java.util.Collections;
import java.util.List;
import java.util.Arrays;

/**
 * Таблица 1 технического приложения. Порядок строк — по возрастанию ДУ
 * (номенклатурный ряд).
 */
public final class PipeTable {

    private static final List<PipeGrade> GRADES = Collections.unmodifiableList(Arrays.asList(
            new PipeGrade(50, 3.5, 181, 74_023, 0.400, 0.125),
            new PipeGrade(65, 8.3, 245, 78_631, 0.430, 0.140),
            new PipeGrade(80, 13.2, 327, 83_530, 0.470, 0.160),
            new PipeGrade(100, 22.3, 419, 89_748, 0.510, 0.180),
            new PipeGrade(125, 40.2, 554, 97_275, 0.600, 0.225),
            new PipeGrade(150, 65.1, 696, 105_507, 0.650, 0.250),
            new PipeGrade(200, 152.3, 1042, 120_275, 0.880, 0.315),
            new PipeGrade(250, 274.9, 1379, 135_323, 1.050, 0.400),
            new PipeGrade(300, 437.4, 1718, 150_022, 1.150, 0.450),
            new PipeGrade(400, 943.1, 2477, 190_299, 1.370, 0.560),
            new PipeGrade(500, 1663.4, 3245, 224_137, 1.670, 0.710),
            new PipeGrade(600, 2627.7, 4037, 264_790, 1.850, 0.800),
            new PipeGrade(700, 3735.1, 4775, 324_298, 2.050, 0.900),
            new PipeGrade(800, 5296.8, 5644, 325_996, 2.250, 1.000),
            new PipeGrade(900, 7165.0, 6518, 327_693, 2.450, 1.100),
            new PipeGrade(1000, 9391.8, 7419, 418_777, 2.650, 1.200),
            new PipeGrade(1200, 15_012.8, 9288, 428_074, 3.100, 1.425),
            new PipeGrade(1400, 22_501.9, 11_276, 683_417, 3.450, 1.600)));

    private PipeTable() {
    }

    public static List<PipeGrade> grades() {
        return GRADES;
    }

    /** Минимальный ДУ, пропускная способность которого не меньше расхода. */
    public static PipeGrade minGradeForFlow(double flowTph) {
        for (PipeGrade g : GRADES) {
            if (g.getCapacityTph() >= flowTph) {
                return g;
            }
        }
        return GRADES.get(GRADES.size() - 1);
    }

    public static PipeGrade byDu(int du) {
        for (PipeGrade g : GRADES) {
            if (g.getDu() == du) {
                return g;
            }
        }
        throw new IllegalArgumentException("Неизвестный условный диаметр: " + du);
    }

    /** Ближайший (не меньший) номенклатурный ДУ для произвольного значения диаметра. */
    public static PipeGrade byDuAtLeast(int du) {
        for (PipeGrade g : GRADES) {
            if (g.getDu() >= du) {
                return g;
            }
        }
        return GRADES.get(GRADES.size() - 1);
    }

    /** Следующий по номенклатуре ДУ, либо null для максимального. */
    public static PipeGrade next(PipeGrade grade) {
        int i = GRADES.indexOf(grade);
        return i >= 0 && i + 1 < GRADES.size() ? GRADES.get(i + 1) : null;
    }
}
