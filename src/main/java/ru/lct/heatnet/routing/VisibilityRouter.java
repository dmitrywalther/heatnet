package ru.lct.heatnet.routing;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import ru.lct.heatnet.refdata.CostModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Поиск маршрута по графу видимости: вершины — углы раздутых препятствий,
 * рёбра — допустимые прямые участки. Вес ребра — вклад в итоговый показатель
 * S (0,7 — стоимость с учётом Kспец, 0,3 — протяжённость), терминальная
 * стоимость кандидата — стоимость камеры или врезки.
 */
public class VisibilityRouter {

    /** Результат маршрутизации: координаты от точки присоединения к началу. */
    public static final class RoutedPath {
        public final List<Coordinate> coords;   // [tie ... start]
        public final TieCandidate tie;
        public final double scoreCost;

        RoutedPath(List<Coordinate> coords, TieCandidate tie, double scoreCost) {
            this.coords = coords;
            this.tie = tie;
            this.scoreCost = scoreCost;
        }
    }

    private final GradeContext ctx;
    private final EdgeValidator validator;
    private final double unitCostPerMeter;
    private final double weightPerMeter;
    private final double weightPerCostRub;
    /** Бюджет раскрытий A*; 0 — без ограничения. */
    private final int maxExpansions;
    /** Ограничение длины ребра графа на первичном поиске; 0 — без ограничения. */
    private final double edgeLengthCap;

    public VisibilityRouter(GradeContext ctx, EdgeValidator validator, double unitCostPerMeter) {
        this(ctx, validator, unitCostPerMeter, 0, 0);
    }

    public VisibilityRouter(GradeContext ctx, EdgeValidator validator,
                            double unitCostPerMeter, int maxExpansions, double edgeLengthCap) {
        this.ctx = ctx;
        this.validator = validator;
        this.unitCostPerMeter = unitCostPerMeter;
        this.maxExpansions = maxExpansions;
        this.edgeLengthCap = edgeLengthCap;
        this.weightPerCostRub = CostModel.SCORE_COST_WEIGHT / CostModel.SCORE_COST_NORM;
        this.weightPerMeter = weightPerCostRub * unitCostPerMeter
                + CostModel.SCORE_LENGTH_WEIGHT / CostModel.SCORE_LENGTH_NORM;
    }

    /**
     * @param starts          стартовые точки (узлы подхода к ОКС) с начальной
     *                        стоимостью (длина финального участка)
     * @param startExemptIds  исключения зон близости для рёбер, инцидентных старту
     * @param targets         кандидаты присоединения
     */
    public RoutedPath route(List<Coordinate> starts, double[] startCosts,
                            Set<Object> startExemptIds, List<TieCandidate> targets) {
        if (targets.isEmpty() || starts.isEmpty()) {
            return null;
        }
        Envelope narrow = corridor(starts, targets, 1.4, 200);
        // Ограничение длины ребра графа видимости резко сокращает число
        // дорогих геометрических проверок; длинный прямой пролёт почти всегда
        // эквивалентен цепочке коротких через вершины препятствий.
        double cap = edgeLengthCap > 0 ? edgeLengthCap : Double.POSITIVE_INFINITY;
        RoutedPath best = routeInCorridor(starts, startCosts, startExemptIds, targets,
                narrow, cap);
        if (best == null && edgeLengthCap > 0) {
            best = routeInCorridor(starts, startCosts, startExemptIds, targets,
                    narrow, Double.POSITIVE_INFINITY);
        }
        if (best == null) {
            Envelope wide = corridor(starts, targets, 4.0, 1500);
            // повторный поиск имеет смысл, только если коридор реально расширился
            if (!narrow.contains(wide) && !narrow.contains(dataExtent())) {
                best = routeInCorridor(starts, startCosts, startExemptIds, targets,
                        wide, Double.POSITIVE_INFINITY);
            }
        }
        return best;
    }

    /** Габарит всех препятствий контекста — верхняя граница полезного коридора. */
    private Envelope dataExtent() {
        Envelope env = new Envelope();
        for (ForbiddenZone z : ctx.forbidden) {
            env.expandToInclude(z.nodeZone.getEnvelopeInternal());
        }
        return env;
    }

    private Envelope corridor(List<Coordinate> starts, List<TieCandidate> targets,
                              double expandFactor, double minMargin) {
        Envelope env = new Envelope(starts.get(0));
        for (Coordinate s : starts) {
            env.expandToInclude(s);
        }
        for (TieCandidate t : targets) {
            env.expandToInclude(t.location);
        }
        double margin = Math.max(minMargin, expandFactor * Math.hypot(env.getWidth(), env.getHeight()) / 2);
        env.expandBy(margin);
        return env;
    }

    private RoutedPath routeInCorridor(List<Coordinate> starts, double[] startCosts,
                                       Set<Object> startExemptIds,
                                       List<TieCandidate> targets, Envelope corridor,
                                       double maxEdgeLength) {
        // --- сборка вершин графа ---
        List<Coordinate> nodes = new ArrayList<>(starts);   // индексы [0, startCount)
        int startCount = starts.size();
        int firstTarget;
        List<TieCandidate> reachableTargets = new ArrayList<>();
        for (TieCandidate t : targets) {
            if (corridor.contains(t.location)) {
                reachableTargets.add(t);
            }
        }
        if (reachableTargets.isEmpty()) {
            return null;
        }
        // вершины уже отфильтрованы от чужих зон блокировки в контексте
        nodes.addAll(ctx.graphNodesIn(corridor));
        firstTarget = nodes.size();
        for (TieCandidate t : reachableTargets) {
            nodes.add(t.location);
        }
        int n = nodes.size();

        // --- A* с ленивым построением видимости ---
        double[] g = new double[n];
        int[] parent = new int[n];
        boolean[] closed = new boolean[n];
        java.util.Arrays.fill(g, Double.POSITIVE_INFINITY);
        java.util.Arrays.fill(parent, -1);

        // эвристика (расстояние до ближайшей цели) считается один раз на вершину
        double[] h = new double[n];
        for (int v = 0; v < n; v++) {
            h[v] = v >= firstTarget
                    ? reachableTargets.get(v - firstTarget).terminalCostRub * weightPerCostRub
                    : heuristic(nodes.get(v), reachableTargets);
        }

        PriorityQueue<long[]> open = new PriorityQueue<>((x, y) ->
                Double.compare(Double.longBitsToDouble(x[0]), Double.longBitsToDouble(y[0])));
        for (int s = 0; s < startCount; s++) {
            g[s] = startCosts[s] * weightPerMeter;
            open.add(new long[]{Double.doubleToLongBits(g[s] + h[s]), s});
        }

        double bestTotal = Double.POSITIVE_INFINITY;
        int bestTargetNode = -1;
        TieCandidate bestTie = null;
        Map<Long, EdgeValidator.EdgeCheck> edgeCache = new HashMap<>();
        int expansions = 0;

        while (!open.isEmpty()) {
            long[] top = open.poll();
            double f = Double.longBitsToDouble(top[0]);
            int u = (int) top[1];
            if (closed[u]) {
                continue;
            }
            closed[u] = true;
            if (maxExpansions > 0 && ++expansions > maxExpansions) {
                // Бюджет поиска исчерпан: возвращаем лучший найденный маршрут
                // (дальше шло бы только доказательство его оптимальности),
                // либо отдаём управление резервной стратегии.
                break;
            }
            if (f >= bestTotal) {
                break; // дальнейшие пути не улучшат лучший найденный результат
            }
            if (u >= firstTarget) {
                TieCandidate tie = reachableTargets.get(u - firstTarget);
                double total = g[u] + tie.terminalCostRub * weightPerCostRub;
                if (total < bestTotal) {
                    bestTotal = total;
                    bestTargetNode = u;
                    bestTie = tie;
                }
                continue;
            }
            Coordinate cu = nodes.get(u);
            for (int v = 0; v < n; v++) {
                if (v == u || closed[v] || (v < startCount && u < startCount)) {
                    continue;
                }
                Coordinate cv = nodes.get(v);
                // дешёвые нижние оценки до дорогой геометрической валидации
                double dist = cu.distance(cv);
                if (dist > maxEdgeLength) {
                    continue;
                }
                double lowerBound = g[u] + dist * weightPerMeter;
                if (lowerBound >= g[v] - 1e-12) {
                    continue;
                }
                if (lowerBound + h[v] >= bestTotal) {
                    continue;
                }
                double w = edgeWeight(u, v, cu, cv, startCount, startExemptIds,
                        reachableTargets, firstTarget, edgeCache);
                if (Double.isNaN(w)) {
                    continue;
                }
                double ng = g[u] + w;
                if (ng < g[v] - 1e-12) {
                    g[v] = ng;
                    parent[v] = u;
                    double hv = v >= firstTarget ? 0 : h[v];
                    open.add(new long[]{Double.doubleToLongBits(ng + hv), v});
                }
            }
        }

        if (bestTargetNode < 0) {
            org.slf4j.LoggerFactory.getLogger(VisibilityRouter.class).warn(
                    "Маршрут не найден: узлов={}, стартов={}, целей={}",
                    n, startCount, reachableTargets.size());
            return null;
        }
        List<Coordinate> path = new ArrayList<>();
        for (int v = bestTargetNode; v != -1; v = parent[v]) {
            path.add(nodes.get(v));
        }
        // path идёт от цели к старту; порядок [tie ... start] соответствует контракту
        return new RoutedPath(path, bestTie, bestTotal);
    }

    private double edgeWeight(int u, int v, Coordinate cu, Coordinate cv,
                              int startCount, Set<Object> startExemptIds,
                              List<TieCandidate> targets,
                              int firstTarget, Map<Long, EdgeValidator.EdgeCheck> cache) {
        long key = u < v ? ((long) u << 32) | v : ((long) v << 32) | u;
        EdgeValidator.EdgeCheck check = cache.get(key);
        if (check == null) {
            Set<Object> exempt = null;
            if (u < startCount || v < startCount) {
                exempt = startExemptIds;
            }
            Coordinate allowTouchAt = null;
            int targetIdx = Math.max(u, v) - firstTarget;
            if (Math.max(u, v) >= firstTarget) {
                TieCandidate tie = targets.get(targetIdx);
                Set<Object> tieExempt = tie.exemptSpecialIds;
                if (exempt == null || exempt.isEmpty()) {
                    exempt = tieExempt;
                } else {
                    Set<Object> merged = new java.util.HashSet<>(exempt);
                    merged.addAll(tieExempt);
                    exempt = merged;
                }
                // касание построенной сети допустимо только в точке присоединения
                allowTouchAt = tie.location;
            }
            check = validator.check(cu, cv, exempt, null, allowTouchAt);
            cache.put(key, check);
        }
        if (!check.valid) {
            return Double.NaN;
        }
        double specialExtra = 0;
        for (GeoUtil.SpecialInterval si : check.specials) {
            specialExtra += (si.kSpec - 1) * (si.to - si.from);
        }
        return check.length * weightPerMeter
                + specialExtra * unitCostPerMeter * weightPerCostRub;
    }

    private double heuristic(Coordinate c, List<TieCandidate> targets) {
        double min = Double.POSITIVE_INFINITY;
        for (TieCandidate t : targets) {
            double d = c.distance(t.location);
            if (d < min) {
                min = d;
            }
        }
        return min * weightPerMeter;
    }
}
