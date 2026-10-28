package com.josexato.navaja.measure;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import com.josexato.navaja.measure.core.Carve;
import com.josexato.navaja.measure.core.Cloud;
import com.josexato.navaja.measure.core.Lin;
import com.josexato.navaja.measure.core.Measure;
import com.josexato.navaja.measure.core.Motion;
import com.josexato.navaja.measure.core.Sfm;
import com.josexato.navaja.measure.core.Texture;
import com.josexato.navaja.scan3d.core.Decimate;
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
        /** Intentos de unir tramos por cierre de bucle. */
        String loopLog = "";
        /** Fotos de los cuadros clave (mismo orden que las cámaras); null si falta alguna. */
        File[] photos;
        byte[][] masks;
        /** Las siluetas usadas (null donde se descartó); null si no se validaron. */
        byte[][] masksUsed;
        int maskW, maskH;
        private Cloud.Result cloud;

        private Mesh[] models;
        /** De la malla (mm, origen en la base) al modelo: mm por unidad y el desplazamiento aplicado. */
        double meshMmPerUnit;
        float[] meshOffset;
        private Texture.Result[] textures;

        /** Texturas (mapeo UV con las fotos) de los modelos base y low poly; null si no hay fotos. Se calculan una vez. */
        Texture.Result[] textures() {
            if (textures == null && photos != null && hull != null) {
                Mesh[] ms = models();
                textures = new Texture.Result[]{texture(this, ms[1], 2048), texture(this, ms[2], 1024)};
            }
            return textures;
        }

        /** Mallas del volumen: completa, base de modelado y low poly (simplificadas por error cuadrático); se calculan una vez. */
        Mesh[] models() {
            if (models == null) {
                Mesh full = hullMesh(this);
                Mesh base = Decimate.simplify(full, BASE_TRIS);
                models = new Mesh[]{full, base, Decimate.simplify(base, LOW_TRIS)};
            }
            return models;
        }

        /** La nube del objeto limpia (sin atípicos, en vóxeles, con normales); se calcula una vez. */
        Cloud.Result cloud() {
            if (cloud == null) cloud = Cloud.object(choice.result, choice.box);
            return cloud;
        }
    }

    private static final int[] COLORS = {0xFFFF5252, 0xFF40C4FF, 0xFFFFD740, 0xFF69F0AE, 0xFFE040FB};

    static void write(OutputStream os, Data d, boolean debug) throws IOException {
        Sfm.Result r = d.choice.result;
        Measure.Box b = d.choice.box;
        int[] lm = Measure.landmarks(r, b, d.hull);
        try (ZipOutputStream zip = new ZipOutputStream(os)) {
            put(zip, "informe.md", report(d, lm).getBytes(StandardCharsets.UTF_8));
            put(zip, "nube_objeto.ply", objectPly(d.cloud(), b, d.scaleCm));
            put(zip, "nube_completa.ply", ply(r, b, d.scaleCm, false));
            if (d.hull != null) {
                Mesh[] ms = d.models();
                put(zip, "volumen.stl", stl(d));
                Texture.Result[] tx = d.textures();
                String[] names = {"modelo_base", "modelo_lowpoly"}, images = {"textura_base.jpg", "textura_lowpoly.jpg"};
                for (int k = 0; k < 2; k++) {
                    Texture.Result t = tx == null ? null : tx[k];
                    put(zip, names[k] + ".obj", obj(ms[k + 1], names[k], t));
                    if (t != null) {
                        put(zip, names[k] + ".mtl", mtl(images[k]));
                        put(zip, images[k], jpeg(t));
                    }
                }
            }
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
        s.append("- Si el seguimiento se corta (imagen movida, algo tapa la vista), el video se parte en tramos y se usa el mejor.\n");
        s.append("- Giro y escala en la imagen: cuánto rotó y cambió de tamaño el objeto en la foto a través del corte, según manchas de "
                + "color dentro de su silueta (por ejemplo ojos o boca) emparejadas por color, forma (momentos de Hu) y disposición.\n\n");
        s.append("| Tramo | Desde | Hasta | Posiciones del celular | Puntos 3D | Hueco hasta el siguiente | Puntos que cruzan el corte | "
                + "Giro y escala en la imagen (regiones) | |\n"
                + "|---|---|---|---|---|---|---|---|---|\n");
        for (int i = 0; i < d.segments.size(); i++) {
            double[] g = d.segments.get(i);
            boolean last = i + 1 >= d.segments.size();
            s.append(String.format(Locale.ROOT, "| %d | %.1f s | %.1f s | %d | %d | %s | %s | %s | %s |\n", i, g[0], g[1], (int) g[2], (int) g[3],
                    last ? "—" : String.format(Locale.ROOT, "%.1f s", d.segments.get(i + 1)[0] - g[1]),
                    last || g.length < 5 || g[4] < 0 ? "—" : String.valueOf((int) g[4]),
                    last || g.length < 10 || g[9] < 2 ? "—" : String.format(Locale.ROOT, "%+.0f° · ×%.2f (%d regiones)", g[7], g[8], (int) g[9]),
                    g.length > 5 && g[5] == 1 ? "**usado**" : ""));
        }
        double usedS = 0;
        for (int[] p : r.parts) usedS += (p[1] - p[0]) / d.fps;
        s.append(String.format(Locale.ROOT, "\n**Video usado: %.1f s de %.1f s (%.0f %%)%s.** En un corte se pierden casi todos los puntos; los tramos se "
                + "unen sólo si más adelante se reconocen lugares ya vistos (cierre de bucle). Los que no, se descartan.\n",
                usedS, d.videoSeconds, d.videoSeconds > 0 ? 100 * usedS / d.videoSeconds : 0, r.parts.size() > 1 ? ", " + r.parts.size() + " tramos unidos" : ""));
        if (d.loopLog != null && !d.loopLog.isEmpty()) {
            s.append("\n### Cierre de bucle\n\n");
            s.append("Para cada par de tramos: cuadros del otro tramo comparados con el principal (coincidencias verificadas con geometría de dos "
                    + "vistas / con posición 3D / confirmadas al ubicar la cámara) y el resultado. [reg N]: con N cuadros del principal "
                    + "las regiones de color calzaron y guiaron la búsqueda de puntos.\n\n");
            for (String line : d.loopLog.split("\n")) if (!line.isEmpty()) s.append("- ").append(line).append('\n');
        }
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
                    silhouette(d, c), p[0] * k, p[1] * k, p[2] * k));
        }
        s.append("\n## 4. Puntos\n\n");
        s.append(String.format(Locale.ROOT, "- Nube: %d puntos (%d seguidos con pistas, el resto de la segunda pasada densa o de zonas reprocesadas).\n",
                r.points.length, r.pointTracks.size()));
        s.append(String.format(Locale.ROOT, "- Del objeto: %d · error de reproyección %.2f px.\n", b.objectPoints.length, r.rms));
        if (b.otherPlanes.isEmpty())
            s.append("- Otros planos grandes junto al objeto (piso, mesa, otra pared): ninguno.\n");
        for (double[] op : b.otherPlanes)
            s.append(String.format(Locale.ROOT, "- Otro plano quitado antes de agrupar (piso, mesa u otra pared): %d puntos, a %.0f° del %s. "
                    + "Si tocaba el objeto, se le habría pegado e inflado la caja.\n", (int) op[6], op[7], b.onWall ? "plano de la pared" : "plano de apoyo"));
        Cloud.Result cl = d.cloud();
        s.append(String.format(Locale.ROOT, "- Puntos sueltos (atípicos) del objeto: %d de %d. Un punto es atípico si la distancia media a sus %d "
                + "vecinos más cercanos supera la típica en más de 3 desvíos y el doble, salvo que se haya visto desde %d o más fotos. Se quitan de "
                + "la nube exportada; la medida no los necesita quitar porque usa los percentiles 1–99.\n",
                cl.outliers, cl.input, Cloud.K_SOR, Cloud.TRUSTED_OBS));
        s.append(String.format(Locale.ROOT, "- Nube limpia para exportar: %d puntos del objeto → %d sin atípicos → %d vóxeles de %.2f %s "
                + "(%.0f %% de la mayor medida; un punto por vóxel, con color medio) · normales: plano de los %d vecinos, hacia la cámara más "
                + "cercana (%d de %d con normal).\n", cl.input, cl.input - cl.outliers, cl.points.size(), cl.voxel * k, u, 100 * Cloud.VOXEL_FRACTION,
                Cloud.K_NORMAL, withNormal(cl.points), cl.points.size()));
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
                s.append(String.format(Locale.ROOT, "- Por siluetas (%d fotos): alto %.1f · ancho %.1f · de la pared al frente %.1f %s · volumen %.3g %s³.\n",
                        d.hull.views, d.hull.alto * kh, d.hull.ancho * kh, d.hull.front * kh, u, d.hull.volume * kh * kh * kh, u));
            else
                s.append(String.format(Locale.ROOT, "- Por siluetas (%d fotos): alto (del apoyo al tope) %.1f · ancho %.1f · largo %.1f %s · volumen %.3g %s³.\n",
                        d.hull.views, d.hull.front * kh, d.hull.ancho * kh, d.hull.prof * kh, u, d.hull.volume * kh * kh * kh, u));
            s.append(String.format(Locale.ROOT, "- Diferencia entre los dos métodos: %.1f %%.\n", 100 * Carve.disagreement(b, d.hull)));
            if (d.masksUsed != null) {
                int all = 0, off = 0;
                for (int c = 0; c < d.masks.length; c++) if (d.masks[c] != null) { all++; if (d.masksUsed[c] == null) off++; }
                s.append(String.format(Locale.ROOT, "- Siluetas descartadas: %d de %d (menos de la mitad de los puntos del objeto adentro, o "
                        + "más de 3 veces el área que cubren: una mancha equivocada o desbordada, típica de una foto movida).\n", off, all));
            }
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
        s.append("- `nube_objeto.ply`: puntos del objeto limpios (sin atípicos, uno por vóxel, con normales) · `nube_completa.ply`: todos "
                + "los puntos tal cual (objeto y entorno), en ejes del objeto.\n");
        if (d.hull != null) {
            Mesh[] ms = d.models();
            String mm = d.scaleCm > 0 ? "mm" : "mm (alto = 100 mm: sin medida conocida, sólo valen las proporciones)";
            s.append(String.format(Locale.ROOT, "- `volumen.stl`: volumen tallado con las siluetas, completo (%d triángulos), en %s, Z hacia arriba.\n",
                    ms[0].triangleCount(), mm));
            s.append(String.format(Locale.ROOT, "- `modelo_base.obj`: el mismo volumen simplificado a %d triángulos (las zonas planas en caras grandes, "
                    + "más detalle en las curvas): base para modelar.\n", ms[1].triangleCount()));
            s.append(String.format(Locale.ROOT, "- `modelo_lowpoly.obj`: simplificado a %d triángulos, punto de partida low poly.\n", ms[2].triangleCount()));
            Texture.Result[] tx = d.textures();
            if (tx != null)
                s.append(String.format(Locale.ROOT, "- Texturas (mapeo UV con las fotos): `textura_base.jpg` %d×%d px, %d parches de %d fotos, %d triángulos "
                                + "sin ver (gris) · `textura_lowpoly.jpg` %d×%d px, %d parches. Cada triángulo toma la foto que mejor lo ve (de frente, "
                                + "grande y sin nada delante); las fotos con silueta descartada no se usan. Puede notarse una costura donde cambia la "
                                + "foto (la luz no se iguala entre fotos). Los `.mtl` enlazan cada OBJ con su textura: déjalos en la misma carpeta.\n",
                        tx[0].width, tx[0].height, tx[0].charts, tx[0].photosUsed, tx[0].unseen, tx[1].width, tx[1].height, tx[1].charts));
            s.append("- Los tres con el origen en el centro de la base. En Blender: *Archivo → Importar → Wavefront (.obj)* con las opciones por "
                    + "defecto (OBJ viene con Y hacia arriba y Blender lo endereza); el STL ya viene con Z hacia arriba. Las unidades son mm: si "
                    + "la escena está en metros, usa *Escala 0.001* al importar.\n");
        }
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

    private static String silhouette(Data d, int c) {
        if (d.masks == null || d.masks[c] == null) return "no";
        return d.masksUsed != null && d.masksUsed[c] == null ? "descartada" : "sí";
    }

    private static int withNormal(Cloud.Points p) {
        int n = 0;
        for (double[] v : p.normal) if (v != null) n++;
        return n;
    }

    /** Nombre del eje y de {@link #boxCoords}. */
    private static String yName(Measure.Box b) { return b.onWall ? "alto" : "largo"; }

    // ------------------------------------------------------------ nube y volumen

    /**
     * La nube del objeto procesada ({@link Cloud#object}) en ejes del objeto,
     * con color, normal y cuántos puntos originales resume cada vóxel.
     */
    static byte[] objectPly(Cloud.Result c, Measure.Box b, double scaleCm) {
        Cloud.Points pts = c.points;
        double k = scaleCm > 0 ? scaleCm : 1;
        double[][] axes = {b.axisW, b.onWall ? b.axisH : b.axisD, b.planeN};
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (PrintWriter pw = new PrintWriter(bo)) {
            pw.print("ply\nformat ascii 1.0\ncomment Navaja: puntos del objeto sin atípicos, un punto por vóxel"
                    + String.format(Locale.ROOT, " de %.4f", c.voxel * k)
                    + ", ejes del objeto (x ancho, y " + yName(b) + ", z distancia al apoyo)" + (scaleCm > 0 ? ", cm" : ", unidades del modelo") + "\n");
            pw.print("element vertex " + pts.size() + "\nproperty float x\nproperty float y\nproperty float z\n"
                    + "property float nx\nproperty float ny\nproperty float nz\n"
                    + "property uchar red\nproperty uchar green\nproperty uchar blue\nproperty ushort puntos\nend_header\n");
            for (int i = 0; i < pts.size(); i++) {
                double[] p = boxCoords(b, pts.p[i]);
                double[] n = new double[3];
                if (pts.normal[i] != null) for (int a = 0; a < 3; a++) n[a] = Lin.dot(pts.normal[i], axes[a]);
                int col = pts.color[i];
                pw.printf(Locale.ROOT, "%.4f %.4f %.4f %.3f %.3f %.3f %d %d %d %d%n", p[0] * k, p[1] * k, p[2] * k, n[0], n[1], n[2],
                        (col >> 16) & 255, (col >> 8) & 255, col & 255, Math.min(65535, pts.count[i]));
            }
        }
        return bo.toByteArray();
    }

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

    /** Triángulos de los modelos simplificados para Blender: base de modelado y low poly. */
    static final int BASE_TRIS = 3000, LOW_TRIS = 400;

    /**
     * El volumen tallado como malla en mm (con alto = 100 mm si no hay medida
     * conocida), con Y hacia arriba (la normal del apoyo o el "arriba" de la
     * pared) y el origen en el centro de la base, apoyada en Y = 0.
     */
    static Mesh hullMesh(Data d) {
        double mmPerUnit = d.scaleCm > 0 ? 10 * d.hullCmPerUnit : 100 / (d.choice.box.onWall ? d.hull.alto : d.hull.front);
        Mesh mesh = SurfaceNets.extract(d.hull.grid);
        mesh.taubinSmooth(8);
        for (int i = 0; i < mesh.pos.length; i++) mesh.pos[i] *= (float) mmPerUnit;
        float[] b = mesh.bounds();
        float cx = (b[0] + b[1]) / 2, cz = (b[4] + b[5]) / 2;
        for (int i = 0; i < mesh.pos.length; i += 3) {
            mesh.pos[i] -= cx;
            mesh.pos[i + 1] -= b[2];
            mesh.pos[i + 2] -= cz;
        }
        d.meshMmPerUnit = mmPerUnit;
        d.meshOffset = new float[]{cx, b[2], cz};
        return mesh;
    }

    /** Vértices de una malla de {@link #hullMesh} en coordenadas del modelo (para proyectarlos en las fotos). */
    static double[][] worldVerts(Data d, Mesh m) {
        double[][] out = new double[m.vertexCount()][];
        for (int v = 0; v < out.length; v++) {
            double[] x = d.hull.center.clone();
            for (int a = 0; a < 3; a++) {
                double g = (m.pos[3 * v + a] + d.meshOffset[a]) / d.meshMmPerUnit;
                x = Lin.add(x, Lin.scale(d.hull.axes[a], g));
            }
            out[v] = x;
        }
        return out;
    }

    /**
     * Mapeo UV de la malla con las fotos clave ({@link Texture}): sólo fotos
     * con silueta válida (las movidas o mal segmentadas se saltan).
     */
    static Texture.Result texture(Data d, Mesh m, int maxSide) {
        Sfm.Result r = d.choice.result;
        boolean[] usable = new boolean[r.poses.size()];
        for (int c = 0; c < usable.length; c++)
            usable[c] = c < d.photos.length && d.photos[c] != null && d.photos[c].exists()
                    && (d.masksUsed == null || d.masksUsed[c] != null);
        return Texture.build(r, worldVerts(d, m), m.tri, usable, c -> photoPixels(d.photos[c], r.width, r.height), maxSide);
    }

    /** Los píxeles de la foto, al tamaño del cuadro analizado; null si no se puede leer. */
    private static int[] photoPixels(File f, int w, int h) {
        Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (bmp == null) return null;
        try {
            if (bmp.getWidth() != w || bmp.getHeight() != h) {
                Bitmap s = Bitmap.createScaledBitmap(bmp, w, h, true);
                bmp.recycle();
                bmp = s;
            }
            int[] px = new int[w * h];
            bmp.getPixels(px, 0, w, 0, 0, w, h);
            return px;
        } finally {
            bmp.recycle();
        }
    }

    /** La textura como JPEG. */
    private static byte[] jpeg(Texture.Result t) {
        Bitmap bmp = Bitmap.createBitmap(t.atlas, t.width, t.height, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, bo);
        bmp.recycle();
        return bo.toByteArray();
    }

    /** Material de OBJ que apunta a la textura. */
    private static byte[] mtl(String image) {
        return ("# Navaja\nnewmtl textura\nKa 1 1 1\nKd 1 1 1\nKs 0 0 0\nillum 1\nmap_Kd " + image + "\n").getBytes(StandardCharsets.US_ASCII);
    }

    /** STL binario del volumen completo, con Z hacia arriba (lo que espera Blender al importar STL y los programas de impresión). */
    static byte[] stl(Data d) throws IOException {
        Mesh m = d.models()[0], z = new Mesh();
        z.tri = m.tri;
        z.pos = new float[m.pos.length];
        for (int i = 0; i < m.pos.length; i += 3) {
            z.pos[i] = m.pos[i];
            z.pos[i + 1] = -m.pos[i + 2];
            z.pos[i + 2] = m.pos[i + 1];
        }
        File tmp = File.createTempFile("volumen", ".stl");
        try {
            z.writeStl(tmp);
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

    /**
     * OBJ con Y hacia arriba (la convención de OBJ: Blender lo importa
     * derecho con sus opciones por defecto), en mm, sin normales: Blender
     * las calcula y se puede elegir sombreado plano para low poly.
     */
    static byte[] obj(Mesh m, String name) { return obj(m, name, null); }

    /** Igual, y si hay textura ({@code tex}), con coordenadas UV (una por esquina) y su material ({@code name}.mtl). */
    static byte[] obj(Mesh m, String name, Texture.Result tex) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (PrintWriter pw = new PrintWriter(bo)) {
            pw.print("# Navaja: " + name + " · " + m.triangleCount() + " triángulos · mm, Y hacia arriba, origen en el centro de la base\n");
            if (tex != null) pw.print("mtllib " + name + ".mtl\n");
            pw.print("o " + name + "\n");
            for (int i = 0; i < m.pos.length; i += 3)
                pw.printf(Locale.ROOT, "v %.3f %.3f %.3f%n", m.pos[i], m.pos[i + 1], m.pos[i + 2]);
            if (tex != null) {
                for (int i = 0; i < tex.uv.length; i += 2) pw.printf(Locale.ROOT, "vt %.5f %.5f%n", tex.uv[i], tex.uv[i + 1]);
                pw.print("usemtl textura\n");
            }
            for (int t = 0; t < m.tri.length; t += 3) {
                if (tex == null) pw.print("f " + (m.tri[t] + 1) + " " + (m.tri[t + 1] + 1) + " " + (m.tri[t + 2] + 1) + "\n");
                else pw.print("f " + (m.tri[t] + 1) + "/" + (t + 1) + " " + (m.tri[t + 1] + 1) + "/" + (t + 2) + " " + (m.tri[t + 2] + 1) + "/" + (t + 3) + "\n");
            }
        }
        return bo.toByteArray();
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
