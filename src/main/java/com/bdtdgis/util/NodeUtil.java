package com.bdtdgis.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class NodeUtil {
    public static boolean isNodeJsInstalled() {
        try {
            Process process = new ProcessBuilder("node", "--version").start();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    public static String findNodePath() {
        String nodePath = System.getenv("NODE_PATH");
        if (nodePath != null && !nodePath.isEmpty()) {
            File nodeExe = new File(nodePath, "node.exe");
            if (nodeExe.exists()) {
                return nodePath;
            }
        }
        try {
            Process process = new ProcessBuilder("where", "node").start();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String path = reader.readLine();
                    if (path != null && !path.trim().isEmpty()) {
                        File nodeExe = new File(path.trim());
                        String nodeDir = nodeExe.getParent();
                        return nodeDir;
                    }
                }
            }
        } catch (Exception e) {
            return null;
        }
        String[] commonPaths = {
            "C:\\Program Files\\nodejs",
            "C:\\nodejs",
            "D:\\Program Files\\nodejs",
            "D:\\nodejs"
        };
        for (String path : commonPaths) {
            File nodeExe = new File(path, "node.exe");
            if (nodeExe.exists()) {
                return path;
            }
        }
        return null;
    }

    public static String findNpxPath() {
        try {
            Process process = new ProcessBuilder("where", "npx").start();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String path = reader.readLine();
                    if (path != null && !path.trim().isEmpty()) {
                        return path.trim();
                    }
                }
            }
        } catch (Exception e) {
            return null;
        }
        String[] commonPaths = {
            "C:\\Program Files\\nodejs\\npx.cmd",
            "C:\\nodejs\\npx.cmd",
            "D:\\Program Files\\nodejs\\npx.cmd",
            "D:\\nodejs\\npx.cmd"
        };
        for (String path : commonPaths) {
            File npxExe = new File(path);
            if (npxExe.exists()) {
                return npxExe.getAbsolutePath();
            }
        }
        return null;
    }

    public static void runExternalCommand(String command) throws IOException, InterruptedException {
        try {
            System.out.println("执行命令: " + command);
            
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            StringBuilder output = new StringBuilder();
            
            // 读取输出
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    System.out.println("输出: " + line);
                }
            }
            
            // 等待进程完成，最多2分钟
            boolean completed = process.waitFor(2, TimeUnit.MINUTES);
            if (!completed) {
                process.destroyForcibly();
                throw new RuntimeException("命令执行超时（2分钟）: " + command);
            }
            
            int exitCode = process.exitValue();
            String outputText = output.toString();
            
            System.out.println("命令执行完成，退出码: " + exitCode);
            
            if (exitCode != 0) {
                if (isToolNotFoundOutput(outputText)) {
                    throw new RuntimeException("外部工具不可用：命令不存在或无法执行。退出码: " + exitCode
                            + "\n输出: " + outputText.trim());
                }
                throw new RuntimeException("命令执行失败，退出码: " + exitCode + "\n输出: " + outputText.trim());
            }
        } catch (Exception e) {
            System.err.println("执行外部命令时发生错误: " + e.getMessage());
            throw e;
        }
    }

    /**
     * 判断命令输出是否表示“工具不存在/无法执行”
     */
    public static boolean isToolNotFoundOutput(String output) {
        if (output == null) {
            return false;
        }
        String text = output.toLowerCase();
        return output.contains("不是内部或外部命令")
                || text.contains("is not recognized as an internal or external command")
                || text.contains("is not recognized")
                || output.contains("系统找不到指定的路径")
                || text.contains("the system cannot find the path specified")
                || text.contains("no such file or directory");
    }

    /**
     * 检查 obj23dtiles 是否可用
     * <p>
     * 配置值为文件路径（绝对路径或带目录分隔符）时检查文件是否存在；
     * 配置值为命令名时通过 {@link #isCommandAvailable(String)} 探测。
     *
     * @param path 配置的 obj23dtiles 路径或命令名
     * @return 是否可用
     */
    public static boolean isObj23dtilesAvailable(String path) {
        if (path == null || path.trim().isEmpty()) {
            System.out.println("obj23dtiles 不可用：未配置 obj23dtilesPath");
            return false;
        }
        String trimmed = path.trim();
        boolean looksLikePath = trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains(":");
        if (looksLikePath) {
            File file = new File(trimmed);
            if (!file.isFile()) {
                System.out.println("obj23dtiles 不可用：文件不存在 " + trimmed);
                return false;
            }
            return true;
        }
        boolean available = isCommandAvailable(trimmed);
        if (!available) {
            System.out.println("obj23dtiles 不可用：命令不存在 " + trimmed);
        }
        return available;
    }

    /**
     * 检查命令是否可用
     * @param command 命令名称
     * @return 是否可用
     */
    public static boolean isCommandAvailable(String command) {
        try {
            System.out.println("检查命令是否可用: " + command);
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", command + " --version");
            // 设置中文编码
            Map<String, String> env = pb.environment();
            env.put("PYTHONIOENCODING", "utf-8");
            env.put("PYTHONLEGACYWINDOWSSTDIO", "utf-8");
            
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();
            System.out.println("命令 " + command + " 检查结果: exitCode=" + exitCode);
            return exitCode == 0;
        } catch (Exception e) {
            System.out.println("命令 " + command + " 检查失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 查找Python的完整路径
     * @return Python的完整路径，如果找不到返回null
     */
    public static String findPythonPath() {
        try {
            Process process = new ProcessBuilder("where", "python").start();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String path = reader.readLine();
                    if (path != null && !path.trim().isEmpty()) {
                        System.out.println("找到Python路径: " + path.trim());
                        return path.trim();
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("where python 执行失败: " + e.getMessage());
        }
        
        // 如果where命令失败，尝试常见的Python安装路径
        String[] commonPaths = {
            "C:\\Python39\\python.exe",
            "C:\\Python310\\python.exe", 
            "C:\\Python311\\python.exe",
            "C:\\Users\\" + System.getProperty("user.name") + "\\AppData\\Local\\Programs\\Python\\Python39\\python.exe",
            "C:\\Users\\" + System.getProperty("user.name") + "\\AppData\\Local\\Programs\\Python\\Python310\\python.exe",
            "C:\\Users\\" + System.getProperty("user.name") + "\\AppData\\Local\\Programs\\Python\\Python311\\python.exe",
            "C:\\Program Files\\Python39\\python.exe",
            "C:\\Program Files\\Python310\\python.exe",
            "C:\\Program Files\\Python311\\python.exe"
        };
        
        for (String path : commonPaths) {
            File pythonExe = new File(path);
            if (pythonExe.exists()) {
                System.out.println("找到Python安装路径: " + path);
                return path;
            }
        }
        
        System.out.println("未找到Python路径");
        return null;
    }

    /**
     * 测试obj23dtiles命令是否可用
     * @return 是否可用
     */
    public static boolean testObj23dtilesCommand() {
        try {
            System.out.println("测试obj23dtiles命令...");
            
            // 使用完整路径调用obj23dtiles
            String obj23dtilesPath = "D:\\nodejs\\obj23dtiles.cmd";
            File obj23dtilesFile = new File(obj23dtilesPath);
            if (!obj23dtilesFile.exists()) {
                System.out.println("obj23dtiles命令文件不存在: " + obj23dtilesPath);
                return false;
            }
            
            // 构建完整的命令，确保路径正确
            String testCommand = "\"" + obj23dtilesPath + "\" --version";
            System.out.println("测试命令: " + testCommand);
            
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", testCommand);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            boolean completed = process.waitFor(30, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                System.out.println("obj23dtiles命令测试超时");
                return false;
            }
            
            int exitCode = process.exitValue();
            String outputText = output.toString().trim();
            System.out.println("obj23dtiles命令测试结果: exitCode=" + exitCode + ", output=" + outputText);
            
            return exitCode == 0 && !outputText.isEmpty();
        } catch (Exception e) {
            System.out.println("obj23dtiles命令测试失败: " + e.getMessage());
            return false;
        }
    }
} 