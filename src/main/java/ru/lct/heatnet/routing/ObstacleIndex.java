package ru.lct.heatnet.routing;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.operation.buffer.BufferOp;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.refdata.PipeGrade;
import ru.lct.heatnet.refdata.PipeTable;
import ru.lct.heatnet.refdata.RestrictionRule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Строит и кэширует контексты препятствий по условному диаметру новой сети.
 *
 * <p>Зона блокировки — ядро препятствия, раздутое на (минимальный отступ +
 * полуширина габарита − ε): осевая линия новой сети не должна попадать в неё.
 * Контур для вершин графа строится с запасом и упрощается, чтобы ограничить
 * размер графа видимости.</p>
 */
public class ObstacleIndex {

    private static final double BLOCK_EPS = 0.01;
    private static final double NODE_MARGIN = 1.0;
    private static final double NODE_SIMPLIFY = 0.35;

    private final InputModel input;
    private final ru.lct.heatnet.refdata.RestrictionRules rules;
    private final Map<Integer, GradeContext> cache = new HashMap<>();

    public ObstacleIndex(InputModel input) {
        this(input, ru.lct.heatnet.refdata.RestrictionRules.builtIn());
    }

    public ObstacleIndex(InputModel input, ru.lct.heatnet.refdata.RestrictionRules rules) {
        this.input = input;
        this.rules = rules;
    }

    public synchronized GradeContext forGrade(PipeGrade grade) {
        return cache.computeIfAbsent(grade.getDu(), du -> build(grade));
    }

    private GradeContext build(PipeGrade grade) {
        GradeContext ctx = new GradeContext(grade);
        double halfWidth = grade.getHalfWidthM();

        for (InputModel.Restriction r : input.restrictions) {
            RestrictionRule rule = rules.forType(r.restrictionType);
            double clearance = rule.clearanceForDu(grade.getDu());
            for (Geometry component : components(r.utm)) {
                if (rule.getMode() == RestrictionRule.Mode.FORBIDDEN) {
                    ctx.forbidden.add(forbiddenZone(r.id, r.restrictionType, component,
                            clearance + halfWidth));
                } else {
                    ctx.specials.add(specialObstacle(r.id, rule, component, halfWidth));
                }
            }
        }

        // Существующая тепловая сеть: пересечение без врезки — специальный проход
        RestrictionRule crossRule = rules.heatNetworkCrossing();
        for (InputModel.ExistingPipe pipe : input.pipes) {
            double ownHalf = PipeTable.byDuAtLeast(pipe.diameter).getHalfWidthM();
            double proximityDist = ownHalf + crossRule.getMinHorizontalDistanceM() + halfWidth;
            Geometry proximityGeom = buffer(pipe.utm, proximityDist - BLOCK_EPS);
            RestrictionRule.Vertical baseVertical = crossRule.getVertical();
            RestrictionRule.Vertical vertical = baseVertical == null ? null
                    : new RestrictionRule.Vertical(baseVertical.underOnly,
                        baseVertical.minTopDepthM, baseVertical.clearanceM,
                        baseVertical.obstacleTopDepthM,
                        PipeTable.byDuAtLeast(pipe.diameter).getHeightM());
            ctx.specials.add(new SpecialObstacle(pipe.id, "heat_network",
                    crossRule.getKSpec(), null, crossRule.getSpecialMarginM(), false,
                    pipe.utm, PreparedGeometryFactory.prepare(pipe.utm),
                    proximityGeom, PreparedGeometryFactory.prepare(proximityGeom),
                    null, null, vertical));
        }

        ctx.build();
        return ctx;
    }

    private ForbiddenZone forbiddenZone(Object id, String type, Geometry core, double inflate) {
        Geometry blocking = buffer(core, inflate - BLOCK_EPS);
        Geometry nodeZone = DouglasPeuckerSimplifier.simplify(
                buffer(core, inflate + NODE_MARGIN), NODE_SIMPLIFY);
        return new ForbiddenZone(id, type, core,
                PreparedGeometryFactory.prepare(blocking), nodeZone);
    }

    private SpecialObstacle specialObstacle(Object id, RestrictionRule rule,
                                            Geometry component, double halfWidth) {
        boolean polygonal = component instanceof Polygon;
        double proximityDist = rule.getMinHorizontalDistanceM() + halfWidth
                + (polygonal ? 0 : rule.getOwnHalfWidthM());
        Geometry proximityGeom = buffer(component, proximityDist - BLOCK_EPS);
        Geometry boundZone = null;
        if (polygonal) {
            boundZone = buffer(component, rule.getSpecialMarginM());
        }
        return new SpecialObstacle(id, rule.getType(), rule.getKSpec(),
                rule.getMinCrossingAngleDeg(), rule.getSpecialMarginM(), polygonal,
                component, PreparedGeometryFactory.prepare(component),
                proximityGeom, PreparedGeometryFactory.prepare(proximityGeom),
                boundZone, boundZone == null ? null : PreparedGeometryFactory.prepare(boundZone),
                rule.getVertical());
    }

    /**
     * Буфер со срезанными (mitre) углами — минимум лишних вершин, отступ
     * не занижается. Предел среза 1.42 гарантирует, что огибание угла контура
     * не потребует поворота больше ~90°.
     */
    static Geometry buffer(Geometry g, double distance) {
        BufferParameters params = new BufferParameters(
                8, BufferParameters.CAP_SQUARE, BufferParameters.JOIN_MITRE, 1.42);
        return BufferOp.bufferOp(g, distance, params);
    }

    private static List<Geometry> components(Geometry g) {
        List<Geometry> result = new ArrayList<>();
        for (int i = 0; i < g.getNumGeometries(); i++) {
            Geometry component = g.getGeometryN(i);
            if (component instanceof Polygon || component instanceof LineString) {
                result.add(component);
            }
        }
        return result;
    }
}
