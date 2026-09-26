package ru.lct.heatnet.report;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.network.VariantResult;
import ru.lct.heatnet.refdata.CostModel;
import ru.lct.heatnet.refdata.PipeTable;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Пояснительная записка к расчёту: сводка вариантов, карта, разбивка
 * стоимости и таблицы участков по каждому варианту, методика расчёта.
 */
public class PdfReportWriter {

    private static final float PAGE_W = PDRectangle.A4.getWidth();   // 595
    private static final float PAGE_H = PDRectangle.A4.getHeight();  // 842
    private static final float MARGIN = 42;
    private static final float CONTENT_W = PAGE_W - 2 * MARGIN;
    private static final float BOTTOM = 46;

    private static final Color ACCENT = new Color(0xFF, 0x00, 0x53);
    private static final Color PURPLE = new Color(0x52, 0x09, 0x78);
    private static final Color TEXT = new Color(0x1C, 0x1D, 0x22);
    private static final Color MUTED = new Color(0x6E, 0x6A, 0x77);
    private static final Color TABLE_HEADER_BG = new Color(0xF3, 0xEC, 0xF8);
    private static final Color ROW_LINE = new Color(0xE1, 0xDA, 0xE8);

    private final InputModel input;
    private final List<VariantResult> variants;
    private final List<VariantResult> depthVariants;

    private PDDocument doc;
    private PDFont regular;
    private PDFont bold;
    private PDPageContentStream cs;
    private float y;
    private int pageNo;

    public PdfReportWriter(InputModel input, List<VariantResult> variants) {
        this(input, variants, null);
    }

    public PdfReportWriter(InputModel input, List<VariantResult> variants,
                           List<VariantResult> depthVariants) {
        this.input = input;
        this.variants = variants;
        this.depthVariants = depthVariants;
    }

    public void write(OutputStream out) throws IOException {
        doc = new PDDocument();
        regular = loadFont("/fonts/DejaVuSans.ttf");
        bold = loadFont("/fonts/DejaVuSans-Bold.ttf");
        try {
            summaryPage();
            MapRenderer renderer = new MapRenderer(input);
            for (VariantResult v : variants) {
                variantPages(v, renderer);
            }
            methodologyPage();
            closePageStream();
            doc.save(out);
        } finally {
            doc.close();
        }
    }

    private PDFont loadFont(String resource) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Не найден шрифт " + resource);
            }
            return PDType0Font.load(doc, in);
        }
    }

    // ------------------------------------------------------------------ pages

    private void summaryPage() throws IOException {
        newPage();
        title("Пояснительная записка");
        text(MARGIN, y, regular, 12, MUTED,
                "к расчёту трассировки тепловых сетей для технологического присоединения");
        y -= 30;

        double totalFlow = input.oksPoints.stream().mapToDouble(o -> o.flowTph).sum();
        String when = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        kv("Дата расчёта", when);
        kv("Режим расчёта", "базовый двумерный (глубина: depth = null, Kгл = 1)");
        kv("Точек подключения ОКС", input.oksPoints.size() + "  (суммарный расход "
                + fmt(totalFlow, 2) + " т/ч)");
        kv("Существующая сеть", input.pipes.size() + " участков, "
                + input.chambers.size() + " тепловых камер");
        kv("Пространственных ограничений", String.valueOf(input.restrictions.size()));
        y -= 14;

        heading("Сравнение вариантов");
        List<String[]> rows = new ArrayList<>();
        for (VariantResult v : variants) {
            rows.add(new String[]{
                    String.valueOf(v.rank),
                    v.variantId + " — " + strategyRu(v.strategyName),
                    fmt(v.calculatedCost, 0),
                    fmt(v.newNetworkLength, 0),
                    fmt(v.score, 4),
                    v.unconnectedOksIds.isEmpty() ? "нет"
                            : String.valueOf(v.unconnectedOksIds.size())});
        }
        table(new String[]{"Место", "Вариант", "Стоимость, руб.", "Длина, м", "Score", "Не подкл."},
                new float[]{45, 190, 110, 70, 60, 55},
                new boolean[]{false, false, true, true, true, false}, rows);
        y -= 16;

        if (depthVariants != null && !depthVariants.isEmpty()) {
            heading("Дополнительный режим с учётом глубины");
            List<String[]> depthRows = new ArrayList<>();
            for (VariantResult v : depthVariants) {
                long deepened = v.segments.stream()
                        .filter(s -> s.depthStart != null
                                && (Math.abs(s.depthStart - 3.0) > 1e-9
                                    || Math.abs(s.depthEnd - 3.0) > 1e-9))
                        .count();
                depthRows.add(new String[]{
                        String.valueOf(v.rank),
                        v.variantId + " — " + strategyRu(v.strategyName),
                        fmt(v.calculatedCost, 0),
                        fmt(v.score, 4),
                        deepened == 0 ? "профиль 3,0 м" : deepened + " уч. с изм. глубиной"});
            }
            table(new String[]{"Место", "Вариант", "Стоимость, руб.", "Score", "Профиль"},
                    new float[]{45, 180, 110, 60, 135},
                    new boolean[]{false, false, true, true, false}, depthRows);
            paragraph("Маршруты вертикально спланированы вдоль тех же трасс: пересечения "
                    + "существующих коммуникаций проходятся выше или ниже с нормативным "
                    + "просветом, глубина задана атрибутами depth_start / depth_end, "
                    + "заглубление ниже 3,0 м оплачивается коэффициентом Kгл.", 10, MUTED);
            y -= 12;
        }

        heading("Итоговый показатель");
        text(MARGIN, y, regular, 11, TEXT,
                "S  =  0,7 · ( C / 25 000 000 )  +  0,3 · ( L / 100 )");
        y -= 16;
        paragraph("C — итоговая стоимость варианта (строительство участков, камер и врезок "
                + "плюс штрафы за неподключённые точки), руб.; L — суммарная длина новых "
                + "участков, м. Чем меньше значение показателя, тем выше вариант в ранжировании.", 10, MUTED);
        y -= 10;
        paragraph("Варианты различаются содержательно: точками присоединения к существующей "
                + "сети и объединением ОКС в общие магистрали. Раздельный вариант приводится "
                + "для сравнения и обоснования совместного подключения.", 10, MUTED);
    }

    private void variantPages(VariantResult v, MapRenderer renderer) throws IOException {
        // --- страница с картой и разбивкой стоимости ---
        newPage();
        title("Вариант " + v.variantId + " — " + strategyRu(v.strategyName));
        text(MARGIN, y, regular, 11, MUTED, "Место в ранжировании: " + v.rank
                + "   ·   score " + fmt(v.score, 4));
        y -= 24;

        BufferedImage map = renderer.render(v, 1560, 1170);
        PDImageXObject image = LosslessFactory.createFromImage(doc, map);
        float mapH = CONTENT_W * 1170f / 1560f;
        cs.drawImage(image, MARGIN, y - mapH, CONTENT_W, mapH);
        cs.setStrokingColor(ROW_LINE);
        cs.setLineWidth(0.8f);
        cs.addRect(MARGIN, y - mapH, CONTENT_W, mapH);
        cs.stroke();
        y -= mapH + 6;
        legendLine();
        y -= 20;

        costWaterfall(v);
        double baseCost = v.segments.stream().filter(s -> !s.special).mapToDouble(s -> s.cost).sum();
        double specialCost = v.segments.stream().filter(s -> s.special).mapToDouble(s -> s.cost).sum();

        // --- таблица участков ---
        newPage();
        title("Вариант " + v.variantId + " — участки новой сети");
        List<String[]> rows = new ArrayList<>();
        int idx = 1;
        for (VariantResult.Segment s : v.segments) {
            rows.add(new String[]{
                    String.valueOf(idx++),
                    String.valueOf(s.diameter),
                    fmt(s.flowTph, 1),
                    fmt(s.lengthM, 1),
                    s.special ? "спецпроход ×" + fmt(s.kSpec, 2) : "обычный",
                    fmt(s.cost, 0)});
        }
        rows.add(new String[]{"", "", "", fmt(v.newNetworkLength, 1), "итого",
                fmt(baseCost + specialCost, 0)});
        table(new String[]{"№", "ДУ, мм", "Расход, т/ч", "Длина, м", "Прокладка", "Стоимость, руб."},
                new float[]{30, 55, 80, 75, 135, 135},
                new boolean[]{false, true, true, true, false, true}, rows);
        y -= 14;

        if (!v.chambers.isEmpty()) {
            heading("Новые тепловые камеры");
            List<String[]> chamberRows = new ArrayList<>();
            for (VariantResult.Chamber c : v.chambers) {
                chamberRows.add(new String[]{c.id, String.valueOf(c.diameter), fmt(c.cost, 0)});
            }
            table(new String[]{"Идентификатор", "ДУ камеры, мм", "Стоимость, руб."},
                    new float[]{220, 110, 180},
                    new boolean[]{false, true, true}, chamberRows);
            y -= 14;
        }
        if (v.existingChamberTieInCount > 0) {
            paragraph("Врезки в существующие тепловые камеры: " + v.existingChamberTieInCount
                    + " × " + fmt(CostModel.TIE_IN_TO_EXISTING_CHAMBER_COST, 0)
                    + " руб. = " + fmt(v.existingChamberTieInCost, 0) + " руб.", 10, TEXT);
        }
        if (!v.unconnectedOksIds.isEmpty()) {
            y -= 6;
            paragraph("Не подключены ОКС: " + v.unconnectedOksIds
                    + " — допустимый маршрут при соблюдении правил не найден; начислен штраф "
                    + fmt(v.unconnectedPenalty, 0) + " руб.", 10, ACCENT);
        }
    }

    private void methodologyPage() throws IOException {
        newPage();
        title("Методика расчёта");
        String[][] items = {
                {"Координаты", "Вход и выход — WGS 84 (EPSG:4326); длины и расстояния — "
                        + "в метрической проекции UTM 37N (EPSG:32637)."},
                {"Маршрутизация", "Граф видимости по контурам препятствий, раздутым на "
                        + "минимальный отступ и полуширину габарита пары труб; поиск A* "
                        + "минимизирует вклад участка в итоговый показатель S."},
                {"Ограничения", "Непроходимые объекты (ОКС — отступ 5/7/9 м по ДУ, парки, "
                        + "соцобъекты, вода, железная дорога — 1 м) обходятся; дороги, "
                        + "трамвай, газопроводы, кабели и существующие теплосети пересекаются "
                        + "специальным проходом: один прямой участок, угол не менее 45° для "
                        + "дорог и трамвая, коэффициент Kспец 1,05–1,75; на границах "
                        + "спецучастка создаются технические узлы."},
                {"Диаметры", "ДУ подбирается по расчётному расходу и предельной длине "
                        + "(таблица 1 техприложения) отдельно по каждому непрерывному пути; "
                        + "по направлению к месту присоединения ДУ не убывает."},
                {"Присоединение", "Врезка в существующую камеру (5 млн руб.) при наличии "
                        + "свободных примыканий (не более 4) и в радиусе 10 м; иначе новая "
                        + "камера в точке существующей сети, стоимость по наибольшему ДУ "
                        + "примыканий. Разветвления новой сети — только в тепловых камерах."},
                {"Совместное подключение", "Точки ОКС подключаются последовательно по "
                        + "убыванию расхода; каждая может присоединиться к уже построенной "
                        + "сети — так возникают общие магистрали, снижающие стоимость."},
                {"Штрафы", "За каждую неподключённую точку: 100 000 000 + 500 000 × G руб., "
                        + "где G — расчётный расход точки, т/ч."},
                {"Контроль", "Каждый участок повторно проверяется после подбора диаметров: "
                        + "отступы, углы поворота (не более 90°), пересечения. Результат "
                        + "дополнительно валидируется автотестами."}};
        for (String[] item : items) {
            ensureSpace(46);
            text(MARGIN, y, bold, 11, PURPLE, item[0]);
            y -= 15;
            paragraph(item[1], 10, TEXT);
            y -= 8;
        }
    }

    // ------------------------------------------------------------- primitives

    private void newPage() throws IOException {
        closePageStream();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        cs = new PDPageContentStream(doc, page);
        pageNo++;
        y = PAGE_H - MARGIN;
        // колонтитул
        text(MARGIN, BOTTOM - 18, regular, 8, MUTED,
                "HEATNET · пояснительная записка к расчёту трассировки");
        String p = String.valueOf(pageNo);
        text(PAGE_W - MARGIN - width(regular, 8, p), BOTTOM - 18, regular, 8, MUTED, p);
    }

    private void closePageStream() throws IOException {
        if (cs != null) {
            cs.close();
            cs = null;
        }
    }

    private void ensureSpace(float needed) throws IOException {
        if (y - needed < BOTTOM) {
            newPage();
        }
    }

    private void title(String s) throws IOException {
        cs.setNonStrokingColor(ACCENT);
        cs.addRect(MARGIN, y - 6, 26, 4);
        cs.fill();
        text(MARGIN + 34, y - 8, bold, 17, TEXT, s);
        y -= 30;
    }

    private void heading(String s) throws IOException {
        ensureSpace(30);
        text(MARGIN, y, bold, 12, PURPLE, s);
        y -= 18;
    }

    private void kv(String key, String value) throws IOException {
        text(MARGIN, y, regular, 10, MUTED, key);
        text(MARGIN + 190, y, regular, 10, TEXT, value);
        y -= 16;
    }

    private void paragraph(String s, float size, Color color) throws IOException {
        for (String line : wrap(s, regular, size, CONTENT_W)) {
            ensureSpace(size + 4);
            text(MARGIN, y, regular, size, color, line);
            y -= size + 3.5f;
        }
    }

    private void legendLine() throws IOException {
        float x = MARGIN;
        // новая сеть
        cs.setStrokingColor(ACCENT);
        cs.setLineWidth(2.4f);
        cs.moveTo(x, y - 2);
        cs.lineTo(x + 20, y - 2);
        cs.stroke();
        x += 25;
        x = legendText(x, "новая сеть");
        // существующая
        cs.setStrokingColor(PURPLE);
        cs.setLineWidth(1.6f);
        cs.setLineDashPattern(new float[]{4, 3}, 0);
        cs.moveTo(x, y - 2);
        cs.lineTo(x + 20, y - 2);
        cs.stroke();
        cs.setLineDashPattern(new float[]{}, 0);
        x += 25;
        x = legendText(x, "существующая сеть");
        // ОКС
        cs.setNonStrokingColor(new Color(0x31, 0x0F, 0x53));
        drawDot(x + 4, y - 2, 3.2f);
        x += 11;
        x = legendText(x, "точки ОКС");
        // камеры
        cs.setNonStrokingColor(ACCENT);
        drawDot(x + 4, y - 2, 3.2f);
        x += 11;
        legendText(x, "новые камеры (пунктир на линии — спецпроход)");
    }

    private float legendText(float x, String s) throws IOException {
        text(x, y - 5, regular, 8.5f, MUTED, s);
        return x + width(regular, 8.5f, s) + 16;
    }

    private void drawDot(float cx, float cy, float r) throws IOException {
        final float k = 0.5523f;
        cs.moveTo(cx - r, cy);
        cs.curveTo(cx - r, cy + k * r, cx - k * r, cy + r, cx, cy + r);
        cs.curveTo(cx + k * r, cy + r, cx + r, cy + k * r, cx + r, cy);
        cs.curveTo(cx + r, cy - k * r, cx + k * r, cy - r, cx, cy - r);
        cs.curveTo(cx - k * r, cy - r, cx - r, cy - k * r, cx - r, cy);
        cs.fill();
    }

    /**
     * Каскад факторов стоимости («водопад»): из чего складывается цена и какие
     * решения её увеличили. База — трубы минимального по расходу диаметра;
     * добавки — магистральные диаметры (предельные длины и неубывание ДУ),
     * спецпроходы, камеры, врезки, штрафы. Сумма факторов равна итогу.
     */
    private void costWaterfall(VariantResult v) throws IOException {
        double pipes = 0, base = 0, dia = 0, spec = 0;
        for (VariantResult.Segment s : v.segments) {
            double rateMin = PipeTable.minGradeForFlow(s.flowTph).getCostPerMeter();
            double rateFact = PipeTable.byDu(s.diameter).getCostPerMeter();
            pipes += s.cost;
            base += s.lengthM * rateMin;
            dia += s.lengthM * (rateFact - rateMin);
            if (s.special) {
                spec += s.lengthM * rateFact * (s.kSpec - 1);
            }
        }
        double resid = pipes - base - dia - spec; // Kгл в режиме глубины; в 2D — округления
        if (Math.abs(resid) < 100_000) {
            base += resid;
            resid = 0;
        }

        heading("Интерпретация стоимости");
        double total = Math.max(1, v.calculatedCost);
        double offset = 0;
        offset = waterfallRow("Трубы минимального по расходу диаметра", base, offset, total, PURPLE, false);
        if (dia > 0.5) {
            offset = waterfallRow("Магистральные диаметры (предельные длины, неубывание ДУ)",
                    dia, offset, total, ACCENT, true);
        }
        if (spec > 0.5) {
            offset = waterfallRow("Спецпроходы дорог и коммуникаций (Kспец)",
                    spec, offset, total, ACCENT, true);
        }
        if (resid != 0) {
            offset = waterfallRow("Заглубление ниже 3 м (Kгл)", resid, offset, total, ACCENT, true);
        }
        if (v.chamberConstructionCost > 0) {
            offset = waterfallRow("Новые тепловые камеры", v.chamberConstructionCost,
                    offset, total, ACCENT, true);
        }
        if (v.existingChamberTieInCost > 0) {
            offset = waterfallRow("Врезки в существующие камеры", v.existingChamberTieInCost,
                    offset, total, ACCENT, true);
        }
        if (v.unconnectedPenalty > 0) {
            offset = waterfallRow("Штрафы за неподключённые ОКС", v.unconnectedPenalty,
                    offset, total, ACCENT, true);
        }
        ensureSpace(20);
        text(MARGIN, y, bold, 10, TEXT, "Итого · новая сеть " + fmt(v.newNetworkLength, 0) + " м");
        String amount = fmt(v.calculatedCost, 0) + " руб.";
        text(PAGE_W - MARGIN - width(bold, 10, amount), y, bold, 10, TEXT, amount);
        y -= 13;
        cs.setNonStrokingColor(TEXT);
        cs.addRect(MARGIN, y, CONTENT_W, 6);
        cs.fill();
        y -= 15;
    }

    private double waterfallRow(String label, double value, double offset, double total,
                                Color color, boolean plus) throws IOException {
        ensureSpace(20);
        text(MARGIN, y, regular, 10, TEXT, label);
        String amount = (plus ? "+ " : "") + fmt(value, 0) + " руб.";
        text(PAGE_W - MARGIN - width(regular, 10, amount), y, regular, 10, TEXT, amount);
        y -= 13;
        cs.setNonStrokingColor(new Color(0xF3, 0xEC, 0xF8));
        cs.addRect(MARGIN, y, CONTENT_W, 6);
        cs.fill();
        float x0 = (float) (CONTENT_W * offset / total);
        float w = (float) Math.max(1.5, CONTENT_W * value / total);
        cs.setNonStrokingColor(color);
        cs.addRect(MARGIN + x0, y, Math.min(w, CONTENT_W - x0), 6);
        cs.fill();
        y -= 15;
        return offset + value;
    }

    private void table(String[] headers, float[] widths, boolean[] rightAlign,
                       List<String[]> rows) throws IOException {
        drawTableHeader(headers, widths);
        for (int r = 0; r < rows.size(); r++) {
            if (y - 16 < BOTTOM) {
                newPage();
                drawTableHeader(headers, widths);
            }
            String[] row = rows.get(r);
            boolean last = r == rows.size() - 1;
            PDFont font = last && row[0].isEmpty() ? bold : regular;
            float x = MARGIN;
            for (int i = 0; i < row.length; i++) {
                String cell = truncate(row[i], font, 9, widths[i] - 8);
                float tx = rightAlign[i]
                        ? x + widths[i] - 6 - width(font, 9, cell)
                        : x + 4;
                text(tx, y - 12, font, 9, TEXT, cell);
                x += widths[i];
            }
            cs.setStrokingColor(ROW_LINE);
            cs.setLineWidth(0.5f);
            cs.moveTo(MARGIN, y - 16);
            cs.lineTo(MARGIN + sum(widths), y - 16);
            cs.stroke();
            y -= 16;
        }
        y -= 4;
    }

    private void drawTableHeader(String[] headers, float[] widths) throws IOException {
        ensureSpace(40);
        cs.setNonStrokingColor(TABLE_HEADER_BG);
        cs.addRect(MARGIN, y - 17, sum(widths), 17);
        cs.fill();
        float x = MARGIN;
        for (int i = 0; i < headers.length; i++) {
            text(x + 4, y - 12, bold, 9, PURPLE, headers[i]);
            x += widths[i];
        }
        y -= 17;
    }

    private void text(float x, float ty, PDFont font, float size, Color color, String s)
            throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.setNonStrokingColor(color);
        cs.newLineAtOffset(x, ty);
        cs.showText(s);
        cs.endText();
    }

    private float width(PDFont font, float size, String s) throws IOException {
        return font.getStringWidth(s) / 1000f * size;
    }

    private String truncate(String s, PDFont font, float size, float maxWidth) throws IOException {
        if (width(font, size, s) <= maxWidth) {
            return s;
        }
        String t = s;
        while (t.length() > 1 && width(font, size, t + "…") > maxWidth) {
            t = t.substring(0, t.length() - 1);
        }
        return t + "…";
    }

    private List<String> wrap(String s, PDFont font, float size, float maxWidth) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : s.split(" ")) {
            String candidate = current.length() == 0 ? word : current + " " + word;
            if (width(font, size, candidate) > maxWidth && current.length() > 0) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }

    private static float sum(float[] widths) {
        float total = 0;
        for (float w : widths) {
            total += w;
        }
        return total;
    }

    private static String strategyRu(String strategy) {
        switch (strategy == null ? "" : strategy) {
            case "shared":
                return "совместное подключение";
            case "independent":
                return "раздельные маршруты";
            case "alternative":
                return "альтернативные врезки";
            default:
                return strategy;
        }
    }

    private static String fmt(double value, int digits) {
        String s = String.format(Locale.forLanguageTag("ru"),
                "%,." + digits + "f", value);
        // русская локаль использует неразрывный пробел как разделитель разрядов
        return s.replace('\u00A0', ' ');
    }
}
