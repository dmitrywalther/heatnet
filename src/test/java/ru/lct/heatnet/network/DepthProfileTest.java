package ru.lct.heatnet.network;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DepthProfileTest {

    @Test
    void conflictingWindowsCannotBeHiddenByReplacingTheirEmptyIntersection() {
        DepthProfile p = DepthProfile.build(100, List.of(
                new DepthProfile.Window(40, 60, 4.0, 5.0),
                new DepthProfile.Window(50, 70, 0.7, 2.0)));
        assertFalse(p.isValid(), "the overlap cannot be both at least 4 m and at most 2 m deep");
    }

    @Test
    void insufficientRampCannotJumpToTheTargetDepthAtTheWindowBoundary() {
        DepthProfile p = DepthProfile.build(100,
                List.of(new DepthProfile.Window(1.23, 5, 0.7, 2.34)));
        assertEquals(3, p.depthAt(0), 1e-9);
        assertEquals(2.34, p.depthAt(1.23), 1e-9);
        assertFalse(p.isValid(), "0.66 m depth change in 1.23 m exceeds the 0.1 slope limit");
    }

    @Test
    void aWindowEntirelyAboveTheMinimumCoverHasNoAdmissibleDepth() {
        DepthProfile p = DepthProfile.build(100,
                List.of(new DepthProfile.Window(40, 60, 0.1, 0.6)));
        assertFalse(p.isValid(), "clamping to 0.7 m does not satisfy the original upper bound 0.6 m");
    }

    @Test
    void nonFiniteVerticalBoundsCannotProduceAnExportableProfile() {
        DepthProfile p = DepthProfile.build(100,
                List.of(new DepthProfile.Window(40, 60, 0.7, Double.NaN)));
        assertFalse(p.isValid());
    }

    @Test
    void aLooseLowerBoundBelowMinimumDoesNotInvalidateAnAdmissibleProfile() {
        DepthProfile p = DepthProfile.build(100,
                List.of(new DepthProfile.Window(40, 60, -1.0, 2.0)));
        assertEquals(2.0, p.depthAt(50), 1e-9);
        assertTrue(p.isValid(), "a lower bound is not the actual burial depth");
    }

    @Test
    void endingAtModifiedDepthIsAllowedWithoutAnInventedReturnRequirement() {
        DepthProfile p = DepthProfile.build(100,
                List.of(new DepthProfile.Window(90, 99, 0.7, 2.0)));
        assertEquals(2.0, p.depthAt(100), 1e-9);
        assertTrue(p.isValid(), "the final plateau is allowed when no return ramp fits");
    }

    @Test
    void flatProfileWithoutConstraints() {
        DepthProfile p = DepthProfile.build(100, List.of());
        assertEquals(3.0, p.depthAt(0), 1e-9);
        assertEquals(3.0, p.depthAt(50), 1e-9);
        assertEquals(1.0, p.kDepth(0, 100), 1e-9);
        assertTrue(p.breakpoints(100).isEmpty());
        assertTrue(p.isValid());
    }

    /** Проход над существующей теплосетью ДУ 300: верх ≤ 3,0 − 0,5 − h. */
    @Test
    void crossingAboveExistingPipeRaisesProfile() {
        // окно [0.7, 2.05] на интервале [48, 52] (наша высота 0.45)
        DepthProfile p = DepthProfile.build(100,
                List.of(new DepthProfile.Window(48, 52, 0.7, 2.05)));
        assertEquals(2.05, p.depthAt(50), 1e-6);
        assertEquals(3.0, p.depthAt(0), 1e-6);
        assertEquals(3.0, p.depthAt(100), 1e-6);
        // рампа с уклоном 0,1: длина (3 − 2.05)/0.1 = 9.5 м
        assertEquals(3.0, p.depthAt(48 - 9.5 - 1e-6), 1e-3);
        assertEquals(1.0, p.kDepth(0, 100), 1e-9, "выше 3,0 м Kгл = 1");
        assertTrue(p.isModified());
        assertTrue(p.isValid());
    }

    /** Ныряем под препятствие: Kгл растёт, на отметке 3,0 — точка деления. */
    @Test
    void crossingBelowAppliesDepthCoefficient() {
        // окно [4.0, inf) на [40, 60]
        DepthProfile p = DepthProfile.build(100,
                List.of(new DepthProfile.Window(40, 60, 4.0, 1e9)));
        assertEquals(4.0, p.depthAt(50), 1e-6);
        assertEquals(1.1, DepthProfile.kAt(4.0), 1e-9);
        // плато [40,60] на 4.0: Kгл = 1.1
        assertEquals(1.1, p.kDepth(45, 55), 1e-9);
        // рампа 30→40: средний Kгл (1 + 1.1)/2, но кусок режется на отметке 3,0
        assertTrue(p.breakpoints(100).size() >= 2);
        assertTrue(p.isValid());
    }

    @Test
    void overlappingWindowsIntersect() {
        DepthProfile p = DepthProfile.build(100, List.of(
                new DepthProfile.Window(40, 55, 0.7, 2.5),
                new DepthProfile.Window(50, 65, 0.7, 2.0)));
        // пересечение окон: [0.7, 2.0] на [40, 65]
        assertEquals(2.0, p.depthAt(45), 1e-6);
        assertEquals(2.0, p.depthAt(60), 1e-6);
        assertTrue(p.isValid());
    }
}
