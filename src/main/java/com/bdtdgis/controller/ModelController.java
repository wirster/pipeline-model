package com.bdtdgis.controller;

import com.bdtdgis.config.FileConfig;
import com.bdtdgis.service.ModelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api")
public class ModelController {

	@Autowired
	private ModelService modelService;
	@Autowired
	private FileConfig fileConfig;

	// 使用FileConfig获取路径
	private String getOutputDir() {
		return fileConfig.getOutputDir();
	}

	@PostMapping("/uploadLine")
	public Map<String, Object> uploadLine(@RequestParam("file") MultipartFile file) throws Exception {
		return modelService.handleUploadLine(file);
	}

	@PostMapping("/uploadPoint")
	public Map<String, Object> uploadPoint(@RequestParam("file") MultipartFile file) throws Exception {
		return modelService.handleUploadPoint(file);
	}

	@PostMapping("/model/generate")
	public Map<String, Object> generateModel(@RequestBody Map<String, Object> config) throws Exception {
		return modelService.generateModel(config);
	}

	@PostMapping("/uploadTexture")
	public Map<String, Object> uploadTexture(@RequestParam("file") MultipartFile file) {
		Map<String, Object> resp = new HashMap<>();
		try {
			String uploadBase = fileConfig.getUploadDir();
			File texDir = new File(uploadBase, "textures");
			if (!texDir.exists()) texDir.mkdirs();
			String original = file.getOriginalFilename();
			String ext = (original != null && original.contains(".")) ? original.substring(original.lastIndexOf('.')) : "";
			String fileName = "pipe_" + System.currentTimeMillis() + ext;
			File out = new File(texDir, fileName);
			file.transferTo(out);
			resp.put("success", true);
			resp.put("fileName", out.getAbsolutePath());
			return resp;
		} catch (Exception e) {
			resp.put("success", false);
			resp.put("message", e.getMessage());
			return resp;
		}
	}

	@PostMapping("/uploadAccessory")
	public Map<String, Object> uploadAccessory(@RequestParam("file") MultipartFile file,
	                                           @RequestParam("attach") String attach,
	                                           @RequestParam(value = "attachType", required = false) String attachType) {
		Map<String, Object> resp = new HashMap<>();
		try {
			String original = file.getOriginalFilename();
			String ext = (original != null && original.contains(".")) ? original.substring(original.lastIndexOf('.')).toLowerCase() : "";
			if (!".zip".equals(ext)) {
				resp.put("success", false);
				resp.put("message", "仅支持ZIP文件，请上传包含OBJ、MTL和贴图文件的ZIP包");
				return resp;
			}
			
			// 调用ModelService处理附属物ZIP包上传（按 附属物/附属物类型 分目录）
			Map<String, Object> result = modelService.handleAccessoryUpload(file, attach, attachType);
			return result;
		} catch (Exception e) {
			resp.put("success", false);
			resp.put("message", e.getMessage());
			return resp;
		}
	}

	@GetMapping("/point/distinct-values")
	public Map<String, Object> getPointDistinctValues(@RequestParam("field") String field) {
		Map<String, Object> resp = new HashMap<>();
		try {
			List<String> values = modelService.getDistinctPointAttributeValues(field);
			resp.put("success", true);
			resp.put("values", values);
		} catch (Exception e) {
			resp.put("success", false);
			resp.put("message", e.getMessage());
		}
		return resp;
	}

	@GetMapping("/point/distinct-values-by")
	public Map<String, Object> getPointDistinctValuesBy(@RequestParam("field") String field,
	                                                  @RequestParam("byField") String byField,
	                                                  @RequestParam("byValue") String byValue) {
		Map<String, Object> resp = new HashMap<>();
		try {
			List<String> values = modelService.getDistinctPointAttributeValuesBy(field, byField, byValue);
			resp.put("success", true);
			resp.put("values", values);
		} catch (Exception e) {
			resp.put("success", false);
			resp.put("message", e.getMessage());
		}
		return resp;
	}

	@GetMapping("/download/3dtiles/status")
	public Map<String, Object> get3DTilesStatus() {
		String outputDir = getOutputDir();
		File outputFolder = new File(outputDir);
		boolean hasFiles = false;
		boolean hasLine = false;
		boolean hasPoint = false;
		if (outputFolder.exists() && outputFolder.isDirectory()) {
			// 总体是否存在任意 tileset.json
			hasFiles = hasTilesetInDirectory(outputFolder);
			// 具体类型
			File lineTileset = new File(outputFolder, "line/tileset.json");
			File pointTileset = new File(outputFolder, "point/tileset.json");
			hasLine = lineTileset.exists();
			hasPoint = pointTileset.exists();
		}
		List<String> found = new ArrayList<>();
		if (hasLine) found.add("line");
		if (hasPoint) found.add("point");
		Map<String, Object> resp = new HashMap<>();
		resp.put("success", true);
		resp.put("hasFiles", hasFiles);
		resp.put("foundFiles", found);
		resp.put("message", hasFiles ? "OK" : "未找到3DTiles文件");
		return resp;
	}

	@GetMapping("/download/3dtiles/zip")
	public ResponseEntity<FileSystemResource> download3DTilesZip() {
		try {
			String outputDir = getOutputDir();
			System.out.println("开始创建3DTiles ZIP包，输出目录: " + outputDir);

			// 检查输出目录是否存在
			File outputFolder = new File(outputDir);
			if (!outputFolder.exists() || !outputFolder.isDirectory()) {
				System.out.println("输出目录不存在: " + outputDir);
				return ResponseEntity.notFound().build();
			}

			// 检查是否有3DTiles文件
			boolean hasTilesetFiles = false;
			File[] subDirs = outputFolder.listFiles(File::isDirectory);
			if (subDirs != null) {
				for (File subDir : subDirs) {
					if (hasTilesetInDirectory(subDir)) {
						hasTilesetFiles = true;
						break;
					}
				}
			}

			if (!hasTilesetFiles) {
				System.out.println("未找到3DTiles文件");
				return ResponseEntity.notFound().build();
			}

			String zipPath = outputDir + "/3dtiles.zip";
			System.out.println("创建ZIP文件: " + zipPath);

			// 删除已存在的ZIP文件
			File existingZip = new File(zipPath);
			if (existingZip.exists()) {
				existingZip.delete();
				System.out.println("删除已存在的ZIP文件");
			}

			// 创建ZIP文件（排除正在生成的zip自身）
			File zipFileToCreate = new File(zipPath);
			try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFileToCreate))) {
				addDirectoryToZip(outputFolder, outputFolder, zos, zipFileToCreate);
				System.out.println("ZIP文件创建完成");
			}

			File zipFile = new File(zipPath);
			if (zipFile.exists() && zipFile.length() > 0) {
				System.out.println("ZIP文件大小: " + zipFile.length() + " bytes");
				FileSystemResource resource = new FileSystemResource(zipFile);
				return ResponseEntity.ok()
						.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"3dtiles.zip\"")
						.header(HttpHeaders.CONTENT_TYPE, "application/zip")
						.header(HttpHeaders.CONTENT_LENGTH, String.valueOf(zipFile.length()))
						.body(resource);
			} else {
				System.out.println("ZIP文件创建失败或为空");
				return ResponseEntity.notFound().build();
			}

		} catch (Exception e) {
			System.err.println("创建ZIP文件时发生错误: " + e.getMessage());
			e.printStackTrace();
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
		}
	}

	// 静态资源代理：获取3D Tiles文件
	@GetMapping("/output/**")
	public ResponseEntity<Resource> getTilesetFile(HttpServletRequest request) {
		try {
			// 获取请求的路径
			String requestURI = request.getRequestURI();
			String filePath = requestURI.substring(requestURI.indexOf("/output/"));

			// 构建实际文件路径
			String actualPath = fileConfig.getOutputDir() + filePath.substring(7); // 去掉 "/output/"
			File file = new File(actualPath);

			if (!file.exists()) {
				return ResponseEntity.notFound().build();
			}
			// 根据文件扩展名设置Content-Type
			String contentType = "application/octet-stream";
			if (filePath.endsWith(".json")) {
				contentType = "application/json";
			} else if (filePath.endsWith(".b3dm")) {
				contentType = "application/octet-stream";
			}

			// 创建资源
			Resource resource = new FileSystemResource(file);

			return ResponseEntity.ok()
					.contentType(MediaType.parseMediaType(contentType))
					.body(resource);

		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
		}
	}

	@GetMapping("/point/attach-values")
	public Map<String, Object> getPointAttachValues(@RequestParam("field") String field) {
		Map<String, Object> resp = new HashMap<>();
		try {
			List<String> values = modelService.getDistinctPointAttributeValues(field);
			resp.put("success", true);
			resp.put("values", values);
		} catch (Exception e) {
			resp.put("success", false);
			resp.put("message", e.getMessage());
		}
		return resp;
	}

	@GetMapping("/point/feature-values")
	public Map<String, Object> getPointFeatureValues(@RequestParam("field") String field) {
		Map<String, Object> resp = new HashMap<>();
		try {
			List<String> values = modelService.getDistinctPointAttributeValues(field);
			resp.put("success", true);
			resp.put("values", values);
		} catch (Exception e) {
			resp.put("success", false);
			resp.put("message", e.getMessage());
		}
		return resp;
	}

	@PostMapping("/uploadFeature")
	public Map<String, Object> uploadFeature(@RequestParam("file") MultipartFile file,
                                             @RequestParam("field") String field,
                                             @RequestParam("value") String value,
                                             @RequestParam(value = "variant", required = false) String variant) {
		Map<String, Object> resp = new HashMap<>();
		try {
			Map<String, Object> result = modelService.handleFeatureUpload(file, field, value, variant);
			return result;
		} catch (Exception e) {
			resp.put("success", false);
			resp.put("message", e.getMessage());
			return resp;
		}
	}

	// 递归添加目录到ZIP文件，支持排除某个文件（如正在创建的zip文件本身）
	private void addDirectoryToZip(File baseDir, File dir, ZipOutputStream zos, File excludeFile) throws Exception {
		File[] files = dir.listFiles();
		if (files != null) {
			for (File file : files) {
				// 跳过正在创建的zip文件本身，避免zip嵌套
				if (excludeFile != null) {
					try {
						if (file.getCanonicalPath().equals(excludeFile.getCanonicalPath())) {
							continue;
						}
					} catch (Exception ignore) {}
				}
				if (file.isDirectory()) {
					// 递归处理子目录
					addDirectoryToZip(baseDir, file, zos, excludeFile);
				} else {
					// 添加文件到ZIP
					String relativePath = getRelativePath(baseDir, file);

					ZipEntry entry = new ZipEntry(relativePath);
					zos.putNextEntry(entry);

					try (FileInputStream fis = new FileInputStream(file)) {
						byte[] buffer = new byte[8192];
						int length;
						while ((length = fis.read(buffer)) > 0) {
							zos.write(buffer, 0, length);
						}
					}
					zos.closeEntry();
				}
			}
		}
	}

	// 获取相对路径
	private String getRelativePath(File baseDir, File file) {
		String basePath = baseDir.getAbsolutePath();
		String filePath = file.getAbsolutePath();

		if (filePath.startsWith(basePath)) {
			String relativePath = filePath.substring(basePath.length());
			// 移除开头的路径分隔符
			if (relativePath.startsWith(File.separator)) {
				relativePath = relativePath.substring(1);
			}
			// 将Windows路径分隔符转换为ZIP标准的分隔符
			return relativePath.replace(File.separator, "/");
		}
		return file.getName();
	}

	// 检查目录中是否有tileset.json文件
	private boolean hasTilesetInDirectory(File dir) {
		File[] files = dir.listFiles();
		if (files != null) {
			for (File file : files) {
				if (file.isFile() && file.getName().equals("tileset.json")) {
					return true;
				} else if (file.isDirectory()) {
					if (hasTilesetInDirectory(file)) {
						return true;
					}
				}
			}
		}
		return false;
	}
} 