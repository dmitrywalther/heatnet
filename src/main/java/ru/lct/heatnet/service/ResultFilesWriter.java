package ru.lct.heatnet.service;

import ru.lct.heatnet.geo.GeoJsonWriter;
import ru.lct.heatnet.geo.Projection;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.report.PdfReportWriter;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Единая семантика файлов для CLI и фонового задания. */
public final class ResultFilesWriter {
    private ResultFilesWriter() { }

    public static boolean write(InputModel model, Projection projection, TracingResult result,
                                Path basePath, Path depthPath, Path reportPath) throws IOException {
        ResultDiagnosticIO.requireValidVariants(result.variants, true);
        ResultDiagnosticIO.requireValidVariants(result.depthVariants, true);
        if (result.variants.isEmpty()) {
            // Только явно заданные выходы данного расчёта, без обхода каталога.
            Files.deleteIfExists(basePath);
            if (depthPath != null) Files.deleteIfExists(depthPath);
            if (reportPath != null) Files.deleteIfExists(reportPath);
            ResultDiagnosticIO.write(basePath, result.diagnostics);
            return false;
        }
        ResultDiagnosticIO.write(basePath, result.diagnostics);
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(basePath))) {
            new GeoJsonWriter(projection).write(result.variants, result.diagnostics, out);
        }
        if (depthPath != null) {
            if (result.depthVariants.isEmpty()) {
                Files.deleteIfExists(depthPath);
            } else {
                try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(depthPath))) {
                    new GeoJsonWriter(projection).write(result.depthVariants, result.diagnostics, out);
                }
            }
        }
        if (reportPath != null) {
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(reportPath))) {
                new PdfReportWriter(model, result.variants, result.depthVariants,
                        result.diagnostics).write(out);
            }
        }
        return true;
    }
}
