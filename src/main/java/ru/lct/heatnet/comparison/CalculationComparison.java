package ru.lct.heatnet.comparison;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.util.JsonParserDelegate;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import ru.lct.heatnet.geo.GeoJsonReader;
import ru.lct.heatnet.geo.Projection;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.profile.ProfileScene;
import ru.lct.heatnet.refdata.RestrictionRules;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Read-only comparison of persisted calculations; never calls the routing engine. */
public final class CalculationComparison {
    private static final double TOLERANCE = 0.01;
    private static final double GEOMETRY_EPSILON = 1e-7;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
    private static final List<String> SAVED_METRICS = List.of("construction_cost", "unconnected_penalty",
            "calculated_cost", "score", "new_network_length", "chamber_construction_cost",
            "existing_chamber_tie_in_count", "existing_chamber_tie_in_cost");
    private static final List<String> ROUTE_METRICS = List.of("total_length", "shared_length",
            "dedicated_length", "special_length", "internal_length");
    private static final List<String> COST_METRICS = List.of("path_segment_cost", "shared_segment_cost",
            "dedicated_segment_cost");

    private CalculationComparison() {}

    public static Map<String,Object> compare(Path beforeInput, Path beforeResult, RestrictionRules beforeRules,
                                             String beforeVariant, Path afterInput, Path afterResult,
                                             RestrictionRules afterRules, String afterVariant) throws IOException {
        boolean depth = depthMode(beforeVariant);
        if (depth != depthMode(afterVariant)) throw invalid("Нельзя сравнить базовый и глубинный режимы");
        Scene before = read(beforeInput,beforeResult,beforeRules,beforeVariant,depth);
        Scene after = read(afterInput,afterResult,afterRules,afterVariant,depth);
        GeometryComparison geometry = new GeometryComparison();
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("schema_version",1);
        report.put("comparison_tolerances",mapOf("xy_m",TOLERANCE,"station_m",TOLERANCE,
                "depth_m",TOLERANCE,"flow_tph",0.000001));
        report.put("before",before.metadata); report.put("after",after.metadata);
        report.put("delta",deltas(before.summary,after.summary));
        Map<String,Integer> counts = new LinkedHashMap<>();
        for (String status : List.of("unchanged","changed","lost","restored","added","removed","still_unconnected"))
            counts.put(status,0);
        List<Map<String,Object>> rows = new ArrayList<>();
        SortedSet<String> keys = new TreeSet<>(before.connections.keySet());keys.addAll(after.connections.keySet());
        for (String key : keys) {
            Map<String,Object> old = before.connections.get(key), current = after.connections.get(key);
            Map<String,Object> changes = connectionChanges(old,current,geometry);
            Map<String,Object> delta = connectionDelta(old,current);
            String status;
            if (old == null) status="added";
            else if (current == null) status="removed";
            else if (connected(old) && !connected(current)) status="lost";
            else if (!connected(old) && connected(current)) status="restored";
            else if (!connected(old)) status="still_unconnected";
            else status = changed(changes,delta) ? "changed" : "unchanged";
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("oks_key",key);row.put("label",(current==null?old:current).get("label"));row.put("status",status);
            row.put("before",old);row.put("after",current);row.put("changes",changes);row.put("delta",delta);
            rows.add(row);counts.put(status,counts.get(status)+1);
        }
        report.put("connections_summary",counts);report.put("connections",rows);
        report.put("network_geometry",geometry.compare(before.lines,after.lines));
        report.put("input_changes",mapOf(
                "added_restrictions",difference(after.restrictions,before.restrictions),
                "removed_restrictions",difference(before.restrictions,after.restrictions)));
        report.put("notes",List.of(
                "Отчёт читает сохранённые расчёты; маршрутизация, пересчёт стоимости и новый нормативный аудит не выполняются.",
                "Все дельты равны «после минус до». Сравнение относится только к явно выбранным вариантам.",
                "Геометрия сравнивается в EPSG:32637 с допуском 0,01 м независимо от идентификаторов и разбиения сегментов. Добавленная и удалённая длина — части вне коридора этого допуска, а не разность сметных длин.",
                "Стоимость пути ОКС включает общие участки, без камер и врезок. Это не цена отдельного подключения; стоимости строк ОКС нельзя суммировать.",
                "При неизвестной или неоднозначной внешней стене её изменение не определяется. Отсутствующие величины обозначены null, а не нулём.",
                "Атрибуты участков сопоставляются по пикетажу только для совпадающей XY-трассы и длины. Разбиение и ориентация участков не определяют равенство. Допуски пикетажа и глубины — 0,01 м; расхода — 0,000001 т/ч. Это сравнение сохранённых ДУ, способа прокладки, расходов и глубин, а не аудит допустимости.",
                "Различия не доказывают глобальный оптимум или единственную причину изменения сети. Проверьте различия входных данных, правил и версий расчётного сервиса.",
                "input_changes перечисляет только добавленные и удалённые ограничения по типизированному ID. Это не полный анализ изменений геометрии, атрибутов или YAML; SHA фиксируют исходные файлы и эффективные правила."));
        return report;
    }

    private static Scene read(Path input,Path result,RestrictionRules rules,String variant,boolean depth) throws IOException {
        if(rules==null) throw invalid("Не заданы правила сохранённого расчёта");
        JsonNode rawInput=readJson(input), rawResult=readJson(result);
        requireCollection(rawInput,"исходных данных");requireCollection(rawResult,"результата");
        InputModel model;
        try(InputStream in=Files.newInputStream(input)){model=new GeoJsonReader(new Projection()).read(in);}
        Scene saved=new Scene();
        Projection projection=new Projection();
        JsonNode summary=null;
        Set<String> featureIds=new HashSet<>(),networkIds=new HashSet<>();
        Map<String,Coordinate> endpoints=new HashMap<>(),pointFeatures=new LinkedHashMap<>();
        BigDecimal segmentCost=BigDecimal.ZERO,chamberCost=BigDecimal.ZERO,exportedLength=BigDecimal.ZERO;
        int newChambers=0;
        for(JsonNode f:rawResult.get("features")) {
            JsonNode p=f.path("properties");
            if(!variant.equals(p.path("variant_id").asText()))continue;
            String id=key(p.get("id"));
            if(!featureIds.add(id))throw invalid("Повторный ID объекта выбранного варианта: "+id);
            String type=p.path("object_type").asText();
            if("variant_summary".equals(type)) {
                if(summary!=null)throw invalid("Несколько сводок выбранного варианта "+variant);
                summary=p;
            } else if("heat_network".equals(type)) {
                if(!"LineString".equals(f.path("geometry").path("type").asText()))throw invalid("Участок должен быть LineString: "+id);
                List<List<Double>> line=coordinates(f.path("geometry").path("coordinates"));
                if(line.size()<2)throw invalid("Недостаточно координат участка: "+id);
                saved.lines.add(line);networkIds.add(p.get("id").asText());
                segmentCost=segmentCost.add(nonnegative(p,"cost"));
                BigDecimal segmentLength=nonnegative(p,"length");
                exportedLength=exportedLength.add(segmentLength);
                double actualLength=0;
                Coordinate previous=null;
                for(List<Double> point:line) {
                    Coordinate current=projected(point,projection);
                    if(previous!=null)actualLength+=previous.distance(current);
                    previous=current;
                }
                // XY length is rounded to 0.01 m; WGS84 vertices are exported to nine decimal places.
                close(segmentLength,BigDecimal.valueOf(actualLength),Math.max(0.01,0.006+0.0002*line.size()),"length участка "+id);
                endpoints.putIfAbsent(key(p.get("start_node_id")),projected(line.get(0),projection));
                endpoints.putIfAbsent(key(p.get("end_node_id")),projected(line.get(line.size()-1),projection));
                nonnegative(p,"flow_tph");
                BigDecimal du=nonnegative(p,"diameter");
                if(du.signum()==0||du.stripTrailingZeros().scale()>0)throw invalid("Некорректный ДУ участка: "+id);
                if(!Set.of("base","special").contains(p.path("laying_method").asText()))throw invalid("Неизвестный способ прокладки: "+id);
                boolean starts=p.hasNonNull("depth_start"),ends=p.hasNonNull("depth_end");
                if(depth) {nonnegative(p,"depth_start");nonnegative(p,"depth_end");}
                else if(starts||ends)throw invalid("Базовый вариант содержит глубины: "+id);
            } else if("heat_chamber".equals(type)) {
                requirePoint(f.path("geometry"),id);newChambers++;chamberCost=chamberCost.add(nonnegative(p,"cost"));
                pointFeatures.put(id,projected(coordinate(f.path("geometry").path("coordinates")),projection));
            } else if("technical_node".equals(type)) {
                requirePoint(f.path("geometry"),id);
                pointFeatures.put(id,projected(coordinate(f.path("geometry").path("coordinates")),projection));
            }
            else throw invalid("Неизвестный объект выбранного варианта: "+type);
        }
        if(summary==null)throw invalid("Не найдена сводка варианта "+variant);
        for(Map.Entry<String,Coordinate> point:pointFeatures.entrySet()) {
            Coordinate endpoint=endpoints.get(point.getKey());
            if(endpoint==null)throw invalid("Камера или технический узел не связан с сохранёнными участками: "+point.getKey());
            if(endpoint.distance(point.getValue())>TOLERANCE)
                throw invalid("Координаты камеры или технического узла не совпадают с концами участков: "+point.getKey());
        }
        for(String field:SAVED_METRICS)saved.summary.put(field,nonnegative(summary,field));
        BigDecimal tieCount=decimal(saved.summary.get("existing_chamber_tie_in_count"));
        if(tieCount.stripTrailingZeros().scale()>0)throw invalid("existing_chamber_tie_in_count должен быть целым");
        close(decimal(saved.summary.get("chamber_construction_cost")),chamberCost,0.01,"chamber_construction_cost");
        close(decimal(saved.summary.get("construction_cost")),segmentCost.add(chamberCost)
                .add(decimal(saved.summary.get("existing_chamber_tie_in_cost"))),0.01,"construction_cost");
        close(decimal(saved.summary.get("calculated_cost")),decimal(saved.summary.get("construction_cost"))
                .add(decimal(saved.summary.get("unconnected_penalty"))),0.01,"calculated_cost");
        // Each segment is exported at 0.01 m, but the summary rounds their original total only once.
        close(decimal(saved.summary.get("new_network_length")),exportedLength,0.006*(networkIds.size()+1),"new_network_length");
        ProfileScene scene=new ProfileScene(model,rawResult,rules,variant,depth);
        Set<String> expectedUnconnected=new TreeSet<>(),usedSegments=new HashSet<>();
        int connectedCount=0;
        for(Map<String,Object> item:maps(scene.connections().get("oks"))) {
            String oksKey=String.valueOf(item.get("key"));
            Map<String,Object> card=scene.connection(oksKey),snapshot=snapshot(card);
            saved.connections.put(oksKey,snapshot);
            if(connected(snapshot))connectedCount++;else expectedUnconnected.add(oksKey);
            for(Map<String,Object> segment:maps(card.get("segments")))usedSegments.add(String.valueOf(segment.get("id")));
        }
        if(!usedSegments.equals(networkIds))throw invalid("Сохранённая сеть содержит участки, не принадлежащие однозначному пути ОКС");
        JsonNode list=summary.get("unconnected_oks_ids");
        if(list==null||!list.isArray())throw invalid("Нет массива unconnected_oks_ids");
        Set<String> statedUnconnected=new TreeSet<>();
        for(JsonNode id:list)if(!statedUnconnected.add(key(id)))throw invalid("Повторный ОКС в unconnected_oks_ids");
        if(!statedUnconnected.equals(expectedUnconnected))throw invalid("unconnected_oks_ids не согласуется с сохранённой сетью и исходными ОКС");
        saved.summary.put("connected_count",connectedCount);saved.summary.put("oks_count",saved.connections.size());
        saved.summary.put("new_chamber_count",newChambers);
        saved.metadata.put("variant_id",variant);saved.metadata.put("mode",depth?"depth":"base");
        saved.metadata.put("input_sha256",sha(input));saved.metadata.put("result_sha256",sha(result));
        saved.metadata.put("effective_rules_sha256",sha(rules.toYaml().getBytes(StandardCharsets.UTF_8)));
        saved.metadata.put("summary",saved.summary);
        for(JsonNode f:rawInput.get("features")) {
            JsonNode p=f.path("properties");
            if(!"restriction".equals(p.path("object_type").asText()))continue;
            String key=key(p.get("id"));
            saved.restrictions.put(key,mapOf("key",key,"label",p.get("id").asText(),
                    "restriction_type",p.path("restriction_type").asText(),"geometry",MAPPER.convertValue(f.get("geometry"),Object.class)));
        }
        return saved;
    }

    private static Map<String,Object> snapshot(Map<String,Object> card) {
        Map<String,Object> oks=map(card.get("oks"));
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("connected","connected".equals(card.get("status")));result.put("label",oks.get("label"));
        result.put("coordinates",oks.get("coordinates"));result.put("flow_tph",oks.get("flow_tph"));
        for(String field:List.of("source","entry","route_summary","diameters","costs"))result.put(field,card.get(field));
        List<Object> lines=new ArrayList<>();
        List<Map<String,Object>> segments=new ArrayList<>();
        for(Map<String,Object> segment:maps(card.get("segments"))) {
            lines.add(segment.get("coordinates"));
            Map<String,Object> details=new LinkedHashMap<>();
            for(String field:List.of("from","to","coordinates","diameter","laying_method","flow_tph","depth_start","depth_end"))
                details.put(field,segment.get(field));
            segments.add(details);
        }
        result.put("route_coordinates",lines);
        result.put("route_segments",segments);
        return result;
    }

    private static Map<String,Object> connectionChanges(Map<String,Object> before,Map<String,Object> after,GeometryComparison geometry) {
        Map<String,Object> changes=new LinkedHashMap<>();
        boolean both=before!=null&&after!=null, paths=both&&connected(before)&&connected(after);
        changes.put("path_geometry",paths?!geometry.same(lines(before),lines(after)):null);
        changes.put("source",paths?!sameSource(map(before.get("source")),map(after.get("source")),geometry):null);
        changes.put("entry",null);
        Map<String,Object> oldEntry=before==null?null:map(before.get("entry")),newEntry=after==null?null:map(after.get("entry"));
        if(paths&&available(oldEntry)&&available(newEntry)) {
            boolean same=Objects.equals(oldEntry.get("building_key"),newEntry.get("building_key"))
                    &&geometry.near(oldEntry.get("coordinates"),newEntry.get("coordinates"))
                    &&geometry.same(List.of(line(oldEntry.get("wall_coordinates"))),List.of(line(newEntry.get("wall_coordinates"))));
            changes.put("entry",!same);
        } else changes.put("entry_note","Внешняя стена не сравнивается: "+entryReason(oldEntry)+" / "+entryReason(newEntry));
        changes.put("load",both?!numericSame(before.get("flow_tph"),after.get("flow_tph"),0):null);
        changes.put("location",both?!geometry.near(before.get("coordinates"),after.get("coordinates")):null);
        changes.put("diameter_set",paths?!new TreeSet<>(numbers(before.get("diameters"))).equals(new TreeSet<>(numbers(after.get("diameters")))):null);
        if(paths&&Boolean.FALSE.equals(changes.get("path_geometry"))
                &&numericSame(nested(before,"route_summary","total_length"),nested(after,"route_summary","total_length"),TOLERANCE)) {
            changes.put("segment_attributes",!sameAttributes(maps(before.get("route_segments")),maps(after.get("route_segments"))));
        } else {
            changes.put("segment_attributes",null);
            changes.put("segment_attributes_note","Атрибуты участков не сопоставляются по разным трассам или различающемуся более чем на 0,01 м пикетажу; исходные значения сохранены в route_segments.");
        }
        return changes;
    }

    /** ProfileScene already orients every route from its source to its OKS. */
    private static boolean sameAttributes(List<Map<String,Object>> before,List<Map<String,Object>> after) {
        if(before.isEmpty()||after.isEmpty())return before.isEmpty()&&after.isEmpty();
        double end=Math.min(((Number)before.get(before.size()-1).get("to")).doubleValue(),
                ((Number)after.get(after.size()-1).get("to")).doubleValue());
        List<Double> boundaries=new ArrayList<>();
        for(List<Map<String,Object>> route:List.of(before,after))for(Map<String,Object> segment:route)
            boundaries.add(((Number)segment.get("to")).doubleValue());
        Collections.sort(boundaries);
        List<Double> cuts=new ArrayList<>();cuts.add(0.0);
        for(double boundary:boundaries)
            if(boundary<end-TOLERANCE&&boundary-cuts.get(cuts.size()-1)>TOLERANCE)cuts.add(boundary);
        cuts.add(end);
        for(int i=1;i<cuts.size();i++) {
            double from=cuts.get(i-1),to=cuts.get(i),middle=(from+to)/2;
            Map<String,Object> a=at(before,middle),b=at(after,middle);
            if(!numericSame(a.get("diameter"),b.get("diameter"),0)
                    ||!Objects.equals(a.get("laying_method"),b.get("laying_method"))
                    ||!numericSame(a.get("flow_tph"),b.get("flow_tph"),0.000001)
                    ||!numericSame(depthAt(a,from),depthAt(b,from),TOLERANCE)
                    ||!numericSame(depthAt(a,to),depthAt(b,to),TOLERANCE))return false;
        }
        return true;
    }
    private static Map<String,Object> at(List<Map<String,Object>> segments,double station) {
        for(Map<String,Object> segment:segments)if(station<((Number)segment.get("to")).doubleValue())return segment;
        return segments.get(segments.size()-1);
    }
    private static Double depthAt(Map<String,Object> segment,double station) {
        if(segment.get("depth_start")==null||segment.get("depth_end")==null)return null;
        double from=((Number)segment.get("from")).doubleValue(),to=((Number)segment.get("to")).doubleValue();
        double fraction=Math.max(0,Math.min(1,(station-from)/(to-from)));
        double first=((Number)segment.get("depth_start")).doubleValue(),last=((Number)segment.get("depth_end")).doubleValue();
        return first+fraction*(last-first);
    }

    private static boolean sameSource(Map<String,Object> a,Map<String,Object> b,GeometryComparison geometry) {
        if(a==null||b==null)return a==b;
        if(!Objects.equals(a.get("kind"),b.get("kind"))||!geometry.near(a.get("coordinates"),b.get("coordinates")))return false;
        if("existing_chamber".equals(a.get("kind"))&&!Objects.equals(a.get("key"),b.get("key")))return false;
        return new TreeSet<>((List<String>)a.get("existing_pipe_keys")).equals(new TreeSet<>((List<String>)b.get("existing_pipe_keys")));
    }
    private static Map<String,Object> connectionDelta(Map<String,Object> before,Map<String,Object> after) {
        Map<String,Object> delta=new LinkedHashMap<>();
        delta.put("flow_tph",subtract(value(after,"flow_tph"),value(before,"flow_tph")));
        for(String key:ROUTE_METRICS)delta.put(key,subtract(nested(after,"route_summary",key),nested(before,"route_summary",key)));
        for(String key:COST_METRICS)delta.put(key,subtract(nested(after,"costs",key),nested(before,"costs",key)));
        return delta;
    }
    private static boolean changed(Map<String,Object> changes,Map<String,Object> delta) {
        if(changes.values().stream().anyMatch(Boolean.TRUE::equals))return true;
        for(Map.Entry<String,Object> d:delta.entrySet()) {
            if(d.getValue()==null)continue;
            double tolerance=ROUTE_METRICS.contains(d.getKey())?TOLERANCE:0;
            if(decimal(d.getValue()).abs().compareTo(BigDecimal.valueOf(tolerance))>0)return true;
        }
        return false;
    }
    private static Map<String,Object> deltas(Map<String,Object> before,Map<String,Object> after) {
        Map<String,Object> result=new LinkedHashMap<>();
        before.keySet().forEach(k->result.put(k,subtract(after.get(k),before.get(k))));return result;
    }
    private static List<Map<String,Object>> difference(Map<String,Map<String,Object>> first,Map<String,Map<String,Object>> second) {
        List<Map<String,Object>> rows=new ArrayList<>();
        new TreeSet<>(first.keySet()).stream().filter(k->!second.containsKey(k)).forEach(k->rows.add(first.get(k)));return rows;
    }

    private static JsonNode readJson(Path path) throws IOException {
        try(InputStream in=Files.newInputStream(path);
            JsonParser parser=new JsonParserDelegate(MAPPER.getFactory().createParser(in)){
                @Override public boolean isNaN(){return false;}
            }) {
            JsonNode value=MAPPER.readTree(parser);
            if(parser.nextToken()!=null)throw invalid("После JSON обнаружены дополнительные данные");
            return value;
        }
    }
    private static void requireCollection(JsonNode node,String name) {
        if(node==null||!"FeatureCollection".equals(node.path("type").asText())||!node.path("features").isArray())
            throw invalid("Ожидался FeatureCollection "+name);
    }
    private static boolean depthMode(String variant) {
        if(variant==null||!variant.matches("[vd][0-9]+"))throw invalid("Явно укажите variant_id вида v1 или d1");
        return variant.startsWith("d");
    }
    private static BigDecimal nonnegative(JsonNode parent,String field) {
        JsonNode n=parent.get(field);
        if(n==null||!n.isNumber()||!Double.isFinite(n.doubleValue())||n.decimalValue().signum()<0)
            throw invalid("Нет корректного неотрицательного числового поля "+field);
        return n.decimalValue();
    }
    private static String key(JsonNode id) {
        if(id!=null&&id.isNumber())return "n:"+id.decimalValue().stripTrailingZeros().toPlainString();
        if(id!=null&&id.isTextual())return "s:"+id.asText();
        throw invalid("Идентификатор должен быть строкой или числом");
    }
    private static void requirePoint(JsonNode geometry,String id) {
        if(!"Point".equals(geometry.path("type").asText()))throw invalid("Ожидалась точка: "+id);
        coordinate(geometry.path("coordinates"));
    }
    private static List<Double> coordinate(JsonNode p) {
        if(!p.isArray()||p.size()<2||!p.get(0).isNumber()||!p.get(1).isNumber())throw invalid("Некорректная координата результата");
        double x=p.get(0).doubleValue(),y=p.get(1).doubleValue();
        if(!Double.isFinite(x)||!Double.isFinite(y)||Math.abs(x)>180||Math.abs(y)>90)throw invalid("Некорректная координата WGS84");
        return List.of(x,y);
    }
    private static List<List<Double>> coordinates(JsonNode array) {
        if(!array.isArray())throw invalid("Не задан массив координат");List<List<Double>> list=new ArrayList<>();
        for(JsonNode point:array)list.add(coordinate(point));return list;
    }
    private static Coordinate projected(List<Double> wgs,Projection projection) {
        Coordinate result=projection.wgsToUtm(wgs.get(0),wgs.get(1));
        if(!Double.isFinite(result.x)||!Double.isFinite(result.y))throw invalid("Координата не представима в EPSG:32637");
        return result;
    }
    private static void close(BigDecimal a,BigDecimal b,double abs,String field) {
        BigDecimal tolerance=BigDecimal.valueOf(abs).max(a.abs().max(b.abs()).multiply(new BigDecimal("1e-12")));
        if(a.subtract(b).abs().compareTo(tolerance)>0)throw invalid("Сводное поле "+field+" не согласуется с данными результата");
    }
    private static String sha(Path path) throws IOException {
        MessageDigest digest=digest();
        try(InputStream in=Files.newInputStream(path)) {byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);}
        return hex(digest.digest());
    }
    private static String sha(byte[] value){return hex(digest().digest(value));}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static String hex(byte[] value){StringBuilder s=new StringBuilder();for(byte b:value)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    private static IllegalArgumentException invalid(String message){return new IllegalArgumentException(message);}
    private static BigDecimal decimal(Object n){return n instanceof BigDecimal?(BigDecimal)n:new BigDecimal(n.toString());}
    private static Object subtract(Object a,Object b){return a==null||b==null?null:decimal(a).subtract(decimal(b));}
    private static boolean numericSame(Object a,Object b,double tolerance){return a==null||b==null?a==b:decimal(a).subtract(decimal(b)).abs().compareTo(BigDecimal.valueOf(tolerance))<=0;}
    private static boolean connected(Map<String,Object> row){return Boolean.TRUE.equals(row.get("connected"));}
    private static boolean available(Map<String,Object> entry){return entry!=null&&"available".equals(entry.get("status"));}
    private static String entryReason(Map<String,Object> entry){return entry==null?"нет подключённого пути":String.valueOf(entry.get("message"));}
    private static Object value(Map<String,Object> map,String key){return map==null?null:map.get(key);}
    private static Object nested(Map<String,Object> parent,String section,String key){return parent==null?null:value(map(parent.get(section)),key);}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> maps(Object value){return (List<Map<String,Object>>)value;}
    @SuppressWarnings("unchecked") private static List<List<List<Double>>> lines(Map<String,Object> value){return (List<List<List<Double>>>)value.get("route_coordinates");}
    @SuppressWarnings("unchecked") private static List<List<Double>> line(Object value){return (List<List<Double>>)value;}
    private static List<BigDecimal> numbers(Object value){List<BigDecimal> result=new ArrayList<>();for(Object n:(List<?>)value)result.add(decimal(n));return result;}
    private static Map<String,Object> mapOf(Object... values){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);return result;}

    private static final class Scene {
        final Map<String,Object> metadata=new LinkedHashMap<>(),summary=new LinkedHashMap<>();
        final Map<String,Map<String,Object>> connections=new LinkedHashMap<>(),restrictions=new LinkedHashMap<>();
        final List<List<List<Double>>> lines=new ArrayList<>();
    }

    private static final class GeometryComparison {
        private final Projection projection=new Projection();
        private final GeometryFactory gf=new GeometryFactory();
        Map<String,Object> compare(List<List<List<Double>>> before,List<List<List<Double>>> after) {
            Geometry a=union(before),b=union(after),removed=outside(a,b),added=outside(b,a);
            return mapOf("tolerance_m",TOLERANCE,"before_length_m",a.getLength(),"after_length_m",b.getLength(),
                    "removed_length_m",removed.getLength(),"added_length_m",added.getLength(),
                    "before",wgs(a),"after",wgs(b),"removed",wgs(removed),"added",wgs(added));
        }
        boolean same(List<List<List<Double>>> before,List<List<List<Double>>> after) {
            Geometry a=union(before),b=union(after);
            return outside(a,b).getLength()<=GEOMETRY_EPSILON&&outside(b,a).getLength()<=GEOMETRY_EPSILON;
        }
        boolean near(Object first,Object second) {
            if(first==null||second==null)return first==second;
            return projected((List<?>)first).distance(projected((List<?>)second))<=TOLERANCE;
        }
        private Geometry outside(Geometry line,Geometry other) {
            if(line.isEmpty()||other.isEmpty())return line;
            return line.difference(other.buffer(TOLERANCE));
        }
        private Geometry union(List<List<List<Double>>> lines) {
            List<Geometry> geometries=new ArrayList<>();
            for(List<List<Double>> line:lines) {
                Coordinate[] coordinates=new Coordinate[line.size()];
                for(int i=0;i<coordinates.length;i++)coordinates[i]=projected(line.get(i));
                geometries.add(gf.createLineString(coordinates));
            }
            return geometries.isEmpty()?gf.createMultiLineString():UnaryUnionOp.union(geometries);
        }
        private Coordinate projected(List<?> point){return projection.wgsToUtm(((Number)point.get(0)).doubleValue(),((Number)point.get(1)).doubleValue());}
        private List<List<List<Double>>> wgs(Geometry geometry) {
            List<List<List<Double>>> lines=new ArrayList<>();collect(geometry,lines);return lines;
        }
        private void collect(Geometry geometry,List<List<List<Double>>> lines) {
            if(geometry.isEmpty())return;
            if(geometry instanceof LineString) {
                if(geometry.getLength()<=GEOMETRY_EPSILON)return;
                List<List<Double>> line=new ArrayList<>();
                for(Coordinate p:geometry.getCoordinates()){Coordinate w=projection.utmToWgs(p.x,p.y);line.add(List.of(w.x,w.y));}
                lines.add(line);
            } else if(geometry instanceof GeometryCollection)
                for(int i=0;i<geometry.getNumGeometries();i++)collect(geometry.getGeometryN(i),lines);
        }
    }
}
