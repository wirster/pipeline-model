package com.bdtdgis.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.BiFunction;

public class TilesetUtil {

    /**
     * 生成单片tileset.json，region为经纬度弧度+实际地理高程，transform为tile中心点（WGS84）转ECEF
     *
     * @param tilesetFile  tileset文件
     * @param region       tile空间范围（投影坐标）
     * @param tileCenter   tile中心点（WGS84经纬度坐标），用于transform计算
     * @param b3dmFileName b3dm文件名
     * @param shpFile      shp文件，用于坐标转换
     */
    public static void generateSingleTilesetJson(File tilesetFile, CustomSpatialTilesUtil.BoundingRegion region,
                                                 double[] tileCenter, String b3dmFileName, File shpFile) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode tileset = mapper.createObjectNode();
        // asset信息
        ObjectNode asset = mapper.createObjectNode();
        asset.put("version", "0.0");
        asset.put("tilesetVersion", "1.0.8-obj23dtiles");
        tileset.set("asset", asset);
        tileset.put("geometricError", 0);
        // root节点
        ObjectNode root = mapper.createObjectNode();
        // 计算region的WGS84边界（从投影坐标精确转换）
        double[] regionBoundsWgs84 = calculateRegionBoundsWgs84(region, shpFile);
        double minLon = regionBoundsWgs84[0];
        double minLat = regionBoundsWgs84[1];
        double maxLon = regionBoundsWgs84[2];
        double maxLat = regionBoundsWgs84[3];
        // region: [west, south, east, north, minHeight, maxHeight]，经纬度转弧度，z为实际地理高程
        ObjectNode boundingVolume = mapper.createObjectNode();
        ArrayNode regionArray = mapper.createArrayNode();
        regionArray.add(Math.toRadians(minLon));
        regionArray.add(Math.toRadians(minLat));
        regionArray.add(Math.toRadians(maxLon));
        regionArray.add(Math.toRadians(maxLat));
        // region.minZ和region.maxZ已经是实际的地理高程，直接使用
        regionArray.add(region.minZ);
        regionArray.add(region.maxZ);
        boundingVolume.set("region", regionArray);
        root.set("boundingVolume", boundingVolume);
        // 需要ECEF变换矩阵来将局部坐标转换为ECEF坐标
        double lon = tileCenter[0];
        double lat = tileCenter[1];
        double height = tileCenter[2];
        // WGS84 -> ECEF（中心点）
        double a = 6378137.0;
        double e2 = 0.00669437999019758;
        double radLat = Math.toRadians(lat);
        double radLon = Math.toRadians(lon);
        double sinLat = Math.sin(radLat);
        double cosLat = Math.cos(radLat);
        double sinLon = Math.sin(radLon);
        double cosLon = Math.cos(radLon);
        double N = a / Math.sqrt(1 - e2 * sinLat * sinLat);
        double x = (N + height) * cosLat * cosLon;
        double y = (N + height) * cosLat * sinLon;
        double z = (N * (1 - e2) + height) * sinLat;
        // 分片中心投影坐标（用于估算列向量：本地X/Y各自代表的1米在ECEF中的增量）
        double[] centerProjected = region.centerProjected != null
                ? region.centerProjected
                : new double[]{(region.minX + region.maxX) / 2.0, (region.minY + region.maxY) / 2.0};
        double delta = 1.0; // 以1米投影步长估算
        // 计算沿投影X/Y方向偏移1米后的经纬度
        double[] px1wgs = CoordinateTransformUtil.convertProjectedToWgs84(centerProjected[0] + delta,
                centerProjected[1], shpFile);
        double[] py1wgs = CoordinateTransformUtil.convertProjectedToWgs84(centerProjected[0],
                centerProjected[1] + delta, shpFile);
        // ECEF转换函数
        BiFunction<double[], Double, double[]> toECEF = (lonLatDeg, h) -> {
            double rl = Math.toRadians(lonLatDeg[1]);
            double rL = Math.toRadians(lonLatDeg[0]);
            double sL = Math.sin(rl);
            double cL = Math.cos(rl);
            double sO = Math.sin(rL);
            double cO = Math.cos(rL);
            double NN = a / Math.sqrt(1 - e2 * sL * sL);
            double xx = (NN + h) * cL * cO;
            double yy = (NN + h) * cL * sO;
            double zz = (NN * (1 - e2) + h) * sL;
            return new double[]{xx, yy, zz};
        };
        double[] centerECEF = new double[]{x, y, z};
        double[] px1ECEF = toECEF.apply(px1wgs, height);
        double[] py1ECEF = toECEF.apply(py1wgs, height);
        double[] pz1ECEF = toECEF.apply(new double[]{lon, lat}, height + delta);
        // 直接使用ECEF增量作为列向量
        double[] colProjX = new double[]{
                px1ECEF[0] - centerECEF[0],
                px1ECEF[1] - centerECEF[1],
                px1ECEF[2] - centerECEF[2]
        };
        double[] colProjY = new double[]{
                py1ECEF[0] - centerECEF[0],
                py1ECEF[1] - centerECEF[1],
                py1ECEF[2] - centerECEF[2]
        };
        double[] colUp = new double[]{
                pz1ECEF[0] - centerECEF[0],
                pz1ECEF[1] - centerECEF[1],
                pz1ECEF[2] - centerECEF[2]
        };
        // 右手系校正：使 colProjX × colProjY 与 colUp 同向（如不一致则翻转 colProjY）
        double cx = colProjX[1] * colProjY[2] - colProjX[2] * colProjY[1];
        double cy = colProjX[2] * colProjY[0] - colProjX[0] * colProjY[2];
        double cz = colProjX[0] * colProjY[1] - colProjX[1] * colProjY[0];
        double dotCrossUp = cx * colUp[0] + cy * colUp[1] + cz * colUp[2];
        // 修复：避免不必要的坐标系翻转，确保管点模型方向正确
        // 只有当叉积与Up向量的点积为负，且差值较大时才进行翻转
        if (dotCrossUp < -0.1) { // 添加阈值，避免微小误差导致的翻转
            System.out.println("检测到坐标系方向不一致，进行右手系校正");
            colUp[0] = -colUp[0];
            colUp[1] = -colUp[1];
            colUp[2] = -colUp[2];
        }
        // 列主序：列1 = 模型X（投影X）
        ArrayNode transform = mapper.createArrayNode();
        transform.add(colProjX[0]);
        transform.add(colProjX[1]);
        transform.add(colProjX[2]);
        transform.add(0.0);
        // 列2 = 模型Y（投影Y）
        transform.add(colProjY[0]);
        transform.add(colProjY[1]);
        transform.add(colProjY[2]);
        transform.add(0.0);
        // 列3 = 模型Z（Up，高程）
        transform.add(colUp[0]);
        transform.add(colUp[1]);
        transform.add(colUp[2]);
        transform.add(0.0);
        // 列4：平移（ECEF）
        transform.add(x);
        transform.add(y);
        transform.add(z);
        transform.add(1.0);
        root.set("transform", transform);
        root.put("geometricError", 0);
        root.put("refine", "ADD");
        // content
        ObjectNode content = mapper.createObjectNode();
        content.put("url", b3dmFileName);
        root.set("content", content);
        tileset.set("root", root);
        mapper.writerWithDefaultPrettyPrinter().writeValue(tilesetFile, tileset);
    }

    /**
     * 计算region的WGS84边界（从投影坐标精确转换）
     * 使用CoordinateTransformUtil.convertProjectedToWgs84进行精确转换
     */
    private static double[] calculateRegionBoundsWgs84(CustomSpatialTilesUtil.BoundingRegion region, File shpFile) {
        try {
            // 使用CoordinateTransformUtil进行精确的投影坐标到WGS84转换
            double[] minPointWgs84 = CoordinateTransformUtil.convertProjectedToWgs84(region.minX, region.minY, shpFile);
            double[] maxPointWgs84 = CoordinateTransformUtil.convertProjectedToWgs84(region.maxX, region.maxY, shpFile);

            // 确保经纬度顺序正确
            double minLon = Math.min(minPointWgs84[0], maxPointWgs84[0]);
            double maxLon = Math.max(minPointWgs84[0], maxPointWgs84[0]);
            double minLat = Math.min(minPointWgs84[1], maxPointWgs84[1]);
            double maxLat = Math.max(minPointWgs84[1], maxPointWgs84[1]);

            return new double[]{minLon, minLat, maxLon, maxLat};

        } catch (Exception e) {
            System.err.println("坐标转换失败，使用估算值: " + e.getMessage());
            // 如果精确转换失败，回退到估算值
            return calculateRegionBoundsWgs84Fallback(region);
        }
    }

    /**
     * 坐标转换失败时的回退方法（使用估算值）
     */
    private static double[] calculateRegionBoundsWgs84Fallback(CustomSpatialTilesUtil.BoundingRegion region) {
        // 如果BoundingRegion有WGS84中心点，基于中心点估算边界
        if (region.centerWgs84Rad != null) {
            double centerLon = Math.toDegrees(region.centerWgs84Rad[0]);
            double centerLat = Math.toDegrees(region.centerWgs84Rad[1]);

            // 基于投影坐标范围估算经纬度范围
            // 这里使用一个简化的估算，实际应用中需要精确的投影坐标转换
            double estimatedLonRange = (region.maxX - region.minX) / 100000.0; // 假设1度约等于100km
            double estimatedLatRange = (region.maxY - region.minY) / 111000.0; // 1度纬度约等于111km

            double minLon = centerLon - estimatedLonRange / 2.0;
            double maxLon = centerLon + estimatedLonRange / 2.0;
            double minLat = centerLat - estimatedLatRange / 2.0;
            double maxLat = centerLat + estimatedLatRange / 2.0;

            return new double[]{minLon, minLat, maxLon, maxLat};
        } else {
            // 如果没有中心点信息，返回默认值
            return new double[]{0.0, 0.0, 0.0, 0.0};
        }
    }

    /**
     * 生成根tileset.json文件
     */
    public static void generateRootTilesetJson(File outputDir, List<String> tileUrls,
                                               List<CustomSpatialTilesUtil.BoundingRegion> regions, File shpFile) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode tileset = mapper.createObjectNode();
        // asset信息
        ObjectNode asset = mapper.createObjectNode();
        asset.put("version", "0.0");
        asset.put("tilesetVersion", "1.0.8-obj23dtiles");
        asset.put("gltfUpAxis", "Z");
        tileset.set("asset", asset);
        // geometricError
        tileset.put("geometricError", 500);
        // root节点
        ObjectNode root = mapper.createObjectNode();
        // 计算全局boundingVolume
        ObjectNode boundingVolume = mapper.createObjectNode();
        ArrayNode region = mapper.createArrayNode();
        if (!regions.isEmpty()) {
            double minLon = Double.MAX_VALUE, minLat = Double.MAX_VALUE;
            double maxLon = -Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
            double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE; // 修改为全局最小/最大高程
            for (CustomSpatialTilesUtil.BoundingRegion r : regions) {
                // 计算每个region的WGS84边界
                double[] regionBoundsWgs84 = calculateRegionBoundsWgs84(r, shpFile);
                double regionMinLon = regionBoundsWgs84[0];
                double regionMinLat = regionBoundsWgs84[1];
                double regionMaxLon = regionBoundsWgs84[2];
                double regionMaxLat = regionBoundsWgs84[3];
                minLon = Math.min(minLon, regionMinLon);
                minLat = Math.min(minLat, regionMinLat);
                maxLon = Math.max(maxLon, regionMaxLon);
                maxLat = Math.max(maxLat, regionMaxLat);
                // 对于根tileset，我们使用去中心化后的Z范围，因为这是相对范围
                minZ = Math.min(minZ, r.minZ);
                maxZ = Math.max(maxZ, r.maxZ);
            }
            if (minLon == Double.MAX_VALUE) minLon = 0;
            if (minLat == Double.MAX_VALUE) minLat = 0;
            if (maxLon == -Double.MAX_VALUE) maxLon = 0;
            if (maxLat == -Double.MAX_VALUE) maxLat = 0;
            if (minZ == Double.MAX_VALUE) minZ = 0;
            if (maxZ == -Double.MAX_VALUE) maxZ = 0;
            region.add(Math.toRadians(minLon));
            region.add(Math.toRadians(minLat));
            region.add(Math.toRadians(maxLon));
            region.add(Math.toRadians(maxLat));
            region.add(minZ);
            region.add(maxZ);
        }
        boundingVolume.set("region", region);
        root.set("boundingVolume", boundingVolume);
        root.put("refine", "ADD");
        root.put("geometricError", 500);
        // children
        ArrayNode children = mapper.createArrayNode();
        for (int i = 0; i < tileUrls.size(); i++) {
            ObjectNode child = mapper.createObjectNode();
            // 子节点的boundingVolume
            ObjectNode childBoundingVolume = mapper.createObjectNode();
            ArrayNode childRegion = mapper.createArrayNode();
            if (i < regions.size()) {
                CustomSpatialTilesUtil.BoundingRegion region_obj = regions.get(i);
                // 计算子region的WGS84边界
                double[] childRegionBoundsWgs84 = calculateRegionBoundsWgs84(region_obj, shpFile);
                childRegion.add(Math.toRadians(childRegionBoundsWgs84[0]));
                childRegion.add(Math.toRadians(childRegionBoundsWgs84[1]));
                childRegion.add(Math.toRadians(childRegionBoundsWgs84[2]));
                childRegion.add(Math.toRadians(childRegionBoundsWgs84[3]));
                childRegion.add(region_obj.minZ);
                childRegion.add(region_obj.maxZ);
            }
            childBoundingVolume.set("region", childRegion);
            child.set("boundingVolume", childBoundingVolume);
            child.put("geometricError", 0);
            child.put("refine", "ADD");
            ObjectNode content = mapper.createObjectNode();
            // 保证url为相对路径
            String url = tileUrls.get(i);
            // 若为绝对路径，转为相对outputDir的路径
            File urlFile = new File(url);
            if (urlFile.isAbsolute()) {
                String relative = outputDir.toPath().relativize(urlFile.toPath()).toString().replace("\\", "/");
                content.put("url", relative);
            } else {
                content.put("url", url.replace("\\", "/"));
            }
            child.set("content", content);
            children.add(child);
        }
        root.set("children", children);
        tileset.set("root", root);
        // 写入文件
        File tilesetFile = new File(outputDir, "tileset.json");
        if (!tilesetFile.getParentFile().exists()) {
            tilesetFile.getParentFile().mkdirs();
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(tilesetFile, tileset);
    }

}