package ru.lct.heatnet.network;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NetworkDepthProfilesConstraintsTest {
    @Test
    void degenerateEdgeInAComponentNeedingRepairIsRejectedWithoutAnEmptyProfileCrash() {
        NetworkPlan plan = new NetworkPlan();
        PlanNode source = node(plan, PlanNode.Kind.EXISTING_CHAMBER, 0);
        PlanNode end = node(plan, PlanNode.Kind.OKS, 40);
        PlanEdge zero = plan.addEdge(source, source, List.of(source.utm, source.utm));
        PlanEdge edge = plan.addEdge(source, end, List.of(source.utm, end.utm));
        Map<PlanEdge, DepthProfile> profiles = NetworkDepthProfiles.build(plan, Map.of(
                zero, List.of(), edge, List.of(new DepthProfile.Window(1, 5, 0.7, 2.0))));
        assertFalse(profiles.get(edge).isValid());
        assertFalse(profiles.get(zero).isValid());
        assertEquals(3, profiles.get(zero).depthAt(0), 1e-9);
    }

    @Test
    void reversingAnEdgePreservesThePhysicalProfileAcrossItsSharedNode() {
        Map<PlanEdge, DepthProfile> forward = branchProfiles(false);
        Map<PlanEdge, DepthProfile> reversed = branchProfiles(true);
        List<DepthProfile> f = List.copyOf(forward.values());
        List<DepthProfile> r = List.copyOf(reversed.values());
        assertTrue(f.stream().allMatch(DepthProfile::isValid));
        assertTrue(r.stream().allMatch(DepthProfile::isValid));
        for (int i = 0; i <= 80; i++) {
            double s = i / 10.0;
            assertEquals(f.get(0).depthAt(s), r.get(0).depthAt(s), 1e-8);
        }
        for (int i = 0; i <= 320; i++) {
            double s = i / 10.0;
            assertEquals(f.get(1).depthAt(s), r.get(1).depthAt(32 - s), 1e-8);
        }
        assertEquals(f.get(0).depthAt(8), f.get(1).depthAt(0), 1e-9);
        assertEquals(r.get(0).depthAt(8), r.get(1).depthAt(32), 1e-9);
    }

    @Test
    void partiallyOverlappingWindowsDoNotApplyTheirIntersectionToTheEntireUnion() {
        NetworkPlan plan = new NetworkPlan();
        PlanNode source = node(plan, PlanNode.Kind.EXISTING_CHAMBER, 0);
        PlanNode end = node(plan, PlanNode.Kind.OKS, 40);
        PlanEdge edge = plan.addEdge(source, end, List.of(source.utm, end.utm));
        // The second window begins at 8 m. Applying its lower bound at the
        // source would wrongly turn this feasible crossing into a conflict.
        List<DepthProfile.Window> windows = List.of(
                new DepthProfile.Window(0, 10, 2.8, 3.5),
                new DepthProfile.Window(8, 12, 3.4, 4.0));
        DepthProfile profile = NetworkDepthProfiles.build(plan, Map.of(edge, windows)).get(edge);
        assertTrue(profile.isValid());
        assertEquals(3, profile.depthAt(0), 1e-9);
        for (int i = 0; i <= 100; i++) {
            double depth = profile.depthAt(i / 10.0);
            assertTrue(depth >= 2.8 - 1e-9 && depth <= 3.5 + 1e-9);
        }
        for (int i = 80; i <= 120; i++) {
            double depth = profile.depthAt(i / 10.0);
            assertTrue(depth >= 3.4 - 1e-9 && depth <= 4.0 + 1e-9);
        }
    }

    @Test
    void sideChoiceIsLocalToEachCrossingRatherThanAppliedToEveryWindow() {
        NetworkPlan plan = new NetworkPlan();
        PlanNode source = node(plan, PlanNode.Kind.EXISTING_CHAMBER, 0);
        PlanNode end = node(plan, PlanNode.Kind.OKS, 80);
        PlanEdge edge = plan.addEdge(source, end, List.of(source.utm, end.utm));
        List<DepthProfile.Window> windows = List.of(
                new DepthProfile.Window(4.53, 8.53, 0.7, 2.2, 3.4, 1e9),
                new DepthProfile.Window(40, 44, 0.7, 2.2, 3.4, 1e9));
        DepthProfile profile = NetworkDepthProfiles.build(plan, Map.of(edge, windows)).get(edge);
        assertTrue(profile.isValid());
        assertEquals(3, profile.depthAt(0), 1e-9);
        assertEquals(3.4, profile.depthAt(6), 1e-9);
        assertEquals(2.2, profile.depthAt(42), 1e-9);
    }

    @Test
    void aNewChamberOnAnExistingPipeRemainsFixedEvenWhenBothCrossingSidesFail() {
        NetworkPlan plan = new NetworkPlan();
        PlanNode source = node(plan, PlanNode.Kind.NEW_CHAMBER, 0);
        source.splitPipeDu = 100;
        PlanNode end = node(plan, PlanNode.Kind.OKS, 40);
        PlanEdge edge = plan.addEdge(source, end, List.of(source.utm, end.utm));
        // Neither a 1 m climb nor a 0.4 m descent fits in the available metre.
        List<DepthProfile.Window> windows = List.of(
                new DepthProfile.Window(1, 5, 0.7, 2.0, 3.4, 1e9));
        DepthProfile profile = NetworkDepthProfiles.build(plan, Map.of(edge, windows)).get(edge);
        assertFalse(profile.isValid());
        assertEquals(3, profile.depthAt(0), 1e-9);
        assertTrue(profile.validationIssues().stream()
                .anyMatch(issue -> "DEPTH_NETWORK_INFEASIBLE".equals(issue.code)));
    }

    private static Map<PlanEdge, DepthProfile> branchProfiles(boolean reverse) {
        NetworkPlan plan = new NetworkPlan();
        PlanNode source = node(plan, PlanNode.Kind.EXISTING_CHAMBER, 0);
        PlanNode chamber = node(plan, PlanNode.Kind.NEW_CHAMBER, 8);
        PlanNode end = node(plan, PlanNode.Kind.OKS, 40);
        PlanEdge incoming = plan.addEdge(source, chamber, List.of(source.utm, chamber.utm));
        PlanEdge outgoing = reverse
                ? plan.addEdge(end, chamber, List.of(end.utm, chamber.utm))
                : plan.addEdge(chamber, end, List.of(chamber.utm, end.utm));
        Map<PlanEdge, List<DepthProfile.Window>> windows = new LinkedHashMap<>();
        windows.put(incoming, List.of());
        windows.put(outgoing, List.of(reverse
                ? new DepthProfile.Window(27, 30.77, 0.7, 2.34)
                : new DepthProfile.Window(1.23, 5, 0.7, 2.34)));
        return NetworkDepthProfiles.build(plan, windows);
    }

    private static PlanNode node(NetworkPlan plan, PlanNode.Kind kind, double x) {
        return plan.addNode(new PlanNode(kind, new Coordinate(x, 0)));
    }
}
