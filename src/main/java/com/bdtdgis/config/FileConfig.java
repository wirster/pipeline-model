package com.bdtdgis.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;

@Component
public class FileConfig {
    
    @Autowired
    private ResourceLoader resourceLoader;
    
    @Value("${app.file.output-dir:}")
    private String configuredOutputDir;
    
    @Value("${app.file.upload-dir:}")
    private String configuredUploadDir;
    
    @Value("${app.file.use-relative-path:true}")
    private boolean useRelativePath;
    
    private String outputDir;
    private String uploadDir;
    
    @PostConstruct
    public void init() {
        // 输出目录：优先使用 app.file.output-dir，未配置时回退到 <工作目录>/output
        outputDir = resolveAndCreateDir(configuredOutputDir, "output", "输出");
        // 上传目录：优先使用 app.file.upload-dir，未配置时回退到 <工作目录>/uploads
        uploadDir = resolveAndCreateDir(configuredUploadDir, "uploads", "上传");
    }

    /**
     * 解析目录配置并确保目录可用。
     * <p>
     * 相对路径统一按进程工作目录解析，绝对路径直接使用；目录无法创建时直接抛出异常，
     * 避免后续建模在无感知的情况下写入失败目录（尤其是配置了本机不存在的盘符时）。
     */
    private String resolveAndCreateDir(String configured, String defaultName, String label) {
        String value = (configured == null) ? "" : configured.trim();
        if (value.isEmpty()) {
            value = defaultName;
        }
        File dir = new File(value);
        if (!dir.isAbsolute()) {
            dir = new File(System.getProperty("user.dir"), value);
        }
        dir = dir.getAbsoluteFile();
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IllegalStateException("无法创建" + label + "目录: " + dir.getAbsolutePath()
                    + "。请检查 app.file." + ("输出".equals(label) ? "output-dir" : "upload-dir")
                    + " 配置及目录权限");
        }
        System.out.println(label + "目录: " + dir.getAbsolutePath());
        return dir.getAbsolutePath();
    }
    
    private String getResourcesPath() {
        try {
            Resource resource = resourceLoader.getResource("classpath:");
            File resourcesDir = resource.getFile();
            return resourcesDir.getAbsolutePath();
        } catch (IOException e) {
            return System.getProperty("user.dir") + "/src/main/resources";
        }
    }
    
    public String getOutputDir() {
        return outputDir;
    }
    
    public String getUploadDir() {
        return uploadDir;
    }
} 