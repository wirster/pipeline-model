package com.bdtdgis.util;

import com.bdtdgis.model.Mesh;
import com.bdtdgis.model.PipeLine;
import com.bdtdgis.model.PipePoint;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.opengis.feature.simple.SimpleFeature;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于gridSize参数的空间分片工具类
 * 根据gridSize值动态计算分片数量和大小
 */
public class CustomSpatialTilesUtil {

    /**
     * 分片区域类
     */
    public static class BoundingRegion {
        public final double minLon, minLat, maxLon, maxLat;
        public final int tileIndex;
        public final double[] centerProjected; // 投影坐标中心点
        public final double[] centerWgs84Rad; // WGS84弧度坐标中心点
        public final double minZ, maxZ;
        public final double minX, maxX, minY, maxY; // 新增投影坐标四至

        public BoundingRegion(double minX, double minY, double maxX, double maxY,
                              int tileIndex, double[] centerProjected, double[] centerWgs84Rad,
                              double minZ, double maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
            this.tileIndex = tileIndex;
            this.centerProjected = centerProjected;
            this.centerWgs84Rad = centerWgs84Rad;
            this.minZ = minZ;
            this.maxZ = maxZ;
            // 为了兼容性，保留经纬度字段（从投影坐标转换得到）
            if (centerWgs84Rad != null) {
                // 使用更精确的估算，基于分片大小
                double estimatedLonRange = (maxX - minX) / 100000.0; // 假设1度约等于100km
                double estimatedLatRange = (maxY - minY) / 111000.0; // 1度纬度约等于111km
                double centerLon = Math.toDegrees(centerWgs84Rad[0]);
                double centerLat = Math.toDegrees(centerWgs84Rad[1]);
                this.minLon = centerLon - estimatedLonRange / 2.0;
                this.maxLon = centerLon + estimatedLonRange / 2.0;
                this.minLat = centerLat - estimatedLatRange / 2.0;
                this.maxLat = centerLat + estimatedLatRange / 2.0;
            } else {
                this.minLon = 0.0;
                this.maxLon = 0.0;
                this.minLat = 0.0;
                this.maxLat = 0.0;
            }
        }

        @Override
        public String toString() {
            return String.format("BoundingRegion{tileIndex=%d, projected=[%.2f,%.2f,%.2f,%.2f], wgs84=[%.6f,%.6f,%" +
                            ".6f,%.6f], z=[%.2f,%.2f]}",
                    tileIndex, minX, minY, maxX, maxY, minLon, minLat, maxLon, maxLat, minZ, maxZ);
        }
    }

    /**
     * 基于gridSize参数进行空间分片
     *
     * @param lines    管线数据列表
     * @param points   管点数据列表
     * @param gridSize 分片大小（km）
     * @param shpFile  用于坐标转换的shp文件
     * @return 分片结果列表
     */
    public static List<BoundingRegion> performGridSizeBasedSplitting(List<PipeLine> lines, List<PipePoint> points,
                                                                     Double gridSize, File shpFile) {
        if (shpFile == null) {
            throw new RuntimeException("未找到shp文件，无法进行坐标转换");
        }
        System.out.println("=== 基于gridSize的空间分片开始 ===");
        System.out.println("  gridSize: " + gridSize + "km");
        // 直接使用shp文件的投影坐标四至信息计算全局包围盒
        double[] projectedBounds = getProjectedBoundsFromFile(shpFile);
        if (projectedBounds == null || projectedBounds.length != 4) {
            throw new RuntimeException("无法获取shp文件的投影坐标四至信息，请检查文件完整性");
        }
        double minX = projectedBounds[0]; // 最小X坐标
        double minY = projectedBounds[1]; // 最小Y坐标
        double maxX = projectedBounds[2]; // 最大X坐标
        double maxY = projectedBounds[3]; // 最大Y坐标
        // 计算高程范围（从管线/管点数据中获取）
        double globalMinZ = Double.MAX_VALUE, globalMaxZ = -Double.MAX_VALUE;
        // 处理管线数据的高程
        if (lines != null) {
            for (PipeLine line : lines) {
                if (line.getStart() != null && line.getEnd() != null) {
                    globalMinZ = Math.min(globalMinZ, Math.min(line.getStart().z, line.getEnd().z));
                    globalMaxZ = Math.max(globalMaxZ, Math.max(line.getStart().z, line.getEnd().z));
                }
            }
        }
        // 处理管点数据的高程
        if (points != null) {
            for (PipePoint point : points) {
                if (point.getCoord() != null) {
                    globalMinZ = Math.min(globalMinZ, point.getCoord().z);
                    globalMaxZ = Math.max(globalMaxZ, point.getCoord().z);
                }
            }
        }
        System.out.println("基于shp文件投影坐标的全局包围盒: minX=" + minX + ", minY=" + minY +
                ", maxX=" + maxX + ", maxY=" + maxY);
        // 根据gridSize创建分片
        List<BoundingRegion> regions = creategridSizeBasedRegions(
                minX, minY, maxX, maxY, globalMinZ, globalMaxZ, gridSize, shpFile);
        System.out.println("分片完成，生成了 " + regions.size() + " 个分片");
        System.out.println("=== 基于gridSize的空间分片完成 ===");
        return regions;
    }

    /**
     * 根据gridSize参数创建分片
     * gridSize值越大，顶层分片越少；gridSize值越小，顶层分片越多
     */
    private static List<BoundingRegion> creategridSizeBasedRegions(
            double minX, double minY, double maxX, double maxY,
            double minZ, double maxZ, double gridSize, File shpFile) {
        List<BoundingRegion> regions = new ArrayList<>();
        // 计算实际的空间范围（投影坐标单位，通常是米）
        double xRange = maxX - minX;
        double yRange = maxY - minY;
        // 将gridSize从km转换为投影坐标单位（通常是米）
        double gridSizeInMeters = gridSize * 1000.0; // 转换为米
        // 计算网格步长
        double xStep = gridSizeInMeters; // X方向步长（米）
        double yStep = gridSizeInMeters; // Y方向步长（米）
        System.out.println("=== 动态分片计算（投影坐标） ===");
        System.out.println("  空间范围: X=" + String.format("%.2f", xRange) + "m, Y=" + String.format("%.2f", yRange) +
                "m");
        System.out.println("  gridSize: " + gridSize + "km (" + gridSizeInMeters + "m)");
        System.out.println("  网格步长: X=" + xStep + "m, Y=" + yStep + "m");
        // 使用投影坐标直接计算面积（平方米）
        double totalAreaM2 = xRange * yRange; // 总面积（平方米）
        double totalAreaKm2 = totalAreaM2 / 1000000.0; // 转换为平方公里
        // 单个分片面积（平方公里）
        double singleTileAreaKm2 = gridSize * gridSize; // gridSize × gridSize
        // 根据总面积和单个分片面积计算分片数量
        double rawRegions = totalAreaKm2 / singleTileAreaKm2; // 原始计算结果
        int totalRegions = (int) Math.ceil(rawRegions); // 向上取整
        System.out.println("  投影坐标面积: " + String.format("%.2f", totalAreaM2) + " m² (" + String.format("%.2f",
                totalAreaKm2) + " km²)");
        System.out.println("  单个分片面积: " + String.format("%.2f", singleTileAreaKm2) + " km²");
        System.out.println("  分片数量计算: " + String.format("%.2f", totalAreaKm2) + " ÷ " + String.format("%.2f",
                singleTileAreaKm2) + " = " + String.format("%.2f", rawRegions));
        System.out.println("  向上取整后分片数量: " + totalRegions + " 个分片");
        System.out.println("=== 动态分片计算完成 ===");
        // 创建分片
        createFlexibleTiles(regions, minX, minY, maxX, maxY, minZ, maxZ,
                xStep, yStep, shpFile);
        return regions;
    }

    /**
     * 创建灵活的分片布局
     */
    private static void createFlexibleTiles(List<BoundingRegion> regions,
                                            double minX, double minY, double maxX, double maxY,
                                            double minZ, double maxZ, double xStep, double yStep,
                                            File shpFile) {
        int tileIndex = 1;
        double currentX = minX;
        while (currentX < maxX) {
            double currentY = minY;
            while (currentY < maxY) {
                double regionMaxX = Math.min(currentX + xStep, maxX);
                double regionMaxY = Math.min(currentY + yStep, maxY);
                // 计算中心点（投影坐标）
                double regionCenterX = (currentX + regionMaxX) / 2.0;
                double regionCenterY = (currentY + regionMaxY) / 2.0;
                // 将投影坐标中心点转换为WGS84坐标（用于tileset.json）
                double[] centerWgs84 = CoordinateTransformUtil.convertProjectedToWgs84(regionCenterX, regionCenterY,
                        shpFile);
                double[] centerWgs84Rad = {Math.toRadians(centerWgs84[0]), Math.toRadians(centerWgs84[1])};
                // 创建BoundingRegion实例
                BoundingRegion region = new BoundingRegion(
                        currentX, currentY, regionMaxX, regionMaxY,
                        tileIndex, new double[]{regionCenterX, regionCenterY}, centerWgs84Rad, minZ, maxZ);
                regions.add(region);
                tileIndex++;
                currentY = regionMaxY;
            }
            currentX += xStep;  // 修复：使用xStep递增，确保正确遍历
        }
    }

    /**
     * 分配管线mesh到分片
     */
    public static Map<BoundingRegion, List<Mesh>> assignLineMeshesToRegions(List<PipeLine> lines,
                                                                            List<BoundingRegion> regions,
                                                                            Map<String, Object> modelSettings,
                                                                            List<PipePoint> points) {
        Map<BoundingRegion, List<Mesh>> lineRegionMeshes = new HashMap<>();
        // 初始化每个region的mesh列表
        for (BoundingRegion region : regions) {
            lineRegionMeshes.put(region, new ArrayList<>());
        }
        // 创建管点映射，方便快速查找
        Map<String, PipePoint> pointMap = new HashMap<>();
        for (PipePoint point : points) {
            pointMap.put(point.getPointCode(), point);
        }
        System.out.println("开始分配管线mesh，管线数量: " + lines.size() + ", 分片数量: " + regions.size());
        for (PipeLine line : lines) {
            // 找到包含该管线的region
            BoundingRegion targetRegion = findRegionForLine(line, regions);
            if (targetRegion != null) {
                // 根据管线起点和终点编号查找对应的管点
                Mesh mesh = PipeLineMeshBuilder.generatePipeLineMesh(line, modelSettings);
                if (mesh != null) {
                    lineRegionMeshes.get(targetRegion).add(mesh);
                }
            }
        }
        return lineRegionMeshes;
    }

    /**
     * 分配管点mesh到分片
     */
    public static Map<BoundingRegion, List<Mesh>> assignPointMeshesToRegions(List<PipePoint> points,
                                                                             List<PipeLine> lines,
                                                                             List<BoundingRegion> regions,
                                                                             Map<String, Object> modelSettings) {
        Map<BoundingRegion, List<Mesh>> pointRegionMeshes = new HashMap<>();
        // 初始化每个region的mesh列表
        for (BoundingRegion region : regions) {
            pointRegionMeshes.put(region, new ArrayList<>());
        }
        System.out.println("开始分配管点附属物模型，管点数量: " + points.size() + ", 分片数量: " + regions.size());
        for (PipePoint point : points) {
            BoundingRegion targetRegion = findRegionForPoint(point, regions);
            if (targetRegion != null) {
                List<Mesh> accessoryMeshes = PipePointMeshBuilder.generatePipePointMesh(point, lines, modelSettings);
                if (accessoryMeshes != null && !accessoryMeshes.isEmpty()) {
                    for (Mesh mesh : accessoryMeshes) {
                        if (mesh != null) {
                            pointRegionMeshes.get(targetRegion).add(mesh);
                        }
                    }
                }
            }
        }
        return pointRegionMeshes;
    }

    /**
     * 为管线找到对应的region
     */
    private static BoundingRegion findRegionForLine(PipeLine line, List<BoundingRegion> regions) {
        if (line.getStart() == null || line.getEnd() == null) {
            return null;
        }
        // 管线坐标已经是投影坐标，直接使用，不需要转换
        double startX = line.getStart().x;
        double startY = line.getStart().y;
        double endX = line.getEnd().x;
        double endY = line.getEnd().y;
        // 计算管线中心点
        double lineCenterX = (startX + endX) / 2.0;
        double lineCenterY = (startY + endY) / 2.0;
        // 找到包含管线中心点的region（优先）
        for (BoundingRegion region : regions) {
            if (isPointInRegion(lineCenterX, lineCenterY, region)) {
                return region;
            }
        }
        // 如果中心点不在任何region内，则检查起点或终点
        for (BoundingRegion region : regions) {
            if (isLineInRegion(startX, startY, endX, endY, region)) {
                System.out.println("管线分配到region(边界): 起点[" + startX + ", " + startY + "] 终点[" + endX + ", " + endY +
                        "] -> region[" +
                        region.minX + ", " + region.minY + ", " + region.maxX + ", " + region.maxY + "]");
                return region;
            }
        }
        System.out.println("警告: 管线未分配到任何region: 中心点[" + lineCenterX + ", " + lineCenterY + "]");
        return null;
    }

    /**
     * 为管点找到对应的region
     */
    private static BoundingRegion findRegionForPoint(PipePoint point, List<BoundingRegion> regions) {
        if (point.getCoord() == null) {
            return null;
        }

        // 管点坐标已经是投影坐标，直接使用，不需要转换
        double pointX = point.getCoord().x;
        double pointY = point.getCoord().y;

        // 找到包含该管点的region
        for (BoundingRegion region : regions) {
            if (isPointInRegion(pointX, pointY, region)) {
                return region;
            }
        }
        return null;
    }

    /**
     * 检查管线是否在region内（使用投影坐标）
     */
    private static boolean isLineInRegion(double startX, double startY, double endX, double endY,
                                          BoundingRegion region) {
        // 检查起点或终点是否在region内
        boolean startInRegion =
                (startX >= region.minX && startX <= region.maxX && startY >= region.minY && startY <= region.maxY);
        boolean endInRegion =
                (endX >= region.minX && endX <= region.maxX && endY >= region.minY && endY <= region.maxY);

        // 如果起点或终点在region内，返回true
        if (startInRegion || endInRegion) {
            return true;
        }

        // 检查管线是否穿过region（线段与矩形相交）
        // 简化的相交检测：检查线段的任何部分是否在region内
        double minLineX = Math.min(startX, endX);
        double maxLineX = Math.max(startX, endX);
        double minLineY = Math.min(startY, endY);
        double maxLineY = Math.max(startY, endY);

        // 检查线段包围盒是否与region包围盒相交
        return !(maxLineX < region.minX || minLineX > region.maxX ||
                maxLineY < region.minY || minLineY > region.maxY);
    }

    /**
     * 检查管点是否在region内（使用投影坐标）
     */
    private static boolean isPointInRegion(double pointX, double pointY, BoundingRegion region) {
        return pointX >= region.minX && pointX <= region.maxX &&
                pointY >= region.minY && pointY <= region.maxY;
    }

    /**
     * 从shp文件直接获取投影坐标四至信息
     *
     * @param shpFile shp文件
     * @return 投影坐标四至数组 [minX, minY, maxX, maxY]
     */
    private static double[] getProjectedBoundsFromFile(File shpFile) {
        try {
            // 直接读取shp文件获取真实的坐标范围
            ShapefileDataStore store = new ShapefileDataStore(shpFile.toURI().toURL());
            SimpleFeatureCollection collection = store.getFeatureSource().getFeatures();
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            try (SimpleFeatureIterator it = collection.features()) {
                while (it.hasNext()) {
                    SimpleFeature feature = it.next();
                    Geometry geom = (Geometry) feature.getDefaultGeometry();
                    if (geom == null) continue;
                    Coordinate[] coords = geom.getCoordinates();
                    for (Coordinate coord : coords) {
                        minX = Math.min(minX, coord.x);
                        maxX = Math.max(maxX, coord.x);
                        minY = Math.min(minY, coord.y);
                        maxY = Math.max(maxY, coord.y);
                    }
                }
            }
            store.dispose();
            // 检查是否找到了有效的坐标
            if (minX == Double.MAX_VALUE || minY == Double.MAX_VALUE ||
                    maxX == -Double.MAX_VALUE || maxY == -Double.MAX_VALUE) {
                System.err.println("未找到有效的投影坐标数据: " + shpFile.getName());
                return null;
            }
            System.out.println("shp文件投影坐标四至信息获取成功:");
            System.out.println("  投影坐标范围: X[" + minX + ", " + maxX + "], Y[" + minY + ", " + maxY + "]");
            return new double[]{minX, minY, maxX, maxY};
        } catch (Exception e) {
            System.err.println("获取shp文件投影坐标四至信息失败: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
}
