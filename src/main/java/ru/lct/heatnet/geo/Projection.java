package ru.lct.heatnet.geo;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateFilter;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

/**
 * Преобразование координат между WGS 84 (EPSG:4326) и EPSG:32637
 * (WGS 84 / UTM zone 37N). Вход и выход сервиса — WGS 84, все расчёты длин
 * и расстояний — в метрической проекции UTM 37N (раздел 1 техприложения).
 */
public final class Projection {

    private final CoordinateTransform toUtm;
    private final CoordinateTransform toWgs;

    public Projection() {
        CRSFactory factory = new CRSFactory();
        CoordinateReferenceSystem wgs84 =
                factory.createFromParameters("EPSG:4326", "+proj=longlat +datum=WGS84 +no_defs");
        CoordinateReferenceSystem utm37n =
                factory.createFromParameters("EPSG:32637", "+proj=utm +zone=37 +datum=WGS84 +units=m +no_defs");
        CoordinateTransformFactory transformFactory = new CoordinateTransformFactory();
        this.toUtm = transformFactory.createTransform(wgs84, utm37n);
        this.toWgs = transformFactory.createTransform(utm37n, wgs84);
    }

    public Coordinate wgsToUtm(double lon, double lat) {
        ProjCoordinate out = new ProjCoordinate();
        toUtm.transform(new ProjCoordinate(lon, lat), out);
        return new Coordinate(out.x, out.y);
    }

    /** @return координата (lon, lat) */
    public Coordinate utmToWgs(double x, double y) {
        ProjCoordinate out = new ProjCoordinate();
        toWgs.transform(new ProjCoordinate(x, y), out);
        return new Coordinate(out.x, out.y);
    }

    /** Преобразует геометрию из WGS 84 в UTM 37N на месте (координаты мутируются). */
    public Geometry projectToUtm(Geometry wgsGeometry) {
        Geometry copy = wgsGeometry.copy();
        copy.apply((CoordinateFilter) c -> {
            Coordinate utm = wgsToUtm(c.x, c.y);
            c.x = utm.x;
            c.y = utm.y;
        });
        copy.geometryChanged();
        return copy;
    }
}
