package com.bdtdgis.util;

import com.bdtdgis.model.PipeLine;
import com.bdtdgis.model.PipePoint;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.data.simple.SimpleFeatureSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Logger;
import java.util.logging.Level;

public class ShpReader {

    // 日志记录器
    private static final Logger logger = Logger.getLogger(ShpReader.class.getName());

    // 只保留编码缓存
    private static final Map<String, String> encodingCache = new HashMap<>();
    private static final Map<String, ShapefileDataStore> storeCache = new HashMap<>();

    private static String getCacheKey(File shpFile) {
        try {
            return shpFile.getCanonicalPath();
        } catch (IOException e) {
            return shpFile.getAbsolutePath();
        }
    }

    public static String readCpgEncoding(File shpFile) {
        String cacheKey = getCacheKey(shpFile);
        // 检查缓存
        if (encodingCache.containsKey(cacheKey)) {
            return encodingCache.get(cacheKey);
        }
        String baseName = shpFile.getName().substring(0, shpFile.getName().lastIndexOf('.'));
        File cpgFile = new File(shpFile.getParentFile(), baseName + ".cpg");
        if (!cpgFile.exists()) {
            encodingCache.put(cacheKey, "UTF-8");
            logger.info("未找到CPG文件，使用默认编码UTF-8: " + shpFile.getName());
            return "UTF-8";
        }
        try {
            byte[] bytes = Files.readAllBytes(cpgFile.toPath());
            String encoding = new String(bytes, "UTF-8").trim();
            if (isValidEncoding(encoding)) {
                encodingCache.put(cacheKey, encoding);
                logger.info("读取CPG编码: " + encoding + " -> " + shpFile.getName());
                return encoding;
            } else {
                encodingCache.put(cacheKey, "UTF-8");
                logger.warning("CPG编码无效，使用默认编码UTF-8: " + encoding + " -> " + shpFile.getName());
                return "UTF-8";
            }
        } catch (Exception e) {
            encodingCache.put(cacheKey, "UTF-8");
            logger.log(Level.WARNING, "读取CPG文件异常，使用默认编码UTF-8: " + shpFile.getName(), e);
            return "UTF-8";
        }
    }

    private static boolean isValidEncoding(String encoding) {
        if (encoding == null || encoding.trim().isEmpty()) {
            return false;
        }
        try {
            Charset.forName(encoding);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static ShapefileDataStore getOrCreateShapefileDataStore(File shpFile) throws Exception {
        String cacheKey = getCacheKey(shpFile);
        // 检查缓存
        if (storeCache.containsKey(cacheKey)) {
            return storeCache.get(cacheKey);
        }
        // 读取编码
        String encoding = readCpgEncoding(shpFile);
        // 创建DataStore
        ShapefileDataStore store = new ShapefileDataStore(shpFile.toURI().toURL());
        store.setCharset(Charset.forName(encoding));
        // 缓存DataStore
        storeCache.put(cacheKey, store);
        logger.info("创建ShapefileDataStore: " + shpFile.getName() + " (编码: " + encoding + ")");
        return store;
    }

    public static List<String> readFields(File shpFile) throws Exception {
        try {
            ShapefileDataStore store = getOrCreateShapefileDataStore(shpFile);
            SimpleFeatureSource featureSource = store.getFeatureSource();
            SimpleFeatureType schema = featureSource.getSchema();
            List<String> fieldNames = new ArrayList<>();
            schema.getAttributeDescriptors().forEach(attr -> fieldNames.add(attr.getLocalName()));
            logger.info("读取字段列表: " + shpFile.getName() + " -> " + fieldNames.size() + " 个字段");
            return fieldNames;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "读取字段列表异常: " + shpFile.getName(), e);
            throw e;
        }
    }

    public static List<PipePoint> readPointsWithAttr(File shpFile, Map<String, String> fieldMap) throws Exception {
        if (shpFile == null || !shpFile.exists()) {
            logger.warning("管点Shapefile文件不存在: " + (shpFile != null ? shpFile.getPath() : "null"));
            return Collections.emptyList();
        }
        logger.info("开始读取管点数据: " + shpFile.getName());
        ShapefileDataStore store = getOrCreateShapefileDataStore(shpFile);
        if (store == null) {
            logger.warning("无法创建ShapefileDataStore: " + shpFile.getName());
            return Collections.emptyList();
        }
        try {
            SimpleFeatureSource featureSource = store.getFeatureSource();
            SimpleFeatureCollection collection = featureSource.getFeatures();
            List<PipePoint> points = new ArrayList<>();
            try (SimpleFeatureIterator it = collection.features()) {
                while (it.hasNext()) {
                    SimpleFeature feature = it.next();
                    Geometry geom = (Geometry) feature.getDefaultGeometry();
                    if (geom == null) {
                        logger.warning("跳过空几何体的管点: " + feature.getID());
                        continue;
                    }
                    Coordinate[] coords = geom.getCoordinates();
                    if (coords.length == 0) {
                        logger.warning("跳过无坐标的管点: " + feature.getID());
                        continue;
                    }
                    double elevation = parseDouble(feature.getAttribute(fieldMap.get("ground")));
                    // 直接用原始投影坐标
                    double pointX = coords[0].x;
                    double pointY = coords[0].y;
                    double pointZ = elevation;
                    // 使用高精度坐标创建管点
                    Coordinate pointCoord = new Coordinate(pointX, pointY, pointZ);
                    Map<String, Object> attrMap = getStringObjectMap(feature);
                    String pointId = String.valueOf(feature.getAttribute(fieldMap.get("id")));
                    if (pointId.equals("32252")) {
                        double[] doubles = CoordinateTransformUtil.webMercatorToWgs84(pointX, pointY);
                        System.out.println("x:" + doubles[0] + "y:" + doubles[1]);
                    }
                    String coverRule = fieldMap.containsKey("coverRule") ?
                            (String) feature.getAttribute(fieldMap.get("coverRule")) : null;
                    Double depth = fieldMap.containsKey("depth") ? parseDouble(feature.getAttribute(fieldMap.get(
                            "depth"))) : null;
                    String coverMat = fieldMap.containsKey("coverMat") ? (String) feature.getAttribute(fieldMap.get(
                            "coverMat")) : null;
                    String pointCode = fieldMap.containsKey("code") ?
                            (String) feature.getAttribute(fieldMap.get("code")) : null;
                    String attach = fieldMap.containsKey("attach") ?
                            (String) feature.getAttribute(fieldMap.get("attach")) : null;
                    String pointFeature = fieldMap.containsKey("feature") ?
                            (String) feature.getAttribute(fieldMap.get("feature")) : null;        
                    Double direction = fieldMap.containsKey("direction") ? parseDouble(feature.getAttribute(fieldMap.get("direction"))) : null;
                    String featureType = fieldMap.containsKey("featureType") ?
                            (String) feature.getAttribute(fieldMap.get("featureType")) : null;
                    String attachType = fieldMap.containsKey("attachType") ?
                            (String) feature.getAttribute(fieldMap.get("attachType")) : null;
                    points.add(new PipePoint(pointId, coverRule, depth, coverMat, pointCoord, elevation, pointCode,
                            attrMap, attach, pointFeature, direction, featureType, attachType));
                }
            }
            return points;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "读取管点数据异常: " + shpFile.getName(), e);
            throw e;
        }
    }

    private static Map<String, Object> getStringObjectMap(SimpleFeature feature) {
        Map<String, Object> attrMap = new HashMap<>();
        for (org.opengis.feature.Property property : feature.getProperties()) {
            if (!property.getName().getLocalPart().equals("the_geom")) {
                attrMap.put(property.getName().getLocalPart(), property.getValue());
            }
        }
        return attrMap;
    }

    public static List<PipeLine> readLinesWithAttr(File shpFile, Map<String, String> fieldMap) throws Exception {
        if (shpFile == null || !shpFile.exists()) {
            logger.warning("管线Shapefile文件不存在: " + (shpFile != null ? shpFile.getPath() : "null"));
            return Collections.emptyList();
        }
        logger.info("开始读取管线数据: " + shpFile.getName());
        ShapefileDataStore store = getOrCreateShapefileDataStore(shpFile);
        if (store == null) {
            logger.warning("无法创建ShapefileDataStore: " + shpFile.getName());
            return Collections.emptyList();
        }
        try {
            SimpleFeatureSource featureSource = store.getFeatureSource();
            SimpleFeatureCollection collection = featureSource.getFeatures();
            List<PipeLine> lines = new ArrayList<>();
            try (SimpleFeatureIterator it = collection.features()) {
                while (it.hasNext()) {
                    SimpleFeature feature = it.next();
                    Geometry geom = (Geometry) feature.getDefaultGeometry();
                    if (geom == null) {
                        logger.warning("跳过空几何体的管线: " + feature.getID());
                        continue;
                    }
                    Coordinate[] coords = geom.getCoordinates();
                    if (coords.length < 2) {
                        logger.warning("跳过坐标不足的管线: " + feature.getID() + " (坐标数: " + coords.length + ")");
                        continue;
                    }
                    // 直接用原始投影坐标
                    double startX = coords[0].x;
                    double startY = coords[0].y;
                    double startZ = parseDouble(feature.getAttribute(fieldMap.get("startElev")));
                    double endX = coords[coords.length - 1].x;
                    double endY = coords[coords.length - 1].y;
                    double endZ = parseDouble(feature.getAttribute(fieldMap.get("endElev")));
                    // 使用高精度坐标创建管线
                    Coordinate startCoord = new Coordinate(startX, startY, startZ);
                    Coordinate endCoord = new Coordinate(endX, endY, endZ);
                    double diameter = fieldMap.containsKey("diameter") ?
                            parseDouble(feature.getAttribute(fieldMap.get("diameter"))) : 1.0;
                    diameter = diameter / 1000; // 转换为米
                    Map<String, Object> attrMap = getStringObjectMap(feature);
                    String lineId = String.valueOf(feature.getAttribute(fieldMap.get("id")));
                    // 起点/终点埋深为可选字段：未映射或字段为空时按 0 处理，且不参与几何计算（几何Z由高程与埋深基础决定）
                    double startDepth = readOptionalDouble(feature, fieldMap, "startDepth");
                    double endDepth = readOptionalDouble(feature, fieldMap, "endDepth");
                    String lineStart = String.valueOf(feature.getAttribute(fieldMap.get("start")));
                    String lineEnd = String.valueOf(feature.getAttribute(fieldMap.get("end")));
                    double startElev = parseDouble(feature.getAttribute(fieldMap.get("startElev")));
                    double endElev = parseDouble(feature.getAttribute(fieldMap.get("endElev")));
                    lines.add(new PipeLine(lineId, startCoord, endCoord, diameter, startDepth, endDepth, lineStart,
                            lineEnd, attrMap, startElev, endElev));
                }
            }

            return lines;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "读取管线数据异常: " + shpFile.getName(), e);
            throw e;
        }
    }

    /**
     * 读取可选的数值字段：字段未映射、名称为空或取值无法解析时返回 0.0。
     */
    private static double readOptionalDouble(SimpleFeature feature, Map<String, String> fieldMap, String key) {
        if (fieldMap == null) {
            return 0.0;
        }
        String fieldName = fieldMap.get(key);
        if (fieldName == null || fieldName.trim().isEmpty()) {
            return 0.0;
        }
        return parseDouble(feature.getAttribute(fieldName));
    }

    /**
     * 高精度数值解析方法
     * 使用BigDecimal进行高精度计算，避免浮点数精度损失
     * 增强验证和日志记录
     */
    private static double parseDouble(Object val) {
        if (val == null) {
            logger.fine("数值解析: null -> 0.0");
            return 0.0;
        }
        try {
            String strVal = val.toString().trim();
            if (strVal.isEmpty()) {
                logger.fine("数值解析: 空字符串 -> 0.0");
                return 0.0;
            }
            // 使用BigDecimal进行高精度解析
            BigDecimal bd = new BigDecimal(strVal);

            // 检查是否为有效数值
            if (bd.compareTo(BigDecimal.valueOf(Double.MAX_VALUE)) > 0) {
                logger.warning("数值超出最大值: " + strVal + " -> " + Double.MAX_VALUE);
                return Double.MAX_VALUE;
            }
            if (bd.compareTo(BigDecimal.valueOf(-Double.MAX_VALUE)) < 0) {
                logger.warning("数值超出最小值: " + strVal + " -> " + (-Double.MAX_VALUE));
                return -Double.MAX_VALUE;
            }
            double result = bd.doubleValue();
            logger.fine("数值解析成功: " + strVal + " -> " + result);
            return result;
        } catch (NumberFormatException e) {
            logger.warning("无法解析数值: " + val + ", 使用默认值0.0");
            return 0.0;
        } catch (Exception e) {
            logger.log(Level.WARNING, "数值解析异常: " + val + ", 使用默认值0.0", e);
            return 0.0;
        }
    }

    /**
     * 自动查找shp同目录下的.prj文件
     */
    public static File findPrjFile(File shpFile) {
        String baseName = shpFile.getName().substring(0, shpFile.getName().lastIndexOf('.'));
        File prjFile = new File(shpFile.getParentFile(), baseName + ".prj");
        if (prjFile.exists()) {
            return prjFile;
        }
        // 兜底：查找目录下任意 .prj 文件
        File[] files = shpFile.getParentFile().listFiles((dir, name) -> name.toLowerCase().endsWith(".prj"));
        if (files != null && files.length > 0) {
            return files[0];
        }
        return null;
    }

}