package ru.lct.heatnet.routing;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import ru.lct.heatnet.model.InputModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Подход к точке подключения ОКС.
 *
 * <p>Полигон ОКС, содержащий целевую точку, — непроходимое препятствие,
 * допускается один финальный прямой вход через внешнюю стену. Сначала
 * рассматриваются перпендикуляры к исходным стенам, а при недоступности
 * маршрута — дискретные косые подходы. Отмена отступа к собственному зданию
 * не разрешает транзит через него.</p>
 */
public final class OksApproach {

    /** Максимум резервных дискретных подходов; нормали к стенам не отсекаются. */
    private static final int MAX_APPROACH_NODES = 60;
    private static final double COORD_EPS = 1e-6;
    private static final double OUTSIDE_MARGIN = 0.05;

    /**
     * Точки входа маршрута (узлы графа видимости) снаружи всех зон блокировки,
     * упорядоченные по длине финального участка.
     */
    public final List<Coordinate> approachNodes;
    /** Косые подходы: использовать только если маршрут к основным не найден. */
    public final List<Coordinate> fallbackApproachNodes;
    /** id ограничения собственного полигона; null, если точка вне полигонов. */
    public final Object ownRestrictionId;
    public final InputModel.OksPoint oks;
    private final List<Geometry> ownCores;
    private final boolean perpendicularApproaches;

    private OksApproach(InputModel.OksPoint oks, List<Coordinate> approachNodes,
                        List<Coordinate> fallbackApproachNodes, Object ownRestrictionId,
                        List<Geometry> ownCores, boolean perpendicularApproaches) {
        this.oks = oks;
        this.approachNodes = approachNodes;
        this.fallbackApproachNodes = fallbackApproachNodes;
        this.ownRestrictionId = ownRestrictionId;
        this.ownCores = ownCores;
        this.perpendicularApproaches = perpendicularApproaches;
    }

    /** Primary approaches may instead be the historical oblique fallback. */
    public boolean hasPerpendicularApproaches() {
        return perpendicularApproaches;
    }

    /** Длина внутри исходного здания, без наружного участка в зоне отступа. */
    public double internalLength(Coordinate approach) {
        Geometry tail = GeoUtil.segment(approach, oks.utm.getCoordinate());
        double length = 0;
        for (Geometry core : ownCores) {
            length += core.intersection(tail).getLength();
        }
        return length;
    }

    /** Есть ли отдельный финальный участок (точка внутри полигона/зоны отступа). */
    public boolean hasTail() {
        return ownRestrictionId != null;
    }

    /**
     * Строит подход: кандидаты узлов подхода выносятся за зоны блокировки
     * (включая внешний контур — для точек во внутренних дворах). Возвращает
     * null, если корректный подход невозможен.
     */
    public static OksApproach build(InputModel.OksPoint oks, GradeContext ctx, EdgeValidator validator) {
        Coordinate p = oks.utm.getCoordinate();
        Object ownId = null;
        for (ForbiddenZone z : ctx.forbiddenIn(new Envelope(p))) {
            if (z.containsPoint(p)) {
                ownId = z.restrictionId;
                break;
            }
        }
        if (ownId == null) {
            return new OksApproach(oks, List.of(p), List.of(), null, List.of(), false);
        }

        List<Geometry> ownNodeZones = new ArrayList<>();
        List<Geometry> ownCores = new ArrayList<>();
        for (ForbiddenZone z : ctx.forbidden) {
            if (ownId.equals(z.restrictionId)) {
                ownNodeZones.add(z.nodeZone);
                ownCores.add(z.core);
            }
        }
        // Буферы компонент могут перекрываться, хотя их core не пересекаются.
        // Нормаль заканчивается снаружи всей собственной зоны, а core проверяется отдельно.
        Geometry ownNodeZone = ownNodeZones.size() == 1 ? ownNodeZones.get(0)
                : UnaryUnionOp.union(ownNodeZones);
        List<Coordinate> perpendicular = new ArrayList<>();
        for (ForbiddenZone z : ctx.forbidden) {
            if (ownId.equals(z.restrictionId) && z.core instanceof Polygon
                    && z.core.covers(oks.utm)) {
                addPerpendicularCandidates(perpendicular, (Polygon) z.core, ownNodeZone, p);
            }
        }
        perpendicular = validCandidates(perpendicular, p, ctx, validator, ownId, Integer.MAX_VALUE);

        // Кандидаты узла подхода: вершины контуров nodeZone всех компонент
        // собственного ограничения, промежуточные точки вдоль рёбер контура
        // (углы зажатого соседями здания часто блокированы — валидный подход
        // идёт через середину стороны, выходящей на улицу) и ближайшая точка
        // контура; сортировка по близости к цели.
        List<Coordinate> candidates = new ArrayList<>();
        for (ForbiddenZone z : ctx.forbidden) {
            if (!ownId.equals(z.restrictionId)) {
                continue;
            }
            candidates.add(DistanceOp.nearestPoints(z.nodeZone.getBoundary(),
                    GeoUtil.GF.createPoint(p))[0]);
            Coordinate[] ring = z.nodeZone.getCoordinates();
            for (int i = 0; i < ring.length; i++) {
                candidates.add(ring[i]);
                if (i + 1 < ring.length) {
                    double segLen = ring[i].distance(ring[i + 1]);
                    int extra = (int) Math.min(6, Math.floor(segLen / 12.0));
                    for (int k = 1; k <= extra; k++) {
                        double t = (double) k / (extra + 1);
                        candidates.add(new Coordinate(
                                ring[i].x + (ring[i + 1].x - ring[i].x) * t,
                                ring[i].y + (ring[i + 1].y - ring[i].y) * t));
                    }
                }
            }
        }
        List<Coordinate> fallback = validCandidates(candidates, p, ctx, validator, ownId, MAX_APPROACH_NODES);
        if (!perpendicular.isEmpty()) {
            return new OksApproach(oks, perpendicular, fallback, ownId, ownCores, true);
        }
        if (fallback.isEmpty()) {
            org.slf4j.LoggerFactory.getLogger(OksApproach.class).warn(
                    "ОКС {}: нет допустимого конечного ввода", oks.id);
            return null;
        }
        return new OksApproach(oks, fallback, List.of(), ownId, ownCores, false);
    }

    /** Проекция на каждое исходное внешнее ребро, без кольца двора/упрощения стен. */
    private static void addPerpendicularCandidates(List<Coordinate> result, Polygon core,
                                                   Geometry nodeZone, Coordinate p) {
        Coordinate[] walls = core.getExteriorRing().getCoordinates();
        Envelope env = nodeZone.getEnvelopeInternal();
        double rayLength = 2 * Math.hypot(env.getWidth(), env.getHeight())
                + p.distance(env.centre()) + 1;
        for (int i = 0; i + 1 < walls.length; i++) {
            Coordinate a = walls[i], b = walls[i + 1];
            double dx = b.x - a.x, dy = b.y - a.y;
            double length2 = dx * dx + dy * dy;
            if (length2 < COORD_EPS * COORD_EPS) {
                continue;
            }
            double t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / length2;
            if (t < 0 || t > 1) {
                continue; // проекция на продолжение стены не является нормальным вводом
            }
            Coordinate foot = new Coordinate(a.x + t * dx, a.y + t * dy);
            double wallDistance = p.distance(foot);
            if (wallDistance < COORD_EPS) {
                continue;
            }
            double ux = (foot.x - p.x) / wallDistance, uy = (foot.y - p.y) / wallDistance;
            Coordinate far = new Coordinate(p.x + ux * rayLength, p.y + uy * rayLength);
            Geometry exits = GeoUtil.segment(p, far).intersection(nodeZone.getBoundary());
            double exitDistance = Double.POSITIVE_INFINITY;
            for (Coordinate c : exits.getCoordinates()) {
                double along = (c.x - p.x) * ux + (c.y - p.y) * uy;
                if (along > wallDistance + COORD_EPS) {
                    exitDistance = Math.min(exitDistance, along);
                }
            }
            if (Double.isFinite(exitDistance)) {
                result.add(new Coordinate(p.x + ux * (exitDistance + OUTSIDE_MARGIN),
                        p.y + uy * (exitDistance + OUTSIDE_MARGIN)));
            }
        }
    }

    private static List<Coordinate> validCandidates(List<Coordinate> candidates, Coordinate p,
                                                    GradeContext ctx, EdgeValidator validator,
                                                    Object ownId, int limit) {
        candidates.sort(Comparator.comparingDouble(c -> c.distance(p)));
        List<Coordinate> valid = new ArrayList<>();
        for (Coordinate a : candidates) {
            // узел подхода обязан быть вне всех зон блокировки, включая
            // компоненты собственного ограничения (из него начинаются обычные рёбра)
            if (insideAnyBlocking(a, ctx)) {
                continue;
            }
            if (valid.stream().anyMatch(c -> c.distance(a) < COORD_EPS)) {
                continue;
            }
            EdgeValidator.EdgeCheck check = validator.check(a, p, null, ownId);
            if (check.valid) {
                valid.add(a);
                if (valid.size() >= limit) {
                    break;
                }
            }
        }
        return valid;
    }

    private static boolean insideAnyBlocking(Coordinate c, GradeContext ctx) {
        for (ForbiddenZone z : ctx.forbiddenIn(new Envelope(c))) {
            if (z.containsPoint(c)) {
                return true;
            }
        }
        return false;
    }
}
