package ru.lct.heatnet.network;

import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.model.InputModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Планируемая новая сеть одного варианта: узлы, рёбра и операции
 * инкрементального добавления подключений.
 */
public class NetworkPlan {

    private static final double SNAP_EPS = 1e-3;

    public final List<PlanNode> nodes = new ArrayList<>();
    public final List<PlanEdge> edges = new ArrayList<>();
    /** Врезки в существующие камеры: узел -> число новых примыканий. */
    public final Map<PlanNode, Integer> tieInsToExisting = new HashMap<>();
    /** Неподключённые ОКС. */
    public final List<InputModel.OksPoint> unconnected = new ArrayList<>();

    private final Map<String, PlanNode> nodeIndex = new HashMap<>();

    private String key(Coordinate c) {
        return Math.round(c.x / SNAP_EPS) + ":" + Math.round(c.y / SNAP_EPS);
    }

    public PlanNode findNode(Coordinate c) {
        return nodeIndex.get(key(c));
    }

    public PlanNode addNode(PlanNode node) {
        PlanNode existing = nodeIndex.get(key(node.utm));
        if (existing != null) {
            return existing;
        }
        nodes.add(node);
        nodeIndex.put(key(node.utm), node);
        return node;
    }

    public PlanEdge addEdge(PlanNode a, PlanNode b, List<Coordinate> coords) {
        PlanEdge edge = new PlanEdge(a, b, coords);
        edges.add(edge);
        return edge;
    }

    public int degree(PlanNode node) {
        int d = 0;
        for (PlanEdge e : edges) {
            if (e.a == node || e.b == node) {
                d++;
            }
        }
        return d;
    }

    public List<PlanEdge> incident(PlanNode node) {
        List<PlanEdge> result = new ArrayList<>();
        for (PlanEdge e : edges) {
            if (e.a == node || e.b == node) {
                result.add(e);
            }
        }
        return result;
    }

    /**
     * Разрезает ребро в точке на полилинии (точка должна лежать на ней),
     * вставляя новый узел; возвращает узел.
     */
    public PlanNode splitEdge(PlanEdge edge, Coordinate at, PlanNode.Kind kind) {
        PlanNode existing = findNode(at);
        if (existing != null) {
            return existing;
        }
        List<Coordinate> coords = edge.coords;
        int bestSeg = -1;
        double bestDist = Double.POSITIVE_INFINITY;
        Coordinate bestPoint = null;
        for (int i = 0; i + 1 < coords.size(); i++) {
            Coordinate p = project(coords.get(i), coords.get(i + 1), at);
            double d = p.distance(at);
            if (d < bestDist) {
                bestDist = d;
                bestSeg = i;
                bestPoint = p;
            }
        }
        if (bestSeg < 0 || bestDist > 0.5) {
            throw new IllegalStateException("Точка врезки не лежит на разрезаемом ребре (d="
                    + bestDist + ")");
        }
        PlanNode node = addNode(new PlanNode(kind, bestPoint));

        List<Coordinate> first = new ArrayList<>(coords.subList(0, bestSeg + 1));
        if (first.isEmpty() || first.get(first.size() - 1).distance(bestPoint) > SNAP_EPS) {
            first.add(bestPoint);
        }
        List<Coordinate> second = new ArrayList<>();
        second.add(bestPoint);
        for (int i = bestSeg + 1; i < coords.size(); i++) {
            if (second.size() == 1 && coords.get(i).distance(bestPoint) <= SNAP_EPS) {
                continue;
            }
            second.add(coords.get(i));
        }

        PlanNode oldB = edge.b;
        edge.b = node;
        edge.coords = first;
        addEdge(node, oldB, second);
        return node;
    }

    /** Глубокая копия плана для пробных перестроений (1-opt улучшение). */
    public NetworkPlan copy() {
        NetworkPlan clone = new NetworkPlan();
        Map<PlanNode, PlanNode> nodeMap = new HashMap<>();
        for (PlanNode n : nodes) {
            PlanNode c = new PlanNode(n.kind, n.utm);
            c.inputId = n.inputId;
            c.wgs = n.wgs;
            c.exemptSpecialIds = n.exemptSpecialIds;
            c.splitPipeDu = n.splitPipeDu;
            c.oksFlow = n.oksFlow;
            c.ownRestrictionId = n.ownRestrictionId;
            nodeMap.put(n, clone.addNode(c));
        }
        for (PlanEdge e : edges) {
            clone.addEdge(nodeMap.get(e.a), nodeMap.get(e.b),
                    new ArrayList<>(e.coords));
        }
        for (Map.Entry<PlanNode, Integer> entry : tieInsToExisting.entrySet()) {
            clone.tieInsToExisting.put(nodeMap.get(entry.getKey()), entry.getValue());
        }
        clone.unconnected.addAll(unconnected);
        return clone;
    }

    private void removeNode(PlanNode node) {
        nodes.remove(node);
        nodeIndex.remove(key(node.utm));
    }

    /**
     * Удаляет ветвь, питающую только указанный ОКС: рёбра от точки подключения
     * назад до первого узла, нужного другим потребителям. Освобождает врезки
     * и склеивает ставшие транзитными камеры. Возвращает false, если ОКС
     * не подключён.
     */
    public boolean removeBranch(Object oksId) {
        PlanNode node = null;
        for (PlanNode n : nodes) {
            if (n.kind == PlanNode.Kind.OKS && oksId.equals(n.inputId)) {
                node = n;
                break;
            }
        }
        if (node == null || degree(node) != 1) {
            return false;
        }
        while (true) {
            List<PlanEdge> incident = incident(node);
            if (incident.size() != 1) {
                break;
            }
            PlanEdge e = incident.get(0);
            edges.remove(e);
            PlanNode far = e.other(node);
            int farDegree = degree(far);
            if (far.kind == PlanNode.Kind.EXISTING_CHAMBER) {
                Integer count = tieInsToExisting.get(far);
                if (count != null) {
                    if (count <= 1) {
                        tieInsToExisting.remove(far);
                    } else {
                        tieInsToExisting.put(far, count - 1);
                    }
                }
                if (farDegree == 0) {
                    removeNode(far);
                }
                break;
            }
            if (far.kind == PlanNode.Kind.NEW_CHAMBER && far.splitPipeDu != null) {
                // корневая врезка в трубу: если больше никого не питает — убрать
                if (farDegree == 0) {
                    removeNode(far);
                }
                break;
            }
            if (farDegree == 0) {
                removeNode(far);
                break;
            }
            if (farDegree == 1) {
                node = far; // транзит только нашей ветви — удаляем дальше
                continue;
            }
            if (farDegree == 2 && far.kind == PlanNode.Kind.NEW_CHAMBER) {
                mergePassThrough(far); // камера осталась без разветвления
            }
            break;
        }
        return true;
    }

    /** Склеивает два ребра транзитной камеры (разветвление исчезло). */
    private void mergePassThrough(PlanNode node) {
        List<PlanEdge> incident = incident(node);
        if (incident.size() != 2) {
            return;
        }
        PlanEdge e1 = incident.get(0);
        PlanEdge e2 = incident.get(1);
        PlanNode a = e1.other(node);
        PlanNode b = e2.other(node);
        List<Coordinate> coords = new ArrayList<>(e1.coordsFrom(a));
        List<Coordinate> tail = e2.coordsFrom(node);
        for (int i = 1; i < tail.size(); i++) {
            coords.add(tail.get(i));
        }
        edges.remove(e1);
        edges.remove(e2);
        removeNode(node);
        addEdge(a, b, coords);
    }

    private static Coordinate project(Coordinate a, Coordinate b, Coordinate p) {
        double dx = b.x - a.x, dy = b.y - a.y;
        double len2 = dx * dx + dy * dy;
        if (len2 == 0) {
            return a.copy();
        }
        double t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2;
        t = Math.max(0, Math.min(1, t));
        return new Coordinate(a.x + t * dx, a.y + t * dy);
    }
}
