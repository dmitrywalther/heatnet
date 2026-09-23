package ru.lct.heatnet.network;

import org.locationtech.jts.geom.Coordinate;

import java.util.ArrayList;
import java.util.List;

/** Готовый к выгрузке вариант трассировки. */
public class VariantResult {

    public String variantId;
    public String strategyName;
    public int rank;

    public final List<Segment> segments = new ArrayList<>();
    public final List<Chamber> chambers = new ArrayList<>();
    public final List<TechNode> techNodes = new ArrayList<>();
    public final List<Object> unconnectedOksIds = new ArrayList<>();

    public double constructionCost;
    public double chamberConstructionCost;
    public int existingChamberTieInCount;
    public double existingChamberTieInCost;
    public double unconnectedPenalty;
    public double calculatedCost;
    public double newNetworkLength;
    public double score;

    /** Суммарная длина, отражающая штрафы за спецпроходы — для отладки. */
    public boolean clearanceViolated;

    public static class Segment {
        public String id;
        public Object startNodeId;
        public Object endNodeId;
        public double flowTph;
        public int diameter;
        public double lengthM;
        public boolean special;
        public double kSpec;
        /** Глубина верха габарита в начале/конце участка; null в 2D-режиме. */
        public Double depthStart;
        public Double depthEnd;
        public double cost;
        /** Координаты UTM; преобразуются в WGS 84 при выгрузке. */
        public List<Coordinate> utmCoords;
        /**
         * Точные WGS-координаты концов, если узел — входной объект
         * (совпадение с входными координатами без потерь).
         */
        public Coordinate startWgs;
        public Coordinate endWgs;
    }

    public static class Chamber {
        public String id;
        public Coordinate utm;
        public int diameter;
        public double cost;
    }

    public static class TechNode {
        public String id;
        public Coordinate utm;
    }
}
