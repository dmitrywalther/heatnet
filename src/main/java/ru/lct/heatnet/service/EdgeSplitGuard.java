package ru.lct.heatnet.service;

import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.network.PlanEdge;
import ru.lct.heatnet.network.PlanNode;
import ru.lct.heatnet.routing.EdgeValidator;
import ru.lct.heatnet.routing.GradeContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Проверка двух изменяемых прямых участков до разреза нового ребра камерой. */
final class EdgeSplitGuard {
    private static final double SPLIT_SNAP_M = 1e-3;
    private static final double ENDPOINT_EPS_M = 1e-6;

    private EdgeSplitGuard() {
    }

    static boolean allows(PlanEdge edge, Coordinate candidate, GradeContext context) {
        List<Coordinate> coords = edge.coords;
        int bestSegment = -1;
        double bestDistance = Double.POSITIVE_INFINITY;
        Coordinate cut = null;
        // Та же проекция и выбор первого ближайшего сегмента, что в NetworkPlan.splitEdge.
        for (int i = 0; i + 1 < coords.size(); i++) {
            Coordinate projected = project(coords.get(i), coords.get(i + 1), candidate);
            double distance = projected.distance(candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestSegment = i;
                cut = projected;
            }
        }
        // Новая ветвь заканчивается в candidate: существенный перенос при splitEdge
        // оставил бы её конец в стороне от фактически созданной камеры.
        if (bestSegment < 0 || bestDistance > ENDPOINT_EPS_M) return false;

        List<Coordinate> first = new ArrayList<>(coords.subList(0, bestSegment + 1));
        if (first.isEmpty() || first.get(first.size() - 1).distance(cut) > SPLIT_SNAP_M) {
            first.add(cut);
        }
        List<Coordinate> second = new ArrayList<>();
        second.add(cut);
        for (int i = bestSegment + 1; i < coords.size(); i++) {
            if (second.size() == 1 && coords.get(i).distance(cut) <= SPLIT_SNAP_M) continue;
            second.add(coords.get(i));
        }
        // splitEdge подавляет координаты в 1 мм. Это не должно создавать
        // несовпадение конца дочерней полилинии с новой камерой.
        if (first.size() < 2 || second.size() < 2
                || first.get(first.size() - 1).distance(cut) > ENDPOINT_EPS_M
                || first.get(0).distance(edge.a.utm) > ENDPOINT_EPS_M
                || second.get(second.size() - 1).distance(edge.b.utm) > ENDPOINT_EPS_M) return false;

        // Без барьеров нового плана: исходное ребро иначе пересекалось бы с собой.
        // Остальные прямые сегменты геометрически не меняются; полный финальный
        // аудит после назначения расходов и ДУ остаётся в NetworkAssembler.
        EdgeValidator validator = new EdgeValidator(context);
        int last = first.size() - 1;
        boolean firstIsOuter = first.size() == 2;
        boolean secondIsOuter = second.size() == 2;
        return validator.check(first.get(last - 1), first.get(last),
                exemptions(edge.a, firstIsOuter), ownOks(edge.a, firstIsOuter),
                null, sourceConnectionAt(edge.a, firstIsOuter)).valid
                && validator.check(second.get(0), second.get(1),
                exemptions(edge.b, secondIsOuter), ownOks(edge.b, secondIsOuter),
                null, sourceConnectionAt(edge.b, secondIsOuter)).valid;
    }

    private static Coordinate sourceConnectionAt(PlanNode originalEnd, boolean touchesOriginalEnd) {
        return touchesOriginalEnd && originalEnd.isSourceConnection() ? originalEnd.utm : null;
    }

    private static Set<Object> exemptions(PlanNode originalEnd, boolean touchesOriginalEnd) {
        return touchesOriginalEnd ? originalEnd.exemptSpecialIds : Collections.emptySet();
    }

    private static Object ownOks(PlanNode originalEnd, boolean touchesOriginalEnd) {
        return touchesOriginalEnd && originalEnd.kind == PlanNode.Kind.OKS ? originalEnd.ownRestrictionId : null;
    }

    private static Coordinate project(Coordinate a, Coordinate b, Coordinate p) {
        double dx = b.x - a.x, dy = b.y - a.y;
        double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared == 0) return a.copy();
        double fraction = ((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSquared;
        fraction = Math.max(0, Math.min(1, fraction));
        return new Coordinate(a.x + fraction * dx, a.y + fraction * dy);
    }
}
