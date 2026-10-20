package ru.lct.heatnet.report;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.network.VariantResult;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MapRendererTest {
    @Test
    void comparisonIncludesBothRoutesOutsideInputAndKeepsSharedPartsVisible() {
        InputModel input = new InputModel();
        InputModel.ExistingPipe pipe = new InputModel.ExistingPipe();
        pipe.diameter = 100;
        pipe.utm = new GeometryFactory().createLineString(
                new Coordinate[]{new Coordinate(0, 0), new Coordinate(100, 0)});
        input.pipes.add(pipe);
        VariantResult a = network(200);
        VariantResult b = network(-200);
        BufferedImage image = new MapRenderer(input).renderComparison(a, b, 900, 500);
        int minAy = image.getHeight(), maxBy = -1;
        int redShared = 0, blueShared = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int color = image.getRGB(x, y);
                if (color == MapRenderer.COMPARISON_A.getRGB()) {
                    minAy = Math.min(minAy, y);
                    if (Math.abs(y - 250) <= 5 && x < 650) redShared++;
                }
                if (color == MapRenderer.COMPARISON_B.getRGB()) {
                    maxBy = Math.max(maxBy, y);
                    if (Math.abs(y - 250) <= 5 && x < 650) blueShared++;
                }
            }
        }
        assertTrue(minAy > 4 && minAy < 50,
                "The upper route outside input extent must be visible with a margin");
        assertTrue(maxBy > 450 && maxBy < 496,
                "The lower route outside input extent must be visible with a margin");
        assertTrue(redShared > 100 && blueShared > 100,
                "A and B must both remain visible along the shared part");
    }

    private static VariantResult network(double endY) {
        VariantResult result = new VariantResult();
        VariantResult.Segment segment = new VariantResult.Segment();
        segment.utmCoords = List.of(new Coordinate(0, 0), new Coordinate(400, 0),
                new Coordinate(400, endY));
        result.segments.add(segment);
        return result;
    }
}
