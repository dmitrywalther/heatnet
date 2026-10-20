package ru.lct.heatnet.report;

import org.junit.jupiter.api.Test;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.network.VariantResult;
import ru.lct.heatnet.service.TracingEngine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PdfReportWriterTest {

    private static final GeometryFactory GF = new GeometryFactory();

    @Test
    void generatesReadablePdfWithCyrillic() throws Exception {
        InputModel m = new InputModel();
        InputModel.ExistingPipe pipe = new InputModel.ExistingPipe();
        pipe.id = "pipe1";
        pipe.diameter = 300;
        pipe.utm = GF.createLineString(new Coordinate[]{
                new Coordinate(0, -200), new Coordinate(0, 200)});
        m.pipes.add(pipe);
        InputModel.OksPoint oks = new InputModel.OksPoint();
        oks.id = "oks1";
        oks.flowTph = 20.0;
        oks.utm = GF.createPoint(new Coordinate(100, 0));
        oks.wgs = new Coordinate(37.001, 55.0);
        m.oksPoints.add(oks);

        ru.lct.heatnet.service.TracingResult result = new TracingEngine().run(m);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new PdfReportWriter(m, result.variants, result.depthVariants).write(out);

        byte[] pdf = out.toByteArray();
        assertTrue(pdf.length > 10_000, "PDF подозрительно мал: " + pdf.length);
        assertTrue(pdf[0] == '%' && pdf[1] == 'P' && pdf[2] == 'D' && pdf[3] == 'F',
                "нет сигнатуры PDF");
        try (PDDocument document = PDDocument.load(pdf)) {
            String text = new PDFTextStripper().getText(document).replaceAll("\\s+", " ");
            assertFalse(text.contains("Раздельный вариант приводится"),
                    "the report must not promise an independent variant absent from its result");
            assertTrue(text.contains("Итоговый показатель"));
            assertTrue(text.contains("Паспорт подключения ОКС"),
                    "The main report must explain how experts can obtain the offline card/profile");
        }
    }

    @Test
    void depthExplanationDoesNotOverlapLastSummaryRow() throws Exception {
        InputModel input = new InputModel();
        InputModel.ExistingPipe pipe = new InputModel.ExistingPipe();
        pipe.id = "p";
        pipe.diameter = 100;
        pipe.utm = GF.createLineString(new Coordinate[]{new Coordinate(0, 0), new Coordinate(100, 0)});
        input.pipes.add(pipe);
        VariantResult base = new VariantResult();
        base.variantId = "v1";
        base.strategyName = "shared";
        base.rank = 1;
        VariantResult depth = new VariantResult();
        depth.variantId = "d1";
        depth.strategyName = "shared";
        depth.rank = 1;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new PdfReportWriter(input, List.of(base), List.of(depth)).write(out);
        try (PDDocument document = PDDocument.load(out.toByteArray())) {
            PositionedText stripper = new PositionedText();
            stripper.getText(document);
            TextLine row = stripper.lines.stream().filter(l -> l.text.contains("профиль 3,0 м"))
                    .findFirst().orElse(null);
            TextLine explanation = stripper.lines.stream()
                    .filter(l -> l.text.contains("Маршруты вертикально спланированы"))
                    .findFirst().orElse(null);
            assertNotNull(row, "fixture must contain the depth summary row");
            assertNotNull(explanation, "fixture must contain the depth explanation");
            assertTrue(explanation.page > row.page
                            || explanation.page == row.page && explanation.top >= row.bottom + 1.0f,
                    "Depth explanation overlaps table row: explanation top=" + explanation.top
                            + ", row bottom=" + row.bottom);
        }
    }

    @Test
    void displaysRankAsVariantNumberWhileKeepingTechnicalIds() throws Exception {
        InputModel input = mapInput();
        VariantResult v1 = variant("v1", 2, "shared", 200_000);
        VariantResult v3 = variant("v3", 1, "alternative", 100_000);
        VariantResult d1 = variant("d1", 2, "shared", 400_000);
        VariantResult d3 = variant("d3", 1, "alternative", 300_000);
        // Deliberately not rank order: labels must use rank, not ID or list index.
        List<VariantResult> base = List.of(v1, v3);
        List<VariantResult> depth = List.of(d1, d3);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new PdfReportWriter(input, base, depth).write(out);
        try (PDDocument document = PDDocument.load(out.toByteArray())) {
            String text = new PDFTextStripper().getText(document).replaceAll("\\s+", " ");
            assertTrue(text.contains("Вариант №2 v1 200 000"), text);
            assertTrue(text.contains("Вариант №1 v3 100 000"), text);
            assertTrue(text.contains("Вариант №2 d1 400 000"), text);
            assertTrue(text.contains("Вариант №1 d3 300 000"), text);
            assertTrue(text.contains("Технический ID"), text);
            for (VariantResult v : base) {
                int mapPages = 0;
                int segmentPages = 0;
                for (int page = 1; page <= document.getNumberOfPages(); page++) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    stripper.setStartPage(page);
                    stripper.setEndPage(page);
                    String pageText = stripper.getText(document).replaceAll("\\s+", " ");
                    if (!pageText.contains("Технический ID: " + v.variantId)) continue;
                    String title = "Вариант №" + v.rank + " — ";
                    if (pageText.contains(title + "участки новой сети")) segmentPages++;
                    if (pageText.contains(title
                            + (v == v1 ? "совместное подключение" : "альтернативные врезки"))) mapPages++;
                }
                assertEquals(1, mapPages, "number and technical ID on the map page");
                assertEquals(1, segmentPages, "number and technical ID on the segment page");
            }
        }
        assertEquals(List.of("v1", "v3", "d1", "d3"),
                List.of(v1.variantId, v3.variantId, d1.variantId, d3.variantId));
        assertEquals(List.of(2, 1, 2, 1), List.of(v1.rank, v3.rank, d1.rank, d3.rank));
        assertEquals(List.of(200_000.0, 100_000.0, 400_000.0, 300_000.0),
                List.of(v1.calculatedCost, v3.calculatedCost, d1.calculatedCost, d3.calculatedCost));
    }

    @Test
    void missingRankKeepsTechnicalIdWithoutInventingVariantNumber() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new PdfReportWriter(mapInput(), List.of(variant("legacy-v3", 0, "alternative", 0)),
                List.of(variant("legacy-d3", -1, "alternative", 0))).write(out);
        try (PDDocument document = PDDocument.load(out.toByteArray())) {
            String text = new PDFTextStripper().getText(document).replaceAll("\\s+", " ");
            assertFalse(text.contains("Вариант №"), "a missing rank must not be replaced by ID/index");
            assertTrue(text.contains("Технический ID: legacy-v3"), text);
            assertTrue(text.contains("legacy-d3"), text);
        }
    }

    private static VariantResult variant(String id, int rank, String strategy, double cost) {
        VariantResult result = new VariantResult();
        result.variantId = id;
        result.rank = rank;
        result.strategyName = strategy;
        result.calculatedCost = cost;
        return result;
    }

    private static InputModel mapInput() {
        InputModel input = new InputModel();
        InputModel.ExistingPipe pipe = new InputModel.ExistingPipe();
        pipe.id = "p";
        pipe.diameter = 100;
        pipe.utm = GF.createLineString(new Coordinate[]{new Coordinate(0, 0), new Coordinate(100, 0)});
        input.pipes.add(pipe);
        return input;
    }

    private static final class TextLine {
        final String text;
        final int page;
        final float top;
        final float bottom;
        TextLine(String text, int page, List<TextPosition> positions) {
            this.text = text;
            this.page = page;
            this.top = (float) positions.stream().mapToDouble(p -> p.getYDirAdj() - p.getHeightDir()).min().orElse(0);
            this.bottom = (float) positions.stream().mapToDouble(TextPosition::getYDirAdj).max().orElse(0);
        }
    }

    private static final class PositionedText extends PDFTextStripper {
        final List<TextLine> lines = new ArrayList<>();
        PositionedText() throws IOException { }
        @Override protected void writeString(String text, List<TextPosition> positions) throws IOException {
            if (!positions.isEmpty()) lines.add(new TextLine(text, getCurrentPageNo(), positions));
            super.writeString(text, positions);
        }
    }
}
