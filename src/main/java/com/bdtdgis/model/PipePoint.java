package com.bdtdgis.model;

import org.locationtech.jts.geom.Coordinate;

import java.util.Map;

public class PipePoint {

    private String pointId;
    private String coverRule; // 井盖规格
    private Double depth; // 井深
    private String coverMat; // 井盖材质
    private Coordinate coord;
    private double elevation;
    private String attach; // 附属物类型
    private String pointCode; // 管点编号
    private String feature; // 特征
    private Double direction; // 方向
    private String featureType;  // 特征类型 变体名称
    private String attachType; // 附属物类型 变体名称

    private Map<String, Object> attributes;

    public PipePoint(String pointId, String coverRule, Double depth, String coverMat, Coordinate coord,
                     double elevation, String pointCode, Map<String, Object> attributes, String Attach, String feature, Double direction, String featureType, String attachType) {
        this.pointId = pointId;
        this.coverRule = coverRule;
        this.depth = depth;
        this.coverMat = coverMat;
        this.coord = coord;
        this.elevation = elevation;
        this.pointCode = pointCode;
        this.attributes = attributes;
        this.attach = Attach;
        this.feature = feature;
        this.direction = direction;
        this.featureType = featureType;
        this.attachType = attachType;
    }

    public String getPointId() {
        return pointId;
    }

    public String getCoverRule() {
        return coverRule;
    }

    public Double getDepth() {
        return depth;
    }

    public String getCoverMat() {
        return coverMat;
    }

    public double getElevation() {
        return elevation;
    }

    public Coordinate getCoord() {
        return coord;
    }

    public String getPointCode() {
        return pointCode;
    }

    public String getAttach() {
        return attach;
    }

    public String getFeature() {
        return feature;
    }

    public Double getDirection() {
        return direction;
    }

    public String getFeatureType() {
        return featureType;
    }

    public String getAttachType() {
        return attachType;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }
}