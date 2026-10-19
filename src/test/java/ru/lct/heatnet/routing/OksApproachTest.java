package ru.lct.heatnet.routing;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.refdata.PipeTable;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OksApproachTest {

    @Test
    void extendsNormalPastOverlappingBuffersOfOtherOwnComponents() throws Exception {
        Geometry building = GeoUtil.GF.createMultiPolygon(new Polygon[]{
                polygon("POLYGON ((0 0,10 0,10 10,0 10,0 0))"),
                polygon("POLYGON ((13 8,20 8,20 14,13 14,13 8))")});
        OksApproach result = approach(building, new Coordinate(5, 5));
        assertNotNull(result);
        assertTrue(result.approachNodes.stream().anyMatch(c -> c.x > 25 && near(c.y, 5)),
                "normal may cross own overlapping buffers, but must not cross the other building core");
        Coordinate extended = result.approachNodes.stream()
                .filter(c -> c.x > 25 && near(c.y, 5)).findFirst().orElseThrow();
        assertEquals(5, result.internalLength(extended), 1e-6,
                "extra exterior buffer length must not count as passage inside the building");
    }

    @Test
    void offersPerpendicularApproachToEveryExteriorWall() throws Exception {
        Polygon building = polygon("POLYGON ((0 0,100 0,100 60,0 60,0 0))");
        OksApproach result = approach(building, new Coordinate(17, 23));
        assertNotNull(result);
        assertTrue(result.approachNodes.stream().anyMatch(c -> c.x < 0 && near(c.y, 23)), "left wall");
        assertTrue(result.approachNodes.stream().anyMatch(c -> c.x > 100 && near(c.y, 23)), "right wall");
        assertTrue(result.approachNodes.stream().anyMatch(c -> c.y < 0 && near(c.x, 17)), "bottom wall");
        assertTrue(result.approachNodes.stream().anyMatch(c -> c.y > 60 && near(c.x, 17)), "top wall");
        assertTrue(result.approachNodes.stream().allMatch(c -> near(c.x, 17) || near(c.y, 23)),
                "oblique approaches must not compete with feasible perpendicular entries in the first search");
    }

    @Test
    void perpendicularApproachesRotateWithTheOriginalWalls() throws Exception {
        Polygon building = polygon("POLYGON ((0 0,100 0,100 60,0 60,0 0))");
        building.apply((org.locationtech.jts.geom.CoordinateFilter) c -> rotateInPlace(c, Math.PI / 6));
        building.geometryChanged();
        Coordinate target = new Coordinate(17, 23);
        rotateInPlace(target, Math.PI / 6);
        OksApproach result = approach(building, target);
        assertNotNull(result);
        List<Coordinate> unrotated = new ArrayList<>();
        for (Coordinate c : result.approachNodes) {
            Coordinate copy = new Coordinate(c);
            rotateInPlace(copy, -Math.PI / 6);
            unrotated.add(copy);
        }
        assertTrue(unrotated.stream().anyMatch(c -> c.x > 100 && near(c.y, 23)), "rotated far wall");
        assertTrue(unrotated.stream().anyMatch(c -> c.y > 60 && near(c.x, 17)), "rotated top wall");
        assertTrue(unrotated.stream().allMatch(c -> near(c.x, 17) || near(c.y, 23)));
    }

    @Test
    void denseNearWallDoesNotDisplaceFarWallCandidate() {
        List<Coordinate> ring = new ArrayList<>();
        for (int x = 0; x <= 1000; x += 10) {
            ring.add(new Coordinate(x, 0));
        }
        ring.add(new Coordinate(1000, 100));
        ring.add(new Coordinate(0, 100));
        ring.add(new Coordinate(0, 0));
        Polygon building = GeoUtil.GF.createPolygon(ring.toArray(new Coordinate[0]));
        OksApproach result = approach(building, new Coordinate(13, 2));
        assertNotNull(result);
        assertTrue(result.approachNodes.stream().anyMatch(c -> c.x > 1000 && near(c.y, 2)),
                "the side facing a possible network must survive near-wall candidate crowding");
    }

    @Test
    void targetOutsideAllBuffersRemainsItsOwnApproach() {
        InputModel model = new InputModel();
        InputModel.OksPoint oks = oks(new Coordinate(17, 23));
        GradeContext ctx = new ObstacleIndex(model).forGrade(PipeTable.byDu(100));
        OksApproach result = OksApproach.build(oks, ctx, new EdgeValidator(ctx));
        assertNotNull(result);
        assertFalse(result.hasTail());
        assertEquals(1, result.approachNodes.size());
        assertEquals(0, result.approachNodes.get(0).distance(oks.utm.getCoordinate()), 1e-9);
        assertEquals(0, result.internalLength(result.approachNodes.get(0)), 1e-9);
    }

    private static OksApproach approach(Geometry building, Coordinate point) {
        InputModel model = new InputModel();
        InputModel.Restriction r = new InputModel.Restriction();
        r.id = "building";
        r.restrictionType = "oks";
        r.utm = building;
        model.restrictions.add(r);
        GradeContext ctx = new ObstacleIndex(model).forGrade(PipeTable.byDu(100));
        return OksApproach.build(oks(point), ctx, new EdgeValidator(ctx));
    }

    private static InputModel.OksPoint oks(Coordinate point) {
        InputModel.OksPoint oks = new InputModel.OksPoint();
        oks.id = "oks";
        oks.flowTph = 20;
        oks.utm = GeoUtil.GF.createPoint(point);
        return oks;
    }

    private static Polygon polygon(String wkt) throws Exception {
        return (Polygon) new WKTReader(GeoUtil.GF).read(wkt);
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-6;
    }

    private static void rotateInPlace(Coordinate c, double angle) {
        double x = c.x, y = c.y;
        c.x = x * Math.cos(angle) - y * Math.sin(angle);
        c.y = x * Math.sin(angle) + y * Math.cos(angle);
    }
}
