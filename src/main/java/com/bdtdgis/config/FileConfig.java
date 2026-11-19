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
        // 优先使用配置文件中的设置
        if (configuredOutputDir != null && !configuredOutputDir.trim().isEmpty()) {
            // 处理相对路径
            if (configuredOutputDir.startsWith("./")) {
                String currentDir = System.getProperty("user.dir");
                outputDir = new File(currentDir, configuredOutputDir.substring(2)).getAbsolutePath();
            } else {
                outputDir = configuredOutputDir;
            }
        } else {
            // 如果没有配置，使用默认路径
            try {
                // 获取classpath路径
                String classpath = getClass().getClassLoader().getResource("").getPath();
                if (classpath.startsWith("/")) {
                    classpath = classpath.substring(1);
                }
                outputDir = new File(classpath, "output").getAbsolutePath();
            } catch (Exception e) {
                // 如果无法获取classpath，使用当前工作目录
                String currentDir = System.getProperty("user.dir");
                outputDir = new File(currentDir, "output").getAbsolutePath();
            }
        }
        
        if (configuredUploadDir != null && !configuredUploadDir.trim().isEmpty()) {
            // 处理相对路径
            if (configuredUploadDir.startsWith("./")) {
                String currentDir = System.getProperty("user.dir");
                uploadDir = new File(currentDir, configuredUploadDir.substring(2)).getAbsolutePath();
            } else {
                uploadDir = configuredUploadDir;
            }
        } else {
            // 如果没有配置，使用默认路径
            try {
                // 获取classpath路径
                String classpath = getClass().getClassLoader().getResource("").getPath();
                if (classpath.startsWith("/")) {
                    classpath = classpath.substring(1);
                }
                uploadDir = new File(classpath, "uploads").getAbsolutePath();
            } catch (Exception e) {
                // 如果无法获取classpath，使用当前工作目录
                String currentDir = System.getProperty("user.dir");
                uploadDir = new File(currentDir, "uploads").getAbsolutePath();
            }
        }
        
        // 创建目录
        new File(outputDir).mkdirs();
        new File(uploadDir).mkdirs();
        
        // 打印调试信息
        System.out.println("输出目录: " + outputDir);
        System.out.println("上传目录: " + uploadDir);
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