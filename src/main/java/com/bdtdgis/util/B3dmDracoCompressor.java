package com.bdtdgis.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对 obj23dtiles 产出的 b3dm 做 Draco 后处理压缩。
 * <p>
 * 处理流程：
 * <ol>
 *   <li>按 b3dm 头（28 字节，小端）拆出 featureTable JSON/Binary、batchTable JSON/Binary 与内嵌 GLB；</li>
 *   <li>把 GLB 交给 gltf-pipeline（-i in.glb -o out.glb -d）做 Draco 压缩；</li>
 *   <li>校验压缩后的 GLB 合法且包含 KHR_draco_mesh_compression、批次属性未丢失；</li>
 *   <li>保留原 featureTable/batchTable 字节不变，替换 glTF 段后回包为新的 b3dm，并保证 8 字节对齐。</li>
 * </ol>
 * 任何一步失败都会抛出异常，由调用方按分片失败如实上报，不做“已压缩”的静默降级。
 */
public final class B3dmDracoCompressor {

    public static final String DRACO_EXTENSION = "KHR_draco_mesh_compression";

    private static final int B3DM_HEADER_LENGTH = 28;
    private static final int GLB_HEADER_LENGTH = 12;
    private static final int GLB_CHUNK_HEADER_LENGTH = 8;
    private static final int GLB_JSON_CHUNK_TYPE = 0x4E4F534A;
    private static final int GLB_BIN_CHUNK_TYPE = 0x004E4942;
    private static final Pattern BATCH_LENGTH_PATTERN =
            Pattern.compile("\"BATCH_LENGTH\"\\s*:\\s*(\\d+)");

    private B3dmDracoCompressor() {
    }

    /**
     * 压缩结果（用于接口返回与日志）
     */
    public static class DracoCompressionResult {
        public final long originalTileBytes;
        public final long compressedTileBytes;
        public final int batchLength;
        public final int glbBytesBefore;
        public final int glbBytesAfter;

        DracoCompressionResult(long originalTileBytes, long compressedTileBytes, int batchLength,
                               int glbBytesBefore, int glbBytesAfter) {
            this.originalTileBytes = originalTileBytes;
            this.compressedTileBytes = compressedTileBytes;
            this.batchLength = batchLength;
            this.glbBytesBefore = glbBytesBefore;
            this.glbBytesAfter = glbBytesAfter;
        }
    }

    /**
     * 检查 Draco 压缩工具是否可用。
     * <p>
     * 配置值为文件路径时检查文件是否存在；配置值为命令名时通过执行 “命令 --version” 探测。
     */
    public static boolean isToolAvailable(String toolPath) {
        if (toolPath == null || toolPath.trim().isEmpty()) {
            System.out.println("Draco 压缩工具不可用：未配置 dracoToolPath");
            return false;
        }
        String trimmed = toolPath.trim();
        boolean looksLikePath = trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains(":");
        if (looksLikePath) {
            File file = new File(trimmed);
            if (!file.isFile()) {
                System.out.println("Draco 压缩工具不可用：文件不存在 " + trimmed);
                return false;
            }
            return true;
        }
        boolean available = NodeUtil.isCommandAvailable(trimmed);
        if (!available) {
            System.out.println("Draco 压缩工具不可用：命令不存在 " + trimmed);
        }
        return available;
    }

    /**
     * 对单个 b3dm 文件做 Draco 压缩，成功后原地替换该文件。
     *
     * @param b3dmFile     obj23dtiles 生成的 b3dm
     * @param dracoToolPath gltf-pipeline 可执行文件路径或命令名
     * @return 压缩结果
     * @throws Exception 工具不可用、b3dm 解析失败、压缩失败或回包校验失败
     */
    public static DracoCompressionResult compress(File b3dmFile, String dracoToolPath) throws Exception {
        if (b3dmFile == null || !b3dmFile.isFile() || b3dmFile.length() == 0) {
            throw new IOException("b3dm 文件不存在或为空: " + b3dmFile);
        }
        if (!isToolAvailable(dracoToolPath)) {
            throw new IOException("Draco 压缩工具不可用（dracoToolPath=" + dracoToolPath
                    + "）。请安装 gltf-pipeline（npm install -g gltf-pipeline）或把 dracoToolPath 指向 gltf-pipeline.cmd");
        }

        byte[] tile = Files.readAllBytes(b3dmFile.toPath());
        B3dmParts parts = parseB3dm(tile);

        File workDir = b3dmFile.getAbsoluteFile().getParentFile();
        String baseName = b3dmFile.getName();
        File inGlb = new File(workDir, baseName + ".draco_in.glb");
        File outGlb = new File(workDir, baseName + ".draco_out.glb");
        File tmpTile = new File(workDir, baseName + ".draco_tmp");
        try {
            Files.write(inGlb.toPath(), parts.glb);

            String command = quoteIfNeeded(dracoToolPath.trim())
                    + " -i " + quoteIfNeeded(inGlb.getAbsolutePath())
                    + " -o " + quoteIfNeeded(outGlb.getAbsolutePath())
                    + " -d";
            NodeUtil.runExternalCommand(command);

            if (!outGlb.isFile() || outGlb.length() == 0) {
                throw new IOException("gltf-pipeline 执行结束但未生成有效 GLB 文件: " + outGlb.getAbsolutePath());
            }
            byte[] compressedGlb = Files.readAllBytes(outGlb.toPath());
            validateGlb(compressedGlb, parts.glbHadBatchId, parts.batchLength);
            compressedGlb = sanitizeBatchIdAccessors(compressedGlb);

            byte[] newTile = repackB3dm(parts, compressedGlb);
            Files.write(tmpTile.toPath(), newTile);
            Files.move(tmpTile.toPath(), b3dmFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

            System.out.println("Draco 压缩完成: " + b3dmFile.getName()
                    + " GLB " + parts.glb.length + " -> " + compressedGlb.length + " 字节，"
                    + "瓦片 " + tile.length + " -> " + newTile.length + " 字节，BATCH_LENGTH=" + parts.batchLength);
            return new DracoCompressionResult(tile.length, newTile.length, parts.batchLength,
                    parts.glb.length, compressedGlb.length);
        } finally {
            deleteQuietly(inGlb);
            deleteQuietly(outGlb);
            deleteQuietly(tmpTile);
        }
    }

    /**
     * b3dm 拆包结果
     */
    private static class B3dmParts {
        /** 28 字节头中的 4 个长度字段（保持不变） */
        int ftJsonLength;
        int ftBinLength;
        int btJsonLength;
        int btBinLength;
        /** featureTable JSON + Binary + batchTable JSON + Binary 的原始字节（包含其内部补齐） */
        byte[] tableBytes;
        /** 内嵌 GLB（按 GLB 头 length 精确截取） */
        byte[] glb;
        int batchLength;
        boolean glbHadBatchId;
    }

    /**
     * 解析 b3dm：校验头、切出各段、读取 BATCH_LENGTH 与 GLB 批次属性
     */
    private static B3dmParts parseB3dm(byte[] tile) throws IOException {
        if (tile.length < B3DM_HEADER_LENGTH) {
            throw new IOException("b3dm 文件长度不足 " + B3DM_HEADER_LENGTH + " 字节: " + tile.length);
        }
        String magic = new String(tile, 0, 4, StandardCharsets.US_ASCII);
        if (!"b3dm".equals(magic)) {
            throw new IOException("不是有效的 b3dm 文件（magic=" + magic + "）");
        }
        ByteBuffer buffer = ByteBuffer.wrap(tile).order(ByteOrder.LITTLE_ENDIAN);
        int version = buffer.getInt(4);
        long declaredByteLength = Integer.toUnsignedLong(buffer.getInt(8));
        B3dmParts parts = new B3dmParts();
        parts.ftJsonLength = buffer.getInt(12);
        parts.ftBinLength = buffer.getInt(16);
        parts.btJsonLength = buffer.getInt(20);
        parts.btBinLength = buffer.getInt(24);
        if (parts.ftJsonLength < 0 || parts.ftBinLength < 0 || parts.btJsonLength < 0 || parts.btBinLength < 0) {
            throw new IOException("b3dm 头中的 featureTable/batchTable 长度非法");
        }
        long headerEnd = B3DM_HEADER_LENGTH + (long) parts.ftJsonLength + parts.ftBinLength
                + parts.btJsonLength + parts.btBinLength;
        int tileLimit = (int) Math.min(declaredByteLength, tile.length);
        if (headerEnd > tileLimit) {
            throw new IOException("b3dm 头声明的表长度超出文件范围（headerEnd=" + headerEnd
                    + ", byteLength=" + declaredByteLength + ", 实际=" + tile.length + "）");
        }
        parts.tableBytes = new byte[(int) (headerEnd - B3DM_HEADER_LENGTH)];
        System.arraycopy(tile, B3DM_HEADER_LENGTH, parts.tableBytes, 0, parts.tableBytes.length);

        int glbStart = (int) headerEnd;
        int glbAvailable = tileLimit - glbStart;
        if (glbAvailable < GLB_HEADER_LENGTH + GLB_CHUNK_HEADER_LENGTH) {
            throw new IOException("b3dm 中未找到有效的内嵌 glTF（b3dm version=" + version + "）");
        }
        int glbDeclaredLength = readIntLE(tile, glbStart + 8);
        if (glbDeclaredLength < GLB_HEADER_LENGTH || glbDeclaredLength > glbAvailable) {
            throw new IOException("b3dm 内嵌 GLB 长度非法（声明 " + glbDeclaredLength
                    + "，可用 " + glbAvailable + "）");
        }
        parts.glb = new byte[glbDeclaredLength];
        System.arraycopy(tile, glbStart, parts.glb, 0, glbDeclaredLength);

        if (!"glTF".equals(new String(parts.glb, 0, 4, StandardCharsets.US_ASCII))) {
            throw new IOException("b3dm 内嵌数据不是 GLB（magic 非 glTF）");
        }
        int glbVersion = readIntLE(parts.glb, 4);
        if (glbVersion != 2) {
            throw new IOException("b3dm 内嵌 glTF 版本为 " + glbVersion + "，gltf-pipeline 仅支持 glTF 2.0");
        }

        String featureTableJson = new String(tile, B3DM_HEADER_LENGTH, parts.ftJsonLength,
                StandardCharsets.UTF_8).trim();
        Matcher matcher = BATCH_LENGTH_PATTERN.matcher(featureTableJson);
        if (!matcher.find()) {
            throw new IOException("b3dm featureTable 缺少 BATCH_LENGTH，无法确认要素数量");
        }
        parts.batchLength = Integer.parseInt(matcher.group(1));

        String glbJson = readGlbJson(parts.glb);
        parts.glbHadBatchId = glbJson.contains("\"_BATCHID\"") || glbJson.contains("BATCHID");
        return parts;
    }

    /**
     * 校验压缩后的 GLB：结构合法、包含 Draco 扩展，批次属性未丢失
     */
    private static void validateGlb(byte[] glb, boolean expectBatchId, int batchLength) throws IOException {
        if (glb == null || glb.length < GLB_HEADER_LENGTH + GLB_CHUNK_HEADER_LENGTH) {
            throw new IOException("gltf-pipeline 输出的 GLB 长度不足");
        }
        if (!"glTF".equals(new String(glb, 0, 4, StandardCharsets.US_ASCII))) {
            throw new IOException("gltf-pipeline 输出的文件不是 GLB（magic 非 glTF）");
        }
        int version = readIntLE(glb, 4);
        if (version != 2) {
            throw new IOException("gltf-pipeline 输出的 GLB 版本非法: " + version);
        }
        int declaredLength = readIntLE(glb, 8);
        if (declaredLength != glb.length) {
            throw new IOException("gltf-pipeline 输出的 GLB 长度与文件不一致（声明 " + declaredLength
                    + "，实际 " + glb.length + "）");
        }
        String json = readGlbJson(glb);
        if (!json.contains(DRACO_EXTENSION)) {
            throw new IOException("gltf-pipeline 输出未包含 " + DRACO_EXTENSION + " 扩展，压缩未生效");
        }
        if (expectBatchId && !json.contains("BATCHID")) {
            throw new IOException("Draco 压缩后批次属性 _BATCHID 丢失，无法保证 " + batchLength + " 个要素的批次归属");
        }
    }

    /**
     * 清洗批次属性 accessor 的 min/max。
     * <p>
     * obj23dtiles 生成的 _BATCHID accessor 把 min/max 写成了标量，gltf-pipeline 压缩后又变成 [null]，
     * 两者都不符合 glTF 规范（SCALAR 的 min/max 必须是数组）。min/max 对批次属性是可选元数据，
     * 这里直接移除，既不影响 Cesium 按 _BATCHID 渲染，也让产物通过 glTF 校验。
     */
    private static byte[] sanitizeBatchIdAccessors(byte[] glb) throws IOException {
        int jsonLength = readIntLE(glb, 12);
        int jsonStart = GLB_HEADER_LENGTH + GLB_CHUNK_HEADER_LENGTH;
        if (jsonLength <= 0 || jsonStart + jsonLength > glb.length) {
            throw new IOException("GLB JSON chunk 长度非法: " + jsonLength);
        }
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root;
        try {
            root = mapper.readTree(new String(glb, jsonStart, jsonLength, StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            throw new IOException("无法解析 gltf-pipeline 输出 GLB 的 JSON: " + e.getMessage(), e);
        }
        Set<Integer> batchIdAccessors = new HashSet<>();
        for (JsonNode mesh : root.path("meshes")) {
            for (JsonNode primitive : mesh.path("primitives")) {
                Iterator<Map.Entry<String, JsonNode>> fields = primitive.path("attributes").fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    if (field.getKey() != null
                            && field.getKey().toUpperCase(Locale.ROOT).contains("BATCHID")
                            && field.getValue().isInt()) {
                        batchIdAccessors.add(field.getValue().asInt());
                    }
                }
            }
        }
        JsonNode accessors = root.path("accessors");
        boolean changed = false;
        for (Integer index : batchIdAccessors) {
            if (accessors.isArray() && index >= 0 && index < accessors.size()) {
                ObjectNode accessor = (ObjectNode) accessors.get(index);
                if (accessor.has("min") || accessor.has("max")) {
                    accessor.remove("min");
                    accessor.remove("max");
                    changed = true;
                }
            }
        }
        if (!changed) {
            return glb;
        }

        byte[] newJson = mapper.writeValueAsBytes(root);
        int jsonPad = (4 - (newJson.length % 4)) % 4;
        int binChunkStart = GLB_HEADER_LENGTH + GLB_CHUNK_HEADER_LENGTH + jsonLength;
        int binLength = readIntLE(glb, binChunkStart);
        int binChunkType = readIntLE(glb, binChunkStart + 4);
        if (binChunkType != GLB_BIN_CHUNK_TYPE || binChunkStart + GLB_CHUNK_HEADER_LENGTH + binLength > glb.length) {
            throw new IOException("GLB 的 BIN chunk 非法，无法回写清洗后的 JSON");
        }
        int binDataStart = binChunkStart + GLB_CHUNK_HEADER_LENGTH;
        int totalLength = GLB_HEADER_LENGTH + GLB_CHUNK_HEADER_LENGTH + newJson.length + jsonPad
                + GLB_CHUNK_HEADER_LENGTH + binLength;
        ByteBuffer out = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN);
        out.put("glTF".getBytes(StandardCharsets.US_ASCII));
        out.putInt(2);
        out.putInt(totalLength);
        out.putInt(newJson.length + jsonPad);
        out.putInt(GLB_JSON_CHUNK_TYPE);
        out.put(newJson);
        // JSON chunk 用空格补齐到 4 字节边界
        for (int i = 0; i < jsonPad; i++) {
            out.put((byte) ' ');
        }
        out.putInt(binLength);
        out.putInt(binChunkType);
        out.put(glb, binDataStart, binLength);
        return out.array();
    }

    /**
     * 回包：保留原 featureTable/batchTable 字节，仅替换 glTF 段
     * <p>
     * b3dm 规范要求 tile data（GLB）起始于 8 字节边界，整个瓦片长度也是 8 字节对齐；
     * 这里在表段与 GLB 之间、以及 GLB 之后补零实现对齐，表段内容与头中的表长度字段保持不变。
     */
    private static byte[] repackB3dm(B3dmParts parts, byte[] compressedGlb) {
        int unpaddedGlbStart = B3DM_HEADER_LENGTH + parts.tableBytes.length;
        int leadingPad = (8 - (unpaddedGlbStart % 8)) % 8;
        int glbStart = unpaddedGlbStart + leadingPad;
        int tailPad = (8 - ((glbStart + compressedGlb.length) % 8)) % 8;
        int totalLength = glbStart + compressedGlb.length + tailPad;

        ByteBuffer out = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN);
        out.put("b3dm".getBytes(StandardCharsets.US_ASCII));
        out.putInt(1);
        out.putInt(totalLength);
        out.putInt(parts.ftJsonLength);
        out.putInt(parts.ftBinLength);
        out.putInt(parts.btJsonLength);
        out.putInt(parts.btBinLength);
        out.put(parts.tableBytes);
        out.put(new byte[leadingPad]);
        out.put(compressedGlb);
        out.put(new byte[tailPad]);
        return out.array();
    }

    /**
     * 读取 GLB 的 JSON chunk（跳过 chunk 头的 8 字节，去掉补齐用的空格/NUL）
     */
    private static String readGlbJson(byte[] glb) throws IOException {
        if (glb.length < GLB_HEADER_LENGTH + GLB_CHUNK_HEADER_LENGTH) {
            throw new IOException("GLB 长度不足，无法读取 JSON chunk");
        }
        int jsonChunkLength = readIntLE(glb, 12);
        int jsonChunkType = readIntLE(glb, 16);
        int jsonStart = GLB_HEADER_LENGTH + GLB_CHUNK_HEADER_LENGTH;
        if (jsonChunkType != GLB_JSON_CHUNK_TYPE) {
            throw new IOException("GLB 第一个 chunk 不是 JSON（type=0x" + Integer.toHexString(jsonChunkType) + "）");
        }
        if (jsonChunkLength <= 0 || jsonStart + jsonChunkLength > glb.length) {
            throw new IOException("GLB JSON chunk 长度非法: " + jsonChunkLength);
        }
        String json = new String(glb, jsonStart, jsonChunkLength, StandardCharsets.UTF_8);
        return json.replace("\u0000", " ").trim();
    }

    private static int readIntLE(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static String quoteIfNeeded(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(' ') >= 0 || value.indexOf('\t') >= 0) {
            return "\"" + value + "\"";
        }
        return value;
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
