package ru.lct.heatnet.routing;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.prep.PreparedGeometry;

/**
 * Препятствие, допускающее специальный проход (одна компонента геометрии):
 * дорога, трамвайные пути, газопровод, силовой кабель, существующая тепловая
 * сеть при пересечении без врезки.
 */
public class SpecialObstacle {

    public final Object sourceId;
    public final String type;
    public final double kSpec;
    /** Минимальный угол пересечения, град.; null — не нормируется. */
    public final Double minAngleDeg;
    /**
     * Граница специального участка: для полигонов — отступ за границу вдоль
     * трассы (3 м), для линейных — отступ от точки пересечения вдоль трассы (2 м).
     */
    public final double marginM;
    public final boolean polygonal;
    /** Компонента исходной геометрии в UTM. */
    public final Geometry core;
    public final PreparedGeometry corePrep;
    /**
     * Зона минимального горизонтального расстояния (с учётом габаритов обеих
     * сторон). Нахождение ребра в зоне без корректного пересечения core —
     * нарушение.
     */
    public final Geometry proximityGeom;
    public final PreparedGeometry proximity;
    /** Для полигональных — core ⊕ margin (зона спецучастка); для линейных null. */
    public final Geometry boundZone;
    public final PreparedGeometry boundZonePrep;
    /**
     * Вертикальные условия пересечения (режим с глубиной); для существующей
     * теплосети высота габарита подставлена по её ДУ. null — не нормируются.
     */
    public final ru.lct.heatnet.refdata.RestrictionRule.Vertical vertical;

    public SpecialObstacle(Object sourceId, String type, double kSpec, Double minAngleDeg,
                           double marginM, boolean polygonal, Geometry core,
                           PreparedGeometry corePrep, Geometry proximityGeom,
                           PreparedGeometry proximity, Geometry boundZone,
                           PreparedGeometry boundZonePrep,
                           ru.lct.heatnet.refdata.RestrictionRule.Vertical vertical) {
        this.sourceId = sourceId;
        this.type = type;
        this.kSpec = kSpec;
        this.minAngleDeg = minAngleDeg;
        this.marginM = marginM;
        this.polygonal = polygonal;
        this.core = core;
        this.corePrep = corePrep;
        this.proximityGeom = proximityGeom;
        this.proximity = proximity;
        this.boundZone = boundZone;
        this.boundZonePrep = boundZonePrep;
        this.vertical = vertical;
    }
}
