package ru.lct.heatnet.network;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.routing.ObstacleIndex;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkGeometryValidatorTest {

    private PlanNode node(NetworkPlan plan, PlanNode.Kind kind, double x, double y) {
        PlanNode n = new PlanNode(kind, new Coordinate(x, y));
        n.inputId = "node-" + plan.nodes.size();
        if (kind == PlanNode.Kind.OKS) {
            n.oksFlow = 1.0;
        }
        return plan.addNode(n);
    }

    private void edge(NetworkPlan plan, PlanNode a, PlanNode b, Coordinate... middle) {
        java.util.ArrayList<Coordinate> coords = new java.util.ArrayList<>();
        coords.add(a.utm.copy());
        coords.addAll(List.of(middle));
        coords.add(b.utm.copy());
        plan.addEdge(a, b, coords);
    }

    private VariantResult assemble(NetworkPlan plan) {
        return new NetworkAssembler(new ObstacleIndex(new InputModel()))
                .assemble(plan, "v1", "test");
    }

    @Test
    void finalAssemblyRejectsCrossingWithoutSharedNode() {
        NetworkPlan plan = new NetworkPlan();
        edge(plan, node(plan, PlanNode.Kind.EXISTING_CHAMBER, -10, 0),
                node(plan, PlanNode.Kind.OKS, 10, 0));
        edge(plan, node(plan, PlanNode.Kind.EXISTING_CHAMBER, 0, -10),
                node(plan, PlanNode.Kind.OKS, 0, 10));
        assertTrue(assemble(plan).clearanceViolated);
    }

    @Test
    void finalAssemblyRejectsSelfCrossingEdge() {
        NetworkPlan plan = new NetworkPlan();
        edge(plan, node(plan, PlanNode.Kind.EXISTING_CHAMBER, 0, 0),
                node(plan, PlanNode.Kind.OKS, 10, 0),
                new Coordinate(10, 10), new Coordinate(0, 10));
        assertTrue(assemble(plan).clearanceViolated);
    }

    @Test
    void finalAssemblyRejectsClosedSingleEdge() {
        NetworkPlan plan = new NetworkPlan();
        PlanNode root = node(plan, PlanNode.Kind.EXISTING_CHAMBER, 0, 0);
        edge(plan, root, root, new Coordinate(10, 0), new Coordinate(10, 10), new Coordinate(0, 10));
        assertTrue(assemble(plan).clearanceViolated,
                "Замкнутое одиночное ребро не имеет однозначных дуговых координат концов");
    }

    @Test
    void finalAssemblyRejectsParallelLinesWithinSeparation() {
        NetworkPlan plan = new NetworkPlan();
        edge(plan, node(plan, PlanNode.Kind.EXISTING_CHAMBER, 0, 0),
                node(plan, PlanNode.Kind.OKS, 20, 0));
        edge(plan, node(plan, PlanNode.Kind.EXISTING_CHAMBER, 0, 1.409),
                node(plan, PlanNode.Kind.OKS, 20, 1.409));
        assertTrue(assemble(plan).clearanceViolated);
    }

    @Test
    void finalAssemblyRejectsOverlapEvenWithSharedNode() {
        NetworkPlan plan = new NetworkPlan();
        PlanNode root = node(plan, PlanNode.Kind.EXISTING_CHAMBER, 0, 0);
        edge(plan, root, node(plan, PlanNode.Kind.OKS, 10, 0));
        edge(plan, root, node(plan, PlanNode.Kind.OKS, 20, 0));
        assertTrue(assemble(plan).clearanceViolated);
    }

    @Test
    void shortChamferWithinContinuousJunctionApproachIsAllowed() {
        NetworkPlan plan = new NetworkPlan();
        PlanNode root = node(plan, PlanNode.Kind.EXISTING_CHAMBER, 0, 0);
        edge(plan, root, node(plan, PlanNode.Kind.OKS, -10, -2), new Coordinate(-1.409, -2));
        edge(plan, root, node(plan, PlanNode.Kind.OKS, 0, -20));
        assertFalse(assemble(plan).clearanceViolated,
                "Непрерывный подход у общей камеры не зависит от фаски внутри радиуса 6 м");
    }

    @Test
    void returningIntoSeparationZoneAfterLeavingItIsRejectedEvenWithinSixMeters() {
        NetworkPlan plan = new NetworkPlan();
        PlanNode root = node(plan, PlanNode.Kind.EXISTING_CHAMBER, 0, 0);
        edge(plan, root, node(plan, PlanNode.Kind.OKS, -3, -5),
                new Coordinate(-2, -1), new Coordinate(-2, -3), new Coordinate(-1, -3));
        edge(plan, root, node(plan, PlanNode.Kind.OKS, 0, -20));
        assertTrue(assemble(plan).clearanceViolated);
    }

    @Test
    void sharedNodeDoesNotExemptParallelApproachBeyondSixMeters() {
        NetworkPlan plan = new NetworkPlan();
        PlanNode root = node(plan, PlanNode.Kind.EXISTING_CHAMBER, 0, 0);
        edge(plan, root, node(plan, PlanNode.Kind.OKS, -3, -12), new Coordinate(-1, -10));
        edge(plan, root, node(plan, PlanNode.Kind.OKS, 0, -20));
        assertTrue(assemble(plan).clearanceViolated);
    }

    @Test
    void finalAssemblyRejectsEndpointThatDoesNotMatchItsNode() {
        NetworkPlan plan = new NetworkPlan();
        PlanNode a = node(plan, PlanNode.Kind.EXISTING_CHAMBER, 0, 0);
        PlanNode b = node(plan, PlanNode.Kind.OKS, 10, 0);
        plan.addEdge(a, b, List.of(new Coordinate(0.02, 0), b.utm));
        assertTrue(assemble(plan).clearanceViolated);
    }

    @Test
    void finalAssemblyAcceptsSharedJunctionWithReversedEdges() {
        NetworkPlan plan = new NetworkPlan();
        PlanNode root = node(plan, PlanNode.Kind.EXISTING_CHAMBER, -20, 0);
        PlanNode junction = node(plan, PlanNode.Kind.NEW_CHAMBER, 0, 0);
        edge(plan, root, junction);
        edge(plan, node(plan, PlanNode.Kind.OKS, 20, 0), junction);
        edge(plan, junction, node(plan, PlanNode.Kind.OKS, 0, 20));
        assertFalse(assemble(plan).clearanceViolated);
    }

    @Test
    void subdividingStraightApproachDoesNotChangeValidity() {
        NetworkPlan plan = new NetworkPlan();
        PlanNode root = node(plan, PlanNode.Kind.EXISTING_CHAMBER, -20, 0);
        PlanNode junction = node(plan, PlanNode.Kind.NEW_CHAMBER, 0, 0);
        edge(plan, root, junction, new Coordinate(-0.5, 0));
        edge(plan, junction, node(plan, PlanNode.Kind.OKS, 20, 0));
        assertFalse(assemble(plan).clearanceViolated,
                "Лишняя коллинеарная вершина не меняет физическую трассу или допустимость примыкания");
        assertTrue(plan.edges.get(0).coords.size() == 3, "Исходная геометрия остаётся неизменной");
    }

    @Test
    void endpointRoundoffAtSharedNodeIsAcceptedWithoutChangingPlan() {
        NetworkPlan plan = new NetworkPlan();
        PlanNode root = node(plan, PlanNode.Kind.EXISTING_CHAMBER, -20, 0);
        PlanNode junction = node(plan, PlanNode.Kind.NEW_CHAMBER, 0, 0);
        PlanNode end = node(plan, PlanNode.Kind.OKS, 0, 20);
        edge(plan, root, junction);
        Coordinate rounded = new Coordinate(0, 1e-10);
        PlanEdge branch = plan.addEdge(junction, end, List.of(rounded, end.utm));
        assertFalse(assemble(plan).clearanceViolated);
        assertTrue(branch.coords.get(0) == rounded, "Проверка не должна изменять геометрию плана");
    }

    @Test
    void finalAssemblyAcceptsSeparatedParallelLines() {
        NetworkPlan plan = new NetworkPlan();
        edge(plan, node(plan, PlanNode.Kind.EXISTING_CHAMBER, 0, 0),
                node(plan, PlanNode.Kind.OKS, 20, 0));
        edge(plan, node(plan, PlanNode.Kind.EXISTING_CHAMBER, 0, 1.6),
                node(plan, PlanNode.Kind.OKS, 20, 1.6));
        assertFalse(assemble(plan).clearanceViolated);
    }

    @Test
    void bothSharedEndpointsAreRecognized() {
        NetworkPlan plan = new NetworkPlan();
        PlanNode a = node(plan, PlanNode.Kind.EXISTING_CHAMBER, 0, 0);
        PlanNode b = node(plan, PlanNode.Kind.OKS, 20, 0);
        edge(plan, a, b);
        edge(plan, b, a, new Coordinate(20, 10), new Coordinate(0, 10));
        assertFalse(assemble(plan).clearanceViolated,
                "Геометрическая проверка учитывает оба общих конца; проверка циклов — отдельная задача");
    }

    @Test
    void coincidentCoordinatesDoNotMakeDifferentPlanNodesAConnection() {
        NetworkPlan plan = new NetworkPlan();
        PlanNode root = node(plan, PlanNode.Kind.EXISTING_CHAMBER, -20, 0);
        PlanNode end = node(plan, PlanNode.Kind.OKS, 0, 0);
        edge(plan, root, end);
        PlanNode otherRoot = new PlanNode(PlanNode.Kind.EXISTING_CHAMBER, end.utm.copy());
        otherRoot.inputId = "different-root";
        plan.nodes.add(otherRoot);
        edge(plan, otherRoot, node(plan, PlanNode.Kind.OKS, 0, 20));
        assertTrue(assemble(plan).clearanceViolated);
    }
}
