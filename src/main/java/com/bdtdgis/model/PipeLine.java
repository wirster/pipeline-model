package com.bdtdgis.model;

import org.locationtech.jts.geom.Coordinate;
import java.util.Map;

public class PipeLine {
    private String lineId;
    private Coordinate start;
    private Coordinate end;
    private double diameter;
    private double startDepth;
    private double endDepth;
    private String lineStart; // 管段起点编号
    private String lineEnd; // 管段终点编号
    private double startElev; // 管段起点高程
    private double endElev; // 管段终点高程
    private Map<String, Object> attributes;

    public PipeLine(String lineId, Coordinate start, Coordinate end, double diameter, double startDepth,
                    double endDepth, String lineStart, String lineEnd, Map<String, Object> attributes, double startElev, double endElev) {
        this.lineId = lineId;
        this.start = start;
        this.end = end;
        this.diameter = diameter;
        this.startDepth = startDepth;
        this.endDepth = endDepth;
        this.lineStart = lineStart;
        this.lineEnd = lineEnd;
        this.startElev = startElev;
        this.endElev = endElev;
        this.attributes = attributes;
    }

    public String getLineId() {
        return lineId;
    }

    public Coordinate getStart() {
        return start;
    }

    public Coordinate getEnd() {
        return end;
    }

    public double getDiameter() {
        return diameter;
    }

    public double getStartDepth() {
        return startDepth;
    }

    public double getEndDepth() {
        return endDepth;
    }

    public String getLineStart() {
        return lineStart;
    }

    public String getLineEnd() {
        return lineEnd;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public double getStartElev() {
        return startElev;
    }

    public double getEndElev() {
        return endElev;
    }
}