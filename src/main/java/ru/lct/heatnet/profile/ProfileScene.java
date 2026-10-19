package ru.lct.heatnet.profile;

import com.fasterxml.jackson.databind.JsonNode;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.operation.buffer.BufferOp;
import org.locationtech.jts.operation.buffer.BufferParameters;
import ru.lct.heatnet.geo.Projection;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.refdata.PipeTable;
import ru.lct.heatnet.refdata.RestrictionRule;
import ru.lct.heatnet.refdata.RestrictionRules;
import java.math.BigDecimal;
import java.util.*;

/** Read-only presentation of persisted results. Does not run or modify routing. */
public final class ProfileScene {
    private static final double NODE_TOLERANCE = 0.002;
    private final String variant;
    private final Projection projection = new Projection();
    private final GeometryFactory gf = new GeometryFactory();
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, Map<String,Object>> oks = new LinkedHashMap<>();
    private final List<Obstacle> obstacles = new ArrayList<>();

    public ProfileScene(InputModel input, JsonNode result, RestrictionRules rules, String variant) {
        this.variant = variant;
        boolean found = false;
        Set<String> segmentIds = new HashSet<>();
        Set<String> newChambers = new HashSet<>();
        for (JsonNode f : result.path("features")) {
            JsonNode p = f.path("properties");
            if (!variant.equals(p.path("variant_id").asText())) continue;
            found = true;
            if ("heat_chamber".equals(p.path("object_type").asText())) newChambers.add(key(p.path("id")));
            if (!"heat_network".equals(p.path("object_type").asText())) continue;
            String id = p.path("id").asText();
            if (!segmentIds.add(id)) throw invalid("Повторный идентификатор участка профиля: " + id);
            if (!"LineString".equals(f.path("geometry").path("type").asText())) throw invalid("Неизвестная геометрия участка " + id);
            List<Coordinate> coords = new ArrayList<>();
            List<List<Double>> wgs = new ArrayList<>();
            for (JsonNode c : f.path("geometry").path("coordinates")) {
                double lon = number(c.get(0)), lat = number(c.get(1));
                coords.add(projection.wgsToUtm(lon, lat)); wgs.add(List.of(lon, lat));
            }
            if (coords.size() < 2) throw invalid("Недостаточно точек участка " + id);
            Node a = node(p.path("start_node_id"), coords.get(0), wgs.get(0));
            Node b = node(p.path("end_node_id"), coords.get(coords.size()-1), wgs.get(wgs.size()-1));
            double length = length(coords);
            if (!(length > 1e-8)) throw invalid("Нулевая длина участка " + id);
            Edge edge = new Edge(id, a, b, coords, wgs, length,
                    number(p.get("depth_start")), number(p.get("depth_end")),
                    p.path("diameter").asInt(), p.path("laying_method").asText("base"));
            checkDepth(a, edge.d0); checkDepth(b, edge.d1);
            a.edges.add(edge); b.edges.add(edge);
        }
        if (!found) throw invalid("Глубинный вариант не найден: " + variant);
        for (InputModel.OksPoint o : input.oksPoints) {
            String key = key(o.id);
            Map<String,Object> item = identity(key, String.valueOf(o.id), List.of(o.wgs.x,o.wgs.y));
            item.put("connected", nodes.containsKey(key)); oks.put(key, item);
        }
        for (InputModel.ExistingChamber c : input.chambers) {
            Node node = nodes.get(key(c.id));
            if (node != null) {
                if (node.at.distance(c.utm.getCoordinate()) > NODE_TOLERANCE) throw invalid("Координаты исходной камеры не совпадают");
                node.source = true;
            }
        }
        for (String key : newChambers) {
            Node node = nodes.get(key);
            if (node == null) continue;
            Point at = gf.createPoint(node.at);
            for (InputModel.ExistingPipe pipe : input.pipes) {
                if (pipe.utm.distance(at) <= NODE_TOLERANCE) node.source = true;
            }
        }
        for (InputModel.Restriction r : input.restrictions) {
            RestrictionRule rule = rules.forType(r.restrictionType);
            obstacles.add(new Obstacle(key(r.id), String.valueOf(r.id), r.restrictionType,
                    footprint(r.utm, rule.getOwnHalfWidthM()), rule.getVertical()));
        }
        RestrictionRule crossing = rules.heatNetworkCrossing();
        for (InputModel.ExistingPipe p : input.pipes) {
            RestrictionRule.Vertical v = crossing.getVertical();
            RestrictionRule.Vertical effective = v == null ? null : new RestrictionRule.Vertical(
                    v.underOnly,v.minTopDepthM,v.clearanceM,v.obstacleTopDepthM,PipeTable.byDuAtLeast(p.diameter).getHeightM());
            obstacles.add(new Obstacle(key(p.id), String.valueOf(p.id), "heat_network",
                    footprint(p.utm, PipeTable.byDuAtLeast(p.diameter).getHalfWidthM()), effective));
        }
    }

    public Map<String,Object> list() {
        return Map.of("variant_id",variant,"oks",new ArrayList<>(oks.values()));
    }

    int coordinateCount() {
        int count=nodes.size()+oks.size();
        Set<Edge> edges=new HashSet<>();
        for(Node node:nodes.values()) edges.addAll(node.edges);
        for(Edge edge:edges) count+=edge.coords.size()*2;
        for(Obstacle obstacle:obstacles) count+=obstacle.footprint.getNumPoints();
        return count;
    }

    public Map<String,Object> profile(String key) {
        Map<String,Object> target = oks.get(key);
        if (target == null) throw invalid("ОКС не найден");
        Node end = nodes.get(key);
        if (end == null) throw invalid("ОКС не подключён в выбранном варианте");
        // Inspect the whole component: picking the first source could conceal ambiguous topology.
        Map<Node,Edge> parents = new LinkedHashMap<>();
        Set<Edge> componentEdges = new HashSet<>();
        Deque<Node> queue = new ArrayDeque<>();
        parents.put(end,null); queue.add(end);
        List<Node> sources = new ArrayList<>();
        while (!queue.isEmpty()) {
            Node n = queue.removeFirst();
            if (n.source) sources.add(n);
            for (Edge e : n.edges) {
                componentEdges.add(e);
                Node next = e.other(n);
                if (!parents.containsKey(next)) { parents.put(next,e); queue.addLast(next); }
            }
        }
        if (sources.size()!=1 || componentEdges.size()!=parents.size()-1)
            throw invalid("Не удалось однозначно восстановить путь: ожидается дерево с одной врезкой");
        Node source=sources.get(0), at=source;
        List<Map<String,Object>> segments=new ArrayList<>();
        List<RoutePiece> pieces=new ArrayList<>();
        double station=0, maxSlope=0;
        while (at!=end) {
            Edge e=parents.get(at); Node next=e.other(at);
            boolean forward=e.a==at;
            List<Coordinate> coords=new ArrayList<>(e.coords);
            List<List<Double>> wgs=new ArrayList<>(e.wgs);
            double d0=forward?e.d0:e.d1, d1=forward?e.d1:e.d0;
            if(!forward) { Collections.reverse(coords); Collections.reverse(wgs); }
            double slope=(d1-d0)/e.length; maxSlope=Math.max(maxSlope,Math.abs(slope));
            Map<String,Object> s=new LinkedHashMap<>();
            s.put("id",e.id); s.put("from",station); s.put("to",station+e.length);
            s.put("depth_start",d0); s.put("depth_end",d1); s.put("slope",slope);
            s.put("diameter",e.diameter); s.put("height",PipeTable.byDu(e.diameter).getHeightM());
            s.put("laying_method",e.method); s.put("coordinates",wgs);
            segments.add(s);
            double pieceAt=station;
            for(int i=1;i<coords.size();i++) {
                Coordinate a=coords.get(i-1),b=coords.get(i);
                double len=a.distance(b);
                if(len>1e-8) pieces.add(new RoutePiece(a,b,pieceAt,len));
                pieceAt+=len;
            }
            station+=e.length; at=next;
        }
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("variant_id",variant); result.put("oks",target);
        result.put("source",identity(source.key,source.label,source.wgs));
        result.put("total_length",station); result.put("max_abs_slope",maxSlope);
        result.put("segments",segments); result.put("obstacles",crossings(pieces));
        result.put("notes",List.of(
                "Глубина — до верха расчётного габарита от условной горизонтальной поверхности; рельеф не задан.",
                "Уклоны вычислены по округлённым глубинам GeoJSON и длине геометрии в EPSG:32637; это отображение результата, не новая проверка нормативов.",
                "Полосы препятствий показывают пересечение физического или расчётного габарита. Отступы и полные окна спецпроходов здесь не изображены.",
                "Параметры препятствий: справочник текущего запуска сервера и сохранённый YAML задачи."));
        return result;
    }

    private List<Map<String,Object>> crossings(List<RoutePiece> pieces) {
        List<Map<String,Object>> result=new ArrayList<>();
        for(Obstacle obstacle:obstacles) {
            List<double[]> spans=new ArrayList<>();
            for(RoutePiece piece:pieces) {
                LineString line=gf.createLineString(new Coordinate[]{piece.a,piece.b});
                if(!obstacle.footprint.getEnvelopeInternal().intersects(line.getEnvelopeInternal())) continue;
                Geometry intersection=line.intersection(obstacle.footprint);
                collectSpans(intersection,piece,spans);
            }
            spans.sort(Comparator.comparingDouble(a->a[0]));
            List<double[]> merged=new ArrayList<>();
            for(double[] span:spans) {
                if(!merged.isEmpty() && span[0]<=merged.get(merged.size()-1)[1]+1e-6)
                    merged.get(merged.size()-1)[1]=Math.max(merged.get(merged.size()-1)[1],span[1]);
                else merged.add(span.clone());
            }
            for(double[] span:merged) {
                Map<String,Object> row=new LinkedHashMap<>();
                boolean tieIn="heat_network".equals(obstacle.type)&&span[0]<NODE_TOLERANCE;
                row.put("key",obstacle.key); row.put("id",obstacle.id);
                row.put("label",(tieIn?"Врезка в теплосеть":label(obstacle.type))+" · "+obstacle.id);
                row.put("type",obstacle.type); row.put("from",span[0]); row.put("to",span[1]);
                RestrictionRule.Vertical v=obstacle.vertical;
                boolean knownPhysicalDepth=v!=null&&(!v.underOnly||"heat_network".equals(obstacle.type));
                row.put("top_depth",knownPhysicalDepth?v.obstacleTopDepthM:null);
                row.put("bottom_depth",knownPhysicalDepth?v.obstacleTopDepthM+v.obstacleHeightM:null);
                row.put("min_top_depth",!tieIn&&v!=null&&v.underOnly?v.minTopDepthM:null);
                row.put("clearance",tieIn||v==null?null:v.clearanceM);
                row.put("role",tieIn?"tie_in":"crossing");
                result.add(row);
            }
        }
        result.sort(Comparator.comparingDouble(m->(double)m.get("from")));
        return result;
    }

    private void collectSpans(Geometry g,RoutePiece p,List<double[]> result) {
        if(g.isEmpty()) return;
        if(g instanceof GeometryCollection) { for(int i=0;i<g.getNumGeometries();i++) collectSpans(g.getGeometryN(i),p,result); return; }
        double lo=Double.POSITIVE_INFINITY,hi=Double.NEGATIVE_INFINITY;
        for(Coordinate c:g.getCoordinates()) {
            double t=((c.x-p.a.x)*(p.b.x-p.a.x)+(c.y-p.a.y)*(p.b.y-p.a.y))/(p.length*p.length);
            double s=p.from+Math.max(0,Math.min(1,t))*p.length; lo=Math.min(lo,s); hi=Math.max(hi,s);
        }
        if(Double.isFinite(lo)) result.add(new double[]{lo,hi});
    }

    private Geometry footprint(Geometry geometry,double halfWidth) {
        if(geometry.getDimension()!=1||halfWidth<=0) return geometry;
        return BufferOp.bufferOp(geometry,halfWidth,new BufferParameters(8,BufferParameters.CAP_SQUARE,BufferParameters.JOIN_MITRE,1.42));
    }
    private Node node(JsonNode id,Coordinate at,List<Double> wgs) {
        if(id==null||id.isNull()||id.isMissingNode()) throw invalid("Отсутствует идентификатор узла");
        String key=key(id); Node node=nodes.get(key);
        if(node==null) { node=new Node(key,id.asText(),at,wgs); nodes.put(key,node); }
        else if(node.at.distance(at)>NODE_TOLERANCE) throw invalid("Несогласованные координаты узла "+id.asText());
        return node;
    }
    static String key(Object id) {
        if(id instanceof JsonNode) {
            JsonNode n=(JsonNode)id;
            if(n.isNumber()) return "n:"+n.decimalValue().stripTrailingZeros().toPlainString();
            if(n.isTextual()) return "s:"+n.asText();
            throw invalid("Некорректный идентификатор");
        }
        return id instanceof Number?"n:"+new BigDecimal(id.toString()).stripTrailingZeros().toPlainString():"s:"+id;
    }
    private static double number(JsonNode node) {
        if(node==null||!node.isNumber()||!Double.isFinite(node.doubleValue())) throw invalid("Нет корректных числовых данных глубинного профиля");
        return node.doubleValue();
    }
    private static void checkDepth(Node node,double depth) {
        if(node.depth!=null&&Math.abs(node.depth-depth)>1e-6)
            throw invalid("Несогласованные глубины общего узла "+node.label);
        node.depth=depth;
    }
    private static Map<String,Object> identity(String key,String label,List<Double> coordinates) {
        Map<String,Object> map=new LinkedHashMap<>(); map.put("key",key);map.put("label",label);map.put("coordinates",coordinates);return map;
    }
    private static double length(List<Coordinate> c) { double sum=0;for(int i=1;i<c.size();i++) sum+=c.get(i-1).distance(c.get(i));return sum; }
    private static IllegalArgumentException invalid(String message) {return new IllegalArgumentException(message);}
    private static String label(String type) {
        switch(type) {
            case "road": return "Дорога"; case "tram_tracks": return "Трамвай";
            case "gas_pipeline": return "Газопровод"; case "power_cable": return "Кабель";
            case "heat_network": return "Существующая теплосеть"; case "oks": return "Здание";
            default:return type;
        }
    }
    private static final class Node {
        final String key,label;final Coordinate at;final List<Double>wgs;final List<Edge>edges=new ArrayList<>();boolean source;Double depth;
        Node(String key,String label,Coordinate at,List<Double>wgs){this.key=key;this.label=label;this.at=at;this.wgs=wgs;}
    }
    private static final class Edge {
        final String id,method;final Node a,b;final List<Coordinate>coords;final List<List<Double>>wgs;final double length,d0,d1;final int diameter;
        Edge(String id,Node a,Node b,List<Coordinate>coords,List<List<Double>>wgs,double length,double d0,double d1,int diameter,String method){this.id=id;this.a=a;this.b=b;this.coords=coords;this.wgs=wgs;this.length=length;this.d0=d0;this.d1=d1;this.diameter=diameter;this.method=method;}
        Node other(Node n){return n==a?b:a;}
    }
    private static final class Obstacle {
        final String key,id,type;final Geometry footprint;final RestrictionRule.Vertical vertical;
        Obstacle(String key,String id,String type,Geometry footprint,RestrictionRule.Vertical vertical){this.key=key;this.id=id;this.type=type;this.footprint=footprint;this.vertical=vertical;}
    }
    private static final class RoutePiece {
        final Coordinate a,b;final double from,length;
        RoutePiece(Coordinate a,Coordinate b,double from,double length){this.a=a;this.b=b;this.from=from;this.length=length;}
    }
}
