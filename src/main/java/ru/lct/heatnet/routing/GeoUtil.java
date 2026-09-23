package ru.lct.heatnet.routing;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.locationtech.jts.operation.distance.DistanceOp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Геометрические утилиты маршрутизации. */
public final class GeoUtil {

    public static final GeometryFactory GF = new GeometryFactory();

    private GeoUtil() {
    }

    public static LineString segment(Coordinate a, Coordinate b) {
        return GF.createLineString(new Coordinate[]{a, b});
    }

    public static LineString polyline(List<Coordinate> coords) {
        return GF.createLineString(coords.toArray(new Coordinate[0]));
    }

    /** Острый угол между двумя направлениями, градусы [0, 90]. */
    public static double acuteAngleDeg(double dx1, double dy1, double dx2, double dy2) {
        double dot = Math.abs(dx1 * dx2 + dy1 * dy2);
        double n1 = Math.hypot(dx1, dy1);
        double n2 = Math.hypot(dx2, dy2);
        if (n1 == 0 || n2 == 0) {
            return 90;
        }
        double cos = Math.min(1.0, dot / (n1 * n2));
        return Math.toDegrees(Math.acos(cos));
    }

    /** Угол поворота в вершине b пути a-b-c: 0 — прямая, градусы [0, 180]. */
    public static double turnAngleDeg(Coordinate a, Coordinate b, Coordinate c) {
        double dx1 = b.x - a.x, dy1 = b.y - a.y;
        double dx2 = c.x - b.x, dy2 = c.y - b.y;
        double n1 = Math.hypot(dx1, dy1), n2 = Math.hypot(dx2, dy2);
        if (n1 == 0 || n2 == 0) {
            return 0;
        }
        double cos = Math.max(-1.0, Math.min(1.0, (dx1 * dx2 + dy1 * dy2) / (n1 * n2)));
        return Math.toDegrees(Math.acos(cos));
    }

    /**
     * Направление границы (ближайшего сегмента) геометрии в точке p.
     * Возвращает вектор {dx, dy} или null.
     */
    public static double[] boundaryDirectionAt(Geometry boundary, Coordinate p) {
        DistanceOp op = new DistanceOp(boundary, GF.createPoint(p));
        // GeometryLocation даёт индекс сегмента внутри компоненты
        org.locationtech.jts.operation.distance.GeometryLocation[] locations = op.nearestLocations();
        Geometry component = locations[0].getGeometryComponent();
        int segIndex = locations[0].getSegmentIndex();
        Coordinate[] coords = component.getCoordinates();
        if (coords.length < 2) {
            return null;
        }
        int i = Math.min(segIndex, coords.length - 2);
        return new double[]{coords[i + 1].x - coords[i].x, coords[i + 1].y - coords[i].y};
    }

    /** Интервал специального прохода вдоль ребра с коэффициентом стоимости. */
    public static final class SpecialInterval {
        public final double from;
        public final double to;
        public final double kSpec;
        /** Пересекаемое препятствие (для вертикального планирования); null у слитых. */
        public final SpecialObstacle source;

        public SpecialInterval(double from, double to, double kSpec) {
            this(from, to, kSpec, null);
        }

        public SpecialInterval(double from, double to, double kSpec, SpecialObstacle source) {
            this.from = from;
            this.to = to;
            this.kSpec = kSpec;
            this.source = source;
        }
    }

    /**
     * Слияние перекрывающихся интервалов: на общем фрагменте применяется
     * максимальный Kспец (коэффициенты не суммируются и не перемножаются).
     * Возвращает непересекающиеся интервалы, упорядоченные по началу.
     */
    public static List<SpecialInterval> mergeIntervals(List<SpecialInterval> intervals, double totalLength) {
        if (intervals.isEmpty()) {
            return intervals;
        }
        // Границы всех интервалов делят ребро на элементарные отрезки
        List<Double> cuts = new ArrayList<>();
        for (SpecialInterval si : intervals) {
            cuts.add(clamp(si.from, 0, totalLength));
            cuts.add(clamp(si.to, 0, totalLength));
        }
        cuts.sort(Comparator.naturalOrder());
        List<SpecialInterval> result = new ArrayList<>();
        for (int i = 0; i + 1 < cuts.size(); i++) {
            double a = cuts.get(i), b = cuts.get(i + 1);
            if (b - a < 1e-9) {
                continue;
            }
            double mid = (a + b) / 2;
            double k = 0;
            for (SpecialInterval si : intervals) {
                if (si.from <= mid && mid <= si.to) {
                    k = Math.max(k, si.kSpec);
                }
            }
            if (k > 0) {
                // объединяем соседние фрагменты с одинаковым K
                if (!result.isEmpty()) {
                    SpecialInterval last = result.get(result.size() - 1);
                    if (Math.abs(last.to - a) < 1e-9 && last.kSpec == k) {
                        result.set(result.size() - 1, new SpecialInterval(last.from, b, k));
                        continue;
                    }
                }
                result.add(new SpecialInterval(a, b, k));
            }
        }
        return result;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Позиция точки вдоль линии (длина от начала). */
    public static double positionAlong(LineString line, Coordinate p) {
        return new LengthIndexedLine(line).indexOf(p);
    }
}
