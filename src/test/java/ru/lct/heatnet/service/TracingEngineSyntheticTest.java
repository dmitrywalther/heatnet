package ru.lct.heatnet.service;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.network.VariantResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Синтетический сквозной тест: одна точка ОКС в 100 м от существующей сети,
 * камера рядом с трубой. Проверяются подключение, подбор ДУ и стоимость.
 */
class TracingEngineSyntheticTest {

    private static final GeometryFactory GF = new GeometryFactory();

    private InputModel model() {
        InputModel m = new InputModel();

        InputModel.ExistingPipe pipe = new InputModel.ExistingPipe();
        pipe.id = "pipe1";
        pipe.diameter = 300;
        pipe.utm = GF.createLineString(new Coordinate[]{
                new Coordinate(0, -200), new Coordinate(0, 200)});
        m.pipes.add(pipe);

        InputModel.ExistingChamber chamber = new InputModel.ExistingChamber();
        chamber.id = "chamber1";
        chamber.utm = GF.createPoint(new Coordinate(0, 200));
        chamber.wgs = new Coordinate(37.0, 55.0);
        m.chambers.add(chamber);

        InputModel.OksPoint oks = new InputModel.OksPoint();
        oks.id = "oks1";
        oks.flowTph = 20.0; // ДУ 100
        oks.utm = GF.createPoint(new Coordinate(100, 0));
        oks.wgs = new Coordinate(37.001, 55.0);
        m.oksPoints.add(oks);
        return m;
    }

    @Test
    void singleOksConnectsWithCorrectDiameterAndCost() {
        TracingResult result = new TracingEngine().run(model());
        List<VariantResult> variants = result.variants;
        assertFalse(variants.isEmpty());
        VariantResult best = variants.get(0);
        assertEquals(1, best.rank);
        assertTrue(best.unconnectedOksIds.isEmpty(), "ОКС должен быть подключён");
        assertFalse(best.segments.isEmpty());

        double totalLen = best.segments.stream().mapToDouble(s -> s.lengthM).sum();
        assertTrue(totalLen >= 100 && totalLen < 130,
                "длина ~100 м, получено " + totalLen);
        for (VariantResult.Segment s : best.segments) {
            assertEquals(100, s.diameter, "ДУ для 20 т/ч — 100");
            assertEquals(20.0, s.flowTph, 1e-6);
        }
        // врезка перпендикуляром в трубу: новая камера (наибольший ДУ 300 -> 5 млн)
        // либо врезка в существующую камеру (5 млн)
        double tieCost = best.chamberConstructionCost + best.existingChamberTieInCost;
        assertEquals(5_000_000, tieCost, 1e-6);
        assertEquals(best.constructionCost + best.unconnectedPenalty, best.calculatedCost, 1e-6);

        // дополнительный режим: без пересечений профиль — обычная глубина 3,0 м
        assertFalse(result.depthVariants.isEmpty());
        VariantResult depth = result.depthVariants.get(0);
        assertFalse(depth.segments.isEmpty());
        for (VariantResult.Segment s : depth.segments) {
            assertEquals(3.0, s.depthStart, 1e-9);
            assertEquals(3.0, s.depthEnd, 1e-9);
        }
        assertEquals(best.calculatedCost, depth.calculatedCost, 1.0);
    }
}
