package com.bdtdgis.util;

import com.bdtdgis.model.Face;
import com.bdtdgis.model.Mesh;
import com.bdtdgis.model.Vertex;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.net.URL;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/**
 * OBJ 导出工具（重写版）
 * 目标：
 * 1) 正确导出"多个 Mesh 到同一个 OBJ 文件"，确保索引连续、分组清晰、无二次变换。
 * 2) 写 MTL 材质文件：
 *    - 管线颜色：使用建模参数中的方案颜色（通过 mesh.attributes 中的 pipeColor 或 color 传入）；
 *    - 管点：如果没有贴图，也使用与管线一致的颜色；如果有贴图，则为贴图生成对应材质。
 * 3) 健壮处理：当无法线或无 UV 时，自动降级写法，避免无效索引。
 */
public class ObjExporter {

    /**
     * 导出 OBJ 文件（传入的 meshes 坐标已由上游中心化/定位完成，这里不做任何几何变换）。
     *
     * @param meshes   Mesh 列表（同一 tile 内多个对象/子对象）
     * @param filePath 输出 OBJ 文件路径
     * @param dataType 数据类型："line"/"point"
     */
    public static void exportToOBJ(List<Mesh> meshes, String filePath, String dataType) throws IOException {
        if (meshes == null || meshes.isEmpty()) {
            return;
        }
        File outFile = new File(filePath);
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        // 生成并写入对应的 MTL 文件
        String mtlFileName = outFile.getName().replace(".obj", ".mtl");
        String mtlFilePath = new File(outFile.getParentFile(), mtlFileName).getAbsolutePath();
        // 检查是否有管点mesh携带原始MTL
        boolean reuseSourceMtl = false;
        File sourceMtl = null;
        if ("point".equalsIgnoreCase(dataType)) {
            for (Mesh m : meshes) {
                if (m != null && m.attributes != null && m.attributes.get("sourceMtlFile") != null) {
                    sourceMtl = new File(String.valueOf(m.attributes.get("sourceMtlFile")));
                    if (sourceMtl.exists() && sourceMtl.isFile()) {
                        reuseSourceMtl = true;
                        break;
                    }
                }
            }
        }
        if (!reuseSourceMtl) {
            generateMTLFile(mtlFilePath, meshes, dataType);
        }
        // 全局偏移：确保多个 mesh 写入同一 OBJ 时，顶点/法线/UV 索引连续
        int globalVertexOffset = 0;
        int globalNormalOffset = 0;
        int globalUVOffset = 0;
        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8))) {
            // 引用 MTL
            if (reuseSourceMtl && sourceMtl != null) {
                // 复制原始MTL到输出目录
                File targetMtl = new File(outFile.getParentFile(), sourceMtl.getName());
                copyFileIfNeeded(sourceMtl, targetMtl);
                // 复制MTL引用的贴图
                copyMtlTexturesIfNeeded(sourceMtl, outFile.getParentFile());
                // 补充：将本次导出中被引用但在源MTL中不存在的材质名，追加到拷贝后的MTL文件中（如 mat_PSGXLN）
                try {
                    augmentMtlWithReferencedMaterials(targetMtl, meshes);
                } catch (Exception e) {
                    System.err.println("[ObjExporter] 追加材质到源MTL失败: " + e.getMessage());
                }
                // 在OBJ中引用同目录下的MTL文件名（相对路径），避免跨盘符绝对路径失效
                writer.println("mtllib " + targetMtl.getName());
            } else {
                // 引用生成的MTL文件名（相对路径）
                writer.println("mtllib " + new File(mtlFilePath).getName());
            }
            // 写入每个 mesh
            for (int meshIdx = 0; meshIdx < meshes.size(); meshIdx++) {
                Mesh mesh = meshes.get(meshIdx);
                if (mesh == null || mesh.vertices == null || mesh.faces == null || mesh.vertices.isEmpty() || mesh.faces.isEmpty()) {
                    continue;
                }
                // 组名：优先使用属性中的 name；否则使用 mesh_<idx>_<type>
                String groupBase = (mesh.attributes != null && mesh.attributes.get("name") != null)
                        ? String.valueOf(mesh.attributes.get("name")) : ("mesh_" + meshIdx + "_" + dataType);
                writer.println("g " + groupBase + "_part" + meshIdx);

                // 写顶点 v
                for (Vertex v : mesh.vertices) {
                    writer.printf("v %.8f %.8f %.8f\n", v.x, v.y, v.z);
                }

                // 写法线 vn（仅当数量与顶点一致时）
                boolean writeNormals = (mesh.normals != null && mesh.normals.size() == mesh.vertices.size());
                if (writeNormals) {
                    for (float[] n : mesh.normals) {
                        writer.printf("vn %.15f %.15f %.15f\n", n[0], n[1], n[2]);
                    }
                }

                // 仅当该 mesh 有“有效贴图”时才输出 UV（否则依赖 Kd 颜色，与管线一致）
                boolean meshHasEffectiveTexture = hasEffectiveTexture(mesh);
                boolean writeUVs = false;
                if (meshHasEffectiveTexture) {
                    writeUVs = (mesh.uvs != null && mesh.uvs.size() == mesh.vertices.size());
                    if (!writeUVs) {
                        // 生成完整UV列表
                        mesh.uvs = generateUVsForMesh(mesh.vertices);
                        writeUVs = true;
                    } else {
                        // 填补空UV
                        boolean hasHole = false;
                        for (int __i = 0; __i < mesh.uvs.size(); __i++) {
                            float[] __uv = mesh.uvs.get(__i);
                            if (__uv == null || __uv.length < 2) { hasHole = true; break; }
                        }
                        if (hasHole) {
                            fillMissingUVs(mesh);
                        }
                    }
                    // 输出UV
                    for (float[] uv : mesh.uvs) {
                        float u = (uv != null && uv.length >= 2) ? uv[0] : 0f;
                        float v = (uv != null && uv.length >= 2) ? uv[1] : 0f;
                        writer.printf("vt %.15f %.15f\n", u, v);
                    }
                }
                // 使用的材质名：
                // - 如果是管点且 mesh 提供了贴图材质名（sideMaterial/topMaterial），后续按面法线切换；
                // - 如果是附属物模型，从attributes中读取预设的材质名称；
                // - 否则使用统一材质 mat_<dataType>
                String defaultMat = "mat_" + dataType;
                boolean hasSideTopMaterial = hasTextureMaterials(mesh);
                boolean isAccessoryModel = isAccessoryModel(mesh) || "accessory".equals(asString(mesh.attributes != null ? mesh.attributes.get("modelType") : null));
                boolean isFeatureModelFlag = isFeatureModel(mesh) || "feature".equals(asString(mesh.attributes != null ? mesh.attributes.get("modelType") : null));
                // 为附属物模型和特征模型设置材质：优先 originalMaterial；否则尝试从 material/texture 或 materialMap 选一个
                if ((isAccessoryModel || isFeatureModelFlag) && mesh.attributes != null) {
                    String chosenMaterial = asString(mesh.attributes.get("originalMaterial"));
                    // 只有当originalMaterial为空或"default"时，才使用fallback逻辑
                    if (chosenMaterial == null || chosenMaterial.isEmpty() || "default".equals(chosenMaterial)) {
                        String fallbackMat = asString(mesh.attributes.get("material"));
                        if (fallbackMat != null && !fallbackMat.isEmpty()) {
                            chosenMaterial = fallbackMat;
                        } else {
                            Object mmObj = mesh.attributes.get("materialMap");
                            if (mmObj instanceof Map) {
                                @SuppressWarnings("unchecked") Map<String,String> mm = (Map<String,String>) mmObj;
                                if (!mm.isEmpty()) {
                                    // 选择第一个有贴图的材质名
                                    for (Map.Entry<String,String> e : mm.entrySet()) {
                                        if (e.getKey() != null && !e.getKey().isEmpty() && e.getValue() != null && !e.getValue().trim().isEmpty()) {
                                            chosenMaterial = e.getKey();
                                            break;
                                        }
                                    }
                                    // 若都无贴图，则仍取第一个键名作为材质
                                    if ((chosenMaterial == null || chosenMaterial.isEmpty()) && !mm.isEmpty()) {
                                        chosenMaterial = mm.keySet().iterator().next();
                                    }
                                }
                            }
                        }
                    }
                    // 若该mesh存在有效贴图但缺少材质名，兜底使用默认材质，避免无 usemtl 情况
                    writer.println("usemtl " + (chosenMaterial != null && !chosenMaterial.isEmpty() ? chosenMaterial : (meshHasEffectiveTexture ? (defaultMat + "_tex") : defaultMat)));
                } else {
                    // 普通模型：优先保持贴图材质名，否则默认材质
                    if (meshHasEffectiveTexture) {
                        // 与 generateMTLFile 保持一致：material → originalMaterial → materialMap → mat_<dataType>_tex
                        String chosenMaterial = asString(mesh.attributes != null ? mesh.attributes.get("material") : null);
                        if (chosenMaterial == null || chosenMaterial.isEmpty()) {
                            chosenMaterial = asString(mesh.attributes != null ? mesh.attributes.get("originalMaterial") : null);
                        }
                        if (chosenMaterial == null || chosenMaterial.isEmpty()) {
                            Object mmObj = mesh.attributes != null ? mesh.attributes.get("materialMap") : null;
                            if (mmObj instanceof Map) {
                                @SuppressWarnings("unchecked") Map<String,String> mm = (Map<String,String>) mmObj;
                                if (!mm.isEmpty()) chosenMaterial = mm.keySet().iterator().next();
                            }
                        }
                        writer.println("usemtl " + (chosenMaterial != null && !chosenMaterial.isEmpty() ? chosenMaterial : (defaultMat + "_tex")));
                    } else {
                        writer.println("usemtl " + defaultMat);
                    }
                }
                // 写面 f：
                String lastUsedMaterial = null; // 记录上次使用的材质，避免重复写入
                for (Face f : mesh.faces) {
                    int v1 = f.v1 + 1 + globalVertexOffset;
                    int v2 = f.v2 + 1 + globalVertexOffset;
                    int v3 = f.v3 + 1 + globalVertexOffset;
                    int vt1 = writeUVs ? (f.v1 + 1 + globalUVOffset) : -1;
                    int vt2 = writeUVs ? (f.v2 + 1 + globalUVOffset) : -1;
                    int vt3 = writeUVs ? (f.v3 + 1 + globalUVOffset) : -1;

                    // 对于有侧/顶材质的模型，根据面法线选择材质
                    if (hasSideTopMaterial) {
                        // 有侧/顶材质时：根据面法线的Z分量选择 top 或 side
                        String mat = pickMaterialForFace(mesh, f, defaultMat);
                        if (!mat.equals(lastUsedMaterial)) {
                            writer.println("usemtl " + mat);
                            lastUsedMaterial = mat;
                        }
                    }
                    // 注意：对于没有侧/顶材质区分的模型，不需要在每个面都写usemtl

                    if (writeNormals && writeUVs) {
                        int vn1 = f.v1 + 1 + globalNormalOffset;
                        int vn2 = f.v2 + 1 + globalNormalOffset;
                        int vn3 = f.v3 + 1 + globalNormalOffset;
                        writer.printf("f %d/%d/%d %d/%d/%d %d/%d/%d\n", v1, vt1, vn1, v2, vt2, vn2, v3, vt3, vn3);
                    } else if (writeNormals) {
                        int vn1 = f.v1 + 1 + globalNormalOffset;
                        int vn2 = f.v2 + 1 + globalNormalOffset;
                        int vn3 = f.v3 + 1 + globalNormalOffset;
                        writer.printf("f %d//%d %d//%d %d//%d\n", v1, vn1, v2, vn2, v3, vn3);
                    } else if (writeUVs) {
                        writer.printf("f %d/%d %d/%d %d/%d\n", v1, vt1, v2, vt2, v3, vt3);
                    } else {
                        writer.printf("f %d %d %d\n", v1, v2, v3);
                    }
                }
                // 更新全局偏移
                globalVertexOffset += mesh.vertices.size();
                globalNormalOffset += writeNormals ? mesh.normals.size() : 0;
                globalUVOffset += (writeUVs && mesh.uvs != null) ? mesh.uvs.size() : 0;
            }

        }
    }

    /** 为整个 Mesh 生成稳定的投影 UV（基于XY包围盒归一化）。 */
    private static List<float[]> generateUVsForMesh(List<Vertex> vertices) {
        List<float[]> uvs = new ArrayList<>();
        if (vertices == null || vertices.isEmpty()) return uvs;
        double[] bbox = computeBBox(vertices);
        double minX = bbox[0], minY = bbox[1];
        double maxX = bbox[3], maxY = bbox[4];
        double dx = Math.max(1e-6, maxX - minX);
        double dy = Math.max(1e-6, maxY - minY);
        for (Vertex v : vertices) {
            float u = (float)((v.x - minX) / dx);
            float vt = (float)((v.y - minY) / dy);
            uvs.add(new float[]{u, vt});
        }
        return uvs;
    }

    /** 填补 Mesh 中缺失的 UV。 */
    private static void fillMissingUVs(com.bdtdgis.model.Mesh mesh) {
        if (mesh == null || mesh.vertices == null) return;
        if (mesh.uvs == null || mesh.uvs.size() != mesh.vertices.size()) {
            mesh.uvs = generateUVsForMesh(mesh.vertices);
            return;
        }
        double[] bbox = computeBBox(mesh.vertices);
        double minX = bbox[0], minY = bbox[1];
        double maxX = bbox[3], maxY = bbox[4];
        double dx = Math.max(1e-6, maxX - minX);
        double dy = Math.max(1e-6, maxY - minY);
        for (int i = 0; i < mesh.uvs.size(); i++) {
            float[] uv = mesh.uvs.get(i);
            if (uv == null || uv.length < 2) {
                Vertex v = mesh.vertices.get(i);
                float u = (float)((v.x - minX) / dx);
                float vt = (float)((v.y - minY) / dy);
                mesh.uvs.set(i, new float[]{u, vt});
            }
        }
    }

    /** 计算顶点集合的包围盒 [minX,minY,minZ,maxX,maxY,maxZ]。 */
    private static double[] computeBBox(List<Vertex> vertices) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Vertex v : vertices) {
            if (v.x < minX) minX = v.x;
            if (v.y < minY) minY = v.y;
            if (v.z < minZ) minZ = v.z;
            if (v.x > maxX) maxX = v.x;
            if (v.y > maxY) maxY = v.y;
            if (v.z > maxZ) maxZ = v.z;
        }
        return new double[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    /**
     * 从meshes的materialMap中查找指定材质的贴图路径
     */
    private static String findTextureForMaterial(String materialName, List<Mesh> meshes) {
        if (materialName == null || meshes == null) return null;
        for (Mesh mesh : meshes) {
            if (mesh == null || mesh.attributes == null) continue;
            Object materialMapObj = mesh.attributes.get("materialMap");
            if (materialMapObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, String> materialMap = (Map<String, String>) materialMapObj;
                String texturePath = materialMap.get(materialName);
                if (texturePath != null && !texturePath.trim().isEmpty()) {
                    // 返回贴图文件名（不包含路径）
                    return new File(texturePath).getName();
                }
            }
        }
        return null;
    }

    /**
     * 判断是否为需要生成颜色纹理的材质
     * 包括管点特征材质和只有Kd颜色没有map_Kd的材质
     */
    private static boolean isPointFeatureMaterial(String materialName) {
        if (materialName == null) return false;
        String name = materialName.toLowerCase();
        return name.contains("psgxpt") || name.contains("mat_psgxpt") || 
               name.contains("psgxln") || name.contains("mat_psgxln") ||
               name.contains("feature") || name.contains("point") ||
               name.contains("mat_") || name.startsWith("mat");
    }

    /**
     * 为管点特征材质生成颜色纹理
     */
    private static String generateColorTextureForMaterial(File outputDir, String materialName, List<Mesh> meshes) {
        try {
            // 获取材质颜色
            float[] rgb = chooseColorForMaterialName(materialName, meshes);
            if (rgb == null || rgb.length < 3) {
                return null;
            }

            // 创建1x1像素的纯色图片
            BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = image.createGraphics();
            
            // 将颜色值转换为RGB
            int r = Math.round(rgb[0] * 255);
            int g = Math.round(rgb[1] * 255);
            int b = Math.round(rgb[2] * 255);
            
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
            
            
            return textureFileName; // 返回相对路径
            
        } catch (Exception e) {
            System.err.println("[ObjExporter] 生成颜色纹理失败: " + materialName + ", 错误: " + e.getMessage());
            return null;
        }
    }

    /**
     * 将 OBJ/mesh 中引用但源 MTL 未声明的材质，追加到目标 MTL 文件。
     * 注意：只添加源MTL中不存在的材质，不覆盖已有的材质定义（包括贴图）。
     */
    private static void augmentMtlWithReferencedMaterials(File targetMtl, List<Mesh> meshes) {
        if (targetMtl == null || meshes == null || meshes.isEmpty()) {
            return;
        }
        try {
            List<String> lines = java.nio.file.Files.readAllLines(targetMtl.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            Set<String> existing = new HashSet<>();
            for (String raw : lines) {
                String line = raw.trim();
                if (line.startsWith("newmtl ")) {
                    String name = line.substring(7).trim();
                    if (!name.isEmpty()) existing.add(name);
                }
            }
            // 收集引用的材质名
            LinkedHashSet<String> referenced = new LinkedHashSet<>();
            for (Mesh m : meshes) {
                if (m == null || m.attributes == null) continue;
                String orig = asString(m.attributes.get("originalMaterial"));
                if (orig != null && !orig.isEmpty()) referenced.add(orig);
                // 兼容侧/顶材质名
                String s = asString(m.attributes.get("sideMaterial"));
                String t = asString(m.attributes.get("topMaterial"));
                if (s != null && !s.isEmpty()) referenced.add(s);
                if (t != null && !t.isEmpty()) referenced.add(t);
            }
            // 只添加源MTL中不存在的材质，避免覆盖已有的贴图定义
            List<String> toAppend = new ArrayList<>();
            for (String name : referenced) {
                if (name == null || name.isEmpty() || existing.contains(name)) {
                    continue;
                }
                float[] rgb = chooseColorForMaterialName(name, meshes);
                toAppend.add("newmtl " + name);
                toAppend.add(String.format(Locale.ROOT, "Kd %.6f %.6f %.6f", rgb[0], rgb[1], rgb[2]));
                toAppend.add("d 1");
                
                // 尝试从meshes的materialMap中获取贴图信息
                String texturePath = findTextureForMaterial(name, meshes);
                if (texturePath != null && !texturePath.trim().isEmpty()) {
                    toAppend.add("map_Kd " + texturePath);
                } else {
                    // 如果没有找到贴图，为需要生成颜色纹理的材质生成颜色纹理
                    if (isPointFeatureMaterial(name)) {
                        String colorTexturePath = generateColorTextureForMaterial(targetMtl.getParentFile(), name, meshes);
                        if (colorTexturePath != null && !colorTexturePath.trim().isEmpty()) {
                            toAppend.add("map_Kd " + colorTexturePath);
                        }
                    }
                }
            }
            if (!toAppend.isEmpty()) {
                try (PrintWriter ap = new PrintWriter(new OutputStreamWriter(new FileOutputStream(targetMtl, true), StandardCharsets.UTF_8))) {
                    for (String l : toAppend) ap.println(l);
                }
                // 复制新添加的纹理文件到输出目录
                copyNewTexturesToOutputDir(toAppend, targetMtl.getParentFile());
            }
        } catch (Exception ignored) {
            ignored.printStackTrace();
        }
    }

    /**
     * 复制新添加的纹理文件到输出目录
     */
    private static void copyNewTexturesToOutputDir(List<String> toAppend, File outputDir) {
        for (String line : toAppend) {
            if (line.trim().startsWith("map_Kd ")) {
                String textureName = line.trim().substring(7).trim();
                
                // 查找纹理文件的位置
                File textureFile = findTextureFile(textureName);
                if (textureFile != null && textureFile.exists()) {
                    File targetFile = new File(outputDir, textureName);
                    try {
                        Files.copy(textureFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    } catch (Exception e) {
                        System.err.println("[ObjExporter] 复制纹理文件失败: " + textureName + ", 错误: " + e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * 查找纹理文件的位置
     */
    private static File findTextureFile(String textureName) {
        // 在常见的纹理目录中查找
        String[] searchPaths = {
            "src/main/resources/uploads/feature/特征/非普查点/default/",
            "src/main/resources/uploads/accessory/",
            "src/main/resources/uploads/feature/",
            "target/classes/uploads/feature/特征/非普查点/default/",
            "target/classes/uploads/accessory/",
            "target/classes/uploads/feature/"
        };
        
        for (String path : searchPaths) {
            File textureFile = new File(path, textureName);
            if (textureFile.exists()) {
                return textureFile;
            }
        }
        
        return null;
    }

    /**
     * 生成 MTL 文件：
     * - 若某些 Mesh 提供了贴图（sideTexture/topTexture + 对应材质名），则为这些材质逐一生成 newmtl，并引用贴图；
     * - 对于附属物模型：从attributes中读取attachType，查找对应的MTL文件并生成材质定义；
     * - 否则生成一个统一材质 mat_<dataType>：
     *   - line：使用方案颜色（来自 mesh.attributes.pipeColor 或 color 的十六进制，例如 "#946B5A"）；
     *   - point：若不存在贴图，同样使用与 line 一致的颜色；
     */
    private static void generateMTLFile(String mtlFilePath, List<Mesh> meshes, String dataType) throws IOException {
        File mtlFile = new File(mtlFilePath);
        File parent = mtlFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(mtlFile), StandardCharsets.UTF_8))) {
            // 收集贴图材质（materialName -> textureFileName）
            Map<String, String> materialToTexture = new LinkedHashMap<>();
            Set<String> declaredMaterials = new HashSet<>();
            // 收集无贴图但被引用的材质名（例如 OBJ 中 usemtl mat_PSGXLN）
            Set<String> referencedMaterialsNoTexture = new LinkedHashSet<>();
            for (Mesh mesh : meshes) {
                if (mesh == null) continue;
                // 检查是否为附属物模型
                String attachType = null;
                if (mesh.attributes != null) {
                    attachType = (String) mesh.attributes.get("attachType");
                }
                if (attachType != null && !attachType.isEmpty()) {
                    // 处理附属物模型：优先使用mesh中已设置的材质信息
                    String material = asString(mesh.attributes.get("material"));
                    String texture = asString(mesh.attributes.get("texture"));
                    // 检查是否有完整的材质映射
                    Object materialMapObj = mesh.attributes.get("materialMap");
                    if (materialMapObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, String> materialMap = (Map<String, String>) materialMapObj;
                        // 使用完整的材质映射
                        for (Map.Entry<String, String> entry : materialMap.entrySet()) {
                            String matName = entry.getKey();
                            String texName = entry.getValue();
                            materialToTexture.put(matName, texName);
                        }
                        // 记录该mesh上引用的原始材质（即使没有贴图）
                        String orig = asString(mesh.attributes.get("originalMaterial"));
                        if (orig != null && !orig.isEmpty() && !materialToTexture.containsKey(orig)) {
                            referencedMaterialsNoTexture.add(orig);
                        }
                    } else if (material != null && texture != null) {
                        // 使用已设置的材质和贴图信息
                        materialToTexture.put(material, texture);
                        // 也记录 originalMaterial 作为引用
                        String orig = asString(mesh.attributes.get("originalMaterial"));
                        if (orig != null && !orig.isEmpty() && !materialToTexture.containsKey(orig)) {
                            referencedMaterialsNoTexture.add(orig);
                        }
                    } else {
                        // 如果没有预设材质信息，从附属物目录查找MTL文件
                        processAccessoryMaterial(attachType, declaredMaterials, materialToTexture);
                        // 也记录 originalMaterial
                        String orig = asString(mesh.attributes.get("originalMaterial"));
                        if (orig != null && !orig.isEmpty() && !materialToTexture.containsKey(orig)) {
                            referencedMaterialsNoTexture.add(orig);
                        }
                    }
                } else if (mesh.attributes != null) {
                    // 处理普通模型和特征模型的材质/贴图
                    // 首先检查是否有从OBJ文件解析的材质映射（适用于特征模型）
                    Object materialMapObj = mesh.attributes.get("materialMap");
                    if (materialMapObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, String> materialMap = (Map<String, String>) materialMapObj;
                        // 使用完整的材质映射
                        for (Map.Entry<String, String> entry : materialMap.entrySet()) {
                            String matName = entry.getKey();
                            String texName = entry.getValue();
                            materialToTexture.put(matName, texName);
                        }
                        // 记录该mesh上引用的原始材质（即使没有贴图）
                        String orig = asString(mesh.attributes.get("originalMaterial"));
                        if (orig != null && !orig.isEmpty() && !materialToTexture.containsKey(orig)) {
                            referencedMaterialsNoTexture.add(orig);
                        }
                    } else {
                        // 检查是否有预设的材质和贴图信息
                        String material = asString(mesh.attributes.get("material"));
                        String texture = asString(mesh.attributes.get("texture"));
                        if (texture != null && !texture.trim().isEmpty()) {
                            // 贴图方案：材质名必须与 OBJ 中 usemtl 一致（无 material 时用 mat_<dataType>_tex）
                            String matName = (material != null && !material.isEmpty())
                                    ? material : ("mat_" + dataType + "_tex");
                            materialToTexture.put(matName, texture);
                            String orig = asString(mesh.attributes.get("originalMaterial"));
                            if (orig != null && !orig.isEmpty() && !materialToTexture.containsKey(orig)) {
                                referencedMaterialsNoTexture.add(orig);
                            }
                        } else {
                            // 处理传统的侧/顶材质
                            String sideTex = asString(mesh.attributes.get("sideTexture"));
                            String topTex = asString(mesh.attributes.get("topTexture"));
                            String sideMat = asString(mesh.attributes.get("sideMaterial"));
                            String topMat = asString(mesh.attributes.get("topMaterial"));
                            if (sideTex != null) {
                                materialToTexture.put(sideMat != null ? sideMat : ("mat_" + dataType + "_side"), new File(sideTex).getName());
                            }
                            if (topTex != null) {
                                materialToTexture.put(topMat != null ? topMat : ("mat_" + dataType + "_top"), new File(topTex).getName());
                            }
                            // 无贴图但可能存在原始材质名
                            String orig = asString(mesh.attributes.get("originalMaterial"));
                            if (orig != null && !orig.isEmpty() && !materialToTexture.containsKey(orig)) {
                                referencedMaterialsNoTexture.add(orig);
                            }
                        }
                    }
                }
            }
            if (!materialToTexture.isEmpty()) {
                // 复制贴图文件到输出目录
                copyTexturesToOutputDir(materialToTexture, mtlFile.getParentFile());
                // 写出所有带贴图的材质
                for (Map.Entry<String, String> e : materialToTexture.entrySet()) {
                    String matName = e.getKey();
                    String texName = e.getValue();
                    if (declaredMaterials.add(matName)) {
                        writer.println("newmtl " + matName);
                        float[] rgb = defaultSchemeColor(); // 有贴图时颜色影响不大，给个稳定默认
                        writer.printf("Kd %.6f %.6f %.6f\n", rgb[0], rgb[1], rgb[2]);
                        writer.println("d 1");
                        writer.println("map_Kd " + texName);
                    }
                }
                // 对于被引用但无贴图的材质名，也需要写入（避免 usemtl 找不到）
                for (String matName : referencedMaterialsNoTexture) {
                    if (matName == null || matName.isEmpty()) continue;
                    if (declaredMaterials.add(matName)) {
                        writer.println("newmtl " + matName);
                        float[] rgb = chooseColorForMaterialName(matName, meshes);
                        writer.printf("Kd %.6f %.6f %.6f\n", rgb[0], rgb[1], rgb[2]);
                        writer.println("d 1");
                    }
                }
            } else {
                // 无贴图：创建统一材质 mat_<dataType>
                String matName = "mat_" + dataType;
                float[] rgb = readSchemeColorFromMeshes(meshes);
                declaredMaterials.add(matName);
                writer.println("newmtl " + matName);
                writer.printf("Kd %.6f %.6f %.6f\n", rgb[0], rgb[1], rgb[2]);
                writer.println("d 1");
                // 同时：如果 OBJ/mesh 引用了特定材质名（如 mat_PSGXLN），也要补充这些材质定义
                for (Mesh mesh : meshes) {
                    if (mesh == null || mesh.attributes == null) continue;
                    String orig = asString(mesh.attributes.get("originalMaterial"));
                    if (orig != null && !orig.isEmpty() && declaredMaterials.add(orig)) {
                        writer.println("newmtl " + orig);
                        float[] c = chooseColorForMaterialName(orig, meshes);
                        writer.printf("Kd %.6f %.6f %.6f\n", c[0], c[1], c[2]);
                        writer.println("d 1");
                    }
                }
            }
        }
    }

    /**
     * 根据材质名选择颜色：
     * - mat_PSGXLN：使用默认方案色（与管线一致）
     * - mat_PSGXPT：使用默认点色
     * - 其他：从 meshes 读取方案色，失败则默认方案色
     */
    private static float[] chooseColorForMaterialName(String materialName, List<Mesh> meshes) {
        String name = materialName == null ? "" : materialName.toUpperCase(Locale.ROOT);
        if (name.contains("PSGXLN")) {
            return defaultSchemeColor();
        }
        if (name.contains("PSGXPT")) {
            return defaultPointColor();
        }
        float[] fromMeshes = readSchemeColorFromMeshes(meshes);
        return fromMeshes != null ? fromMeshes : defaultSchemeColor();
    }

    /** 默认点颜色（与现有资源一致）。 */
    private static float[] defaultPointColor() {
        return new float[]{0.48627450980392156f, 0.5843137254901961f, 0.6666666666666666f};
    }

    /**
     * 处理附属物模型的材质定义
     * @param attachType 附属物类型
     * @param declaredMaterials 已声明的材质集合
     * @param materialToTexture 材质到贴图的映射
     */
    private static void processAccessoryMaterial(String attachType,
                                                 Set<String> declaredMaterials,
                                                 Map<String, String> materialToTexture) {
        try {
            // 查找附属物目录
            String[] possiblePaths = {
                "src/main/resources/uploads/accessory/" + attachType,
                "uploads/accessory/" + attachType,
                "target/classes/uploads/accessory/" + attachType
            };
            for (String basePath : possiblePaths) {
                File accessoryDir = new File(basePath);
                if (accessoryDir.exists() && accessoryDir.isDirectory()) {
                    // 查找MTL文件
                    File[] files = accessoryDir.listFiles();
                    if (files == null) files = new File[0];
                    for (File file : files) {
                        if (file.isFile() && file.getName().toLowerCase().endsWith(".mtl")) {
                            // 读取并处理MTL文件，填充materialToTexture映射
                            processMTLFileForMapping(file, declaredMaterials, materialToTexture);
                            return; // 找到并处理了一个MTL文件就返回
                        }
                    }
                    // 如果没有找到MTL文件，尝试从OBJ文件中提取材质信息
                    for (File file : files) {
                        if (file.isFile() && file.getName().toLowerCase().endsWith(".obj")) {
                            processOBJForMaterialsForMapping(file, declaredMaterials, materialToTexture);
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 处理MTL文件，仅填充材质到贴图的映射，不写入文件
     */
    private static void processMTLFileForMapping(File mtlFile,
                                               Set<String> declaredMaterials,
                                               Map<String, String> materialToTexture) {
        try {
            List<String> lines = Files.readAllLines(mtlFile.toPath());
            String currentMaterial = null;

            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("newmtl ")) {
                    currentMaterial = line.substring(7).trim();
                    declaredMaterials.add(currentMaterial);
                } else if (line.startsWith("map_Kd ") && currentMaterial != null) {
                    String textureName = line.substring(7).trim();
                    // 记录材质到贴图的映射
                    materialToTexture.put(currentMaterial, textureName);
                }
            }
        } catch (Exception e) {
            System.err.println("[ObjExporter] 读取MTL文件失败: " + mtlFile.getAbsolutePath() + ", 错误: " + e.getMessage());
        }
    }

    /**
     * 从OBJ文件中提取材质信息，仅填充材质集合，不写入文件
     */
    private static void processOBJForMaterialsForMapping(File objFile,
                                                       Set<String> declaredMaterials,
                                                       Map<String, String> materialToTexture) {
        try {
            List<String> lines = Files.readAllLines(objFile.toPath());
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("usemtl ")) {
                    String matName = line.substring(7).trim();
                    declaredMaterials.add(matName);
                }
            }
        } catch (Exception e) {
            System.err.println("[ObjExporter] 从OBJ文件提取材质失败: " + objFile.getAbsolutePath() + ", 错误: " + e.getMessage());
        }
    }

    /**
     * 判断 mesh 是否声明了侧/顶材质（用于选择每个面的材质）。
     */
    private static boolean hasTextureMaterials(Mesh mesh) {
        if (mesh == null || mesh.attributes == null) return false;
        return mesh.attributes.get("sideMaterial") != null || mesh.attributes.get("topMaterial") != null
                || mesh.attributes.get("sideTexture") != null || mesh.attributes.get("topTexture") != null;
    }

    /** 判断 mesh 是否存在“有效贴图”：显式声明了贴图，或“当前使用的材质名”在材质映射中有贴图。 */
    /**
     * 判断贴图路径是否可用（绝对/相对路径存在，或能在常见目录中找到同名文件）
     */
    private static boolean isTextureUsable(String texturePath) {
        if (texturePath == null || texturePath.trim().isEmpty()) return false;
        File file = new File(texturePath.trim());
        if (file.exists() && file.isFile()) return true;
        return !file.isAbsolute() && findTextureInMultipleLocations(texturePath.trim()) != null;
    }

    private static boolean hasEffectiveTexture(Mesh mesh) {
        if (mesh == null || mesh.attributes == null) return false;
        // 直接贴图键（贴图文件不可用时按无贴图处理，避免 usemtl 指向 MTL 中不存在的材质）
        if (isTextureUsable(asString(mesh.attributes.get("texture")))) return true;
        if (isTextureUsable(asString(mesh.attributes.get("sideTexture")))) return true;
        if (isTextureUsable(asString(mesh.attributes.get("topTexture")))) return true;
        // 解析自源MTL的完整材质映射
        Object m = mesh.attributes.get("materialMap");
        if (m instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, String> mm = (Map<String, String>) m;
            if (!mm.isEmpty()) {
                // 仅当“本 mesh 实际使用的材质名”在映射中且有贴图时，认为有效
                String usedMaterial = asString(mesh.attributes.get("originalMaterial"));
                if (usedMaterial != null && mm.containsKey(usedMaterial)) {
                    String tex = mm.get(usedMaterial);
                    return tex != null && !tex.trim().isEmpty();
                }
            }
        }
        return false;
    }

    /**
     * 判断 mesh 是否为附属物模型。
     */
    private static boolean isAccessoryModel(Mesh mesh) {
        if (mesh == null || mesh.attributes == null) return false;
        return mesh.attributes.get("attachType") != null && !((String)mesh.attributes.get("attachType")).isEmpty();
    }

    /**
     * 判断 mesh 是否为特征模型。
     */
    private static boolean isFeatureModel(Mesh mesh) {
        if (mesh == null || mesh.attributes == null) return false;
        return mesh.attributes.get("featureType") != null && !((String)mesh.attributes.get("featureType")).isEmpty();
    }

    /**
     * 根据面的法线，选择使用顶面/侧面材质。
     * 规则：|nz| >= 0.7 视为顶/底面；否则视为侧面。
     */
    private static String pickMaterialForFace(Mesh mesh, Face f, String defaultMat) {
        String sideMat = asString(mesh.attributes.get("sideMaterial"));
        String topMat = asString(mesh.attributes.get("topMaterial"));
        Vertex a = mesh.vertices.get(f.v1), b = mesh.vertices.get(f.v2), c = mesh.vertices.get(f.v3);
        double ux = b.x - a.x, uy = b.y - a.y, uz = b.z - a.z;
        double vx = c.x - a.x, vy = c.y - a.y, vz = c.z - a.z;
        double nx = uy * vz - uz * vy;
        double ny = uz * vx - ux * vz;
        double nz = ux * vy - uy * vx;
        double len = Math.max(1e-9, Math.sqrt(nx * nx + ny * ny + nz * nz));
        double zn = nz / len;
        if (Math.abs(zn) >= 0.7) {
            return topMat != null ? topMat : (defaultMat + "_top");
        }
        return sideMat != null ? sideMat : (defaultMat + "_side");
    }

    /**
     * 从 meshes 中读取方案颜色：优先 attributes.pipeColor（#RRGGBB），次选 attributes.color，最后使用默认棕色。
     * 注：当 dataType=point 且没有贴图时，也使用该颜色，满足"管点默认与管线同色"的要求。
     */
    private static float[] readSchemeColorFromMeshes(List<Mesh> meshes) {
        if (meshes != null) {
            for (Mesh m : meshes) {
                if (m == null || m.attributes == null) continue;
                String hex = asString(m.attributes.get("pipeColor"));
                if (hex == null) hex = asString(m.attributes.get("color"));
                if (hex != null) {
                    float[] rgb = parseHexToRgb01(hex);
                    if (rgb != null) return rgb;
                }
            }
            // 兼容：部分场景通过 Mesh.colors 传入第一项颜色
            for (Mesh m : meshes) {
                if (m != null && m.colors != null && !m.colors.isEmpty()) {
                    float[] c = m.colors.get(0);
                    if (c != null && c.length >= 3) return new float[]{c[0], c[1], c[2]};
                }
            }
        }
        return defaultSchemeColor();
    }

    /** 默认方案颜色（棕色，与项目既有默认一致）。 */
    private static float[] defaultSchemeColor() {
        return new float[]{0.5803921568627451f, 0.5098039215686274f, 0.4235294117647059f};
    }

    /** 字符串化工具。 */
    private static String asString(Object v) { return v == null ? null : String.valueOf(v); }

    /** 将 #RRGGBB 或 RRGGBB 解析为 0..1 RGB；异常返回 null。 */
    private static float[] parseHexToRgb01(String hex) {
        if (hex == null) return null;
        String s = hex.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() != 6) return null;
        try {
            int r = Integer.parseInt(s.substring(0, 2), 16);
            int g = Integer.parseInt(s.substring(2, 4), 16);
            int b = Integer.parseInt(s.substring(4, 6), 16);
            return new float[]{r / 255f, g / 255f, b / 255f};
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 复制文件，如果目标文件不存在或内容不同则复制。
     */
    private static void copyFileIfNeeded(File source, File target) throws IOException {
        if (!target.exists() || !isContentEqual(source, target)) {
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } else {
        }
    }

    /**
     * 复制材质映射中的贴图文件到输出目录
     * 如果贴图文件不存在，则从材质映射中移除该条目，不创建默认贴图
     */
    private static void copyTexturesToOutputDir(Map<String, String> materialToTexture, File outputDir) {
        // 确保输出目录存在
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        // 用于收集需要移除的条目（贴图文件不存在的）
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, String> entry : materialToTexture.entrySet()) {
            String materialName = entry.getKey();
            String texturePath = entry.getValue();
            if (texturePath == null || texturePath.trim().isEmpty()) {
                toRemove.add(materialName);
                continue;
            }
            try {
                File srcTexture = new File(texturePath);
                // 如果是相对路径，尝试在多个可能的位置查找
                if (!srcTexture.exists() && !srcTexture.isAbsolute()) {
                    File altTexture = findTextureInMultipleLocations(texturePath);
                    if (altTexture != null) {
                        srcTexture = altTexture;
                    }
                }
                if (srcTexture.exists()) {
                    String fileName = srcTexture.getName();
                    File targetTexture = new File(outputDir, fileName);
                    // 检查是否需要复制（文件不存在或内容不同）
                    if (!targetTexture.exists() || !isContentEqual(srcTexture, targetTexture)) {
                        Files.copy(srcTexture.toPath(), targetTexture.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                    // 更新材质映射中的贴图路径为相对路径
                    entry.setValue(fileName);
                } else {
                    toRemove.add(materialName);
                }
            } catch (Exception e) {
                toRemove.add(materialName);
            }
        }
        // 移除贴图文件不存在的材质条目
        for (String materialName : toRemove) {
            materialToTexture.remove(materialName);
        }
    }
    
    /**
     * 在多个可能的位置查找贴图文件
     */
    private static File findTextureInMultipleLocations(String texturePath) {
        // 可能的查找位置
        String[] searchPaths = {
            "uploads",
            "src/main/resources/uploads", 
            "target/classes/uploads",
            "src/main/resources/static/images"
        };
        for (String basePath : searchPaths) {
            File baseDir = new File(basePath);
            if (baseDir.exists()) {
                File found = findTextureInDirectory(baseDir, texturePath);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
    
    /**
     * 在指定目录中递归查找贴图文件
     */
    private static File findTextureInDirectory(File dir, String texturePath) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return null;
        // 直接查找
        File directFile = new File(dir, texturePath);
        if (directFile.exists()) return directFile;
        // 递归查找
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    File found = findTextureInDirectory(file, texturePath);
                    if (found != null) return found;
                } else if (file.getName().equals(new File(texturePath).getName())) {
                    return file;
                }
            }
        }
        return null;
    }

    /**
     * 复制 MTL 文件中引用的贴图文件。
     */
    private static void copyMtlTexturesIfNeeded(File mtlFile, File outputDir) {
        try {
            List<String> lines = Files.readAllLines(mtlFile.toPath());
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("map_Kd ")) {
                    String textureName = line.substring(7).trim();
                    if (textureName.startsWith("http://") || textureName.startsWith("https://")) {
                        // 处理网络URL，尝试下载或复制
                        try {
                            URL url = new URL(textureName);
                            String fileName = new File(url.getPath()).getName();
                            File textureFile = new File(outputDir, fileName);
                            if (!textureFile.exists() || !isContentEqual(url.openStream(), textureFile)) {
                                Files.copy(url.openStream(), textureFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            } else {
                            }
                        } catch (Exception e) {
                            System.err.println("[ObjExporter] 复制网络贴图失败: " + textureName + ", 错误: " + e.getMessage());
                        }
                    } else {
                        // 处理本地文件路径（相对路径基于MTL所在目录，且保留相对目录结构）
                        File srcTexture = new File(textureName);
                        if (!srcTexture.isAbsolute()) {
                            srcTexture = new File(mtlFile.getParentFile(), textureName);
                        }
                        if (srcTexture.exists()) {
                            File targetTextureFile = new File(outputDir, textureName); // 保留相对子目录
                            File parentDir = targetTextureFile.getParentFile();
                            if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();
                            if (!targetTextureFile.exists() || !isContentEqual(srcTexture, targetTextureFile)) {
                                Files.copy(srcTexture.toPath(), targetTextureFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[ObjExporter] 读取MTL文件失败: " + mtlFile.getAbsolutePath() + ", 错误: " + e.getMessage());
        }
    }

    /**
     * 比较两个文件的内容是否相同。
     */
    private static boolean isContentEqual(File file1, File file2) throws IOException {
        if (file1.length() != file2.length()) {
            return false;
        }
        try (InputStream fis1 = new FileInputStream(file1);
             InputStream fis2 = new FileInputStream(file2)) {
            byte[] b1 = new byte[1024];
            byte[] b2 = new byte[1024];
            int r1;
            int r2;
            while ((r1 = fis1.read(b1)) != -1 && (r2 = fis2.read(b2)) != -1) {
                if (r1 != r2) return false;
                if (!Arrays.equals(Arrays.copyOf(b1, r1), Arrays.copyOf(b2, r2))) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * 比较两个 InputStream 的内容是否相同。
     */
    private static boolean isContentEqual(InputStream is1, File file2) throws IOException {
        try (InputStream fis2 = new FileInputStream(file2)) {
            byte[] b1 = new byte[1024];
            byte[] b2 = new byte[1024];
            int r1;
            int r2;
            while ((r1 = is1.read(b1)) != -1 && (r2 = fis2.read(b2)) != -1) {
                if (r1 != r2) return false;
                if (!Arrays.equals(Arrays.copyOf(b1, r1), Arrays.copyOf(b2, r2))) {
                    return false;
                }
            }
            return true;
        }
    }
} 