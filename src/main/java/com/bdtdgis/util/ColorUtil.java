package com.bdtdgis.util;

public class ColorUtil {
    public static float[] hexToRgbFloat(String color) {
        if (color == null) return new float[]{0.3f, 0.3f, 0.3f, 1.0f};
        color = color.trim();
        if (color.startsWith("#")) {
            color = color.substring(1);
            int r = Integer.parseInt(color.substring(0, 2), 16);
            int g = Integer.parseInt(color.substring(2, 4), 16);
            int b = Integer.parseInt(color.substring(4, 6), 16);
            float a = 1.0f;
            if (color.length() == 8) {
                a = Integer.parseInt(color.substring(6, 8), 16) / 255f;
            }
            return new float[]{r / 255f, g / 255f, b / 255f, a};
        } else if (color.startsWith("rgba")) {
            String[] parts = color.replace("rgba(", "").replace(")", "").split(",");
            if (parts.length == 4) {
                float r = Float.parseFloat(parts[0].trim()) / 255f;
                float g = Float.parseFloat(parts[1].trim()) / 255f;
                float b = Float.parseFloat(parts[2].trim()) / 255f;
                float a = Float.parseFloat(parts[3].trim());
                return new float[]{r, g, b, a};
            }
        }
        // fallback
        return new float[]{0.3f, 0.3f, 0.3f, 1.0f};
    }
} 