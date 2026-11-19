package com.bdtdgis.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.util.List;
import java.util.Map;

public class BatchTableUtil {

    /**
     * 生成batch table文件
     */
    public static void generateBatchTable(File batchTableFile, List<Map<String, Object>> attributes) {
        generateBatchTable(batchTableFile, attributes, 1000); // 默认1000个face
    }

    /**
     * 生成batch table文件（带faceCount参数）
     */
    public static void generateBatchTable(File batchTableFile, List<Map<String, Object>> attributes, int faceCount) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
            
            ObjectNode batchTable = mapper.createObjectNode();
            
            // 不自动生成batchId和name，只使用传入的属性
            int actualCount = attributes != null ? attributes.size() : faceCount;
            
            // 如果有attributes，添加所有属性（包括用户数据和系统数据）
            if (attributes != null && !attributes.isEmpty()) {
                Map<String, Object> firstAttr = attributes.get(0);
                for (String key : firstAttr.keySet()) {
                    // 保留所有属性，不排除任何字段
                    ArrayNode arr = mapper.createArrayNode();
                    for (Map<String, Object> attr : attributes) {
                        Object value = attr.get(key);
                        if (value instanceof List) {
                            ArrayNode subArr = mapper.createArrayNode();
                            for (Object sub : (List<?>) value) {
                                if (sub instanceof Number) {
                                    subArr.add(((Number) sub).doubleValue());
                                } else {
                                    subArr.add(Double.parseDouble(sub.toString()));
                                }
                            }
                            arr.add(subArr);
                        } else if (value instanceof Number) {
                            arr.add(((Number) value).doubleValue());
                        } else {
                            arr.add(value == null ? "" : value.toString());
                        }
                    }
                    batchTable.set(key, arr);
                }
            }
            
            mapper.writerWithDefaultPrettyPrinter().writeValue(batchTableFile, batchTable);
            System.out.println("生成批表文件: " + batchTableFile.getAbsolutePath() + " (数量: " + actualCount + ")");
        } catch (Exception e) {
            System.err.println("Failed to write batch table: " + batchTableFile.getAbsolutePath());
            e.printStackTrace();
            throw new RuntimeException("生成batch table失败: " + e.getMessage(), e);
        }
    }

}