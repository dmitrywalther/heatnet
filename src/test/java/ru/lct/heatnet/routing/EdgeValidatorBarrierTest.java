package ru.lct.heatnet.routing;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.refdata.PipeTable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdgeValidatorBarrierTest {

    private EdgeValidator validatorWithBarrier() {
        GradeContext ctx = new GradeContext(PipeTable.byDu(100));
        ctx.build();
        EdgeValidator validator = new EdgeValidator(ctx);
        validator.addBarrier(GeoUtil.polyline(java.util.List.of(
                new Coordinate(0, 0), new Coordinate(100, 0))));
        return validator;
    }

    @Test
    void crossingBarrierInteriorIsInvalid() {
        EdgeValidator v = validatorWithBarrier();
        assertFalse(v.check(new Coordinate(50, -10), new Coordinate(50, 10), null, null).valid);
    }

    @Test
    void parallelDuplicateIsInvalid() {
        EdgeValidator v = validatorWithBarrier();
        assertFalse(v.check(new Coordinate(0, 0.5), new Coordinate(100, 0.5), null, null).valid);
    }

    @Test
    void farParallelIsValid() {
        EdgeValidator v = validatorWithBarrier();
        assertTrue(v.check(new Coordinate(0, 5), new Coordinate(100, 5), null, null).valid);
    }

    @Test
    void touchingAtDeclaredConnectionPointAllowed() {
        EdgeValidator v = validatorWithBarrier();
        Coordinate connect = new Coordinate(50, 0);
        // заход на присоединение: объявленная точка лежит на барьере
        assertTrue(v.check(new Coordinate(50, 5), connect, null, null, connect).valid);
    }

    @Test
    void touchingBarrierWithoutDeclaredConnectionIsInvalid() {
        EdgeValidator v = validatorWithBarrier();
        // без объявленной точки присоединения касание барьера запрещено —
        // иначе пути «проскальзывают» через общие вершины графа
        assertFalse(v.check(new Coordinate(50, 5), new Coordinate(50, 0), null, null).valid);
    }
}
