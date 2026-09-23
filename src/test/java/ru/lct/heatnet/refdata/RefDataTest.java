package ru.lct.heatnet.refdata;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RefDataTest {

    @Test
    void minGradeForFlowMatchesTable1() {
        assertEquals(50, PipeTable.minGradeForFlow(3.5).getDu());
        assertEquals(65, PipeTable.minGradeForFlow(3.6).getDu());
        assertEquals(100, PipeTable.minGradeForFlow(22.3).getDu());
        assertEquals(125, PipeTable.minGradeForFlow(24.9).getDu());
        assertEquals(200, PipeTable.minGradeForFlow(76.3).getDu());
        assertEquals(400, PipeTable.minGradeForFlow(488.7).getDu());
    }

    @Test
    void chamberCostBrackets() {
        assertEquals(3_000_000, CostModel.chamberCost(50), 0);
        assertEquals(3_000_000, CostModel.chamberCost(200), 0);
        assertEquals(5_000_000, CostModel.chamberCost(250), 0);
        assertEquals(5_000_000, CostModel.chamberCost(500), 0);
        assertEquals(8_000_000, CostModel.chamberCost(1000), 0);
        assertEquals(12_000_000, CostModel.chamberCost(1400), 0);
    }

    @Test
    void oksClearanceByDu() {
        RestrictionRule oks = RestrictionRules.builtIn().forType("oks");
        assertEquals(5.0, oks.clearanceForDu(400), 0);
        assertEquals(7.0, oks.clearanceForDu(500), 0);
        assertEquals(7.0, oks.clearanceForDu(800), 0);
        assertEquals(9.0, oks.clearanceForDu(900), 0);
    }

    @Test
    void penaltyFormula() {
        assertEquals(100_000_000 + 500_000 * 20.0, CostModel.unconnectedPenalty(20.0), 1e-6);
    }

    /** Пример из раздела 7.3 техприложения: C=13 974 800, L=100 -> S=0.6913. */
    @Test
    void scoreMatchesReferenceExample() {
        double s = CostModel.score(13_974_800, 100.0);
        assertEquals(0.6913, Math.round(s * 10000) / 10000.0, 1e-9);
    }
}
