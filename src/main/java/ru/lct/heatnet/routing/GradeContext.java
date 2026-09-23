package ru.lct.heatnet.routing;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.strtree.STRtree;
import ru.lct.heatnet.refdata.PipeGrade;

import java.util.ArrayList;
import java.util.List;

/**
 * Индекс препятствий, раздутых под конкретный условный диаметр новой сети
 * (отступы и полуширина габарита зависят от ДУ).
 */
public class GradeContext {

    public final PipeGrade grade;
    public final List<ForbiddenZone> forbidden = new ArrayList<>();
    public final STRtree forbiddenTree = new STRtree();
    public final List<SpecialObstacle> specials = new ArrayList<>();
    public final STRtree specialTree = new STRtree();

    /**
     * Кэш статической части проверки рёбер (препятствия не меняются в рамках
     * контекста; барьеры построенной сети проверяются отдельно).
     */
    public final java.util.Map<EdgeKey, EdgeValidator.EdgeCheck> staticEdgeCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private volatile List<Coordinate> unblockedNodes;

    public GradeContext(PipeGrade grade) {
        this.grade = grade;
    }

    /** Ключ ребра по координатам концов (канонический порядок). */
    public static final class EdgeKey {
        final double x1, y1, x2, y2;

        public EdgeKey(Coordinate a, Coordinate b) {
            boolean ordered = a.x < b.x || (a.x == b.x && a.y <= b.y);
            Coordinate p = ordered ? a : b;
            Coordinate q = ordered ? b : a;
            this.x1 = p.x;
            this.y1 = p.y;
            this.x2 = q.x;
            this.y2 = q.y;
        }

        /** true, если ключ построен в направлении (a -> b) без перестановки. */
        public static boolean isCanonical(Coordinate a, Coordinate b) {
            return a.x < b.x || (a.x == b.x && a.y <= b.y);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof EdgeKey)) {
                return false;
            }
            EdgeKey k = (EdgeKey) o;
            return x1 == k.x1 && y1 == k.y1 && x2 == k.x2 && y2 == k.y2;
        }

        @Override
        public int hashCode() {
            long h = Double.doubleToLongBits(x1);
            h = h * 31 + Double.doubleToLongBits(y1);
            h = h * 31 + Double.doubleToLongBits(x2);
            h = h * 31 + Double.doubleToLongBits(y2);
            return (int) (h ^ (h >>> 32));
        }
    }

    void build() {
        for (ForbiddenZone z : forbidden) {
            forbiddenTree.insert(z.blocking.getGeometry().getEnvelopeInternal(), z);
        }
        for (SpecialObstacle s : specials) {
            specialTree.insert(s.proximityGeom.getEnvelopeInternal(), s);
        }
        forbiddenTree.build();
        specialTree.build();
        this.unblockedNodes = computeUnblockedNodes();
    }

    @SuppressWarnings("unchecked")
    public List<ForbiddenZone> forbiddenIn(Envelope env) {
        return forbiddenTree.query(env);
    }

    @SuppressWarnings("unchecked")
    public List<SpecialObstacle> specialsIn(Envelope env) {
        return specialTree.query(env);
    }

    /** Свободные (вне чужих зон блокировки) вершины графа видимости. */
    private List<Coordinate> computeUnblockedNodes() {
        List<Coordinate> all = new ArrayList<>();
        for (ForbiddenZone z : forbidden) {
            for (Coordinate c : z.nodeZone.getCoordinates()) {
                all.add(c);
            }
        }
        for (SpecialObstacle s : specials) {
            // огибание полигональных спецпрепятствий (например, дороги при
            // невозможности пересечь под нужным углом)
            if (s.polygonal && s.boundZone != null) {
                for (Coordinate c : s.boundZone.getCoordinates()) {
                    all.add(c);
                }
            }
        }
        List<Coordinate> free = new ArrayList<>(all.size());
        for (Coordinate c : all) {
            boolean blocked = false;
            for (ForbiddenZone z : forbiddenIn(new Envelope(c))) {
                if (z.containsPoint(c)) {
                    blocked = true;
                    break;
                }
            }
            if (!blocked) {
                free.add(c);
            }
        }
        return free;
    }

    /** Вершины графа видимости в пределах коридора поиска. */
    public List<Coordinate> graphNodesIn(Envelope corridor) {
        List<Coordinate> nodes = new ArrayList<>();
        for (Coordinate c : unblockedNodes) {
            if (corridor.contains(c)) {
                nodes.add(c);
            }
        }
        return nodes;
    }
}
