package com.bdtdgis.util;

import com.bdtdgis.model.Face;
import com.bdtdgis.model.Mesh;
import com.bdtdgis.model.PipePoint;
import com.bdtdgis.model.PipeLine;
import com.bdtdgis.model.Vertex;
import org.apache.commons.lang3.StringUtils;
import org.locationtech.jts.geom.Coordinate;

import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/**
 * 附属物和特征模型放置工具
 * <p>
 * 处理步骤：
 * 1. 根据管点附属物+附属物类型值找到对应的附属物模型（附属物类型为空则使用默认default模型）
 * 优先从modelSettings.attachModels 按附属物+附属物类型找到对应OBJ 文件
 * 如果没有找到附属物模型为空，则从modelSettings.featureModels 按特征+特征类型找到 OBJ 文件
 * 2. 将附属物或者特征对应的模型转为mesh，从OBJ 解析出多个 Mesh（按 g/usemtl 分组）
 * 将模型移动到管点位置（模型顶面中心对齐到管点坐标(x,y,z)）
 * 3. 自动模型将Y轴"上"方向调整为Z轴"上"方向，进行朝向调整（以原点为中心）
 * 4. 给mesh设置对应的管点属性信息，保留模型原始的材质贴图信息
 */
public class PipePointMeshBuilder {

    /**
     * 模型信息类
     */
    private static class ModelInfo {
        final String modelPath;
        final String attachName;
        final String attachVariant;
        final String featureName;
        final String featureVariant;
        final boolean isAccessory;

        ModelInfo(String modelPath, String attachName, String attachVariant, String featureName,
                  String featureVariant, boolean isAccessory) {
            this.modelPath = modelPath;
            this.attachName = attachName;
            this.attachVariant = attachVariant;
            this.featureName = featureName;
            this.featureVariant = featureVariant;
            this.isAccessory = isAccessory;
        }
    }

    /**
     * 放置附属物或特征模型到管点位置
     *
     * @param point         管点对象
     * @param lines         管线列表
     * @param modelSettings 模型设置
     * @return 放置后的Mesh列表
     */
    public static List<Mesh> generatePipePointMesh(PipePoint point, List<PipeLine> lines,
                                                   Map<String, Object> modelSettings) {
        // 步骤1: 基本校验
        if (!validateInput(point)) {
            return Collections.emptyList();
        }

        // 步骤2: 查找模型路径
        ModelInfo modelInfo = findModelPath(point, modelSettings);
        if (modelInfo == null) {
            return Collections.emptyList();
        }

        // 步骤3: 加载OBJ文件为Mesh
        List<Mesh> meshes = loadObjAsMeshes(modelInfo.modelPath);
        if (meshes.isEmpty()) {
            return Collections.emptyList();
        }

        // 步骤4: 模型变换和放置
        transformAndPlaceModel(meshes, point, lines, modelInfo, modelSettings);
        // 步骤5: 设置Mesh属性
        setMeshAttributes(meshes, point, modelInfo);

        return meshes;
    }

    /**
     * 步骤1: 基本校验
     */
    private static boolean validateInput(PipePoint point) {
        return point != null && point.getCoord() != null;
    }

    /**
     * 获取默认值，如果字符串为空则返回"default"
     */
    private static String getDefaultIfBlank(String value) {
        return StringUtils.isNotBlank(value) ? value : "default";
    }

    /**
     * 步骤2: 查找模型路径
     * 优先查找附属物模型，如果没找到则查找特征模型
     */
    private static ModelInfo findModelPath(PipePoint point, Map<String, Object> modelSettings) {
        String attachName = (point.getAttach() != null) ? point.getAttach().trim() : null;
        String attachVariant = (point.getAttachType() != null) ? point.getAttachType().trim() : null;
        String featureName = (point.getFeature() != null) ? point.getFeature().trim() : null;
        String featureVariant = (point.getFeatureType() != null) ? point.getFeatureType().trim() : null;

        // 优先查找附属物模型
        if (StringUtils.isNotBlank(attachName)) {
            String modelPath = resolveAccessoryModelPath(attachName, attachVariant, modelSettings);
            if (modelPath != null) {
                return new ModelInfo(modelPath, attachName, attachVariant, null, null, true);
            }
        }

        // 如果附属物模型为空，查找特征模型
        if (StringUtils.isNotBlank(featureName)) {
            String modelPath = resolveFeatureModelPath(featureName, featureVariant, modelSettings);
            if (modelPath != null) {
                return new ModelInfo(modelPath, null, null, featureName, featureVariant, false);
            }
        }

        return null;
    }

    /**
     * 步骤4: 模型变换和放置
     */
    private static void transformAndPlaceModel(List<Mesh> meshes, PipePoint point, List<PipeLine> lines,
                                               ModelInfo modelInfo, Map<String, Object> modelSettings) {
        Coordinate pointCoord = point.getCoord();

        // 4.1 计算模型包围盒和中心
        double[] bbox = computeUnionBBox(meshes);
        double centerX = (bbox[0] + bbox[3]) / 2.0;
        double centerY = (bbox[1] + bbox[4]) / 2.0;
        double centerZ = (bbox[2] + bbox[5]) / 2.0;
        double originalHeight = bbox[5] - bbox[2];
        // 4.2 将模型中心移动到原点，便于后续变换
        translateAll(meshes, -centerX, -centerY, -centerZ);
        // 4.3 自动将Y轴"上"方向调整为Z轴"上"方向
        // 绕X轴旋转90度，将Y轴"上"调整为Z轴"上"
        rotateAll(meshes, Math.PI / 2.0, "x", new Coordinate(0, 0, 0));
        // 4.4 根据管点数据计算并应用正确朝向
        if (!modelInfo.isAccessory) {
            calculateModelOrientation(meshes, point, lines);
        }
        // 收集特征模型所需的"起点/终点"实际高程（仅特征模型用）
        List<Double> adjustedStartElevations = !modelInfo.isAccessory ?
                collectAdjustedEndpointElevations(point, lines, modelSettings) : null;
        // 4.5 根据模型类型调整高度
        adjustModelHeight(meshes, point, modelInfo, originalHeight, lines);
        // 4.6 将模型移动到目标位置（顶面中心对齐）
        double[] bboxAfter = computeUnionBBox(meshes);
        double curCenterX = (bboxAfter[0] + bboxAfter[3]) / 2.0;
        double curCenterY = (bboxAfter[1] + bboxAfter[4]) / 2.0;
        double curTopZ = bboxAfter[5];
        // 始终使XY对齐到管点坐标，仅Z的目标不同
        double dx = pointCoord.x - curCenterX;
        double dy = pointCoord.y - curCenterY;
        double targetTopZ =
                (!modelInfo.isAccessory && adjustedStartElevations != null && !adjustedStartElevations.isEmpty()) ?
                        Collections.max(adjustedStartElevations) : pointCoord.z;
        double dz = targetTopZ - curTopZ;
        translateAll(meshes, dx, dy, dz);

    }

    /**
     * 步骤4.5: 根据模型类型调整高度
     */
    private static void adjustModelHeight(List<Mesh> meshes, PipePoint point, ModelInfo modelInfo,
                                          double originalHeight, List<PipeLine> lines) {
        if (!modelInfo.isAccessory) {
            // 特征模型：高度来源于关联管线（高程差或最大直径）
            adjustFeatureModelHeight(meshes, point, originalHeight, lines);
        } else {
            // 附属物模型：使用井深或原始高度
            adjustAccessoryModelHeight(meshes, point, originalHeight);
        }
    }

    /**
     * 调整特征模型高度
     */
    private static void adjustFeatureModelHeight(List<Mesh> meshes, PipePoint point, double originalHeight,
                                                 List<PipeLine> lines) {
        List<PipeLine> assoc = findAssociatedLines(point, lines);
        if (assoc.isEmpty()) {
            return; // 无关联管线，保持原始高度
        }
        boolean isDiameterBased = false;
        double targetHeight;
        if (assoc.size() == 1) {
            // 单管线：使用直径
            isDiameterBased = true;
            targetHeight = assoc.get(0).getDiameter();
        } else {
            // 多管线：计算高程差
            List<Double> elevations = collectElevations(point, assoc);
            if (elevations.isEmpty()) {
                return; // 无有效高程，保持原始高度
            }
            double minElev = Collections.min(elevations);
            double maxElev = Collections.max(elevations);
            double diff = Math.max(0.0, maxElev - minElev);
            if (diff <= 0.001) {
                // 高程差为0，使用最大直径
                isDiameterBased = true;
                targetHeight = assoc.stream().mapToDouble(PipeLine::getDiameter).max().orElse(originalHeight);
            } else {
                // 高程差大于0，使用高程差
                targetHeight = diff;
            }
        }
        if (isDiameterBased) {
            // 直径驱动：等比缩放高度和XY
            double targetH = Math.max(0.0, targetHeight);
            scaleHeightAndOptionallyXY(meshes, targetH, true);
        } else {
            // 高程差驱动：只缩放XY
            if (Math.abs(targetHeight - originalHeight) > 0.01) {
                double uniformScale = targetHeight / Math.max(1e-9, originalHeight);
                scaleAllInXYDirection(meshes, uniformScale, new Coordinate(0, 0, 0));
            }
        }
    }

    /**
     * 调整附属物模型高度
     */
    private static void adjustAccessoryModelHeight(List<Mesh> meshes, PipePoint point, double originalHeight) {
        if (point.getDepth() != null && point.getDepth() > 0) {
            double targetHeight = point.getDepth();
            if (Math.abs(targetHeight - originalHeight) > 0.01) {
                double uniformScale = targetHeight / Math.max(1e-9, originalHeight);
                scaleAllInZDirection(meshes, uniformScale, new Coordinate(0, 0, 0));
            }
        }
    }

    /**
     * 收集管点关联的高程数据
     */
    private static List<Double> collectElevations(PipePoint point, List<PipeLine> lines) {
        List<Double> elevations = new ArrayList<>();
        String pointCode = point.getPointCode();
        for (PipeLine line : lines) {
            if (pointCode.equals(line.getLineStart())) {
                elevations.add(line.getStartElev());
            }
            if (pointCode.equals(line.getLineEnd())) {
                elevations.add(line.getEndElev());
            }
        }
        return elevations;
    }

    /**
     * 按比例缩放所有 mesh 的 XY，围绕给定中心点
     */
    private static void scaleAllInXYDirection(List<Mesh> meshes, double scale, Coordinate center) {
        for (Mesh mesh : meshes) {
            if (mesh.vertices != null) {
                for (Vertex vertex : mesh.vertices) {
                    vertex.x = center.x + (vertex.x - center.x) * scale;
                    vertex.y = center.y + (vertex.y - center.y) * scale;
                }
            }
        }
    }

    /**
     * 将模型的总高度严格设置为 targetHeight，Z 围绕当前最小 Z 缩放；
     * 当 scaleXY 为 true 时，XY 围绕当前中心点按同一比例缩放。
     */
    private static void scaleHeightAndOptionallyXY(List<Mesh> meshes, double targetHeight, boolean scaleXY) {
        if (meshes == null || meshes.isEmpty()) return;
        double[] bbox = computeUnionBBox(meshes);
        double minZ = bbox[2];
        double maxZ = bbox[5];
        double curHeight = Math.max(1e-9, maxZ - minZ);
        double s = targetHeight / curHeight;
        // 计算当前XY中心
        double centerX = (bbox[0] + bbox[3]) / 2.0;
        double centerY = (bbox[1] + bbox[4]) / 2.0;
        for (Mesh mesh : meshes) {
            if (mesh.vertices == null) continue;
            for (Vertex v : mesh.vertices) {
                // Z 围绕 minZ 缩放，保证底部固定不抬升
                v.z = minZ + (v.z - minZ) * s;
                if (scaleXY) {
                    v.x = centerX + (v.x - centerX) * s;
                    v.y = centerY + (v.y - centerY) * s;
                }
            }
        }
        // 修正数值误差：再次校验高度
        double[] bbox2 = computeUnionBBox(meshes);
        double finalHeight = bbox2[5] - bbox2[2];
        if (Math.abs(finalHeight - targetHeight) > 1e-6) {
            double s2 = targetHeight / Math.max(1e-9, finalHeight);
            for (Mesh mesh : meshes) {
                if (mesh.vertices == null) continue;
                for (Vertex v : mesh.vertices) {
                    v.z = bbox2[2] + (v.z - bbox2[2]) * s2;
                }
            }
        }
    }

    /**
     * 查找与管点关联的管线
     */
    private static List<PipeLine> findAssociatedLines(PipePoint point, List<PipeLine> lines) {
        List<PipeLine> associatedLines = new ArrayList<>();
        if (point == null || lines == null || point.getPointCode() == null) {
            return associatedLines;
        }
        String pointCode = point.getPointCode();
        for (PipeLine line : lines) {
            if (pointCode.equals(line.getLineStart()) || pointCode.equals(line.getLineEnd())) {
                associatedLines.add(line);
            }
        }

        return associatedLines;
    }

    /**
     * 步骤5: 设置Mesh属性
     */
    private static void setMeshAttributes(List<Mesh> meshes, PipePoint point, ModelInfo modelInfo) {
        for (Mesh mesh : meshes) {
            Map<String, Object> attrs = mesh.getAttributes();
            if (attrs == null) {
                attrs = new HashMap<>();
            }
            // 设置基本属性
            attrs.put("name", "Point-" + point.getPointId());
            attrs.put("batchId", point.getPointId());
            // 记录方向（若存在）便于调试/导出
            if (point.getDirection() != null) {
                attrs.put("direction", point.getDirection());
            }
            // 根据模型类型设置不同的属性
            if (modelInfo.isAccessory) {
                attrs.put("attach", modelInfo.attachName);
                attrs.put("attachType", getDefaultIfBlank(modelInfo.attachVariant));
                attrs.put("modelType", "accessory");
            } else {
                attrs.put("feature", modelInfo.featureName);
                attrs.put("featureType", getDefaultIfBlank(modelInfo.featureVariant));
                attrs.put("modelType", "feature");
            }
            // 保留原始材质和贴图信息（用于ObjExporter导出）
            preserveMaterialAndTextureInfo(mesh, modelInfo, point);
            mesh.setAttributes(attrs);
            mesh.setOriginalAttributes(point.getAttributes());
        }
    }

    /**
     * 保留原始材质和贴图信息，确保ObjExporter能正确导出
     */
    private static void preserveMaterialAndTextureInfo(Mesh mesh, ModelInfo modelInfo, PipePoint point) {
        Map<String, Object> attrs = mesh.getAttributes();
        if (attrs == null) return;
        // 确保原始材质信息被保留（这是ObjExporter需要的）
        if (attrs.containsKey("originalMaterial")) {
            String originalMaterial = (String) attrs.get("originalMaterial");
            if (StringUtils.isNotBlank(originalMaterial)) {
                // 确保originalMaterial属性存在且不为空
                attrs.put("originalMaterial", originalMaterial);
            }
        }
        // 为ObjExporter设置必要的标识
        if (modelInfo.isAccessory) {
            // 附属物模型：ObjExporter会检查attachType来识别附属物模型
            attrs.put("attachType", getDefaultIfBlank(modelInfo.attachVariant));
            attrs.put("modelType", "accessory");
        } else {
            // 特征模型：设置特征类型标识，让ObjExporter知道这是特征模型
            attrs.put("featureType", getDefaultIfBlank(modelInfo.featureVariant));
            attrs.put("modelType", "feature");
            // 清除attachType，避免被误识别为附属物模型
            attrs.remove("attachType");
        }
        // 设置管点颜色（如果管点有颜色属性）
        if (point.getAttributes() != null) {
            Object pointColor = point.getAttributes().get("color");
            if (pointColor != null) {
                attrs.put("pointColor", pointColor);
            }
        }
    }

    // 取消可配置偏移逻辑，改为PCA自动对齐，因此移除相关读取方法

    private static double normalizeRadians(double angle) {
        double twoPi = Math.PI * 2.0;
        angle = angle % twoPi;
        if (angle < 0) angle += twoPi;
        return angle;
    }


    /**
     * 计算模型朝向：优先使用管点direction，否则基于关联管线
     * 1. 优先使用PipePoint.direction字段
     * 2. 如果direction为空或0，且只关联一条管线，使用管线角度
     * 3. 其他情况使用默认朝向0
     */
    private static void calculateModelOrientation(List<Mesh> meshes, PipePoint point, List<PipeLine> lines) {
        // 1. 优先使用管点direction字段
        if (point.getDirection() != null && Math.abs(point.getDirection()) > 1e-6) {
            double direction = point.getDirection();
            // 检查是否为角度制（通常范围在0-360度）
            if (direction > 2 * Math.PI) {
                // 角度制，转换为弧度
                direction = Math.toRadians(direction);
            }
            rotateAll(meshes, direction, "z", new Coordinate(0, 0, 0));
        }
        // 2. 如果direction为空或0，且只关联一条管线，使用管线切线方向
        List<PipeLine> associatedLines = findAssociatedLines(point, lines);
        if (associatedLines.size() == 1) {
            PipeLine line = associatedLines.get(0);
            Coordinate start = line.getStart();
            Coordinate end = line.getEnd();
            String pointCode = point.getPointCode();
            // 计算管点特征模型的朝向
            // 特征模型应该朝向管点所在位置（起点或终点）的圆柱体端面方向
            double dx, dy;
            if (pointCode.equals(line.getLineStart())) {
                // 管点是起点，朝向起点端面方向（从起点指向终点）
                dx = end.x - start.x;
                dy = end.y - start.y;
            } else {
                // 管点是终点，朝向终点端面方向（从终点指向起点）
                dx = start.x - end.x;
                dy = start.y - end.y;
            }
            double angle = Math.atan2(dy, dx);
            // 为使特征模型朝向管线端面（圆柱体端面）的本地前向轴，加入固定偏移（默认90度）
            double featureFacingOffsetRad = Math.PI / 2.0; // 90°
            double baseAngle = angle + featureFacingOffsetRad;
            // 计算当前网格在XY投影的主轴方向（通过PCA），使其与 baseAngle 对齐
            double meshPrincipalAngle = computeMeshFootprintPrincipalAngle(meshes);
            double finalAngle = normalizeRadians(baseAngle - meshPrincipalAngle);
            rotateAll(meshes, finalAngle, "z", new Coordinate(0, 0, 0));
        } else {
            // 3. 其他情况使用默认朝向0
            rotateAll(meshes, point.getDirection(), "z", new Coordinate(0, 0, 0));
        }
    }

    /**
     * 绕指定轴旋转所有mesh
     */
    private static void rotateAll(List<Mesh> meshes, double angleRad, String axis, Coordinate center) {
        for (Mesh mesh : meshes) {
            if (mesh.vertices != null) {
                for (Vertex vertex : mesh.vertices) {
                    rotateVertex(vertex, angleRad, axis, center);
                }
            }
        }
    }

    // 计算网格在 XY 投影的主轴方向角（弧度）。
    // 方法：将所有顶点投影到XY，计算协方差矩阵的主特征向量，对应角度 atan2(vy, vx)。
    private static double computeMeshFootprintPrincipalAngle(List<Mesh> meshes) {
        // 收集XY
        List<double[]> pts = new ArrayList<>();
        for (Mesh mesh : meshes) {
            if (mesh.vertices == null) continue;
            for (Vertex v : mesh.vertices) {
                pts.add(new double[]{v.x, v.y});
            }
        }
        if (pts.isEmpty()) return 0.0;
        // 计算均值
        double mx = 0, my = 0;
        for (double[] p : pts) {
            mx += p[0];
            my += p[1];
        }
        mx /= pts.size();
        my /= pts.size();
        // 协方差元素
        double sxx = 0, syy = 0, sxy = 0;
        for (double[] p : pts) {
            double dx = p[0] - mx;
            double dy = p[1] - my;
            sxx += dx * dx;
            syy += dy * dy;
            sxy += dx * dy;
        }
        sxx /= pts.size();
        syy /= pts.size();
        sxy /= pts.size();
        // 主方向角：0.5 * atan2(2*sxy, sxx - syy)
        double angle = 0.5 * Math.atan2(2.0 * sxy, (sxx - syy));
        // 归一到 [0, 2pi)
        return normalizeRadians(angle);
    }

    /**
     * 旋转单个顶点
     */
    private static void rotateVertex(Vertex vertex, double angleRad, String axis, Coordinate center) {
        double cos = Math.cos(angleRad);
        double sin = Math.sin(angleRad);
        // 相对于中心点的坐标
        double x = vertex.x - center.x;
        double y = vertex.y - center.y;
        double z = vertex.z - center.z;
        double newX, newY, newZ;
        switch (axis.toLowerCase()) {
            case "x": // 绕X轴旋转
                newX = x;
                newY = y * cos - z * sin;
                newZ = y * sin + z * cos;
                break;
            case "y": // 绕Y轴旋转
                newX = x * cos + z * sin;
                newY = y;
                newZ = -x * sin + z * cos;
                break;
            case "z": // 绕Z轴旋转
                newX = x * cos - y * sin;
                newY = x * sin + y * cos;
                newZ = z;
                break;
            default:
                return;
        }
        // 更新顶点坐标
        vertex.x = center.x + newX;
        vertex.y = center.y + newY;
        vertex.z = center.z + newZ;
    }

    /**
     * 缩放所有mesh在Z轴方向
     */
    private static void scaleAllInZDirection(List<Mesh> meshes, double scale, Coordinate center) {
        for (Mesh mesh : meshes) {
            if (mesh.vertices != null) {
                for (Vertex vertex : mesh.vertices) {
                    // 相对于中心点缩放
                    vertex.z = center.z + (vertex.z - center.z) * scale;
                }
            }
        }
    }

    /**
     * 平移所有mesh
     */
    private static void translateAll(List<Mesh> meshes, double dx, double dy, double dz) {
        for (Mesh mesh : meshes) {
            if (mesh.vertices != null) {
                for (Vertex vertex : mesh.vertices) {
                    vertex.x += dx;
                    vertex.y += dy;
                    vertex.z += dz;
                }
            }
        }
    }

    /**
     * 计算多个mesh的联合包围盒 [minX,minY,minZ,maxX,maxY,maxZ]
     */
    private static double[] computeUnionBBox(List<Mesh> meshes) {
        if (meshes == null || meshes.isEmpty()) {
            return new double[]{0, 0, 0, 0, 0, 0};
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Mesh mesh : meshes) {
            if (mesh.vertices == null) continue;
            for (Vertex v : mesh.vertices) {
                if (v.x < minX) minX = v.x;
                if (v.y < minY) minY = v.y;
                if (v.z < minZ) minZ = v.z;
                if (v.x > maxX) maxX = v.x;
                if (v.y > maxY) maxY = v.y;
                if (v.z > maxZ) maxZ = v.z;
            }
        }
        return new double[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    /**
     * 从 modelSettings.attachModels 中解析附属物类型对应的OBJ路径
     */
    private static String resolveAccessoryModelPath(String attachName, String attachVariant,
                                                    Map<String, Object> modelSettings) {
        if (attachName == null || attachName.trim().isEmpty() || modelSettings == null) return null;
        Object attachModelsObj = modelSettings.get("attachModels");
        if (!(attachModelsObj instanceof Map)) return null;
        @SuppressWarnings("unchecked") Map<String, Object> attachModels = (Map<String, Object>) attachModelsObj;
        Object cfg = attachModels.get(attachName);
        if (cfg == null) return null;
        return resolveModelPathFromConfig(cfg, attachVariant, attachName);
    }

    /**
     * 从 modelSettings.featureModels 中解析特征类型对应的OBJ路径
     */
    private static String resolveFeatureModelPath(String featureName, String featureVariant,
                                                  Map<String, Object> modelSettings) {
        if (featureName == null || featureName.trim().isEmpty() || modelSettings == null) return null;
        Object featureModelsObj = modelSettings.get("featureModels");
        if (!(featureModelsObj instanceof Map)) return null;
        @SuppressWarnings("unchecked") Map<String, Object> featureModels = (Map<String, Object>) featureModelsObj;
        Object featureConfig = featureModels.get(featureName);
        if (featureConfig == null) return null;
        return resolveModelPathFromConfig(featureConfig, featureVariant, featureName);
    }

    /**
     * 从配置对象中解析模型路径（支持变体和默认值）
     */
    private static String resolveModelPathFromConfig(Object config, String variant, String modelName) {
        if (config == null) return null;

        if (config instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, Object> variants = (Map<String, Object>) config;
            String[] defaultKeys = {"default", "默认", "_default", ""};

            // 若显式提供了变体
            if (StringUtils.isNotBlank(variant)) {
                String trimmed = variant.trim();
                // 检查是否为默认别名
                for (String dk : defaultKeys) {
                    if (trimmed.equalsIgnoreCase(dk)) {
                        return findModelPathInVariants(variants, defaultKeys);
                    }
                }
                if (variants.containsKey(trimmed)) {
                    return resolvePathFromString(String.valueOf(variants.get(trimmed)));
                }
            }
            // 未选择类型或为空：按默认键顺序查找
            return findModelPathInVariants(variants, defaultKeys);
        } else {
            // 如果配置是字符串，说明是旧格式（单个模型）
            return resolvePathFromString(String.valueOf(config));
        }
    }

    /**
     * 在变体映射中查找模型路径
     */
    private static String findModelPathInVariants(Map<String, Object> variants, String[] defaultKeys) {
        for (String key : defaultKeys) {
            if (variants.containsKey(key)) {
                String result = resolvePathFromString(String.valueOf(variants.get(key)));
                if (result != null) return result;
            }
        }
        return null;
    }

    /**
     * 从路径字符串解析OBJ文件路径
     */
    private static String resolvePathFromString(String pathStr) {
        if (pathStr == null || pathStr.trim().isEmpty()) return null;
        String path = pathStr.trim();
        File file = new File(path);

        if (file.isFile() && file.getName().toLowerCase().endsWith(".obj")) {
            return file.getAbsolutePath();
        }
        if (file.isDirectory()) {
            File obj = findBestMatchingObj(file);
            return obj != null ? obj.getAbsolutePath() : null;
        }
        return null;
    }

    // 在目录中查找第一个 .obj 文件（递归）
    private static File findBestMatchingObj(File dir) {
        if (dir == null || !dir.isDirectory()) return null;
        List<File> allObjs = new ArrayList<>();
        collectObjFilesRecursive(dir, allObjs);
        if (allObjs.isEmpty()) return null;
        // 移除推测逻辑，直接返回第一个找到的OBJ文件
        return allObjs.get(0);
    }

    private static void collectObjFilesRecursive(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) collectObjFilesRecursive(f, out);
            else if (f.isFile() && f.getName().toLowerCase().endsWith(".obj")) out.add(f);
        }
    }

    /**
     * 简易 OBJ 解析为多个 Mesh（按 g/o 分组；若无分组则合并为一个）
     */
    private static List<Mesh> loadObjAsMeshes(String objPath) {
        List<Mesh> result = new ArrayList<>();
        if (objPath == null) return result;
        File objFile = new File(objPath);
        if (objFile.isDirectory()) {
            File first = findBestMatchingObj(objFile);
            if (first != null) objFile = first;
            else {
                return result;
            }
        }
        if (!objFile.exists()) {
            return result;
        }
        try {
            List<String> lines = Files.readAllLines(objFile.toPath(), StandardCharsets.UTF_8);
            List<Vertex> allVertices = new ArrayList<>();
            List<float[]> allUVs = new ArrayList<>();
            Map<String, List<int[]>> groupFacesVVt = new LinkedHashMap<>(); // face as triplets of (v,v,v) and store
            // vt separately in map
            Map<String, List<int[]>> groupFacesVt = new LinkedHashMap<>();  // parallel list of (vt,vt,vt)
            String currentGroupName = "default";
            String currentMaterialName = null;
            // 记录每个分组键对应的材质名（仅保存 usemtl 名称）
            Map<String, String> groupKeyToMaterial = new LinkedHashMap<>();
            // 若 OBJ 缺少显式 usemtl，把分组名也作为候选材质名（方便立方体这类不带 usemtl 的简化OBJ）
            Map<String, String> groupNameFallbackMaterial = new LinkedHashMap<>();
            // 解析原始材质映射（从同目录MTL读取）
            Map<String, String> materialToTexture = new HashMap<>();
            File accessoryDir = objFile.getParentFile();
            File foundMtl = null;
            if (accessoryDir != null && accessoryDir.exists()) {
                foundMtl = findMTLFile(accessoryDir);
                if (foundMtl != null) {
                    materialToTexture = extractMaterialInfo(foundMtl);
                    // 为管点特征模型生成颜色纹理（如果MTL中只有Kd没有map_Kd）
                    // 纹理文件将保存到与OBJ文件相同的目录下
                    generateColorTexturesForFeatureModels(foundMtl, objFile, materialToTexture);
                }
            }
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("g ")) {
                    String name = line.substring(2).trim();
                    if (!name.isEmpty()) currentGroupName = name;
                    // 新组开始时重置当前材质，避免上一组的 usemtl 透传到本组
                    currentMaterialName = null;
                    String key = currentGroupName + "||" + (currentMaterialName == null ? "" : currentMaterialName);
                    groupFacesVVt.computeIfAbsent(key, k -> new ArrayList<>());
                    groupFacesVt.computeIfAbsent(key, k -> new ArrayList<>());
                    // 记录一个基于组名的回退材质名
                    groupNameFallbackMaterial.putIfAbsent(currentGroupName + "||", currentGroupName);
                    continue;
                }
                if (line.startsWith("usemtl ")) {
                    String name = line.substring(7).trim();
                    if (!name.isEmpty()) {
                        currentMaterialName = name;
                        String key = currentGroupName + "||" + currentMaterialName;
                        groupFacesVVt.computeIfAbsent(key, k -> new ArrayList<>());
                        groupFacesVt.computeIfAbsent(key, k -> new ArrayList<>());
                        groupKeyToMaterial.putIfAbsent(key, currentMaterialName);
                    }
                    continue;
                }
                if (line.startsWith("v ")) {
                    String[] p = line.split("\\s+");
                    if (p.length >= 4) {
                        allVertices.add(new Vertex(Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                                Double.parseDouble(p[3])));
                    }
                    continue;
                }
                if (line.startsWith("vt ")) {
                    String[] p = line.split("\\s+");
                    if (p.length >= 3) {
                        float u = Float.parseFloat(p[1]);
                        float v = Float.parseFloat(p[2]);
                        allUVs.add(new float[]{u, v});
                    }
                    continue;
                }
                if (line.startsWith("f ")) {
                    String[] tok = line.substring(2).trim().split("\\s+");
                    if (tok.length >= 3) {
                        int[] vIdxs = new int[tok.length];
                        int[] vtIdxs = new int[tok.length];
                        Arrays.fill(vtIdxs, -1);
                        for (int i = 0; i < tok.length; i++) {
                            String[] fp = tok[i].split("/");
                            int vIdx = Integer.parseInt(fp[0]); // 1-based
                            vIdxs[i] = vIdx - 1; // 0-based
                            if (fp.length >= 2 && fp[1] != null && !fp[1].isEmpty()) {
                                try {
                                    int vt = Integer.parseInt(fp[1]);
                                    vtIdxs[i] = vt - 1;
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                        String key = currentGroupName + "||" + (currentMaterialName == null ? "" : currentMaterialName);
                        for (int i = 2; i < vIdxs.length; i++) {
                            // triangle (0, i-1, i)
                            groupFacesVVt.computeIfAbsent(key, k -> new ArrayList<>()).add(new int[]{vIdxs[0],
                                    vIdxs[i - 1], vIdxs[i]});
                            groupFacesVt.computeIfAbsent(key, k -> new ArrayList<>()).add(new int[]{vtIdxs[0],
                                    vtIdxs[i - 1], vtIdxs[i]});
                        }
                    }
                }
            }
            // 为每个分组构建独立 Mesh，按 (v,vt) 组合去重，保证每个顶点有对应UV
            for (Map.Entry<String, List<int[]>> e : groupFacesVVt.entrySet()) {
                String grpKey = e.getKey();
                List<int[]> facesV = e.getValue();
                List<int[]> facesVt = groupFacesVt.getOrDefault(grpKey, Collections.emptyList());
                if (facesV == null || facesV.isEmpty()) continue;
                Map<Long, Integer> pairToNew = new HashMap<>();
                List<Vertex> verts = new ArrayList<>();
                List<float[]> uvs = new ArrayList<>();
                List<Face> triFaces = new ArrayList<>();
                for (int fIdx = 0; fIdx < facesV.size(); fIdx++) {
                    int[] triV = facesV.get(fIdx);
                    int[] triVt = (fIdx < facesVt.size()) ? facesVt.get(fIdx) : new int[]{-1, -1, -1};
                    int a = mapVertexWithUV(allVertices, allUVs, pairToNew, verts, uvs, triV[0], triVt[0]);
                    int b = mapVertexWithUV(allVertices, allUVs, pairToNew, verts, uvs, triV[1], triVt[1]);
                    int c = mapVertexWithUV(allVertices, allUVs, pairToNew, verts, uvs, triV[2], triVt[2]);
                    triFaces.add(new Face(a, b, c));
                }
                if (!verts.isEmpty() && !triFaces.isEmpty()) {
                    Mesh m = new Mesh();
                    m.vertices = verts;
                    m.faces = triFaces;
                    if (!uvs.isEmpty()) m.uvs = uvs;
                    // 记录原始材质与贴图信息
                    Map<String, Object> attrs = m.getAttributes();
                    if (attrs == null) attrs = new HashMap<>();
                    String materialName = groupKeyToMaterial.get(grpKey);
                    if ((materialName == null || materialName.isEmpty()) && groupNameFallbackMaterial.containsKey(grpKey)) {
                        materialName = groupNameFallbackMaterial.get(grpKey);
                    }
                    if (materialName != null && !materialName.isEmpty()) {
                        attrs.put("originalMaterial", materialName);
                    }
                    if (foundMtl != null) {
                        attrs.put("sourceMtlFile", foundMtl.getAbsolutePath());
                    }
                    if (materialToTexture != null && !materialToTexture.isEmpty()) {
                        attrs.put("materialMap", materialToTexture);
                        if (materialName != null && materialToTexture.containsKey(materialName)) {
                            attrs.put("material", materialName);
                            attrs.put("texture", materialToTexture.get(materialName));
                        }
                        // 若未能从分组名得到材质，但存在材质映射，则选择一个合理的默认材质
                        if (!attrs.containsKey("originalMaterial")) {
                            // 优先选择有贴图的材质名
                            String chosenMat = null;
                            String chosenTex = null;
                            for (Map.Entry<String, String> en : materialToTexture.entrySet()) {
                                if (en.getKey() != null && !en.getKey().isEmpty() && en.getValue() != null && !en.getValue().trim().isEmpty()) {
                                    chosenMat = en.getKey();
                                    chosenTex = en.getValue();
                                    break;
                                }
                            }
                            if (chosenMat == null && !materialToTexture.isEmpty()) {
                                Map.Entry<String, String> first = materialToTexture.entrySet().iterator().next();
                                chosenMat = first.getKey();
                                chosenTex = first.getValue();
                            }
                            if (chosenMat != null) {
                                attrs.put("originalMaterial", chosenMat);
                                if (!attrs.containsKey("material")) attrs.put("material", chosenMat);
                                if (chosenTex != null && !chosenTex.trim().isEmpty() && !attrs.containsKey("texture")) {
                                    attrs.put("texture", chosenTex);
                                }
                            }
                        }
                    }
                    // Fallback: if originalMaterial missing but only one material exists in MTL, use it
                    if (!attrs.containsKey("originalMaterial") && materialToTexture != null && !materialToTexture.isEmpty() && materialToTexture.size() == 1) {
                        String soleMat = materialToTexture.keySet().iterator().next();
                        attrs.put("originalMaterial", soleMat);
                    }
                    m.setAttributes(attrs);
                    result.add(m);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    private static int mapVertexWithUV(List<Vertex> all, List<float[]> allUVs, Map<Long, Integer> pairToNew,
                                       List<Vertex> outVerts, List<float[]> outUVs, int vIdx, int vtIdx) {
        long key = (((long) Math.max(0, vIdx)) << 32) | (long) Math.max(-1, vtIdx);
        Integer mapped = pairToNew.get(key);
        if (mapped != null) return mapped;
        int newIdx = outVerts.size();
        outVerts.add(all.get(vIdx));
        if (vtIdx >= 0 && vtIdx < allUVs.size()) {
            outUVs.add(allUVs.get(vtIdx));
        } else {
            // 无UV时占位，交由导出器生成自适应UV
            outUVs.add(null);
        }
        pairToNew.put(key, newIdx);
        return newIdx;
    }

    /**
     * 查找目录中的首个 .mtl 文件
     */
    private static File findMTLFile(File dir) {
        if (dir == null || !dir.isDirectory()) return null;
        File[] files = dir.listFiles(f -> f.isFile() && f.getName().toLowerCase().endsWith(".mtl"));
        return (files != null && files.length > 0) ? files[0] : null;
    }

    /**
     * 解析 MTL 中的材质名到贴图路径映射（map_Kd）
     */
    private static Map<String, String> extractMaterialInfo(File mtlFile) {
        Map<String, String> map = new HashMap<>();
        if (mtlFile == null || !mtlFile.exists()) return map;
        try {
            List<String> lines = Files.readAllLines(mtlFile.toPath(), StandardCharsets.UTF_8);
            String current = null;
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("newmtl ")) {
                    current = line.substring(7).trim();
                } else if (line.startsWith("map_Kd ") && current != null) {
                    String tex = line.substring(7).trim();
                    // 将相对路径转换为绝对路径
                    if (!tex.startsWith("/") && !tex.contains(":")) {
                        // 相对路径，基于MTL文件所在目录
                        File texFile = new File(mtlFile.getParentFile(), tex);
                        if (texFile.exists()) {
                            tex = texFile.getAbsolutePath();
                        }
                    }
                    map.put(current, tex);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return map;
    }

    /**
     * 为管点特征模型生成颜色纹理图片
     * 根据MTL文件中的Kd颜色值生成对应的map_Kd纹理
     * 纹理文件将保存到与OBJ文件相同的目录下
     */
    private static void generateColorTexturesForFeatureModels(File mtlFile, File objFile, Map<String, String> materialToTexture) {
        if (mtlFile == null || !mtlFile.exists()) return;
        
        // 确定纹理文件保存目录：优先使用OBJ文件所在目录，否则使用MTL文件所在目录
        File textureOutputDir = (objFile != null && objFile.getParentFile() != null) 
            ? objFile.getParentFile() 
            : mtlFile.getParentFile();
        
        try {
            List<String> lines = Files.readAllLines(mtlFile.toPath(), StandardCharsets.UTF_8);
            String currentMaterial = null;
            float[] currentKd = null;
            
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                
                if (line.startsWith("newmtl ")) {
                    // 处理上一个材质
                    if (currentMaterial != null && currentKd != null) {
                        generateColorTexture(textureOutputDir, currentMaterial, currentKd, materialToTexture);
                    }
                    // 开始新材质
                    currentMaterial = line.substring(7).trim();
                    currentKd = null;
                } else if (line.startsWith("Kd ") && currentMaterial != null) {
                    // 解析Kd颜色值
                    String[] parts = line.substring(3).trim().split("\\s+");
                    if (parts.length >= 3) {
                        currentKd = new float[3];
                        currentKd[0] = Float.parseFloat(parts[0]);
                        currentKd[1] = Float.parseFloat(parts[1]);
                        currentKd[2] = Float.parseFloat(parts[2]);
                    }
                } else if (line.startsWith("map_Kd ") && currentMaterial != null) {
                    // 如果已经有map_Kd，跳过生成
                    currentKd = null;
                }
            }
            
            // 处理最后一个材质
            if (currentMaterial != null && currentKd != null) {
                generateColorTexture(textureOutputDir, currentMaterial, currentKd, materialToTexture);
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 生成单个材质的颜色纹理图片
     */
    private static void generateColorTexture(File outputDir, String materialName, float[] kdColor, Map<String, String> materialToTexture) {
        try {
            // 创建1x1像素的纯色图片
            BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = image.createGraphics();
            
            // 将Kd颜色值转换为RGB
            int r = Math.round(kdColor[0] * 255);
            int g = Math.round(kdColor[1] * 255);
            int b = Math.round(kdColor[2] * 255);
            
            // 确保颜色值在有效范围内
            r = Math.max(0, Math.min(255, r));
            g = Math.max(0, Math.min(255, g));
            b = Math.max(0, Math.min(255, b));
            
            g2d.setColor(new Color(r, g, b));
            g2d.fillRect(0, 0, 1, 1);
            g2d.dispose();
            
            // 生成纹理文件名
            String textureFileName = materialName + "_color.png";
            File textureFile = new File(outputDir, textureFileName);
            
            // 保存图片
            ImageIO.write(image, "PNG", textureFile);
            
            // 更新材质映射：使用相对路径，这样MTL文件中的map_Kd引用就是相对路径
            materialToTexture.put(materialName, textureFileName);
            
            
        } catch (Exception e) {
            System.err.println("[PipePointMeshBuilder] 生成颜色纹理失败: " + materialName + ", 错误: " + e.getMessage());
        }
    }

    // 收集与管点关联的"起点/终点"实际高程（考虑埋深基础），使用 PipeLine 几何坐标Z
    private static List<Double> collectAdjustedEndpointElevations(PipePoint point, List<PipeLine> lines, Map<String,
            Object> modelSettings) {
        List<Double> values = new ArrayList<>();
        if (point == null || lines == null || lines.isEmpty()) return values;
        String pointCode = point.getPointCode();
        if (pointCode == null || pointCode.trim().isEmpty()) return values;
        for (PipeLine line : lines) {
            if (pointCode.equals(line.getLineStart())) {
                // 使用管线起点高程字段作为基础高程
                double base = line.getStartElev();
                values.add(base);
            }
            if (pointCode.equals(line.getLineEnd())) {
                // 使用管线终点高程字段作为基础高程
                double base = line.getEndElev();
                values.add(base);
            }
        }
        return values;
    }

}