package ru.lct.heatnet.report;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSNumber;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionPdfWriterTest {
    @Test void basePassportContainsFactsButDoesNotInventDepthAndDoesNotMutateData() throws Exception {
        Map<String,Object> card=card(false);
        String before=card.toString();
        try(PDDocument doc=PDDocument.load(pdf(card,null))) {
            String text=new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Паспорт подключения"));
            assertTrue(text.contains("9007199254740993"));
            assertTrue(text.contains("Схема пути"));
            assertTrue(text.contains("не цена отдельного подключения"));
            assertTrue(text.contains("Глубины в базовом варианте не заданы"));
            assertFalse(text.contains("Продольный профиль"));
            assertEquals(before,card.toString());
        }
    }
    @Test void depthPassportIncludesProfileObstaclesSlopesAndEverySegmentAcrossPages() throws Exception {
        Map<String,Object> card=card(true);
        List<Map<String,Object>> segments=list(card,"segments");
        for(int i=2;i<70;i++) segments.add(segment("segment-"+i,i*10,(i+1)*10,true));
        Map<String,Object> profile=map("variant_id","d1","oks",card.get("oks"),"segments",segments,
                "obstacles",card.get("obstacles"),"total_length",700.0,"max_abs_slope",0.1);
        try(PDDocument doc=PDDocument.load(pdf(card,profile))) {
            String text=new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Продольный профиль"));
            assertTrue(text.contains("Трамвай"));
            assertTrue(text.contains("4,20"));
            assertTrue(text.contains("segment-69"));
            assertTrue(text.contains("Уклон"));
            assertTrue(doc.getNumberOfPages()>=4);
        }
    }
    @Test void unconnectedPassportReportsStatusWithoutMapProfileOrCost() throws Exception {
        Map<String,Object> card=map("variant_id","v1","status","unconnected",
                "oks",map("key","s:missing","label","Не подключён","flow_tph",7.0),
                "message","ОКС не подключён. История причин поиска не сохраняется.",
                "segments",List.of(),"notes",List.of("Это не доказательство невозможности подключения."));
        try(PDDocument doc=PDDocument.load(pdf(card,null))) {
            String text=new PDFTextStripper().getText(doc);
            assertEquals(1,doc.getNumberOfPages());
            assertTrue(text.contains("История причин поиска"));
            assertFalse(text.contains("Схема пути"));
            assertFalse(text.contains("Продольный профиль"));
        }
    }
    @Test void longIdentifiersAndUnsupportedGlyphsDoNotBreakExport() throws Exception {
        Map<String,Object> card=card(false);
        ((Map<String,Object>)card.get("oks")).put("label","длинное_имя_".repeat(45)+"😀\n\t");
        assertTrue(pdf(card,null).length>10000);
    }
    @Test void depthGraphStaysInsidePageAfterLongLabel() throws Exception {
        Map<String,Object> card=card(true);
        ((Map<String,Object>)card.get("oks")).put("label","длинное_имя_".repeat(240));
        Map<String,Object> profile=map("variant_id","d1","oks",card.get("oks"),"segments",card.get("segments"),
                "obstacles",card.get("obstacles"),"total_length",30.0,"max_abs_slope",0.1);
        try(PDDocument doc=PDDocument.load(pdf(card,profile))) {
            for(org.apache.pdfbox.pdmodel.PDPage page:doc.getPages()) {
                PDFStreamParser parser=new PDFStreamParser(page);parser.parse();
                List<Object> args=new ArrayList<>();
                for(Object t:parser.getTokens()) {
                    if(t instanceof Operator) {
                        String op=((Operator)t).getName();
                        if(("m".equals(op)||"l".equals(op)||"re".equals(op))&&args.size()>=2) {
                            float yy=((COSNumber)args.get(1)).floatValue();
                            assertTrue(yy>=48 && yy<=page.getMediaBox().getHeight()-40,
                                    "Graph/table geometry escapes content area: "+op+" y="+yy);
                        }
                        args.clear();
                    } else args.add(t);
                }
            }
        }
    }
    private byte[] pdf(Map<String,Object> card,Map<String,Object> profile)throws Exception {
        Class<?> type=assertDoesNotThrow(()->Class.forName("ru.lct.heatnet.report.ConnectionPdfWriter"),
                "PDF passport writer is required");
        Method writer=type.getMethod("write",java.io.OutputStream.class,Map.class,Map.class);
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        writer.invoke(null,out,card,profile);return out.toByteArray();
    }
    static Map<String,Object> card(boolean depth) {
        List<Map<String,Object>> segments=new ArrayList<>(List.of(segment("shared",0,10,depth),segment("tail",10,30,depth)));
        return map("variant_id",depth?"d1":"v1","status","connected",
                "oks",map("key","n:9007199254740993","label","9007199254740993","flow_tph",1.0,"coordinates",List.of(37.5004,55.7)),
                "source",map("label","source","kind","existing_chamber","coordinates",List.of(37.5,55.7)),
                "route_summary",map("total_length",30.0,"shared_length",10.0,"dedicated_length",20.0,"special_length",10.0,"internal_length",5.0),
                "diameters",List.of(100),"segments",segments,
                "entry",map("status","available","wall_label","Внешняя стена №4","coordinates",List.of(37.5003,55.7),
                        "wall_coordinates",List.of(List.of(37.5003,55.6999),List.of(37.5003,55.7001)),"internal_length",5.0),
                "costs",map("path_segment_cost",3000.0,"shared_segment_cost",1000.0,"dedicated_segment_cost",2000.0,
                        "note","Стоимость участков пути: не цена отдельного подключения."),
                "explanations",List.of(map("text","Путь пересекает габарит: Трамвай.")),
                "obstacles",List.of(map("label","Трамвай · 903","type","tram_tracks","from",11.0,"to",19.0,
                        "top_depth",null,"bottom_depth",null,"min_top_depth",4.2,"role","crossing")),
                "notes",List.of("История отклонённых альтернатив не сохраняется."));
    }
    static Map<String,Object> segment(String id,double from,double to,boolean depth) {
        return map("id",id,"from",from,"to",to,"depth_start",depth?3.0:null,"depth_end",depth?4.2:null,
                "height",0.5,"slope",depth?0.1:null,"diameter",100,"flow_tph",1.0,"cost",1000.0,
                "laying_method","special","shared",id.equals("shared"),
                "coordinates",List.of(List.of(37.5+from/100000,55.7),List.of(37.5+to/100000,55.7)));
    }
    static Map<String,Object> map(Object... kv) {
        Map<String,Object> m=new LinkedHashMap<>();
        for(int i=0;i<kv.length;i+=2)m.put((String)kv[i],kv[i+1]);return m;
    }
    @SuppressWarnings("unchecked") static List<Map<String,Object>> list(Map<String,Object> m,String k){return (List<Map<String,Object>>)m.get(k);}
}
