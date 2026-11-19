package com.bdtdgis.util;

import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.referencing.CRS;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 高精度坐标转换工具类
 * 提供WGS84坐标系与投影坐标系之间的高精度转换功能
 * 使用BigDecimal进行高精度计算，减少中间计算步骤
 * 增强坐标验证和日志记录
 */
public class CoordinateTransformUtil {
    
    // 日志记录器
    private static final Logger logger = Logger.getLogger(CoordinateTransformUtil.class.getName());

    // 高精度常量定义
    // WGS84椭球体长半轴
    private static final BigDecimal WEB_MERCATOR_MAX = new BigDecimal("20037508.342789244"); // WebMercator最大坐标
    private static final BigDecimal PI = new BigDecimal("3.1415926535897932384626433832795");
    private static final BigDecimal RAD_TO_DEG = new BigDecimal("180").divide(PI, 20, RoundingMode.HALF_UP);
    
    // 计算精度设置
    private static final int CALCULATION_PRECISION = 20;
    private static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;
    
    // 坐标范围常量
    private static final double MIN_LON = -180.0;
    private static final double MAX_LON = 180.0;
    private static final double MIN_LAT = -90.0;
    private static final double MAX_LAT = 90.0;
    private static final double MIN_WEB_MERCATOR_X = -20037508.342789244;
    private static final double MAX_WEB_MERCATOR_X = 20037508.342789244;
    private static final double MIN_WEB_MERCATOR_Y = -20037508.342789244;
    private static final double MAX_WEB_MERCATOR_Y = 20037508.342789244;

    /**
     * 根据SimpleFeatureSource判断是否为WGS84:4326坐标系
     */
    public static boolean isWGS84(SimpleFeatureSource featureSource) {
        if (featureSource == null) {
            logger.warning("SimpleFeatureSource为空，无法判断坐标系");
            return false;
        }
        try {
            SimpleFeatureType schema = featureSource.getSchema();
            if (schema == null) {
                logger.warning("SimpleFeatureType为空，无法判断坐标系");
                return false;
            }
            CoordinateReferenceSystem crs = schema.getCoordinateReferenceSystem();
            if (crs == null) {
                logger.warning("CoordinateReferenceSystem为空，无法判断坐标系");
                return false;
            }
            String epsgString = CRS.lookupIdentifier(crs, true);
            if (epsgString == null) {
                logger.warning("无法获取EPSG标识符");
                return false;
            }
            boolean isWGS84 = epsgString.equals("EPSG:4326");
            logger.info("坐标系检查结果: " + epsgString + " -> " + (isWGS84 ? "WGS84" : "非WGS84"));
            return isWGS84;
        } catch (Exception e) {
            logger.log(Level.WARNING, "坐标系检查异常", e);
            return false;
        }
    }

    /**
     * 高精度WebMercator EPSG:3857坐标转换为WGS84经纬度
     * 优化算法，减少中间计算步骤，提高精度
     * 增强坐标验证和日志记录
     * @param x WebMercator X坐标
     * @param y WebMercator Y坐标
     * @return WGS84坐标 [经度, 纬度]
     */
    public static double[] webMercatorToWgs84(double x, double y) {
        // 输入坐标验证
        if (!validateWebMercatorInput(x, y)) {
            logger.warning("WebMercator输入坐标无效: x=" + x + ", y=" + y);
            return new double[]{0.0, 0.0};
        }
        try {
            // 经度转换（BigDecimal高精度）
            BigDecimal xBD = new BigDecimal(x);
            BigDecimal lon = xBD.multiply(new BigDecimal("180"))
                    .divide(WEB_MERCATOR_MAX, CALCULATION_PRECISION, ROUNDING_MODE);

            // 纬度转换（BigDecimal高精度标准实现）
            BigDecimal yBD = new BigDecimal(y);
            BigDecimal latBD = yBD.multiply(new BigDecimal("180"))
                    .divide(WEB_MERCATOR_MAX, CALCULATION_PRECISION, ROUNDING_MODE);
            // latBD 现在是归一化到经纬度的度数
            // 转为弧度
            BigDecimal latRad = latBD.multiply(PI).divide(new BigDecimal("180"), CALCULATION_PRECISION, ROUNDING_MODE);
            // exp(latRad)
            double expVal = Math.exp(latRad.doubleValue());
            // 2 * atan(expVal) - PI/2
            double atanVal = 2.0 * Math.atan(expVal) - Math.PI / 2.0;
            // 转为度
            BigDecimal lat = new BigDecimal(atanVal).multiply(RAD_TO_DEG);

            double[] result = new double[]{lon.doubleValue(), lat.doubleValue()};

            // 精度测试：转换回WebMercator验证
            double[] backToWebMercator = wgs84ToWebMercator(result[0], result[1]);
            double xDiff = Math.abs(x - backToWebMercator[0]);
            double yDiff = Math.abs(y - backToWebMercator[1]);
            if (xDiff > 0.1 || yDiff > 0.1) {
                logger.warning("坐标转换精度问题 - 原始: (" + x + ", " + y + ") -> WGS84: (" + result[0] + ", " + result[1] + ") -> 回转换: (" + backToWebMercator[0] + ", " + backToWebMercator[1] + ")");
                logger.warning("精度差异 - X: " + xDiff + ", Y: " + yDiff);
            }

            // 输出坐标验证
            if (validateWGS84Output(result[0], result[1])) {
                logger.fine("WebMercator到WGS84转换成功: (" + x + ", " + y + ") -> (" + result[0] + ", " + result[1] + ")");
                return result;
            } else {
                logger.warning("WebMercator到WGS84转换结果超出范围: (" + x + ", " + y + ") -> (" + result[0] + ", " + result[1] + ")");
                return new double[]{0.0, 0.0};
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "WebMercator到WGS84转换异常: x=" + x + ", y=" + y, e);
            return new double[]{0.0, 0.0};
        }
    }

    /**
     * 高精度WGS84经纬度转换为WebMercator EPSG:3857坐标
     * @param lon WGS84经度
     * @param lat WGS84纬度
     * @return WebMercator坐标 [x, y]
     */
    public static double[] wgs84ToWebMercator(double lon, double lat) {
        // 输入坐标验证
        if (Double.isNaN(lon) || Double.isNaN(lat) || Double.isInfinite(lon) || Double.isInfinite(lat)) {
            logger.warning("WGS84输入坐标无效: lon=" + lon + ", lat=" + lat);
            return new double[]{0.0, 0.0};
        }
        if (lon < MIN_LON || lon > MAX_LON || lat < MIN_LAT || lat > MAX_LAT) {
            logger.warning("WGS84输入坐标超出范围: lon=" + lon + ", lat=" + lat);
            return new double[]{0.0, 0.0};
        }
        
        try {
            BigDecimal lonBD = new BigDecimal(lon);
            BigDecimal latBD = new BigDecimal(lat);
            
            // 经度转换：直接线性映射
            BigDecimal x = lonBD.multiply(WEB_MERCATOR_MAX).divide(new BigDecimal("180"), CALCULATION_PRECISION, ROUNDING_MODE);
            
            // 纬度转换：修正计算方式
            BigDecimal latRad = latBD.multiply(PI).divide(new BigDecimal("180"), CALCULATION_PRECISION, ROUNDING_MODE);
            BigDecimal sinLat = BigDecimal.valueOf(Math.sin(latRad.doubleValue()));
            BigDecimal y = BigDecimal.valueOf(Math.log((1.0 + sinLat.doubleValue()) / (1.0 - sinLat.doubleValue())))
                                   .multiply(WEB_MERCATOR_MAX)
                                   .divide(PI.multiply(new BigDecimal("2")), CALCULATION_PRECISION, ROUNDING_MODE);
            
            double[] result = new double[]{x.doubleValue(), y.doubleValue()};
            
            // 输出坐标验证
            if (validateWebMercatorInput(result[0], result[1])) {
                logger.fine("WGS84到WebMercator转换成功: (" + lon + ", " + lat + ") -> (" + result[0] + ", " + result[1] + ")");
                return result;
            } else {
                logger.warning("WGS84到WebMercator转换结果超出范围: (" + lon + ", " + lat + ") -> (" + result[0] + ", " + result[1] + ")");
                return new double[]{0.0, 0.0};
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "WGS84到WebMercator转换异常: lon=" + lon + ", lat=" + lat, e);
            return new double[]{0.0, 0.0};
        }
    }

    /**
     * 使用GeoTools进行精确的投影坐标转换
     * 从原始投影坐标转换为WGS84坐标
     * @param x 原始投影坐标X
     * @param y 原始投影坐标Y
     * @param shpFile shp文件，用于获取.prj文件信息
     * @return WGS84坐标 [经度, 纬度]
     */
    public static double[] convertProjectedToWgs84(double x, double y, File shpFile) {
        try {
            // 查找.prj文件
            File prjFile = ShpReader.findPrjFile(shpFile);
            if (prjFile == null) {
                throw new RuntimeException("未找到.prj文件，无法进行精确的坐标转换");
            }
            // 读取投影信息
            String wkt = new String(Files.readAllBytes(prjFile.toPath()), StandardCharsets.UTF_8);
            CoordinateReferenceSystem sourceCRS = CRS.parseWKT(wkt);
            CoordinateReferenceSystem targetCRS = CRS.decode("EPSG:4326"); // WGS84
            // 创建坐标转换
            MathTransform transform = CRS.findMathTransform(sourceCRS, targetCRS);
            // 执行转换
            double[] sourcePoint = {x, y};
            double[] targetPoint = new double[2];
            transform.transform(sourcePoint, 0, targetPoint, 0, 1);
            // GeoTools返回的是[纬度, 经度]顺序，需要调整为[经度, 纬度]
            double lat = targetPoint[0]; // 第一个值是纬度
            double lon = targetPoint[1]; // 第二个值是经度
            // 验证坐标范围
            if (lon < -180 || lon > 180 || lat < -90 || lat > 90) {
                logger.warning("坐标转换结果超出范围: [" + lon + ", " + lat + "]");
            }
            return new double[]{lon, lat};
        } catch (Exception e) {
            logger.log(Level.SEVERE, "坐标转换失败: " + e.getMessage(), e);
            throw new RuntimeException("坐标转换失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 使用GeoTools进行精确的坐标转换
     * 从WGS84坐标转换为原始投影坐标
     * @param lon WGS84经度
     * @param lat WGS84纬度
     * @param shpFile shp文件，用于获取.prj文件信息
     * @return 原始投影坐标 [x, y]
     */
    public static double[] convertWgs84ToProjected(double lon, double lat, File shpFile) {
        try {
            // 查找.prj文件
            File prjFile = ShpReader.findPrjFile(shpFile);
            if (prjFile == null) {
                throw new RuntimeException("未找到.prj文件，无法进行精确的坐标转换");
            }
            // 读取投影信息
            String wkt = new String(Files.readAllBytes(prjFile.toPath()), StandardCharsets.UTF_8);
            CoordinateReferenceSystem sourceCRS = CRS.decode("EPSG:4326"); // WGS84
            CoordinateReferenceSystem targetCRS = CRS.parseWKT(wkt);
            // 创建坐标转换
            MathTransform transform = CRS.findMathTransform(sourceCRS, targetCRS);
            // 执行转换
            // 注意：GeoTools期望的输入顺序是[纬度, 经度]，所以需要调整输入顺序
            double[] sourcePoint = {lat, lon}; // 调整为[纬度, 经度]顺序
            double[] targetPoint = new double[2];
            transform.transform(sourcePoint, 0, targetPoint, 0, 1);
            logger.fine("WGS84到投影坐标转换 - WGS84: [" + lon + ", " + lat + "] -> 投影坐标: [" + targetPoint[0] + ", " + targetPoint[1] + "]");
            return new double[]{targetPoint[0], targetPoint[1]};
        } catch (Exception e) {
            logger.log(Level.SEVERE, "坐标转换失败: " + e.getMessage(), e);
            throw new RuntimeException("坐标转换失败: " + e.getMessage(), e);
        }
    }

    /**
     * 验证WebMercator输入坐标
     */
    private static boolean validateWebMercatorInput(double x, double y) {
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isInfinite(x) || Double.isInfinite(y)) {
            return false;
        }
        if (x < MIN_WEB_MERCATOR_X || x > MAX_WEB_MERCATOR_X ||
            y < MIN_WEB_MERCATOR_Y || y > MAX_WEB_MERCATOR_Y) {
            return false;
        }
        return true;
    }

    /**
     * 验证WGS84输出坐标
     */
    private static boolean validateWGS84Output(double lon, double lat) {
        if (Double.isNaN(lon) || Double.isNaN(lat) || Double.isInfinite(lon) || Double.isInfinite(lat)) {
            return false;
        }
        if (lon < MIN_LON || lon > MAX_LON || lat < MIN_LAT || lat > MAX_LAT) {
            return false;
        }
        return true;
    }

}