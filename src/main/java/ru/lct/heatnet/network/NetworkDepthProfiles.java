package ru.lct.heatnet.network;

import java.util.*;

/** Согласование профиля через узлы сети при неизменных XY, ДУ и окнах пересечений. */
final class NetworkDepthProfiles {
    private static final double EPS = 1e-9;
    private static final double INF = 1e9;
    private static final int MAX_SIDE_ASSIGNMENTS = 256;

    private NetworkDepthProfiles() { }

    static Map<PlanEdge, DepthProfile> build(NetworkPlan plan,
                                            Map<PlanEdge, List<DepthProfile.Window>> windows) {
        return build(plan, windows, DepthProfile.NORMAL_DEPTH);
    }

    static Map<PlanEdge, DepthProfile> build(NetworkPlan plan,
                                            Map<PlanEdge, List<DepthProfile.Window>> windows,
                                            double sourceDepth) {
        Map<PlanEdge, DepthProfile> result = new LinkedHashMap<>();
        Map<PlanNode, List<PlanEdge>> adjacency = new LinkedHashMap<>();
        for (PlanEdge edge : plan.edges) {
            result.put(edge, DepthProfile.build(edge.length(), windows.get(edge)));
            adjacency.computeIfAbsent(edge.a, k -> new ArrayList<>()).add(edge);
            adjacency.computeIfAbsent(edge.b, k -> new ArrayList<>()).add(edge);
        }
        Set<PlanEdge> visited = new HashSet<>();
        for (PlanEdge seed : plan.edges) {
            if (!visited.add(seed)) continue;
            List<PlanEdge> component = new ArrayList<>();
            Deque<PlanEdge> queue = new ArrayDeque<>();
            queue.add(seed);
            while (!queue.isEmpty()) {
                PlanEdge edge = queue.remove();
                component.add(edge);
                for (PlanNode node : List.of(edge.a, edge.b)) {
                    for (PlanEdge next : adjacency.get(node)) if (visited.add(next)) queue.add(next);
                }
            }
            if (consistent(component, result, sourceDepth)) continue;
            Graph graph = new Graph(component, windows, sourceDepth);
            Map<PlanEdge, DepthProfile> repaired = graph.repair();
            if (repaired != null && consistent(component, repaired, sourceDepth)) {
                result.putAll(repaired);
            } else {
                String code = graph.limitReached ? "DEPTH_SEARCH_LIMIT" : "DEPTH_NETWORK_INFEASIBLE";
                String message = graph.limitReached
                        ? "Не найден согласованный профиль в пределах перебора разрешённых сторон пересечений."
                        : sourceDepth < DepthProfile.MIN_DEPTH
                        ? "Глубина врезки в исходную теплосеть " + sourceDepth
                            + " м меньше минимального покрытия новой сети " + DepthProfile.MIN_DEPTH + " м."
                        : "На текущей XY-трассе не удалось согласовать глубины, уклон и отметки врезок.";
                for (PlanEdge edge : component) result.put(edge, result.get(edge).withIssue(code, message));
            }
        }
        return result;
    }

    private static boolean fixed(PlanNode node) {
        return node.kind == PlanNode.Kind.EXISTING_CHAMBER
                || node.kind == PlanNode.Kind.NEW_CHAMBER && node.splitPipeDu != null;
    }

    private static boolean consistent(List<PlanEdge> edges, Map<PlanEdge, DepthProfile> profiles,
                                      double sourceDepth) {
        Map<PlanNode, Double> depths = new HashMap<>();
        for (PlanEdge edge : edges) {
            DepthProfile p = profiles.get(edge);
            if (p == null || !p.isValid()) return false;
            for (PlanNode node : List.of(edge.a, edge.b)) {
                double depth = p.depthAt(node == edge.a ? 0 : edge.length());
                if (fixed(node) && Math.abs(depth - sourceDepth) > EPS) return false;
                Double previous = depths.putIfAbsent(node, depth);
                if (previous != null && Math.abs(previous - depth) > EPS) return false;
            }
        }
        return true;
    }

    private static final class Vertex {
        final int id;
        final List<Link> links = new ArrayList<>();
        final boolean fixed;
        Vertex(int id, boolean fixed) { this.id = id; this.fixed = fixed; }
    }

    private static final class Link {
        final int to;
        final double change;
        Link(int to, double length) { this.to = to; this.change = DepthProfile.MAX_SLOPE * length; }
    }

    private static final class Constraint {
        final DepthProfile.Window preferred;
        final List<Integer> vertices = new ArrayList<>();
        final int choice;
        Constraint(DepthProfile.Window window, int choice) { preferred = window; this.choice = choice; }
        DepthProfile.Window selected(BitSet sides) {
            return choice >= 0 && sides.get(choice) ? preferred.alternative : preferred;
        }
    }

    private static final class Arc {
        final PlanEdge edge;
        final List<Double> positions;
        final List<Vertex> vertices = new ArrayList<>();
        final List<Constraint> constraints = new ArrayList<>();
        Arc(PlanEdge edge, List<Double> positions) { this.edge = edge; this.positions = positions; }
    }

    private static final class Bound {
        final int vertex;
        final double value;
        Bound(int vertex, double value) { this.vertex = vertex; this.value = value; }
    }

    private static final class Graph {
        final List<Vertex> vertices = new ArrayList<>();
        final List<Arc> arcs = new ArrayList<>();
        final List<Constraint> constraints = new ArrayList<>();
        final double sourceDepth;
        boolean valid = true;
        boolean limitReached;
        int choices;

        Graph(List<PlanEdge> edges, Map<PlanEdge, List<DepthProfile.Window>> windows, double sourceDepth) {
            this.sourceDepth = sourceDepth;
            Map<PlanNode, Vertex> nodes = new HashMap<>();
            for (PlanEdge edge : edges) {
                double length = edge.length();
                if (!Double.isFinite(length) || length <= EPS) {
                    valid = false;
                    return;
                }
                TreeSet<Double> positions = new TreeSet<>(List.of(0.0, length));
                for (DepthProfile.Window w : windows.get(edge)) {
                    if (!finiteWindow(w, length) || w.alternative != null && !finiteWindow(w.alternative, length)) {
                        valid = false;
                        return;
                    }
                    positions.add(w.from);
                    positions.add(w.to);
                }
                Arc arc = new Arc(edge, new ArrayList<>(positions));
                for (double s : arc.positions) {
                    PlanNode node = s == 0 ? edge.a : s == length ? edge.b : null;
                    Vertex v = node == null ? vertex(false) : nodes.computeIfAbsent(node, n -> vertex(fixed(n)));
                    arc.vertices.add(v);
                }
                for (int i = 1; i < arc.vertices.size(); i++) {
                    Vertex a = arc.vertices.get(i - 1), b = arc.vertices.get(i);
                    double distance = arc.positions.get(i) - arc.positions.get(i - 1);
                    a.links.add(new Link(b.id, distance));
                    b.links.add(new Link(a.id, distance));
                }
                for (DepthProfile.Window w : windows.get(edge)) {
                    Constraint constraint = new Constraint(w, w.alternative == null ? -1 : choices++);
                    for (int i = 0; i < arc.positions.size(); i++) {
                        double s = arc.positions.get(i);
                        if (s >= w.from && s <= w.to) constraint.vertices.add(arc.vertices.get(i).id);
                    }
                    arc.constraints.add(constraint);
                    constraints.add(constraint);
                }
                arcs.add(arc);
            }
        }

        private Vertex vertex(boolean fixed) {
            Vertex v = new Vertex(vertices.size(), fixed);
            vertices.add(v);
            return v;
        }

        private boolean finiteWindow(DepthProfile.Window w, double length) {
            return Double.isFinite(length) && length >= 0 && Double.isFinite(w.from)
                    && Double.isFinite(w.to) && Double.isFinite(w.minDepth) && Double.isFinite(w.maxDepth)
                    && w.from >= 0 && w.to <= length && w.from <= w.to;
        }

        Map<PlanEdge, DepthProfile> repair() {
            if (!valid) return null;
            // Конфликт двух распространённых границ требует изменить хотя бы одно
            // из породивших их окон. Перебираем только эти стороны, сохраняя лимит.
            Deque<BitSet> pending = new ArrayDeque<>();
            Set<BitSet> seen = new HashSet<>();
            pending.add(new BitSet());
            int attempts = 0;
            while (!pending.isEmpty() && attempts < MAX_SIDE_ASSIGNMENTS) {
                BitSet sides = pending.removeFirst();
                if (!seen.add(sides)) continue;
                attempts++;
                double[] lo = new double[vertices.size()], hi = new double[vertices.size()];
                int[] loSource = new int[lo.length], hiSource = new int[hi.length];
                Arrays.fill(loSource, -1);
                Arrays.fill(hiSource, -1);
                for (Vertex v : vertices) {
                    // Нижняя граница распространяется как кратчайший путь для -h.
                    lo[v.id] = v.fixed ? -Math.max(sourceDepth, DepthProfile.MIN_DEPTH) : -DepthProfile.MIN_DEPTH;
                    hi[v.id] = v.fixed ? sourceDepth : INF;
                }
                for (Constraint c : constraints) {
                    DepthProfile.Window w = c.selected(sides);
                    for (int v : c.vertices) {
                        if (-w.minDepth < lo[v]) { lo[v] = -w.minDepth; loSource[v] = c.choice; }
                        if (w.maxDepth < hi[v]) { hi[v] = w.maxDepth; hiSource[v] = c.choice; }
                    }
                }
                propagate(lo, loSource);
                propagate(hi, hiSource);
                int conflict = -1;
                for (int i = 0; i < lo.length; i++) if (-lo[i] > hi[i] + EPS) { conflict = i; break; }
                if (conflict < 0) {
                    double[] depth = new double[lo.length];
                    for (int i = 0; i < lo.length; i++) depth[i] = clamp(DepthProfile.NORMAL_DEPTH, -lo[i], hi[i]);
                    Map<PlanEdge, DepthProfile> profiles = profiles(depth, sides);
                    if (profiles.values().stream().allMatch(DepthProfile::isValid)) return profiles;
                    return null;
                }
                for (int choice : new int[]{hiSource[conflict], loSource[conflict]}) {
                    if (choice < 0) continue;
                    BitSet next = (BitSet) sides.clone();
                    next.flip(choice);
                    if (!seen.contains(next)) pending.addLast(next);
                }
            }
            limitReached = !pending.isEmpty();
            return null;
        }

        private void propagate(double[] bounds, int[] origins) {
            PriorityQueue<Bound> queue = new PriorityQueue<>(Comparator.comparingDouble((Bound b) -> b.value)
                    .thenComparingInt(b -> b.vertex));
            for (Vertex v : vertices) queue.add(new Bound(v.id, bounds[v.id]));
            while (!queue.isEmpty()) {
                Bound item = queue.remove();
                if (item.value != bounds[item.vertex]) continue;
                for (Link link : vertices.get(item.vertex).links) {
                    double proposed = item.value + link.change;
                    if (proposed < bounds[link.to] - EPS) {
                        bounds[link.to] = proposed;
                        origins[link.to] = origins[item.vertex];
                        queue.add(new Bound(link.to, proposed));
                    }
                }
            }
        }

        private Map<PlanEdge, DepthProfile> profiles(double[] depth, BitSet sides) {
            Map<PlanEdge, DepthProfile> profiles = new LinkedHashMap<>();
            for (Arc arc : arcs) {
                List<DepthProfile.Window> selected = new ArrayList<>();
                for (Constraint c : arc.constraints) selected.add(c.selected(sides));
                List<double[]> points = new ArrayList<>();
                for (int i = 1; i < arc.positions.size(); i++) {
                    double start = arc.positions.get(i - 1), end = arc.positions.get(i), mid = (start + end) / 2;
                    double lo = DepthProfile.MIN_DEPTH, hi = INF;
                    for (DepthProfile.Window w : selected) if (mid >= w.from && mid <= w.to) {
                        lo = Math.max(lo, w.minDepth);
                        hi = Math.min(hi, w.maxDepth);
                    }
                    appendInterval(points, start, end, depth[arc.vertices.get(i - 1).id],
                            depth[arc.vertices.get(i).id], lo, hi);
                }
                profiles.put(arc.edge, DepthProfile.checked(points, selected));
            }
            return profiles;
        }
    }

    private static double clamp(double value, double lo, double hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    /** Ближайшая к 3 м допустимая функция между двумя согласованными отметками. */
    private static void appendInterval(List<double[]> points, double from, double to,
                                       double a, double b, double lo, double hi) {
        double length = to - from, slope = DepthProfile.MAX_SLOPE;
        // Все изломы max/min огибающих находятся в пересечениях этих прямых.
        double[][] lines = {{lo, 0}, {hi, 0}, {DepthProfile.NORMAL_DEPTH, 0},
                {a, -slope}, {b - slope * length, slope}, {a, slope}, {b + slope * length, -slope}};
        TreeSet<Double> cuts = new TreeSet<>(List.of(0.0, length));
        for (int i = 0; i < lines.length; i++) for (int j = i + 1; j < lines.length; j++) {
            double denominator = lines[i][1] - lines[j][1];
            if (denominator == 0) continue;
            double s = (lines[j][0] - lines[i][0]) / denominator;
            if (s > EPS && s < length - EPS) cuts.add(s);
        }
        for (double s : cuts) {
            double lower = Math.max(lo, Math.max(a - slope * s, b - slope * (length - s)));
            double upper = Math.min(hi, Math.min(a + slope * s, b + slope * (length - s)));
            double h = s == 0 ? a : s == length ? b : clamp(DepthProfile.NORMAL_DEPTH, lower, upper);
            appendPoint(points, from + s, h);
        }
    }

    private static void appendPoint(List<double[]> points, double s, double h) {
        if (!points.isEmpty() && Math.abs(points.get(points.size() - 1)[0] - s) < EPS) return;
        while (points.size() >= 2) {
            double[] a = points.get(points.size() - 2), b = points.get(points.size() - 1);
            double expected = a[1] + (h - a[1]) * (b[0] - a[0]) / (s - a[0]);
            if (Math.abs(expected - b[1]) > 1e-10) break;
            points.remove(points.size() - 1);
        }
        points.add(new double[]{s, h});
    }
}
