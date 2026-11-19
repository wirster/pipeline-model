package com.bdtdgis.util;

import com.bdtdgis.model.*;
import org.locationtech.jts.geom.Coordinate;
import java.util.*;

public class PipeLineMeshBuilder {

    /**
     * 根据pipeColor参数获取材质颜色
     *
     * @param pipeColor 颜色字符串，支持hex格式(#RRGGBB)或rgba格式
     * @return RGB颜色数组 [R, G, B]
     */
    public static float[] getPipeMaterialColor(String pipeColor) {
        if (pipeColor == null || pipeColor.trim().isEmpty()) {
            // 如果未指定颜色，使用默认的gszh_xyz颜色
            return new float[]{0.5803921568627451f, 0.5098039215686274f, 0.4235294117647059f};
        }
        // 使用ColorUtil转换颜色格式
        float[] color = ColorUtil.hexToRgbFloat(pipeColor.trim());
        // 只返回RGB部分，不包含alpha
        return new float[]{color[0], color[1], color[2]};
    }

    /**
     * 生成管道Mesh（圆柱体）
     *
     * @param pipe          管段对象
     * @param modelSettings 建模参数
     * @return Mesh
     */
    public static Mesh generatePipeLineMesh(PipeLine pipe, Map<String, Object> modelSettings) {
        String renderScheme = modelSettings != null && modelSettings.containsKey("renderScheme") ?
                (String) modelSettings.get("renderScheme") : "color";
        String pipeColor = modelSettings != null && modelSettings.containsKey("pipeColor") ?
                (String) modelSettings.get("pipeColor") : "#946B5A";
        String pipeTexture = modelSettings != null && modelSettings.containsKey("pipeTexture") ?
                (String) modelSettings.get("pipeTexture") : null;
        int segments = 32;
        Coordinate start = pipe.getStart();
        Coordinate end = pipe.getEnd();
        double diameter = pipe.getDiameter();
        int batchId = Integer.parseInt(pipe.getLineId());
        double radius = diameter / 2.0;
        // 根据埋深基础调整起终点高程
        // 原始坐标表示地面高程（井盖面），埋深基础指定管线几何的哪个部分与地面对应
        String depthBase = modelSettings != null && modelSettings.containsKey("depthBase") ?
                String.valueOf(modelSettings.get("depthBase")) : "bottom";
        Coordinate startAdj = new Coordinate(start.x, start.y, start.z);
        Coordinate endAdj = new Coordinate(end.x, end.y, end.z);
        if ("top".equalsIgnoreCase(depthBase)) {
            // 管顶：原始坐标向下偏移半径距离，使管线顶部与地面平齐
            startAdj.z = start.z - radius;
            endAdj.z = end.z - radius;
        } else if ("center".equalsIgnoreCase(depthBase)) {
            // 管心：原始坐标保持不变，管线中心与地面平齐
            // startAdj.z = start.z (保持不变)
            // endAdj.z = end.z (保持不变)
        } else if ("bottom".equalsIgnoreCase(depthBase)) {
            // 管底：原始坐标向上偏移半径距离，使管线底部与地面平齐
            startAdj.z = start.z + radius;
            endAdj.z = end.z + radius;
        }
        // 检查坐标是否有效
        if (Double.isNaN(startAdj.x) || Double.isNaN(startAdj.y) || Double.isNaN(startAdj.z) || Double.isNaN(endAdj.x) || Double.isNaN(endAdj.y) || Double.isNaN(endAdj.z)) {
            System.err.println("警告：管线坐标无效，跳过生成: " + pipe.getLineId());
            return null;
        }
        // 检查管线长度，如果太短则跳过（使用调整后的坐标）
        double length =
                Math.sqrt(Math.pow(endAdj.x - startAdj.x, 2) + Math.pow(endAdj.y - startAdj.y, 2) + Math.pow(endAdj.z - startAdj.z, 2));
        if (length < 0.1) {
            System.err.println("警告：管线长度过短，跳过生成: " + pipe.getLineId() + " (长度: " + length + ")");
            return null;
        }
        // 直接使用调整后的坐标，去中心化在更高层处理（不再增加端部间隙）
        Mesh mesh = buildCylinder(startAdj, endAdj, radius, segments, batchId);
        // 保存原始数据属性（仅用户数据）
        mesh.setOriginalAttributes(pipe.getAttributes() != null ? new HashMap<>(pipe.getAttributes()) :
                new HashMap<>());
        // 设置完整属性（包含系统属性）
        Map<String, Object> attr = pipe.getAttributes() != null ? new HashMap<>(pipe.getAttributes()) : new HashMap<>();
        attr.put("name", "Line-" + pipe.getLineId());
        attr.put("batchId", batchId);
        attr.put("diameter", diameter);
        attr.put("length", length);
        // 修正边界框计算，考虑管线的实际几何体积（半径）并使用调整后的坐标
        double minX = Math.min(startAdj.x, endAdj.x) - radius;
        double maxX = Math.max(startAdj.x, endAdj.x) + radius;
        double minY = Math.min(startAdj.y, endAdj.y) - radius;
        double maxY = Math.max(startAdj.y, endAdj.y) + radius;
        double minZ = Math.min(startAdj.z, endAdj.z) - radius;
        double maxZ = Math.max(startAdj.z, endAdj.z) + radius;
        attr.put("maxPoint", Arrays.asList(maxX, maxY, maxZ));
        attr.put("minPoint", Arrays.asList(minX, minY, minZ));
        mesh.setAttributes(attr);
        // 最后设置颜色，确保不被其他操作覆盖
        if (renderScheme.equals("color")) {
            float[] color = getPipeMaterialColor(pipeColor);
            mesh.setColors(Collections.singletonList(color));
        } else if (renderScheme.equals("texture") && pipeTexture != null && !pipeTexture.isEmpty()) {
            mesh.setUvs(mesh.getUvs());
            // 保留原有属性，只添加texture属性
            attr.put("texture", pipeTexture);
            mesh.setAttributes(attr);
        }
        return mesh;
    }

    // 构建圆柱体（管线，无间隙版本）
    public static Mesh buildCylinder(Coordinate start, Coordinate end, double radius, int segments, int batchId) {
        Mesh mesh = new Mesh();
        List<Vertex> vertices = new ArrayList<>();
        int[] topIdx = new int[segments];
        int[] botIdx = new int[segments];
        Vertex v0 = new Vertex(start.x, start.y, start.z);
        Vertex v1 = new Vertex(end.x, end.y, end.z);
        double dx = v1.x - v0.x, dy = v1.y - v0.y, dz = v1.z - v0.z;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1e-6) return mesh;
        // 方向与正交基
        double ux = dx / length, uy = dy / length, uz = dz / length;
        double vx = -uy, vy = ux, vz = 0;
        double vlen = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (vlen < 1e-6) {
            vx = 0;
            vy = -uz;
            vz = uy;
            vlen = Math.sqrt(vx * vx + vy * vy + vz * vz);
        }
        if (vlen < 1e-6) return mesh;
        vx /= vlen;
        vy /= vlen;
        vz /= vlen;
        double wx = uy * vz - uz * vy;
        double wy = uz * vx - ux * vz;
        double wz = ux * vy - uy * vx;
        // 起点/终点环（无任何偏移）
        for (int i = 0; i < segments; i++) {
            double angle = 2 * Math.PI * i / segments;
            double cos = Math.cos(angle), sin = Math.sin(angle);
            double nx = vx * cos + wx * sin;
            double ny = vy * cos + wy * sin;
            double nz = vz * cos + wz * sin;
            double px = v0.x + radius * nx;
            double py = v0.y + radius * ny;
            double pz = v0.z + radius * nz;
            vertices.add(new Vertex(px, py, pz));
            topIdx[i] = vertices.size() - 1;
        }
        for (int i = 0; i < segments; i++) {
            double angle = 2 * Math.PI * i / segments;
            double cos = Math.cos(angle), sin = Math.sin(angle);
            double nx = vx * cos + wx * sin;
            double ny = vy * cos + wy * sin;
            double nz = vz * cos + wz * sin;
            double qx = v1.x + radius * nx;
            double qy = v1.y + radius * ny;
            double qz = v1.z + radius * nz;
            vertices.add(new Vertex(qx, qy, qz));
            botIdx[i] = vertices.size() - 1;
        }

        int n = segments;
        // 侧面（双三角）
        for (int i = 0; i < n; i++) {
            int next = (i + 1) % n;
            mesh.faces.add(new Face(topIdx[i], botIdx[next], topIdx[next]));
            mesh.faces.add(new Face(topIdx[i], botIdx[i], botIdx[next]));
        }
        int startFace = 0;
        int endFace = mesh.faces.size();
        int originalVertexCount = vertices.size();
        for (int vi = 0; vi < originalVertexCount; vi++) {
            Vertex v = vertices.get(vi);
            vertices.add(new Vertex(v.x, v.y, v.z));
        }
        int offset = originalVertexCount;
        List<Face> reversed = new ArrayList<>();
        for (int fi = startFace; fi < endFace; fi++) {
            Face f = mesh.faces.get(fi);
            reversed.add(new Face(f.v1 + offset, f.v3 + offset, f.v2 + offset));
        }
        mesh.faces.addAll(reversed);
        mesh.vertices = vertices;
        autoFillAttributes(mesh, batchId);
        return mesh;
    }

    // 自动补全Mesh属性：法线、UV、批次ID
    public static void autoFillAttributes(Mesh mesh, int batchId) {
        if (mesh.normals == null || mesh.normals.isEmpty()) {
            mesh.normals = computeVertexNormals(mesh.vertices, mesh.faces);
        }
        if (mesh.uvs == null || mesh.uvs.isEmpty()) {
            mesh.uvs = generateUVs(mesh.vertices);
        }
        if (mesh.batchIds == null || mesh.batchIds.isEmpty()) {
            mesh.batchIds = new ArrayList<>();
            for (int i = 0; i < mesh.vertices.size(); i++) {
                mesh.batchIds.add(batchId);
            }
        }
    }

    // 计算每个顶点的法线（面法线平均）
    public static List<float[]> computeVertexNormals(List<Vertex> vertices, List<Face> faces) {
        List<float[]> normals = new ArrayList<>();
        for (int i = 0; i < vertices.size(); i++) normals.add(new float[]{0, 0, 0});
        for (Face face : faces) {
            Vertex v0 = vertices.get(face.v1);
            Vertex v1 = vertices.get(face.v2);
            Vertex v2 = vertices.get(face.v3);
            float[] normal = MathUtil.cross(MathUtil.subtract(v1, v0), MathUtil.subtract(v2, v0));
            MathUtil.add(normals.get(face.v1), normal);
            MathUtil.add(normals.get(face.v2), normal);
            MathUtil.add(normals.get(face.v3), normal);
        }
        for (int i = 0; i < normals.size(); i++) normals.set(i, MathUtil.normalize(normals.get(i)));
        return normals;
    }

    // 生成简单UV（x/y归一化）
    public static List<float[]> generateUVs(List<Vertex> vertices) {
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (Vertex v : vertices) {
            minX = Math.min(minX, (float) v.x);
            maxX = Math.max(maxX, (float) v.x);
            minY = Math.min(minY, (float) v.y);
            maxY = Math.max(maxY, (float) v.y);
        }
        float dx = maxX - minX;
        float dy = maxY - minY;
        List<float[]> uvs = new ArrayList<>();
        for (Vertex v : vertices) {
            float u = dx > 0 ? ((float) v.x - minX) / dx : 0f;
            float vCoord = dy > 0 ? ((float) v.y - minY) / dy : 0f;
            uvs.add(new float[]{u, vCoord});
        }
        return uvs;
    }

}