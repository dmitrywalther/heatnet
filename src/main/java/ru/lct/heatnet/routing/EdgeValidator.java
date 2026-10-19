package ru.lct.heatnet.routing;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.linearref.LengthIndexedLine;
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
    public static final double BARRIER_SEPARATION_M = 1.5;
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
     * @param skipRestrictionId     id собственного ОКС: снимается отступ на
     *                              финальном подходе, но разрешён только один
     *                              вход через внешнюю стену без транзита
     * @param allowBarrierTouchAt   точка присоединения к построенной сети: только
     *                              в ней ребру разрешено коснуться барьера
     */
    public EdgeCheck check(Coordinate a, Coordinate b,
                           Set<Object> exemptSpecialIds, Object skipRestrictionId,
                           Coordinate allowBarrierTouchAt) {
        return check(a, b, exemptSpecialIds, skipRestrictionId, allowBarrierTouchAt, null);
    }

    /**
     * sourceConnectionAt — подтверждённая вызывающим кодом камера на исходной
     * теплосети. П. 13 разрешает начать прямой спецпроход внутри дороги/трамвая;
     * это не исключение объекта из проверок угла, отступа, цены или глубины.
     */
    public EdgeCheck check(Coordinate a, Coordinate b,
                           Set<Object> exemptSpecialIds, Object skipRestrictionId,
                           Coordinate allowBarrierTouchAt, Coordinate sourceConnectionAt) {
        double length = a.distance(b);
        if (length < COORD_EPS) {
            return EdgeCheck.invalid();
        }
        EdgeCheck staticPart = staticCheck(a, b, length, exemptSpecialIds, skipRestrictionId, sourceConnectionAt);
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
                                  Set<Object> exemptSpecialIds, Object skipRestrictionId,
                                  Coordinate sourceConnectionAt) {
        boolean cacheable = (exemptSpecialIds == null || exemptSpecialIds.isEmpty())
                && skipRestrictionId == null && sourceConnectionAt == null;
        GradeContext.EdgeKey key = null;
        if (cacheable) {
            key = new GradeContext.EdgeKey(a, b);
            EdgeCheck cached = ctx.staticEdgeCache.get(key);
            if (cached != null) {
                return orient(cached, a, b, length);
            }
        }

        EdgeCheck result = computeStatic(a, b, length, exemptSpecialIds, skipRestrictionId, sourceConnectionAt);
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
                                    Set<Object> exemptSpecialIds, Object skipRestrictionId,
                                    Coordinate sourceConnectionAt) {
        LineString seg = GeoUtil.segment(a, b);
        Envelope env = seg.getEnvelopeInternal();

        List<ForbiddenZone> nearbyForbidden = ctx.forbiddenIn(env);
        if (skipRestrictionId != null
                && !OksEntryGeometry.allows(seg, nearbyForbidden, skipRestrictionId)) {
            return EdgeCheck.invalid();
        }
        for (ForbiddenZone zone : nearbyForbidden) {
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
            if (!checkSpecial(seg, a, b, length, s, intervals, sourceConnectionAt)) {
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
        List<Coordinate> connections = allowBarrierTouchAt == null
                ? Collections.emptyList() : Collections.singletonList(allowBarrierTouchAt);
        for (Barrier barrier : barriersIn(env)) {
            if (!barrierAllows(seg, a, b, connections, barrier)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Финальная симметричная проверка двух полных рёбер. Общие узлы передаёт
     * план. Близость разрешена только непрерывным подходом от общего конца
     * внутри радиуса присоединения; число вершин и фасок не меняет результат.
     */
    public static boolean networkLinesCompatible(LineString first, LineString second,
                                                  List<Coordinate> sharedNodes) {
        Geometry intersection = first.intersection(second);
        if (!intersection.isEmpty() && intersection.getDimension() >= 1) {
            return false;
        }
        for (Coordinate point : intersection.getCoordinates()) {
            boolean shared = false;
            for (Coordinate node : sharedNodes) {
                if (point.distance(node) <= COORD_EPS) {
                    shared = true;
                    break;
                }
            }
            if (!shared) {
                return false;
            }
        }
        return wholeLineAgainstBarrier(first, new Barrier(second), sharedNodes)
                && wholeLineAgainstBarrier(second, new Barrier(first), sharedNodes);
    }

    private static boolean wholeLineAgainstBarrier(LineString line, Barrier barrier,
                                                     List<Coordinate> sharedNodes) {
        if (!barrier.zonePrep.intersects(line)) {
            return true;
        }
        LengthIndexedLine indexed = new LengthIndexedLine(line);
        List<double[]> intervals = new ArrayList<>();
        collectArcIntervals(line.intersection(barrier.zone), indexed, intervals);
        intervals.sort(java.util.Comparator.comparingDouble(interval -> interval[0]));
        List<double[]> merged = new ArrayList<>();
        for (double[] interval : intervals) {
            if (!merged.isEmpty() && interval[0] <= merged.get(merged.size() - 1)[1] + COORD_EPS) {
                double[] previous = merged.get(merged.size() - 1);
                previous[1] = Math.max(previous[1], interval[1]);
            } else {
                merged.add(interval.clone());
            }
        }
        Coordinate start = line.getCoordinateN(0);
        Coordinate end = line.getCoordinateN(line.getNumPoints() - 1);
        double length = line.getLength();
        for (double[] interval : merged) {
            boolean allowed = false;
            for (Coordinate node : sharedNodes) {
                boolean atStart = interval[0] <= COORD_EPS && start.equals2D(node);
                boolean atEnd = interval[1] >= length - COORD_EPS && end.equals2D(node);
                if ((atStart || atEnd)
                        && portionNearConnection(indexed.extractLine(interval[0], interval[1]), node, barrier)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    private static void collectArcIntervals(Geometry geometry, LengthIndexedLine indexed,
                                            List<double[]> intervals) {
        if (geometry.isEmpty() || geometry.getDimension() < 1) {
            return;
        }
        if (geometry instanceof LineString) {
            Coordinate[] coords = geometry.getCoordinates();
            double from = indexed.indexOf(coords[0]);
            double to = indexed.indexOf(coords[coords.length - 1]);
            intervals.add(new double[]{Math.min(from, to), Math.max(from, to)});
        } else {
            for (int i = 0; i < geometry.getNumGeometries(); i++) {
                collectArcIntervals(geometry.getGeometryN(i), indexed, intervals);
            }
        }
    }

    private static boolean nearConnection(Coordinate point, List<Coordinate> connections) {
        for (Coordinate connection : connections) {
            if (point.distance(connection) <= 1e-3) {
                return true;
            }
        }
        return false;
    }

    private static boolean barrierAllows(LineString seg, Coordinate a, Coordinate b,
                                          List<Coordinate> connections, Barrier barrier) {
        if (!barrier.zonePrep.intersects(seg)) {
            return true;
        }
        Geometry x = seg.intersection(barrier.line);
        // LINESTRING EMPTY имеет размерность 1, но не является наложением.
        if (!x.isEmpty() && x.getDimension() >= 1) {
            return false;
        }
        for (Coordinate c : x.getCoordinates()) {
            if (!nearConnection(c, connections)) {
                return false;
            }
        }
        // Вершина около чужой оси не должна разрешать скольжение по ней.
        for (Coordinate end : new Coordinate[]{a, b}) {
            if (!nearConnection(end, connections)
                    && barrier.line.distance(GeoUtil.GF.createPoint(end)) < 0.02) {
                return false;
            }
        }
        for (Coordinate v : barrier.line.getCoordinates()) {
            if (v.distance(a) <= 1e-3 || v.distance(b) <= 1e-3 || nearConnection(v, connections)) {
                continue;
            }
            if (seg.distance(GeoUtil.GF.createPoint(v)) < 0.02) {
                return false;
            }
        }
        Geometry portions = seg.intersection(barrier.zone);
        for (int i = 0; i < portions.getNumGeometries(); i++) {
            Geometry portion = portions.getGeometryN(i);
            if (portion.getDimension() < 1 || portion.isEmpty()) {
                continue;
            }
            boolean near = false;
            for (Coordinate connection : connections) {
                if (portionNearConnection(portion, connection, barrier)) {
                    near = true;
                    break;
                }
            }
            if (!near) {
                return false;
            }
        }
        return true;
    }

    private boolean checkSpecial(LineString seg, Coordinate a, Coordinate b, double length,
                                 SpecialObstacle s, List<SpecialInterval> intervals,
                                 Coordinate sourceConnectionAt) {
        boolean intersectsCore = s.corePrep.intersects(seg);
        boolean sourceExit = s.polygonal && intersectsCore && sourceConnectionAt != null
                && ("road".equals(s.type) || "tram_tracks".equals(s.type))
                && (sourceConnectionAt.distance(a) <= COORD_EPS || sourceConnectionAt.distance(b) <= COORD_EPS)
                && covers(s.corePrep, sourceConnectionAt);
        // Ранний отказ сохраняет быстрый prepared-предикат, но измеряет запас
        // вдоль самого ребра. Евклидов буфер ошибочно запрещает допустимые
        // концы косых проходов. Ровно margin (с COORD_EPS) должно хватать.
        if (s.polygonal && intersectsCore && !sourceExit
                && lacksPolygonMargin(a, b, length, s)) {
            return false;
        }
        // Каждый связный фрагмент ребра внутри зоны близости обязан содержать
        // корректное пересечение ядра препятствия — иначе это прохождение
        // ближе минимального расстояния.
        Geometry portions = seg.intersection(s.proximityGeom);
        for (int i = 0; i < portions.getNumGeometries(); i++) {
            Geometry portion = portions.getGeometryN(i);
            if (portion.getDimension() < 1) {
                continue; // касание в точке
            }
            // Reuse the immutable core's prepared index for every clipped portion.
            if (!s.corePrep.intersects(portion)) {
                return false;
            }
        }
        if (!intersectsCore) {
            // Ядро не пересечено; протяжённые фрагменты в зоне близости уже
            // отклонены выше, значит зона задета лишь касанием — допустимо.
            return true;
        }

        if (s.polygonal) {
            int firstInterval = intervals.size();
            if (!checkPolygonalCrossing(seg, a, b, s, intervals, sourceExit ? sourceConnectionAt : null)) {
                return false;
            }
            // Отступ по ДУ не применяется только внутри SPECIAL этого объекта.
            // При большом габарите/косом угле proximity может продолжаться
            // дальше трёх метров: обычный хвост нельзя освободить от отступа
            // лишь потому, что тот же связный фрагмент касается ядра.
            return proximityCoveredBySpecials(portions, a, b, intervals, firstInterval);
        }
        return checkLinearCrossing(seg, a, b, length, s, intervals);
    }

    private boolean lacksPolygonMargin(Coordinate a, Coordinate b, double length, SpecialObstacle s) {
        if (s.marginM <= COORD_EPS) {
            return covers(s.corePrep, a) || covers(s.corePrep, b);
        }
        double fraction = Math.min(length, s.marginM - COORD_EPS) / length;
        Coordinate fromStart = new Coordinate(a.x + (b.x - a.x) * fraction,
                a.y + (b.y - a.y) * fraction);
        Coordinate fromEnd = new Coordinate(b.x + (a.x - b.x) * fraction,
                b.y + (a.y - b.y) * fraction);
        return s.corePrep.intersects(GeoUtil.segment(a, fromStart))
                || s.corePrep.intersects(GeoUtil.segment(fromEnd, b));
    }

    private boolean proximityCoveredBySpecials(Geometry portions, Coordinate a, Coordinate b,
                                               List<SpecialInterval> intervals, int firstInterval) {
        for (int i = 0; i < portions.getNumGeometries(); i++) {
            Geometry portion = portions.getGeometryN(i);
            if (portion.isEmpty() || portion.getDimension() < 1) continue;
            Coordinate[] cs = portion.getCoordinates();
            double p0 = posOnSegment(a, b, cs[0]);
            double p1 = posOnSegment(a, b, cs[cs.length - 1]);
            double coveredTo = Math.min(p0, p1), end = Math.max(p0, p1);
            // JTS не гарантирует порядок компонентов; объединяем покрытие
            // без предположения об их ориентации и без окон других объектов.
            boolean advanced;
            do {
                double previous = coveredTo;
                for (int j = firstInterval; j < intervals.size(); j++) {
                    SpecialInterval interval = intervals.get(j);
                    if (interval.from <= coveredTo + COORD_EPS && interval.to > coveredTo) {
                        coveredTo = interval.to;
                    }
                }
                advanced = coveredTo > previous;
            } while (advanced && coveredTo < end - COORD_EPS);
            if (coveredTo < end - COORD_EPS) return false;
        }
        return true;
    }

    private boolean checkPolygonalCrossing(LineString seg, Coordinate a, Coordinate b,
                                           SpecialObstacle s, List<SpecialInterval> intervals,
                                           Coordinate sourceConnectionAt) {
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
        if (sourceConnectionAt != null) {
            // Единственный односторонний допуск — у исходной камеры. На другом
            // конце каждого прямого участка должен полностью поместиться выход
            // из полигона и margin вдоль трассы (не евклидов буфер полигона).
            double length = seg.getLength();
            boolean sourceAtStart = sourceConnectionAt.distance(a) <= COORD_EPS;
            Geometry inside = seg.intersection(s.core);
            boolean found = false;
            for (int i = 0; i < inside.getNumGeometries(); i++) {
                Geometry part = inside.getGeometryN(i);
                if (part.getDimension() < 1) {
                    // Камера ровно на границе с выходом наружу: внутренняя
                    // длина нулевая, наружные 3 м прямого прохода сохраняются.
                    if (!part.isEmpty() && part.distance(GeoUtil.GF.createPoint(sourceConnectionAt)) <= COORD_EPS) {
                        if (length < s.marginM - COORD_EPS) return false;
                        intervals.add(new SpecialInterval(sourceAtStart ? 0 : length - s.marginM,
                                sourceAtStart ? Math.min(length, s.marginM) : length, s.kSpec, s));
                        found = true;
                    }
                    continue;
                }
                Coordinate[] cs = part.getCoordinates();
                double t0 = posOnSegment(a, b, cs[0]);
                double t1 = posOnSegment(a, b, cs[cs.length - 1]);
                double from = Math.min(t0, t1), to = Math.max(t0, t1);
                // При близком отверстии соседние окна могут перекрываться:
                // их запас также не продолжается за подтверждённую камеру.
                double start = sourceAtStart ? Math.max(0, from - s.marginM) : from - s.marginM;
                double end = sourceAtStart ? to + s.marginM : Math.min(length, to + s.marginM);
                if (start < -COORD_EPS || end > length + COORD_EPS) return false;
                intervals.add(new SpecialInterval(Math.max(0, start), Math.min(length, end), s.kSpec, s));
                found = true;
            }
            return found;
        }
        // Каждое пересечение ядра расширяется на margin вдоль трассы.
        // Сохраняем отдельные raw-окна для глубины; для цены они объединяются.
        // Касание ядра в точке сохраняет прежнюю проверку угла и получает
        // симметричный запас относительно этой точки.
        Geometry inside = seg.intersection(s.core);
        double length = seg.getLength();
        for (int i = 0; i < inside.getNumGeometries(); i++) {
            Geometry part = inside.getGeometryN(i);
            if (part.isEmpty()) continue;
            Coordinate[] cs = part.getCoordinates();
            double t0 = posOnSegment(a, b, cs[0]);
            double t1 = posOnSegment(a, b, cs[cs.length - 1]);
            double from = Math.min(t0, t1) - s.marginM;
            double to = Math.max(t0, t1) + s.marginM;
            if (from < -COORD_EPS || to > length + COORD_EPS) return false;
            intervals.add(new SpecialInterval(Math.max(0, from), Math.min(length, to), s.kSpec, s));
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
    private static boolean portionNearConnection(Geometry portion, Coordinate connectAt, Barrier barrier) {
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
