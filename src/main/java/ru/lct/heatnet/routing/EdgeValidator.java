package ru.lct.heatnet.routing;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;
import ru.lct.heatnet.routing.GeoUtil.SpecialInterval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Проверка допустимости прямого участка новой сети между двумя точками
 * и вычисление интервалов специальных проходов вдоль него.
 */
public class EdgeValidator {

    private static final double ANGLE_TOLERANCE_DEG = 0.3;
    private static final double COORD_EPS = 1e-6;

    public static final class EdgeCheck {
        public final boolean valid;
        /** Слитые интервалы спецпроходов (для стоимости, максимальный Kспец). */
        public final List<SpecialInterval> specials;
        /** Исходные интервалы с привязкой к препятствиям (для режима с глубиной). */
        public final List<SpecialInterval> rawSpecials;
        public final double length;

        EdgeCheck(boolean valid, List<SpecialInterval> specials,
                  List<SpecialInterval> rawSpecials, double length) {
            this.valid = valid;
            this.specials = specials;
            this.rawSpecials = rawSpecials;
            this.length = length;
        }

        static EdgeCheck invalid() {
            return new EdgeCheck(false, Collections.emptyList(), Collections.emptyList(), 0);
        }
    }

    /**
     * Минимальное расстояние между осями участков новой сети: два габарита
     * пары плюс зазор. Дублирование трасс вплотную не имеет инженерного смысла
     * и после округления координат выглядит как наложение.
     */
    private static final double BARRIER_SEPARATION_M = 1.5;
    /** Радиус, в котором допускается заход в зону барьера для присоединения. */
    private static final double CONNECT_RADIUS_M = 6.0;

    private static final class Barrier {
        final LineString line;
        final org.locationtech.jts.geom.Geometry zone;
        final org.locationtech.jts.geom.prep.PreparedGeometry zonePrep;

        Barrier(LineString line) {
            this.line = line;
            this.zone = line.buffer(BARRIER_SEPARATION_M);
            this.zonePrep = org.locationtech.jts.geom.prep.PreparedGeometryFactory.prepare(zone);
        }
    }

    private final GradeContext ctx;
    /** Уже построенные новые участки (пересечение вне общего узла запрещено). */
    private final List<Barrier> barriers = new ArrayList<>();
    private STRtree barrierTree;

    public EdgeValidator(GradeContext ctx) {
        this.ctx = ctx;
    }

    public void addBarrier(LineString line) {
        barriers.add(new Barrier(line));
        barrierTree = null;
    }

    public int barrierCount() {
        return barriers.size();
    }

    private List<Barrier> barriersIn(Envelope env) {
        if (barrierTree == null) {
            // STRtree нельзя пополнять после build — пересобираем при изменении.
            // Индексируем посегментно: габарит целой полилинии накрывает почти
            // весь охват данных, и без этого каждый барьер проверялся бы
            // для каждого ребра графа.
            barrierTree = new STRtree();
            for (Barrier b : barriers) {
                Coordinate[] cs = b.line.getCoordinates();
                for (int i = 0; i + 1 < cs.length; i++) {
                    Envelope e = new Envelope(cs[i], cs[i + 1]);
                    e.expandBy(BARRIER_SEPARATION_M);
                    barrierTree.insert(e, b);
                }
            }
            barrierTree.build();
        }
        @SuppressWarnings("unchecked")
        List<Barrier> hits = barrierTree.query(env);
        if (hits.size() <= 1) {
            return hits;
        }
        return new ArrayList<>(new java.util.LinkedHashSet<>(hits));
    }

    public EdgeCheck check(Coordinate a, Coordinate b,
                           Set<Object> exemptSpecialIds, Object skipRestrictionId) {
        return check(a, b, exemptSpecialIds, skipRestrictionId, null);
    }

    /**
     * @param exemptSpecialIds      объекты (трубы существующей сети), к которым ребро
     *                              примыкает — их зоны близости не проверяются
     * @param skipRestrictionId     id ограничения собственного полигона ОКС: все его
     *                              компоненты игнорируются на финальном подходе
     * @param allowBarrierTouchAt   точка присоединения к построенной сети: только
     *                              в ней ребру разрешено коснуться барьера
     */
    public EdgeCheck check(Coordinate a, Coordinate b,
                           Set<Object> exemptSpecialIds, Object skipRestrictionId,
                           Coordinate allowBarrierTouchAt) {
        double length = a.distance(b);
        if (length < COORD_EPS) {
            return EdgeCheck.invalid();
        }
        EdgeCheck staticPart = staticCheck(a, b, length, exemptSpecialIds, skipRestrictionId);
        if (!staticPart.valid) {
            return EdgeCheck.invalid();
        }
        if (!barriers.isEmpty() && !barriersOk(a, b, allowBarrierTouchAt)) {
            return EdgeCheck.invalid();
        }
        return staticPart;
    }

    /**
     * Статическая часть проверки: препятствия входного набора. Не зависит от
     * построенной сети, поэтому кэшируется в контексте (общая для всех ОКС
     * и вариантов с одним расчётным габаритом).
     */
    private EdgeCheck staticCheck(Coordinate a, Coordinate b, double length,
                                  Set<Object> exemptSpecialIds, Object skipRestrictionId) {
        boolean cacheable = (exemptSpecialIds == null || exemptSpecialIds.isEmpty())
                && skipRestrictionId == null;
        GradeContext.EdgeKey key = null;
        if (cacheable) {
            key = new GradeContext.EdgeKey(a, b);
            EdgeCheck cached = ctx.staticEdgeCache.get(key);
            if (cached != null) {
                return orient(cached, a, b, length);
            }
        }

        EdgeCheck result = computeStatic(a, b, length, exemptSpecialIds, skipRestrictionId);
        if (cacheable) {
            // в кэше — результат в каноническом направлении ключа
            ctx.staticEdgeCache.put(key,
                    GradeContext.EdgeKey.isCanonical(a, b) ? result : reverse(result));
        }
        return result;
    }

    /** Интервалы кэшированы в каноническом направлении; при развороте — отражаем. */
    private EdgeCheck orient(EdgeCheck cached, Coordinate a, Coordinate b, double length) {
        if (!cached.valid
                || (cached.specials.isEmpty() && cached.rawSpecials.isEmpty())
                || GradeContext.EdgeKey.isCanonical(a, b)) {
            return cached;
        }
        return reverse(cached);
    }

    private EdgeCheck reverse(EdgeCheck check) {
        if (!check.valid || (check.specials.isEmpty() && check.rawSpecials.isEmpty())) {
            return check;
        }
        return new EdgeCheck(true, flip(check.specials, check.length),
                flip(check.rawSpecials, check.length), check.length);
    }

    private List<SpecialInterval> flip(List<SpecialInterval> intervals, double length) {
        List<SpecialInterval> flipped = new ArrayList<>(intervals.size());
        for (int i = intervals.size() - 1; i >= 0; i--) {
            SpecialInterval si = intervals.get(i);
            flipped.add(new SpecialInterval(length - si.to, length - si.from, si.kSpec, si.source));
        }
        return flipped;
    }

    private EdgeCheck computeStatic(Coordinate a, Coordinate b, double length,
                                    Set<Object> exemptSpecialIds, Object skipRestrictionId) {
        LineString seg = GeoUtil.segment(a, b);
        Envelope env = seg.getEnvelopeInternal();

        for (ForbiddenZone zone : ctx.forbiddenIn(env)) {
            if (skipRestrictionId != null && skipRestrictionId.equals(zone.restrictionId)) {
                continue;
            }
            if (zone.blocking.intersects(seg)) {
                return EdgeCheck.invalid();
            }
        }

        List<SpecialInterval> intervals = new ArrayList<>();
        for (SpecialObstacle s : ctx.specialsIn(env)) {
            if (exemptSpecialIds != null && exemptSpecialIds.contains(s.sourceId)) {
                continue;
            }
            if (!s.proximity.intersects(seg)) {
                continue;
            }
            if (!checkSpecial(seg, a, b, length, s, intervals)) {
                return EdgeCheck.invalid();
            }
        }
        return new EdgeCheck(true, GeoUtil.mergeIntervals(intervals, length),
                intervals, length);
    }

    /**
     * Построенные новые участки: любой контакт с осью или зоной разделения
     * допустим только в объявленной точке присоединения маршрута — иначе
     * пути «проскальзывают» сквозь общие вершины графа видимости.
     */
    private boolean barriersOk(Coordinate a, Coordinate b, Coordinate allowBarrierTouchAt) {
        LineString seg = GeoUtil.segment(a, b);
        Envelope env = seg.getEnvelopeInternal();
        for (Barrier barrier : barriersIn(env)) {
            if (!barrier.zonePrep.intersects(seg)) {
                continue;
            }
            Geometry x = seg.intersection(barrier.line);
            if (x.getDimension() >= 1) {
                return false; // прокладка вдоль уже построенной оси
            }
            for (Coordinate c : x.getCoordinates()) {
                if (allowBarrierTouchAt == null || c.distance(allowBarrierTouchAt) > 1e-3) {
                    return false; // контакт вне точки присоединения
                }
            }
            // концы ребра не должны лежать на оси барьера (кроме точки
            // присоединения): вершина графа в микронах от оси позволяет
            // «скользить» вдоль построенного участка сквозь робастность нодинга
            for (Coordinate end : new Coordinate[]{a, b}) {
                if (allowBarrierTouchAt != null && end.distance(allowBarrierTouchAt) <= 1e-3) {
                    continue;
                }
                if (barrier.line.distance(GeoUtil.GF.createPoint(end)) < 0.02) {
                    return false;
                }
            }
            // симметрично: вершина барьера на оси нового ребра — то же
            // скольжение, когда барьер строился позже соседa
            for (Coordinate v : barrier.line.getCoordinates()) {
                if (v.distance(a) <= 1e-3 || v.distance(b) <= 1e-3) {
                    continue;
                }
                if (allowBarrierTouchAt != null && v.distance(allowBarrierTouchAt) <= 1e-3) {
                    continue;
                }
                if (seg.distance(GeoUtil.GF.createPoint(v)) < 0.02) {
                    return false;
                }
            }
            Geometry portions = seg.intersection(barrier.zone);
            for (int i = 0; i < portions.getNumGeometries(); i++) {
                Geometry portion = portions.getGeometryN(i);
                if (portion.getDimension() < 1) {
                    continue;
                }
                if (!portionNearConnection(portion, allowBarrierTouchAt, barrier)) {
                    return false; // параллельное дублирование трассы
                }
            }
        }
        return true;
    }

    private boolean checkSpecial(LineString seg, Coordinate a, Coordinate b, double length,
                                 SpecialObstacle s, List<SpecialInterval> intervals) {
        // Каждый связный фрагмент ребра внутри зоны близости обязан содержать
        // корректное пересечение ядра препятствия — иначе это прохождение
        // ближе минимального расстояния.
        Geometry portions = seg.intersection(s.proximityGeom);
        for (int i = 0; i < portions.getNumGeometries(); i++) {
            Geometry portion = portions.getGeometryN(i);
            if (portion.getDimension() < 1) {
                continue; // касание в точке
            }
            if (!portion.intersects(s.core)) {
                return false;
            }
        }
        if (!s.corePrep.intersects(seg)) {
            // Ядро не пересечено; протяжённые фрагменты в зоне близости уже
            // отклонены выше, значит зона задета лишь касанием — допустимо.
            return true;
        }

        if (s.polygonal) {
            return checkPolygonalCrossing(seg, a, b, s, intervals);
        }
        return checkLinearCrossing(seg, a, b, length, s, intervals);
    }

    private boolean checkPolygonalCrossing(LineString seg, Coordinate a, Coordinate b,
                                           SpecialObstacle s, List<SpecialInterval> intervals) {
        // Специальный проход — один прямой участок: повороты (концы ребра)
        // не должны находиться внутри зоны спецучастка (полигон + 3 м).
        if (covers(s.boundZonePrep, a) || covers(s.boundZonePrep, b)) {
            return false;
        }
        // Угол пересечения в точках входа относительно границы полигона
        if (s.minAngleDeg != null) {
            Geometry boundary = s.core.getBoundary();
            Geometry crossings = seg.intersection(boundary);
            for (Coordinate p : crossings.getCoordinates()) {
                double[] dir = GeoUtil.boundaryDirectionAt(boundary, p);
                if (dir == null) {
                    continue;
                }
                double angle = GeoUtil.acuteAngleDeg(dir[0], dir[1], b.x - a.x, b.y - a.y);
                if (angle < s.minAngleDeg - ANGLE_TOLERANCE_DEG) {
                    return false;
                }
            }
        }
        // Интервалы спецучастка: пересечение ребра с (полигон + 3 м)
        Geometry inBound = seg.intersection(s.boundZone);
        for (int i = 0; i < inBound.getNumGeometries(); i++) {
            Geometry part = inBound.getGeometryN(i);
            if (part.getDimension() < 1) {
                continue;
            }
            Coordinate[] cs = part.getCoordinates();
            double from = posOnSegment(a, b, cs[0]);
            double to = posOnSegment(a, b, cs[cs.length - 1]);
            intervals.add(new SpecialInterval(Math.min(from, to), Math.max(from, to), s.kSpec, s));
        }
        return true;
    }

    private boolean checkLinearCrossing(LineString seg, Coordinate a, Coordinate b, double length,
                                        SpecialObstacle s, List<SpecialInterval> intervals) {
        Geometry x = seg.intersection(s.core);
        if (x.getDimension() >= 1) {
            return false; // прокладка вдоль оси линейного препятствия
        }
        for (Coordinate p : x.getCoordinates()) {
            double t = posOnSegment(a, b, p);
            // Границы спецучастка (±margin вдоль трассы) должны помещаться
            // внутри одного прямого ребра
            if (t < s.marginM - COORD_EPS || length - t < s.marginM - COORD_EPS) {
                return false;
            }
            if (s.minAngleDeg != null) {
                double[] dir = GeoUtil.boundaryDirectionAt(s.core, p);
                if (dir != null) {
                    double angle = GeoUtil.acuteAngleDeg(dir[0], dir[1], b.x - a.x, b.y - a.y);
                    if (angle < s.minAngleDeg - ANGLE_TOLERANCE_DEG) {
                        return false;
                    }
                }
            }
            intervals.add(new SpecialInterval(t - s.marginM, t + s.marginM, s.kSpec, s));
        }
        return true;
    }

    /**
     * Фрагмент внутри зоны барьера допустим, если объявлена точка присоединения,
     * она лежит на этом барьере и весь фрагмент — в радиусе подхода к ней.
     */
    private boolean portionNearConnection(Geometry portion, Coordinate connectAt, Barrier barrier) {
        if (connectAt == null
                || barrier.line.distance(GeoUtil.GF.createPoint(connectAt)) > 0.05) {
            return false;
        }
        for (Coordinate c : portion.getCoordinates()) {
            if (c.distance(connectAt) > CONNECT_RADIUS_M) {
                return false;
            }
        }
        return true;
    }

    private boolean covers(org.locationtech.jts.geom.prep.PreparedGeometry prepared, Coordinate c) {
        Point p = GeoUtil.GF.createPoint(c);
        return prepared != null && prepared.covers(p);
    }

    static double posOnSegment(Coordinate a, Coordinate b, Coordinate p) {
        double dx = b.x - a.x, dy = b.y - a.y;
        double len2 = dx * dx + dy * dy;
        if (len2 == 0) {
            return 0;
        }
        double t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2;
        return t * Math.sqrt(len2);
    }
}
