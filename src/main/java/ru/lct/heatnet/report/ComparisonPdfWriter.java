package ru.lct.heatnet.report;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.geo.Projection;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.*;
import java.util.List;

/** Read-only offline presentation of the calculation comparison JSON contract. */
public final class ComparisonPdfWriter {
    private static final float W=PDRectangle.A4.getWidth(), H=PDRectangle.A4.getHeight();
    private static final float M=42, B=48, CW=W-2*M, MAP_H=205;
    private static final Color INK=new Color(0x222332), MUTED=new Color(0x62616D),
            PURPLE=new Color(0x520978), REMOVED=new Color(0xCB295B), ADDED=new Color(0x007F78),
            GRID=new Color(0xDFDDE5), PALE=new Color(0xF4EFF8),
            ZONE=new Color(0xBA641C), ZONE_FILL=new Color(0xFBE6D0);
    private final PDDocument doc=new PDDocument();
    private final PDFont font,bold;
    private PDPageContentStream cs;
    private float y;
    private int page;

    private ComparisonPdfWriter()throws IOException {
        try {font=font("/fonts/DejaVuSans.ttf");bold=font("/fonts/DejaVuSans-Bold.ttf");}
        catch(IOException|RuntimeException ex){doc.close();throw ex;}
    }

    public static void write(OutputStream out,Map<String,Object> comparison)throws IOException {
        Objects.requireNonNull(out,"output");
        Objects.requireNonNull(comparison,"comparison");
        ComparisonPdfWriter writer=new ComparisonPdfWriter();
        try {
            writer.render(comparison);
            writer.closePage();
            writer.doc.save(out);
        }finally {writer.closePage();writer.doc.close();}
    }

    private PDFont font(String path)throws IOException {
        try(InputStream in=getClass().getResourceAsStream(path)) {
            if(in==null)throw new IOException("Не найден шрифт "+path);
            return PDType0Font.load(doc,in);
        }
    }

    private void render(Map<String,Object> data)throws IOException {
        Map<String,Object> before=obj(data,"before"),after=obj(data,"after");
        newPage();title("Сравнение расчётов");
        para("До: "+str(before.get("variant_id"))+" · "+mode(before.get("mode")),11,INK);
        para("После: "+str(after.get("variant_id"))+" · "+mode(after.get("mode")),11,INK);
        para("Сопоставление двух сохранённых результатов. Новый расчёт не выполняется. "
                +"Все дельты: после минус до; знак «+» означает увеличение.",9,MUTED);
        heading("Итоговые показатели сети");
        String[][] metrics={
                {"Стоимость строительства, руб.","construction_cost","0"},
                {"Штраф за неподключение, руб.","unconnected_penalty","0"},
                {"Расчётная стоимость со штрафом, руб.","calculated_cost","0"},
                {"Показатель S","score","4"},
                {"Длина новой сети по смете, м","new_network_length","2"},
                {"Подключено ОКС","connected_count","0"},
                {"Всего ОКС","oks_count","0"},
                {"Новые камеры, шт.","new_chamber_count","0"},
                {"Строительство камер, руб.","chamber_construction_cost","0"},
                {"Врезки в существующие камеры, шт.","existing_chamber_tie_in_count","0"},
                {"Врезки в существующие камеры, руб.","existing_chamber_tie_in_cost","0"}
        };
        List<String[]> rows=new ArrayList<>();
        Map<String,Object> a=obj(before,"summary"),b=obj(after,"summary"),delta=obj(data,"delta");
        for(String[] metric:metrics) {
            int digits=Integer.parseInt(metric[2]);
            rows.add(new String[]{metric[0],fmt(a.get(metric[1]),digits),
                    fmt(b.get(metric[1]),digits),signed(delta.get(metric[1]),digits)});
        }
        table(new String[]{"Показатель","До","После","Изменение"},
                new float[]{211,100,100,CW-411},rows);
        para("н/д - нет данных для показателя или его сравнения; это не ноль. "
                +"Стоимость строительства и штраф показаны отдельно. При потере подключений "
                +"снижение стоимости само по себе не означает улучшение решения.",8,MUTED);
        heading("Состояние подключений");
        Map<String,Object> counts=obj(data,"connections_summary");
        String[] statuses={"unchanged","changed","lost","restored","added","removed","still_unconnected"};
        List<String> countLabels=new ArrayList<>();
        for(String status:statuses)countLabels.add(status(status)+": "+fmt(counts.get(status),0));
        para(String.join("; ",countLabels)+".",10,INK);
        para("«Добавлен» и «удалён» означают изменение состава входных ОКС. "
                +"«Утрачено» и «восстановлено» относятся к подключению ОКС, присутствующих в обоих расчётах.",8,MUTED);

        maps(data);
        connections(data);
        heading("Как читать сравнение");
        para("Схемы показывают XY-геометрию в EPSG:32637 без картографической подложки. "
                +"Они не являются продольным профилем. Для глубинного режима используются "
                +"показатели сохранённого depth-результата; новый нормативный аудит не выполняется.",9,MUTED);
        for(Object note:values(data.get("notes")))para(str(note),9,MUTED);
        heading("Источники сравнения");
        sources("До",before);
        sources("После",after);
        para("SHA-256 идентифицирует точные исходные файлы. JSON-сравнение содержит полные "
                +"данные, типизированные ключи ОКС и геометрию изменений.",8,MUTED);
    }

    private void sources(String side,Map<String,Object> source)throws IOException {
        para(side+" · вариант "+str(source.get("variant_id")),9,INK);
        para("Вход SHA-256: "+str(source.get("input_sha256")),8,MUTED);
        para("Результат SHA-256: "+str(source.get("result_sha256")),8,MUTED);
        para("Правила SHA-256: "+str(source.get("effective_rules_sha256")),8,MUTED);
        y-=4;
    }

    private void connections(Map<String,Object> data)throws IOException {
        newPage();title("Изменения подключений ОКС");
        para("Показаны изменённые подключения и изменения состава ОКС. Полный перечень, "
                +"включая неизменённые и оставшиеся неподключёнными, находится в JSON.",9,MUTED);
        List<String[]> rows=new ArrayList<>();
        List<String> comparisonNotes=new ArrayList<>();
        for(Map<String,Object> row:list(data,"connections")) {
            String status=str(row.get("status"));
            if("unchanged".equals(status)||"still_unconnected".equals(status))continue;
            Map<String,Object> a=obj(row,"before"),b=obj(row,"after"),delta=obj(row,"delta"),
                    changes=obj(row,"changes");
            String key=str(row.get("oks_key"));
            rows.add(new String[]{str(row.get("label"))+"\n"+key,status(status),changes(changes),
                    fmt(obj(a,"route_summary").get("total_length"),2)+" / "
                            +fmt(obj(b,"route_summary").get("total_length"),2),
                    signed(delta.get("total_length"),2),signed(delta.get("path_segment_cost"),0)});
            if(changes.get("entry_note")!=null)comparisonNotes.add(key+": "+str(changes.get("entry_note")));
            if(changes.get("segment_attributes_note")!=null)
                comparisonNotes.add(key+": "+str(changes.get("segment_attributes_note")));
        }
        if(rows.isEmpty())para("Изменённых подключений и изменений состава ОКС не обнаружено.",10,INK);
        else table(new String[]{"ОКС / точный ключ","Статус","Что изменилось","Путь, м\nдо / после","Δ пути, м","Δ труб*, руб."},
                new float[]{112,65,124,80,59,CW-440},rows);
        para("* Δ труб - изменение стоимости трубных участков пути, включая общие; "
                +"не цена отдельного подключения. Не суммировать по ОКС. "
                +"Для отсутствующего пути дельта не подменяется нулём.",8,MUTED);
        para("«Атрибуты участков» - ДУ, расход, способ прокладки и глубины; "
                +"«не сопоставлены» не означает равенство.",8,MUTED);
        if(!comparisonNotes.isEmpty()) {
            heading("Уточнения сопоставления");
            for(String note:comparisonNotes)para(note,9,MUTED);
        }
    }

    private String changes(Map<String,Object> changes) {
        String[][] labels={{"path_geometry","трасса"},{"source","врезка"},{"entry","стена / ввод"},
                {"load","нагрузка"},{"location","точка ОКС"},{"diameter_set","набор ДУ"},
                {"segment_attributes","атрибуты участков"}};
        List<String> out=new ArrayList<>();
        for(String[] label:labels)if(Boolean.TRUE.equals(changes.get(label[0])))out.add(label[1]);
        if(changes.containsKey("entry")&&changes.get("entry")==null)out.add("стена: не сопоставлена");
        if(changes.containsKey("segment_attributes")&&changes.get("segment_attributes")==null)
            out.add("атрибуты: не сопоставлены");
        return out.isEmpty()?"Нет сопоставимых изменений":String.join(", ",out);
    }

    private void maps(Map<String,Object> data)throws IOException {
        newPage();title("Трассы до и после");
        Map<String,Object> geometry=obj(data,"network_geometry");
        Projection projection=new Projection();
        List<List<Coordinate>> before=lines(projection,geometry.get("before")),
                after=lines(projection,geometry.get("after")),
                removed=lines(projection,geometry.get("removed")),
                added=lines(projection,geometry.get("added"));
        List<Zone> addedZones=zones(projection,list(obj(data,"input_changes"),"added_restrictions"));
        List<Zone> removedZones=zones(projection,list(obj(data,"input_changes"),"removed_restrictions"));
        List<Coordinate> extentPoints=new ArrayList<>();
        for(List<List<Coordinate>> collection:List.of(before,after,removed,added))
            for(List<Coordinate> line:collection)extentPoints.addAll(line);
        for(List<Zone> zones:List.of(addedZones,removedZones))
            for(Zone zone:zones)for(List<Coordinate> ring:zone.rings)extentPoints.addAll(ring);
        List<Map<String,Object>> connections=list(data,"connections");
        for(Map<String,Object> connection:connections)for(String side:List.of("before","after")) {
            Coordinate point=point(projection,obj(connection,side).get("coordinates"));
            if(point!=null)extentPoints.add(point);
        }
        if(extentPoints.isEmpty()) {
            para("XY-геометрия для схем отсутствует. Нулевая длина из этого не выводится.",10,MUTED);
        }else {
            Frame frame=new Frame(extentPoints);
            para("Обе схемы: одинаковый масштаб по X и Y и общий охват. "
                    +"Фиолетовый - новая сеть; красный - удалённые части; зелёный - добавленные.",9,MUTED);
            panel("До",before,removed,REMOVED,addedZones,removedZones,false,frame,connections,projection);
            panel("После",after,added,ADDED,addedZones,removedZones,true,frame,connections,projection);
            para("Квадраты - точки ОКС (подписи могут быть сокращены); перечёркнутый квадрат - "
                    +"ОКС без подключения. Геометрия изменённых частей показана с допуском "
                    +fmt(geometry.get("tolerance_m"),2)+" м.",8,MUTED);
        }
        para("Удалённые части: "+fmt(geometry.get("removed_length_m"),2)+" м. "
                +"Добавленные части: "+fmt(geometry.get("added_length_m"),2)+" м.",10,INK);
        para("Длина объединённой XY-геометрии до / после: "+fmt(geometry.get("before_length_m"),2)
                +" / "+fmt(geometry.get("after_length_m"),2)+" м. Это геометрическая мера, "
                +"отдельная от округлённой длины в смете.",8,MUTED);
        if(!addedZones.isEmpty()||!removedZones.isEmpty()) {
            para("Оранжевые зоны: до добавления показан контур будущей зоны пунктиром, "
                    +"после добавления - заливка. Для удалённой зоны порядок обратный.",8,MUTED);
            para("Показаны только добавленные и удалённые ограничения; это не полный анализ "
                    +"всех изменений исходных данных. Остальные препятствия на схеме не показаны.",8,MUTED);
            for(Zone zone:addedZones)para("Добавлено ограничение: "+zone.label,8,ZONE);
            for(Zone zone:removedZones)para("Удалено ограничение: "+zone.label,8,ZONE);
        }
    }

    private void panel(String caption,List<List<Coordinate>> network,List<List<Coordinate>> changed,Color changedColor,
                       List<Zone> addedZones,List<Zone> removedZones,boolean after,Frame frame,
                       List<Map<String,Object>> connections,Projection projection)throws IOException {
        space(MAP_H+32);
        para(caption,11,PURPLE);
        float top=y, bottom=top-MAP_H;
        rect(M,bottom,CW,MAP_H,PALE);
        for(Zone zone:addedZones)drawZone(zone,after,frame,top);
        for(Zone zone:removedZones)drawZone(zone,!after,frame,top);
        for(List<Coordinate> path:network)drawPath(path,frame,top,PURPLE,1.7f);
        for(List<Coordinate> path:changed)drawPath(path,frame,top,changedColor,3);
        for(Map<String,Object> row:connections) {
            Map<String,Object> snapshot=obj(row,after?"after":"before");
            Coordinate c=point(projection,snapshot.get("coordinates"));
            if(c==null)continue;
            float x=frame.x(c),yy=frame.y(c,top);
            boolean connected=Boolean.TRUE.equals(snapshot.get("connected"));
            rect(x-2.5f,yy-2.5f,5,5,connected?INK:REMOVED);
            if(!connected) {
                line(x-4,yy-4,x+4,yy+4,REMOVED,1);
                line(x-4,yy+4,x+4,yy-4,REMOVED,1);
            }
            if(connections.size()<=30) {
                String label=shortLabel(str(row.get("label")),14);
                float tw=width(safe(label,font),font,7);
                text(Math.min(x+5,W-M-tw-4),Math.min(yy+5,top-12),label,7,font,INK);
            }
        }
        text(W-M-20,top-15,"N",8,bold,MUTED);
        line(W-M-17,top-36,W-M-17,top-20,MUTED,1);
        double bar=Math.pow(10,Math.floor(Math.log10(65/frame.scale)));
        if(bar*frame.scale<25)bar*=2;
        float barY=bottom+10;
        line(M+12,barY,(float)(M+12+bar*frame.scale),barY,INK,1);
        text(M+12,barY+5,fmt(bar,bar<1?2:0)+" м",8,font,MUTED);
        y=bottom-15;
    }

    private void drawPath(List<Coordinate> path,Frame frame,float top,Color color,float stroke)throws IOException {
        if(path.size()<2)return;
        cs.setStrokingColor(color);cs.setLineWidth(stroke);cs.setLineDashPattern(new float[]{},0);
        cs.moveTo(frame.x(path.get(0)),frame.y(path.get(0),top));
        for(int i=1;i<path.size();i++)cs.lineTo(frame.x(path.get(i)),frame.y(path.get(i),top));
        cs.stroke();
    }

    private void drawZone(Zone zone,boolean present,Frame frame,float top)throws IOException {
        cs.setStrokingColor(ZONE);cs.setNonStrokingColor(ZONE_FILL);cs.setLineWidth(0.8f);
        cs.setLineDashPattern(present?new float[]{}:new float[]{3,3},0);
        for(List<Coordinate> ring:zone.rings) {
            if(ring.size()<3)continue;
            cs.moveTo(frame.x(ring.get(0)),frame.y(ring.get(0),top));
            for(int i=1;i<ring.size();i++)cs.lineTo(frame.x(ring.get(i)),frame.y(ring.get(i),top));
            cs.closePath();
        }
        if(present)cs.fillAndStrokeEvenOdd();else cs.stroke();
        cs.setLineDashPattern(new float[]{},0);
    }

    private static final class Frame {
        final double cx,cy,scale;
        Frame(List<Coordinate> points) {
            double minX=Double.POSITIVE_INFINITY,minY=minX,maxX=-minX,maxY=-minX;
            for(Coordinate c:points) {
                minX=Math.min(minX,c.x);maxX=Math.max(maxX,c.x);
                minY=Math.min(minY,c.y);maxY=Math.max(maxY,c.y);
            }
            cx=(minX+maxX)/2;cy=(minY+maxY)/2;
            scale=Math.min((CW-58)/Math.max(1,maxX-minX),(MAP_H-55)/Math.max(1,maxY-minY));
        }
        float x(Coordinate point){return (float)(M+CW/2+(point.x-cx)*scale);}
        float y(Coordinate point,float top){return (float)(top-MAP_H/2+8+(point.y-cy)*scale);}
    }

    private static final class Zone {
        final String label;
        final List<List<Coordinate>> rings;
        Zone(String label,List<List<Coordinate>> rings){this.label=label;this.rings=rings;}
    }

    private static List<Zone> zones(Projection projection,List<Map<String,Object>> entries) {
        List<Zone> zones=new ArrayList<>();
        for(Map<String,Object> entry:entries) {
            Map<String,Object> geometry=obj(entry,"geometry");
            String label=str(entry.get("label"))+" ("+str(entry.get("key"))+")";
            if("Polygon".equals(geometry.get("type"))) {
                zones.add(new Zone(label,lines(projection,geometry.get("coordinates"))));
            }else if("MultiPolygon".equals(geometry.get("type"))) {
                List<List<Coordinate>> rings=new ArrayList<>();
                for(Object polygon:values(geometry.get("coordinates")))rings.addAll(lines(projection,polygon));
                zones.add(new Zone(label,rings));
            }
        }
        return zones;
    }

    private static List<List<Coordinate>> lines(Projection projection,Object source) {
        List<List<Coordinate>> paths=new ArrayList<>();
        for(Object line:values(source)) {
            List<Coordinate> coordinates=new ArrayList<>();
            for(Object xy:values(line)) {
                Coordinate point=point(projection,xy);
                if(point==null)throw new IllegalArgumentException("Некорректные координаты схемы сравнения");
                coordinates.add(point);
            }
            if(!coordinates.isEmpty())paths.add(coordinates);
        }
        return paths;
    }

    private static Coordinate point(Projection projection,Object source) {
        if(source==null)return null;
        List<?> xy=values(source);
        if(xy.size()<2||number(xy.get(0))==null||number(xy.get(1))==null)
            throw new IllegalArgumentException("Некорректная точка схемы сравнения");
        Coordinate p=projection.wgsToUtm(number(xy.get(0)),number(xy.get(1)));
        if(!Double.isFinite(p.x)||!Double.isFinite(p.y))
            throw new IllegalArgumentException("Координаты вне области метрической проекции");
        return p;
    }

    private void table(String[] headers,float[] widths,List<String[]> rows)throws IOException {
        space(rowHeight(headers,widths,true)+28);tableRow(headers,widths,true);
        for(String[] row:rows) {
            float needed=rowHeight(row,widths,false);
            if(needed>H-M-B-rowHeight(headers,widths,true)) {
                for(int i=0;i<row.length;i++)para(headers[i]+": "+row[i],8,INK);
                y-=6;
            }else {
                if(y-needed<B){newPage();tableRow(headers,widths,true);}
                tableRow(row,widths,false);
            }
        }
        y-=12;
    }
    private float rowHeight(String[] row,float[] widths,boolean header)throws IOException {
        int lines=1;
        for(int i=0;i<row.length;i++)lines=Math.max(lines,wrap(row[i],header?bold:font,8,widths[i]-8).size());
        return lines*11+8;
    }
    private void tableRow(String[] row,float[] widths,boolean header)throws IOException {
        float height=rowHeight(row,widths,header),x=M;
        if(header)rect(M,y-height,CW,height,PALE);
        for(int i=0;i<row.length;i++) {
            float ty=y-12;
            for(String s:wrap(row[i],header?bold:font,8,widths[i]-8)) {
                text(x+4,ty,s,8,header?bold:font,header?PURPLE:INK);ty-=11;
            }
            x+=widths[i];
        }
        y-=height;line(M,y,M+CW,y,GRID,0.5f);
    }
    private void newPage()throws IOException {
        closePage();PDPage p=new PDPage(PDRectangle.A4);doc.addPage(p);cs=new PDPageContentStream(doc,p);
        y=H-M;page++;text(M,26,"HEATNET · сравнение сохранённых расчётов",8,font,MUTED);
        text(W-M-18,26,String.valueOf(page),8,font,MUTED);
    }
    private void closePage()throws IOException {if(cs!=null){cs.close();cs=null;}}
    private void space(float n)throws IOException {if(y-n<B)newPage();}
    private void title(String s)throws IOException {para(s,18,PURPLE);y-=12;}
    private void heading(String s)throws IOException {space(42);y-=6;para(s,12,PURPLE);y-=4;}
    private void para(String s,float size,Color color)throws IOException {
        for(String line:wrap(s,font,size,CW)) {
            space(size+4);text(M,y,line,size,font,color);y-=size+4;
        }
    }
    private List<String> wrap(String s,PDFont f,float size,float limit)throws IOException {
        List<String> out=new ArrayList<>();StringBuilder line=new StringBuilder();
        for(String word:safe(s,f).split("\\s+")) {
            String candidate=line.length()==0?word:line+" "+word;
            if(width(candidate,f,size)<=limit){line=new StringBuilder(candidate);continue;}
            if(line.length()>0){out.add(line.toString());line.setLength(0);}
            for(int offset=0;offset<word.length();) {
                int cp=word.codePointAt(offset);String ch=new String(Character.toChars(cp));
                offset+=Character.charCount(cp);
                if(line.length()>0&&width(line+ch,f,size)>limit){out.add(line.toString());line.setLength(0);}
                line.append(ch);
            }
        }
        if(line.length()>0)out.add(line.toString());
        if(out.isEmpty())out.add("");
        return out;
    }
    private String safe(String s,PDFont f)throws IOException {
        StringBuilder result=new StringBuilder();
        for(int offset=0;offset<s.length();) {
            int cp=s.codePointAt(offset);offset+=Character.charCount(cp);
            if(Character.isISOControl(cp)){result.append(' ');continue;}
            String ch=new String(Character.toChars(cp));
            try{f.encode(ch);result.append(ch);}catch(IllegalArgumentException ex){result.append('?');}
        }
        return result.toString();
    }
    private float width(String s,PDFont f,float size)throws IOException{return f.getStringWidth(s)*size/1000;}
    private void text(float x,float yy,String s,float size,PDFont f,Color color)throws IOException {
        cs.beginText();cs.setFont(f,size);cs.setNonStrokingColor(color);
        cs.newLineAtOffset(x,yy);cs.showText(safe(s,f));cs.endText();
    }
    private void line(float x,float yy,float xx,float y2,Color color,float stroke)throws IOException {
        cs.setStrokingColor(color);cs.setLineWidth(stroke);cs.moveTo(x,yy);cs.lineTo(xx,y2);cs.stroke();
    }
    private void rect(float x,float yy,float width,float height,Color color)throws IOException {
        cs.setNonStrokingColor(color);cs.addRect(x,yy,width,height);cs.fill();
    }
    private static String mode(Object mode) {
        return "depth".equals(mode)?"глубинный режим":"base".equals(mode)?"базовый режим":str(mode);
    }
    private static String status(String status) {
        switch(status) {
            case "unchanged":return "Без изменений";
            case "changed":return "Изменено";
            case "lost":return "Утрачено";
            case "restored":return "Восстановлено";
            case "added":return "Добавлен ОКС";
            case "removed":return "Удалён ОКС";
            case "still_unconnected":return "Остался без подключения";
            default:return status;
        }
    }
    private static String shortLabel(String value,int maxCodePoints) {
        int n=value.codePointCount(0,value.length());
        return n<=maxCodePoints?value:value.substring(0,value.offsetByCodePoints(0,maxCodePoints-3))+"...";
    }
    private static String str(Object value){return value==null?"н/д":String.valueOf(value);}
    private static Double number(Object value) {
        if(!(value instanceof Number))return null;
        double d=((Number)value).doubleValue();return Double.isFinite(d)?d:null;
    }
    private static String fmt(Object value,int digits) {
        BigDecimal n=decimal(value);
        return n==null?"н/д":String.format(Locale.forLanguageTag("ru"),"%,."+digits+"f",n).replace('\u00a0',' ');
    }
    private static String signed(Object value,int digits) {
        BigDecimal n=decimal(value);
        return n==null?"н/д":(n.signum()>0?"+":"")+fmt(value,digits);
    }
    private static BigDecimal decimal(Object value) {
        if(!(value instanceof Number))return null;
        if(value instanceof BigDecimal)return (BigDecimal)value;
        try{return new BigDecimal(value.toString());}
        catch(NumberFormatException ex){return null;}
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> obj(Map<String,Object> source,String key) {
        Object value=source.get(key);return value instanceof Map?(Map<String,Object>)value:Map.of();
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> list(Map<String,Object> source,String key) {
        return (List<Map<String,Object>>)(List<?>)values(source.get(key));
    }
    private static List<?> values(Object source){return source instanceof List?(List<?>)source:List.of();}
}
