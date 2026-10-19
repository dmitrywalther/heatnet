package ru.lct.heatnet.profile;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.refdata.RestrictionRules;
import java.lang.reflect.InvocationTargetException;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionSceneTest extends ProfileSceneTest {
    @Test void baseCardReportsSharedPipeCostsWithoutAllocatingThemToOneBuilding() {
        source("source",0); oks(10L,30); oks("zero",20); input.oksPoints.get(1).flowTph=0;
        line("trunk","source","joint",0,10,1000,1);
        line("tail",10L,"joint",30,10,2000,1);
        line("other","joint","zero",10,20,3000,0);
        Map<String,Object> card=card(false,"n:10");
        assertEquals("connected",card.get("status"));
        List<Map<String,Object>> segments=list(card,"segments");
        assertEquals("trunk",segments.get(0).get("id"));
        assertEquals(2,segments.get(0).get("usage_count"));
        assertEquals(true,segments.get(0).get("shared"));
        assertEquals(1,segments.get(1).get("usage_count"));
        assertNull(segments.get(1).get("depth_start")); assertNull(segments.get(1).get("slope"));
        assertEquals(10.0,(double)map(card,"route_summary").get("shared_length"),0.001);
        assertEquals(20.0,(double)map(card,"route_summary").get("dedicated_length"),0.001);
        assertEquals(3000.0,map(card,"costs").get("path_segment_cost"));
        assertEquals(1000.0,map(card,"costs").get("shared_segment_cost"));
        assertEquals(List.of(100),card.get("diameters"));
        assertEquals("existing_chamber",map(card,"source").get("kind"));
        assertEquals(1.0,map(card,"oks").get("flow_tph"));
        assertThrows(IllegalArgumentException.class,()->scene());
    }

    @Test void depthCardReversesDepthAndKeepsOriginalProfilePayloadUntouched() {
        source("source",0); oks(10L,30);
        line("tail",10L,"source",30,0,2000,1);
        ObjectNode p=(ObjectNode)features.get(0).get("properties");p.put("depth_start",4).put("depth_end",3);
        ProfileScene scene=scene(); Map<String,Object> before=scene.profile("n:10");
        Map<String,Object> card=invoke(scene,"connection","n:10");
        assertEquals(3.0,list(card,"segments").get(0).get("depth_start"));
        assertEquals(4.0,list(card,"segments").get(0).get("depth_end"));
        assertEquals(before,scene.profile("n:10"));
        assertFalse(((Map<?,?>)before.get("oks")).containsKey("flow_tph"));
    }

    @Test void disconnectedOksHasASelectableCardAndDoesNotInventReason() {
        source("source",0); oks(10L,30); oks("missing",40); line("a","source",10L,0,30,1000,1);
        Map<String,Object> card=card(false,"s:missing");
        assertEquals("unconnected",card.get("status"));assertNull(card.get("source"));
        assertTrue(String.valueOf(card.get("message")).contains("История причин поиска"));
        assertNull(card.get("route_summary"));assertNull(card.get("costs"));assertNull(card.get("entry"));
        assertEquals(List.of(),card.get("segments"));assertEquals(List.of(),card.get("explanations"));
        assertThrows(IllegalArgumentException.class,()->card(false,"s:unknown"));
        List<Map<String,Object>> choices=list(invoke(build(false),"connections",null),"oks");
        assertEquals(false,choices.get(1).get("connected"));assertEquals(1.0,choices.get(1).get("flow_tph"));
    }

    @Test void typedIdentifiersRemainDistinctInDownstreamCounts() {
        source("source",0); BigInteger id=new BigInteger("9007199254740993");
        oks(id,30);oks(id.toString(),20);line("a","source","joint",0,10,100,2);
        line("b","joint",id,10,30,200,1);line("c","joint",id.toString(),10,20,200,1);
        assertEquals(2,list(card(false,"n:"+id),"segments").get(0).get("usage_count"));
        assertEquals(20.0,(double)map(card(false,"s:"+id),"route_summary").get("total_length"),0.001);
    }

    @Test void rejectsCyclesAndMultipleSourcesInsteadOfChoosingAConvenientPath() {
        source("source",0);source("second",20);oks(10L,30);
        line("a","source",10L,0,30,100,1);line("b","second",10L,20,30,100,1);
        assertThrows(IllegalArgumentException.class,()->card(false,"n:10"));
        input.chambers.remove(1);line("c","source","second",0,20,100,1);
        assertThrows(IllegalArgumentException.class,()->card(false,"n:10"));
    }

    @Test void terminalEntryIsRecoveredAcrossDepthSplits() {
        source("source",0);oks(10L,30);building("building",25,35);
        line("a","source","split",0,27,100,1);line("b","split",10L,27,30,100,1);
        Map<String,Object> entry=map(card(false,"n:10"),"entry");
        assertEquals("available",entry.get("status"));assertEquals("s:building",entry.get("building_key"));
        assertEquals(5.0,(double)entry.get("internal_length"),0.001);
        assertEquals(90.0,(double)entry.get("wall_angle_deg"),0.001);
        assertNotNull(entry.get("wall_coordinates"));assertEquals(4,entry.get("wall_index"));
    }

    @Test void anEntryAtVertexDoesNotInventOneOfTheTwoWalls() {
        source("source",0);oks(10L,30);
        InputModel.Restriction r=new InputModel.Restriction();r.id="triangle";r.restrictionType="oks";
        r.utm=gf.createPolygon(new Coordinate[]{at(25,0),at(35,-10),at(35,10),at(25,0)});input.restrictions.add(r);
        line("a","source",10L,0,30,100,1);
        assertEquals("ambiguous",map(card(false,"n:10"),"entry").get("status"));
        assertNull(map(card(false,"n:10"),"entry").get("wall_index"));
    }

    @Test void courtyardAndMultipleContainingBuildingsDoNotProduceInventedEntry() {
        source("source",0);oks(10L,30);line("a","source",10L,0,30,100,1);
        building("building",25,35);
        InputModel.Restriction building=input.restrictions.get(0);
        building.utm=gf.createPolygon(gf.createLinearRing(building.utm.getCoordinates()),new org.locationtech.jts.geom.LinearRing[]{
                gf.createLinearRing(new Coordinate[]{at(28,-2),at(32,-2),at(32,2),at(28,2),at(28,-2)})});
        assertEquals("unavailable",map(card(false,"n:10"),"entry").get("status"));
        input.restrictions.clear();building("a",25,35);building("b",20,40);
        assertEquals("ambiguous",map(card(false,"n:10"),"entry").get("status"));
    }

    @Test void sourcePipeAndPhysicalObstaclesKeepTypedKeys() {
        oks(10L,30);InputModel.ExistingPipe pipe=new InputModel.ExistingPipe();pipe.id=55L;pipe.diameter=100;
        pipe.utm=gf.createLineString(new Coordinate[]{at(0,-5),at(0,5)});input.pipes.add(pipe);
        feature("tie","heat_chamber").set("geometry",point(0));line("a","tie",10L,0,30,100,1);
        restriction("tram","tram_tracks",20,true);
        Map<String,Object> card=card(false,"n:10");assertEquals("new_chamber_tie",map(card,"source").get("kind"));
        assertEquals(List.of("n:55"),map(card,"source").get("existing_pipe_keys"));
        assertEquals("s:tram",list(card,"obstacles").get(1).get("key"));
    }

    @Test void bendAtBoundaryDoesNotClaimOneAngleForBothDirections() {
        source("source",0);oks(10L,30);building("building",25,35);
        line("a","source","wall",0,25,100,1);line("b","wall",10L,25,30,100,1);
        com.fasterxml.jackson.databind.node.ArrayNode points=(com.fasterxml.jackson.databind.node.ArrayNode)features.get(1).path("geometry").get("coordinates");
        points.removeAll();
        for(Coordinate p:List.of(at(25,0),at(27,2),at(30,0))) {Coordinate w=projection.utmToWgs(p.x,p.y);points.addArray().add(w.x).add(w.y);}
        Map<String,Object> entry=map(card(false,"n:10"),"entry");
        assertEquals("available",entry.get("status"));assertNull(entry.get("wall_angle_deg"));
    }

    @Test void repeatedObstacleCrossingsTargetTheirOwnSegments() {
        source("source",0);oks(10L,30);line("a","source","joint",0,15,100,1);line("b","joint",10L,15,30,100,1);
        restriction("road","road",10,true);restriction("temporary","road",20,true);
        input.restrictions.get(0).utm=gf.createMultiPolygon(new org.locationtech.jts.geom.Polygon[]{
                (org.locationtech.jts.geom.Polygon)input.restrictions.get(0).utm,(org.locationtech.jts.geom.Polygon)input.restrictions.get(1).utm});
        input.restrictions.remove(1);
        List<Map<String,Object>> facts=list(card(false,"n:10"),"explanations");
        List<Map<String,Object>> obstacles=new java.util.ArrayList<>();for(Map<String,Object> fact:facts)if("obstacle".equals(fact.get("kind")))obstacles.add(fact);
        assertEquals(2,obstacles.size());assertEquals("a",map(obstacles.get(0),"target").get("segment_id"));
        assertEquals("b",map(obstacles.get(1),"target").get("segment_id"));
    }

    @Test void unrelatedMissingOrMalformedCostDoesNotBreakLegacyProfile() {
        source("source",0);oks(10L,30);segment("a","source",10L,0,30,3,3);
        ((ObjectNode)features.get(0).get("properties")).put("cost","unknown");
        assertEquals(30.0,(double)assertDoesNotThrow(()->scene().profile("n:10")).get("total_length"),0.001);
        assertThrows(IllegalArgumentException.class,()->card(true,"n:10"));
    }

    @Test void tangentContactIsNotDescribedAsCrossingAnObstacle() {
        source("source",0);oks(10L,30);line("a","source",10L,0,30,100,1);
        InputModel.Restriction r=new InputModel.Restriction();r.id="road";r.restrictionType="road";
        r.utm=gf.createPolygon(new Coordinate[]{at(10,0),at(12,5),at(8,5),at(10,0)});input.restrictions.add(r);
        Map<String,Object> obstacle=list(card(false,"n:10"),"explanations").stream().filter(f->"obstacle".equals(f.get("kind"))).findFirst().orElseThrow();
        assertTrue(String.valueOf(obstacle.get("text")).contains("касается границы"));
    }

    private void line(String id,Object a,Object b,double x,double y,double cost,double flow) {
        segment(id,a,b,x,y,3,3);ObjectNode p=(ObjectNode)features.get(features.size()-1).get("properties");
        p.put("cost",cost).put("flow_tph",flow).putNull("depth_start").putNull("depth_end");
    }
    private void building(String id,double left,double right) {
        InputModel.Restriction r=new InputModel.Restriction();r.id=id;r.restrictionType="oks";
        r.utm=gf.createPolygon(new Coordinate[]{at(left,-10),at(right,-10),at(right,10),at(left,10),at(left,-10)});input.restrictions.add(r);
    }
    private ProfileScene build(boolean depth) {
        return assertDoesNotThrow(()->ProfileScene.class.getConstructor(InputModel.class,com.fasterxml.jackson.databind.JsonNode.class,RestrictionRules.class,String.class,boolean.class)
                .newInstance(input,collection(),RestrictionRules.builtIn(),"d1",depth),"Режим карточки должен поддерживать базовый GeoJSON");
    }
    private Map<String,Object> card(boolean depth,String key) {return invoke(build(depth),"connection",key);}
    @SuppressWarnings("unchecked") private Map<String,Object> invoke(ProfileScene scene,String name,String key) {
        java.lang.reflect.Method method=assertDoesNotThrow(()->key==null?ProfileScene.class.getMethod(name):ProfileScene.class.getMethod(name,String.class));
        try {return (Map<String,Object>)(key==null?method.invoke(scene):method.invoke(scene,key));}
        catch(InvocationTargetException e) {if(e.getCause() instanceof RuntimeException)throw (RuntimeException)e.getCause();throw new AssertionError(e.getCause());}
        catch(ReflectiveOperationException e) {throw new AssertionError(e);}
    }
    @SuppressWarnings("unchecked") private Map<String,Object> map(Map<String,Object> value,String key){return (Map<String,Object>)value.get(key);}
}
