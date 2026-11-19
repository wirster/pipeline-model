package com.bdtdgis.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Mesh {
    public List<Vertex> vertices = new ArrayList<>();
    public List<Face> faces = new ArrayList<>();
    public List<float[]> normals = new ArrayList<>();
    public List<float[]> uvs = new ArrayList<>();
    public List<float[]> colors;
    public List<Integer> batchIds = new ArrayList<>();
    public List<Map<String, Object>> vertexAttributes = new ArrayList<>(); // 每顶点属性表
    public Map<String, Object> attributes; // shp属性字段（包含系统属性）
    public Map<String, Object> originalAttributes; // 原始数据属性（仅用户数据）

    public List<Vertex> getVertices() { return vertices; }
    public void setVertices(List<Vertex> vertices) { this.vertices = vertices; }
    public List<Face> getFaces() { return faces; }
    public void setFaces(List<Face> faces) { this.faces = faces; }
    public List<float[]> getNormals() { return normals; }
    public void setNormals(List<float[]> normals) { this.normals = normals; }
    public List<float[]> getUvs() { return uvs; }
    public void setUvs(List<float[]> uvs) { this.uvs = uvs; }
    public List<float[]> getColors() { return colors; }
    public void setColors(List<float[]> colors) { this.colors = colors; }
    public List<Integer> getBatchIds() { return batchIds; }
    public void setBatchIds(List<Integer> batchIds) { this.batchIds = batchIds; }
    public List<Map<String, Object>> getVertexAttributes() { return vertexAttributes; }
    public void setVertexAttributes(List<Map<String, Object>> vertexAttributes) { this.vertexAttributes = vertexAttributes; }
    public Map<String, Object> getAttributes() { return attributes; }
    public void setAttributes(Map<String, Object> attributes) { this.attributes = attributes; }
    public Map<String, Object> getOriginalAttributes() { return originalAttributes; }
    public void setOriginalAttributes(Map<String, Object> originalAttributes) { this.originalAttributes = originalAttributes; }
} 