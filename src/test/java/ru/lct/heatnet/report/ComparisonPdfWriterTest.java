package ru.lct.heatnet.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ComparisonPdfWriterTest {
    @Test void rendersSavedFactsSignedDeltasAndSourcesWithoutMutatingComparison() throws Exception {
        Map<String,Object> data=comparison();
        ObjectMapper json=new ObjectMapper();
        String snapshot=json.writeValueAsString(data);
        try(PDDocument doc=PDDocument.load(pdf(data))) {
            String text=new PDFTextStripper().getText(doc).replaceAll("\\s+"," ");
            assertTrue(text.contains("Сравнение расчётов"));
            assertTrue(text.contains("v1"));
            assertTrue(text.contains("Стоимость строительства"));
            assertTrue(text.contains("1 250 000"));
            assertTrue(text.contains("+250 000"));
            assertTrue(text.contains("-1"));
            assertTrue(text.contains("9007199254740993"));
            assertTrue(text.contains("a".repeat(64)));
            assertTrue(text.contains("b".repeat(64)));
            assertTrue(text.contains("после минус до"));
            assertTrue(text.contains("не цена отдельного подключения"));
            assertTrue(text.contains("не выполняется"));
            assertEquals(snapshot,json.writeValueAsString(data));
        }
    }

    @Test void missingMetricsAreUnavailableInsteadOfInventedZero() throws Exception {
        Map<String,Object> data=comparison();
        for(String side:List.of("before","after")) {
            Map<String,Object> summary=obj(obj(data,side),"summary");
            for(String key:List.of("construction_cost","unconnected_penalty","calculated_cost",
                    "chamber_construction_cost","existing_chamber_tie_in_cost")) summary.put(key,null);
        }
        Map<String,Object> delta=obj(data,"delta");
        for(String key:new ArrayList<>(delta.keySet()))delta.put(key,null);
        data.put("connections",List.of());
        try(PDDocument doc=PDDocument.load(pdf(data))) {
            String text=new PDFTextStripper().getText(doc);
            assertTrue(text.contains("н/д"));
            assertFalse(text.contains("0 руб."));
        }
    }

    @Test void bothMapPanelsUseCommonMetricScaleAndShowRemovedAddedGeometry() throws Exception {
        Map<String,Object> data=comparison();
        try(PDDocument doc=PDDocument.load(pdf(data))) {
            String text=new PDFTextStripper().getText(doc);
            assertTrue(text.contains("До"));
            assertTrue(text.contains("После"));
            assertTrue(text.contains("одинаковый"));
            assertTrue(text.contains("Добавленные"));
            assertTrue(text.contains("Удалённые"));
            // The common first line must have identical drawn length in both panels,
            // even though the after network extends beyond the before network.
            List<Double> pathLengths=new ArrayList<>();
            for(org.apache.pdfbox.pdmodel.PDPage page:doc.getPages()) {
                PDFStreamParser parser=new PDFStreamParser(page);parser.parse();
                List<Object> args=new ArrayList<>();
                double width=0,lastX=0,lastY=0,length=0;
                boolean path=false;
                for(Object token:parser.getTokens()) {
                    if(token instanceof Operator) {
                        String op=((Operator)token).getName();
                        if("w".equals(op))width=((COSNumber)args.get(0)).doubleValue();
                        if("m".equals(op)) {
                            lastX=((COSNumber)args.get(0)).doubleValue();
                            lastY=((COSNumber)args.get(1)).doubleValue();path=true;length=0;
                        } else if("l".equals(op)&&path) {
                            double x=((COSNumber)args.get(0)).doubleValue(),y=((COSNumber)args.get(1)).doubleValue();
                            length+=Math.hypot(x-lastX,y-lastY);lastX=x;lastY=y;
                        } else if("S".equals(op)) {
                            if(path&&Math.abs(width-1.7)<0.01)pathLengths.add(length);
                            path=false;
                        }
                        args.clear();
                    } else args.add(token);
                }
            }
            assertEquals(3,pathLengths.size(),"Full network lines must be present in both panels");
            assertTrue(pathLengths.get(0)>1);
            assertEquals(pathLengths.get(0),pathLengths.get(1),0.001,
                    "Equal metric geometry must have equal drawing scale before and after");
        }
    }

    @Test void changedLostRestoredAndTypedOksKeysSurviveMultipageExport() throws Exception {
        Map<String,Object> data=comparison();
        List<Map<String,Object>> rows=list(data,"connections");
        rows.add(connection("s:2","Строковый 2","lost"));
        rows.add(connection("n:2","Числовой 2","restored"));
        for(int i=0;i<85;i++) rows.add(connection("s:building-"+i,"Здание "+i,"changed"));
        try(PDDocument doc=PDDocument.load(pdf(data))) {
            String text=new PDFTextStripper().getText(doc);
            assertTrue(doc.getNumberOfPages()>=4);
            assertTrue(text.contains("s:2"));
            assertTrue(text.contains("n:2"));
            assertTrue(text.contains("Утрачено"));
            assertTrue(text.contains("Восстановлено"));
            assertTrue(text.contains("building-84"));
        }
    }

    @Test void longVariantAndOksIdentifiersWrapWithoutEscapingPage() throws Exception {
        Map<String,Object> data=comparison();
        obj(data,"before").put("variant_id","вариант_".repeat(200)+"😀");
        list(data,"connections").get(0).put("label","очень_длинный_ОКС_".repeat(400));
        list(data,"connections").get(0).put("oks_key","s:"+"ID".repeat(600));
        try(PDDocument doc=PDDocument.load(pdf(data))) {
            assertTrue(doc.getNumberOfPages()>3);
            for(org.apache.pdfbox.pdmodel.PDPage page:doc.getPages()) {
                PDFStreamParser parser=new PDFStreamParser(page);parser.parse();
                List<Object> args=new ArrayList<>();
                for(Object token:parser.getTokens()) {
                    if(token instanceof Operator) {
                        String op=((Operator)token).getName();
                        if(("m".equals(op)||"l".equals(op)||"re".equals(op))&&args.size()>=2) {
                            float y=((COSNumber)args.get(1)).floatValue();
                            assertTrue(y>=46 && y<=page.getMediaBox().getHeight()-35,
                                    "Drawing outside content area: "+op+" y="+y);
                        }
                        args.clear();
                    }else args.add(token);
                }
            }
        }
    }

    @Test void depthComparisonDoesNotInventDepthProfileOrNormativeApproval() throws Exception {
        Map<String,Object> data=comparison();
        obj(data,"before").put("mode","depth");obj(data,"after").put("mode","depth");
        obj(data,"before").put("variant_id","d1");obj(data,"after").put("variant_id","d1");
        try(PDDocument doc=PDDocument.load(pdf(data))) {
            String text=new PDFTextStripper().getText(doc);
            assertTrue(text.contains("глубинный"));
            assertTrue(text.contains("XY"));
            assertTrue(text.contains("нормативный аудит"));
            assertFalse(text.contains("Все ограничения соблюдены"));
        }
    }

    @Test void newlyAddedRestrictionIsIdentifiedWithoutClaimingCompleteInputDiff() throws Exception {
        Map<String,Object> data=comparison();
        data.put("input_changes",map("added_restrictions",List.of(map("key","s:new-zone",
                "label","Новая запретная зона","restriction_type","prohibited_site",
                "geometry",map("type","Polygon","coordinates",List.of(List.of(
                        List.of(37.5001,55.6999),List.of(37.5003,55.6999),
                        List.of(37.5003,55.7001),List.of(37.5001,55.7001),List.of(37.5001,55.6999)))))),
                "removed_restrictions",List.of()));
        try(PDDocument doc=PDDocument.load(pdf(data))) {
            String text=new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Новая запретная зона"));
            assertTrue(text.contains("контур будущей зоны"));
            assertTrue(text.contains("не полный"));
        }
    }

    @Test void decimalCostsDoNotLoseUnitsBeyondDoubleIntegerPrecision() throws Exception {
        Map<String,Object> data=comparison();
        obj(obj(data,"after"),"summary").put("construction_cost",new java.math.BigDecimal("9007199254740997"));
        obj(data,"delta").put("construction_cost",new java.math.BigDecimal("9007199253740997"));
        try(PDDocument doc=PDDocument.load(pdf(data))) {
            String text=new PDFTextStripper().getText(doc).replaceAll("\\s+","");
            assertTrue(text.contains("9007199254740997"),"Saved decimal cost must retain every displayed ruble");
            assertTrue(text.contains("+9007199253740997"),"Saved decimal delta must retain every displayed ruble");
        }
    }

    @Test void recordsEffectiveRulesHashesForBothCalculations() throws Exception {
        Map<String,Object> data=comparison();
        obj(data,"before").put("effective_rules_sha256","e".repeat(64));
        obj(data,"after").put("effective_rules_sha256","f".repeat(64));
        try(PDDocument doc=PDDocument.load(pdf(data))) {
            String text=new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Правила SHA-256"));
            assertTrue(text.contains("e".repeat(64)));
            assertTrue(text.contains("f".repeat(64)));
        }
    }

    @Test void distinguishesSegmentAttributeChangeAndUnavailableComparisonFromUnchangedXy() throws Exception {
        Map<String,Object> data=comparison();
        Map<String,Object> changed=list(data,"connections").get(0);
        obj(changed,"changes").put("path_geometry",false);
        obj(changed,"changes").put("segment_attributes",true);
        Map<String,Object> moved=connection("s:moved","Другой путь","changed");
        obj(moved,"changes").put("segment_attributes",null);
        obj(moved,"changes").put("segment_attributes_note","Атрибуты не сопоставлены: изменилась XY-трасса.");
        list(data,"connections").add(moved);
        try(PDDocument doc=PDDocument.load(pdf(data))) {
            String text=new PDFTextStripper().getText(doc).replaceAll("\\s+"," ");
            assertTrue(text.contains("атрибуты участков"));
            assertTrue(text.contains("Атрибуты не сопоставлены: изменилась XY-трасса."));
            assertTrue(text.contains("глубины"));
        }
    }

    @Test void completeNotesDoNotCreateAlmostEmptyTailPage() throws Exception {
        Map<String,Object> data=comparison();
        Map<String,Object> row=list(data,"connections").get(0);
        row.put("label","2");row.put("oks_key","n:2");
        obj(row,"changes").put("entry",false);
        obj(row,"changes").put("diameter_set",false);
        obj(row,"changes").put("segment_attributes",null);
        obj(row,"changes").put("segment_attributes_note","Атрибуты участков не сопоставляются по разным трассам или различающемуся более чем на 0,01 м пикетажу; исходные значения сохранены в route_segments.");
        obj(data,"before").put("effective_rules_sha256","e".repeat(64));
        obj(data,"after").put("effective_rules_sha256","f".repeat(64));
        data.put("notes",List.of(
                "Отчёт читает сохранённые расчёты; маршрутизация, пересчёт стоимости и новый нормативный аудит не выполняются.",
                "Все дельты равны «после минус до». Сравнение относится только к явно выбранным вариантам.",
                "Геометрия сравнивается в EPSG:32637 с допуском 0,01 м независимо от идентификаторов и разбиения сегментов. Добавленная и удалённая длина — части вне коридора этого допуска, а не разность сметных длин.",
                "Стоимость пути ОКС включает общие участки, без камер и врезок. Это не цена отдельного подключения; стоимости строк ОКС нельзя суммировать.",
                "При неизвестной или неоднозначной внешней стене её изменение не определяется. Отсутствующие величины обозначены null, а не нулём.",
                "Атрибуты участков сопоставляются по пикетажу только для совпадающей XY-трассы и длины. Разбиение и ориентация участков не определяют равенство. Допуски пикетажа и глубины — 0,01 м; расхода — 0,000001 т/ч. Это сравнение сохранённых ДУ, способа прокладки, расходов и глубин, а не аудит допустимости.",
                "Различия не доказывают глобальный оптимум или единственную причину изменения сети. Проверьте различия входных данных, правил и версий расчётного сервиса.",
                "input_changes перечисляет только добавленные и удалённые ограничения по типизированному ID. Это не полный анализ изменений геометрии, атрибутов или YAML; SHA фиксируют исходные файлы и эффективные правила."));
        try(PDDocument doc=PDDocument.load(pdf(data))) {
            assertEquals(3,doc.getNumberOfPages(),
                    "Compact one-change report must not leave a fourth page with only the SHA explanation");
            String text=new PDFTextStripper().getText(doc).replaceAll("\\s+"," ");
            assertTrue(text.contains("SHA-256 идентифицирует точные исходные файлы"));
            assertTrue(text.contains("input_changes перечисляет"));
        }
    }

    private byte[] pdf(Map<String,Object> data)throws Exception {
        Class<?> type=assertDoesNotThrow(()->Class.forName("ru.lct.heatnet.report.ComparisonPdfWriter"),
                "Comparison PDF writer is required");
        Method writer=type.getMethod("write",java.io.OutputStream.class,Map.class);
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        writer.invoke(null,out,data);return out.toByteArray();
    }
    static Map<String,Object> comparison() {
        List<Object> first=List.of(List.of(37.5,55.7),List.of(37.5004,55.7));
        List<Object> extension=List.of(List.of(37.5004,55.7),List.of(37.5004,55.7008));
        return map("schema_version",1,
                "before",map("variant_id","v1","mode","base","input_sha256","a".repeat(64),
                        "result_sha256","b".repeat(64),"summary",summary(false)),
                "after",map("variant_id","v1","mode","base","input_sha256","c".repeat(64),
                        "result_sha256","d".repeat(64),"summary",summary(true)),
                "delta",map("construction_cost",250000,"calculated_cost",350000,
                        "unconnected_penalty",100000,"new_network_length",12.5,"connected_count",-1,"oks_count",0,
                        "new_chamber_count",1,"chamber_construction_cost",50000,
                        "existing_chamber_tie_in_count",0,"existing_chamber_tie_in_cost",0,"score",2.5),
                "connections_summary",map("unchanged",0,"changed",1,"lost",0,"restored",0,"added",0,"removed",0,"still_unconnected",0),
                "connections",new ArrayList<>(List.of(connection("n:9007199254740993","9007199254740993","changed"))),
                "network_geometry",map("tolerance_m",0.01,"before_length_m",25.0,"after_length_m",114.0,
                        "removed_length_m",0.0,"added_length_m",89.0,
                        "before",List.of(first),"after",List.of(first,extension),"removed",List.of(),"added",List.of(extension)),
                "notes",List.of("Новый расчёт и нормативный аудит не выполняется.",
                        "Различие результатов не доказывает глобальный оптимум."));
    }
    static Map<String,Object> summary(boolean after) {
        return map("construction_cost",after?1250000:1000000,"unconnected_penalty",after?100000:0,
                "calculated_cost",after?1350000:1000000,"score",after?12.5:10.0,
                "new_network_length",after?112.5:100.0,"connected_count",after?2:3,"oks_count",3,
                "new_chamber_count",after?2:1,"chamber_construction_cost",after?100000:50000,
                "existing_chamber_tie_in_count",1,"existing_chamber_tie_in_cost",15000);
    }
    static Map<String,Object> connection(String key,String label,String status) {
        Map<String,Object> snap=map("connected",true,"flow_tph",15.0,
                "route_summary",map("total_length",45.5),"costs",map("path_segment_cost",70000));
        return map("oks_key",key,"label",label,"status",status,"before",snap,"after",snap,
                "changes",map("path_geometry",true,"source",false,"entry",null,"load",false,
                        "location",false,"diameter_set",true),
                "delta",map("total_length",5.5,"path_segment_cost",3000,"flow_tph",0));
    }
    static Map<String,Object> map(Object... kv) {
        Map<String,Object> m=new LinkedHashMap<>();
        for(int i=0;i<kv.length;i+=2)m.put((String)kv[i],kv[i+1]);return m;
    }
    @SuppressWarnings("unchecked") static Map<String,Object> obj(Map<String,Object> m,String key){return (Map<String,Object>)m.get(key);}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> list(Map<String,Object> m,String key){return (List<Map<String,Object>>)m.get(key);}
}
