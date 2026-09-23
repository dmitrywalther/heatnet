package ru.lct.heatnet.report;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.network.VariantResult;
import ru.lct.heatnet.service.TracingEngine;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
    }
}
