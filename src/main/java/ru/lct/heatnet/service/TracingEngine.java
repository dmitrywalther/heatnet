package ru.lct.heatnet.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.network.NetworkAssembler;
import ru.lct.heatnet.network.NetworkPlan;
import ru.lct.heatnet.network.PlanEdge;
import ru.lct.heatnet.network.PlanNode;
import ru.lct.heatnet.network.VariantResult;
import ru.lct.heatnet.routing.ObstacleIndex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Формирование до трёх содержательно различающихся вариантов подключения
 * и их ранжирование по итоговому показателю S.
 */
public class TracingEngine {

    private static final Logger log = LoggerFactory.getLogger(TracingEngine.class);

    private final ru.lct.heatnet.refdata.RestrictionRules rules;

    public TracingEngine() {
        this(ru.lct.heatnet.refdata.RestrictionRules.builtIn());
    }

    public TracingEngine(ru.lct.heatnet.refdata.RestrictionRules rules) {
        this.rules = rules;
    }

    public TracingResult run(InputModel input) {
        ObstacleIndex obstacles = new ObstacleIndex(input, rules);
        VariantPlanner planner = new VariantPlanner(input, obstacles);
        NetworkAssembler assembler = new NetworkAssembler(obstacles);

        List<VariantResult> variants = new ArrayList<>();
        List<BuiltVariant> kept = new ArrayList<>();

        // Совместный вариант строится несколькими порядками вставки ОКС —
        // жадная эвристика чувствительна к порядку; лучший берётся как v1.
        // Все кандидаты и независимый вариант считаются параллельно (контексты
        // препятствий и кэши потокобезопасны, PreparedGeometry в JTS
        // рассчитан на многопоточное использование).
        List<java.util.concurrent.CompletableFuture<BuiltVariant>> sharedCandidates =
                new ArrayList<>();
        for (VariantPlanner.InsertionOrder order : VariantPlanner.InsertionOrder.values()) {
            sharedCandidates.add(java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> buildVariant(planner, assembler, VariantPlanner.Strategy.SHARED,
                            null, "v1", "shared", order)));
        }
        java.util.concurrent.CompletableFuture<BuiltVariant> f2 =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> buildVariant(
                        planner, assembler, VariantPlanner.Strategy.INDEPENDENT, null,
                        "v2", "independent", VariantPlanner.InsertionOrder.FLOW_DESC));

        BuiltVariant b1 = null;
        for (int i = 0; i < sharedCandidates.size(); i++) {
            BuiltVariant candidate = sharedCandidates.get(i).join();
            log.info("Порядок вставки {}: score={}, не подключено {}",
                    VariantPlanner.InsertionOrder.values()[i], candidate.result.score,
                    candidate.result.unconnectedOksIds.size());
            if (b1 == null || better(candidate.result, b1.result)) {
                b1 = candidate;
            }
        }
        BuiltVariant b2 = f2.join();

        // 1-opt улучшение лучшего совместного варианта: каждый ОКС по очереди
        // отключается и переприкладывается против финальной сети
        b1 = localImprove(input, planner, assembler, b1);

        VariantResult v1 = b1.result;
        variants.add(v1);
        kept.add(b1);
        if (isMeaningfullyDifferent(v1, b2.result)) {
            variants.add(b2.result);
            kept.add(b2);
        }

        // Вариант 3: альтернативные точки присоединения
        Set<Object> usedTies = tieRefs(b1.plan);
        if (!usedTies.isEmpty()) {
            BuiltVariant b3 = buildVariant(planner, assembler,
                    VariantPlanner.Strategy.ALTERNATIVE, usedTies, "v3", "alternative",
                    VariantPlanner.InsertionOrder.FLOW_DESC);
            boolean sameConnected = b3.result.unconnectedOksIds.size() <= v1.unconnectedOksIds.size();
            if (sameConnected && isMeaningfullyDifferentFromAll(variants, b3.result)) {
                variants.add(b3.result);
                kept.add(b3);
            }
        }

        for (VariantResult v : variants) {
            if (v.clearanceViolated) {
                log.warn("Вариант {} содержит участки с нарушением отступов после подбора ДУ", v.variantId);
            }
        }

        variants.sort(Comparator.comparingDouble(v -> v.score));
        for (int i = 0; i < variants.size(); i++) {
            variants.get(i).rank = i + 1;
        }

        // Дополнительный режим с учётом глубины: вертикальное планирование
        // вдоль тех же маршрутов, отдельный набор вариантов и ранжирование
        List<VariantResult> depthVariants = new ArrayList<>();
        for (int i = 0; i < kept.size(); i++) {
            BuiltVariant b = kept.get(i);
            depthVariants.add(assembler.assemble(b.plan, "d" + (i + 1),
                    b.result.strategyName, true));
        }
        depthVariants.sort(Comparator.comparingDouble(v -> v.score));
        for (int i = 0; i < depthVariants.size(); i++) {
            depthVariants.get(i).rank = i + 1;
        }
        return new TracingResult(variants, depthVariants);
    }

    private static final class BuiltVariant {
        final NetworkPlan plan;
        final VariantResult result;

        BuiltVariant(NetworkPlan plan, VariantResult result) {
            this.plan = plan;
            this.result = result;
        }
    }

    /**
     * Строит вариант; если после подбора ДУ итоговый габарит вырос и отступы
     * нарушены — перезапускает построение с расчётным габаритом по
     * максимальному фактическому ДУ.
     */
    /**
     * 1-opt: последовательная перепрокладка каждого подключённого ОКС против
     * итоговой сети. Пробы выполняются на копии плана и принимаются только
     * при улучшении показателя; до двух проходов, пока есть прогресс.
     */
    private BuiltVariant localImprove(InputModel input, VariantPlanner planner,
                                      NetworkAssembler assembler, BuiltVariant start) {
        BuiltVariant current = start;
        for (int pass = 0; pass < 2; pass++) {
            boolean improved = false;
            for (InputModel.OksPoint oks : input.oksPoints) {
                if (current.result.unconnectedOksIds.contains(oks.id)) {
                    continue;
                }
                NetworkPlan trial = current.plan.copy();
                if (!trial.removeBranch(oks.id)) {
                    continue;
                }
                if (!planner.reconnect(trial, oks)) {
                    continue;
                }
                VariantResult trialResult = assembler.assemble(trial,
                        current.result.variantId, current.result.strategyName);
                if (better(trialResult, current.result)) {
                    log.info("1-opt: перепрокладка ОКС {} улучшила score {} -> {}",
                            oks.id, current.result.score, trialResult.score);
                    current = new BuiltVariant(trial, trialResult);
                    improved = true;
                }
            }
            if (!improved) {
                break;
            }
        }
        if (current != start) {
            log.info("1-opt итог: score {} -> {}", start.result.score, current.result.score);
        }
        return current;
    }

    /** Полнота подключения важнее чистоты отступов, чистота — важнее показателя. */
    private boolean better(VariantResult candidate, VariantResult current) {
        if (candidate.unconnectedOksIds.size() != current.unconnectedOksIds.size()) {
            return candidate.unconnectedOksIds.size() < current.unconnectedOksIds.size();
        }
        if (candidate.clearanceViolated != current.clearanceViolated) {
            return !candidate.clearanceViolated;
        }
        return candidate.score < current.score;
    }

    private BuiltVariant buildVariant(VariantPlanner planner, NetworkAssembler assembler,
                                      VariantPlanner.Strategy strategy, Set<Object> banned,
                                      String variantId, String name,
                                      VariantPlanner.InsertionOrder order) {
        long start = System.currentTimeMillis();
        NetworkPlan plan = planner.plan(strategy, banned, null, order);
        long planned = System.currentTimeMillis();
        VariantResult result = assembler.assemble(plan, variantId, name);
        log.info("Вариант {} [{}] порядок {}: маршрутизация {} мс, сборка {} мс", variantId,
                name, order, planned - start, System.currentTimeMillis() - planned);
        if (!result.clearanceViolated) {
            return new BuiltVariant(plan, result);
        }
        ru.lct.heatnet.refdata.PipeGrade maxGrade = null;
        for (ru.lct.heatnet.network.PlanEdge e : plan.edges) {
            if (e.grade != null && (maxGrade == null || e.grade.getDu() > maxGrade.getDu())) {
                maxGrade = e.grade;
            }
        }
        log.info("Вариант {}: перезапуск с расчётным габаритом ДУ {}", variantId,
                maxGrade != null ? maxGrade.getDu() : null);
        NetworkPlan retryPlan = planner.plan(strategy, banned, maxGrade, order);
        VariantResult retry = assembler.assemble(retryPlan, variantId, name);
        // выбираем лучший из двух: сначала полнота подключения, затем отсутствие
        // нарушений, затем показатель
        boolean retryBetter = retry.unconnectedOksIds.size() < result.unconnectedOksIds.size()
                || (retry.unconnectedOksIds.size() == result.unconnectedOksIds.size()
                    && (!retry.clearanceViolated && result.clearanceViolated
                        || retry.clearanceViolated == result.clearanceViolated
                           && retry.score < result.score));
        return retryBetter ? new BuiltVariant(retryPlan, retry) : new BuiltVariant(plan, result);
    }

    /** Места присоединения плана: id труб и существующих камер. */
    private Set<Object> tieRefs(NetworkPlan plan) {
        Set<Object> refs = new HashSet<>();
        for (PlanNode node : plan.tieInsToExisting.keySet()) {
            refs.add(node.inputId);
        }
        for (PlanNode node : plan.nodes) {
            if (node.kind == PlanNode.Kind.NEW_CHAMBER && node.splitPipeDu != null) {
                // Найти трубу можно по exempt-набору; используем все исключения узла
                refs.addAll(node.exemptSpecialIds);
            }
        }
        return refs;
    }

    private boolean isMeaningfullyDifferent(VariantResult a, VariantResult b) {
        return Math.abs(a.newNetworkLength - b.newNetworkLength) > 1.0
                || Math.abs(a.constructionCost - b.constructionCost) > 1000
                || a.segments.size() != b.segments.size();
    }

    private boolean isMeaningfullyDifferentFromAll(List<VariantResult> existing, VariantResult candidate) {
        for (VariantResult v : existing) {
            if (!isMeaningfullyDifferent(v, candidate)) {
                return false;
            }
        }
        return true;
    }
}
