package ru.lct.heatnet.routing;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.operation.distance.DistanceOp;
import ru.lct.heatnet.model.InputModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Подход к точке подключения ОКС.
 *
 * <p>Полигон ОКС, содержащий целевую точку, — непроходимое препятствие,
 * но допускается один финальный прямой участок от ближайшей к точке границы
 * полигона до самой точки; отступ к собственному полигону (всем компонентам
 * этого ограничения) на этот участок не распространяется (раздел 2.2
 * техприложения).</p>
 */
public final class OksApproach {

    /** Максимум кандидатов подхода, передаваемых маршрутизатору. */
    private static final int MAX_APPROACH_NODES = 60;

    /**
     * Точки входа маршрута (узлы графа видимости) снаружи всех зон блокировки,
     * упорядоченные по длине финального участка.
     */
    public final List<Coordinate> approachNodes;
    /** id ограничения собственного полигона; null, если точка вне полигонов. */
    public final Object ownRestrictionId;
    public final InputModel.OksPoint oks;

    private OksApproach(InputModel.OksPoint oks, List<Coordinate> approachNodes, Object ownRestrictionId) {
        this.oks = oks;
        this.approachNodes = approachNodes;
        this.ownRestrictionId = ownRestrictionId;
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
            return new OksApproach(oks, List.of(p), null);
        }

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
        candidates.sort(Comparator.comparingDouble(c -> c.distance(p)));

        List<Coordinate> valid = new ArrayList<>();
        int blockedNodes = 0, invalidSegments = 0;
        for (Coordinate a : candidates) {
            // узел подхода обязан быть вне всех зон блокировки, включая
            // компоненты собственного ограничения (из него начинаются обычные рёбра)
            if (insideAnyBlocking(a, ctx)) {
                blockedNodes++;
                continue;
            }
            EdgeValidator.EdgeCheck check = validator.check(a, p, null, ownId);
            if (check.valid) {
                valid.add(a);
                if (valid.size() >= MAX_APPROACH_NODES) {
                    break;
                }
            } else {
                invalidSegments++;
            }
        }
        if (valid.isEmpty()) {
            org.slf4j.LoggerFactory.getLogger(OksApproach.class).warn(
                    "ОКС {}: из {} кандидатов подхода {} в зонах блокировки, {} с недопустимым сегментом",
                    oks.id, candidates.size(), blockedNodes, invalidSegments);
            return null;
        }
        return new OksApproach(oks, valid, ownId);
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
