package com.bdtdgis.util;

import com.bdtdgis.model.Vertex;
import org.locationtech.jts.geom.Coordinate;

public class MathUtil {
    public static boolean isValidCoordinate(Coordinate coord) {
        return coord != null &&
                !Double.isNaN(coord.x) && !Double.isInfinite(coord.x) &&
                !Double.isNaN(coord.y) && !Double.isInfinite(coord.y) &&
                !Double.isNaN(coord.z) && !Double.isInfinite(coord.z);
    }
    public static boolean isValidValue(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
    public static float[] subtract(Vertex a, Vertex b) {
        return new float[]{(float)(a.x - b.x), (float)(a.y - b.y), (float)(a.z - b.z)};
    }
    public static float[] cross(float[] a, float[] b) {
        return new float[]{
            a[1]*b[2] - a[2]*b[1],
            a[2]*b[0] - a[0]*b[2],
            a[0]*b[1] - a[1]*b[0]
        };
    }
    public static void add(float[] a, float[] b) {
        a[0] += b[0]; a[1] += b[1]; a[2] += b[2];
    }
    public static float[] normalize(float[] v) {
        float len = (float)Math.sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2]);
        if (len > 1e-8) {
            return new float[]{v[0]/len, v[1]/len, v[2]/len};
        } else {
            return new float[]{0,0,1};
        }
    }
    public static double validateCoordinate(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return value;
    }
} 