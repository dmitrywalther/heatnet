package ru.lct.heatnet.service;

import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.network.NetworkPlan;
import ru.lct.heatnet.network.PlanEdge;
import ru.lct.heatnet.network.PlanNode;
import ru.lct.heatnet.network.VariantResult;
import ru.lct.heatnet.routing.GeoUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Топологический отбор зависимой ветви; окончательную допустимость проверяет сборщик. */
final class JointBranchRepair {
    private static final double MAX_TURN_DEG = 90.0 + 1e-6;
    private static final double POINT_EPS_M = 1e-6;
    private static final int MAX_PARTNERS = 2;

    private JointBranchRepair() { }

    static List<InputModel.OksPoint> partners(InputModel input, NetworkPlan disconnected,
                                             NetworkPlan candidate, Object primaryId) {
        List<Conflict> removedJunctions = conflicts(disconnected);
        if (removedJunctions.isEmpty()) return Collections.emptyList();
        Map<PlanNode, List<PlanEdge>> adjacent = adjacency(candidate);
        Set<Object> ids = new LinkedHashSet<>();
        for (Conflict conflict : conflicts(candidate)) {
            boolean fromRemoval = removedJunctions.stream()
                    .anyMatch(old -> old.at.distance(conflict.at) <= POINT_EPS_M);
            if (!fromRemoval) continue;
            for (PlanEdge edge : conflict.edges) {
                addTerminal(ids, adjacent, edge.a, edge, primaryId);
                addTerminal(ids, adjacent, edge.b, edge, primaryId);
            }
        }
        List<InputModel.OksPoint> result = new ArrayList<>();
        // Порядок входа устойчив; расстояние по карте не определяет зависимость.
        for (InputModel.OksPoint oks : input.oksPoints) {
            if (ids.contains(oks.id)) {
                result.add(oks);
                if (result.size() == MAX_PARTNERS) break;
            }
        }
        return result;
    }

    static boolean hasInvalidTurns(NetworkPlan plan) {
        return !conflicts(plan).isEmpty();
    }

    /** removeBranch не обновляет unconnected: проверяем реальные пути и исходные P. */
    static boolean preservesConnections(InputModel input, VariantResult current, NetworkPlan candidate) {
        Map<PlanNode, List<PlanEdge>> adjacent = adjacency(candidate);
        Set<PlanNode> reached = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<PlanNode> pending = new ArrayDeque<>();
        Map<Object, PlanNode> consumers = new HashMap<>();
        for (PlanNode node : candidate.nodes) {
            if (node.kind == PlanNode.Kind.NEW_CHAMBER && adjacent.get(node).isEmpty()) return false;
            if (source(node) && reached.add(node)) pending.add(node);
            if (node.kind == PlanNode.Kind.OKS && consumers.put(node.inputId, node) != null) return false;
        }
        while (!pending.isEmpty()) {
            PlanNode node = pending.remove();
            for (PlanEdge edge : adjacent.get(node)) {
                PlanNode next = edge.other(node);
                if (reached.add(next)) pending.add(next);
            }
        }
        for (InputModel.OksPoint oks : input.oksPoints) {
            if (current.unconnectedOksIds.contains(oks.id)) continue;
            PlanNode node = consumers.get(oks.id);
            if (node == null || !reached.contains(node) || adjacent.get(node).size() != 1
                    || node.utm.distance(oks.utm.getCoordinate()) > POINT_EPS_M) return false;
        }
        return true;
    }

    private static void addTerminal(Set<Object> ids, Map<PlanNode, List<PlanEdge>> adjacent,
                                    PlanNode node, PlanEdge previous, Object primaryId) {
        Set<PlanNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (visited.add(node)) {
            if (source(node)) return;
            List<PlanEdge> edges = adjacent.get(node);
            if (node.kind == PlanNode.Kind.OKS) {
                if (edges.size() == 1 && !primaryId.equals(node.inputId)) ids.add(node.inputId);
                return;
            }
            if (!transit(node) || edges.size() != 2) return;
            PlanEdge next = edges.get(0) == previous ? edges.get(1) : edges.get(0);
            node = next.other(node);
            previous = next;
        }
    }

    private static List<Conflict> conflicts(NetworkPlan plan) {
        List<Conflict> result = new ArrayList<>();
        Map<PlanNode, List<PlanEdge>> adjacent = adjacency(plan);
        for (PlanEdge edge : plan.edges) {
            if (edge.coords.size() < 2) continue; // полный валидатор отклонит повреждённое ребро
            Coordinate previous = null, current = edge.a.utm;
            for (int i = 1; i < edge.coords.size(); i++) {
                Coordinate next = i == edge.coords.size() - 1 ? edge.b.utm : edge.coords.get(i);
                if (current.equals2D(next)) continue;
                if (previous != null && GeoUtil.turnAngleDeg(previous, current, next) > MAX_TURN_DEG) {
                    result.add(new Conflict(current, List.of(edge)));
                }
                previous = current;
                current = next;
            }
        }
        for (Map.Entry<PlanNode, List<PlanEdge>> entry : adjacent.entrySet()) {
            PlanNode node = entry.getKey();
            List<PlanEdge> edges = entry.getValue();
            if (!transit(node) || edges.size() != 2) continue;
            Coordinate a = direction(edges.get(0), node), b = direction(edges.get(1), node);
            if (a != null && b != null && GeoUtil.turnAngleDeg(a, node.utm, b) > MAX_TURN_DEG) {
                result.add(new Conflict(node.utm, edges));
            }
        }
        return result;
    }

    private static Coordinate direction(PlanEdge edge, PlanNode node) {
        List<Coordinate> coords = edge.coordsFrom(node);
        for (int i = 1; i < coords.size(); i++) {
            Coordinate next = i == coords.size() - 1 ? edge.other(node).utm : coords.get(i);
            if (!node.utm.equals2D(next)) return next;
        }
        return null;
    }

    private static Map<PlanNode, List<PlanEdge>> adjacency(NetworkPlan plan) {
        Map<PlanNode, List<PlanEdge>> result = new IdentityHashMap<>();
        for (PlanNode node : plan.nodes) result.put(node, new ArrayList<>(3));
        for (PlanEdge edge : plan.edges) {
            result.get(edge.a).add(edge);
            result.get(edge.b).add(edge);
        }
        return result;
    }

    private static boolean source(PlanNode node) {
        return node.kind == PlanNode.Kind.EXISTING_CHAMBER
                || node.kind == PlanNode.Kind.NEW_CHAMBER && node.splitPipeDu != null;
    }

    private static boolean transit(PlanNode node) {
        return node.kind == PlanNode.Kind.TECHNICAL_NODE
                || node.kind == PlanNode.Kind.NEW_CHAMBER && node.splitPipeDu == null;
    }

    private static final class Conflict {
        final Coordinate at;
        final List<PlanEdge> edges;
        Conflict(Coordinate at, List<PlanEdge> edges) { this.at = at; this.edges = edges; }
    }
}
