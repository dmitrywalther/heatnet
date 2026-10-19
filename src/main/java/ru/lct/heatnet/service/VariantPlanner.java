package ru.lct.heatnet.service;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.network.NetworkPlan;
import ru.lct.heatnet.network.PlanEdge;
import ru.lct.heatnet.network.PlanNode;
import ru.lct.heatnet.refdata.CostModel;
import ru.lct.heatnet.refdata.PipeGrade;
import ru.lct.heatnet.refdata.PipeTable;
import ru.lct.heatnet.routing.EdgeValidator;
import ru.lct.heatnet.routing.ForbiddenZone;
import ru.lct.heatnet.routing.GeoUtil;
import ru.lct.heatnet.routing.GradeContext;
import ru.lct.heatnet.routing.ObstacleIndex;
import ru.lct.heatnet.routing.OksApproach;
import ru.lct.heatnet.routing.TieCandidate;
import ru.lct.heatnet.routing.VisibilityRouter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Построение плана сети одного варианта: последовательное подключение ОКС
 * (по убыванию расхода) с выбором оптимальной точки присоединения —
 * к существующей сети или к уже построенной новой (совместное подключение).
 */
public class VariantPlanner {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(VariantPlanner.class);

    public enum Strategy {
        /** Совместное подключение: разрешено присоединяться к построенной новой сети. */
        SHARED,
        /** Каждый ОКС ведётся к существующей сети отдельно. */
        INDEPENDENT,
        /** Совместное подключение с запретом точек присоединения базового варианта. */
        ALTERNATIVE
    }

    /**
     * Порядок вставки ОКС. Жадное последовательное подключение чувствительно
     * к порядку, поэтому лучший совместный вариант выбирается из нескольких
     * порядков (все детерминированы — результат воспроизводим).
     */
    public enum InsertionOrder {
        /** По убыванию расхода: крупные потребители формируют магистраль. */
        FLOW_DESC,
        /** От дальних к сети: самый дальний прокладывает магистраль через район. */
        FARTHEST_FIRST,
        /** От ближних: сеть прирастает от существующей инфраструктуры наружу. */
        NEAREST_FIRST,
        /** Детерминированные перемешивания — выход из локальных оптимумов. */
        SHUFFLE_A,
        SHUFFLE_B,
        SHUFFLE_C,
        SHUFFLE_D
    }

    private static final double PIPE_SAMPLE_STEP = 10.0;
    private static final double CHAMBER_RULE_RADIUS = 10.0;
    private static final int MAX_CHAMBER_CONNECTIONS = 4;
    private static final double SOURCE_TOPOLOGY_EPS = 1e-6;
    private static final double MAX_SUPPORTED_FLOW = PipeTable.grades()
            .get(PipeTable.grades().size() - 1).getCapacityTph();

    private final InputModel input;
    private final ObstacleIndex obstacles;
    private final EntrySelectionPolicy entrySelectionPolicy;
    /**
     * Расчётный габарит для проверки отступов: ДУ по суммарному расходу всех
     * точек подключения (максимум, который может собрать общая магистраль),
     * но не выше ДУ 400 — дальше растёт отступ от ОКС (7/9 м) и излишняя
     * консервативность может отрезать узкие проходы. Ширина габарита ДУ 400
     * отличается от малых ДУ менее чем на полметра, зато маршруты не требуют
     * перестроения после подбора фактических диаметров.
     */
    private final PipeGrade clearanceGrade;
    /** Проверка суммарной нагрузки нужна только при возможном превышении максимума таблицы. */
    private final boolean capacityChecksRequired;

    /** Существующая степень камер (число примыкающих существующих участков). */
    private final Map<Object, Integer> chamberDegrees = new HashMap<>();
    /** Трубы, чьи зоны близости не проверяются при примыкании к камере. */
    private final Map<Object, Set<Object>> chamberExemptPipes = new HashMap<>();
    private final Map<Object, InputModel.ExistingPipe> pipesById = new HashMap<>();
    /** Пространственный индекс существующих труб для поиска пересечений. */
    private final org.locationtech.jts.index.strtree.STRtree pipeIndex =
            new org.locationtech.jts.index.strtree.STRtree();

    public VariantPlanner(InputModel input, ObstacleIndex obstacles) {
        this(input, obstacles, EntrySelectionPolicy.DEFAULT);
    }

    VariantPlanner(InputModel input, ObstacleIndex obstacles, EntrySelectionPolicy entrySelectionPolicy) {
        this.input = input;
        this.obstacles = obstacles;
        this.entrySelectionPolicy = java.util.Objects.requireNonNull(entrySelectionPolicy);
        double totalFlow = 0;
        for (InputModel.OksPoint oks : input.oksPoints) {
            totalFlow += oks.flowTph;
        }
        PipeGrade byTotal = PipeTable.minGradeForFlow(totalFlow);
        this.clearanceGrade = byTotal.getDu() > 400 ? PipeTable.byDu(400) : byTotal;
        this.capacityChecksRequired = !Double.isFinite(totalFlow) || totalFlow > MAX_SUPPORTED_FLOW;
        for (InputModel.ExistingPipe pipe : input.pipes) {
            pipesById.put(pipe.id, pipe);
            pipeIndex.insert(pipe.utm.getEnvelopeInternal(), pipe);
        }
        pipeIndex.build();
        for (InputModel.ExistingChamber chamber : input.chambers) {
            int degree = 0;
            Set<Object> exempt = new HashSet<>();
            Coordinate c = chamber.utm.getCoordinate();
            for (InputModel.ExistingPipe pipe : input.pipes) {
                Coordinate[] coords = pipe.utm.getCoordinates();
                if (coords[0].distance(c) < 0.5 || coords[coords.length - 1].distance(c) < 0.5) {
                    degree++;
                    exempt.add(pipe.id);
                } else if (pipe.utm.distance(GeoUtil.GF.createPoint(c)) < 1.5) {
                    // транзитная линия через камеру занимает два примыкания
                    degree += 2;
                    exempt.add(pipe.id);
                }
            }
            chamberDegrees.put(chamber.id, degree);
            chamberExemptPipes.put(chamber.id, exempt);
        }
    }

    /** @param bannedTieRefs id труб/камер, запрещённых как места присоединения */
    public NetworkPlan plan(Strategy strategy, Set<Object> bannedTieRefs) {
        return plan(strategy, bannedTieRefs, null, InsertionOrder.FLOW_DESC);
    }

    /**
     * @param minAssumedGrade нижняя граница расчётного ДУ для отступов —
     *                        используется при перезапуске, если после подбора
     *                        диаметров итоговый габарит вырос
     */
    public NetworkPlan plan(Strategy strategy, Set<Object> bannedTieRefs,
                            PipeGrade minAssumedGrade, InsertionOrder order) {
        NetworkPlan plan = new NetworkPlan();
        List<InputModel.OksPoint> ordered = orderedOks(order);

        PipeGrade assumed = clearanceGrade;
        if (minAssumedGrade != null && minAssumedGrade.getDu() > assumed.getDu()) {
            assumed = minAssumedGrade;
        }
        GradeContext ctx = obstacles.forGrade(assumed);
        // один валидатор на вариант: статический кэш проверок сохраняется,
        // барьеры построенных участков добавляются инкрементально
        EdgeValidator validator = new EdgeValidator(ctx);

        for (InputModel.OksPoint oks : ordered) {
            long start = System.currentTimeMillis();
            boolean connected = connectOks(plan, oks, strategy, bannedTieRefs, ctx, validator);
            if (!connected) {
                plan.unconnected.add(oks);
            }
            log.debug("[{}] ОКС {}: {} за {} мс", strategy, oks.id,
                    connected ? "подключён" : "НЕ подключён", System.currentTimeMillis() - start);
        }
        return plan;
    }

    /**
     * Повторное подключение одного ОКС к текущему состоянию плана (после
     * удаления его ветви) — используется 1-opt-улучшением: ранние ветви
     * строились, когда общей сети ещё не было.
     */
    public boolean reconnect(NetworkPlan plan, InputModel.OksPoint oks) {
        GradeContext ctx = obstacles.forGrade(clearanceGrade);
        return connectOks(plan, oks, Strategy.SHARED, null, ctx, validatorFor(plan, ctx));
    }

    private EdgeValidator validatorFor(NetworkPlan plan, GradeContext ctx) {
        EdgeValidator validator = new EdgeValidator(ctx);
        for (PlanEdge built : plan.edges) {
            if (built.coords.size() >= 2) {
                validator.addBarrier(GeoUtil.polyline(built.coords));
            }
        }
        return validator;
    }

    public static final class ReconnectCandidate {
        public final NetworkPlan plan;
        public final boolean alternativeEntry;

        ReconnectCandidate(NetworkPlan plan, boolean alternativeEntry) {
            this.plan = plan;
            this.alternativeEntry = alternativeEntry;
        }
    }

    /**
     * Независимые пробы для полной оценки сети. Входной план уже без ветви ОКС
     * и не изменяется. Обычный reconnect сохраняет прежние fallback; до трёх
     * дополнительных близких нормалей проверяются строго по отдельности.
     */
    public List<ReconnectCandidate> reconnectCandidates(NetworkPlan disconnectedPlan,
                                                        InputModel.OksPoint oks) {
        List<ReconnectCandidate> candidates = new ArrayList<>();
        if (!supportedIndividualFlow(oks.flowTph)) return candidates;
        NetworkPlan ordinary = disconnectedPlan.copy();
        Coordinate ordinaryDirection = null;
        if (reconnect(ordinary, oks)) {
            candidates.add(new ReconnectCandidate(ordinary, false));
            ordinaryDirection = terminalDirection(ordinary, oks.id);
        }
        if (entrySelectionPolicy.maxAlternativeEntries == 0) {
            return candidates;
        }

        GradeContext ctx = obstacles.forGrade(clearanceGrade);
        ApproachOptions options = approachOptions(disconnectedPlan, oks, Strategy.SHARED,
                ctx, validatorFor(disconnectedPlan, ctx));
        if (options == null || !options.geometry.hasTail() || options.normals.isEmpty()) {
            return candidates;
        }
        OksApproach approach = options.geometry;
        Map<Coordinate, Double> internal = new HashMap<>();
        for (Coordinate start : options.normals) {
            internal.put(start, approach.internalLength(start));
        }
        double minInternal = internal.values().stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        List<Coordinate> ordered = new ArrayList<>(options.normals);
        ordered.sort(Comparator.comparingDouble((Coordinate c) -> internal.get(c))
                .thenComparingDouble(c -> c.x).thenComparingDouble(c -> c.y));
        Coordinate p = oks.utm.getCoordinate();
        int attempted = 0;
        for (Coordinate start : ordered) {
            if (internal.get(start) > minInternal + entrySelectionPolicy.extraInternalLengthM + 1e-6) {
                break;
            }
            if (sameEntryDirection(p, start, ordinaryDirection)) {
                continue;
            }
            if (attempted++ >= entrySelectionPolicy.maxAlternativeEntries) {
                break;
            }
            NetworkPlan trial = disconnectedPlan.copy();
            if (connectOks(trial, oks, Strategy.SHARED, null, ctx,
                    validatorFor(trial, ctx), List.of(start))) {
                candidates.add(new ReconnectCandidate(trial, true));
            }
        }
        return candidates;
    }

    /** Ближайшая к P вершина конечного ребра; сохраняет направление после фаски. */
    private Coordinate terminalDirection(NetworkPlan plan, Object oksId) {
        for (PlanNode node : plan.nodes) {
            if (node.kind == PlanNode.Kind.OKS && oksId.equals(node.inputId)) {
                List<PlanEdge> incident = plan.incident(node);
                if (incident.size() == 1) {
                    List<Coordinate> coords = incident.get(0).coordsFrom(node);
                    return coords.size() > 1 ? coords.get(1) : null;
                }
            }
        }
        return null;
    }

    private boolean sameEntryDirection(Coordinate p, Coordinate a, Coordinate b) {
        if (b == null) {
            return false;
        }
        double ax = a.x - p.x, ay = a.y - p.y;
        double bx = b.x - p.x, by = b.y - p.y;
        double scale = Math.hypot(ax, ay) * Math.hypot(bx, by);
        return scale > 1e-12 && ax * bx + ay * by > 0
                && Math.abs(ax * by - ay * bx) <= 1e-8 * scale;
    }

    private static final class DirectNormalTie {
        final TieCandidate tie;
        final EdgeValidator.EdgeCheck check;
        DirectNormalTie(TieCandidate tie, EdgeValidator.EdgeCheck check) {
            this.tie = tie;
            this.check = check;
        }
    }

    private static final class ApproachOptions {
        final OksApproach geometry;
        final List<Coordinate> normals = new ArrayList<>();
        final List<Coordinate> ordinaryNormals = new ArrayList<>();
        final List<Coordinate> fallback = new ArrayList<>();
        final Map<Coordinate, List<DirectNormalTie>> direct = new HashMap<>();
        ApproachOptions(OksApproach geometry) { this.geometry = geometry; }
    }

    private ApproachOptions approachOptions(NetworkPlan plan, InputModel.OksPoint oks, Strategy strategy,
                                             GradeContext ctx, EdgeValidator validator) {
        OksApproach available = OksApproach.build(oks, ctx, validator);
        OksApproach staticApproach = strategy == Strategy.INDEPENDENT ? null
                : OksApproach.build(oks, ctx, new EdgeValidator(ctx));
        OksApproach geometry = available != null ? available : staticApproach;
        if (geometry == null) return null;
        ApproachOptions options = new ApproachOptions(geometry);
        if (available != null) {
            if (!available.hasTail() || available.hasPerpendicularApproaches()) {
                options.ordinaryNormals.addAll(available.approachNodes);
                options.fallback.addAll(available.fallbackApproachNodes);
            } else {
                options.fallback.addAll(available.approachNodes);
            }
        }
        options.normals.addAll(options.ordinaryNormals);
        if (staticApproach == null || !staticApproach.hasPerpendicularApproaches() || plan.edges.isEmpty()) {
            return options;
        }
        Coordinate p = oks.utm.getCoordinate();
        Envelope extent = new Envelope(p);
        for (PlanEdge edge : plan.edges) for (Coordinate c : edge.coords) extent.expandToInclude(c);
        double rayLength = 2 * Math.hypot(extent.getWidth(), extent.getHeight()) + 1;
        PipeGrade costGrade = PipeTable.minGradeForFlow(oks.flowTph);
        for (Coordinate start : staticApproach.approachNodes) {
            double startDistance = p.distance(start);
            Coordinate far = new Coordinate(p.x + (start.x - p.x) * rayLength / startDistance,
                    p.y + (start.y - p.y) * rayLength / startDistance);
            LineString ray = GeoUtil.segment(p, far);
            List<DirectNormalTie> ties = new ArrayList<>();
            for (PlanEdge edge : plan.edges) {
                org.locationtech.jts.geom.Geometry crossings = ray.intersection(GeoUtil.polyline(edge.coords));
                if (crossings.isEmpty() || crossings.getDimension() != 0) continue;
                for (Coordinate crossing : crossings.getCoordinates()) {
                    if (p.distance(crossing) < startDistance - 1e-6 || insideBlocking(crossing, ctx)) continue;
                    TieCandidate tie = directTieCandidate(plan, edge, crossing, costGrade, ctx);
                    if (tie == null || ties.stream().anyMatch(t -> t.tie.location.distance(tie.location) < 1e-6)) continue;
                    EdgeValidator.EdgeCheck check = validator.check(tie.location, p, tie.exemptSpecialIds,
                            staticApproach.ownRestrictionId, tie.location, tie.sourceConnectionAt());
                    if (check.valid) ties.add(new DirectNormalTie(tie, check));
                }
            }
            if (!ties.isEmpty()) {
                options.direct.put(start, ties);
                if (!options.normals.contains(start)) options.normals.add(start);
            }
        }
        return options;
    }

    private TieCandidate directTieCandidate(NetworkPlan plan, PlanEdge edge, Coordinate crossing,
                                             PipeGrade costGrade) {
        return directTieCandidate(plan, edge, crossing, costGrade, obstacles.forGrade(clearanceGrade));
    }

    private TieCandidate directTieCandidate(NetworkPlan plan, PlanEdge edge, Coordinate crossing,
                                             PipeGrade costGrade, GradeContext ctx) {
        PlanNode node = plan.findNode(crossing);
        if (node == null && edge.a.utm.distance(crossing) <= 1e-6) node = edge.a;
        if (node == null && edge.b.utm.distance(crossing) <= 1e-6) node = edge.b;
        if (node != null) {
            // Grid lookup uses 1 mm; a direct normal may not be bent to a nearby grid match.
            if (node.utm.distance(crossing) > 1e-6 || node.kind != PlanNode.Kind.NEW_CHAMBER
                    || !canAddBranch(plan, node)) {
                return null;
            }
            return new TieCandidate(TieCandidate.Kind.NEW_NETWORK_NODE, node.utm, node, 0, node.exemptSpecialIds);
        }
        if (edge.a.utm.distance(crossing) <= 1e-3 || edge.b.utm.distance(crossing) <= 1e-3) return null;
        if (!canSplitEdge(edge, crossing, ctx)) return null;
        return new TieCandidate(TieCandidate.Kind.NEW_NETWORK_EDGE_POINT, crossing, edge,
                CostModel.chamberCost(costGrade.getDu()), null);
    }

    private List<InputModel.OksPoint> orderedOks(InsertionOrder order) {
        List<InputModel.OksPoint> ordered = new ArrayList<>(input.oksPoints);
        switch (order) {
            case FARTHEST_FIRST:
                ordered.sort(Comparator.comparingDouble(this::distanceToNetwork).reversed());
                break;
            case NEAREST_FIRST:
                ordered.sort(Comparator.comparingDouble(this::distanceToNetwork));
                break;
            case SHUFFLE_A:
                java.util.Collections.shuffle(ordered, new java.util.Random(42));
                break;
            case SHUFFLE_B:
                java.util.Collections.shuffle(ordered, new java.util.Random(4242));
                break;
            case SHUFFLE_C:
                java.util.Collections.shuffle(ordered, new java.util.Random(777));
                break;
            case SHUFFLE_D:
                java.util.Collections.shuffle(ordered, new java.util.Random(314159));
                break;
            case FLOW_DESC:
            default:
                ordered.sort(Comparator.comparingDouble(
                        (InputModel.OksPoint o) -> o.flowTph).reversed());
        }
        return ordered;
    }

    private final Map<Object, Double> distToNetworkCache = new java.util.concurrent.ConcurrentHashMap<>();

    private double distanceToNetwork(InputModel.OksPoint oks) {
        return distToNetworkCache.computeIfAbsent(oks.id, id -> {
            double min = Double.POSITIVE_INFINITY;
            for (InputModel.ExistingPipe pipe : input.pipes) {
                min = Math.min(min, pipe.utm.distance(oks.utm));
            }
            return min;
        });
    }

    private boolean connectOks(NetworkPlan plan, InputModel.OksPoint oks,
                               Strategy strategy, Set<Object> bannedTieRefs,
                               GradeContext ctx, EdgeValidator validator) {
        return connectOks(plan, oks, strategy, bannedTieRefs, ctx, validator, null);
    }

    private boolean connectOks(NetworkPlan plan, InputModel.OksPoint oks,
                               Strategy strategy, Set<Object> bannedTieRefs,
                               GradeContext ctx, EdgeValidator validator,
                               List<Coordinate> forcedNormalStarts) {
        if (!supportedIndividualFlow(oks.flowTph)) {
            log.warn("ОКС {}: расход {} т/ч не поддерживается таблицей ДУ; точка останется неподключённой",
                    oks.id, oks.flowTph);
            return false;
        }
        PipeGrade costGrade = PipeTable.minGradeForFlow(oks.flowTph);
        CapacitySnapshot capacity = capacityChecksRequired ? new CapacitySnapshot(plan) : null;

        ApproachOptions options = approachOptions(plan, oks, strategy, ctx, validator);
        if (options == null) {
            log.warn("ОКС {}: не найден допустимый подход к точке подключения", oks.id);
            return false;
        }
        OksApproach approach = options.geometry;

        List<TieCandidate> allTargets =
                buildCandidates(plan, ctx, costGrade, strategy, bannedTieRefs);
        if (capacity != null) {
            allTargets.removeIf(t -> !capacity.allows(t, oks.flowTph));
            filterDirectCapacity(options, capacity, oks.flowTph);
        }
        if (allTargets.isEmpty() && options.direct.isEmpty()) {
            log.warn("ОКС {}: нет кандидатов точек присоединения", oks.id);
            return false;
        }

        Coordinate p = oks.utm.getCoordinate();

        // Независимые маршруты (вариант для сравнения, а не лидер рейтинга)
        // считаем в «быстром» режиме: бюджет раскрытий и ограничение длины
        // ребра; при неудаче деградация к совместному подключению.
        // Основные варианты (shared/alternative) считаются без ограничений.
        boolean fast = strategy == Strategy.INDEPENDENT;
        VisibilityRouter router = new VisibilityRouter(ctx, validator,
                costGrade.getCostPerMeter(), fast ? 800 : 0, fast ? 300 : 0);
        VisibilityRouter.RoutedPath routed =
                routeApproaches(router, forcedNormalStarts == null ? options.normals
                        : forcedNormalStarts, approach, allTargets, options);
        if (routed == null && forcedNormalStarts == null && !options.fallback.isEmpty()) {
            routed = routeApproaches(router, options.fallback, approach, allTargets);
        }
        if (routed == null && forcedNormalStarts == null && strategy == Strategy.INDEPENDENT) {
            // коридор для независимого маршрута занят — допускаем совместное
            // подключение, чтобы не оставлять ОКС без сети
            List<TieCandidate> sharedTargets =
                    buildCandidates(plan, ctx, costGrade, Strategy.SHARED, bannedTieRefs);
            options = approachOptions(plan, oks, Strategy.SHARED, ctx, validator);
            if (options != null) {
                if (capacity != null) {
                    sharedTargets.removeIf(t -> !capacity.allows(t, oks.flowTph));
                    filterDirectCapacity(options, capacity, oks.flowTph);
                }
                approach = options.geometry;
                routed = routeApproaches(router, options.normals, approach, sharedTargets, options);
                if (routed == null && !options.fallback.isEmpty()) {
                    routed = routeApproaches(router, options.fallback, approach, sharedTargets);
                }
            }
        }
        if (routed == null) {
            log.warn("ОКС {}: маршрут не найден ({} кандидатов, {} узлов подхода)",
                    oks.id, allTargets.size(), approach.approachNodes.size());
            return false;
        }

        // уточнение положения врезки в трубу: перпендикулярное основание
        TieCandidate tie = routed.tie;
        List<Coordinate> coords = new ArrayList<>(routed.coords); // [tie ... approach]
        if (tie.kind == TieCandidate.Kind.PIPE_POINT && coords.size() >= 2) {
            Coordinate before = tie.location;
            tie = refinePipeTie(plan, tie, coords, validator);
            if (!tie.location.equals2D(before)) {
                // перенос основания мог оставить объезд, который от новой точки
                // врезки уже не нужен: спрямляем к самой дальней достижимой
                // вершине маршрута
                for (int j = coords.size() - 1; j >= 2; j--) {
                    EdgeValidator.EdgeCheck cut = validator.check(coords.get(j), coords.get(0),
                            tie.exemptSpecialIds, null, tie.location, tie.sourceConnectionAt());
                    if (cut.valid) {
                        coords.subList(1, j).clear();
                        break;
                    }
                }
            }
        }

        // если путь и так пересекает существующую теплосеть спецпереходом,
        // врезка новой камерой в точке пересечения короче: префикс маршрута
        // до пересечения отбрасывается
        tie = snapTieToCrossing(tie, coords, plan, ctx, validator, costGrade, bannedTieRefs);

        // финальный прямой участок от границы полигона до точки подключения
        if (coords.get(coords.size() - 1).distance(p) > 1e-9) {
            coords.add(p);
        }

        coords = postprocess(coords, validator, tie.location, tie.sourceConnectionAt());

        // --- интеграция в план ---
        PlanNode tieNode = resolveTieNode(plan, tie, ctx);
        if (tieNode == null) {
            return false;
        }
        PlanNode oksNode = plan.addNode(new PlanNode(PlanNode.Kind.OKS, p));
        oksNode.kind = PlanNode.Kind.OKS;
        oksNode.inputId = oks.id;
        oksNode.wgs = oks.wgs;
        oksNode.oksFlow = oks.flowTph;
        oksNode.ownRestrictionId = approach.ownRestrictionId;

        plan.addEdge(tieNode, oksNode, coords);
        if (coords.size() >= 2) {
            validator.addBarrier(GeoUtil.polyline(coords));
        }
        if (log.isDebugEnabled()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(3, coords.size()); i++) {
                sb.append(String.format("(%.3f %.3f) ", coords.get(i).x, coords.get(i).y));
            }
            log.debug("[{}] ОКС {}: путь {} вершин, начало: {}", strategy, oks.id, coords.size(), sb);
        }
        return true;
    }

    private static boolean supportedIndividualFlow(double flow) {
        return Double.isFinite(flow) && flow >= 0 && flow <= MAX_SUPPORTED_FLOW;
    }

    private static void filterDirectCapacity(ApproachOptions options, CapacitySnapshot capacity, double flow) {
        for (List<DirectNormalTie> ties : options.direct.values()) {
            ties.removeIf(t -> !capacity.allows(t.tie, flow));
        }
        options.direct.entrySet().removeIf(e -> e.getValue().isEmpty());
        options.normals.removeIf(n -> !options.ordinaryNormals.contains(n) && !options.direct.containsKey(n));
    }

    /**
     * Максимальная текущая нагрузка на пути до исходной сети. Несколько новых
     * ветвей у исходной камеры проверяются отдельно: ограничение мощности
     * существующей сети в этой расчётной модели не вводится.
     */
    private static final class CapacitySnapshot {
        private final Map<PlanNode, Double> nodeMaxFlow = new HashMap<>();
        private final Map<PlanEdge, Double> edgeMaxFlow = new HashMap<>();

        CapacitySnapshot(NetworkPlan plan) {
            Map<PlanNode, List<PlanEdge>> adjacent = new HashMap<>();
            for (PlanEdge edge : plan.edges) {
                adjacent.computeIfAbsent(edge.a, n -> new ArrayList<>()).add(edge);
                adjacent.computeIfAbsent(edge.b, n -> new ArrayList<>()).add(edge);
            }
            Map<PlanNode, PlanEdge> parent = new HashMap<>();
            List<PlanNode> order = new ArrayList<>();
            Set<PlanNode> visited = new HashSet<>();
            for (PlanNode node : plan.nodes) {
                if (node.isSourceConnection()) {
                    order.add(node);
                    visited.add(node);
                    nodeMaxFlow.put(node, 0.0);
                }
            }
            for (int i = 0; i < order.size(); i++) {
                PlanNode node = order.get(i);
                for (PlanEdge edge : adjacent.getOrDefault(node, java.util.Collections.emptyList())) {
                    PlanNode other = edge.other(node);
                    if (visited.add(other)) {
                        parent.put(other, edge);
                        order.add(other);
                    }
                }
            }
            Map<PlanNode, Double> downstreamFlow = new HashMap<>();
            Map<PlanEdge, Double> edgeFlow = new HashMap<>();
            for (int i = order.size() - 1; i >= 0; i--) {
                PlanNode node = order.get(i);
                double flow = downstreamFlow.getOrDefault(node, 0.0);
                if (node.kind == PlanNode.Kind.OKS && node.oksFlow != null) flow += node.oksFlow;
                PlanEdge up = parent.get(node);
                if (up != null) {
                    edgeFlow.put(up, flow);
                    downstreamFlow.merge(up.other(node), flow, Double::sum);
                }
            }
            for (PlanNode node : order) {
                PlanEdge up = parent.get(node);
                if (up == null) continue;
                double flow = edgeFlow.get(up);
                double maximum = Double.isFinite(flow)
                        ? Math.max(flow, nodeMaxFlow.get(up.other(node))) : Double.POSITIVE_INFINITY;
                nodeMaxFlow.put(node, maximum);
                edgeMaxFlow.put(up, maximum);
            }
        }

        boolean allows(TieCandidate tie, double flow) {
            switch (tie.kind) {
                case EXISTING_CHAMBER:
                case PIPE_POINT:
                    return true;
                case NEW_NETWORK_NODE:
                    return flow + nodeMaxFlow.getOrDefault(tie.refId, Double.POSITIVE_INFINITY) <= MAX_SUPPORTED_FLOW;
                case NEW_NETWORK_EDGE_POINT:
                    return flow + edgeMaxFlow.getOrDefault(tie.refId, Double.POSITIVE_INFINITY) <= MAX_SUPPORTED_FLOW;
                default:
                    return false;
            }
        }
    }

    private VisibilityRouter.RoutedPath routeApproaches(VisibilityRouter router,
                                                        List<Coordinate> starts, OksApproach approach,
                                                        List<TieCandidate> allTargets) {
        return routeApproaches(router, starts, approach, allTargets, null);
    }

    private VisibilityRouter.RoutedPath routeApproaches(VisibilityRouter router,
                                                        List<Coordinate> starts, OksApproach approach,
                                                        List<TieCandidate> allTargets, ApproachOptions options) {
        // Сначала кратчайший доступный ввод внутри здания, затем цена наружного
        // маршрута. Длина собственного буфера не должна менять порядок стен.
        Map<Coordinate, Double> internalLengths = new HashMap<>();
        for (Coordinate start : starts) {
            internalLengths.put(start, approach.internalLength(start));
        }
        List<Coordinate> ordered = new ArrayList<>(starts);
        ordered.sort(Comparator.comparingDouble(internalLengths::get));
        Coordinate p = approach.oks.utm.getCoordinate();
        // Даже короткий ввод может быть выгоднее соединить с сетью у другой
        // стороны наружным обходом: цели сохраняем от всех подходов.
        List<TieCandidate> targets = pruneTargets(allTargets, starts);
        for (int from = 0; from < ordered.size();) {
            int to = from + 1;
            double shortest = internalLengths.get(ordered.get(from));
            while (to < ordered.size()
                    && internalLengths.get(ordered.get(to)) - shortest <= 1e-6) {
                to++;
            }
            List<Coordinate> group = new ArrayList<>(ordered.subList(from, to));
            if (options != null) group.removeIf(c -> !options.ordinaryNormals.contains(c));
            double[] startCosts = new double[group.size()];
            for (int i = 0; i < group.size(); i++) {
                startCosts[i] = group.get(i).distance(p);
            }
            VisibilityRouter.RoutedPath routed = router.route(group, startCosts, null, targets);
            if (routed == null && allTargets.size() > targets.size()) {
                routed = router.route(group, startCosts, null, allTargets);
            }
            if (options != null) {
                for (Coordinate start : ordered.subList(from, to)) {
                    for (DirectNormalTie direct : options.direct.getOrDefault(start, List.of())) {
                        VisibilityRouter.RoutedPath candidate = router.directPath(p, direct.tie, direct.check);
                        if (routed == null || candidate.scoreCost < routed.scoreCost) routed = candidate;
                    }
                }
            }
            if (routed != null) {
                return routed;
            }
            from = to;
        }
        return null;
    }


    /** Число направлений исходных труб, геометрически примыкающих к новой камере. */
    private int existingPipeDegree(Coordinate location) {
        Envelope envelope = new Envelope(location);
        envelope.expandBy(SOURCE_TOPOLOGY_EPS);
        @SuppressWarnings("unchecked")
        List<InputModel.ExistingPipe> nearby = pipeIndex.query(envelope);
        org.locationtech.jts.geom.Point point = GeoUtil.GF.createPoint(location);
        int degree = 0;
        for (InputModel.ExistingPipe pipe : nearby) {
            LineString line = pipe.utm;
            if (line.isEmpty() || line.distance(point) > SOURCE_TOPOLOGY_EPS) continue;
            int ends = 0;
            if (line.getCoordinateN(0).distance(location) <= SOURCE_TOPOLOGY_EPS) ends++;
            if (line.getCoordinateN(line.getNumPoints() - 1).distance(location) <= SOURCE_TOPOLOGY_EPS) ends++;
            // Конец занимает одно примыкание, транзитная труба — два.
            // При совпадении нескольких исходных труб учитываем все направления.
            degree += ends == 0 ? 2 : ends;
        }
        return degree;
    }

    /** Existing source-pipe directions are absent from the planned-edge degree. */
    private boolean canAddBranch(NetworkPlan plan, PlanNode node) {
        int existing;
        if (node.kind == PlanNode.Kind.EXISTING_CHAMBER) {
            existing = chamberDegrees.getOrDefault(node.inputId, 0);
        } else if (node.kind == PlanNode.Kind.NEW_CHAMBER) {
            existing = node.splitPipeDu == null ? 0 : existingPipeDegree(node.utm);
        } else {
            return false;
        }
        return existing + plan.degree(node) + 1 <= MAX_CHAMBER_CONNECTIONS;
    }

    private PlanNode resolveTieNode(NetworkPlan plan, TieCandidate tie) {
        return resolveTieNode(plan, tie, obstacles.forGrade(clearanceGrade));
    }

    private PlanNode resolveTieNode(NetworkPlan plan, TieCandidate tie, GradeContext ctx) {
        PlanNode present = plan.findNode(tie.location);
        if (present != null) {
            // An occupied pipe sample is represented by its dedicated chamber
            // candidate. Do not change its kind, split DU or charging metadata.
            if (tie.kind == TieCandidate.Kind.PIPE_POINT
                    || present.utm.distance(tie.location) > 1e-6
                    || !canAddBranch(plan, present)) {
                return null;
            }
            if (tie.kind == TieCandidate.Kind.NEW_NETWORK_NODE
                    || tie.kind == TieCandidate.Kind.NEW_NETWORK_EDGE_POINT) {
                return present.kind == PlanNode.Kind.NEW_CHAMBER ? present : null;
            }
            if (tie.kind == TieCandidate.Kind.EXISTING_CHAMBER
                    && present.kind != PlanNode.Kind.EXISTING_CHAMBER) {
                return null;
            }
        }
        switch (tie.kind) {
            case EXISTING_CHAMBER: {
                InputModel.ExistingChamber chamber = findChamber(tie.refId);
                if (chamber == null || chamberDegrees.getOrDefault(chamber.id, 0)
                        + (present == null ? 0 : plan.degree(present)) + 1 > MAX_CHAMBER_CONNECTIONS) {
                    return null;
                }
                PlanNode node = plan.addNode(new PlanNode(PlanNode.Kind.EXISTING_CHAMBER,
                        chamber.utm.getCoordinate()));
                node.kind = PlanNode.Kind.EXISTING_CHAMBER;
                node.inputId = chamber.id;
                node.wgs = chamber.wgs;
                node.addExempt(tie.exemptSpecialIds);
                plan.tieInsToExisting.merge(node, 1, Integer::sum);
                return node;
            }
            case PIPE_POINT: {
                if (existingPipeDegree(tie.location) + 1 > MAX_CHAMBER_CONNECTIONS) {
                    return null;
                }
                PlanNode node = plan.addNode(new PlanNode(PlanNode.Kind.NEW_CHAMBER, tie.location));
                InputModel.ExistingPipe pipe = pipesById.get(tie.refId);
                node.splitPipeDu = pipe != null ? pipe.diameter : null;
                node.addExempt(tie.exemptSpecialIds);
                return node;
            }
            case NEW_NETWORK_NODE: {
                return plan.findNode(tie.location);
            }
            case NEW_NETWORK_EDGE_POINT: {
                PlanEdge target = null;
                for (PlanEdge e : plan.edges) {
                    if (e == tie.refId) {
                        target = e;
                        break;
                    }
                }
                if (target == null || !canSplitEdge(target, tie.location, ctx)) {
                    return null;
                }
                return plan.splitEdge(target, tie.location, PlanNode.Kind.NEW_CHAMBER);
            }
            default:
                return null;
        }
    }

    private InputModel.ExistingChamber findChamber(Object id) {
        for (InputModel.ExistingChamber c : input.chambers) {
            if (c.id.equals(id)) {
                return c;
            }
        }
        return null;
    }

    private boolean canSplitEdge(PlanEdge edge, Coordinate point, GradeContext ctx) {
        // ctx is the planning/retry clearance grade, not the cost grade of the
        // incoming consumer. An already assigned larger trunk must keep its width.
        GradeContext effective = edge.grade != null && edge.grade.getDu() > ctx.grade.getDu()
                ? obstacles.forGrade(edge.grade) : ctx;
        return EdgeSplitGuard.allows(edge, point, effective);
    }

    private List<TieCandidate> buildCandidates(NetworkPlan plan, GradeContext ctx, PipeGrade assumed,
                                               Strategy strategy, Set<Object> bannedTieRefs) {
        List<TieCandidate> candidates = new ArrayList<>();

        // 1) существующие тепловые камеры
        for (InputModel.ExistingChamber chamber : input.chambers) {
            if (bannedTieRefs != null && bannedTieRefs.contains(chamber.id)) {
                continue;
            }
            int degree = chamberDegrees.getOrDefault(chamber.id, 0);
            PlanNode node = plan.findNode(chamber.utm.getCoordinate());
            int added = node != null ? plan.degree(node) : 0;
            if (degree + added + 1 > MAX_CHAMBER_CONNECTIONS) {
                continue;
            }
            candidates.add(new TieCandidate(TieCandidate.Kind.EXISTING_CHAMBER,
                    chamber.utm.getCoordinate(), chamber.id,
                    CostModel.TIE_IN_TO_EXISTING_CHAMBER_COST,
                    chamberExemptPipes.get(chamber.id)));
        }

        // 2) точки на существующих участках сети (новая камера)
        for (InputModel.ExistingPipe pipe : input.pipes) {
            if (bannedTieRefs != null && bannedTieRefs.contains(pipe.id)) {
                continue;
            }
            for (Coordinate sample : samplePipe(pipe)) {
                if (plan.findNode(sample) != null
                        || existingPipeDegree(sample) + 1 > MAX_CHAMBER_CONNECTIONS) {
                    continue; // occupied positions have a correctly priced chamber candidate
                }
                if (nearAvailableChamber(sample, plan)) {
                    continue; // правило 10 м: используется существующая камера
                }
                if (insideBlocking(sample, ctx)) {
                    continue;
                }
                Set<Object> exempt = new HashSet<>();
                exempt.add(pipe.id);
                for (InputModel.ExistingPipe other : input.pipes) {
                    if (other != pipe
                            && other.utm.distance(GeoUtil.GF.createPoint(sample)) < 1.5) {
                        exempt.add(other.id);
                    }
                }
                double terminal = CostModel.chamberCost(
                        Math.max(pipe.diameter, assumed.getDu()));
                candidates.add(new TieCandidate(TieCandidate.Kind.PIPE_POINT,
                        sample, pipe.id, terminal, exempt));
            }
        }

        // 3) построенная новая сеть (совместное подключение)
        if (strategy != Strategy.INDEPENDENT) {
            for (ru.lct.heatnet.network.PlanNode node : plan.nodes) {
                if (node.kind == PlanNode.Kind.NEW_CHAMBER
                        && canAddBranch(plan, node)
                        && !insideBlocking(node.utm, ctx)) {
                    candidates.add(new TieCandidate(TieCandidate.Kind.NEW_NETWORK_NODE,
                            node.utm, node, 0, node.exemptSpecialIds));
                }
            }
            for (PlanEdge edge : plan.edges) {
                List<Coordinate> pts = sampleEdge(edge);
                double terminal = CostModel.chamberCost(assumed.getDu());
                for (Coordinate c : pts) {
                    if (plan.findNode(c) == null && !insideBlocking(c, ctx) && canSplitEdge(edge, c, ctx)) {
                        candidates.add(new TieCandidate(TieCandidate.Kind.NEW_NETWORK_EDGE_POINT,
                                c, edge, terminal, null));
                    }
                }
            }
        }
        return candidates;
    }

    /**
     * Эвристическое отсечение дальних целей относительно всех подходов.
     * Ближайшая к каждой стене цель сохраняется даже при общем лимите;
     * расстояние до одной ближайшей стены не описывает доступность других.
     */
    private List<TieCandidate> pruneTargets(List<TieCandidate> targets, List<Coordinate> starts) {
        if (targets.size() <= 40) {
            return targets;
        }
        Map<TieCandidate, Double> distance = new HashMap<>();
        for (TieCandidate t : targets) {
            distance.put(t, Double.POSITIVE_INFINITY);
        }
        Set<TieCandidate> selected = new java.util.LinkedHashSet<>();
        Set<TieCandidate> eligible = new java.util.LinkedHashSet<>();
        for (Coordinate from : starts) {
            TieCandidate closest = targets.stream()
                    .min(Comparator.comparingDouble(t -> t.location.distance(from))).orElseThrow();
            selected.add(closest);
            double dmin = closest.location.distance(from);
            double cutoff = Math.max(2 * dmin, dmin + 150);
            for (TieCandidate t : targets) {
                double d = t.location.distance(from);
                distance.put(t, Math.min(distance.get(t), d));
                if (d <= cutoff) {
                    eligible.add(t);
                }
            }
        }
        List<TieCandidate> near = new ArrayList<>(eligible);
        near.sort(Comparator.comparingDouble(distance::get));
        for (TieCandidate t : near) {
            if (selected.size() >= 250) {
                break;
            }
            selected.add(t);
        }
        return new ArrayList<>(selected);
    }

    private boolean nearAvailableChamber(Coordinate c, NetworkPlan plan) {
        for (InputModel.ExistingChamber chamber : input.chambers) {
            if (chamber.utm.getCoordinate().distance(c) <= CHAMBER_RULE_RADIUS) {
                int degree = chamberDegrees.getOrDefault(chamber.id, 0);
                PlanNode node = plan.findNode(chamber.utm.getCoordinate());
                int added = node != null ? plan.degree(node) : 0;
                if (degree + added + 1 <= MAX_CHAMBER_CONNECTIONS) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean insideBlocking(Coordinate c, GradeContext ctx) {
        for (ForbiddenZone z : ctx.forbiddenIn(new Envelope(c))) {
            if (z.containsPoint(c)) {
                return true;
            }
        }
        return false;
    }

    private List<Coordinate> samplePipe(InputModel.ExistingPipe pipe) {
        List<Coordinate> samples = new ArrayList<>();
        LengthIndexedLine indexed = new LengthIndexedLine(pipe.utm);
        double length = pipe.utm.getLength();
        for (double d = 0; d <= length; d += PIPE_SAMPLE_STEP) {
            samples.add(indexed.extractPoint(d));
        }
        if (length % PIPE_SAMPLE_STEP > 1) {
            samples.add(indexed.extractPoint(length));
        }
        for (Coordinate v : pipe.utm.getCoordinates()) {
            samples.add(v.copy());
        }
        return samples;
    }

    private List<Coordinate> sampleEdge(PlanEdge edge) {
        List<Coordinate> samples = new ArrayList<>();
        LineString line = GeoUtil.polyline(edge.coords);
        LengthIndexedLine indexed = new LengthIndexedLine(line);
        double length = line.getLength();
        // не подключаемся вплотную к концам ребра (там уже есть узлы)
        for (double d = PIPE_SAMPLE_STEP; d < length - PIPE_SAMPLE_STEP / 2; d += PIPE_SAMPLE_STEP) {
            samples.add(indexed.extractPoint(d));
        }
        return samples;
    }

    /**
     * Перенос врезки в точку пересечения существующей теплосети. Если маршрут
     * к выбранной точке присоединения по пути пересекает существующий участок
     * (спецпереход), врезка новой камерой прямо в точке пересечения даёт тот же
     * результат без «хвоста»: префикс пути и сам спецпереход отбрасываются.
     * Применяется последнее по ходу пути пересечение и только при выигрыше по
     * показателю с учётом смены терминальной стоимости.
     */
    private TieCandidate snapTieToCrossing(TieCandidate tie, List<Coordinate> coords,
                                           NetworkPlan plan, GradeContext ctx,
                                           EdgeValidator validator, PipeGrade costGrade,
                                           Set<Object> bannedTieRefs) {
        int n = coords.size();
        if (n < 2) {
            return tie;
        }
        double[] prefix = new double[n];
        for (int i = 1; i < n; i++) {
            prefix[i] = prefix[i - 1] + coords.get(i - 1).distance(coords.get(i));
        }
        // от стороны ОКС к врезке: первое ребро, пересекающее существующую трубу
        for (int i = n - 2; i >= 0; i--) {
            org.locationtech.jts.geom.LineString seg = GeoUtil.GF.createLineString(
                    new Coordinate[]{coords.get(i), coords.get(i + 1)});
            @SuppressWarnings("unchecked")
            List<InputModel.ExistingPipe> nearPipes =
                    pipeIndex.query(seg.getEnvelopeInternal());
            if (nearPipes.isEmpty()) {
                continue;
            }
            InputModel.ExistingPipe crossPipe = null;
            Coordinate crossPt = null;
            double bestAlong = -1;
            for (InputModel.ExistingPipe pipe : nearPipes) {
                if (bannedTieRefs != null && bannedTieRefs.contains(pipe.id)) {
                    continue;
                }
                org.locationtech.jts.geom.Geometry inter = seg.intersection(pipe.utm);
                if (inter.isEmpty()) {
                    continue;
                }
                for (Coordinate c : inter.getCoordinates()) {
                    if (plan.findNode(c) != null
                            || existingPipeDegree(c) + 1 > MAX_CHAMBER_CONNECTIONS) {
                        continue; // do not replace a valid tie with an occupied pipe sample
                    }
                    double along = c.distance(coords.get(i));
                    if (along > bestAlong) {
                        bestAlong = along;
                        crossPt = new Coordinate(c);
                        crossPipe = pipe;
                    }
                }
            }
            if (crossPipe == null) {
                continue;
            }
            double cutLength = prefix[i] + bestAlong;
            if (cutLength < 1.0) {
                return tie; // пересечение и так у самой врезки
            }
            if (nearAvailableChamber(crossPt, plan) || insideBlocking(crossPt, ctx)) {
                return tie;
            }
            Set<Object> exempt = new HashSet<>();
            exempt.add(crossPipe.id);
            for (InputModel.ExistingPipe other : input.pipes) {
                if (other != crossPipe
                        && other.utm.distance(GeoUtil.GF.createPoint(crossPt)) < 1.5) {
                    exempt.add(other.id);
                }
            }
            double terminal = CostModel.chamberCost(
                    Math.max(crossPipe.diameter, costGrade.getDu()));
            double gain = cutLength * costGrade.getCostPerMeter()
                    + tie.terminalCostRub - terminal;
            if (gain <= 0) {
                return tie;
            }
            Coordinate rest = coords.get(i + 1);
            if (crossPt.distance(rest) > 0.05) {
                EdgeValidator.EdgeCheck check = validator.check(crossPt, rest, exempt, null, crossPt, crossPt);
                if (!check.valid) {
                    return tie;
                }
            }
            coords.subList(0, i + 1).clear();
            if (!coords.isEmpty() && crossPt.distance(coords.get(0)) <= 0.05) {
                coords.remove(0);
            }
            coords.add(0, crossPt);
            log.debug("Врезка перенесена в точку пересечения трубы {}: срезано {} м",
                    crossPipe.id, String.format("%.1f", cutLength));
            return new TieCandidate(TieCandidate.Kind.PIPE_POINT, crossPt, crossPipe.id,
                    terminal, exempt);
        }
        return tie;
    }

    /** Перенос врезки в основание перпендикуляра из последней вершины маршрута. */
    private TieCandidate refinePipeTie(NetworkPlan plan, TieCandidate tie, List<Coordinate> coords,
                                       EdgeValidator validator) {
        InputModel.ExistingPipe pipe = pipesById.get(tie.refId);
        if (pipe == null) {
            return tie;
        }
        Coordinate next = coords.get(1);
        Coordinate foot = org.locationtech.jts.operation.distance.DistanceOp
                .nearestPoints(pipe.utm, GeoUtil.GF.createPoint(next))[0];
        if (foot.distance(tie.location) < 0.5 || plan.findNode(foot) != null
                || existingPipeDegree(foot) + 1 > MAX_CHAMBER_CONNECTIONS
                || nearAvailableChamber(foot, plan)) {
            return tie;
        }
        EdgeValidator.EdgeCheck check = validator.check(next, foot, tie.exemptSpecialIds, null, foot, foot);
        if (!check.valid) {
            return tie;
        }
        coords.set(0, foot);
        return new TieCandidate(tie.kind, foot, tie.refId, tie.terminalCostRub, tie.exemptSpecialIds);
    }

    /** Чистка микро-рёбер, почти коллинеарных вершин, исправление поворотов больше 90°. */
    private List<Coordinate> postprocess(List<Coordinate> coords, EdgeValidator validator,
                                         Coordinate tieLocation, Coordinate sourceConnectionAt) {
        List<Coordinate> result = new ArrayList<>(coords);
        // микро-рёбра: промежуточная вершина ближе 0.25 м к соседней удаляется,
        // если спрямлённое ребро допустимо
        for (int i = 1; i + 1 < result.size(); ) {
            if (result.get(i).distance(result.get(i - 1)) < 0.25
                    || result.get(i).distance(result.get(i + 1)) < 0.25) {
                Coordinate allowTouch = i - 1 == 0 ? tieLocation : null;
                Coordinate sourceEnd = i - 1 == 0 && sourceConnectionAt != null
                        && result.get(i - 1).equals2D(sourceConnectionAt) ? sourceConnectionAt : null;
                EdgeValidator.EdgeCheck bridged =
                        validator.check(result.get(i - 1), result.get(i + 1), null, null, allowTouch, sourceEnd);
                if (bridged.valid) {
                    result.remove(i);
                    if (i > 1) {
                        i--;
                    }
                    continue;
                }
            }
            i++;
        }
        // почти коллинеарные вершины
        for (int i = 1; i + 1 < result.size(); ) {
            double turn = GeoUtil.turnAngleDeg(result.get(i - 1), result.get(i), result.get(i + 1));
            if (turn < 0.05) {
                result.remove(i);
            } else {
                i++;
            }
        }
        // повороты > 90°: срез угла (до двух итераций — срез может сдвинуть
        // проблему в соседнюю вершину)
        for (int pass = 0; pass < 2; pass++) {
            boolean changed = false;
            for (int i = 1; i + 1 < result.size(); i++) {
                double turn = GeoUtil.turnAngleDeg(result.get(i - 1), result.get(i), result.get(i + 1));
                if (turn <= 90.0 + 1e-6) {
                    continue;
                }
                Coordinate prev = result.get(i - 1), v = result.get(i), next = result.get(i + 1);
                for (double m : new double[]{0.5, 1, 2, 4, 8, 15}) {
                    if (prev.distance(v) < m * 1.2 || next.distance(v) < m * 1.2) {
                        continue;
                    }
                    Coordinate v1 = pointAt(v, prev, m);
                    Coordinate v2 = pointAt(v, next, m);
                    // A bevel creates two new internal points: neither inherits the source permission.
                    EdgeValidator.EdgeCheck check = validator.check(v1, v2, null, null,
                            i - 1 == 0 ? tieLocation : null);
                    if (check.valid) {
                        result.set(i, v1);
                        result.add(i + 1, v2);
                        changed = true;
                        break;
                    }
                }
            }
            if (!changed) {
                break;
            }
        }
        return result;
    }

    private static Coordinate pointAt(Coordinate from, Coordinate towards, double distance) {
        double d = from.distance(towards);
        double t = distance / d;
        return new Coordinate(from.x + (towards.x - from.x) * t,
                from.y + (towards.y - from.y) * t);
    }
}
