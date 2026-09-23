package ru.lct.heatnet.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * Кусочно-линейный профиль глубины вдоль ребра (дополнительный режим,
 * раздел 5 техприложения). Глубина h — до верхней границы расчётного
 * габарита новой сети; поверхность земли условно горизонтальна.
 *
 * <p>Каждое пересечение задаёт окно допустимых глубин на интервале дуги.
 * Если окно содержит обычную глубину 3,0 м — профиль не меняется; иначе
 * выбирается ближайшая к 3,0 граница окна (минимальные Kгл и рампы).
 * Переходы — уклон не более 0,10 м/м; между близкими пересечениями
 * изменённая глубина сохраняется.</p>
 */
public final class DepthProfile {

    private static final Logger log = LoggerFactory.getLogger(DepthProfile.class);

    public static final double NORMAL_DEPTH = 3.0;
    public static final double MIN_DEPTH = 0.7;
    public static final double MAX_SLOPE = 0.10;

    /** Окно допустимых глубин верха габарита на интервале дуги ребра. */
    public static final class Window {
        public final double from;
        public final double to;
        public final double minDepth;
        public final double maxDepth;

        public Window(double from, double to, double minDepth, double maxDepth) {
            this.from = from;
            this.to = to;
            this.minDepth = minDepth;
            this.maxDepth = maxDepth;
        }
    }

    /** Точки (s, h) кусочно-линейного профиля, s по возрастанию. */
    private final List<double[]> points;

    private DepthProfile(List<double[]> points) {
        this.points = points;
    }

    /** Профиль постоянной обычной глубины. */
    public static DepthProfile flat(double length) {
        List<double[]> pts = new ArrayList<>();
        pts.add(new double[]{0, NORMAL_DEPTH});
        pts.add(new double[]{length, NORMAL_DEPTH});
        return new DepthProfile(pts);
    }

    public static DepthProfile build(double length, List<Window> windows) {
        if (windows.isEmpty()) {
            return flat(length);
        }
        // слияние перекрывающихся окон: пересечение диапазонов глубин
        List<Window> sorted = new ArrayList<>(windows);
        sorted.sort(Comparator.comparingDouble(w -> w.from));
        List<Window> merged = new ArrayList<>();
        for (Window w : sorted) {
            if (!merged.isEmpty() && w.from <= merged.get(merged.size() - 1).to + 1e-9) {
                Window prev = merged.remove(merged.size() - 1);
                double lo = Math.max(prev.minDepth, w.minDepth);
                double hi = Math.min(prev.maxDepth, w.maxDepth);
                if (lo > hi) {
                    log.warn("Конфликт вертикальных требований на [{}, {}] — участок "
                            + "требует ручной проработки", prev.from, w.to);
                    hi = lo;
                }
                merged.add(new Window(prev.from, Math.max(prev.to, w.to), lo, hi));
            } else {
                merged.add(w);
            }
        }

        List<double[]> pts = new ArrayList<>();
        double currentDepth = NORMAL_DEPTH;
        double currentS = 0;
        pts.add(new double[]{0, NORMAL_DEPTH});
        for (Window w : merged) {
            double target = plateauDepth(w);
            if (Math.abs(target - NORMAL_DEPTH) < 1e-9 && Math.abs(currentDepth - NORMAL_DEPTH) < 1e-9) {
                continue; // окно допускает обычную глубину — профиль не меняется
            }
            double rampLen = Math.abs(target - currentDepth) / MAX_SLOPE;
            double rampStart = Math.max(currentS, w.from - rampLen);
            if (rampStart > currentS) {
                pts.add(new double[]{rampStart, currentDepth});
            }
            // если рампа не помещается — плато начинается с достигнутой глубины
            double plateauStart = Math.min(w.from, rampStart + rampLen);
            pts.add(new double[]{Math.max(plateauStart, rampStart), target});
            pts.add(new double[]{w.to, target});
            currentDepth = target;
            currentS = w.to;
        }
        // возврат к обычной глубине, если помещается
        if (Math.abs(currentDepth - NORMAL_DEPTH) > 1e-9) {
            double rampLen = Math.abs(NORMAL_DEPTH - currentDepth) / MAX_SLOPE;
            if (currentS + rampLen <= length) {
                pts.add(new double[]{currentS + rampLen, NORMAL_DEPTH});
                pts.add(new double[]{length, NORMAL_DEPTH});
            } else {
                pts.add(new double[]{length, currentDepth});
            }
        } else if (pts.get(pts.size() - 1)[0] < length) {
            pts.add(new double[]{length, NORMAL_DEPTH});
        }
        return new DepthProfile(normalize(pts, length));
    }

    /** Глубина плато: 3,0 если допустима, иначе ближайшая граница окна. */
    private static double plateauDepth(Window w) {
        double lo = Math.max(MIN_DEPTH, w.minDepth);
        double hi = Math.max(lo, w.maxDepth);
        if (NORMAL_DEPTH >= lo && NORMAL_DEPTH <= hi) {
            return NORMAL_DEPTH;
        }
        return NORMAL_DEPTH < lo ? lo : hi;
    }

    private static List<double[]> normalize(List<double[]> pts, double length) {
        List<double[]> out = new ArrayList<>();
        for (double[] p : pts) {
            double s = Math.max(0, Math.min(length, p[0]));
            if (!out.isEmpty() && s <= out.get(out.size() - 1)[0] + 1e-9) {
                // сохраняем более позднее значение в совпадающей точке
                if (Math.abs(s - out.get(out.size() - 1)[0]) < 1e-9) {
                    out.get(out.size() - 1)[1] = p[1];
                }
                continue;
            }
            out.add(new double[]{s, p[1]});
        }
        if (out.get(out.size() - 1)[0] < length - 1e-9) {
            out.add(new double[]{length, out.get(out.size() - 1)[1]});
        }
        return out;
    }

    public double depthAt(double s) {
        for (int i = 0; i + 1 < points.size(); i++) {
            double[] a = points.get(i);
            double[] b = points.get(i + 1);
            if (s <= b[0] + 1e-9) {
                if (b[0] - a[0] < 1e-9) {
                    return b[1];
                }
                double t = (s - a[0]) / (b[0] - a[0]);
                return a[1] + (b[1] - a[1]) * Math.max(0, Math.min(1, t));
            }
        }
        return points.get(points.size() - 1)[1];
    }

    /**
     * Точки деления ребра: изломы профиля и пересечения отметки 3,0 м
     * (в них создаются технические узлы, раздел 5 техприложения).
     */
    public TreeSet<Double> breakpoints(double length) {
        TreeSet<Double> cuts = new TreeSet<>();
        for (int i = 0; i < points.size(); i++) {
            double s = points.get(i)[0];
            if (s > 1e-6 && s < length - 1e-6) {
                cuts.add(s);
            }
            if (i + 1 < points.size()) {
                double[] a = points.get(i);
                double[] b = points.get(i + 1);
                // пересечение уровня 3,0 внутри наклонного куска
                if ((a[1] - NORMAL_DEPTH) * (b[1] - NORMAL_DEPTH) < 0) {
                    double t = (NORMAL_DEPTH - a[1]) / (b[1] - a[1]);
                    double s3 = a[0] + t * (b[0] - a[0]);
                    if (s3 > 1e-6 && s3 < length - 1e-6) {
                        cuts.add(s3);
                    }
                }
            }
        }
        return cuts;
    }

    /** Kгл фрагмента [s1, s2]: линеен по глубине, на наклоне — среднее концов. */
    public double kDepth(double s1, double s2) {
        return (kAt(depthAt(s1)) + kAt(depthAt(s2))) / 2.0;
    }

    public static double kAt(double depth) {
        return depth <= NORMAL_DEPTH ? 1.0 : 1.0 + 0.10 * (depth - NORMAL_DEPTH);
    }

    /** true, если профиль отличается от обычной глубины. */
    public boolean isModified() {
        for (double[] p : points) {
            if (Math.abs(p[1] - NORMAL_DEPTH) > 1e-9) {
                return true;
            }
        }
        return false;
    }
}
