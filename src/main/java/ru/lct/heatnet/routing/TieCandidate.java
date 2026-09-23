package ru.lct.heatnet.routing;

import org.locationtech.jts.geom.Coordinate;

import java.util.Collections;
import java.util.Set;

/** Кандидат точки присоединения маршрута. */
public class TieCandidate {

    public enum Kind {
        /** Врезка в существующую тепловую камеру. */
        EXISTING_CHAMBER,
        /** Новая тепловая камера в точке существующего участка сети. */
        PIPE_POINT,
        /** Существующий узел уже построенной новой сети (камера/узел). */
        NEW_NETWORK_NODE,
        /** Точка на уже построенном новом участке — врезка новой камерой. */
        NEW_NETWORK_EDGE_POINT
    }

    public final Kind kind;
    public final Coordinate location;
    /** id камеры / трубы / внутренний id узла или ребра новой сети. */
    public final Object refId;
    /** Оценка терминальной стоимости присоединения, руб. */
    public final double terminalCostRub;
    /** Трубы существующей сети, примыкание к которым не считается сближением. */
    public final Set<Object> exemptSpecialIds;

    public TieCandidate(Kind kind, Coordinate location, Object refId,
                        double terminalCostRub, Set<Object> exemptSpecialIds) {
        this.kind = kind;
        this.location = location;
        this.refId = refId;
        this.terminalCostRub = terminalCostRub;
        this.exemptSpecialIds = exemptSpecialIds == null ? Collections.emptySet() : exemptSpecialIds;
    }
}
