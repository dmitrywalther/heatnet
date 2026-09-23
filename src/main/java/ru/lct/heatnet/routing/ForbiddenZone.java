package ru.lct.heatnet.routing;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.prep.PreparedGeometry;

/**
 * Непроходимое препятствие (одна компонента геометрии ограничения),
 * раздутое на минимальный отступ и полуширину габарита новой сети.
 */
public class ForbiddenZone {

    /** id исходного ограничения. */
    public final Object restrictionId;
    public final String type;
    /** Исходная компонента геометрии (полигон или линия) в UTM. */
    public final Geometry core;
    /** Зона блокировки: core ⊕ (отступ + полуширина − ε). Пересечение ребром запрещено. */
    public final PreparedGeometry blocking;
    /** Контур для размещения вершин графа видимости: core ⊕ (отступ + полуширина + запас), упрощённый. */
    public final Geometry nodeZone;

    public ForbiddenZone(Object restrictionId, String type, Geometry core,
                         PreparedGeometry blocking, Geometry nodeZone) {
        this.restrictionId = restrictionId;
        this.type = type;
        this.core = core;
        this.blocking = blocking;
        this.nodeZone = nodeZone;
    }

    public boolean containsPoint(Coordinate c) {
        return blocking.covers(GeoUtil.GF.createPoint(c));
    }
}
