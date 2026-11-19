package com.bdtdgis.service;

import com.bdtdgis.config.FileConfig;
import com.bdtdgis.model.Mesh;
import com.bdtdgis.model.PipeLine;
import com.bdtdgis.model.PipePoint;
import com.bdtdgis.model.Vertex;
import com.bdtdgis.util.*;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.data.simple.SimpleFeatureSource;
import org.opengis.feature.simple.SimpleFeature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

@Service
public class ModelService {

    @Value("${obj23dtilesPath}")
    private String obj23dtilesPath;
    @Autowired
    private FileConfig fileConfig;

    // 使用FileConfig获取路径
    private String getOutputDir() {
        return fileConfig.getOutputDir();
    }

    private String getUploadDir() {
        return fileConfig.getUploadDir();
    }

    // 添加文件路径保存
    private String lineShpPath = null;
    private String pointShpPath = null;

    // 递归查找shp文件，增强健壮性并加日志
    private void findShpFiles(File dir, List<File> shpFiles) {
        if (dir == null) {
            return;
        }
        if (!dir.exists()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                findShpFiles(f, shpFiles);
            } else if (f.getName().toLowerCase().endsWith(".shp")) {
                shpFiles.add(f);
            }
        }
    }

    // 通用的文件上传处理方法
    private Map<String, Object> handleFileUpload(MultipartFile file, String fileType) throws Exception {
        File tempDir;
        File shpFile;
        // 按类型分目录存放
        String subDir = "line".equals(fileType) ? "line" : "point";
        File uploadDir = new File(getUploadDir(), subDir);
        // 每次上传前清空对应目录
        if (uploadDir.exists()) {
            for (File f : uploadDir.listFiles()) {
                f.delete();
            }
        } else {
            uploadDir.mkdirs();
        }
        // 保存zip到分目录，使用原始文件名
        String originalFileName = file.getOriginalFilename();
        if (originalFileName == null || originalFileName.isEmpty()) {
            originalFileName = "upload_" + fileType + "_" + System.currentTimeMillis() + ".zip";
        }
        File tempZip = new File(uploadDir, originalFileName);
        file.transferTo(tempZip);
        tempDir = ZipUtil.unzipToUploadsDir(tempZip, uploadDir.getAbsolutePath());
        // 查找shp文件
        List<File> shpFiles = new ArrayList<>();
        findShpFiles(tempDir, shpFiles);
        if (shpFiles.isEmpty()) throw new RuntimeException("zip包中未找到shp文件");
        // 支持多图层
        List<String> layers = new ArrayList<>();
        for (File sf : shpFiles) {
            layers.add(sf.getName());
        }
        shpFile = shpFiles.get(0); // 默认取第一个
        // 检查编码信息
        String encoding = ShpReader.readCpgEncoding(shpFile);
        List<String> fields = ShpReader.readFields(shpFile);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("layers", layers);
        result.put("fields", fields);
        result.put("encoding", encoding);
        // 保存临时目录和shp文件引用
        if ("line".equals(fileType)) {
            // 保存管线文件在uploads/line中的路径
            lineShpPath = shpFile.getAbsolutePath();
        } else if ("point".equals(fileType)) {
            // 保存管点文件在uploads/point中的路径
            pointShpPath = shpFile.getAbsolutePath();
        }
        return result;
    }

    public Map<String, Object> handleUploadLine(MultipartFile file) throws Exception {
        return handleFileUpload(file, "line");
    }

    public Map<String, Object> handleUploadPoint(MultipartFile file) throws Exception {
        return handleFileUpload(file, "point");
    }

    /**
     * 处理附属物ZIP包上传
     * 路径：uploads/accessory/<attach>/<attachType>
     *
     * @param zipFile    上传的ZIP文件
     * @param attach     附属物名称（如：雨水井）
     * @param attachType 附属物类型（变体）
     * @return 处理结果
     */
    public Map<String, Object> handleAccessoryUpload(MultipartFile zipFile, String attach, String attachType) throws Exception {
        Map<String, Object> result = new HashMap<>();
        try {
            if (attach == null || attach.trim().isEmpty()) {
                throw new RuntimeException("attach 不能为空");
            }
            // attachType 允许为空：为空时作为默认模型
            String safeAttachType = (attachType == null) ? "" : attachType.trim();
            // 创建附属物模型目录：按 附属物/附属物类型 分层
            String accessoryDir = (safeAttachType.isEmpty()
                    ? (getUploadDir() + "/accessory/" + attach)
                    : (getUploadDir() + "/accessory/" + attach + "/" + safeAttachType));
            File dir = new File(accessoryDir);
            if (dir.exists()) {
                // 清空现有文件
                for (File f : dir.listFiles()) {
                    f.delete();
                }
            } else {
                dir.mkdirs();
            }
            // 保存ZIP文件
            File tempZip = new File(accessoryDir, zipFile.getOriginalFilename());
            zipFile.transferTo(tempZip);
            // 验证ZIP文件
            if (!tempZip.exists()) {
                throw new RuntimeException("ZIP文件保存失败");
            }
            // 解压ZIP文件
            File tempDir = ZipUtil.unzipToUploadsDir(tempZip, accessoryDir);
            // 查找OBJ文件
            List<File> objFiles = new ArrayList<>();
            findObjFiles(tempDir, objFiles);
            if (objFiles.isEmpty()) {
                throw new RuntimeException("ZIP包中未找到OBJ文件，请确保ZIP包包含obj文件");
            }
            // 列出解压后的所有文件
            listFilesRecursively(tempDir, "");
            // 复制所有文件到附属物目录
            copyDirectoryContents(tempDir, dir);
            // 记录成功信息
            result.put("success", true);
            result.put("message", "附属物模型包上传成功");
            result.put("attach", attach);
            result.put("attachType", safeAttachType);
            result.put("modelPath", accessoryDir);
            result.put("objFileCount", objFiles.size());
            // 清理临时文件
            tempZip.delete();
            deleteDirectory(tempDir);
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("message", "附属物模型包上传失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 处理特征参考模型ZIP包上传
     * 路径：uploads/feature/<field>/<value>/<variant>
     */
    public Map<String, Object> handleFeatureUpload(MultipartFile zipFile, String field, String value, String variant) throws Exception {
        Map<String, Object> result = new HashMap<>();
        try {
            if (field == null || field.trim().isEmpty() || value == null || value.trim().isEmpty()) {
                throw new RuntimeException("field/value 不能为空");
            }

            // 构建路径，如果有变体则包含变体目录
            String base;
            if (variant != null && !variant.trim().isEmpty()) {
                base = getUploadDir() + "/feature/" + field + "/" + value + "/" + variant.trim();
            } else {
                base = getUploadDir() + "/feature/" + field + "/" + value;
            }

            File dir = new File(base);
            if (dir.exists()) {
                for (File f : dir.listFiles()) {
                    deleteDirectory(f);
                }
            } else {
                dir.mkdirs();
            }
            String original = zipFile.getOriginalFilename();
            if (original == null || !original.toLowerCase().endsWith(".zip")) {
                throw new RuntimeException("仅支持ZIP文件");
            }
            File tempZip = new File(dir, original);
            zipFile.transferTo(tempZip);
            if (!tempZip.exists()) throw new RuntimeException("ZIP文件保存失败");
            File tempDir = ZipUtil.unzipToUploadsDir(tempZip, dir.getAbsolutePath());
            List<File> objFiles = new ArrayList<>();
            findObjFiles(tempDir, objFiles);
            if (objFiles.isEmpty()) throw new RuntimeException("ZIP包中未找到OBJ文件");
            copyDirectoryContents(tempDir, dir);
            result.put("success", true);
            result.put("message", "特征模型包上传成功");
            result.put("field", field);
            result.put("value", value);
            result.put("variant", variant);
            result.put("modelPath", dir.getAbsolutePath());
            result.put("objFileCount", objFiles.size());
            tempZip.delete();
            deleteDirectory(tempDir);
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("message", "特征模型包上传失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 递归查找OBJ文件
     */
    private void findObjFiles(File dir, List<File> objFiles) {
        if (dir == null || !dir.exists()) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                findObjFiles(f, objFiles);
            } else if (f.getName().toLowerCase().endsWith(".obj")) {
                objFiles.add(f);
            }
        }
    }

    /**
     * 复制目录内容
     */
    private void copyDirectoryContents(File source, File target) throws Exception {
        if (source.isFile()) {
            Files.copy(source.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } else if (source.isDirectory()) {
            if (!target.exists()) {
                target.mkdirs();
            }

            File[] files = source.listFiles();
            if (files != null) {
                for (File file : files) {
                    File targetFile = new File(target, file.getName());
                    copyDirectoryContents(file, targetFile);
                }
            }
        }
    }

    /**
     * 删除目录
     */
    private void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) return;

        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteDirectory(file);
                }
            }
        }
        dir.delete();
    }

    /**
     * 主建模流程
     *
     * @param config 前端传入的建模参数和字段映射
     * @return 结果Map，包含输出OBJ、batch_table.json路径等
     * @throws Exception 读取或导出异常
     */
    public Map<String, Object> generateModel(Map<String, Object> config) throws Exception {
        try {
            // 数据准备
            List<PipeLine> lines = readPipeLines(config);
            List<PipePoint> points = readPipePoints(config);
            if (lines.isEmpty() || points.isEmpty()) {
                throw new RuntimeException("未找到管线、管点数据，请重新上传管线、管点shape文件后重试！");
            }
            Map<String, Object> modelSettings = (Map<String, Object>) config.get("modelSettings");
            // 获取gridSize参数（网格分片大小，单位：km）
            Double gridSize = null;
            Object gridSizeObj = modelSettings.get("gridSize");
            if (gridSizeObj != null) {
                if (gridSizeObj instanceof Double) {
                    gridSize = (Double) gridSizeObj;
                } else if (gridSizeObj instanceof Integer) {
                    gridSize = ((Integer) gridSizeObj).doubleValue();
                } else if (gridSizeObj instanceof String) {
                    try {
                        gridSize = Double.parseDouble((String) gridSizeObj);
                    } catch (NumberFormatException e) {
                        System.err.println("警告：无法解析gridSize字符串值: " + gridSizeObj + "，使用默认值");
                    }
                }
            }
            if (gridSize == null) {
                gridSize = 0.25; // 默认0.25km
            }
            // 获取shp文件用于坐标转换
            File lineShpFile = findLineShpFile();
            File pointShpFile = findPointShpFile();
            File shpFile = lineShpFile != null ? lineShpFile : pointShpFile;
            if (shpFile == null) {
                throw new RuntimeException("未找到shp文件，无法进行坐标转换");
            }
            // 使用CustomSpatialTilesUtil进行基于gridSize的空间分割
            List<CustomSpatialTilesUtil.BoundingRegion> regions =
                    CustomSpatialTilesUtil.performGridSizeBasedSplitting(lines, points, gridSize, shpFile);
            // 分片生成与导出（对管线、管点进行分片）
            String outputDir = getOutputDir();
            String lineTilesetDir = outputDir + "/line";
            String pointTilesetDir = outputDir + "/point";
            // 1) 为每个分片生成管线mesh
            Map<CustomSpatialTilesUtil.BoundingRegion, List<Mesh>> lineRegionMeshes =
                    CustomSpatialTilesUtil.assignLineMeshesToRegions(lines, regions, modelSettings, points);
            List<String> lineTileUrls = new ArrayList<>();
            List<CustomSpatialTilesUtil.BoundingRegion> lineRegions = new ArrayList<>();
            for (CustomSpatialTilesUtil.BoundingRegion region : regions) {
                List<Mesh> meshes = lineRegionMeshes.get(region);
                if (meshes == null || meshes.isEmpty()) continue;
                // 使用分片中心进行去中心化与transform
                double tileCenterZ = (region.minZ + region.maxZ) / 2.0;
                double[] projectedTileCenter = new double[]{region.centerProjected[0], region.centerProjected[1],
                        tileCenterZ};
                double[] wgs84TileCenter = new double[]{Math.toDegrees(region.centerWgs84Rad[0]),
                        Math.toDegrees(region.centerWgs84Rad[1]), tileCenterZ};
                String tileRelativeUrl = exportTile(meshes, region, lineTilesetDir, region.tileIndex,
                        projectedTileCenter, wgs84TileCenter, "line", shpFile);
                if (tileRelativeUrl != null) {
                    lineTileUrls.add(tileRelativeUrl);
                    lineRegions.add(region);
                }
            }
            if (!lineTileUrls.isEmpty()) {
                TilesetUtil.generateRootTilesetJson(new File(lineTilesetDir), lineTileUrls, lineRegions, shpFile);
            }
            // 2) 为每个分片生成管点mesh
            Map<CustomSpatialTilesUtil.BoundingRegion, List<Mesh>> pointRegionMeshes =
                    CustomSpatialTilesUtil.assignPointMeshesToRegions(points, lines, regions, modelSettings);
            List<String> pointTileUrls = new ArrayList<>();
            List<CustomSpatialTilesUtil.BoundingRegion> pointRegions = new ArrayList<>();
            for (CustomSpatialTilesUtil.BoundingRegion region : regions) {
                List<Mesh> meshes = pointRegionMeshes.get(region);
                if (meshes == null || meshes.isEmpty()) continue;
                // 使用分片中心进行去中心化与transform
                double tileCenterZ = (region.minZ + region.maxZ) / 2.0;
                double[] projectedTileCenter = new double[]{region.centerProjected[0], region.centerProjected[1],
                        tileCenterZ};
                double[] wgs84TileCenter = new double[]{Math.toDegrees(region.centerWgs84Rad[0]),
                        Math.toDegrees(region.centerWgs84Rad[1]), tileCenterZ};
                String tileRelativeUrl = exportTile(meshes, region, pointTilesetDir, region.tileIndex,
                        projectedTileCenter, wgs84TileCenter, "point", shpFile);
                if (tileRelativeUrl != null) {
                    pointTileUrls.add(tileRelativeUrl);
                    pointRegions.add(region);
                }
            }
            if (!pointTileUrls.isEmpty()) {
                TilesetUtil.generateRootTilesetJson(new File(pointTilesetDir), pointTileUrls, pointRegions, shpFile);
            }
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("lineTileCount", lineTileUrls.size());
            result.put("pointTileCount", pointTileUrls.size());
            return result;
        } catch (Exception e) {
            throw e;
        }
    }


    /**
     * 读取管线shp文件，生成PipeLine列表
     *
     * @param config 配置参数，包含字段映射
     * @return 管线列表
     * @throws Exception 读取异常
     */
    private List<PipeLine> readPipeLines(Map<String, Object> config) throws Exception {
        File lineShp = findLineShpFile();
        if (lineShp == null) return new ArrayList<>();
        Map<String, String> lineFieldMap = (Map<String, String>) config.get("lineFields");
        return ShpReader.readLinesWithAttr(lineShp, lineFieldMap);
    }

    /**
     * 读取管点shp文件，生成PipePoint列表
     *
     * @param config 配置参数，包含字段映射
     * @return 管点列表
     * @throws Exception 读取异常
     */
    private List<PipePoint> readPipePoints(Map<String, Object> config) throws Exception {
        File pointShp = findPointShpFile();
        if (pointShp == null) return new ArrayList<>();
        Map<String, String> pointFieldMap = (Map<String, String>) config.get("pointFields");
        return ShpReader.readPointsWithAttr(pointShp, pointFieldMap);
    }

    /**
     * 导出单个tile的OBJ、B3DM、tileset.json等
     *
     * @param meshes          原始mesh列表
     * @param region          tile的空间范围
     * @param outputDir       输出目录
     * @param tileNum         tile编号
     * @param projectedCenter 投影坐标中心点，用于mesh去中心化
     * @param wgs84Center     WGS84坐标中心点，用于生成tileset.json的transform
     * @param dataType        数据类型
     * @param shpFile         shp文件，用于坐标转换
     * @return tileset.json的相对路径
     */
    private String exportTile(List<Mesh> meshes, CustomSpatialTilesUtil.BoundingRegion region, String outputDir,
                              int tileNum, double[] projectedCenter, double[] wgs84Center, String dataType,
                              File shpFile) {
        try {
            String tileSubDir = tileNum + "/3dtiles_output";
            File file = new File(outputDir, tileSubDir);
            if (!file.exists()) {
                file.mkdirs();
            }
            String objPath = file.getAbsolutePath() + "/grid_0_0.obj";
            // 所有mesh都需要去中心化处理，保持坐标系统一
            List<Mesh> processedMeshes = centerMeshes(meshes, projectedCenter);
            // 导出处理后的OBJ文件
            ObjExporter.exportToOBJ(processedMeshes, objPath, dataType);
            String batchTablePath = objPath.substring(0, objPath.lastIndexOf('.')) + "_batchTable.json";
            // 修复BatchTable生成逻辑，使用原始数据属性
            List<String> attrFields = new ArrayList<>();
            List<Map<String, Object>> attributes = new ArrayList<>();
            if (processedMeshes != null && !processedMeshes.isEmpty()) {
                // 收集所有mesh的原始数据属性字段
                Set<String> allAttrFields = new HashSet<>();
                for (Mesh mesh : processedMeshes) {
                    if (mesh.originalAttributes != null) {
                        allAttrFields.addAll(mesh.originalAttributes.keySet());
                    }
                }
                attrFields.addAll(allAttrFields);
                // 为每个mesh生成属性，确保所有mesh都有相同的属性字段
                for (int i = 0; i < processedMeshes.size(); i++) {
                    Mesh mesh = processedMeshes.get(i);
                    Map<String, Object> meshAttributes = new HashMap<>();
                    // 只添加原始数据属性，不添加任何系统属性
                    for (String field : attrFields) {
                        if (mesh.originalAttributes != null && mesh.originalAttributes.containsKey(field)) {
                            meshAttributes.put(field, mesh.originalAttributes.get(field));
                        } else {
                            // 为缺失的属性添加默认值
                            meshAttributes.put(field, "");
                        }
                    }
                    attributes.add(meshAttributes);
                }
            }
            BatchTableUtil.generateBatchTable(new File(batchTablePath), attributes);
            // 生成 b3dm/tileset.json
            // OBJ -> b3dm，使用obj23dtiles工具
            String b3dmPath = objPath.replace(".obj", ".b3dm");
            String tilesetPath = file.getAbsolutePath() + "/tileset.json";
            // 直接使用obj23dtiles的完整路径
            String obj2b3dmCmd = obj23dtilesPath + " -i " + objPath + " -o " + b3dmPath + " --b3dm --customBatchTable" +
                    " " + batchTablePath + " --binary --outputBatchTable";
            NodeUtil.runExternalCommand(obj2b3dmCmd);
            // 使用TilesetUtil生成tileset.json文件，传递WGS84中心点用于transform计算
            String tilesetFileName = "tileset.json";
            File tilesetFile = new File(file, tilesetFileName);
            TilesetUtil.generateSingleTilesetJson(tilesetFile, region, wgs84Center, new File(b3dmPath).getName(),
                    shpFile);
            return tilesetPath;
        } catch (Exception e) {
            System.err.println("分片导出异常: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    // 1. findLineShpFile/findPointShpFile 方法补充
    private File findLineShpFile() {
        return lineShpPath != null ? new File(lineShpPath) : null;
    }

    private File findPointShpFile() {
        return pointShpPath != null ? new File(pointShpPath) : null;
    }

    // 修改：直接修改原mesh顶点坐标，返回原mesh列表
    private List<Mesh> centerMeshes(List<Mesh> meshes, double[] center) {
        for (Mesh mesh : meshes) {
            // 直接修改原mesh的顶点坐标
            for (Vertex vertex : mesh.vertices) {
                vertex.x -= center[0];
                vertex.y -= center[1];
                vertex.z -= center[2];
            }
        }
        return meshes; // 返回原mesh列表
    }


    /**
     * 递归列出目录中的所有文件
     */
    private void listFilesRecursively(File dir, String indent) {
        if (dir == null || !dir.exists()) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                listFilesRecursively(file, indent + "  ");
            }
        }
    }

    public List<String> getDistinctPointAttributeValues(String attributeName) throws Exception {
        File pointShpFile = findPointShpFile();
        if (pointShpFile == null || !pointShpFile.exists()) {
            return Collections.emptyList();
        }
        ShapefileDataStore store = ShpReader.getOrCreateShapefileDataStore(pointShpFile);
        SimpleFeatureSource featureSource = store.getFeatureSource();
        SimpleFeatureCollection collection = featureSource.getFeatures();
        Set<String> values = new HashSet<>();
        try (SimpleFeatureIterator it = collection.features()) {
            while (it.hasNext()) {
                SimpleFeature feature = it.next();
                Object val = feature.getAttribute(attributeName);
                if (val != null) {
                    String s = val.toString().trim();
                    if (!s.isEmpty()) values.add(s);
                }
            }
        }
        List<String> list = new ArrayList<>(values);
        Collections.sort(list);
        return list;
    }

    public List<String> getDistinctPointAttributeValuesBy(String attributeName, String byField, String byValue) throws Exception {
        File pointShpFile = findPointShpFile();
        if (pointShpFile == null || !pointShpFile.exists()) {
            return Collections.emptyList();
        }
        ShapefileDataStore store = ShpReader.getOrCreateShapefileDataStore(pointShpFile);
        SimpleFeatureSource featureSource = store.getFeatureSource();
        SimpleFeatureCollection collection = featureSource.getFeatures();
        Set<String> values = new HashSet<>();
        try (SimpleFeatureIterator it = collection.features()) {
            while (it.hasNext()) {
                SimpleFeature feature = it.next();
                Object by = feature.getAttribute(byField);
                if (by == null) continue;
                if (!by.toString().trim().equals(byValue)) continue;
                Object val = feature.getAttribute(attributeName);
                if (val != null) {
                    String s = val.toString().trim();
                    if (!s.isEmpty()) values.add(s);
                }
            }
        }
        List<String> list = new ArrayList<>(values);
        Collections.sort(list);
        return list;
    }

}
