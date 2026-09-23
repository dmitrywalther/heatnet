package ru.lct.heatnet.network;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DepthProfileTest {

    @Test
    void flatProfileWithoutConstraints() {
        DepthProfile p = DepthProfile.build(100, List.of());
        assertEquals(3.0, p.depthAt(0), 1e-9);
        assertEquals(3.0, p.depthAt(50), 1e-9);
        assertEquals(1.0, p.kDepth(0, 100), 1e-9);
        assertTrue(p.breakpoints(100).isEmpty());
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
    }

    @Test
    void overlappingWindowsIntersect() {
        DepthProfile p = DepthProfile.build(100, List.of(
                new DepthProfile.Window(40, 55, 0.7, 2.5),
                new DepthProfile.Window(50, 65, 0.7, 2.0)));
        // пересечение окон: [0.7, 2.0] на [40, 65]
        assertEquals(2.0, p.depthAt(45), 1e-6);
        assertEquals(2.0, p.depthAt(60), 1e-6);
    }
}
