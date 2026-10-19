package ru.lct.heatnet.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.network.NetworkAssembler;
import ru.lct.heatnet.network.NetworkPlan;
import ru.lct.heatnet.network.PlanEdge;
import ru.lct.heatnet.network.PlanNode;
import ru.lct.heatnet.network.VariantResult;
import ru.lct.heatnet.network.VariantDiagnostic;
import ru.lct.heatnet.routing.ObstacleIndex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Формирование совместного и содержательно отличающегося альтернативного варианта
 * и их ранжирование по итоговому показателю S.
 */
public class TracingEngine {

    private static final Logger log = LoggerFactory.getLogger(TracingEngine.class);
    /** Бюджет дорогих вторых перепрокладок на один запуск, не норматив. */
    private static final int MAX_JOINT_RECONNECTS = 16;

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
        List<VariantDiagnostic> diagnostics = new ArrayList<>();

        // Совместный вариант строится несколькими порядками вставки ОКС —
        // жадная эвристика чувствительна к порядку; лучший берётся как v1.
        // Все совместные кандидаты считаются параллельно (контексты
        // препятствий и кэши потокобезопасны, PreparedGeometry в JTS
        // рассчитан на многопоточное использование).
        List<java.util.concurrent.CompletableFuture<BuiltVariant>> sharedCandidates =
                new ArrayList<>();
        for (VariantPlanner.InsertionOrder order : VariantPlanner.InsertionOrder.values()) {
            sharedCandidates.add(java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> buildVariant(planner, assembler, VariantPlanner.Strategy.SHARED,
                            null, "v1", "shared", order)));
        }
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
        // 1-opt улучшение лучшего совместного варианта: каждый ОКС по очереди
        // отключается и переприкладывается против финальной сети
        b1 = localImprove(input, planner, assembler, b1);

        VariantResult v1 = b1.result;
        variants.add(v1);
        kept.add(b1);
        // Вариант 3: альтернативные точки присоединения
        Set<Object> usedTies = tieRefs(b1.plan);
        if (!usedTies.isEmpty()) {
            BuiltVariant b3 = buildVariant(planner, assembler,
                    VariantPlanner.Strategy.ALTERNATIVE, usedTies, "v3", "alternative",
                    VariantPlanner.InsertionOrder.FLOW_DESC);
            if (acceptsAlternative(b3.result, v1, variants)) {
                variants.add(b3.result);
                kept.add(b3);
            } else if (!b3.result.isValid()) {
                log.warn("Альтернативный вариант v3 исключён из выдачи: после повторного построения "
                        + "остались нарушения геометрических ограничений");
                recordRejection(b3.result, "base", diagnostics);
            }
        }

        for (VariantResult v : variants) {
            if (v.clearanceViolated) {
                log.warn("Вариант {} содержит участки с нарушением отступов после подбора ДУ", v.variantId);
            }
        }

        return finalizeVariants(kept, assembler, diagnostics);
    }

    TracingResult finalizeVariants(List<BuiltVariant> kept, NetworkAssembler assembler) {
        return finalizeVariants(kept, assembler, new ArrayList<>());
    }

    private TracingResult finalizeVariants(List<BuiltVariant> kept, NetworkAssembler assembler,
                                           List<VariantDiagnostic> diagnostics) {
        List<VariantResult> variants = new ArrayList<>();
        for (BuiltVariant b : kept) {
            if (b.result.isValid()) variants.add(b.result);
            else recordRejection(b.result, "base", diagnostics);
        }
        variants.sort(Comparator.comparingDouble(v -> v.score));
        for (int i = 0; i < variants.size(); i++) {
            variants.get(i).rank = i + 1;
        }

        // Дополнительный режим с учётом глубины: вертикальное планирование
        // вдоль тех же маршрутов, отдельный набор вариантов и ранжирование
        List<VariantResult> depthVariants = new ArrayList<>();
        for (BuiltVariant b : kept) {
            if (!b.result.isValid()) continue;
            // ID сохраняет соответствие базовой стратегии и не зависит от rank
            // или отсутствия других вариантов в отфильтрованном списке.
            VariantResult depth = assembler.assemble(b.plan, "d" + b.result.variantId.substring(1),
                    b.result.strategyName, true);
            if (depth.isValid()) depthVariants.add(depth);
            else recordRejection(depth, "depth", diagnostics);
        }
        depthVariants.sort(Comparator.comparingDouble(v -> v.score));
        for (int i = 0; i < depthVariants.size(); i++) {
            depthVariants.get(i).rank = i + 1;
        }
        return new TracingResult(variants, depthVariants, diagnostics);
    }

    private static void recordRejection(VariantResult result, String mode, List<VariantDiagnostic> diagnostics) {
        if (!result.diagnostics.isEmpty()) {
            diagnostics.addAll(result.diagnostics);
        } else {
            diagnostics.add(new VariantDiagnostic(result.variantId, mode, "GEOMETRY_CONFLICT",
                    "Вариант исключён из-за нарушения геометрических ограничений."));
        }
        log.warn("Вариант {} ({}) исключён из выдачи: не выполнены проверки допустимости", result.variantId, mode);
    }

    boolean acceptsAlternative(VariantResult candidate, VariantResult shared, List<VariantResult> retained) {
        return candidate.isValid()
                && (!shared.isValid() || candidate.unconnectedOksIds.size() <= shared.unconnectedOksIds.size())
                && isMeaningfullyDifferentFromAll(retained, candidate);
    }

    static final class BuiltVariant {
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
     * Выгодная проба с изломом после удаления стыка может быть дополнена
     * перепрокладкой одной зависимой ветви. Принимается только готовая пара.
     */
    private BuiltVariant localImprove(InputModel input, VariantPlanner planner,
                                      NetworkAssembler assembler, BuiltVariant start) {
        BuiltVariant current = start;
        JointBudget joint = new JointBudget();
        for (int pass = 0; pass < 2; pass++) {
            boolean improved = false;
            for (InputModel.OksPoint oks : input.oksPoints) {
                if (current.result.unconnectedOksIds.contains(oks.id)) {
                    continue;
                }
                NetworkPlan disconnected = current.plan.copy();
                if (!disconnected.removeBranch(oks.id)) {
                    continue;
                }
                for (VariantPlanner.ReconnectCandidate candidate : planner.reconnectCandidates(disconnected, oks)) {
                    VariantResult trialResult = assembler.assemble(candidate.plan,
                            current.result.variantId, current.result.strategyName);
                    if (acceptsReconnectCandidate(trialResult, current.result, candidate.alternativeEntry)) {
                        log.info("1-opt: ОКС {}, другая близкая стена={}: score {} -> {}, стоимость {} -> {}",
                                oks.id, candidate.alternativeEntry, current.result.score, trialResult.score,
                                current.result.calculatedCost, trialResult.calculatedCost);
                        current = new BuiltVariant(candidate.plan, trialResult);
                        improved = true;
                    } else if (!current.result.clearanceViolated && trialResult.clearanceViolated
                            && trialResult.score < current.result.score
                            && trialResult.calculatedCost <= current.result.calculatedCost + 1e-6
                            && joint.attempted < MAX_JOINT_RECONNECTS) {
                        BuiltVariant paired = tryJointReconnect(input, planner, assembler, current,
                                disconnected, candidate.plan, oks.id, joint);
                        if (paired != null) {
                            current = paired;
                            improved = true;
                        }
                    }
                }
            }
            if (!improved) {
                break;
            }
        }
        if (current != start) {
            log.info("1-opt итог: score {} -> {}", start.result.score, current.result.score);
        }
        if (joint.attempted > 0) {
            log.info("Совместные перестройки: вторых перепрокладок {}/{}, принято {}",
                    joint.attempted, MAX_JOINT_RECONNECTS, joint.accepted);
        }
        return current;
    }

    private BuiltVariant tryJointReconnect(InputModel input, VariantPlanner planner,
                                            NetworkAssembler assembler, BuiltVariant current,
                                            NetworkPlan disconnected, NetworkPlan firstTrial,
                                            Object primaryId, JointBudget budget) {
        BuiltVariant best = current;
        for (InputModel.OksPoint partner : JointBranchRepair.partners(input, disconnected, firstTrial, primaryId)) {
            if (budget.attempted >= MAX_JOINT_RECONNECTS) break;
            if (current.result.unconnectedOksIds.contains(partner.id)) continue;
            NetworkPlan paired = firstTrial.copy();
            if (!paired.removeBranch(partner.id) || JointBranchRepair.hasInvalidTurns(paired)) continue;
            budget.attempted++;
            // Только обычная перепрокладка партнёра: ограничиваем число дорогих A*.
            if (!planner.reconnect(paired, partner)
                    || !JointBranchRepair.preservesConnections(input, current.result, paired)) continue;
            VariantResult result = assembler.assemble(paired,
                    current.result.variantId, current.result.strategyName);
            // Для любой пары обязательны полная допустимость, меньший S и цена не выше.
            if (acceptsReconnectCandidate(result, best.result, true)) {
                log.info("Совместная перестройка ОКС {} + {}: score {} -> {}, стоимость {} -> {}",
                        primaryId, partner.id, best.result.score, result.score,
                        best.result.calculatedCost, result.calculatedCost);
                best = new BuiltVariant(paired, result);
                budget.accepted++;
            }
        }
        return best == current ? null : best;
    }

    private static final class JointBudget {
        int attempted;
        int accepted;
    }

    boolean acceptsReconnectCandidate(VariantResult candidate, VariantResult current,
                                      boolean alternativeEntry) {
        // Переподключение не должно закреплять недопустимую геометрию,
        // даже если оно подключает больше ОКС или снижает показатель стоимости.
        if (!candidate.isValid()) {
            return false;
        }
        // Дополнительный внутренний участок оправдываем полной экономикой,
        // сохраняя прежний критерий для обычного переподключения.
        if (alternativeEntry && (!current.unconnectedOksIds.containsAll(candidate.unconnectedOksIds)
                || !(candidate.score < current.score)
                || candidate.calculatedCost > current.calculatedCost + 1e-6)) {
            return false;
        }
        return better(candidate, current);
    }

    /** Допустимость обязательна; среди допустимых вариантов важнее полнота, затем показатель. */
    private boolean better(VariantResult candidate, VariantResult current) {
        if (candidate.isValid() != current.isValid()) {
            return candidate.isValid();
        }
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
        boolean retryBetter = better(retry, result);
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
            if (v.isValid() && !isMeaningfullyDifferent(v, candidate)) {
                return false;
            }
        }
        return true;
    }
}
