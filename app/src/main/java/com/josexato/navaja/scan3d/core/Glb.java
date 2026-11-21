package com.josexato.navaja.scan3d.core;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Escribe un modelo como GLB (glTF 2.0 binario, un solo archivo): la malla,
 * sus normales y, si hay, la textura (JPEG adentro) o el color por vértice.
 * Es el formato que abren directo Blender, los visores de Windows y Android
 * y los motores de juego. Java puro.
 *
 * <p>glTF usa metros e Y hacia arriba: las posiciones se escalan con
 * {@code toMeters} (0,001 si vienen en mm) y, si la malla viene con Z hacia
 * arriba, se gira. Con textura, cada esquina de triángulo lleva su propia
 * coordenada UV, así que los vértices se escriben repetidos (3 por
 * triángulo, sin índices).
 */
public final class Glb {

    private Glb() { }

    /**
     * @param pos      3 por vértice
     * @param tri      3 índices por triángulo
     * @param cornerUv 6 por triángulo (u0 v0 u1 v1 u2 v2, con v = 0 abajo como en OBJ), o null
     * @param jpeg     la textura (JPEG), o null
     * @param col      color por vértice (3 por vértice, 0..1), o null; se usa sólo sin textura
     * @param zUp      si la malla viene con Z hacia arriba (se pasa a Y hacia arriba)
     * @param toMeters factor de las unidades de {@code pos} a metros
     */
    public static byte[] write(float[] pos, int[] tri, float[] cornerUv, byte[] jpeg, float[] col, boolean zUp, double toMeters, String name) {
        boolean tex = cornerUv != null && jpeg != null;
        boolean color = !tex && col != null;
        int nTri = tri.length / 3, nv = tex ? 3 * nTri : pos.length / 3;
        float[] p = new float[3 * nv], n = new float[3 * nv], uv = tex ? new float[2 * nv] : null, c = color ? new float[3 * nv] : null;
        int[] idx = tex ? null : tri;
        if (tex) {
            for (int t = 0; t < nTri; t++)
                for (int k = 0; k < 3; k++) {
                    int v = tri[3 * t + k], o = 3 * t + k;
                    put(p, o, pos, v, zUp, toMeters);
                    uv[2 * o] = cornerUv[6 * t + 2 * k];
                    uv[2 * o + 1] = 1 - cornerUv[6 * t + 2 * k + 1]; // glTF: v = 0 arriba
                }
        } else {
            for (int v = 0; v < nv; v++) put(p, v, pos, v, zUp, toMeters);
            if (color) for (int i = 0; i < c.length; i++) c[i] = Math.max(0, Math.min(1, col[i]));
        }
        normals(p, idx, n);
        float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < p.length; i++) {
            lo[i % 3] = Math.min(lo[i % 3], p[i]);
            hi[i % 3] = Math.max(hi[i % 3], p[i]);
        }
        // Binario: posiciones, normales, [uv | color], [índices], [imagen]; cada tramo alineado a 4 bytes
        ByteArrayOutputStream bin = new ByteArrayOutputStream();
        StringBuilder views = new StringBuilder(), acc = new StringBuilder(), attrs = new StringBuilder();
        int view = 0;
        int off = floats(bin, p);
        views.append(view(off, 4 * p.length, 34962));
        acc.append(String.format(Locale.ROOT, "{\"bufferView\":%d,\"componentType\":5126,\"count\":%d,\"type\":\"VEC3\",\"min\":[%s],\"max\":[%s]}", view, nv,
                vec(lo), vec(hi)));
        attrs.append("\"POSITION\":").append(view++);
        off = floats(bin, n);
        views.append(',').append(view(off, 4 * n.length, 34962));
        acc.append(String.format(Locale.ROOT, ",{\"bufferView\":%d,\"componentType\":5126,\"count\":%d,\"type\":\"VEC3\"}", view, nv));
        attrs.append(",\"NORMAL\":").append(view++);
        if (tex) {
            off = floats(bin, uv);
            views.append(',').append(view(off, 4 * uv.length, 34962));
            acc.append(String.format(Locale.ROOT, ",{\"bufferView\":%d,\"componentType\":5126,\"count\":%d,\"type\":\"VEC2\"}", view, nv));
            attrs.append(",\"TEXCOORD_0\":").append(view++);
        } else if (color) {
            off = floats(bin, c);
            views.append(',').append(view(off, 4 * c.length, 34962));
            acc.append(String.format(Locale.ROOT, ",{\"bufferView\":%d,\"componentType\":5126,\"count\":%d,\"type\":\"VEC3\"}", view, nv));
            attrs.append(",\"COLOR_0\":").append(view++);
        }
        String indices = "";
        if (idx != null) {
            off = bin.size();
            ByteBuffer b = ByteBuffer.allocate(4 * idx.length).order(ByteOrder.LITTLE_ENDIAN);
            for (int i : idx) b.putInt(i);
            bin.write(b.array(), 0, b.capacity());
            views.append(',').append(view(off, 4 * idx.length, 34963));
            acc.append(String.format(Locale.ROOT, ",{\"bufferView\":%d,\"componentType\":5125,\"count\":%d,\"type\":\"SCALAR\"}", view, idx.length));
            indices = ",\"indices\":" + view++;
        }
        String images = "", material;
        if (tex) {
            off = bin.size();
            bin.write(jpeg, 0, jpeg.length);
            while (bin.size() % 4 != 0) bin.write(0);
            views.append(',').append(String.format(Locale.ROOT, "{\"buffer\":0,\"byteOffset\":%d,\"byteLength\":%d}", off, jpeg.length));
            images = ",\"images\":[{\"bufferView\":" + view + ",\"mimeType\":\"image/jpeg\"}],\"textures\":[{\"source\":0,\"sampler\":0}],"
                    + "\"samplers\":[{\"magFilter\":9729,\"minFilter\":9987,\"wrapS\":33071,\"wrapT\":33071}]";
            material = "{\"name\":\"textura\",\"pbrMetallicRoughness\":{\"baseColorTexture\":{\"index\":0},\"metallicFactor\":0,\"roughnessFactor\":1}}";
        } else {
            material = "{\"name\":\"" + (color ? "color" : "gris") + "\",\"pbrMetallicRoughness\":{\"baseColorFactor\":[" + (color ? "1,1,1,1" : "0.7,0.7,0.7,1")
                    + "],\"metallicFactor\":0,\"roughnessFactor\":1}}";
        }
        String safe = name.replaceAll("[\"\\\\]", "");
        String json = "{\"asset\":{\"version\":\"2.0\",\"generator\":\"Navaja\"},\"scene\":0,\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{\"mesh\":0,\"name\":\"" + safe
                + "\"}],\"meshes\":[{\"name\":\"" + safe + "\",\"primitives\":[{\"attributes\":{" + attrs + "}" + indices + ",\"material\":0,\"mode\":4}]}],"
                + "\"materials\":[" + material + "]" + images + ",\"accessors\":[" + acc + "],\"bufferViews\":[" + views + "],\"buffers\":[{\"byteLength\":"
                + bin.size() + "}]}";
        byte[] jb = json.getBytes(StandardCharsets.UTF_8);
        int jpad = (4 - jb.length % 4) % 4, total = 12 + 8 + jb.length + jpad + 8 + bin.size();
        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x46546C67).putInt(2).putInt(total); // "glTF", versión 2
        out.putInt(jb.length + jpad).putInt(0x4E4F534A).put(jb); // JSON
        for (int i = 0; i < jpad; i++) out.put((byte) ' ');
        out.putInt(bin.size()).putInt(0x004E4942).put(bin.toByteArray()); // BIN
        return out.array();
    }

    private static void put(float[] dst, int o, float[] pos, int v, boolean zUp, double s) {
        float x = pos[3 * v], y = pos[3 * v + 1], z = pos[3 * v + 2];
        dst[3 * o] = (float) (x * s);
        dst[3 * o + 1] = (float) ((zUp ? z : y) * s);
        dst[3 * o + 2] = (float) ((zUp ? -y : z) * s);
    }

    /** Normales por vértice (suma de las normales de sus triángulos, normalizada); sin índices, cada 3 vértices son un triángulo. */
    private static void normals(float[] p, int[] idx, float[] n) {
        int nTri = idx == null ? p.length / 9 : idx.length / 3;
        for (int t = 0; t < nTri; t++) {
            int a = idx == null ? 3 * t : idx[3 * t], b = idx == null ? 3 * t + 1 : idx[3 * t + 1], c = idx == null ? 3 * t + 2 : idx[3 * t + 2];
            float ux = p[3 * b] - p[3 * a], uy = p[3 * b + 1] - p[3 * a + 1], uz = p[3 * b + 2] - p[3 * a + 2];
            float vx = p[3 * c] - p[3 * a], vy = p[3 * c + 1] - p[3 * a + 1], vz = p[3 * c + 2] - p[3 * a + 2];
            float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            for (int v : new int[]{a, b, c}) {
                n[3 * v] += nx;
                n[3 * v + 1] += ny;
                n[3 * v + 2] += nz;
            }
        }
        for (int v = 0; v < n.length / 3; v++) {
            double l = Math.sqrt(n[3 * v] * n[3 * v] + n[3 * v + 1] * n[3 * v + 1] + n[3 * v + 2] * n[3 * v + 2]);
            if (l < 1e-20) { n[3 * v] = 0; n[3 * v + 1] = 1; n[3 * v + 2] = 0; continue; } // glTF exige normales unitarias
            n[3 * v] /= l;
            n[3 * v + 1] /= l;
            n[3 * v + 2] /= l;
        }
    }

    private static int floats(ByteArrayOutputStream bin, float[] v) {
        int off = bin.size();
        ByteBuffer b = ByteBuffer.allocate(4 * v.length).order(ByteOrder.LITTLE_ENDIAN);
        b.asFloatBuffer().put(v);
        bin.write(b.array(), 0, b.capacity());
        return off;
    }

    private static String view(int off, int len, int target) {
        return String.format(Locale.ROOT, "{\"buffer\":0,\"byteOffset\":%d,\"byteLength\":%d,\"target\":%d}", off, len, target);
    }

    private static String vec(float[] v) { return String.format(Locale.ROOT, "%.7g,%.7g,%.7g", v[0], v[1], v[2]); }
}
