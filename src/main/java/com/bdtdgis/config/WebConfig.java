package com.bdtdgis.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    
    @Autowired
    private FileConfig fileConfig;
    
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 配置3DTiles文件的静态访问
        String outputDir = fileConfig.getOutputDir();
        registry.addResourceHandler("/3dtiles/**")
                .addResourceLocations("file:" + outputDir + "/");
        
        // 保持原有的output路径支持
        registry.addResourceHandler("/output/**")
                .addResourceLocations("file:" + outputDir + "/");
    }
} 