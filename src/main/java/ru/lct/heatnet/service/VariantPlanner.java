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

    private final InputModel input;
    private final ObstacleIndex obstacles;
    /**
     * Расчётный габарит для проверки отступов: ДУ по суммарному расходу всех
     * точек подключения (максимум, который может собрать общая магистраль),
     * но не выше ДУ 400 — дальше растёт отступ от ОКС (7/9 м) и излишняя
     * консервативность может отрезать узкие проходы. Ширина габарита ДУ 400
     * отличается от малых ДУ менее чем на полметра, зато маршруты не требуют
     * перестроения после подбора фактических диаметров.
     */
    private final PipeGrade clearanceGrade;

    /** Существующая степень камер (число примыкающих существующих участков). */
    private final Map<Object, Integer> chamberDegrees = new HashMap<>();
    /** Трубы, чьи зоны близости не проверяются при примыкании к камере. */
    private final Map<Object, Set<Object>> chamberExemptPipes = new HashMap<>();
    private final Map<Object, InputModel.ExistingPipe> pipesById = new HashMap<>();
    /** Пространственный индекс существующих труб для поиска пересечений. */
    private final org.locationtech.jts.index.strtree.STRtree pipeIndex =
            new org.locationtech.jts.index.strtree.STRtree();

    public VariantPlanner(InputModel input, ObstacleIndex obstacles) {
        this.input = input;
        this.obstacles = obstacles;
        double totalFlow = 0;
        for (InputModel.OksPoint oks : input.oksPoints) {
            totalFlow += oks.flowTph;
        }
        PipeGrade byTotal = PipeTable.minGradeForFlow(totalFlow);
        this.clearanceGrade = byTotal.getDu() > 400 ? PipeTable.byDu(400) : byTotal;
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
        EdgeValidator validator = new EdgeValidator(ctx);
        for (PlanEdge built : plan.edges) {
            if (built.coords.size() >= 2) {
                validator.addBarrier(GeoUtil.polyline(built.coords));
            }
        }
        return connectOks(plan, oks, Strategy.SHARED, null, ctx, validator);
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
        PipeGrade costGrade = PipeTable.minGradeForFlow(oks.flowTph);

        OksApproach approach = OksApproach.build(oks, ctx, validator);
        if (approach == null) {
            log.warn("ОКС {}: не найден допустимый подход к точке подключения", oks.id);
            return false;
        }

        List<TieCandidate> allTargets =
                buildCandidates(plan, ctx, costGrade, strategy, bannedTieRefs);
        List<TieCandidate> targets = pruneTargets(allTargets, approach.approachNodes.get(0));
        if (targets.isEmpty()) {
            log.warn("ОКС {}: нет кандидатов точек присоединения", oks.id);
            return false;
        }

        // стартовая стоимость каждого узла подхода — длина финального участка
        Coordinate p = oks.utm.getCoordinate();
        double[] startCosts = new double[approach.approachNodes.size()];
        for (int i = 0; i < startCosts.length; i++) {
            startCosts[i] = approach.approachNodes.get(i).distance(p);
        }

        // Независимые маршруты (вариант для сравнения, а не лидер рейтинга)
        // считаем в «быстром» режиме: бюджет раскрытий и ограничение длины
        // ребра; при неудаче деградация к совместному подключению.
        // Основные варианты (shared/alternative) считаются без ограничений.
        boolean fast = strategy == Strategy.INDEPENDENT;
        VisibilityRouter router = new VisibilityRouter(ctx, validator,
                costGrade.getCostPerMeter(), fast ? 800 : 0, fast ? 300 : 0);
        VisibilityRouter.RoutedPath routed =
                router.route(approach.approachNodes, startCosts, null, targets);
        if (routed == null && allTargets.size() > targets.size()) {
            // близкие цели могли оказаться заблокированными — пробуем без
            // отсечения дальних кандидатов
            routed = router.route(approach.approachNodes, startCosts, null, allTargets);
        }
        if (routed == null && strategy == Strategy.INDEPENDENT) {
            // коридор для независимого маршрута занят — допускаем совместное
            // подключение, чтобы не оставлять ОКС без сети
            List<TieCandidate> sharedTargets =
                    buildCandidates(plan, ctx, costGrade, Strategy.SHARED, bannedTieRefs);
            routed = router.route(approach.approachNodes, startCosts, null, sharedTargets);
        }
        if (routed == null) {
            log.warn("ОКС {}: маршрут не найден ({} кандидатов, {} узлов подхода)",
                    oks.id, targets.size(), approach.approachNodes.size());
            return false;
        }

        // уточнение положения врезки в трубу: перпендикулярное основание
        TieCandidate tie = routed.tie;
        List<Coordinate> coords = new ArrayList<>(routed.coords); // [tie ... approach]
        if (tie.kind == TieCandidate.Kind.PIPE_POINT && coords.size() >= 2) {
            Coordinate before = tie.location;
            tie = refinePipeTie(tie, coords, validator);
            if (!tie.location.equals2D(before)) {
                // перенос основания мог оставить объезд, который от новой точки
                // врезки уже не нужен: спрямляем к самой дальней достижимой
                // вершине маршрута
                for (int j = coords.size() - 1; j >= 2; j--) {
                    EdgeValidator.EdgeCheck cut = validator.check(coords.get(j), coords.get(0),
                            tie.exemptSpecialIds, null, tie.location);
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

        coords = postprocess(coords, validator, tie.location);

        // --- интеграция в план ---
        PlanNode tieNode = resolveTieNode(plan, tie);
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


    private PlanNode resolveTieNode(NetworkPlan plan, TieCandidate tie) {
        switch (tie.kind) {
            case EXISTING_CHAMBER: {
                InputModel.ExistingChamber chamber = findChamber(tie.refId);
                if (chamber == null) {
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
                if (target == null) {
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
            int added = node != null ? plan.tieInsToExisting.getOrDefault(node, 0) : 0;
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
                        && plan.degree(node) < MAX_CHAMBER_CONNECTIONS
                        && !insideBlocking(node.utm, ctx)) {
                    candidates.add(new TieCandidate(TieCandidate.Kind.NEW_NETWORK_NODE,
                            node.utm, node, 0, node.exemptSpecialIds));
                }
            }
            for (PlanEdge edge : plan.edges) {
                List<Coordinate> pts = sampleEdge(edge);
                double terminal = CostModel.chamberCost(assumed.getDu());
                for (Coordinate c : pts) {
                    if (!insideBlocking(c, ctx)) {
                        candidates.add(new TieCandidate(TieCandidate.Kind.NEW_NETWORK_EDGE_POINT,
                                c, edge, terminal, null));
                    }
                }
            }
        }
        return candidates;
    }

    /**
     * Отсечение дальних кандидатов присоединения: цель в разы дальше ближайшей
     * не может выиграть по показателю S (разница терминальных стоимостей камер
     * и врезок эквивалентна ~20 м трассы). Кратно сокращает коридор поиска.
     */
    private List<TieCandidate> pruneTargets(List<TieCandidate> targets, Coordinate from) {
        if (targets.size() <= 40) {
            return targets;
        }
        double dmin = Double.POSITIVE_INFINITY;
        for (TieCandidate t : targets) {
            dmin = Math.min(dmin, t.location.distance(from));
        }
        double cutoff = Math.max(2 * dmin, dmin + 150);
        List<TieCandidate> near = new ArrayList<>();
        for (TieCandidate t : targets) {
            if (t.location.distance(from) <= cutoff) {
                near.add(t);
            }
        }
        if (near.size() > 250) {
            near.sort(Comparator.comparingDouble(t -> t.location.distance(from)));
            near = new ArrayList<>(near.subList(0, 250));
        }
        return near;
    }

    private boolean nearAvailableChamber(Coordinate c, NetworkPlan plan) {
        for (InputModel.ExistingChamber chamber : input.chambers) {
            if (chamber.utm.getCoordinate().distance(c) < CHAMBER_RULE_RADIUS) {
                int degree = chamberDegrees.getOrDefault(chamber.id, 0);
                PlanNode node = plan.findNode(chamber.utm.getCoordinate());
                int added = node != null ? plan.tieInsToExisting.getOrDefault(node, 0) : 0;
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
                EdgeValidator.EdgeCheck check = validator.check(crossPt, rest, exempt, null, crossPt);
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
    private TieCandidate refinePipeTie(TieCandidate tie, List<Coordinate> coords,
                                       EdgeValidator validator) {
        InputModel.ExistingPipe pipe = pipesById.get(tie.refId);
        if (pipe == null) {
            return tie;
        }
        Coordinate next = coords.get(1);
        Coordinate foot = org.locationtech.jts.operation.distance.DistanceOp
                .nearestPoints(pipe.utm, GeoUtil.GF.createPoint(next))[0];
        if (foot.distance(tie.location) < 0.5) {
            return tie;
        }
        EdgeValidator.EdgeCheck check = validator.check(next, foot, tie.exemptSpecialIds, null, foot);
        if (!check.valid) {
            return tie;
        }
        coords.set(0, foot);
        return new TieCandidate(tie.kind, foot, tie.refId, tie.terminalCostRub, tie.exemptSpecialIds);
    }

    /** Чистка микро-рёбер, почти коллинеарных вершин, исправление поворотов больше 90°. */
    private List<Coordinate> postprocess(List<Coordinate> coords, EdgeValidator validator,
                                         Coordinate tieLocation) {
        List<Coordinate> result = new ArrayList<>(coords);
        // микро-рёбра: промежуточная вершина ближе 0.25 м к соседней удаляется,
        // если спрямлённое ребро допустимо
        for (int i = 1; i + 1 < result.size(); ) {
            if (result.get(i).distance(result.get(i - 1)) < 0.25
                    || result.get(i).distance(result.get(i + 1)) < 0.25) {
                Coordinate allowTouch = i - 1 == 0 ? tieLocation : null;
                EdgeValidator.EdgeCheck bridged =
                        validator.check(result.get(i - 1), result.get(i + 1), null, null, allowTouch);
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
