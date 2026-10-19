package ru.lct.heatnet.network;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.geo.GeoJsonWriter;
import ru.lct.heatnet.geo.GeoJsonReader;
import ru.lct.heatnet.geo.Projection;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.routing.GeoUtil;
import ru.lct.heatnet.routing.ObstacleIndex;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class NetworkOutputIdCollisionTest {
    @Test
    void ignoredAdditionalInputTypesStillReserveTheirIds() throws Exception {
        String json = "{\"type\":\"FeatureCollection\",\"features\":["
                + "{\"type\":\"Feature\",\"properties\":{\"object_type\":\"metadata\",\"id\":\"v1_net_1\"}},"
                + "{\"type\":\"Feature\",\"properties\":{\"object_type\":\"metadata\",\"id\":\"v1_net_1_2\"}},"
                + "{\"type\":\"Feature\",\"properties\":{\"object_type\":\"metadata\",\"id\":\"v1_summary\"}}]}";
        InputModel input = new GeoJsonReader(new Projection()).read(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        NetworkPlan plan = new NetworkPlan();
        PlanNode source = plan.addNode(new PlanNode(PlanNode.Kind.EXISTING_CHAMBER, at(0, 0)));
        source.inputId = "source";
        PlanNode target = plan.addNode(new PlanNode(PlanNode.Kind.OKS, at(100, 0)));
        target.inputId = "consumer";
        target.oksFlow = 1.0;
        plan.addEdge(source, target, List.of(source.utm, target.utm));
        VariantResult result = new NetworkAssembler(new ObstacleIndex(input)).assemble(plan, "v1", "shared");
        assertTrue(result.isValid());
        assertEquals("v1_net_1_3", result.segments.get(0).id);
        assertEquals("source", result.segments.get(0).startNodeId);
        assertEquals("consumer", result.segments.get(0).endNodeId);
        assertEquals("v1_summary_2", result.summaryId);
    }

    @Test
    void generatedFeaturesNeverReuseAnInputIdAndNodeReferencesRemainUnambiguous() throws Exception {
        Fixture fixture = fixture(true);
        VariantResult result = fixture.assemble();
        assertTrue(result.isValid(), () -> result.diagnostics.toString());
        JsonNode features = write(result).get("features");
        Set<String> generated = new HashSet<>();
        for (JsonNode feature : features) {
            String id = feature.get("properties").get("id").textValue();
            assertFalse(fixture.inputIds.contains(id), "Generated ID collides with an input object: " + id);
            assertTrue(generated.add(id), "Generated ID is repeated: " + id);
        }
        assertTrue(result.chambers.stream().noneMatch(c -> c.id.equals("v1_chamber_1_2")),
                "The first suffix is an input ID as well");
        Set<Object> nodeIds = new HashSet<>(fixture.inputIds);
        result.chambers.forEach(c -> nodeIds.add(c.id));
        result.techNodes.forEach(t -> nodeIds.add(t.id));
        for (VariantResult.Segment segment : result.segments) {
            assertTrue(nodeIds.contains(segment.startNodeId));
            assertTrue(nodeIds.contains(segment.endNodeId));
        }
        assertEquals(1, result.segments.stream().filter(s -> fixture.oksId.equals(s.endNodeId)).count());
        assertEquals(1, result.segments.stream().filter(s -> fixture.chamberId.equals(s.startNodeId)).count());
    }

    @Test
    void reservingIdsChangesNeitherGeometryFlowsDiametersNorCost() {
        VariantResult ordinary = fixture(false).assemble();
        VariantResult collisions = fixture(true).assemble();
        assertEquals(ordinary.calculatedCost, collisions.calculatedCost);
        assertEquals(ordinary.newNetworkLength, collisions.newNetworkLength);
        assertEquals(ordinary.segments.size(), collisions.segments.size());
        for (int i = 0; i < ordinary.segments.size(); i++) {
            VariantResult.Segment a = ordinary.segments.get(i), b = collisions.segments.get(i);
            assertEquals(a.utmCoords, b.utmCoords);
            assertEquals(a.flowTph, b.flowTph);
            assertEquals(a.diameter, b.diameter);
            assertEquals(a.cost, b.cost);
        }
    }

    @Test
    void ordinaryIdsKeepTheExistingNamesAndReassemblyIsDeterministic() throws Exception {
        Fixture fixture = fixture(false);
        VariantResult first = fixture.assemble();
        assertEquals("v1_chamber_1", first.chambers.get(0).id);
        assertEquals(List.of("v1_net_1", "v1_net_2", "v1_net_3", "v1_net_4", "v1_net_5"),
                first.segments.stream().map(s -> s.id).collect(Collectors.toList()));
        assertEquals(Set.of("v1_node_p1", "v1_node_1", "v1_node_2"),
                first.techNodes.stream().map(t -> t.id).collect(Collectors.toSet()));
        JsonNode initial = write(first);
        assertEquals(initial, write(fixture.assemble()));
        assertEquals("v1_summary", initial.get("features").get(initial.get("features").size() - 1)
                .get("properties").get("id").textValue());
    }

    private static Fixture fixture(boolean collisions) {
        Fixture f = new Fixture();
        InputModel.SourcePoint source = new InputModel.SourcePoint();
        source.id = collisions ? "v1_net_1" : "source";
        f.input.sources.add(source);
        InputModel.SourcePoint suffix = new InputModel.SourcePoint();
        suffix.id = collisions ? "v1_chamber_1_2" : "spare_source";
        f.input.sources.add(suffix);
        InputModel.ExistingPipe pipe = new InputModel.ExistingPipe();
        pipe.id = collisions ? "v1_chamber_1" : "pipe";
        pipe.diameter = 100;
        pipe.utm = GeoUtil.GF.createLineString(new Coordinate[]{at(0, -100), at(100, -100)});
        f.input.pipes.add(pipe);
        InputModel.ExistingChamber chamber = new InputModel.ExistingChamber();
        chamber.id = f.chamberId = collisions ? "v1_node_p1" : "existing_chamber";
        chamber.utm = GeoUtil.GF.createPoint(at(0, 0));
        f.input.chambers.add(chamber);
        InputModel.OksPoint oks = new InputModel.OksPoint();
        oks.id = f.oksId = collisions ? "v1_node_1" : "consumer";
        oks.flowTph = 1;
        oks.utm = GeoUtil.GF.createPoint(at(100, 0));
        f.input.oksPoints.add(oks);
        InputModel.Restriction road = new InputModel.Restriction();
        road.id = collisions ? "v1_summary" : "road";
        road.restrictionType = "road";
        road.utm = GeoUtil.GF.createPolygon(new Coordinate[]{at(45, -20), at(55, -20), at(55, 20), at(45, 20), at(45, -20)});
        f.input.restrictions.add(road);
        for (Object id : List.of(source.id, suffix.id, pipe.id, chamber.id, oks.id, road.id)) f.inputIds.add((String) id);
        PlanNode start = f.plan.addNode(new PlanNode(PlanNode.Kind.EXISTING_CHAMBER, at(0, 0)));
        start.inputId = chamber.id;
        PlanNode technical = f.plan.addNode(new PlanNode(PlanNode.Kind.TECHNICAL_NODE, at(20, 0)));
        PlanNode branch = f.plan.addNode(new PlanNode(PlanNode.Kind.NEW_CHAMBER, at(30, 0)));
        PlanNode finish = f.plan.addNode(new PlanNode(PlanNode.Kind.OKS, at(100, 0)));
        finish.inputId = oks.id;
        finish.oksFlow = 1.0;
        f.plan.addEdge(start, technical, List.of(start.utm, technical.utm));
        f.plan.addEdge(technical, branch, List.of(technical.utm, branch.utm));
        f.plan.addEdge(branch, finish, List.of(branch.utm, finish.utm));
        return f;
    }

    private static JsonNode write(VariantResult result) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new GeoJsonWriter(new Projection()).write(List.of(result), out);
        return new ObjectMapper().readTree(out.toByteArray());
    }

    private static Coordinate at(double x, double y) { return new Coordinate(400000 + x, 6170000 + y); }

    private static final class Fixture {
        final InputModel input = new InputModel();
        final NetworkPlan plan = new NetworkPlan();
        final Set<String> inputIds = new HashSet<>();
        String chamberId, oksId;
        VariantResult assemble() { return new NetworkAssembler(new ObstacleIndex(input)).assemble(plan, "v1", "shared"); }
    }
}
