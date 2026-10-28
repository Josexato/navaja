package com.josexato.navaja.measure.core;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * La reconstrucción del teléfono en el formato de texto de COLMAP
 * (cameras.txt, images.txt, points3D.txt) más un poses.json legible y las
 * siluetas como máscaras PNG. Con eso un servidor parte de las poses ya
 * calculadas: sólo las afina (triangulación y ajuste de haces) en vez de
 * buscarlas de cero, y puede densificar o entrenar 2D-GS directamente.
 *
 * <p>Convenciones de COLMAP: x_cámara = R·X + t (como {@link Geometry.Pose}),
 * cámara mirando a +z con y hacia abajo, y el centro del píxel superior
 * izquierdo en (0,5; 0,5); por eso las coordenadas de imagen y el punto
 * principal llevan +0,5 respecto de las de Navaja (centro del píxel en 0).
 */
public final class Colmap {

    private Colmap() { }

    /** Los archivos de texto: {cameras.txt, images.txt, points3D.txt}. */
    public static String[] text(Sfm.Result r, String[] names) {
        double cx = r.width / 2.0 + 0.5, cy = r.height / 2.0 + 0.5;
        StringBuilder cam = new StringBuilder("# Navaja: una cámara (sin distorsión)\n# CAMERA_ID, MODEL, WIDTH, HEIGHT, PARAMS[]\n");
        cam.append(String.format(Locale.ROOT, "1 SIMPLE_PINHOLE %d %d %.6f %.6f %.6f\n", r.width, r.height, r.focal, cx, cy));
        // Observaciones de cada punto con pista en cada foto, con su índice dentro de la foto
        int np = Math.min(r.pointTracks.size(), r.points.length), nc = r.poses.size();
        List<List<int[]>> track = new ArrayList<>(); // por punto: {imagen, índice del punto 2D}
        for (int i = 0; i < np; i++) track.add(new ArrayList<>());
        StringBuilder img = new StringBuilder("# IMAGE_ID, QW, QX, QY, QZ, TX, TY, TZ, CAMERA_ID, NAME\n# POINTS2D[] as (X, Y, POINT3D_ID)\n");
        for (int c = 0; c < nc; c++) {
            Geometry.Pose p = r.poses.get(c);
            double[] q = quaternion(p.r);
            img.append(String.format(Locale.ROOT, "%d %.9f %.9f %.9f %.9f %.9f %.9f %.9f 1 %s\n", c + 1, q[0], q[1], q[2], q[3], p.t[0], p.t[1], p.t[2],
                    names[c]));
            int fr = r.camFrames.get(c), k = 0;
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < np; i++)
                for (Sfm.Track t : r.pointTracks.get(i))
                    if (t.visible(fr)) {
                        if (line.length() > 0) line.append(' ');
                        line.append(String.format(Locale.ROOT, "%.2f %.2f %d", t.x(fr) + 0.5, t.y(fr) + 0.5, i + 1));
                        track.get(i).add(new int[]{c + 1, k++});
                        break;
                    }
            img.append(line).append('\n');
        }
        StringBuilder pts = new StringBuilder("# POINT3D_ID, X, Y, Z, R, G, B, ERROR, TRACK[] as (IMAGE_ID, POINT2D_IDX)\n");
        for (int i = 0; i < np; i++) {
            if (track.get(i).size() < 2) continue;
            double[] x = r.points[i];
            int col = r.colors[i];
            pts.append(String.format(Locale.ROOT, "%d %.6f %.6f %.6f %d %d %d %.3f", i + 1, x[0], x[1], x[2], (col >> 16) & 255, (col >> 8) & 255, col & 255, r.rms));
            for (int[] o : track.get(i)) pts.append(' ').append(o[0]).append(' ').append(o[1]);
            pts.append('\n');
        }
        return new String[]{cam.toString(), img.toString(), pts.toString()};
    }

    /** Cuaternión (w, x, y, z) de una rotación (matriz 3×3 por filas), con w ≥ 0 como usa COLMAP. */
    static double[] quaternion(double[] m) {
        double tr = m[0] + m[4] + m[8], w, x, y, z;
        if (tr > 0) {
            double s = Math.sqrt(tr + 1) * 2;
            w = s / 4;
            x = (m[7] - m[5]) / s;
            y = (m[2] - m[6]) / s;
            z = (m[3] - m[1]) / s;
        } else if (m[0] > m[4] && m[0] > m[8]) {
            double s = Math.sqrt(1 + m[0] - m[4] - m[8]) * 2;
            w = (m[7] - m[5]) / s;
            x = s / 4;
            y = (m[1] + m[3]) / s;
            z = (m[2] + m[6]) / s;
        } else if (m[4] > m[8]) {
            double s = Math.sqrt(1 + m[4] - m[0] - m[8]) * 2;
            w = (m[2] - m[6]) / s;
            x = (m[1] + m[3]) / s;
            y = s / 4;
            z = (m[5] + m[7]) / s;
        } else {
            double s = Math.sqrt(1 + m[8] - m[0] - m[4]) * 2;
            w = (m[3] - m[1]) / s;
            x = (m[2] + m[6]) / s;
            y = (m[5] + m[7]) / s;
            z = s / 4;
        }
        double n = Math.sqrt(w * w + x * x + y * y + z * z), sg = w < 0 ? -1 : 1;
        return new double[]{sg * w / n, sg * x / n, sg * y / n, sg * z / n};
    }

    /**
     * poses.json: cámara, y por foto su archivo, cuadro, tiempo, R y t
     * (x_cámara = R·X + t), centro, nitidez y si su silueta se usó; más la
     * escala (cm por unidad, 0 si no se conoce) y la caja del objeto.
     */
    public static String json(Sfm.Result r, String[] names, double fps, List<Float> sharpness, byte[][] masksUsed, Measure.Box b, double cmPerUnit) {
        StringBuilder s = new StringBuilder("{\n");
        s.append("  \"formato\": \"navaja-poses-1\",\n");
        s.append("  \"convencion\": \"x_camara = R * X + t; camara mira a +z, y hacia abajo; pixeles con el centro del primero en (0, 0)\",\n");
        s.append(String.format(Locale.ROOT, "  \"cm_por_unidad\": %.6f,\n", cmPerUnit));
        s.append(String.format(Locale.ROOT, "  \"camara\": {\"modelo\": \"pinhole\", \"ancho\": %d, \"alto\": %d, \"f\": %.6f, \"cx\": %.3f, \"cy\": %.3f},\n",
                r.width, r.height, r.focal, r.width / 2.0, r.height / 2.0));
        s.append(String.format(Locale.ROOT, "  \"error_reproyeccion_px\": %.4f,\n", r.rms));
        if (b != null) {
            s.append("  \"objeto\": {");
            s.append("\"centro\": ").append(vec(b.center)).append(", \"eje_ancho\": ").append(vec(b.axisW)).append(", \"eje_alto\": ").append(vec(b.axisH))
                    .append(", \"eje_prof\": ").append(vec(b.axisD));
            s.append(String.format(Locale.ROOT, ", \"alto\": %.6f, \"ancho\": %.6f, \"prof\": %.6f, \"en_pared\": %b", b.alto, b.ancho, b.prof, b.onWall));
            s.append(", \"plano_normal\": ").append(vec(b.planeN)).append(", \"plano_punto\": ").append(vec(b.planeP)).append("},\n");
        }
        s.append("  \"fotos\": [\n");
        for (int c = 0; c < r.poses.size(); c++) {
            Geometry.Pose p = r.poses.get(c);
            int fr = r.camFrames.get(c);
            s.append("    {\"archivo\": \"").append(names[c]).append("\", \"cuadro\": ").append(fr);
            s.append(String.format(Locale.ROOT, ", \"tiempo_s\": %.3f", fr / fps));
            s.append(", \"R\": ").append(vec(p.r)).append(", \"t\": ").append(vec(p.t)).append(", \"centro\": ").append(vec(p.center()));
            if (sharpness != null && fr < sharpness.size()) s.append(String.format(Locale.ROOT, ", \"nitidez\": %.2f", sharpness.get(fr)));
            if (masksUsed != null) s.append(", \"silueta\": ").append(masksUsed[c] != null);
            s.append(c + 1 < r.poses.size() ? "},\n" : "}\n");
        }
        s.append("  ]\n}\n");
        return s.toString();
    }

    private static String vec(double[] v) {
        StringBuilder s = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) s.append(i > 0 ? ", " : "").append(String.format(Locale.ROOT, "%.9g", v[i]));
        return s.append(']').toString();
    }

    /**
     * Máscara como PNG en grises de {@code w}×{@code h} (el tamaño de la foto,
     * como pide COLMAP), ampliando la silueta de {@code mw}×{@code mh}:
     * blanco = objeto, negro = fondo.
     */
    public static byte[] maskPng(byte[] m, int mw, int mh, int w, int h) throws IOException {
        byte[] raw = new byte[(w + 1) * h];
        for (int y = 0; y < h; y++) {
            raw[y * (w + 1)] = 0; // filtro "ninguno"
            int my = Math.min(mh - 1, y * mh / h);
            for (int x = 0; x < w; x++) raw[y * (w + 1) + 1 + x] = m[my * mw + Math.min(mw - 1, x * mw / w)] != 0 ? (byte) 255 : 0;
        }
        Deflater d = new Deflater(Deflater.BEST_SPEED);
        d.setInput(raw);
        d.finish();
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        while (!d.finished()) z.write(buf, 0, d.deflate(buf));
        d.end();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bo);
        out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        ByteArrayOutputStream ih = new ByteArrayOutputStream();
        DataOutputStream ihd = new DataOutputStream(ih);
        ihd.writeInt(w);
        ihd.writeInt(h);
        ihd.write(new byte[]{8, 0, 0, 0, 0}); // 8 bits, grises, sin entrelazado
        chunk(out, "IHDR", ih.toByteArray());
        chunk(out, "IDAT", z.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return bo.toByteArray();
    }

    private static void chunk(DataOutputStream out, String type, byte[] data) throws IOException {
        byte[] t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.writeInt(data.length);
        out.write(t);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        out.writeInt((int) crc.getValue());
    }
}
