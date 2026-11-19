package com.bdtdgis.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ZipUtil {

    /**
     * 将zip文件解压到uploads目录下，避免重复的目录名称
     * @param zipFile zip文件
     * @param uploadsDir uploads目录路径
     * @return 解压后的目录
     * @throws IOException
     */
    public static File unzipToUploadsDir(File zipFile, String uploadsDir) throws IOException {
        // 验证ZIP文件
        if (!zipFile.exists()) {
            throw new IOException("ZIP文件不存在: " + zipFile.getAbsolutePath());
        }
        if (zipFile.length() == 0) {
            throw new IOException("ZIP文件为空: " + zipFile.getAbsolutePath());
        }
        // 获取zip文件名（不含扩展名）
        String zipFileName = zipFile.getName();
        String dirName = zipFileName;
        if (zipFileName.toLowerCase().endsWith(".zip")) {
            dirName = zipFileName.substring(0, zipFileName.length() - 4);
        }
        // 创建uploads目录
        File uploadsDirectory = new File(uploadsDir);
        if (!uploadsDirectory.exists()) {
            uploadsDirectory.mkdirs();
        }
        IOException last = null;
        Charset[] tryCharsets = new Charset[]{StandardCharsets.UTF_8, Charset.forName("GBK")};
        for (Charset cs : tryCharsets) {
            try (ZipFile zf = new ZipFile(zipFile, cs)) {
                // 是否包含与zip文件名相同的顶级目录
                boolean hasMatchingDir = false;
                for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
                    ZipEntry e = en.nextElement();
                    String name = e.getName();
                    if (name.startsWith(dirName + "/") || name.equals(dirName + "/")) {
                        hasMatchingDir = true;
                        break;
                    }
                }

                // 确定目标目录
                File targetDir = hasMatchingDir ? uploadsDirectory : new File(uploadsDirectory, dirName);
                if (targetDir.exists() && !targetDir.equals(uploadsDirectory)) {
                    FileUtil.deleteDirectory(targetDir);
                }
                if (!targetDir.equals(uploadsDirectory)) {
                    targetDir.mkdirs();
                }

                Path root = targetDir.toPath().toAbsolutePath().normalize();
                // 解压
                for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
                    ZipEntry entry = en.nextElement();
                    String entryName = entry.getName();
                    String relative = entryName;
                    if (hasMatchingDir && entryName.startsWith(dirName + "/")) {
                        relative = entryName.substring(dirName.length() + 1);
                        if (relative.isEmpty()) continue; // 跳过根目录条目
                    }

                    Path outPath = new File(targetDir, relative).toPath().normalize();
                    if (!outPath.toAbsolutePath().startsWith(root)) {
                        throw new IOException("拒绝解压潜在路径穿越条目: " + entryName);
                    }

                    if (entry.isDirectory()) {
                        Files.createDirectories(outPath);
                    } else {
                        Files.createDirectories(outPath.getParent());
                        try (InputStream in = zf.getInputStream(entry)) {
                            Files.copy(in, outPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
                return targetDir; // 成功
            } catch (IllegalArgumentException | IOException ex) {
                // 统一包一层 IOException 以满足声明
                last = (ex instanceof IOException) ? (IOException) ex : new IOException(ex);
            }
        }
        throw new IOException("解压ZIP文件失败（可能为文件名编码问题）", last);
    }

}