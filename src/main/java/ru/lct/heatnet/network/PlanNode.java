package ru.lct.heatnet.network;

import org.locationtech.jts.geom.Coordinate;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Узел планируемой сети. */
public class PlanNode {

    public enum Kind {
        /** Точка подключения ОКС (входной объект). */
        OKS,
        /** Существующая тепловая камера (входной объект). */
        EXISTING_CHAMBER,
        /** Новая тепловая камера. */
        NEW_CHAMBER,
        /** Технический узел (граница спецучастка и т.п.). */
        TECHNICAL_NODE
    }

    public Kind kind;
    public final Coordinate utm;
    /** id входного объекта (для OKS и существующих камер). */
    public Object inputId;
    /** Исходные координаты WGS 84 входного объекта (точное совпадение с входом). */
    public Coordinate wgs;
    /** Исключения зон близости для рёбер, примыкающих к узлу (трубы у врезки). */
    public Set<Object> exemptSpecialIds = Collections.emptySet();
    /** Для врезки в трубу: ДУ разрезаемой существующей линии. */
    public Integer splitPipeDu;
    /** Для ОКС: расчётный расход, т/ч. */
    public Double oksFlow;
    /** Для ОКС: id ограничения собственного полигона (исключение отступа на финальном участке). */
    public Object ownRestrictionId;
    /** Идентификатор в выходном файле (присваивается при выгрузке). */
    public String outId;

    public PlanNode(Kind kind, Coordinate utm) {
        this.kind = kind;
        this.utm = utm;
    }

    public void addExempt(Set<Object> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        Set<Object> merged = new HashSet<>(exemptSpecialIds);
        merged.addAll(ids);
        exemptSpecialIds = merged;
    }
}
