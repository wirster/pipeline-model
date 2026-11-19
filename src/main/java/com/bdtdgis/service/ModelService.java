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

    @Value("${dracoToolPath:gltf-pipeline}")
    private String dracoToolPath;

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
        // 每次上传前清空对应目录（含子目录，避免残留上一次的图层）
        if (uploadDir.exists()) {
            File[] oldFiles = uploadDir.listFiles();
            if (oldFiles != null) {
                for (File f : oldFiles) {
                    deleteDirectory(f);
                }
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
        // 可选图层：返回相对上传目录的路径，与建模时 lineLayer/pointLayer 的取值完全一致
        List<String> layers = listShpLayers(uploadDir);
        if (layers.isEmpty()) throw new RuntimeException("zip包中未找到shp文件");
        String defaultLayer = layers.get(0);
        shpFile = new File(uploadDir, defaultLayer);
        // 检查编码信息
        String encoding = ShpReader.readCpgEncoding(shpFile);
        List<String> fields = ShpReader.readFields(shpFile);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("layers", layers);
        result.put("defaultLayer", defaultLayer);
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
     * @return 结果Map，包含输出OBJ、批表文件路径等
     * @throws Exception 读取或导出异常
     */
    public Map<String, Object> generateModel(Map<String, Object> config) throws Exception {
        try {
            // 数据准备：按前端传入的图层名定位压缩包内的 .shp，未传或空白时回退到第一个 .shp
            File lineShpFile = resolveLayerShpFile("line", asString(config.get("lineLayer")));
            File pointShpFile = resolveLayerShpFile("point", asString(config.get("pointLayer")));
            List<PipeLine> lines = readPipeLines(config, lineShpFile);
            List<PipePoint> points = readPipePoints(config, pointShpFile);
            if (lines.isEmpty() || points.isEmpty()) {
                throw new RuntimeException("未找到管线、管点数据，请重新上传管线、管点shape文件后重试！");
            }
            Map<String, Object> modelSettings = (Map<String, Object>) config.get("modelSettings");
            boolean dracoRequested = isTrue(modelSettings.get("enableDraco"));
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
            // 获取shp文件用于坐标转换（与图层选择一致）
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

            // 前置校验：obj23dtiles 不可用时直接返回明确的失败结果，避免产出不可用的半成品
            if (!NodeUtil.isObj23dtilesAvailable(obj23dtilesPath)) {
                Map<String, Object> missingResult = buildToolMissingResult();
                missingResult.put("draco", buildDracoInfo(dracoRequested, 0, 0,
                        "未执行：obj23dtiles 不可用，未能生成 b3dm。"));
                return missingResult;
            }
            // 前置校验：请求 Draco 时，压缩工具不可用直接明确失败，不产出未压缩的“假已压缩”瓦片
            if (dracoRequested && !B3dmDracoCompressor.isToolAvailable(dracoToolPath)) {
                Map<String, Object> missingResult = buildDracoToolMissingResult();
                missingResult.put("draco", buildDracoInfo(true, 0, 0,
                        "Draco 压缩未执行：未找到 gltf-pipeline（当前配置 dracoToolPath: " + dracoToolPath + "）。"));
                return missingResult;
            }

            List<Map<String, Object>> failures = new ArrayList<>();
            int dracoCompressedTiles = 0;
            int dracoFailedTiles = 0;
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
                TileExportResult exportResult = exportTile(meshes, region, lineTilesetDir, region.tileIndex,
                        projectedTileCenter, wgs84TileCenter, "line", shpFile, dracoRequested);
                if (exportResult.success) {
                    lineTileUrls.add(exportResult.tilesetPath);
                    lineRegions.add(region);
                    if (exportResult.dracoCompressed) {
                        dracoCompressedTiles++;
                    }
                } else {
                    failures.add(buildTileFailure("line", region.tileIndex, exportResult));
                    if (isDracoFailure(exportResult)) {
                        dracoFailedTiles++;
                    }
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
                TileExportResult exportResult = exportTile(meshes, region, pointTilesetDir, region.tileIndex,
                        projectedTileCenter, wgs84TileCenter, "point", shpFile, dracoRequested);
                if (exportResult.success) {
                    pointTileUrls.add(exportResult.tilesetPath);
                    pointRegions.add(region);
                    if (exportResult.dracoCompressed) {
                        dracoCompressedTiles++;
                    }
                } else {
                    failures.add(buildTileFailure("point", region.tileIndex, exportResult));
                    if (isDracoFailure(exportResult)) {
                        dracoFailedTiles++;
                    }
                }
            }
            if (!pointTileUrls.isEmpty()) {
                TilesetUtil.generateRootTilesetJson(new File(pointTilesetDir), pointTileUrls, pointRegions, shpFile);
            }

            int lineCount = lineTileUrls.size();
            int pointCount = pointTileUrls.size();
            Map<String, Object> result = new HashMap<>();
            result.put("lineTileCount", lineCount);
            result.put("pointTileCount", pointCount);
            result.put("failedTileCount", failures.size());
            result.put("failures", failures);
            result.put("draco", buildDracoInfo(dracoRequested, dracoCompressedTiles, dracoFailedTiles, null));
            if (!failures.isEmpty()) {
                boolean allToolMissing = failures.stream()
                        .allMatch(f -> "TOOL_MISSING".equals(f.get("errorType"))
                                || "DRACO_TOOL_MISSING".equals(f.get("errorType")));
                result.put("success", false);
                result.put("errorType", allToolMissing ? "TOOL_MISSING" : "TILE_EXPORT_FAILED");
                result.put("message", buildFailureMessage(failures, lineCount, pointCount));
            } else if (lineCount == 0 && pointCount == 0) {
                result.put("success", false);
                result.put("errorType", "NO_TILE");
                result.put("message", "未生成任何3D Tiles分片，请检查管线/管点数据范围与字段映射");
            } else {
                result.put("success", true);
                result.put("message", "建模完成");
            }
            return result;
        } catch (Exception e) {
            throw e;
        }
    }

    /**
     * 构造外部工具缺失的失败结果
     */
    private Map<String, Object> buildToolMissingResult() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("lineTileCount", 0);
        result.put("pointTileCount", 0);
        result.put("failedTileCount", 0);
        result.put("failures", Collections.emptyList());
        result.put("errorType", "TOOL_MISSING");
        result.put("message", "未找到 obj23dtiles 工具（当前配置: " + obj23dtilesPath
                + "）。请安装 obj23dtiles，或将 application.yml 中的 obj23dtilesPath 指向可执行的 obj23dtiles(.cmd) 后重试；本次未生成任何3D Tiles瓦片。");
        return result;
    }

    /**
     * 构造 Draco 压缩工具缺失的失败结果（enableDraco=true 且工具不可用）
     */
    private Map<String, Object> buildDracoToolMissingResult() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("lineTileCount", 0);
        result.put("pointTileCount", 0);
        result.put("failedTileCount", 0);
        result.put("failures", Collections.emptyList());
        result.put("errorType", "TOOL_MISSING");
        result.put("message", "未找到 Draco 压缩工具 gltf-pipeline（当前配置 dracoToolPath: " + dracoToolPath
                + "）。请执行 npm install -g gltf-pipeline，或将 application.yml 中的 dracoToolPath 指向 gltf-pipeline.cmd；"
                + "本次未生成任何3D Tiles瓦片，也未降级为未压缩输出。");
        return result;
    }

    /**
     * 构造单个分片的失败信息
     */
    private Map<String, Object> buildTileFailure(String dataType, int tileIndex, TileExportResult result) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("dataType", dataType);
        failure.put("tileIndex", tileIndex);
        failure.put("stage", result.stage);
        failure.put("errorType", result.errorType);
        failure.put("reason", result.reason);
        return failure;
    }

    /**
     * 汇总失败信息（成功/失败分片数 + 首个失败原因）
     */
    private String buildFailureMessage(List<Map<String, Object>> failures, int lineCount, int pointCount) {
        String firstReason = failures.isEmpty() ? "未知原因" : String.valueOf(failures.get(0).get("reason"));
        String prefix = lineCount + pointCount > 0 ? "建模部分失败" : "建模失败";
        return String.format("%s：成功分片 管线%d / 管点%d，失败分片 %d。首个失败原因：%s",
                prefix, lineCount, pointCount, failures.size(), firstReason);
    }


    /**
     * 读取管线shp文件，生成PipeLine列表
     *
     * @param config   配置参数，包含字段映射
     * @param lineShp  已定位的管线shp文件
     * @return 管线列表
     * @throws Exception 读取异常
     */
    private List<PipeLine> readPipeLines(Map<String, Object> config, File lineShp) throws Exception {
        if (lineShp == null) return new ArrayList<>();
        Map<String, String> lineFieldMap = (Map<String, String>) config.get("lineFields");
        return ShpReader.readLinesWithAttr(lineShp, lineFieldMap);
    }

    /**
     * 读取管点shp文件，生成PipePoint列表
     *
     * @param config     配置参数，包含字段映射
     * @param pointShp   已定位的管点shp文件
     * @return 管点列表
     * @throws Exception 读取异常
     */
    private List<PipePoint> readPipePoints(Map<String, Object> config, File pointShp) throws Exception {
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
     * @param dracoRequested  是否对生成的 b3dm 做 Draco 后处理压缩
     * @return 导出结果（成功时包含tileset.json路径，失败时包含失败阶段、类型与原因）
     */
    private TileExportResult exportTile(List<Mesh> meshes, CustomSpatialTilesUtil.BoundingRegion region, String outputDir,
                              int tileNum, double[] projectedCenter, double[] wgs84Center, String dataType,
                              File shpFile, boolean dracoRequested) {
        String stage = "obj";
        try {
            String tileSubDir = tileNum + "/3dtiles_output";
            File file = new File(outputDir, tileSubDir);
            if (!file.exists() && !file.mkdirs()) {
                throw new IllegalStateException("无法创建分片输出目录: " + file.getAbsolutePath());
            }
            String objPath = file.getAbsolutePath() + "/grid_0_0.obj";
            // 所有mesh都需要去中心化处理，保持坐标系统一
            List<Mesh> processedMeshes = centerMeshes(meshes, projectedCenter);
            // 导出处理后的OBJ文件
            ObjExporter.exportToOBJ(processedMeshes, objPath, dataType);
            String batchTablePath = objPath.substring(0, objPath.lastIndexOf('.')) + "_batchTable.json";
            // 修复BatchTable生成逻辑，使用原始数据属性
            stage = "batchTable";
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
            stage = "b3dm";
            NodeUtil.runExternalCommand(obj2b3dmCmd);
            File b3dmFile = new File(b3dmPath);
            if (!b3dmFile.isFile() || b3dmFile.length() == 0) {
                throw new IllegalStateException("obj23dtiles 执行结束但未生成有效 b3dm 文件: " + b3dmPath);
            }
            // Draco 后处理：拆出 b3dm 内嵌 GLB -> gltf-pipeline 压缩 -> 保留 batchTable 回包
            boolean dracoCompressed = false;
            if (dracoRequested) {
                stage = "draco";
                B3dmDracoCompressor.compress(b3dmFile, dracoToolPath);
                dracoCompressed = true;
            }
            // 使用TilesetUtil生成tileset.json文件，传递WGS84中心点用于transform计算
            stage = "tileset";
            File tilesetFile = new File(file, "tileset.json");
            TilesetUtil.generateSingleTilesetJson(tilesetFile, region, wgs84Center, b3dmFile.getName(),
                    shpFile);
            if (!tilesetFile.isFile()) {
                throw new IllegalStateException("tileset.json 未生成: " + tilesetFile.getAbsolutePath());
            }
            return TileExportResult.ok(tilesetPath, dracoCompressed);
        } catch (Exception e) {
            String reason = (e.getMessage() == null || e.getMessage().trim().isEmpty())
                    ? e.getClass().getSimpleName() : e.getMessage().trim();
            System.err.println("分片导出失败 [" + dataType + "#" + tileNum + " @ " + stage + "]: " + reason);
            return TileExportResult.fail(stage, classifyExportError(stage, reason), reason);
        }
    }

    /**
     * 判断分片失败是否由 Draco 压缩链路导致
     */
    private static boolean isDracoFailure(TileExportResult result) {
        return "DRACO_FAILED".equals(result.errorType) || "DRACO_TOOL_MISSING".equals(result.errorType);
    }

    /**
     * 判断导出失败的类型（外部工具不可用 / Draco 压缩失败 / 其他）
     */
    private static String classifyExportError(String stage, String reason) {
        if ("draco".equals(stage)) {
            if (reason != null && reason.contains("工具不可用")) {
                return "DRACO_TOOL_MISSING";
            }
            return "DRACO_FAILED";
        }
        if (reason == null) {
            return "TILE_EXPORT_FAILED";
        }
        String lower = reason.toLowerCase(Locale.ROOT);
        if (reason.contains("外部工具不可用") || reason.contains("不是内部或外部命令")
                || reason.contains("系统找不到指定的路径")
                || lower.contains("is not recognized")
                || lower.contains("the system cannot find the path specified")
                || lower.contains("no such file or directory")) {
            return "TOOL_MISSING";
        }
        return "TILE_EXPORT_FAILED";
    }

    /**
     * 分片导出结果
     */
    private static class TileExportResult {
        final boolean success;
        final String tilesetPath;
        final String stage;
        final String errorType;
        final String reason;
        final boolean dracoCompressed;

        private TileExportResult(boolean success, String tilesetPath, String stage, String errorType, String reason,
                                 boolean dracoCompressed) {
            this.success = success;
            this.tilesetPath = tilesetPath;
            this.stage = stage;
            this.errorType = errorType;
            this.reason = reason;
            this.dracoCompressed = dracoCompressed;
        }

        static TileExportResult ok(String tilesetPath, boolean dracoCompressed) {
            return new TileExportResult(true, tilesetPath, null, null, null, dracoCompressed);
        }

        static TileExportResult fail(String stage, String errorType, String reason) {
            return new TileExportResult(false, null, stage, errorType, reason, false);
        }
    }

    /**
     * 供点属性查询接口使用的管点shp定位（未指定图层时取第一个）
     */
    private File findPointShpFile() {
        if (pointShpPath != null) {
            File file = new File(pointShpPath);
            if (file.isFile()) return file;
        }
        try {
            return resolveLayerShpFile("point", null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 列出上传目录下所有可选 .shp 图层的相对路径（统一使用 '/'，并按路径排序）
     */
    private List<String> listShpLayers(File baseDir) {
        List<File> shpFiles = new ArrayList<>();
        findShpFiles(baseDir, shpFiles);
        List<String> layers = new ArrayList<>();
        for (File shp : shpFiles) {
            layers.add(relativePath(baseDir, shp));
        }
        Collections.sort(layers, String.CASE_INSENSITIVE_ORDER);
        return layers;
    }

    /**
     * 计算文件相对基准目录的路径，统一使用 '/' 分隔
     */
    private String relativePath(File baseDir, File file) {
        try {
            return baseDir.toPath().toAbsolutePath().normalize()
                    .relativize(file.toPath().toAbsolutePath().normalize())
                    .toString().replace('\\', '/');
        } catch (Exception e) {
            return file.getName();
        }
    }

    /**
     * 按前端传入的图层名定位 .shp 文件
     * <p>
     * 图层名为上传接口返回的相对路径（也兼容仅文件名）；未传或空白时回退到包内第一个 .shp。
     * 传入了图层名但找不到（或仅文件名匹配到多个）时抛出明确错误。
     */
    private File resolveLayerShpFile(String fileType, String layerName) {
        String typeLabel = "line".equals(fileType) ? "管线" : "管点";
        File baseDir = new File(getUploadDir(), fileType);
        List<File> shpFiles = new ArrayList<>();
        findShpFiles(baseDir, shpFiles);
        shpFiles.sort(Comparator.comparing(f -> relativePath(baseDir, f).toLowerCase(Locale.ROOT)));

        if (layerName == null || layerName.trim().isEmpty()) {
            if (shpFiles.isEmpty()) {
                throw new RuntimeException("未找到" + typeLabel + "shp文件，请先上传" + typeLabel + "压缩包");
            }
            return shpFiles.get(0);
        }

        String target = layerName.trim().replace('\\', '/');
        for (File shp : shpFiles) {
            if (relativePath(baseDir, shp).equalsIgnoreCase(target)) {
                return shp;
            }
        }

        List<File> byName = new ArrayList<>();
        for (File shp : shpFiles) {
            if (shp.getName().equalsIgnoreCase(target)) {
                byName.add(shp);
            }
        }
        if (byName.size() == 1) {
            return byName.get(0);
        }

        List<String> available = new ArrayList<>();
        for (File shp : shpFiles) {
            available.add(relativePath(baseDir, shp));
        }
        String reason = byName.size() > 1
                ? "仅按文件名匹配到多个同名图层，请改用完整相对路径"
                : "压缩包内不存在该图层";
        throw new RuntimeException("无法定位" + typeLabel + "图层 \"" + layerName + "\"：" + reason
                + "。可选图层: " + (available.isEmpty() ? "无" : String.join(", ", available)));
    }

    /**
     * 读取字符串属性
     */
    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 布尔值解析（兼容 Boolean / "true" / "1"）
     */
    private static boolean isTrue(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(text) || "1".equals(text);
    }

    /**
     * Draco 压缩的真实执行结果
     * <p>
     * enableDraco=true 时对每个分片的 b3dm 调用 gltf-pipeline 做 Draco 压缩并回包，
     * 这里的 applied 只有在“请求压缩、且所有产出分片都压缩成功”时才为 true；
     * 工具缺失或压缩失败会同时体现在失败分片与 reason 中，不会伪报“已压缩”。
     *
     * @param requested       前端是否勾选 enableDraco
     * @param compressedCount 成功压缩的分片数
     * @param failedCount     Draco 压缩失败的分片数
     * @param reasonWhenIdle  未压缩/未执行时的说明（可为 null）
     */
    private Map<String, Object> buildDracoInfo(boolean requested, int compressedCount, int failedCount,
                                               String reasonWhenIdle) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("requested", requested);
        boolean applied = requested && failedCount == 0 && compressedCount > 0;
        info.put("applied", applied);
        if (!requested) {
            return info;
        }
        info.put("tool", "gltf-pipeline");
        info.put("toolPath", dracoToolPath);
        info.put("compressedTileCount", compressedCount);
        info.put("failedTileCount", failedCount);
        if (applied) {
            info.put("reason", "已对 " + compressedCount + " 个分片的 b3dm 执行 Draco 压缩（"
                    + B3dmDracoCompressor.DRACO_EXTENSION + "），batchTable 与要素数量保持不变。");
        } else if (failedCount > 0) {
            info.put("reason", "有 " + failedCount + " 个分片的 Draco 压缩失败"
                    + (compressedCount > 0 ? "（成功 " + compressedCount + " 个）" : "")
                    + "，失败详情见 failures。");
            System.err.println("[警告] enableDraco=true，但有 " + failedCount + " 个分片 Draco 压缩失败");
        } else {
            info.put("reason", reasonWhenIdle != null ? reasonWhenIdle : "未执行 Draco 压缩（没有产出分片）");
        }
        return info;
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
