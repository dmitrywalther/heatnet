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
    private final boolean depthMode;
    private final Projection projection = new Projection();
    private final GeometryFactory gf = new GeometryFactory();
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, Map<String,Object>> oks = new LinkedHashMap<>();
    private final List<Obstacle> obstacles = new ArrayList<>();
    private final Map<String, Double> loads = new LinkedHashMap<>();
    private final Map<String, Edge> edgesById = new LinkedHashMap<>();

    public ProfileScene(InputModel input, JsonNode result, RestrictionRules rules, String variant) {
        this(input,result,rules,variant,true);
    }

    public ProfileScene(InputModel input, JsonNode result, RestrictionRules rules, String variant, boolean depthMode) {
        this.variant = variant;
        this.depthMode = depthMode;
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
                    depthMode?number(p.get("depth_start")):null, depthMode?number(p.get("depth_end")):null,
                    p.path("diameter").asInt(), p.path("laying_method").asText("base"),
                    optionalNumber(p.get("flow_tph")),optionalNumber(p.get("cost")));
            if(depthMode) {checkDepth(a, edge.d0); checkDepth(b, edge.d1);}
            a.edges.add(edge); b.edges.add(edge);
            edgesById.put(id,edge);
        }
        if (!found) throw invalid("Глубинный вариант не найден: " + variant);
        for (InputModel.OksPoint o : input.oksPoints) {
            String key = key(o.id);
            Map<String,Object> item = identity(key, String.valueOf(o.id), List.of(o.wgs.x,o.wgs.y));
            item.put("connected", nodes.containsKey(key)); oks.put(key, item);
            loads.put(key,o.flowTph);
        }
        for (InputModel.ExistingChamber c : input.chambers) {
            Node node = nodes.get(key(c.id));
            if (node != null) {
                if (node.at.distance(c.utm.getCoordinate()) > NODE_TOLERANCE) throw invalid("Координаты исходной камеры не совпадают");
                node.source = true;
                node.sourceKind = "existing_chamber";
            }
        }
        for (String key : newChambers) {
            Node node = nodes.get(key);
            if (node == null) continue;
            Point at = gf.createPoint(node.at);
            for (InputModel.ExistingPipe pipe : input.pipes) {
                if (pipe.utm.distance(at) <= NODE_TOLERANCE) {
                    node.source = true;
                    if(node.sourceKind==null) node.sourceKind="new_chamber_tie";
                }
            }
        }
        for(Node n:nodes.values()) if(n.source) {
            Point at=gf.createPoint(n.at);
            for(InputModel.ExistingPipe pipe:input.pipes)
                if(pipe.utm.distance(at)<=NODE_TOLERANCE) n.sourcePipeKeys.add(key(pipe.id));
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
        if(!depthMode) throw invalid("Глубинный профиль недоступен для базового варианта");
        return presentation(key);
    }

    private Map<String,Object> presentation(String key) {
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
            Double d0=forward?e.d0:e.d1, d1=forward?e.d1:e.d0;
            if(!forward) { Collections.reverse(coords); Collections.reverse(wgs); }
            Double slope=depthMode?(d1-d0)/e.length:null;
            if(slope!=null) maxSlope=Math.max(maxSlope,Math.abs(slope));
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

    /** The same saved graph, with connection facts; never searches for a route. */
    public Map<String,Object> connections() {
        List<Map<String,Object>> choices=new ArrayList<>();
        for(Map.Entry<String,Map<String,Object>> item:oks.entrySet()) {
            Map<String,Object> copy=new LinkedHashMap<>(item.getValue());
            copy.put("flow_tph",loads.get(item.getKey()));choices.add(copy);
        }
        return Map.of("variant_id",variant,"oks",choices);
    }

    @SuppressWarnings("unchecked")
    public Map<String,Object> connection(String key) {
        Map<String,Object> original=oks.get(key);
        if(original==null) throw invalid("ОКС не найден");
        Map<String,Object> target=new LinkedHashMap<>(original);target.put("flow_tph",loads.get(key));
        Map<String,Object> card=new LinkedHashMap<>();
        card.put("variant_id",variant);card.put("status",nodes.containsKey(key)?"connected":"unconnected");card.put("oks",target);
        if(!nodes.containsKey(key)) {
            card.put("source",null);card.put("segments",List.of());card.put("route_summary",null);
            card.put("diameters",List.of());card.put("entry",null);card.put("obstacles",List.of());card.put("costs",null);
            card.put("explanations",List.of());
            String message="ОКС не подключён в этом варианте. История причин поиска не сохраняется; это не доказательство отсутствия допустимого подключения.";
            card.put("message",message);card.put("notes",List.of(message));
            return card;
        }
        List<Double> targetWgs=(List<Double>)target.get("coordinates");
        if(nodes.get(key).at.distance(projection.wgsToUtm(targetWgs.get(0),targetWgs.get(1)))>NODE_TOLERANCE)
            throw invalid("Координаты точки ОКС не совпадают с сохранённым маршрутом");
        Map<String,Object> saved=presentation(key);
        Map<String,Object> source=new LinkedHashMap<>((Map<String,Object>)saved.get("source"));
        Node sourceNode=nodes.get(source.get("key"));
        source.put("kind",sourceNode.sourceKind);source.put("existing_pipe_keys",new ArrayList<>(sourceNode.sourcePipeKeys));
        Map<Edge,Integer> usage=usageCounts(sourceNode);
        List<Map<String,Object>> segments=(List<Map<String,Object>>)saved.get("segments");
        double sharedLength=0,dedicatedLength=0,specialLength=0,sharedCost=0,dedicatedCost=0;
        SortedSet<Integer> diameters=new TreeSet<>();
        for(Map<String,Object> segment:segments) {
            Edge e=edgesById.get(segment.get("id"));
            if(e.flow==null||e.cost==null||e.flow<0||e.cost<0) throw invalid("Нет корректных расхода или стоимости участка "+e.id);
            int count=usage.get(e);boolean shared=count>1;
            segment.put("flow_tph",e.flow);segment.put("cost",e.cost);segment.put("usage_count",count);segment.put("shared",shared);
            if(shared) {sharedLength+=e.length;sharedCost+=e.cost;} else {dedicatedLength+=e.length;dedicatedCost+=e.cost;}
            if("special".equals(e.method)) specialLength+=e.length;
            diameters.add(e.diameter);
        }
        List<RoutePiece> pieces=pieces(segments);
        Map<String,Object> entry=entry(nodes.get(key),pieces,(double)saved.get("total_length"));
        Map<String,Object> summary=new LinkedHashMap<>();
        summary.put("total_length",saved.get("total_length"));summary.put("shared_length",sharedLength);
        summary.put("dedicated_length",dedicatedLength);summary.put("special_length",specialLength);
        summary.put("internal_length","available".equals(entry.get("status"))?entry.get("internal_length"):null);
        Map<String,Object> costs=new LinkedHashMap<>();
        costs.put("path_segment_cost",sharedCost+dedicatedCost);costs.put("shared_segment_cost",sharedCost);costs.put("dedicated_segment_cost",dedicatedCost);
        costs.put("note","Сумма стоимостей участков выбранного пути из GeoJSON, без камер и врезок. Общие участки обслуживают несколько ОКС; это не цена отдельного подключения и не сумма для сложения карточек.");
        List<Map<String,Object>> explanations=new ArrayList<>();
        explanations.add(fact("source","Путь начинается во врезке "+source.get("label")+" и заканчивается в точке ОКС "+target.get("label")+".",
                Map.of("kind","source","coordinates",source.get("coordinates"))));
        if(sharedLength>0) {
            Map<String,Object> shared=segments.stream().filter(s->Boolean.TRUE.equals(s.get("shared"))).findFirst().orElseThrow();
            explanations.add(fact("shared","Часть пути используется совместно с другими ОКС. ДУ и расход показаны для всей нагрузки соответствующего участка.",
                    Map.of("kind","segment","segment_id",shared.get("id"))));
        } else explanations.add(fact("dedicated","Все участки этого пути в сохранённом дереве ведут только к выбранному ОКС.",null));
        if("available".equals(entry.get("status"))) explanations.add(fact("entry",entry.get("wall_label")+": конечный ввод восстановлен по геометрии сохранённого пути.",
                Map.of("kind","entry","coordinates",entry.get("coordinates"),"wall_coordinates",entry.get("wall_coordinates"))));
        else explanations.add(fact("entry",String.valueOf(entry.get("message")),null));
        if(specialLength>0) {
            Map<String,Object> special=segments.stream().filter(s->"special".equals(s.get("laying_method"))).findFirst().orElseThrow();
            explanations.add(fact("special","В пути есть участки специальной прокладки. Их границы и стоимость взяты из результата расчёта.",
                    Map.of("kind","segment","segment_id",special.get("id"))));
        }
        List<Map<String,Object>> crossings=(List<Map<String,Object>>)saved.get("obstacles");
        for(Map<String,Object> obstacle:crossings) {
            if("tie_in".equals(obstacle.get("role"))||"oks".equals(obstacle.get("type"))) continue;
            double station=((double)obstacle.get("from")+(double)obstacle.get("to"))/2;
            String segmentId=String.valueOf(segments.get(segments.size()-1).get("id"));
            for(Map<String,Object> segment:segments) if(station<=(double)segment.get("to")+1e-6) {segmentId=String.valueOf(segment.get("id"));break;}
            boolean touch=(double)obstacle.get("to")-(double)obstacle.get("from")<=NODE_TOLERANCE;
            explanations.add(fact("obstacle",(touch?"Путь касается границы габарита: ":"Путь пересекает габарит: ")+obstacle.get("label")+".",
                    Map.of("kind","obstacle","key",obstacle.get("key"),"segment_id",segmentId,"coordinates",position(pieces,station))));
        }
        card.put("source",source);card.put("segments",segments);card.put("route_summary",summary);card.put("diameters",new ArrayList<>(diameters));
        card.put("entry",entry);card.put("obstacles",crossings);card.put("costs",costs);card.put("explanations",explanations);
        List<String> notes=new ArrayList<>();
        notes.add("Карточка объясняет геометрию и состав сохранённого результата. История отклонённых маршрутов и стен не сохраняется; глобальная оптимальность не утверждается.");
        notes.add("Общие участки определены по числу ОКС, использующих их в дереве; ОКС с нулевой нагрузкой также учитываются.");
        notes.add("Длины измерены по геометрии в EPSG:32637 и могут отличаться от суммы округлённых длин в выгрузке.");
        if(depthMode) notes.addAll((List<String>)saved.get("notes"));
        else notes.add("Базовый вариант не содержит глубин; вертикальные параметры препятствий показаны по текущему справочнику сервера и сохранённому YAML, без утверждения о вертикальном прохождении.");
        card.put("notes",notes);return card;
    }

    private Map<Edge,Integer> usageCounts(Node source) {
        Map<Node,Edge> parents=new LinkedHashMap<>();List<Node> order=new ArrayList<>();
        Deque<Node> queue=new ArrayDeque<>();queue.add(source);parents.put(source,null);
        while(!queue.isEmpty()) {
            Node at=queue.removeFirst();order.add(at);
            for(Edge edge:at.edges) {Node next=edge.other(at);if(!parents.containsKey(next)){parents.put(next,edge);queue.addLast(next);}}
        }
        Map<Node,Integer> counts=new HashMap<>();Map<Edge,Integer> usage=new HashMap<>();
        for(int i=order.size()-1;i>=0;i--) {
            Node node=order.get(i);int count=counts.getOrDefault(node,0)+(oks.containsKey(node.key)?1:0);
            Edge parent=parents.get(node);if(parent!=null){usage.put(parent,count);Node next=parent.other(node);counts.merge(next,count,Integer::sum);}
        }
        return usage;
    }

    @SuppressWarnings("unchecked")
    private List<RoutePiece> pieces(List<Map<String,Object>> segments) {
        List<RoutePiece> pieces=new ArrayList<>();
        for(Map<String,Object> segment:segments) {
            List<List<Double>> coordinates=(List<List<Double>>)segment.get("coordinates");double station=(double)segment.get("from");
            for(int i=1;i<coordinates.size();i++) {
                List<Double> first=coordinates.get(i-1),second=coordinates.get(i);
                Coordinate a=projection.wgsToUtm(first.get(0),first.get(1)),b=projection.wgsToUtm(second.get(0),second.get(1));double len=a.distance(b);
                if(len>1e-8) pieces.add(new RoutePiece(a,b,station,len));station+=len;
            }
        }
        return pieces;
    }

    private Map<String,Object> entry(Node end,List<RoutePiece> pieces,double total) {
        List<Building> owners=new ArrayList<>();Point endpoint=gf.createPoint(end.at);
        for(Obstacle obstacle:obstacles) if("oks".equals(obstacle.type)) {
            for(int i=0;i<obstacle.footprint.getNumGeometries();i++) {
                Geometry component=obstacle.footprint.getGeometryN(i);
                if(component instanceof Polygon&&component.covers(endpoint)) owners.add(new Building(obstacle,(Polygon)component));
            }
        }
        if(owners.isEmpty()) return unavailableEntry("unavailable","Точка ОКС не находится внутри однозначно определённого полигона здания; внешняя стена не определена.");
        if(owners.size()!=1) return unavailableEntry("ambiguous","Точка ОКС принадлежит нескольким полигонам здания; ввод нельзя определить однозначно.");
        Building owner=owners.get(0);List<double[]> contacts=new ArrayList<>();
        for(RoutePiece piece:pieces) {
            Geometry intersection=gf.createLineString(new Coordinate[]{piece.a,piece.b}).intersection(owner.polygon.getBoundary());
            if(intersection.getLength()>NODE_TOLERANCE) return unavailableEntry("ambiguous","Есть участок вдоль границы здания; единственная внешняя стена не определена.");
            collectSpans(intersection,piece,contacts);
        }
        contacts.sort(Comparator.comparingDouble(c->c[0]));List<Double> stations=new ArrayList<>();
        for(double[] contact:contacts) if(stations.isEmpty()||Math.abs(contact[0]-stations.get(stations.size()-1))>NODE_TOLERANCE) stations.add(contact[0]);
        if(stations.size()!=1) return unavailableEntry("ambiguous","В сохранённом пути нет единственного контакта с границей здания; конечный ввод не определён.");
        double station=stations.get(0);Coordinate at=coordinate(pieces,station);
        if(owner.polygon.getExteriorRing().distance(gf.createPoint(at))>NODE_TOLERANCE)
            return unavailableEntry("unavailable","Контакт относится к внутреннему кольцу здания, а не к внешней стене.");
        // The unique crossing must be an outside-to-inside entry, not an exit or a tangent.
        for(RoutePiece piece:pieces) {
            double lo=Math.max(station,piece.from),hi=Math.min(total,piece.from+piece.length);
            if(hi-lo>NODE_TOLERANCE&&!owner.polygon.covers(gf.createPoint(coordinate(pieces,(lo+hi)/2))))
                return unavailableEntry("ambiguous","После контакта с границей путь не остаётся внутри здания.");
            if(piece.from+piece.length<station-NODE_TOLERANCE&&owner.polygon.contains(gf.createPoint(coordinate(pieces,piece.from+piece.length/2))))
                return unavailableEntry("ambiguous","Перед вводом найден участок внутри здания.");
        }
        Coordinate[] ring=owner.polygon.getExteriorRing().getCoordinates();int wall=-1;
        for(int i=1;i<ring.length;i++) {
            if(ring[i-1].distance(ring[i])<=1e-8) continue;
            if(gf.createLineString(new Coordinate[]{ring[i-1],ring[i]}).distance(gf.createPoint(at))<=NODE_TOLERANCE) {
                if(wall!=-1) return unavailableEntry("ambiguous","Ввод совпадает с вершиной нескольких внешних стен; единственная стена не выбрана.");
                wall=i;
            }
        }
        if(wall<0) return unavailableEntry("unavailable","Не удалось сопоставить контакт внешней стене исходного полигона.");
        RoutePiece direction=null;boolean bent=false;
        for(RoutePiece piece:pieces) if(station>=piece.from-NODE_TOLERANCE&&station<=piece.from+piece.length+NODE_TOLERANCE) {
            if(direction==null) direction=piece;
            else {
                double dot=((direction.b.x-direction.a.x)*(piece.b.x-piece.a.x)+(direction.b.y-direction.a.y)*(piece.b.y-piece.a.y))/(direction.length*piece.length);
                if(dot<1-1e-6) bent=true;
            }
        }
        double wx=ring[wall].x-ring[wall-1].x,wy=ring[wall].y-ring[wall-1].y;
        double dx=direction.b.x-direction.a.x,dy=direction.b.y-direction.a.y;
        double cosine=Math.abs((wx*dx+wy*dy)/(Math.hypot(wx,wy)*Math.hypot(dx,dy)));
        Map<String,Object> entry=unavailableEntry("available","Ввод восстановлен по единственному контакту с внешним кольцом здания.");
        entry.put("building_key",owner.obstacle.key);entry.put("wall_index",wall);entry.put("wall_label","Внешняя стена №"+wall);
        entry.put("coordinates",wgs(at));entry.put("wall_coordinates",List.of(wgs(ring[wall-1]),wgs(ring[wall])));
        entry.put("internal_length",Math.max(0,total-station));
        entry.put("wall_angle_deg",bent?null:Math.toDegrees(Math.acos(Math.min(1,cosine))));
        if(bent) entry.put("message","Ввод восстановлен по внешнему кольцу здания. На границе направление пути меняется, поэтому единственный угол ввода не указан.");
        return entry;
    }

    private Map<String,Object> unavailableEntry(String status,String message) {
        Map<String,Object> entry=new LinkedHashMap<>();entry.put("status",status);entry.put("message",message);
        for(String field:List.of("building_key","wall_index","wall_label","coordinates","wall_coordinates","internal_length","wall_angle_deg")) entry.put(field,null);
        return entry;
    }
    private Map<String,Object> fact(String kind,String text,Map<String,Object> target) {
        Map<String,Object> fact=new LinkedHashMap<>();fact.put("kind",kind);fact.put("text",text);if(target!=null)fact.put("target",target);return fact;
    }
    private Coordinate coordinate(List<RoutePiece> pieces,double station) {
        for(RoutePiece piece:pieces) if(station<=piece.from+piece.length+1e-6) {
            double t=Math.max(0,Math.min(1,(station-piece.from)/piece.length));return new Coordinate(piece.a.x+t*(piece.b.x-piece.a.x),piece.a.y+t*(piece.b.y-piece.a.y));
        }
        return pieces.get(pieces.size()-1).b;
    }
    private List<Double> position(List<RoutePiece> pieces,double station) {return wgs(coordinate(pieces,station));}
    private List<Double> wgs(Coordinate coordinate) {Coordinate wgs=projection.utmToWgs(coordinate.x,coordinate.y);return List.of(wgs.x,wgs.y);}

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
    private static Double optionalNumber(JsonNode node) {
        // These fields are not part of the legacy profile contract. Validate them only for a card.
        return node!=null&&node.isNumber()&&Double.isFinite(node.doubleValue())?node.doubleValue():null;
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
        final String key,label;final Coordinate at;final List<Double>wgs;final List<Edge>edges=new ArrayList<>();boolean source;Double depth;String sourceKind;
        final List<String> sourcePipeKeys=new ArrayList<>();
        Node(String key,String label,Coordinate at,List<Double>wgs){this.key=key;this.label=label;this.at=at;this.wgs=wgs;}
    }
    private static final class Edge {
        final String id,method;final Node a,b;final List<Coordinate>coords;final List<List<Double>>wgs;final double length;final Double d0,d1,flow,cost;final int diameter;
        Edge(String id,Node a,Node b,List<Coordinate>coords,List<List<Double>>wgs,double length,Double d0,Double d1,int diameter,String method,Double flow,Double cost){this.id=id;this.a=a;this.b=b;this.coords=coords;this.wgs=wgs;this.length=length;this.d0=d0;this.d1=d1;this.diameter=diameter;this.method=method;this.flow=flow;this.cost=cost;}
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
    private static final class Building {
        final Obstacle obstacle;final Polygon polygon;
        Building(Obstacle obstacle,Polygon polygon){this.obstacle=obstacle;this.polygon=polygon;}
    }
}
