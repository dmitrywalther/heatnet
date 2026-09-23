package ru.lct.heatnet.network;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import ru.lct.heatnet.refdata.CostModel;
import ru.lct.heatnet.refdata.PipeGrade;
import ru.lct.heatnet.refdata.PipeTable;
import ru.lct.heatnet.routing.EdgeValidator;
import ru.lct.heatnet.routing.ForbiddenZone;
import ru.lct.heatnet.routing.GeoUtil;
import ru.lct.heatnet.routing.GradeContext;
import ru.lct.heatnet.routing.ObstacleIndex;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Превращает планируемую сеть в готовый вариант: расчёт расходов, подбор
 * условных диаметров (пропускная способность + предельная длина +
 * неубывание к месту присоединения), деление на спецучастки с техническими
 * узлами, стоимость и сводка.
 */
public class NetworkAssembler {

    private final ObstacleIndex obstacles;

    public NetworkAssembler(ObstacleIndex obstacles) {
        this.obstacles = obstacles;
    }

    public VariantResult assemble(NetworkPlan plan, String variantId, String strategyName) {
        return assemble(plan, variantId, strategyName, false);
    }

    /**
     * @param depthMode true — дополнительный режим: вертикальный профиль вдоль
     *                  готовых маршрутов, атрибуты depth_start/depth_end и Kгл
     */
    public VariantResult assemble(NetworkPlan plan, String variantId, String strategyName,
                                  boolean depthMode) {
        VariantResult result = new VariantResult();
        result.variantId = variantId;
        result.strategyName = strategyName;

        Map<PlanNode, PlanEdge> parentEdge = computeFlows(plan);
        assignGrades(plan, parentEdge);

        // --- идентификаторы узлов ---
        int chamberSeq = 1, nodeSeq = 1, segmentSeq = 1;
        Map<PlanNode, Object> nodeIds = new LinkedHashMap<>();
        for (PlanNode n : plan.nodes) {
            switch (n.kind) {
                case OKS:
                case EXISTING_CHAMBER:
                    nodeIds.put(n, n.inputId);
                    break;
                case NEW_CHAMBER:
                    n.outId = variantId + "_chamber_" + chamberSeq++;
                    nodeIds.put(n, n.outId);
                    break;
                case TECHNICAL_NODE:
                    n.outId = variantId + "_node_p" + nodeSeq++;
                    nodeIds.put(n, n.outId);
                    break;
                default:
            }
        }

        // --- сегментация рёбер по спецучасткам, проверка отступов ---
        double totalLength = 0;
        double segmentsCost = 0;
        for (PlanEdge edge : plan.edges) {
            GradeContext ctx = obstacles.forGrade(edge.grade);
            EdgeValidator validator = new EdgeValidator(ctx);
            List<Coordinate> coords = edge.coords;
            double arc = 0;
            List<GeoUtil.SpecialInterval> intervals = new ArrayList<>();
            List<GeoUtil.SpecialInterval> rawIntervals = new ArrayList<>();
            boolean valid = true;
            for (int i = 0; i + 1 < coords.size(); i++) {
                Coordinate a = coords.get(i), b = coords.get(i + 1);
                Set<Object> exempt = exemptFor(edge, i, coords.size());
                Object skip = skipRestrictionFor(edge, i, coords.size());
                EdgeValidator.EdgeCheck check = validator.check(a, b, exempt, skip);
                if (!check.valid) {
                    valid = false;
                    org.slf4j.LoggerFactory.getLogger(NetworkAssembler.class).warn(
                            "Нарушение отступа: ребро {}->{} (ДУ {}), сегмент {} [{} -> {}]",
                            edge.a.kind, edge.b.kind, edge.grade.getDu(), i, a, b);
                }
                for (GeoUtil.SpecialInterval si : check.specials) {
                    intervals.add(new GeoUtil.SpecialInterval(arc + si.from, arc + si.to, si.kSpec));
                }
                for (GeoUtil.SpecialInterval si : check.rawSpecials) {
                    rawIntervals.add(new GeoUtil.SpecialInterval(
                            arc + si.from, arc + si.to, si.kSpec, si.source));
                }
                arc += a.distance(b);
            }
            if (!valid) {
                result.clearanceViolated = true;
            }
            double edgeLength = arc;
            List<GeoUtil.SpecialInterval> merged = GeoUtil.mergeIntervals(intervals, edgeLength);

            // вертикальный профиль (дополнительный режим)
            DepthProfile profile = null;
            if (depthMode) {
                profile = DepthProfile.build(edgeLength,
                        verticalWindows(rawIntervals, edge.grade.getHeightM(), edgeLength));
            }

            // точки деления: границы спецучастков + изломы профиля глубины
            java.util.TreeSet<Double> cuts = new java.util.TreeSet<>();
            cuts.add(0.0);
            cuts.add(edgeLength);
            for (GeoUtil.SpecialInterval si : merged) {
                cuts.add(Math.max(0, si.from));
                cuts.add(Math.min(edgeLength, si.to));
            }
            if (profile != null) {
                cuts.addAll(profile.breakpoints(edgeLength));
            }

            Object aId = nodeIds.get(edge.a);
            Object bId = nodeIds.get(edge.b);
            List<Double> cutList = new ArrayList<>(cuts);
            int fragCount = 0;
            for (int f = 0; f + 1 < cutList.size(); f++) {
                if (cutList.get(f + 1) - cutList.get(f) > 1e-6) {
                    fragCount++;
                }
            }
            int fragIndex = 0;
            for (int f = 0; f + 1 < cutList.size(); f++) {
                double fromArc = cutList.get(f), toArc = cutList.get(f + 1);
                double fragLen = toArc - fromArc;
                if (fragLen <= 1e-6) {
                    continue;
                }
                double mid = (fromArc + toArc) / 2;
                double k = 1.0;
                for (GeoUtil.SpecialInterval si : merged) {
                    if (si.from <= mid && mid <= si.to) {
                        k = Math.max(k, si.kSpec);
                    }
                }
                double kDepth = profile == null ? 1.0 : profile.kDepth(fromArc, toArc);
                List<Coordinate> fragCoords = substring(coords, fromArc, toArc);

                VariantResult.Segment seg = new VariantResult.Segment();
                seg.id = variantId + "_net_" + segmentSeq++;
                seg.flowTph = round2(edge.flowTph);
                seg.diameter = edge.grade.getDu();
                seg.lengthM = round2(fragLen);
                seg.special = k > 1.0;
                seg.kSpec = k;
                if (profile != null) {
                    seg.depthStart = round2(profile.depthAt(fromArc));
                    seg.depthEnd = round2(profile.depthAt(toArc));
                }
                // стоимость от выгружаемой (округлённой) длины — воспроизводимо
                // проверяющей стороной
                seg.cost = Math.round(seg.lengthM * edge.grade.getCostPerMeter() * k * kDepth);
                seg.utmCoords = fragCoords;

                // идентификаторы граничных узлов
                if (fragIndex == 0) {
                    seg.startNodeId = aId;
                    seg.startWgs = edge.a.wgs;
                } else {
                    seg.startNodeId = techNodeId(result, fragCoords.get(0), variantId);
                }
                if (fragIndex == fragCount - 1) {
                    seg.endNodeId = bId;
                    seg.endWgs = edge.b.wgs;
                } else {
                    seg.endNodeId = techNodeId(result, fragCoords.get(fragCoords.size() - 1), variantId);
                }
                fragIndex++;

                totalLength += fragLen;
                segmentsCost += seg.cost;
                result.segments.add(seg);
            }
        }

        // --- камеры и плановые технические узлы ---
        double chambersCost = 0;
        for (PlanNode n : plan.nodes) {
            if (n.kind == PlanNode.Kind.TECHNICAL_NODE) {
                VariantResult.TechNode t = new VariantResult.TechNode();
                t.id = n.outId;
                t.utm = n.utm;
                result.techNodes.add(t);
                continue;
            }
            if (n.kind != PlanNode.Kind.NEW_CHAMBER) {
                continue;
            }
            int maxDu = n.splitPipeDu != null ? n.splitPipeDu : 0;
            for (PlanEdge e : plan.incident(n)) {
                if (e.grade != null) {
                    maxDu = Math.max(maxDu, e.grade.getDu());
                }
            }
            VariantResult.Chamber chamber = new VariantResult.Chamber();
            chamber.id = n.outId;
            chamber.utm = n.utm;
            chamber.diameter = maxDu;
            chamber.cost = CostModel.chamberCost(maxDu);
            chambersCost += chamber.cost;
            result.chambers.add(chamber);
        }

        // --- врезки в существующие камеры ---
        int tieIns = 0;
        for (Map.Entry<PlanNode, Integer> entry : plan.tieInsToExisting.entrySet()) {
            tieIns += entry.getValue();
        }
        double tieInCost = tieIns * CostModel.TIE_IN_TO_EXISTING_CHAMBER_COST;

        // --- сводка ---
        double penalty = 0;
        for (ru.lct.heatnet.model.InputModel.OksPoint oks : plan.unconnected) {
            penalty += CostModel.unconnectedPenalty(oks.flowTph);
            result.unconnectedOksIds.add(oks.id);
        }

        result.chamberConstructionCost = chambersCost;
        result.existingChamberTieInCount = tieIns;
        result.existingChamberTieInCost = tieInCost;
        result.constructionCost = segmentsCost + chambersCost + tieInCost;
        result.unconnectedPenalty = penalty;
        result.calculatedCost = result.constructionCost + penalty;
        result.newNetworkLength = round2(totalLength);
        result.score = round4(CostModel.score(result.calculatedCost, result.newNetworkLength));
        return result;
    }

    /**
     * Окна допустимых глубин верха габарита новой сети по вертикальным
     * правилам пересекаемых препятствий (таблица 2, режим с глубиной).
     */
    private List<DepthProfile.Window> verticalWindows(
            List<GeoUtil.SpecialInterval> rawIntervals, double ourHeightM, double edgeLength) {
        List<DepthProfile.Window> windows = new ArrayList<>();
        for (GeoUtil.SpecialInterval si : rawIntervals) {
            if (si.source == null || si.source.vertical == null) {
                continue;
            }
            ru.lct.heatnet.refdata.RestrictionRule.Vertical v = si.source.vertical;
            double lo;
            double hi;
            if (v.underOnly) {
                // под объектом: верх габарита не выше заданной глубины
                lo = Math.max(DepthProfile.MIN_DEPTH, v.minTopDepthM);
                hi = 1e9;
            } else {
                double aboveHi = v.obstacleTopDepthM - v.clearanceM - ourHeightM;
                double belowLo = v.obstacleTopDepthM + v.obstacleHeightM + v.clearanceM;
                if (aboveHi >= DepthProfile.MIN_DEPTH) {
                    lo = DepthProfile.MIN_DEPTH;   // проходим над препятствием — дешевле
                    hi = aboveHi;
                } else {
                    lo = belowLo;                  // над не помещаемся — ныряем под
                    hi = 1e9;
                }
            }
            windows.add(new DepthProfile.Window(
                    Math.max(0, si.from), Math.min(edgeLength, si.to), lo, hi));
        }
        return windows;
    }

    private Set<Object> exemptFor(PlanEdge edge, int segIndex, int coordCount) {
        Set<Object> exempt = new HashSet<>();
        if (segIndex == 0) {
            exempt.addAll(edge.a.exemptSpecialIds);
        }
        if (segIndex == coordCount - 2) {
            exempt.addAll(edge.b.exemptSpecialIds);
        }
        return exempt;
    }

    private Object skipRestrictionFor(PlanEdge edge, int segIndex, int coordCount) {
        if (segIndex == 0 && edge.a.kind == PlanNode.Kind.OKS) {
            return edge.a.ownRestrictionId;
        }
        if (segIndex == coordCount - 2 && edge.b.kind == PlanNode.Kind.OKS) {
            return edge.b.ownRestrictionId;
        }
        return null;
    }

    /** Технический узел на границе спецучастка; совпадающие координаты переиспользуются. */
    private Object techNodeId(VariantResult result, Coordinate at, String variantId) {
        for (VariantResult.TechNode t : result.techNodes) {
            if (t.utm.distance(at) < 1e-3) {
                return t.id;
            }
        }
        VariantResult.TechNode node = new VariantResult.TechNode();
        node.id = variantId + "_node_" + (result.techNodes.size() + 1);
        node.utm = at;
        result.techNodes.add(node);
        return node.id;
    }

    /** Подполилиния по дуговым координатам [from, to]. */
    static List<Coordinate> substring(List<Coordinate> coords, double from, double to) {
        List<Coordinate> result = new ArrayList<>();
        double arc = 0;
        for (int i = 0; i + 1 < coords.size(); i++) {
            Coordinate a = coords.get(i), b = coords.get(i + 1);
            double len = a.distance(b);
            double start = arc, end = arc + len;
            if (end < from - 1e-9) {
                arc = end;
                continue;
            }
            if (start > to + 1e-9) {
                break;
            }
            double t0 = Math.max(0, (from - start) / len);
            double t1 = Math.min(1, (to - start) / len);
            Coordinate c0 = interpolate(a, b, t0);
            Coordinate c1 = interpolate(a, b, t1);
            if (result.isEmpty()) {
                result.add(c0);
            }
            if (c1.distance(result.get(result.size() - 1)) > 1e-9) {
                result.add(c1);
            }
            arc = end;
        }
        if (result.size() < 2 && !coords.isEmpty()) {
            result.clear();
            result.add(coords.get(0));
            result.add(coords.get(coords.size() - 1));
        }
        return result;
    }

    private static Coordinate interpolate(Coordinate a, Coordinate b, double t) {
        return new Coordinate(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t);
    }

    /** Ориентация дерева от мест присоединения, расчёт расходов. */
    private Map<PlanNode, PlanEdge> computeFlows(NetworkPlan plan) {
        Map<PlanNode, List<PlanEdge>> adj = new HashMap<>();
        for (PlanEdge e : plan.edges) {
            adj.computeIfAbsent(e.a, k -> new ArrayList<>()).add(e);
            adj.computeIfAbsent(e.b, k -> new ArrayList<>()).add(e);
        }
        List<PlanNode> roots = new ArrayList<>();
        for (PlanNode n : plan.nodes) {
            boolean tieRoot = n.kind == PlanNode.Kind.EXISTING_CHAMBER
                    || (n.kind == PlanNode.Kind.NEW_CHAMBER && n.splitPipeDu != null);
            if (tieRoot && adj.containsKey(n)) {
                roots.add(n);
            }
        }

        Map<PlanNode, PlanEdge> parentEdge = new HashMap<>();
        List<PlanNode> order = new ArrayList<>();
        Set<PlanNode> visited = new HashSet<>(roots);
        Deque<PlanNode> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            PlanNode u = queue.poll();
            order.add(u);
            for (PlanEdge e : adj.getOrDefault(u, Collections.emptyList())) {
                PlanNode v = e.other(u);
                if (visited.add(v)) {
                    parentEdge.put(v, e);
                    queue.add(v);
                }
            }
        }

        // обход в обратном порядке: аккумулируем расходы от листьев к корню
        for (PlanEdge e : plan.edges) {
            e.downstreamOks.clear();
        }
        for (int i = order.size() - 1; i >= 0; i--) {
            PlanNode u = order.get(i);
            PlanEdge up = parentEdge.get(u);
            if (up == null) {
                continue;
            }
            if (u.kind == PlanNode.Kind.OKS && u.inputId != null) {
                up.downstreamOks.add(u.inputId);
            }
            for (PlanEdge e : adj.getOrDefault(u, Collections.emptyList())) {
                if (e != up && parentEdge.get(e.other(u)) == e) {
                    up.downstreamOks.addAll(e.downstreamOks);
                }
            }
        }
        Map<Object, Double> flows = new HashMap<>();
        for (PlanNode n : plan.nodes) {
            if (n.kind == PlanNode.Kind.OKS && n.oksFlow != null) {
                flows.put(n.inputId, n.oksFlow);
            }
        }
        for (PlanEdge e : plan.edges) {
            double f = 0;
            for (Object id : e.downstreamOks) {
                f += flows.getOrDefault(id, 0.0);
            }
            e.flowTph = f;
        }
        return parentEdge;
    }

    /** Подбор ДУ: пропускная способность, предельная длина, неубывание к корню. */
    private void assignGrades(NetworkPlan plan, Map<PlanNode, PlanEdge> parentEdge) {
        for (PlanEdge e : plan.edges) {
            e.grade = PipeTable.minGradeForFlow(e.flowTph);
        }
        List<PlanNode> leaves = new ArrayList<>();
        for (PlanNode n : plan.nodes) {
            if (n.kind == PlanNode.Kind.OKS) {
                leaves.add(n);
            }
        }
        for (int iter = 0; iter < 30; iter++) {
            boolean changed = false;
            // предельная длина по каждому непрерывному пути лист -> корень
            for (PlanNode leaf : leaves) {
                List<PlanEdge> path = pathToRoot(leaf, parentEdge);
                int i = 0;
                while (i < path.size()) {
                    int j = i;
                    double runLength = 0;
                    while (j < path.size() && path.get(j).grade == path.get(i).grade) {
                        runLength += path.get(j).length();
                        j++;
                    }
                    if (runLength > path.get(i).grade.getLimitLengthM() + 1e-6) {
                        PipeGrade next = PipeTable.next(path.get(i).grade);
                        if (next != null) {
                            for (int k = i; k < j; k++) {
                                path.get(k).grade = next;
                            }
                            changed = true;
                        }
                    }
                    i = j;
                }
            }
            // неубывание ДУ по направлению к месту присоединения
            for (PlanNode leaf : leaves) {
                List<PlanEdge> path = pathToRoot(leaf, parentEdge);
                for (int i = 1; i < path.size(); i++) {
                    if (path.get(i).grade.getDu() < path.get(i - 1).grade.getDu()) {
                        path.get(i).grade = path.get(i - 1).grade;
                        changed = true;
                    }
                }
            }
            if (!changed) {
                break;
            }
        }
    }

    /** Рёбра пути от листа к корню (в порядке от листа). */
    private List<PlanEdge> pathToRoot(PlanNode leaf, Map<PlanNode, PlanEdge> parentEdge) {
        List<PlanEdge> path = new ArrayList<>();
        PlanNode current = leaf;
        Set<PlanNode> guard = new HashSet<>();
        while (parentEdge.containsKey(current) && guard.add(current)) {
            PlanEdge e = parentEdge.get(current);
            path.add(e);
            current = e.other(current);
        }
        return path;
    }

    static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}
