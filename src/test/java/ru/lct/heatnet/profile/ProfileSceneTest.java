package ru.lct.heatnet.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import ru.lct.heatnet.geo.Projection;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.refdata.RestrictionRules;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProfileSceneTest {
    final Projection projection = new Projection();
    final GeometryFactory gf = new GeometryFactory();
    final ObjectMapper mapper = new ObjectMapper();
    final InputModel input = new InputModel();
    final ArrayNode features = mapper.createArrayNode();

    @Test void followsUndirectedTreeAndReversesDepthWithCoordinates() {
        source("source", 0); oks(10L, 30); oks("other", 20);
        segment("b", 10L, "joint", 30, 10, 2.5, 3);
        segment("a", "source", "joint", 0, 10, 3.5, 3);
        segment("branch", "joint", "other", 10, 20, 3, 3);
        Map<String, Object> profile = scene().profile("n:10");
        List<Map<String, Object>> segments = list(profile, "segments");
        assertEquals(2, segments.size());
        assertEquals("a", segments.get(0).get("id"));
        assertEquals("b", segments.get(1).get("id"));
        assertEquals(3.0, segments.get(1).get("depth_start"));
        assertEquals(2.5, segments.get(1).get("depth_end"));
        assertEquals(30.0, (double) profile.get("total_length"), 0.001);
        assertEquals(-0.025, (double) segments.get(1).get("slope"), 0.00001);
        assertEquals("10", ((Map<?, ?>) profile.get("oks")).get("label"));
    }

    @Test void preservesLargeNumericAndStringIdentifiersSeparately() {
        source("source", 0);
        BigInteger id = new BigInteger("9007199254740993");
        oks(id, 10); oks(id.toString(), 20);
        segment("a", "source", id, 0, 10, 3, 3);
        segment("b", "source", id.toString(), 0, 20, 3, 3);
        List<Map<String, Object>> options = list(scene().list(), "oks");
        assertEquals("n:9007199254740993", options.get(0).get("key"));
        assertEquals("s:9007199254740993", options.get(1).get("key"));
        assertEquals(10.0, (double) scene().profile("n:9007199254740993").get("total_length"), 0.001);
    }

    @Test void distinguishesDisconnectedOksAndRejectsMissingDepth() {
        source("source", 0); oks(10L, 10); oks(20L, 20);
        segment("a", "source", 10L, 0, 10, 3, 3);
        assertEquals(false, list(scene().list(), "oks").get(1).get("connected"));
        assertThrows(IllegalArgumentException.class, () -> scene().profile("n:20"));
        ((ObjectNode) features.get(0).get("properties")).putNull("depth_end");
        assertThrows(IllegalArgumentException.class, () -> scene().profile("n:10"));
    }

    @Test void reportsPhysicalCrossingsAndYamlDepthsWithoutInventedRoadBottom() throws Exception {
        source("source", 0); oks(10L, 30);
        segment("a", "source", 10L, 0, 30, 3, 3);
        restriction("gas", "gas_pipeline", 10, false);
        restriction("street", "road", 20, true);
        RestrictionRules rules = RestrictionRules.builtIn().mergeYaml(new ByteArrayInputStream(
                ("restrictions:\n  gas_pipeline:\n    vertical:\n      obstacle_top_depth: 4.2\n"
                + "      obstacle_height: 0.6\n  road:\n    vertical:\n      min_top_depth: 2.2\n")
                .getBytes(StandardCharsets.UTF_8)));
        Map<String, Object> profile = new ProfileScene(input, collection(), rules, "d1").profile("n:10");
        List<Map<String, Object>> obstacles = list(profile, "obstacles");
        assertEquals(2, obstacles.size());
        Map<String, Object> gas = obstacles.get(0), road = obstacles.get(1);
        assertEquals(4.2, gas.get("top_depth"));
        assertEquals(4.8, (double) gas.get("bottom_depth"), 1e-9);
        assertEquals(9.8, (double) gas.get("from"), 0.001);
        assertEquals(10.2, (double) gas.get("to"), 0.001);
        assertEquals(2.2, road.get("min_top_depth"));
        assertNull(road.get("bottom_depth"));
    }

    @Test void rejectsAmbiguousTwoSourceComponent() {
        source("left", 0); source("right", 20); oks(10L, 10);
        segment("a", "left", 10L, 0, 10, 3, 3);
        segment("b", "right", 10L, 20, 10, 3, 3);
        assertThrows(IllegalArgumentException.class, () -> scene().profile("n:10"));
    }

    @Test void recognizesNewChamberTieOnExistingPipe() {
        oks(10L, 20);
        InputModel.ExistingPipe pipe = new InputModel.ExistingPipe();
        pipe.id = "existing"; pipe.diameter = 100;
        pipe.utm = gf.createLineString(new Coordinate[]{at(0, -10), at(0, 10)});
        input.pipes.add(pipe);
        ObjectNode chamber = feature("tie", "heat_chamber");
        chamber.set("geometry", point(0));
        segment("a", "tie", 10L, 0, 20, 3, 3);
        assertEquals("tie", ((Map<?, ?>) scene().profile("n:10").get("source")).get("label"));
    }

    @Test void rejectsDisconnectedDepthsAtSharedNodeAndCycles() {
        source("source",0); oks(10L,30);
        segment("a","source","joint",0,10,3,3);
        segment("b","joint",10L,10,30,3.5,3);
        assertThrows(IllegalArgumentException.class, () -> scene().profile("n:10"));
        ((ObjectNode)features.get(1).get("properties")).put("depth_start",3);
        segment("c","source",10L,0,30,3,3);
        assertThrows(IllegalArgumentException.class, () -> scene().profile("n:10"));
    }

    @Test void keepsObstacleKeysDistinctWhenNumericAndStringIdsHaveSameLabel() {
        source("source",0); oks(10L,30);
        segment("a","source",10L,0,30,3,3);
        restriction("1","road",10,true); input.restrictions.get(0).id=1L;
        restriction("1","road",20,true);
        List<Map<String,Object>> crossing=list(scene().profile("n:10"),"obstacles");
        assertEquals("n:1",crossing.get(0).get("key"));
        assertEquals("s:1",crossing.get(1).get("key"));
    }

    @Test void existingPipeKeepsPhysicalDepthForUnderOnlyAndTieDoesNotClaimCrossingClearance() throws Exception {
        oks(10L,20);
        InputModel.ExistingPipe pipe=new InputModel.ExistingPipe();pipe.id="old";pipe.diameter=100;
        pipe.utm=gf.createLineString(new Coordinate[]{at(0,-10),at(0,10)});input.pipes.add(pipe);
        feature("tie","heat_chamber").set("geometry",point(0));
        segment("a","tie",10L,0,20,3,3);
        RestrictionRules rules=RestrictionRules.builtIn().mergeYaml(new ByteArrayInputStream(
                "heat_network_crossing:\n  vertical:\n    crossing: under\n    min_top_depth: 4.5\n    obstacle_top_depth: 4.2\n".getBytes(StandardCharsets.UTF_8)));
        Map<String,Object> crossing=list(new ProfileScene(input,collection(),rules,"d1").profile("n:10"),"obstacles").get(0);
        assertEquals(4.2,crossing.get("top_depth"));
        assertEquals(4.38,(double)crossing.get("bottom_depth"),1e-9);
        assertEquals("tie_in",crossing.get("role"));
        assertNull(crossing.get("clearance"));
        assertNull(crossing.get("min_top_depth"));
    }

    ProfileScene scene() { return new ProfileScene(input, collection(), RestrictionRules.builtIn(), "d1"); }
    JsonNode collection() { return mapper.createObjectNode().put("type", "FeatureCollection").set("features", features); }
    @SuppressWarnings("unchecked") List<Map<String, Object>> list(Map<String,Object> map, String key) {
        return (List<Map<String,Object>>) map.get(key);
    }
    Coordinate at(double x, double y) { return new Coordinate(500000 + x, 6200000 + y); }
    Coordinate wgs(double x) { Coordinate c = at(x, 0); return projection.utmToWgs(c.x, c.y); }
    ObjectNode point(double x) {
        ObjectNode g = mapper.createObjectNode().put("type", "Point");
        Coordinate c = wgs(x); g.putArray("coordinates").add(c.x).add(c.y); return g;
    }
    void oks(Object id, double x) {
        InputModel.OksPoint o = new InputModel.OksPoint(); o.id=id; o.flowTph=1;
        o.utm=gf.createPoint(at(x, 0)); o.wgs=wgs(x); input.oksPoints.add(o);
    }
    void source(Object id, double x) {
        InputModel.ExistingChamber c=new InputModel.ExistingChamber(); c.id=id;
        c.utm=gf.createPoint(at(x,0)); c.wgs=wgs(x); input.chambers.add(c);
    }
    ObjectNode feature(String id, String type) {
        ObjectNode f=features.addObject().put("type", "Feature");
        f.putObject("properties").put("id",id).put("object_type",type).put("variant_id","d1"); return f;
    }
    void segment(String id, Object a, Object b, double x, double y, double d0, double d1) {
        ObjectNode f=feature(id,"heat_network"), p=(ObjectNode)f.get("properties");
        p.set("start_node_id",mapper.valueToTree(a)); p.set("end_node_id",mapper.valueToTree(b));
        p.put("depth_start",d0).put("depth_end",d1).put("diameter",100).put("length",Math.abs(x-y)).put("laying_method","base");
        ArrayNode cs=f.putObject("geometry").put("type","LineString").putArray("coordinates");
        Coordinate c=wgs(x), d=wgs(y); cs.addArray().add(c.x).add(c.y); cs.addArray().add(d.x).add(d.y);
    }
    void restriction(String id, String type, double x, boolean polygon) {
        InputModel.Restriction r=new InputModel.Restriction(); r.id=id; r.restrictionType=type;
        r.utm=polygon ? gf.createPolygon(new Coordinate[]{at(x-1,-10),at(x+1,-10),at(x+1,10),at(x-1,10),at(x-1,-10)})
                : gf.createLineString(new Coordinate[]{at(x,-10),at(x,10)}); input.restrictions.add(r);
    }
}
