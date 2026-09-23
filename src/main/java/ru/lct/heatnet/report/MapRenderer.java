package ru.lct.heatnet.report;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.network.VariantResult;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Карта варианта трассировки для пояснительной записки (Java2D).
 * Палитра согласована с презентацией ЛЦТ: акцент #FF0053, фиолетовый #520978.
 */
public class MapRenderer {

    private static final Color BUILDING_FILL = new Color(0xEB, 0xE5, 0xF2);
    private static final Color BUILDING_EDGE = new Color(0xB8, 0xAB, 0xCB);
    private static final Color WATER = new Color(0xC9, 0xDE, 0xF0);
    private static final Color RAIL_FILL = new Color(0xDD, 0xD6, 0xE4);
    private static final Color RAIL_EDGE = new Color(0xB0, 0xA6, 0xBE);
    private static final Color OTHER_RESTRICTION = new Color(0xE8, 0xE3, 0xD9);
    private static final Color EXISTING = new Color(0x52, 0x09, 0x78);
    private static final Color NEW_NET = new Color(0xFF, 0x00, 0x53);
    private static final Color OKS_POINT = new Color(0x31, 0x0F, 0x53);
    private static final Color TEXT = new Color(0x1C, 0x1D, 0x22);

    private final InputModel input;
    private final Envelope extent;

    public MapRenderer(InputModel input) {
        this.input = input;
        this.extent = computeExtent(input);
    }

    private static Envelope computeExtent(InputModel input) {
        Envelope env = new Envelope();
        for (InputModel.Restriction r : input.restrictions) {
            env.expandToInclude(r.utm.getEnvelopeInternal());
        }
        for (InputModel.ExistingPipe p : input.pipes) {
            env.expandToInclude(p.utm.getEnvelopeInternal());
        }
        for (InputModel.OksPoint o : input.oksPoints) {
            env.expandToInclude(o.utm.getEnvelopeInternal());
        }
        env.expandBy(Math.max(30, env.getWidth() * 0.02));
        return env;
    }

    /** Рисует карту варианта в изображение заданного размера. */
    public BufferedImage render(VariantResult variant, int widthPx, int heightPx) {
        BufferedImage image = new BufferedImage(widthPx, heightPx, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, widthPx, heightPx);

        double scale = Math.min(widthPx / extent.getWidth(), heightPx / extent.getHeight());
        double offsetX = (widthPx - extent.getWidth() * scale) / 2;
        double offsetY = (heightPx - extent.getHeight() * scale) / 2;
        Transform t = new Transform(scale, offsetX, offsetY);

        // подложка: ограничения
        for (InputModel.Restriction r : input.restrictions) {
            switch (r.restrictionType) {
                case "water":
                    fillGeometry(g, t, r.utm, WATER, null);
                    break;
                case "railway":
                    fillGeometry(g, t, r.utm, RAIL_FILL, RAIL_EDGE);
                    break;
                case "oks":
                    fillGeometry(g, t, r.utm, BUILDING_FILL, BUILDING_EDGE);
                    break;
                default:
                    fillGeometry(g, t, r.utm, OTHER_RESTRICTION, RAIL_EDGE);
            }
        }

        // существующая сеть
        for (InputModel.ExistingPipe pipe : input.pipes) {
            float w = (float) (1.0 + pipe.diameter / 300.0);
            g.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    1f, new float[]{8f, 5f}, 0f));
            g.setColor(EXISTING);
            drawLine(g, t, pipe.utm.getCoordinates());
        }
        for (InputModel.ExistingChamber c : input.chambers) {
            int[] p = t.px(c.utm.getCoordinate());
            g.setColor(Color.WHITE);
            g.fillRect(p[0] - 4, p[1] - 4, 8, 8);
            g.setColor(EXISTING);
            g.setStroke(new BasicStroke(1.6f));
            g.drawRect(p[0] - 4, p[1] - 4, 8, 8);
        }

        // новая сеть варианта
        for (VariantResult.Segment s : variant.segments) {
            float w = (float) (1.8 + s.diameter / 150.0);
            BasicStroke stroke = s.special
                    ? new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                        1f, new float[]{7f, 4f}, 0f)
                    : new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
            g.setStroke(stroke);
            g.setColor(NEW_NET);
            drawLine(g, t, s.utmCoords.toArray(new Coordinate[0]));
        }
        for (VariantResult.TechNode n : variant.techNodes) {
            int[] p = t.px(n.utm);
            g.setColor(Color.WHITE);
            g.fillOval(p[0] - 4, p[1] - 4, 8, 8);
            g.setColor(NEW_NET);
            g.setStroke(new BasicStroke(1.6f));
            g.drawOval(p[0] - 4, p[1] - 4, 8, 8);
        }
        for (VariantResult.Chamber c : variant.chambers) {
            int[] p = t.px(c.utm);
            g.setColor(NEW_NET);
            g.fillOval(p[0] - 5, p[1] - 5, 10, 10);
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(1.4f));
            g.drawOval(p[0] - 5, p[1] - 5, 10, 10);
        }

        // точки подключения и источник
        for (InputModel.OksPoint o : input.oksPoints) {
            int[] p = t.px(o.utm.getCoordinate());
            g.setColor(OKS_POINT);
            g.fillOval(p[0] - 5, p[1] - 5, 10, 10);
            g.setColor(Color.WHITE);
            g.drawOval(p[0] - 5, p[1] - 5, 10, 10);
        }
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        for (InputModel.SourcePoint src : input.sources) {
            int[] p = t.px(src.utm.getCoordinate());
            g.setColor(EXISTING);
            g.fillOval(p[0] - 8, p[1] - 8, 16, 16);
            g.setColor(Color.WHITE);
            g.fillOval(p[0] - 3, p[1] - 3, 6, 6);
            if (src.name != null) {
                g.setColor(TEXT);
                g.drawString(src.name, p[0] + 12, p[1] + 5);
            }
        }

        // масштабная линейка
        drawScaleBar(g, t, widthPx, heightPx);

        g.dispose();
        return image;
    }

    private void drawScaleBar(Graphics2D g, Transform t, int widthPx, int heightPx) {
        double meters = niceScale(extent.getWidth() / 5);
        int barPx = (int) (meters * t.scale);
        int x = widthPx - barPx - 20;
        int y = heightPx - 22;
        g.setColor(TEXT);
        g.setStroke(new BasicStroke(2f));
        g.drawLine(x, y, x + barPx, y);
        g.drawLine(x, y - 4, x, y + 4);
        g.drawLine(x + barPx, y - 4, x + barPx, y + 4);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        String label = meters >= 1000 ? (meters / 1000) + " км" : (int) meters + " м";
        g.drawString(label, x + barPx / 2 - 15, y - 7);
    }

    private static double niceScale(double raw) {
        double pow = Math.pow(10, Math.floor(Math.log10(raw)));
        double n = raw / pow;
        double nice = n < 1.5 ? 1 : n < 3.5 ? 2 : n < 7.5 ? 5 : 10;
        return nice * pow;
    }

    private void fillGeometry(Graphics2D g, Transform t, Geometry geom, Color fill, Color edge) {
        for (int i = 0; i < geom.getNumGeometries(); i++) {
            Geometry component = geom.getGeometryN(i);
            if (component instanceof Polygon) {
                GeneralPath path = polygonPath((Polygon) component, t);
                g.setColor(fill);
                g.fill(path);
                if (edge != null) {
                    g.setColor(edge);
                    g.setStroke(new BasicStroke(0.8f));
                    g.draw(path);
                }
            } else if (component instanceof LineString) {
                g.setColor(edge != null ? edge : fill);
                g.setStroke(new BasicStroke(2.5f));
                drawLine(g, t, component.getCoordinates());
            }
        }
    }

    private GeneralPath polygonPath(Polygon polygon, Transform t) {
        GeneralPath path = new GeneralPath(GeneralPath.WIND_EVEN_ODD);
        appendRing(path, polygon.getExteriorRing().getCoordinates(), t);
        for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
            appendRing(path, polygon.getInteriorRingN(i).getCoordinates(), t);
        }
        return path;
    }

    private void appendRing(GeneralPath path, Coordinate[] ring, Transform t) {
        for (int i = 0; i < ring.length; i++) {
            int[] p = t.px(ring[i]);
            if (i == 0) {
                path.moveTo(p[0], p[1]);
            } else {
                path.lineTo(p[0], p[1]);
            }
        }
        path.closePath();
    }

    private void drawLine(Graphics2D g, Transform t, Coordinate[] coords) {
        GeneralPath path = new GeneralPath();
        for (int i = 0; i < coords.length; i++) {
            int[] p = t.px(coords[i]);
            if (i == 0) {
                path.moveTo(p[0], p[1]);
            } else {
                path.lineTo(p[0], p[1]);
            }
        }
        g.draw(path);
    }

    private final class Transform {
        final double scale;
        final double offsetX;
        final double offsetY;

        Transform(double scale, double offsetX, double offsetY) {
            this.scale = scale;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
        }

        int[] px(Coordinate c) {
            int x = (int) Math.round(offsetX + (c.x - extent.getMinX()) * scale);
            int y = (int) Math.round(offsetY + (extent.getMaxY() - c.y) * scale);
            return new int[]{x, y};
        }
    }

    /** Легенда: пары (описание, тип маркера) — рисуется поверх PDF, не картинки. */
    public static List<String[]> legendItems() {
        return List.of(
                new String[]{"new", "Новая тепловая сеть (сплошная — обычный участок, пунктир — спецпроход)"},
                new String[]{"existing", "Существующая сеть и камеры"},
                new String[]{"oks", "Точки подключения ОКС"},
                new String[]{"chamber", "Новые тепловые камеры и технические узлы"});
    }
}
