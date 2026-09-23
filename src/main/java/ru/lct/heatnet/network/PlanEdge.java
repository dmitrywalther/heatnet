package ru.lct.heatnet.network;

import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.refdata.PipeGrade;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Ребро планируемой сети: полилиния между двумя узлами (повороты — внутренние вершины). */
public class PlanEdge {

    public PlanNode a;
    public PlanNode b;
    /** Координаты полилинии от a к b. */
    public List<Coordinate> coords;

    /** ОКС, запитанные через это ребро (заполняется при расчёте потоков). */
    public final Set<Object> downstreamOks = new LinkedHashSet<>();
    public double flowTph;
    public PipeGrade grade;

    public PlanEdge(PlanNode a, PlanNode b, List<Coordinate> coords) {
        this.a = a;
        this.b = b;
        this.coords = coords;
    }

    public double length() {
        double sum = 0;
        for (int i = 0; i + 1 < coords.size(); i++) {
            sum += coords.get(i).distance(coords.get(i + 1));
        }
        return sum;
    }

    public PlanNode other(PlanNode n) {
        return n == a ? b : a;
    }

    public List<Coordinate> coordsFrom(PlanNode start) {
        if (start == a) {
            return coords;
        }
        List<Coordinate> reversed = new ArrayList<>(coords);
        java.util.Collections.reverse(reversed);
        return reversed;
    }
}
