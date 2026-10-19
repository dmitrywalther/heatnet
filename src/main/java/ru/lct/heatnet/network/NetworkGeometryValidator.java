package ru.lct.heatnet.network;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.index.strtree.STRtree;
import ru.lct.heatnet.routing.EdgeValidator;
import ru.lct.heatnet.routing.GeoUtil;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Проверка поворотов, взаимных пересечений и отступов после изменения всей новой сети. */
public final class NetworkGeometryValidator {

    private static final double ENDPOINT_ROUNDOFF_M = 1e-6;
    private static final double MAX_TURN_DEG = 90.0 + 1e-6;

    private NetworkGeometryValidator() {
    }

    public static boolean isValid(NetworkPlan plan) {
        List<LineString> lines = new ArrayList<>(plan.edges.size());
        Map<PlanNode, List<Coordinate>> directions = new IdentityHashMap<>();
        STRtree index = new STRtree();
        for (int i = 0; i < plan.edges.size(); i++) {
            PlanEdge edge = plan.edges.get(i);
            if (edge.coords.size() < 2
                    || edge.coords.get(0).distance(edge.a.utm) > ENDPOINT_ROUNDOFF_M
                    || edge.coords.get(edge.coords.size() - 1).distance(edge.b.utm) > ENDPOINT_ROUNDOFF_M) {
                return false;
            }
            // Только копия: округление не должно менять геометрию или смету.
            List<Coordinate> coords = new ArrayList<>(edge.coords);
            coords.set(0, edge.a.utm);
            coords.set(coords.size() - 1, edge.b.utm);
            if (!validTurns(coords)) {
                return false;
            }
            LineString line = GeoUtil.polyline(coords);
            if (!line.isSimple() || line.isClosed() || line.getLength() < ENDPOINT_ROUNDOFF_M) {
                return false;
            }
            lines.add(line);
            index.insert(line.getEnvelopeInternal(), i);
            directions.computeIfAbsent(edge.a, n -> new ArrayList<>(2))
                    .add(firstDistinct(coords, true));
            directions.computeIfAbsent(edge.b, n -> new ArrayList<>(2))
                    .add(firstDistinct(coords, false));
        }
        for (Map.Entry<PlanNode, List<Coordinate>> entry : directions.entrySet()) {
            PlanNode node = entry.getKey();
            List<Coordinate> neighbors = entry.getValue();
            // Разбиение линии техническим узлом или лишней камерой не отменяет
            // предел поворота. У врезки в исходную трубу есть ещё два направления.
            boolean transit = node.kind == PlanNode.Kind.TECHNICAL_NODE
                    || (node.kind == PlanNode.Kind.NEW_CHAMBER && node.splitPipeDu == null);
            if (transit && neighbors.size() == 2
                    && GeoUtil.turnAngleDeg(neighbors.get(0), node.utm, neighbors.get(1)) > MAX_TURN_DEG) {
                return false;
            }
        }
        index.build();
        for (int i = 0; i < lines.size(); i++) {
            Envelope search = new Envelope(lines.get(i).getEnvelopeInternal());
            search.expandBy(EdgeValidator.BARRIER_SEPARATION_M);
            @SuppressWarnings("unchecked")
            List<Integer> nearby = index.query(search);
            for (int j : nearby) {
                if (j <= i) {
                    continue;
                }
                PlanEdge a = plan.edges.get(i), b = plan.edges.get(j);
                List<Coordinate> shared = new ArrayList<>(2);
                if (a.a == b.a || a.a == b.b) {
                    shared.add(a.a.utm);
                }
                if (a.b == b.a || a.b == b.b) {
                    shared.add(a.b.utm);
                }
                if (!EdgeValidator.networkLinesCompatible(lines.get(i), lines.get(j), shared)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean validTurns(List<Coordinate> coords) {
        Coordinate previous = null, current = coords.get(0);
        for (int i = 1; i < coords.size(); i++) {
            Coordinate next = coords.get(i);
            if (current.equals2D(next)) {
                continue;
            }
            if (previous != null && GeoUtil.turnAngleDeg(previous, current, next) > MAX_TURN_DEG) {
                return false;
            }
            previous = current;
            current = next;
        }
        return true;
    }

    private static Coordinate firstDistinct(List<Coordinate> coords, boolean fromStart) {
        int start = fromStart ? 0 : coords.size() - 1;
        int step = fromStart ? 1 : -1;
        for (int i = start + step; i >= 0 && i < coords.size(); i += step) {
            if (!coords.get(start).equals2D(coords.get(i))) {
                return coords.get(i);
            }
        }
        throw new IllegalArgumentException("Ребро нулевой длины");
    }
}
