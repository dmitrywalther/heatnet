package ru.lct.heatnet.service;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.network.NetworkPlan;
import ru.lct.heatnet.network.PlanEdge;
import ru.lct.heatnet.network.PlanNode;
import ru.lct.heatnet.refdata.CostModel;
import ru.lct.heatnet.routing.GeoUtil;
import ru.lct.heatnet.routing.ObstacleIndex;
import ru.lct.heatnet.routing.TieCandidate;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class VariantPlannerChamberCapacityTest {
    @Test
    void terminalSourcePipeLeavesThreeNewPortsAvailable() throws Exception {
        InputModel input = new InputModel();
        sourcePipe(input, "west", -100, 0, 0, 0);
        NetworkPlan plan = new NetworkPlan();
        VariantPlanner planner = new VariantPlanner(input, new ObstacleIndex(input));
        PlanNode chamber = resolve(planner, plan, new TieCandidate(TieCandidate.Kind.PIPE_POINT,
                new Coordinate(0, 0), "west", 5_000_000, Set.of("west")));
        assertNotNull(chamber);
        addPlannedBranch(plan, chamber, 0, 20);
        addPlannedBranch(plan, chamber, 0, -20);
        TieCandidate reuse = new TieCandidate(TieCandidate.Kind.NEW_NETWORK_NODE,
                chamber.utm, chamber, 0, chamber.exemptSpecialIds);

        assertSame(chamber, resolve(planner, plan, reuse),
                "one existing direction plus three new branches occupies exactly four ports");
        addPlannedBranch(plan, chamber, 20, 0);
        List<Object> before = state(plan);
        assertNull(resolve(planner, plan, reuse), "a fourth new branch would occupy a fifth port");
        assertEquals(before, state(plan));
    }

    @Test
    void sourceTeeLeavesOnlyOneNewPortAvailable() throws Exception {
        InputModel input = new InputModel();
        sourcePipe(input, "through", -100, 0, 100, 0);
        sourcePipe(input, "north", 0, 0, 0, 100);
        NetworkPlan plan = new NetworkPlan();
        VariantPlanner planner = new VariantPlanner(input, new ObstacleIndex(input));
        PlanNode chamber = resolve(planner, plan, new TieCandidate(TieCandidate.Kind.PIPE_POINT,
                new Coordinate(0, 0), "through", 5_000_000, Set.of("through", "north")));
        assertNotNull(chamber, "three existing directions still leave one legal new connection");
        addPlannedBranch(plan, chamber, 0, -20);
        List<Object> before = state(plan);

        assertNull(resolve(planner, plan, new TieCandidate(TieCandidate.Kind.NEW_NETWORK_NODE,
                chamber.utm, chamber, 0, chamber.exemptSpecialIds)),
                "two directions of the through pipe plus the source endpoint already occupy three ports");
        assertEquals(before, state(plan), "an unavailable port must not mutate the plan");
    }

    @Test
    void crossingOfTwoSourcePipesCannotCreateAFifthPort() throws Exception {
        InputModel input = new InputModel();
        sourcePipe(input, "horizontal", -100, 0, 100, 0);
        sourcePipe(input, "vertical", 0, -100, 0, 100);
        NetworkPlan plan = new NetworkPlan();
        VariantPlanner planner = new VariantPlanner(input, new ObstacleIndex(input));
        List<Object> before = state(plan);

        assertNull(resolve(planner, plan, new TieCandidate(TieCandidate.Kind.PIPE_POINT,
                new Coordinate(0, 0), "horizontal", 5_000_000, Set.of("horizontal", "vertical"))),
                "all four incident source directions must be counted before creating a new chamber");
        assertEquals(before, state(plan), "rejection must not leave an orphan chamber behind");
    }

    @Test
    void repeatedConnectionsUseTheThirdAndFourthPortsButNotAFifthAtAnExistingPipeSplit() {
        InputModel input = new InputModel();
        InputModel.ExistingPipe trunk = new InputModel.ExistingPipe();
        trunk.id = "existing-trunk";
        trunk.diameter = 400;
        trunk.utm = GeoUtil.segment(new Coordinate(-100, 0), new Coordinate(100, 0));
        input.pipes.add(trunk);
        InputModel.OksPoint north = oks(input, "north", 0, 20);
        InputModel.OksPoint south = oks(input, "south", 0, -20);
        InputModel.OksPoint northeast = oks(input, "northeast", 20, 20);
        NetworkPlan plan = new NetworkPlan();
        VariantPlanner planner = new VariantPlanner(input, new ObstacleIndex(input));

        // Without an enclosing OKS polygon there are no wall normals: this exercises
        // ordinary routing/candidate generation, not the separate direct-normal guard.
        assertTrue(input.restrictions.isEmpty());
        assertTrue(planner.reconnect(plan, north), "the first new branch must be connected");
        PlanNode split = branch(plan, north).other(plan.findNode(north.utm.getCoordinate()));
        assertEquals(PlanNode.Kind.NEW_CHAMBER, split.kind);
        assertEquals(Integer.valueOf(400), split.splitPipeDu);
        assertEquals(0, split.utm.distance(new Coordinate(0, 0)), 1e-6);
        assertEquals(0, trunk.utm.distance(GeoUtil.GF.createPoint(split.utm)), 1e-9);
        assertTrue(split.utm.distance(trunk.utm.getCoordinateN(0)) > 1);
        assertTrue(split.utm.distance(trunk.utm.getCoordinateN(1)) > 1);
        // The real trunk continues west and east through this interior point.
        // Its two existing directions are not represented by PlanEdge objects.
        assertEquals(1, plan.degree(split), "two existing + one new = three occupied ports");
        assertTrue(split.exemptSpecialIds.contains(trunk.id));

        assertTrue(planner.reconnect(plan, south), "the fourth physical port is still available");
        assertSame(split, branch(plan, south).other(plan.findNode(south.utm.getCoordinate())),
                "the existing split chamber must remain reusable for its fourth connection");
        assertEquals(2, plan.degree(split), "two existing + two new = four occupied ports");

        assertTrue(planner.reconnect(plan, northeast),
                "another legal tie exists on the trunk; a full chamber must not disconnect this OKS");
        assertEquals(2, plan.degree(split),
                "a fifth physical connection must not be admitted through any candidate kind");
        assertNotSame(split, branch(plan, northeast).other(plan.findNode(northeast.utm.getCoordinate())));
        for (InputModel.OksPoint target : input.oksPoints) {
            assertEquals(1, plan.degree(plan.findNode(target.utm.getCoordinate())));
        }
        for (PlanNode node : plan.nodes) {
            if (node.kind == PlanNode.Kind.NEW_CHAMBER && node.splitPipeDu != null) {
                // Every source-tie chamber in this fixture lies on the same through-trunk.
                assertTrue(plan.degree(node) <= 2,
                        () -> "Through-trunk chamber has more than two new branches at " + node.utm);
            }
        }
    }

    @Test
    void ordinaryNewJunctionStillAcceptsItsThirdAndFourthPlannedConnections() {
        InputModel input = new InputModel();
        InputModel.ExistingChamber source = new InputModel.ExistingChamber();
        source.id = "source";
        source.utm = GeoUtil.GF.createPoint(new Coordinate(-20, 0));
        input.chambers.add(source);
        InputModel.OksPoint north = oks(input, "north", 0, 20);
        InputModel.OksPoint south = oks(input, "south", 0, -20);
        InputModel.OksPoint east = oks(input, "east", 20, 0);
        NetworkPlan plan = new NetworkPlan();
        PlanNode root = plan.addNode(new PlanNode(PlanNode.Kind.EXISTING_CHAMBER,
                source.utm.getCoordinate()));
        root.inputId = source.id;
        PlanNode junction = plan.addNode(new PlanNode(PlanNode.Kind.NEW_CHAMBER, new Coordinate(0, 0)));
        PlanNode northNode = plan.addNode(new PlanNode(PlanNode.Kind.OKS, north.utm.getCoordinate()));
        northNode.inputId = north.id;
        northNode.oksFlow = north.flowTph;
        plan.addEdge(root, junction, List.of(root.utm, junction.utm));
        plan.addEdge(junction, northNode, List.of(junction.utm, northNode.utm));
        plan.tieInsToExisting.put(root, 1);
        assertNull(junction.splitPipeDu);
        assertEquals(2, plan.degree(junction));
        VariantPlanner planner = new VariantPlanner(input, new ObstacleIndex(input));

        assertTrue(planner.reconnect(plan, south));
        assertSame(junction, branch(plan, south).other(plan.findNode(south.utm.getCoordinate())));
        assertEquals(3, plan.degree(junction), "ordinary new junction has a legal third port");
        assertTrue(planner.reconnect(plan, east));
        assertSame(junction, branch(plan, east).other(plan.findNode(east.utm.getCoordinate())));
        assertEquals(4, plan.degree(junction), "ordinary new junction has a legal fourth port");
        assertNull(junction.splitPipeDu, "ordinary branch reuse must not turn it into an existing-pipe split");
    }

    @Test
    void stalePipeAndEdgeAliasesCannotMutateAnAlreadyFullThroughPipeChamber() throws Exception {
        for (TieCandidate.Kind kind : List.of(TieCandidate.Kind.PIPE_POINT,
                TieCandidate.Kind.NEW_NETWORK_EDGE_POINT)) {
            AliasFixture f = new AliasFixture(false, true);
            Object reference = kind == TieCandidate.Kind.PIPE_POINT ? "west-400" : f.plan.edges.get(0);
            TieCandidate stale = new TieCandidate(kind, new Coordinate(f.node.utm), reference,
                    5_000_000, Set.of("west-400"));
            List<Object> before = state(f.plan);

            assertNull(resolve(f.planner, f.plan, stale),
                    "a stale alias cannot bypass the four occupied physical ports: " + kind);

            assertEquals(before, state(f.plan), "rejection must precede every plan mutation");
        }
    }

    @Test
    void availablePipeAliasesAreRejectedWithoutOverwritingMetadataOrChargingTheWrongKind() throws Exception {
        for (boolean existingChamber : List.of(false, true)) {
            AliasFixture f = new AliasFixture(existingChamber, false);
            List<Object> before = state(f.plan);
            TieCandidate alias = new TieCandidate(TieCandidate.Kind.PIPE_POINT,
                    new Coordinate(f.node.utm), "west-400", 5_000_000, Set.of("west-400"));

            assertNull(resolve(f.planner, f.plan, alias),
                    "occupied positions have a dedicated reuse candidate with the correct fee");
            assertEquals(before, state(f.plan));

            TieCandidate proper = new TieCandidate(existingChamber ? TieCandidate.Kind.EXISTING_CHAMBER
                    : TieCandidate.Kind.NEW_NETWORK_NODE, new Coordinate(f.node.utm),
                    existingChamber ? "existing-camera" : f.node,
                    existingChamber ? CostModel.TIE_IN_TO_EXISTING_CHAMBER_COST : 0,
                    Set.of("west-400", "east-600"));
            assertSame(f.node, resolve(f.planner, f.plan, proper),
                    "the available fourth port remains usable through the correct candidate kind");
            assertEquals(existingChamber ? PlanNode.Kind.EXISTING_CHAMBER : PlanNode.Kind.NEW_CHAMBER, f.node.kind);
            assertEquals(existingChamber ? null : Integer.valueOf(600), f.node.splitPipeDu);
            assertEquals(Set.of("west-400", "east-600", "retained-exemption"), f.node.exemptSpecialIds);
            assertEquals(2, f.plan.nodes.size());
            assertEquals(1, f.plan.edges.size());
            if (existingChamber) {
                assertEquals("existing-camera", f.node.inputId);
                assertEquals(2, f.plan.tieInsToExisting.get(f.node),
                        "one original plus one newly reserved tie-in must be charged exactly once each");
                assertEquals(new Coordinate(37, 55), f.node.wgs);
            } else {
                assertEquals(before, state(f.plan), "reuse of a new chamber adds no new camera or existing-camera fee");
            }
        }
    }

    private static PlanNode resolve(VariantPlanner planner, NetworkPlan plan, TieCandidate candidate) throws Exception {
        Method method = VariantPlanner.class.getDeclaredMethod("resolveTieNode", NetworkPlan.class, TieCandidate.class);
        method.setAccessible(true);
        return (PlanNode) method.invoke(planner, plan, candidate);
    }

    private static void sourcePipe(InputModel input, String id, double x1, double y1, double x2, double y2) {
        InputModel.ExistingPipe pipe = new InputModel.ExistingPipe();
        pipe.id = id;
        pipe.diameter = 400;
        pipe.utm = GeoUtil.segment(new Coordinate(x1, y1), new Coordinate(x2, y2));
        input.pipes.add(pipe);
    }

    private static void addPlannedBranch(NetworkPlan plan, PlanNode chamber, double x, double y) {
        PlanNode leaf = plan.addNode(new PlanNode(PlanNode.Kind.OKS, new Coordinate(x, y)));
        leaf.inputId = "branch-" + x + "-" + y;
        leaf.oksFlow = 1.0;
        plan.addEdge(chamber, leaf, List.of(chamber.utm, leaf.utm));
    }

    private static List<Object> state(NetworkPlan plan) {
        List<Object> values = new ArrayList<>();
        values.add(List.copyOf(plan.nodes));
        values.add(List.copyOf(plan.edges));
        values.add(new HashMap<>(plan.tieInsToExisting));
        values.add(List.copyOf(plan.unconnected));
        for (PlanNode node : plan.nodes) {
            values.add(Arrays.asList(node.kind, coordinate(node.utm), node.inputId, coordinate(node.wgs),
                    Set.copyOf(node.exemptSpecialIds), node.splitPipeDu, node.oksFlow,
                    node.ownRestrictionId, node.outId));
        }
        for (PlanEdge edge : plan.edges) {
            List<Object> coordinates = new ArrayList<>();
            for (Coordinate point : edge.coords) coordinates.add(coordinate(point));
            values.add(Arrays.asList(edge.a, edge.b, coordinates, Set.copyOf(edge.downstreamOks),
                    edge.flowTph, edge.grade));
        }
        return values;
    }

    private static List<Double> coordinate(Coordinate value) {
        return value == null ? null : Arrays.asList(value.x, value.y, value.getZ());
    }

    private static final class AliasFixture {
        final InputModel input = new InputModel();
        final NetworkPlan plan = new NetworkPlan();
        final PlanNode node;
        final VariantPlanner planner;

        AliasFixture(boolean existingChamber, boolean full) {
            for (boolean west : List.of(true, false)) {
                InputModel.ExistingPipe pipe = new InputModel.ExistingPipe();
                pipe.id = west ? "west-400" : "east-600";
                pipe.diameter = west ? 400 : 600;
                pipe.utm = west ? GeoUtil.segment(new Coordinate(-100, 0), new Coordinate(0, 0))
                        : GeoUtil.segment(new Coordinate(0, 0), new Coordinate(100, 0));
                input.pipes.add(pipe);
            }
            node = plan.addNode(new PlanNode(existingChamber ? PlanNode.Kind.EXISTING_CHAMBER
                    : PlanNode.Kind.NEW_CHAMBER, new Coordinate(0, 0)));
            node.exemptSpecialIds = Set.of("west-400", "east-600", "retained-exemption");
            node.splitPipeDu = existingChamber ? null : 600;
            if (existingChamber) {
                InputModel.ExistingChamber chamber = new InputModel.ExistingChamber();
                chamber.id = "existing-camera";
                chamber.utm = GeoUtil.GF.createPoint(node.utm);
                chamber.wgs = new Coordinate(37, 55);
                input.chambers.add(chamber);
                node.inputId = chamber.id;
                node.wgs = chamber.wgs;
            }
            for (int i = 0; i < (full ? 2 : 1); i++) {
                InputModel.OksPoint point = oks(input, "branch-" + i, 0, i == 0 ? 20 : -20);
                PlanNode leaf = plan.addNode(new PlanNode(PlanNode.Kind.OKS, point.utm.getCoordinate()));
                leaf.inputId = point.id;
                leaf.oksFlow = point.flowTph;
                plan.addEdge(node, leaf, List.of(node.utm, leaf.utm));
            }
            if (existingChamber) plan.tieInsToExisting.put(node, full ? 2 : 1);
            planner = new VariantPlanner(input, new ObstacleIndex(input));
        }
    }

    private static InputModel.OksPoint oks(InputModel input, String id, double x, double y) {
        InputModel.OksPoint point = new InputModel.OksPoint();
        point.id = id;
        point.flowTph = 1;
        point.utm = GeoUtil.GF.createPoint(new Coordinate(x, y));
        input.oksPoints.add(point);
        return point;
    }

    private static PlanEdge branch(NetworkPlan plan, InputModel.OksPoint point) {
        PlanNode node = plan.findNode(point.utm.getCoordinate());
        assertNotNull(node, "OKS must exist in the connected plan");
        assertEquals(PlanNode.Kind.OKS, node.kind);
        assertEquals(point.id, node.inputId);
        assertEquals(1, plan.degree(node));
        return plan.incident(node).get(0);
    }
}
