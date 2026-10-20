package ru.lct.heatnet.comparison;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.geo.Projection;
import ru.lct.heatnet.refdata.RestrictionRules;

import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CalculationComparisonTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Projection projection = new Projection();

    @Test void comparingTheSameSavedResultHasZeroDeltasAndDoesNotWriteInputs() throws Exception {
        Fixture f = fixture("same").oks(10, 100, 0, 2).line("a", "source", 10, 100, xy(0,0),xy(100,0)).save();
        byte[] input = Files.readAllBytes(f.input), result = Files.readAllBytes(f.result);
        Map<String,Object> comparison = compare(f,f);
        assertEquals(1, comparison.get("schema_version"));
        for (Object value : map(comparison,"delta").values()) assertEquals(0, decimal(value).signum());
        assertEquals(1, map(comparison,"connections_summary").get("unchanged"));
        assertEquals("unchanged", row(comparison,"n:10").get("status"));
        assertEquals(false,map(row(comparison,"n:10"),"changes").get("path_geometry"));
        assertNull(map(row(comparison,"n:10"),"changes").get("entry"));
        assertNotNull(map(row(comparison,"n:10"),"changes").get("entry_note"));
        assertEquals(0,decimal(map(comparison,"network_geometry").get("removed_length_m")).signum());
        assertArrayEquals(input,Files.readAllBytes(f.input)); assertArrayEquals(result,Files.readAllBytes(f.result));
        assertEquals(comparison, compare(f,f));
    }

    @Test void splitReversedAndRenamedSegmentsAreTheSamePhysicalRoute() throws Exception {
        Fixture a=fixture("a").oks(10,100,0,2).line("old","source",10,100,xy(0,0),xy(100,0)).save();
        Fixture b=fixture("b").oks(10,100,0,2)
                .line("new2",10,"joint",60,xy(100,0),xy(40,0))
                .line("new1","joint","source",40,xy(40,0),xy(0,0)).save();
        Map<String,Object> c=compare(a,b);
        assertEquals("unchanged",row(c,"n:10").get("status"));
        assertEquals(false,map(row(c,"n:10"),"changes").get("path_geometry"));
        assertEquals(0,decimal(map(c,"network_geometry").get("added_length_m")).signum());
        assertEquals(100,number(map(c,"network_geometry").get("after_length_m")),1e-6);
    }

    @Test void actualDetourIsReportedWithPositiveAddedAndRemovedGeometry() throws Exception {
        Fixture a=fixture("a").oks(10,100,0,2).line("a","source",10,100,xy(0,0),xy(100,0)).save();
        Fixture b=fixture("b").oks(10,100,0,2).line("a","source",10,140,xy(0,0),xy(40,20),xy(100,0)).save();
        Map<String,Object> c=compare(a,b);
        assertEquals("changed",row(c,"n:10").get("status"));
        assertEquals(true,map(row(c,"n:10"),"changes").get("path_geometry"));
        assertEquals(40,number(map(c,"delta").get("construction_cost")),1e-9);
        assertTrue(number(map(c,"network_geometry").get("added_length_m"))>100);
        assertTrue(number(map(c,"network_geometry").get("removed_length_m"))>99);
    }

    @Test void equalLengthDifferentRoutesCannotBeMistakenForUnchanged() throws Exception {
        Fixture a=fixture("a").oks(10,100,0,2).line("a","source",10,100,xy(0,0),xy(50,20),xy(100,0)).save();
        Fixture b=fixture("b").oks(10,100,0,2).line("b","source",10,100,xy(0,0),xy(50,-20),xy(100,0)).save();
        Map<String,Object> c=compare(a,b);
        assertEquals("changed",row(c,"n:10").get("status"));
        assertEquals(true,map(row(c,"n:10"),"changes").get("path_geometry"));
    }

    @Test void generatedTieNodeNamesDoNotChangeSourceIdentity() throws Exception {
        Fixture a=fixture("a").newSource("oldTie").oks(10,100,0,2)
                .line("a","oldTie",10,100,xy(0,0),xy(100,0)).save();
        Fixture b=fixture("b").newSource("newTie").oks(10,100,0,2)
                .line("z","newTie",10,100,xy(0,0),xy(100,0)).save();
        Map<String,Object> c=compare(a,b);
        assertEquals(false,map(row(c,"n:10"),"changes").get("source"));
        assertEquals("unchanged",row(c,"n:10").get("status"));
    }

    @Test void reportsLostRestoredAddedRemovedAndStillUnconnectedSeparately() throws Exception {
        Fixture a=fixture("a").oks(1,10,0,1).oks(2,20,0,1).oks(3,30,0,1).oks(5,50,0,1)
                .line("a","source",1,10,xy(0,0),xy(10,0)).save();
        Fixture b=fixture("b").oks(1,10,0,1).oks(2,20,0,1).oks(4,40,0,1).oks(5,50,0,1)
                .line("b","source",2,20,xy(0,0),xy(20,0)).save();
        Map<String,Object> c=compare(a,b);
        assertEquals("lost",row(c,"n:1").get("status"));
        assertEquals("restored",row(c,"n:2").get("status"));
        assertEquals("removed",row(c,"n:3").get("status"));
        assertEquals("added",row(c,"n:4").get("status"));
        assertEquals("still_unconnected",row(c,"n:5").get("status"));
        assertNull(row(c,"n:3").get("after")); assertNull(row(c,"n:4").get("before"));
        assertEquals(1,map(c,"connections_summary").get("lost"));
        assertEquals(0,map(c,"connections_summary").get("changed"));
    }

    @Test void numericAndTextIdsIncludingNumbersBeyondDoubleRangeRemainDistinct() throws Exception {
        BigDecimal huge=new BigDecimal("1e400");
        Fixture f=fixture("large").oks(huge,100,0,1).oks(huge.toPlainString(),110,0,1)
                .line("a","source",huge,10,xy(0,0),xy(100,0)).save();
        Map<String,Object> c=compare(f,f);
        assertEquals("unchanged",row(c,"n:"+huge.toPlainString()).get("status"));
        assertEquals("still_unconnected",row(c,"s:"+huge.toPlainString()).get("status"));
    }

    @Test void changedLoadIsVisibleWhenPhysicalRouteIsTheSame() throws Exception {
        Fixture a=fixture("a").oks(10,100,0,1).line("a","source",10,100,xy(0,0),xy(100,0)).save();
        Fixture b=fixture("b").oks(10,100,0,2).line("a","source",10,100,xy(0,0),xy(100,0)).save();
        Map<String,Object> c=compare(a,b);
        assertEquals("changed",row(c,"n:10").get("status"));
        assertEquals(true,map(row(c,"n:10"),"changes").get("load"));
        assertEquals(false,map(row(c,"n:10"),"changes").get("path_geometry"));
    }

    @Test void oneCentimetreToleranceTreatsTinyCoordinateNoiseAsSameButNotLargerDetour() throws Exception {
        Fixture a=fixture("a").oks(10,100,0,1).line("a","source",10,100,xy(0,0),xy(100,0)).save();
        Fixture b=fixture("b").oks(10,100,0,1).line("b","source",10,100,xy(0,0),xy(50,0.005),xy(100,0)).save();
        Fixture c=fixture("c").oks(10,100,0,1).line("c","source",10,100,xy(0,0),xy(50,0.02),xy(100,0)).save();
        assertEquals(false,map(row(compare(a,b),"n:10"),"changes").get("path_geometry"));
        assertEquals(true,map(row(compare(a,c),"n:10"),"changes").get("path_geometry"));
    }

    @Test void rejectsMixedModesUnknownVariantMissingAndInconsistentSummary() throws Exception {
        Fixture f=fixture("good").oks(10,100,0,1).line("a","source",10,100,xy(0,0),xy(100,0)).save();
        Fixture d=fixture("depth").depth().oks(10,100,0,1).line("a","source",10,100,xy(0,0),xy(100,0)).save();
        assertThrows(IllegalArgumentException.class,()->compare(f,d));
        String variant=f.variant; f.variant="v99";
        assertThrows(IllegalArgumentException.class,()->compare(f,f)); f.variant=variant;
        ((ObjectNode)f.output.path("features").get(f.output.path("features").size()-1).get("properties")).put("calculated_cost",123);
        mapper.writeValue(f.result.toFile(),f.output);
        assertThrows(IllegalArgumentException.class,()->compare(f,f));
        f.output.withArray("features").remove(f.output.withArray("features").size()-1);
        mapper.writeValue(f.result.toFile(),f.output);
        assertThrows(IllegalArgumentException.class,()->compare(f,f));
    }

    @Test void rejectsUnconnectedListThatDisagreesWithSavedGraph() throws Exception {
        Fixture f=fixture("wrong").oks(10,100,0,1).line("a","source",10,100,xy(0,0),xy(100,0)).save();
        ObjectNode summary=(ObjectNode)f.output.path("features").get(1).get("properties");
        summary.withArray("unconnected_oks_ids").add(10);
        mapper.writeValue(f.result.toFile(),f.output);
        assertThrows(IllegalArgumentException.class,()->compare(f,f));
    }

    @Test void wallRingStartAndOrientationDoNotCreateEntryChanges() throws Exception {
        Fixture a=fixture("a").oks(10,100,0,1).building(false).line("a","source",10,100,xy(0,0),xy(100,0)).save();
        Fixture b=fixture("b").oks(10,100,0,1).building(true).line("a","source",10,100,xy(0,0),xy(100,0)).save();
        assertEquals(false,map(row(compare(a,b),"n:10"),"changes").get("entry"));
    }

    @Test void addedAndRemovedRestrictionIdsAreTypedAndCarryOriginalGeometry() throws Exception {
        Fixture a=fixture("a").oks(10,100,0,1).line("a","source",10,100,xy(0,0),xy(100,0)).building(false).save();
        Fixture b=fixture("b").oks(10,100,0,1).line("a","source",10,100,xy(0,0),xy(100,0)).building(false).save();
        ObjectNode old=(ObjectNode)a.original.withArray("features").get(3);
        old.with("properties").put("id",77).put("restriction_type","prohibited_site");
        ObjectNode added=(ObjectNode)b.original.withArray("features").get(3);
        added.with("properties").put("id","77").put("restriction_type","prohibited_site");
        mapper.writeValue(a.input.toFile(),a.original);mapper.writeValue(b.input.toFile(),b.original);
        Map<String,Object> changes=map(compare(a,b),"input_changes");
        List<?> removed=(List<?>)changes.get("removed_restrictions"),newOnes=(List<?>)changes.get("added_restrictions");
        assertEquals(1,removed.size());assertEquals(1,newOnes.size());
        assertEquals("n:77",((Map<?,?>)removed.get(0)).get("key"));
        assertEquals("s:77",((Map<?,?>)newOnes.get(0)).get("key"));
        assertEquals("prohibited_site",((Map<?,?>)newOnes.get(0)).get("restriction_type"));
        // DoubleNode vs DecimalNode are unequal as Java nodes although the JSON numbers are identical.
        assertEquals(mapper.writeValueAsString(added.get("geometry")),
                mapper.writeValueAsString(((Map<?,?>)newOnes.get(0)).get("geometry")));
    }

    @Test void attributesDetectChangedDepthDespiteSameGeometryAndCost() throws Exception {
        Fixture a=fixture("depth-before").depth().oks(10,100,0,1)
                .line("a","source",10,100,xy(0,0),xy(100,0)).save();
        Fixture b=fixture("depth-after").depth().oks(10,100,0,1)
                .line("b","source",10,100,xy(0,0),xy(100,0)).save();
        ((ObjectNode)b.output.withArray("features").get(0).get("properties")).put("depth_end",3.2);
        mapper.writeValue(b.result.toFile(),b.output);
        Map<String,Object> c=compare(a,b),row=row(c,"n:10");
        assertEquals(false,map(row,"changes").get("path_geometry"));
        assertEquals(true,map(row,"changes").get("segment_attributes"));
        assertEquals("changed",row.get("status"));
        assertNotNull(map(row,"before").get("route_segments"));
        assertEquals(0.01,number(map(c,"comparison_tolerances").get("depth_m")),1e-12);
    }

    @Test void attributesDetectReassignedDiametersWhenDiameterSetIsUnchanged() throws Exception {
        Fixture a=fixture("diameter-before").oks(10,100,0,1)
                .line("a","source","joint",50,xy(0,0),xy(50,0)).line("b","joint",10,50,xy(50,0),xy(100,0)).save();
        Fixture b=fixture("diameter-after").oks(10,100,0,1)
                .line("a","source","joint",50,xy(0,0),xy(50,0)).line("b","joint",10,50,xy(50,0),xy(100,0)).save();
        ((ObjectNode)a.output.withArray("features").get(1).get("properties")).put("diameter",125);
        ((ObjectNode)b.output.withArray("features").get(0).get("properties")).put("diameter",125);
        mapper.writeValue(a.result.toFile(),a.output);mapper.writeValue(b.result.toFile(),b.output);
        Map<String,Object> row=row(compare(a,b),"n:10");
        assertEquals(false,map(row,"changes").get("diameter_set"));
        assertEquals(true,map(row,"changes").get("segment_attributes"));
        assertEquals("changed",row.get("status"));
    }

    @Test void attributesPreserveLinearDepthAcrossSplitsAndReversal() throws Exception {
        Fixture a=fixture("depth-whole").depth().oks(10,100,0,1)
                .line("old","source",10,100,xy(0,0),xy(100,0)).save();
        Fixture b=fixture("depth-split").depth().oks(10,100,0,1)
                .line("reversed-tail",10,"joint",60,xy(100,0),xy(40,0))
                .line("reversed-head","joint","source",40,xy(40,0),xy(0,0)).save();
        ((ObjectNode)a.output.withArray("features").get(0).get("properties")).put("depth_end",4);
        ((ObjectNode)b.output.withArray("features").get(0).get("properties")).put("depth_start",4).put("depth_end",3.4);
        ((ObjectNode)b.output.withArray("features").get(1).get("properties")).put("depth_start",3.4).put("depth_end",3);
        mapper.writeValue(a.result.toFile(),a.output);mapper.writeValue(b.result.toFile(),b.output);
        Map<String,Object> row=row(compare(a,b),"n:10");
        assertEquals(false,map(row,"changes").get("segment_attributes"));
        assertEquals("unchanged",row.get("status"));
    }

    @Test void rejectsOutputChamberWhosePointDiffersFromItsIncidentNode() throws Exception {
        Fixture f=fixture("wrong-chamber").newSource("tie").oks(10,100,0,1)
                .line("a","tie",10,100,xy(0,0),xy(100,0)).save();
        ((ObjectNode)f.output.withArray("features").get(0)).set("geometry",
                f.geometry("Point",new double[][]{xy(10,0)}));
        mapper.writeValue(f.result.toFile(),f.output);
        assertThrows(IllegalArgumentException.class,()->compare(f,f));
    }

    @Test void rejectsOrphanOutputChambersAndTechnicalNodes() throws Exception {
        for(String type:List.of("heat_chamber","technical_node")) {
            Fixture f=fixture("orphan-"+type).oks(10,100,0,1).line("a","source",10,100,xy(0,0),xy(100,0)).save();
            ObjectNode orphan=f.feature(f.output,"orphan",type);
            orphan.with("properties").put("variant_id","v1").put("cost",0);
            orphan.set("geometry",f.geometry("Point",new double[][]{xy(30,30)}));
            mapper.writeValue(f.result.toFile(),f.output);
            assertThrows(IllegalArgumentException.class,()->compare(f,f),type);
        }
    }

    @Test void rejectsExportedSegmentLengthInconsistentWithItsGeometry() throws Exception {
        for(boolean depth:List.of(false,true)) {
            Fixture f=fixture("length-"+depth);if(depth)f.depth();
            f.oks(10,100,0,1).line("a","source",10,100,xy(0,0),xy(100,0)).save();
            ((ObjectNode)f.output.withArray("features").get(0).get("properties")).put("length",200);
            ((ObjectNode)f.output.withArray("features").get(1).get("properties")).put("new_network_length",200);
            mapper.writeValue(f.result.toFile(),f.output);
            assertThrows(IllegalArgumentException.class,()->compare(f,f),"depth="+depth);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> compare(Fixture before,Fixture after) throws Exception {
        Class<?> type=assertDoesNotThrow(()->Class.forName("ru.lct.heatnet.comparison.CalculationComparison"),
                "Модуль сравнения сохранённых расчётов должен существовать");
        try {
            return (Map<String,Object>)type.getMethod("compare",Path.class,Path.class,RestrictionRules.class,String.class,
                    Path.class,Path.class,RestrictionRules.class,String.class).invoke(null,before.input,before.result,
                    RestrictionRules.builtIn(),before.variant,after.input,after.result,RestrictionRules.builtIn(),after.variant);
        } catch(InvocationTargetException e) {
            if(e.getCause() instanceof Exception) throw (Exception)e.getCause(); throw new AssertionError(e.getCause());
        }
    }
    private Fixture fixture(String name) {return new Fixture(name);}
    private double[] xy(double x,double y){return new double[]{x,y};}
    @SuppressWarnings("unchecked") private Map<String,Object> map(Map<String,Object> m,String key){return (Map<String,Object>)m.get(key);}
    @SuppressWarnings("unchecked") private Map<String,Object> row(Map<String,Object> m,String key){
        return ((List<Map<String,Object>>)m.get("connections")).stream().filter(r->key.equals(r.get("oks_key"))).findFirst().orElseThrow();
    }
    private BigDecimal decimal(Object n){return new BigDecimal(n.toString());}
    private double number(Object n){return ((Number)n).doubleValue();}

    private class Fixture {
        final Path input,result; final ObjectNode original=collection(),output=collection();
        final Map<Object,Double> loads=new LinkedHashMap<>(); final Set<Object> connected=new HashSet<>();
        String variant="v1"; boolean generated; double cost,length;
        Fixture(String name){input=temp.resolve(name+"-input.json");result=temp.resolve(name+"-result.json");
            feature(original,"source","heat_chamber").set("geometry",geometry("Point",new double[][]{xy(0,0)}));
            ObjectNode pipe=feature(original,"pipe","heat_network");pipe.with("properties").put("diameter",300);
            pipe.set("geometry",geometry("LineString",new double[][]{xy(0,-20),xy(0,20)}));}
        Fixture depth(){variant="d1";return this;}
        Fixture newSource(String id){original.withArray("features").remove(0); generated=true;
            ObjectNode c=feature(output,id,"heat_chamber");c.with("properties").put("cost",0).put("diameter",100).put("variant_id",variant);
            c.set("geometry",geometry("Point",new double[][]{xy(0,0)}));return this;}
        Fixture oks(Object id,double x,double y,double flow){ObjectNode f=feature(original,id,"oks_connection_point");
            f.with("properties").put("flow_tph",flow);f.set("geometry",geometry("Point",new double[][]{xy(x,y)}));loads.put(id,flow);return this;}
        Fixture line(String id,Object from,Object to,double value,double[]... points){
            ObjectNode f=feature(output,id,"heat_network"),p=f.with("properties");
            p.put("variant_id",variant).put("flow_tph",2).put("diameter",100).put("cost",value).put("laying_method","base");
            p.set("start_node_id",mapper.valueToTree(from));p.set("end_node_id",mapper.valueToTree(to));
            double len=0;for(int i=1;i<points.length;i++)len+=Math.hypot(points[i][0]-points[i-1][0],points[i][1]-points[i-1][1]);
            p.put("length",len);if(variant.startsWith("d"))p.put("depth_start",3).put("depth_end",3);
            else p.putNull("depth_start").putNull("depth_end");
            f.set("geometry",geometry("LineString",points));cost+=value;length+=len;
            if(loads.containsKey(from))connected.add(from);if(loads.containsKey(to))connected.add(to);return this;}
        Fixture building(boolean reverse){ObjectNode b=feature(original,"building","restriction");b.with("properties").put("restriction_type","oks");
            double[][] ring=reverse?new double[][]{xy(110,10),xy(110,-10),xy(90,-10),xy(90,10),xy(110,10)}
                    :new double[][]{xy(90,-10),xy(110,-10),xy(110,10),xy(90,10),xy(90,-10)};
            ObjectNode geom=mapper.createObjectNode().put("type","Polygon");geom.putArray("coordinates").add(geometry("LineString",ring).get("coordinates"));
            b.set("geometry",geom);return this;}
        Fixture save() throws Exception {
            ObjectNode f=feature(output,variant+"_summary","variant_summary"),p=f.with("properties");
            double penalty=loads.entrySet().stream().filter(e->!connected.contains(e.getKey())).mapToDouble(e->100000000+500000*e.getValue()).sum();
            p.put("variant_id",variant).put("rank",1).put("construction_cost",cost).put("chamber_construction_cost",0)
                    .put("existing_chamber_tie_in_count",connected.isEmpty()||generated?0:1).put("existing_chamber_tie_in_cost",0)
                    .put("unconnected_penalty",penalty).put("calculated_cost",cost+penalty).put("new_network_length",length).put("score",cost*0.00001);
            ArrayNode u=p.putArray("unconnected_oks_ids");loads.keySet().stream().filter(id->!connected.contains(id)).forEach(id->u.add(mapper.valueToTree(id)));
            f.putNull("geometry");mapper.writeValue(input.toFile(),original);mapper.writeValue(result.toFile(),output);return this;}
        ObjectNode collection(){ObjectNode c=mapper.createObjectNode().put("type","FeatureCollection");c.putArray("features");return c;}
        ObjectNode feature(ObjectNode root,Object id,String type){ObjectNode f=root.withArray("features").addObject().put("type","Feature");
            ObjectNode p=f.putObject("properties").put("object_type",type);p.set("id",mapper.valueToTree(id));return f;}
        ObjectNode geometry(String type,double[][] points){ObjectNode g=mapper.createObjectNode().put("type",type);ArrayNode a=g.putArray("coordinates");
            for(double[] point:points){Coordinate w=projection.utmToWgs(400000+point[0],6200000+point[1]);
                if("Point".equals(type))a.add(w.x).add(w.y);else a.addArray().add(w.x).add(w.y);}return g;}
    }
}
