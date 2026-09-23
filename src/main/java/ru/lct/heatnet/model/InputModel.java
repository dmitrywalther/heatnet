package ru.lct.heatnet.model;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;

import java.util.ArrayList;
import java.util.List;

/**
 * Разобранный входной набор. Геометрии хранятся в метрической проекции
 * EPSG:32637; для точечных объектов дополнительно сохраняются исходные
 * координаты WGS 84, чтобы выходной файл ссылался на те же координаты
 * без потерь обратного преобразования.
 */
public class InputModel {

    public static class SourcePoint {
        public Object id;
        public String name;
        public Point utm;
        public Coordinate wgs;
    }

    public static class ExistingPipe {
        public Object id;
        public int diameter;
        public LineString utm;
    }

    public static class ExistingChamber {
        public Object id;
        public Point utm;
        public Coordinate wgs;
    }

    public static class OksPoint {
        public Object id;
        public double flowTph;
        public Point utm;
        public Coordinate wgs;
    }

    public static class Restriction {
        public Object id;
        public String restrictionType;
        /** Polygon/MultiPolygon/LineString/MultiLineString в UTM. */
        public Geometry utm;
    }

    public final List<SourcePoint> sources = new ArrayList<>();
    public final List<ExistingPipe> pipes = new ArrayList<>();
    public final List<ExistingChamber> chambers = new ArrayList<>();
    public final List<OksPoint> oksPoints = new ArrayList<>();
    public final List<Restriction> restrictions = new ArrayList<>();
}
