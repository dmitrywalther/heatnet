package ru.lct.heatnet.routing;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.TopologyException;

import java.util.List;

/** Геометрия единственного конечного входа в собственный ОКС. */
final class OksEntryGeometry {

    /** Только допуск совпадения точек, без расширения разрешённой области. */
    private static final double COORD_EPS = 1e-6;

    private OksEntryGeometry() {
    }

    /**
     * Исключение собственного ограничения снимает отступ, но не разрешает
     * транзит через здание. Для точки внутри core допустим один вход через
     * внешнее кольцо: ровно один контакт прямого участка с границей.
     * Это исключает повторные входы, промежуточные касания и ход вдоль стены.
     * Направление участка несущественно.
     *
     * <p>Для цели во дворе или только в зоне отступа пересечений с core быть
     * не должно: можно подойти к такой точке, не проходя сквозь материал здания.
     * Другие компоненты того же ограничения также нельзя пересекать.</p>
     */
    static boolean allows(LineString segment, List<ForbiddenZone> zones, Object ownId) {
        try {
            return check(segment, zones, ownId);
        } catch (TopologyException invalidGeometry) {
            // Невозможность надёжно проверить core не превращает его в проход.
            return false;
        }
    }

    private static boolean check(LineString segment, List<ForbiddenZone> zones, Object ownId) {
        Point start = segment.getStartPoint();
        Point end = segment.getEndPoint();
        Polygon owner = null;
        boolean startCovered = false;
        boolean endCovered = false;

        for (ForbiddenZone zone : zones) {
            if (!ownId.equals(zone.restrictionId)) {
                continue;
            }
            boolean coversStart = zone.core.covers(start);
            boolean coversEnd = zone.core.covers(end);
            if (coversStart || coversEnd) {
                // ОКС должен принадлежать единственной полигональной компоненте.
                if (!(zone.core instanceof Polygon) || owner != null) {
                    return false;
                }
                owner = (Polygon) zone.core;
                startCovered = coversStart;
                endCovered = coversEnd;
            }
        }

        if (owner != null && startCovered == endCovered) {
            return false; // оба конца внутри/на границе — это не наружный ввод
        }

        for (ForbiddenZone zone : zones) {
            if (ownId.equals(zone.restrictionId) && zone.core != owner
                    && zone.core.intersects(segment)) {
                return false;
            }
        }
        if (owner == null) {
            // Оба конца вне core; выше уже исключён любой транзит или касание.
            return true;
        }

        Point outside = startCovered ? end : start;
        Polygon shell = owner.getFactory().createPolygon(owner.getExteriorRing().getCoordinates());
        if (shell.covers(outside)) {
            // Вход из двора не становится внешним даже в общей вершине колец.
            return false;
        }

        Geometry contacts = owner.getBoundary().intersection(segment);
        if (contacts.isEmpty() || contacts.getDimension() != 0) {
            return false;
        }
        Coordinate[] points = contacts.getCoordinates();
        Coordinate entry = points[0];
        for (int i = 1; i < points.length; i++) {
            if (entry.distance(points[i]) > COORD_EPS) {
                return false;
            }
        }
        // Контакт с границей двора не является входом через внешнюю стену.
        return owner.getExteriorRing().isWithinDistance(GeoUtil.GF.createPoint(entry), COORD_EPS);
    }
}
