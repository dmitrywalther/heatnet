package ru.lct.heatnet.report;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.geo.Projection;
import java.awt.Color;
import java.io.*;
import java.util.*;
import java.util.List;

/** Offline presentation of one saved connection. Never routes or mutates the supplied data. */
public final class ConnectionPdfWriter {
    private static final float W=PDRectangle.A4.getWidth(), H=PDRectangle.A4.getHeight(), M=42, CW=W-2*M, B=48;
    private static final Color INK=new Color(0x222332), MUTED=new Color(0x62616D),
            PURPLE=new Color(0x520978), PINK=new Color(0xFF0053), TEAL=new Color(0x007F78),
            GRID=new Color(0xDFDDE5), PALE=new Color(0xF4EFF8), SAND=new Color(0xD5C29D);
    private final PDDocument doc=new PDDocument();
    private final PDFont font,bold;
    private PDPageContentStream cs;
    private float y;
    private String variant;
    private int page;
    private ConnectionPdfWriter() throws IOException {
        try {
            font=font("/fonts/DejaVuSans.ttf");bold=font("/fonts/DejaVuSans-Bold.ttf");
        } catch(IOException|RuntimeException e) {doc.close();throw e;}
    }
    public static void write(OutputStream out,Map<String,Object> card,Map<String,Object> profile) throws IOException {
        if(profile!=null && (!Objects.equals(card.get("variant_id"),profile.get("variant_id"))
                || !Objects.equals(obj(card,"oks").get("key"),obj(profile,"oks").get("key"))))
            throw new IllegalArgumentException("Карточка и профиль относятся к разным ОКС или вариантам");
        ConnectionPdfWriter w=new ConnectionPdfWriter();
        try {
            w.render(card,profile);w.closePage();w.doc.save(out);
        } finally {w.closePage();w.doc.close();}
    }
    private PDFont font(String path)throws IOException {
        try(InputStream in=getClass().getResourceAsStream(path)) {
            if(in==null)throw new IOException("Не найден шрифт "+path);
            return PDType0Font.load(doc,in);
        }
    }
    private void render(Map<String,Object> card,Map<String,Object> profile)throws IOException {
        variant=str(card.get("variant_id"));newPage();
        title("Паспорт подключения ОКС");
        para("ОКС "+str(obj(card,"oks").get("label"))+" · вариант "+variant,12,INK);
        para("Ключ ОКС: "+str(obj(card,"oks").get("key")),8,MUTED);
        para("Представление сохранённого результата. Новый расчёт трассы не выполняется.",9,MUTED);
        y-=8;
        kv("Статус","connected".equals(card.get("status"))?"Подключён":"Не подключён");
        kv("Нагрузка ОКС",fmt(obj(card,"oks").get("flow_tph"),2)+" т/ч");
        if(!"connected".equals(card.get("status"))) {
            para(str(card.get("message")),11,INK);notes(card);return;
        }
        Map<String,Object> s=obj(card,"route_summary"),entry=obj(card,"entry"),costs=obj(card,"costs");
        kv("Путь от врезки",fmt(s.get("total_length"),2)+" м");
        kv("Общие / только этот ОКС",fmt(s.get("shared_length"),2)+" / "+fmt(s.get("dedicated_length"),2)+" м");
        kv("Внутри здания / спецпроходы",fmt(s.get("internal_length"),2)+" / "+fmt(s.get("special_length"),2)+" м");
        kv("Диаметры на пути",str(card.get("diameters"))+" мм");
        para("Врезка: "+str(obj(card,"source").get("label"))+" ("+sourceKind(obj(card,"source").get("kind"))+")",10,INK);
        if("available".equals(entry.get("status"))) {
            para("Ввод: "+str(entry.get("wall_label"))+", внутренний участок "+fmt(entry.get("internal_length"),2)+" м.",10,INK);
            para("Номер стены - индекс ребра внешнего контура, не архитектурный номер фасада.",8,MUTED);
        } else para("Ввод: "+str(entry.get("message")),10,MUTED);
        y-=6;heading("Стоимость трубных участков пути");
        kv("Все / общие / индивидуальные",fmt(costs.get("path_segment_cost"),0)+" / "
                +fmt(costs.get("shared_segment_cost"),0)+" / "+fmt(costs.get("dedicated_segment_cost"),0)+" руб.");
        para(str(costs.get("note")),9,MUTED);
        if(profile==null)para("Глубины в базовом варианте не заданы. Продольный график не формируется.",9,MUTED);
        routeMap(card);

        if(profile!=null) {newPage();title("Продольный профиль");profile(profile);}
        newPage();title("Участки выбранного пути");
        para("Порядок от врезки к ОКС. Расход и ДУ относятся ко всей нагрузке участка. "
                +"Длины - по геометрии EPSG:32637; цены - из сохранённого GeoJSON.",9,MUTED);
        List<String[]> rows=new ArrayList<>();
        List<Map<String,Object>> segs=list(card,"segments");
        for(int i=0;i<segs.size();i++) {
            Map<String,Object> a=segs.get(i);
            rows.add(new String[]{String.valueOf(i+1),str(a.get("id")),fmt(num(a.get("to"))-num(a.get("from")),2),
                    str(a.get("diameter")),fmt(a.get("flow_tph"),2),
                    Boolean.TRUE.equals(a.get("shared"))?"общий":"индив.",
                    "special".equals(a.get("laying_method"))?"спец.":"обычн.",fmt(a.get("cost"),0)});
        }
        table(new String[]{"№","Участок","Длина, м","ДУ","т/ч","Тип","Метод","Руб."},
                new float[]{22,105,62,36,58,56,53,CW-392},rows);
        if(profile!=null) {
            heading("Глубины и уклоны участков");
            rows=new ArrayList<>();
            for(int i=0;i<segs.size();i++) {
                Map<String,Object> a=segs.get(i);
                rows.add(new String[]{String.valueOf(i+1),fmt(a.get("from"),2)+" - "+fmt(a.get("to"),2),
                        fmt(a.get("depth_start"),2)+" / "+fmt(a.get("depth_end"),2),
                        a.get("slope")==null?"-":fmt(num(a.get("slope"))*100,2)+" %"});
            }
            table(new String[]{"№","Расстояние от врезки, м","Глубина верха начало / конец, м","Уклон, прибл."},
                    new float[]{28,153,214,CW-395},rows);
        }
        heading("Пересечения габаритов");
        rows=new ArrayList<>();
        for(Map<String,Object> a:list(card,"obstacles")) rows.add(new String[]{
                str(a.get("label")),fmt(a.get("from"),2)+" - "+fmt(a.get("to"),2),
                fmt(a.get("top_depth"),2)+" / "+fmt(a.get("bottom_depth"),2),
                fmt(a.get("min_top_depth"),2),"tie_in".equals(a.get("role"))?"врезка":"контакт"});
        if(rows.isEmpty())para("Пересечений габаритов на выбранном пути не обнаружено.",10,MUTED);
        else table(new String[]{"Объект","Положение, м","Верх / низ, м","Мин. верх*, м","Роль"},
                new float[]{172,95,99,84,CW-450},rows);
        para("* Минимальная глубина верха новой сети по правилу подземного прохода; "
                +"это не физическая отметка препятствия. «-» означает отсутствие сведений. "
                +"Интервалы показывают габарит, не полные окна SPECIAL с запасами.",8,MUTED);
        heading("Как проходит маршрут");
        for(Map<String,Object> fact:list(card,"explanations"))para(str(fact.get("text")),10,INK);
        notes(card);
    }
    private void notes(Map<String,Object> card)throws IOException {
        heading("Как читать паспорт");
        for(Object n:values(card.get("notes")))para(str(n),9,MUTED);
        para("Паспорт - пояснение полученного решения, не проектная документация "
                +"и не доказательство глобального оптимума. Округлённые длины частей могут отличаться от суммы на 0,01 м.",9,MUTED);
    }
    private void routeMap(Map<String,Object> card)throws IOException {
        space(260);heading("Схема пути");
        float top=y,hh=190,x=M+14,ww=CW-28;
        Projection p=new Projection();List<Map<String,Object>> segments=list(card,"segments");
        List<Coordinate> points=new ArrayList<>();
        for(Map<String,Object> a:segments)for(Object xy:values(a.get("coordinates")))points.add(project(p,xy));
        Map<String,Object> entry=obj(card,"entry");
        for(Object xy:values(entry.get("wall_coordinates")))points.add(project(p,xy));
        if(points.isEmpty())return;
        double minX=Double.POSITIVE_INFINITY,minY=minX,maxX=-minX,maxY=-minX;
        for(Coordinate c:points){minX=Math.min(minX,c.x);maxX=Math.max(maxX,c.x);minY=Math.min(minY,c.y);maxY=Math.max(maxY,c.y);}
        double scale=Math.min((ww-40)/Math.max(1,maxX-minX),(hh-30)/Math.max(1,maxY-minY));
        double cx=(minX+maxX)/2,cy=(minY+maxY)/2;
        rect(M,top-hh,CW,hh,PALE);
        for(Map<String,Object> a:segments) {
            cs.setStrokingColor(Boolean.TRUE.equals(a.get("shared"))?PURPLE:TEAL);cs.setLineWidth(2.5f);
            cs.setLineDashPattern("special".equals(a.get("laying_method"))?new float[]{4,3}:new float[]{},0);
            boolean first=true;
            for(Object xy:values(a.get("coordinates"))) {
                Coordinate c=project(p,xy);float xx=(float)(x+ww/2+(c.x-cx)*scale),yy=(float)(top-hh/2+(c.y-cy)*scale);
                if(first){cs.moveTo(xx,yy);first=false;}else cs.lineTo(xx,yy);
            }cs.stroke();
        }
        cs.setLineDashPattern(new float[]{},0);
        List<?> wall=values(entry.get("wall_coordinates"));
        if(wall.size()==2){Coordinate a=project(p,wall.get(0)),b=project(p,wall.get(1));
            line((float)(x+ww/2+(a.x-cx)*scale),(float)(top-hh/2+(a.y-cy)*scale),
                    (float)(x+ww/2+(b.x-cx)*scale),(float)(top-hh/2+(b.y-cy)*scale),PINK,3);}
        Object[] locations={obj(card,"source").get("coordinates"),obj(card,"oks").get("coordinates"),entry.get("coordinates")};
        Color[] colors={PURPLE,TEAL,PINK};
        for(int i=0;i<locations.length;i++)if(locations[i]!=null){
            Coordinate c=project(p,locations[i]);float xx=(float)(x+ww/2+(c.x-cx)*scale),yy=(float)(top-hh/2+(c.y-cy)*scale);
            rect(xx-3,yy-3,6,6,colors[i]);text(xx+5,yy+4,String.valueOf(i+1),8,bold,colors[i]);
        }
        text(M+CW-22,top-15,"N",8,bold,MUTED);
        line(M+CW-19,top-36,M+CW-19,top-20,MUTED,1);
        double bar=Math.pow(10,Math.floor(Math.log10(80/scale)));
        if(bar*scale<30)bar*=2;
        float by=top-hh+10;line(M+14,by,(float)(M+14+bar*scale),by,INK,1);
        text(M+14,by+4,fmt(bar,bar<1?2:0)+" м",8,font,MUTED);
        y=top-hh-14;
        para("1 - врезка; 2 - точка ОКС; 3 - ввод. Фиолетовый - общие участки; "
                +"зелёный - индивидуальные; розовый - стена; пунктир - спецпроход.",8,MUTED);
        para("Схема в метрической проекции, без подложки и соседних объектов. "
                +"Масштаб X и Y одинаков; стена показана только при однозначном определении.",8,MUTED);
    }
    private void profile(Map<String,Object> p)throws IOException {
        para("ОКС "+str(obj(p,"oks").get("label"))+" · "+variant+" · от врезки к зданию",11,INK);
        para("Глубина верха расчётного габарита от условной ровной поверхности. "
                +"Рельеф не задан; уклоны приблизительны по округлённому экспорту.",9,MUTED);
        space(380);
        List<Map<String,Object>> segments=list(p,"segments"),obstacles=list(p,"obstacles");
        double length=num(p.get("total_length")),maxDepth=1;
        for(Map<String,Object> s:segments)maxDepth=Math.max(maxDepth,Math.max(num(s.get("depth_start")),num(s.get("depth_end")))+num(s.get("height")));
        for(Map<String,Object> a:obstacles)if(a.get("bottom_depth")!=null)maxDepth=Math.max(maxDepth,num(a.get("bottom_depth")));
        maxDepth=Math.ceil(maxDepth+0.5);
        float left=M+35,right=W-M-8,top=y-45,bottom=top-220;
        double sx=(right-left)/Math.max(length,0.001),sy=(top-bottom)/maxDepth;
        for(int i=0;i<=5;i++){
            double d=maxDepth*i/5;float yy=(float)(top-d*sy);
            line(left,yy,right,yy,GRID,0.6f);text(M,yy-3,fmt(d,1),8,font,MUTED);
            double station=length*i/5;float xx=(float)(left+station*sx);
            line(xx,top,xx,bottom,GRID,0.6f);text(xx-8,bottom-14,fmt(station,length<5?2:0),8,font,MUTED);
        }
        text(M,top+9,"Глубина, м",8,font,MUTED);text(left,bottom-28,"Расстояние от врезки, м",8,font,MUTED);
        for(Map<String,Object> a:obstacles) {
            float ax=(float)(left+num(a.get("from"))*sx),bx=(float)(left+num(a.get("to"))*sx);
            if(a.get("top_depth")!=null&&a.get("bottom_depth")!=null) {
                float upper=(float)(top-num(a.get("top_depth"))*sy),lower=(float)(top-num(a.get("bottom_depth"))*sy);
                rect(ax,lower,Math.max(1,bx-ax),Math.max(1,upper-lower),SAND);
            } else {
                rect(ax,top+18,Math.max(1,bx-ax),6,SAND);
            }
        }
        for(Map<String,Object> s:segments){
            float ax=(float)(left+num(s.get("from"))*sx),bx=(float)(left+num(s.get("to"))*sx);
            float ay=(float)(top-num(s.get("depth_start"))*sy),by=(float)(top-num(s.get("depth_end"))*sy);
            float h=(float)(num(s.get("height"))*sy);
            cs.setNonStrokingColor(PALE);cs.moveTo(ax,ay);cs.lineTo(bx,by);cs.lineTo(bx,by-h);cs.lineTo(ax,ay-h);cs.closePath();cs.fill();
            line(ax,ay-h,bx,by-h,TEAL,1);line(ax,ay,bx,by,PURPLE,2);
        }
        y=bottom-46;
        para("Фиолетовый - верх габарита новой сети; зелёный - низ. Бежевый - габарит препятствия. "
                +"Полоса над графиком означает, что физическая глубина препятствия неизвестна.",9,MUTED);
        para("Масштабы расстояния и глубины различаются. Максимальный |уклон| ≈ "
                +fmt(num(p.get("max_abs_slope"))*100,2)+" %. Численные глубины, уклоны "
                +"и положения препятствий приведены в таблицах далее.",9,MUTED);
    }
    private void table(String[] headers,float[] widths,List<String[]> rows)throws IOException {
        space(45);tableRow(headers,widths,true);
        for(String[] row:rows){
            float needed=rowHeight(row,widths,false);
            if(y-needed<B){newPage();tableRow(headers,widths,true);}
            // Very long user IDs continue through normal paragraphs instead of clipping a whole page.
            if(needed>H-M-B-45) {
                for(int i=0;i<row.length;i++)para(headers[i]+": "+row[i],8,INK);
                y-=6;
            }else tableRow(row,widths,false);
        }y-=12;
    }
    private float rowHeight(String[] row,float[] widths,boolean header)throws IOException {
        int lines=1;for(int i=0;i<row.length;i++)lines=Math.max(lines,wrap(row[i],header?bold:font,8,widths[i]-8).size());
        return lines*11+8;
    }
    private void tableRow(String[] row,float[] widths,boolean header)throws IOException {
        float height=rowHeight(row,widths,header),x=M;
        if(header)rect(M,y-height,CW,height,PALE);
        for(int i=0;i<row.length;i++){
            float ty=y-12;
            for(String s:wrap(row[i],header?bold:font,8,widths[i]-8)){text(x+4,ty,s,8,header?bold:font,header?PURPLE:INK);ty-=11;}
            x+=widths[i];
        }
        y-=height;line(M,y,M+CW,y,GRID,0.5f);
    }
    private void newPage()throws IOException {
        closePage();PDPage p=new PDPage(PDRectangle.A4);doc.addPage(p);cs=new PDPageContentStream(doc,p);
        y=H-M;page++;text(M,26,"HEATNET · паспорт ОКС · "+variant,8,font,MUTED);
        text(W-M-18,26,String.valueOf(page),8,font,MUTED);
    }
    private void closePage()throws IOException {if(cs!=null){cs.close();cs=null;}}
    private void space(float n)throws IOException {if(y-n<B)newPage();}
    private void title(String s)throws IOException {para(s,18,PURPLE);y-=12;}
    private void heading(String s)throws IOException {space(38);y-=6;para(s,12,PURPLE);y-=4;}
    private void kv(String k,String v)throws IOException {para(k+": "+v,10,INK);}
    private void para(String s,float size,Color color)throws IOException {
        for(String line:wrap(s,font,size,CW)){space(size+4);text(M,y,line,size,font,color);y-=size+4;}
    }
    private List<String> wrap(String s,PDFont f,float size,float limit)throws IOException {
        s=safe(s,f);List<String> out=new ArrayList<>();StringBuilder line=new StringBuilder();
        for(String word:s.split("\\s+")) {
            String candidate=line.length()==0?word:line+" "+word;
            if(width(candidate,f,size)<=limit){line=new StringBuilder(candidate);continue;}
            if(line.length()>0){out.add(line.toString());line.setLength(0);}
            for(int off=0;off<word.length();) {
                int cp=word.codePointAt(off);String ch=new String(Character.toChars(cp));off+=Character.charCount(cp);
                if(line.length()>0&&width(line+ch,f,size)>limit){out.add(line.toString());line.setLength(0);}
                line.append(ch);
            }
        }
        if(line.length()>0)out.add(line.toString());if(out.isEmpty())out.add("");return out;
    }
    private String safe(String s,PDFont f)throws IOException {
        StringBuilder clean=new StringBuilder();
        for(int off=0;off<s.length();) {
            int cp=s.codePointAt(off);off+=Character.charCount(cp);
            if(Character.isISOControl(cp)){clean.append(' ');continue;}
            String ch=new String(Character.toChars(cp));
            try{f.encode(ch);clean.append(ch);}catch(IllegalArgumentException e){clean.append('?');}
        }return clean.toString();
    }
    private float width(String s,PDFont f,float size)throws IOException{return f.getStringWidth(s)*size/1000;}
    private void text(float x,float yy,String s,float size,PDFont f,Color c)throws IOException {
        cs.beginText();cs.setFont(f,size);cs.setNonStrokingColor(c);cs.newLineAtOffset(x,yy);cs.showText(safe(s,f));cs.endText();
    }
    private void line(float x,float yy,float xx,float y2,Color c,float size)throws IOException {
        cs.setStrokingColor(c);cs.setLineWidth(size);cs.moveTo(x,yy);cs.lineTo(xx,y2);cs.stroke();
    }
    private void rect(float x,float yy,float w,float h,Color c)throws IOException {
        cs.setNonStrokingColor(c);cs.addRect(x,yy,w,h);cs.fill();
    }
    private static Coordinate project(Projection p,Object xy) {
        List<?> a=(List<?>)xy;return p.wgsToUtm(num(a.get(0)),num(a.get(1)));
    }
    private static String sourceKind(Object kind) {
        return "existing_chamber".equals(kind)?"существующая камера":
                "new_chamber_tie".equals(kind)?"новая камера на существующей трубе":"тип не указан";
    }
    private static String str(Object o){return o==null?"-":String.valueOf(o);}
    private static double num(Object n){return n==null?0:((Number)n).doubleValue();}
    private static String fmt(Object n,int digits){return n==null?"-":String.format(Locale.forLanguageTag("ru"),"%,."+digits+"f",num(n)).replace('\u00a0',' ');}
    @SuppressWarnings("unchecked") private static Map<String,Object> obj(Map<String,Object> a,String k){Object v=a.get(k);return v instanceof Map?(Map<String,Object>)v:Map.of();}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> list(Map<String,Object> a,String k){return (List<Map<String,Object>>)(List<?>)values(a.get(k));}
    private static List<?> values(Object a){return a instanceof List?(List<?>)a:List.of();}
}
