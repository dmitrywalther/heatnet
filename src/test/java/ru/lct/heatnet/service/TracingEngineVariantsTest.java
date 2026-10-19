package ru.lct.heatnet.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.slf4j.LoggerFactory;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.network.VariantResult;
import ru.lct.heatnet.network.NetworkAssembler;
import ru.lct.heatnet.network.NetworkPlan;
import ru.lct.heatnet.network.PlanNode;
import ru.lct.heatnet.routing.GeoUtil;
import ru.lct.heatnet.routing.ObstacleIndex;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class TracingEngineVariantsTest {
    private static TracingResult result;
    private static List<ILoggingEvent> events;

    @Test
    void rejectsAlternativeWhoseChamberTruncatesTheRequiredRoadCrossing() {
        InputModel roadScene = new InputModel();
        InputModel.Restriction road = new InputModel.Restriction();
        road.id = "road";
        road.restrictionType = "road";
        road.utm = GeoUtil.GF.createPolygon(new Coordinate[]{new Coordinate(0, -100),
                new Coordinate(10, -100), new Coordinate(10, 100), new Coordinate(0, 100),
                new Coordinate(0, -100)});
        roadScene.restrictions.add(road);
        VariantResult shared = crossingResult(new InputModel(), 40, 50, "v1");
        VariantResult truncated = crossingResult(roadScene, 12, -20, "v3");
        VariantResult complete = crossingResult(roadScene, 14, -20, "v3");
        assertFalse(shared.clearanceViolated);
        assertTrue(truncated.clearanceViolated,
                "a chamber only 2 m beyond the road leaves less than the required 3 m margin");
        assertTrue(truncated.unconnectedOksIds.isEmpty());
        assertFalse(complete.clearanceViolated, "the 4 m road margin is sufficient");
        TracingEngine engine = new TracingEngine();
        assertFalse(engine.acceptsAlternative(truncated, shared, List.of(shared)),
                "a complete but invalid alternative must not enter base or the matching depth output");
        assertTrue(engine.acceptsAlternative(complete, shared, List.of(shared)),
                "a geometrically valid distinct alternative must remain available");
    }

    private static VariantResult crossingResult(InputModel input, double start, double end, String id) {
        NetworkPlan plan = new NetworkPlan();
        PlanNode source = plan.addNode(new PlanNode(PlanNode.Kind.NEW_CHAMBER, new Coordinate(start, 0)));
        source.splitPipeDu = 400;
        PlanNode oks = plan.addNode(new PlanNode(PlanNode.Kind.OKS, new Coordinate(end, 0)));
        oks.inputId = "target";
        oks.oksFlow = 1.0;
        plan.addEdge(source, oks, List.of(source.utm, oks.utm));
        return new NetworkAssembler(new ObstacleIndex(input)).assemble(plan, id, id.equals("v1") ? "shared" : "alternative");
    }

    @BeforeAll
    static void calculateRealNetwork() {
        InputModel input = new InputModel();
        InputModel.ExistingPipe pipe = new InputModel.ExistingPipe();
        pipe.id = "pipe";
        pipe.diameter = 300;
        pipe.utm = GeoUtil.GF.createLineString(new Coordinate[]{
                new Coordinate(0, -200), new Coordinate(0, 200)});
        input.pipes.add(pipe);
        InputModel.ExistingChamber chamber = new InputModel.ExistingChamber();
        chamber.id = "chamber";
        chamber.utm = GeoUtil.GF.createPoint(new Coordinate(0, 200));
        input.chambers.add(chamber);
        InputModel.OksPoint oks = new InputModel.OksPoint();
        oks.id = "oks";
        oks.flowTph = 20;
        oks.utm = GeoUtil.GF.createPoint(new Coordinate(100, 0));
        input.oksPoints.add(oks);

        Logger logger = (Logger) LoggerFactory.getLogger(TracingEngine.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            result = new TracingEngine().run(input);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
            appender.stop();
        }
        events = List.copyOf(appender.list);
    }

    @Test
    void doesNotComputeIndependentEvenWhenItWouldBeFilteredAsDuplicate() {
        assertTrue(events.stream().anyMatch(e -> e.getFormattedMessage().contains("[shared]")),
                "the observer must capture actual variant construction");
        assertTrue(events.stream().noneMatch(e -> e.getFormattedMessage().contains("[independent]")),
                "independent must not consume work before being discarded");
        assertEquals(List.of("v1", "v3"), result.variants.stream()
                .map(v -> v.variantId).sorted().collect(Collectors.toList()));
    }

    @Test
    void keepsDepthIdentityOfAlternativeWhenIndependentIsAbsent() {
        assertEquals(List.of("d1", "d3"), result.depthVariants.stream()
                .map(v -> v.variantId).sorted().collect(Collectors.toList()));
        for (VariantResult base : result.variants) {
            VariantResult depth = result.depthVariants.stream()
                    .filter(v -> v.variantId.equals("d" + base.variantId.substring(1)))
                    .findFirst().orElseThrow();
            assertEquals(base.strategyName, depth.strategyName);
            assertEquals(base.newNetworkLength, depth.newNetworkLength, 1e-6);
        }
    }

    @Test
    void ranksRetainedVariantsContinuouslyAndConnectsTheConsumer() {
        for (List<VariantResult> variants : List.of(result.variants, result.depthVariants)) {
            assertEquals(2, variants.size(), "this scene has a distinct alternative connection");
            for (int i = 0; i < variants.size(); i++) {
                VariantResult variant = variants.get(i);
                assertEquals(i + 1, variant.rank);
                assertTrue(variant.unconnectedOksIds.isEmpty());
                assertFalse(variant.clearanceViolated);
                assertTrue(variant.strategyName.equals("shared") || variant.strategyName.equals("alternative"));
            }
        }
    }

    @Test
    void permitsOnlySharedWhenThereIsNoExistingConnectionForAnAlternative() {
        TracingResult empty = new TracingEngine().run(new InputModel());
        assertEquals(1, empty.variants.size());
        assertEquals("v1", empty.variants.get(0).variantId);
        assertEquals(1, empty.variants.get(0).rank);
        assertEquals(1, empty.depthVariants.size());
        assertEquals("d1", empty.depthVariants.get(0).variantId);
        assertEquals(1, empty.depthVariants.get(0).rank);
    }
}
