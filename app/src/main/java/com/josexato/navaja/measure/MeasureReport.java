package com.josexato.navaja.measure;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import com.josexato.navaja.measure.core.Carve;
import com.josexato.navaja.measure.core.Lin;
import com.josexato.navaja.measure.core.Measure;
import com.josexato.navaja.measure.core.Motion;
import com.josexato.navaja.measure.core.Sfm;
import com.josexato.navaja.scan3d.core.Mesh;
import com.josexato.navaja.scan3d.core.SurfaceNets;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Todo lo de una medición en un ZIP: informe paso a paso, nube de puntos
 * (PLY), volumen (STL), las fotos (cuadros clave) usadas y, en modo
 * depuración, cada foto con la silueta y los cinco puntos clave dibujados.
 */
final class MeasureReport {

    private MeasureReport() { }

    /** Lo que hace falta para el informe. */
    static final class Data {
        Measure.Choice choice;
        Carve.Result hull;
        /** cm por unidad del modelo (0 = sin medida conocida) y cm por unidad del casco. */
        double scaleCm, hullCmPerUnit;
        String knownName;
        double videoSeconds, fps;
        int tracks;
        /** Una línea por tramo: desde, hasta (s), cámaras y puntos. */
        List<double[]> segments = new ArrayList<>();
        /** Por cuadro analizado: puntos activos, perdidos y nuevos. */
        List<int[]> perFrame = new ArrayList<>();
        /** Fotos de los cuadros clave (mismo orden que las cámaras); null si falta alguna. */
        File[] photos;
        byte[][] masks;
        int maskW, maskH;
    }

    private static final int[] COLORS = {0xFFFF5252, 0xFF40C4FF, 0xFFFFD740, 0xFF69F0AE, 0xFFE040FB};

    static void write(OutputStream os, Data d, boolean debug) throws IOException {
        Sfm.Result r = d.choice.result;
        Measure.Box b = d.choice.box;
        int[] lm = Measure.landmarks(r, b, d.hull);
        try (ZipOutputStream zip = new ZipOutputStream(os)) {
            put(zip, "informe.md", report(d, lm).getBytes(StandardCharsets.UTF_8));
            put(zip, "nube_objeto.ply", ply(r, b, d.scaleCm, true));
            put(zip, "nube_completa.ply", ply(r, b, d.scaleCm, false));
            if (d.hull != null) put(zip, "volumen.stl", stl(d));
            for (int c = 0; d.photos != null && c < d.photos.length; c++) {
                File f = d.photos[c];
                if (f == null || !f.exists()) continue;
                zip.putNextEntry(new ZipEntry("fotos/" + f.getName()));
                try (InputStream in = new FileInputStream(f)) {
                    byte[] buf = new byte[1 << 16];
                    for (int n; (n = in.read(buf)) > 0; ) zip.write(buf, 0, n);
                }
                zip.closeEntry();
                if (debug) put(zip, "depuracion/" + f.getName(), annotate(f, d, c, lm));
            }
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    // ------------------------------------------------------------ informe

    private static String report(Data d, int[] lm) {
        Sfm.Result r = d.choice.result;
        Measure.Box b = d.choice.box;
        double k = d.scaleCm > 0 ? d.scaleCm : 1;
        String u = d.scaleCm > 0 ? "cm" : "u";
        StringBuilder s = new StringBuilder();
        s.append("# Escáner 3D desde video — informe\n\n");
        s.append("Generado por Navaja el ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date())).append(".\n");
        s.append(d.scaleCm > 0 ? "Medidas en cm (escala con " + d.knownName + ").\n"
                : "Sin medida conocida: las longitudes están en unidades del modelo (u), sólo valen las proporciones.\n");
        s.append("\n## 1. Video\n\n");
        s.append(String.format(Locale.ROOT, "- Duración: %.1f s · se analizan %.0f cuadros por segundo, %d × %d px.\n", d.videoSeconds, d.fps,
                r.width, r.height));
        s.append("\n## 2. Seguimiento de puntos y tramos\n\n");
        s.append("- Puntos seguidos cuadro a cuadro (pistas): ").append(d.tracks).append(".\n");
        s.append("- Si el seguimiento se corta (imagen movida, algo tapa la vista), el video se parte en tramos y se usa el mejor.\n\n");
        s.append("| Tramo | Desde | Hasta | Posiciones del celular | Puntos 3D | Hueco hasta el siguiente | Puntos que cruzan el corte | |\n"
                + "|---|---|---|---|---|---|---|---|\n");
        for (int i = 0; i < d.segments.size(); i++) {
            double[] g = d.segments.get(i);
            boolean last = i + 1 >= d.segments.size();
            s.append(String.format(Locale.ROOT, "| %d | %.1f s | %.1f s | %d | %d | %s | %s | %s |\n", i, g[0], g[1], (int) g[2], (int) g[3],
                    last ? "—" : String.format(Locale.ROOT, "%.1f s", d.segments.get(i + 1)[0] - g[1]),
                    last || g.length < 5 ? "—" : String.valueOf((int) g[4]), i == d.choice.segment ? "**usado**" : ""));
        }
        double usedS = (r.endFrame - r.startFrame) / d.fps;
        s.append(String.format(Locale.ROOT, "\n**Video usado: %.1f s de %.1f s (%.0f %%).** La medida usa un solo tramo, el mejor; los otros se "
                + "descartan, porque en un corte se pierden casi todos los puntos y no hay cómo alinear los tramos entre sí.\n",
                usedS, d.videoSeconds, d.videoSeconds > 0 ? 100 * usedS / d.videoSeconds : 0));
        // Seguimiento segundo a segundo: dónde se perdieron puntos (movimiento brusco o imagen movida)
        if (!d.perFrame.isEmpty()) {
            s.append("\n### Seguimiento segundo a segundo\n\n");
            s.append("Puntos activos (el mínimo de ese segundo), perdidos y nuevos, y el peor cuadro: qué parte de los puntos se perdió de un "
                    + "cuadro al siguiente. Si es alto (⚠, 40 % o más), el celular se movió bruscamente o la imagen salió movida.\n\n");
            s.append("| Segundo | Activos | Perdidos | Nuevos | Peor cuadro | |\n|---|---|---|---|---|---|\n");
            for (double[] row : Motion.perSecond(d.perFrame, d.fps)) {
                double t0 = row[0], t1 = row[0] + 1;
                String mark = "";
                for (int i = 0; i < d.segments.size(); i++) {
                    double[] g = d.segments.get(i);
                    if (g[1] >= t0 && g[1] < t1 && i + 1 < d.segments.size()) mark += "corte · ";
                }
                if (t1 > r.startFrame / d.fps && t0 <= r.endFrame / d.fps) mark += "usado";
                s.append(String.format(Locale.ROOT, "| %d | %d | %d | %d | %s%.0f %% | %s |\n", (int) row[0], (int) row[1], (int) row[2],
                        (int) row[3], row[4] >= 0.4 ? "⚠ " : "", 100 * row[4], mark.endsWith(" · ") ? mark.substring(0, mark.length() - 3) : mark));
            }
        }
        s.append("\n## 3. Fotos usadas (carpeta `fotos/`)\n\n");
        s.append("Cada foto es un cuadro clave del tramo usado: ahí se ubicó el celular. Posición del celular en ejes del objeto ")
                .append("(ancho, ").append(yName(b)).append(", distancia a la ").append(b.onWall ? "pared" : "mesa").append(").\n\n");
        s.append("Desde la anterior: tiempo, puntos 3D vistos en las dos fotos y cuánto giró el celular (en grados y grados por segundo).\n\n");
        s.append("| Foto | Tiempo | Puntos vistos | Desde la anterior | En común | Giro | Silueta | Celular (").append(u).append(") |\n"
                + "|---|---|---|---|---|---|---|---|\n");
        java.util.Map<Integer, Motion.Step> stepTo = new java.util.HashMap<>();
        for (Motion.Step st : Motion.steps(r)) stepTo.put(st.toFrame, st);
        for (int c : byTime(r)) {
            int fr = r.camFrames.get(c);
            Motion.Step st = stepTo.get(fr);
            int seen = 0;
            for (List<Sfm.Track> tl : r.pointTracks)
                for (Sfm.Track t : tl) if (t.visible(fr)) { seen++; break; }
            double[] p = boxCoords(b, r.poses.get(c).center());
            s.append(String.format(Locale.ROOT, "| %s | %.2f s | %d | %s | %s | %s | %s | %.1f, %.1f, %.1f |\n", photoName(fr), fr / d.fps, seen,
                    st == null ? "—" : String.format(Locale.ROOT, "%.2f s", st.seconds(d.fps)), st == null ? "—" : String.valueOf(st.shared),
                    st == null ? "—" : String.format(Locale.ROOT, "%.1f° (%.0f°/s)", st.turnDeg, st.turnPerSecond(d.fps)),
                    d.masks != null && d.masks[c] != null ? "sí" : "no", p[0] * k, p[1] * k, p[2] * k));
        }
        s.append("\n## 4. Puntos\n\n");
        s.append(String.format(Locale.ROOT, "- Nube: %d puntos (%d seguidos con pistas, el resto de la segunda pasada densa o de zonas reprocesadas).\n",
                r.points.length, r.pointTracks.size()));
        s.append(String.format(Locale.ROOT, "- Del objeto: %d · error de reproyección %.2f px.\n", b.objectPoints.length, r.rms));
        s.append("\n## 5. Medidas\n\n");
        if (b.onWall)
            s.append(String.format(Locale.ROOT, "- Por puntos: alto %.1f · ancho %.1f · de la pared al frente %.1f %s (profundidad %.1f + separación %.1f).\n",
                    b.alto * k, b.ancho * k, (b.separation + b.prof) * k, u, b.prof * k, b.separation * k));
        else
            s.append(String.format(Locale.ROOT, "- Por puntos: alto (del apoyo al tope) %.1f · ancho %.1f · largo %.1f %s.\n",
                    (b.separation + b.alto) * k, b.ancho * k, b.prof * k, u));
        if (d.hull != null) {
            double kh = d.scaleCm > 0 ? d.hullCmPerUnit : 1;
            if (b.onWall)
                s.append(String.format(Locale.ROOT, "- Por siluetas (%d fotos): alto %.1f · ancho %.1f · de la pared al frente %.1f %s · volumen %.0f %s³.\n",
                        d.hull.views, d.hull.alto * kh, d.hull.ancho * kh, d.hull.front * kh, u, d.hull.volume * kh * kh * kh, u));
            else
                s.append(String.format(Locale.ROOT, "- Por siluetas (%d fotos): alto (del apoyo al tope) %.1f · ancho %.1f · largo %.1f %s · volumen %.0f %s³.\n",
                        d.hull.views, d.hull.front * kh, d.hull.ancho * kh, d.hull.prof * kh, u, d.hull.volume * kh * kh * kh, u));
            s.append(String.format(Locale.ROOT, "- Diferencia entre los dos métodos: %.1f %%.\n", 100 * Carve.disagreement(b, d.hull)));
        } else {
            s.append("- Sin segunda opinión por siluetas.\n");
        }
        s.append("\n## 6. Puntos clave\n\n");
        s.append("Cinco puntos que marcan la geometría (en `depuracion/` se dibujan sobre cada foto donde se ven).\n\n");
        s.append("| Punto | Ancho | ").append(b.onWall ? "Alto" : "Largo").append(" | Distancia a la ").append(b.onWall ? "pared" : "mesa")
                .append(" | Visto en fotos |\n|---|---|---|---|---|\n");
        for (int l = 0; l < lm.length; l++) {
            if (lm[l] < 0) continue;
            double[] p = boxCoords(b, r.points[lm[l]]);
            List<String> in = new ArrayList<>();
            for (int c : byTime(r))
                for (Sfm.Track t : r.pointTracks.get(lm[l])) if (t.visible(r.camFrames.get(c))) { in.add(String.valueOf(r.camFrames.get(c))); break; }
            s.append(String.format(Locale.ROOT, "| %s | %.1f | %.1f | %.1f | %d (%s) |\n", Measure.LANDMARKS[l], p[0] * k, p[1] * k, p[2] * k,
                    in.size(), in.size() > 8 ? String.join(", ", in.subList(0, 8)) + "…" : String.join(", ", in)));
        }
        if (lm[0] >= 0 && lm[1] >= 0)
            s.append(String.format(Locale.ROOT, "\n- arriba ↔ abajo: %.1f %s · izquierda ↔ derecha: %.1f %s.\n",
                    Lin.norm(Lin.sub(r.points[lm[0]], r.points[lm[1]])) * k, u,
                    lm[2] >= 0 && lm[3] >= 0 ? Lin.norm(Lin.sub(r.points[lm[2]], r.points[lm[3]])) * k : 0, u));
        s.append("\n## 7. Archivos\n\n");
        s.append("- `nube_objeto.ply`: puntos del objeto · `nube_completa.ply`: todos (objeto y entorno), en ejes del objeto.\n");
        if (d.hull != null) s.append("- `volumen.stl`: volumen tallado con las siluetas, en mm").append(d.scaleCm > 0 ? "" : " (alto = 100 mm)").append(".\n");
        s.append("- `fotos/`: los cuadros clave usados").append(".\n");
        s.append("- `depuracion/` (si se pidió): cada foto con la silueta (cian) y los puntos clave.\n");
        return s.toString();
    }

    /** Índices de las cámaras en orden de tiempo (se ubican en otro orden: primero el par inicial). */
    static Integer[] byTime(Sfm.Result r) {
        Integer[] o = new Integer[r.poses.size()];
        for (int c = 0; c < o.length; c++) o[c] = c;
        java.util.Arrays.sort(o, (a, b) -> Integer.compare(r.camFrames.get(a), r.camFrames.get(b)));
        return o;
    }

    static String photoName(int frame) { return String.format(Locale.ROOT, "cuadro_%03d.jpg", frame); }

    /**
     * Coordenadas en ejes del objeto: x = ancho, y = alto (en una pared) o
     * largo (sobre una mesa), ambos desde el centro, y z = distancia al
     * plano de apoyo. (Sobre una mesa el alto ES la distancia al apoyo: antes
     * se repetía y se perdía el largo.)
     */
    static double[] boxCoords(Measure.Box b, double[] x) {
        double[] q = Lin.sub(x, b.center);
        return new double[]{Lin.dot(q, b.axisW), Lin.dot(q, b.onWall ? b.axisH : b.axisD), Lin.dot(Lin.sub(x, b.planeP), b.planeN)};
    }

    /** Nombre del eje y de {@link #boxCoords}. */
    private static String yName(Measure.Box b) { return b.onWall ? "alto" : "largo"; }

    // ------------------------------------------------------------ nube y volumen

    private static byte[] ply(Sfm.Result r, Measure.Box b, double scaleCm, boolean objectOnly) {
        boolean[] obj = new boolean[r.points.length];
        for (int i : b.objectPoints) obj[i] = true;
        int n = 0;
        for (int i = 0; i < obj.length; i++) if (obj[i] || !objectOnly) n++;
        double k = scaleCm > 0 ? scaleCm : 1;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (PrintWriter pw = new PrintWriter(bo)) {
            pw.print("ply\nformat ascii 1.0\ncomment Navaja: " + (objectOnly ? "puntos del objeto" : "todos los puntos")
                    + ", ejes del objeto (x ancho, y " + yName(b) + ", z distancia al apoyo)" + (scaleCm > 0 ? ", cm" : ", unidades del modelo") + "\n");
            pw.print("element vertex " + n + "\nproperty float x\nproperty float y\nproperty float z\n"
                    + "property uchar red\nproperty uchar green\nproperty uchar blue\n" + (objectOnly ? "" : "property uchar objeto\n") + "end_header\n");
            for (int i = 0; i < r.points.length; i++) {
                if (objectOnly && !obj[i]) continue;
                double[] p = boxCoords(b, r.points[i]);
                int col = r.colors[i];
                pw.printf(Locale.ROOT, "%.4f %.4f %.4f %d %d %d%s%n", p[0] * k, p[1] * k, p[2] * k, (col >> 16) & 255, (col >> 8) & 255, col & 255,
                        objectOnly ? "" : obj[i] ? " 1" : " 0");
            }
        }
        return bo.toByteArray();
    }

    /** Volumen tallado como STL binario, en mm (con alto = 100 mm si no hay medida conocida). */
    static byte[] stl(Data d) throws IOException {
        double mmPerUnit = d.scaleCm > 0 ? 10 * d.hullCmPerUnit : 100 / (d.choice.box.onWall ? d.hull.alto : d.hull.front);
        Mesh mesh = SurfaceNets.extract(d.hull.grid);
        mesh.taubinSmooth(8);
        for (int i = 0; i < mesh.pos.length; i++) mesh.pos[i] *= (float) mmPerUnit;
        File tmp = File.createTempFile("volumen", ".stl");
        try {
            mesh.writeStl(tmp);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream in = new FileInputStream(tmp)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    // ------------------------------------------------------------ depuración

    /** La foto con el contorno de la silueta y los puntos clave que se ven en ella. */
    private static byte[] annotate(File photo, Data d, int c, int[] lm) {
        Sfm.Result r = d.choice.result;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inMutable = true;
        Bitmap bmp = BitmapFactory.decodeFile(photo.getAbsolutePath(), o);
        if (bmp == null) return new byte[0];
        Canvas cv = new Canvas(bmp);
        int w = bmp.getWidth(), h = bmp.getHeight();
        float s = Math.max(w, h) / 960f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // Contorno de la silueta (píxeles de borde de la máscara)
        byte[] m = d.masks == null ? null : d.masks[c];
        if (m != null) {
            p.setColor(0xFF00E5FF);
            float sx = (float) w / d.maskW, sy = (float) h / d.maskH;
            for (int y = 1; y < d.maskH - 1; y++)
                for (int x = 1; x < d.maskW - 1; x++) {
                    int i = y * d.maskW + x;
                    if (m[i] != 0 && (m[i - 1] == 0 || m[i + 1] == 0 || m[i - d.maskW] == 0 || m[i + d.maskW] == 0))
                        cv.drawRect(x * sx, y * sy, (x + 1) * sx, (y + 1) * sy, p);
                }
        }
        // Puntos clave: donde la pista los detectó en este cuadro
        int fr = r.camFrames.get(c);
        p.setTextSize(22 * s);
        for (int l = 0; l < lm.length; l++) {
            if (lm[l] < 0) continue;
            for (Sfm.Track t : r.pointTracks.get(lm[l])) {
                if (!t.visible(fr)) continue;
                float x = t.x(fr), y = t.y(fr);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(3 * s);
                p.setColor(COLORS[l]);
                cv.drawCircle(x, y, 12 * s, p);
                p.setStyle(Paint.Style.FILL);
                cv.drawCircle(x, y, 3 * s, p);
                p.setColor(Color.BLACK);
                cv.drawText(Measure.LANDMARKS[l], x + 15 * s + 1, y - 8 * s + 1, p);
                p.setColor(COLORS[l]);
                cv.drawText(Measure.LANDMARKS[l], x + 15 * s, y - 8 * s, p);
                break;
            }
        }
        // Encabezado
        p.setColor(0xAA000000);
        cv.drawRect(0, 0, w, 34 * s, p);
        p.setColor(Color.WHITE);
        p.setTextSize(20 * s);
        cv.drawText(String.format(Locale.ROOT, "cuadro %d · %.2f s · posición %d de %d", fr, fr / d.fps, c + 1, r.poses.size()), 10 * s, 24 * s, p);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 88, bo);
        bmp.recycle();
        return bo.toByteArray();
    }
}
