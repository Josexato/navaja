package com.josexato.navaja.measure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.josexato.navaja.measure.core.Carve;
import com.josexato.navaja.measure.core.Geometry;
import com.josexato.navaja.measure.core.Measure;
import com.josexato.navaja.measure.core.Sfm;
import com.josexato.navaja.measure.core.SilhouetteIO;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class CarveTest {

    /**
     * Caja de 15 × 20 × 10 cm separada 5 cm de una pared, vista en un arco
     * de 90° con el vaivén vertical de un video a mano (±12 cm), con poses y
     * puntos exactos y siluetas perfectas: el casco tallado debe medir lo
     * mismo que la caja y coincidir con la medida por puntos. (Si todas las
     * cámaras estuvieran a la misma altura, nada recortaría arriba y abajo
     * de la caja y el alto saldría de más: es el límite de las siluetas.)
     */
    @Test
    public void tallaUnaCajaContraLaPared() {
        Random rnd = new Random(11);
        int w = 540, h = 960;
        double f = 720;
        List<double[]> world = new ArrayList<>();
        for (int i = 0; i < 1200; i++) world.add(new double[]{rnd.nextDouble() * 120 - 60, rnd.nextDouble() * 120 - 60, 0});
        for (int i = 0; i < 1500; i++) {
            double a = rnd.nextDouble(), b = rnd.nextDouble();
            switch (rnd.nextInt(5)) {
                case 0: world.add(new double[]{-7.5 + 15 * a, -10 + 20 * b, 15}); break;
                case 1: world.add(new double[]{-7.5, -10 + 20 * b, 5 + 10 * a}); break;
                case 2: world.add(new double[]{7.5, -10 + 20 * b, 5 + 10 * a}); break;
                case 3: world.add(new double[]{-7.5 + 15 * a, -10, 5 + 10 * b}); break;
                default: world.add(new double[]{-7.5 + 15 * a, 10, 5 + 10 * b});
            }
        }
        List<Geometry.Pose> cams = new ArrayList<>();
        int frames = 60;
        for (int k = 0; k < frames; k++) {
            double ang = Math.toRadians(-45 + 90.0 * k / (frames - 1));
            double[] c = {55 * Math.sin(ang), 12 * Math.sin(k * 0.25), 8 + 55 * Math.cos(ang)};
            cams.add(MeasureTest.lookAt(c, new double[]{0, 0, 8}));
        }
        // Resultado "reconstruido" exacto: puntos del mundo, pistas sin ruido, una cámara cada 2 cuadros
        Sfm.Result r = new Sfm.Result();
        r.width = w;
        r.height = h;
        r.focal = f;
        r.startFrame = 0;
        r.endFrame = frames - 1;
        for (int k = 0; k < frames; k += 2) {
            r.camFrames.add(k);
            r.poses.add(cams.get(k));
        }
        List<double[]> pts = new ArrayList<>();
        for (double[] x : world) {
            Sfm.Track t = null;
            for (int k = 0; k < frames; k++) {
                double[] xc = cams.get(k).apply(x);
                // Visible si está en la imagen y no lo tapa la caja (los de pared detrás de la caja no se ven)
                boolean vis = xc[2] > 1 && !(x[2] == 0 && behindBox(cams.get(k).center(), x));
                double u = f * xc[0] / xc[2] + w / 2.0, v = f * xc[1] / xc[2] + h / 2.0;
                vis &= u > 5 && v > 5 && u < w - 5 && v < h - 5;
                if (vis) {
                    if (t == null) t = new Sfm.Track(k, (float) u, (float) v, 0xFF808080);
                    else if (t.end() == k - 1) t.add((float) u, (float) v);
                    else break;
                } else if (t != null) break;
            }
            if (t == null || t.end() - t.start < 4) continue;
            pts.add(x);
            List<Sfm.Track> l = new ArrayList<>();
            l.add(t);
            r.pointTracks.add(l);
        }
        r.points = pts.toArray(new double[0][]);
        r.colors = new int[r.points.length];
        r.nObs = new int[r.points.length];
        for (int i = 0; i < r.points.length; i++)
            for (int fr : r.camFrames) if (r.pointTracks.get(i).get(0).visible(fr)) r.nObs[i]++;
        Measure.Box box = Measure.measure(r);
        assertNotNull(box);
        assertTrue(box.onWall);
        // Siluetas exactas: la envolvente de las 8 esquinas proyectadas
        int mw = w / 2, mh = h / 2;
        byte[][] masks = new byte[r.poses.size()][];
        for (int c = 0; c < masks.length; c++) masks[c] = boxMask(r.poses.get(c), f, w, h, mw, mh);
        // Pertenencia por siluetas: sólo puntos de la caja (z ≥ 5), ninguno de la pared, y casi todos los de la caja
        List<Integer> mem = Carve.silhouetteMembers(r, box, masks, mw, mh);
        int boxPts = 0;
        for (double[] x : r.points) if (x[2] >= 5) boxPts++;
        for (int i : mem) assertTrue("de la caja", r.points[i][2] >= 4.9);
        assertTrue("miembros " + mem.size() + " de " + boxPts, mem.size() > 0.8 * boxPts);
        assertNotNull(Measure.rebox(r, box, mem));
        Carve.Result hull = Carve.carve(r, box, masks, mw, mh);
        assertNotNull(hull);
        assertEquals(30, hull.views);
        // En cm: el modelo está en cm (poses exactas)
        assertEquals("ancho", 15, hull.ancho, 1.0);
        assertEquals("alto", 20, hull.alto, 1.5);
        assertEquals("de la pared al frente", 15, hull.front, 1.0);
        assertTrue("diferencia " + Carve.disagreement(box, hull), Carve.disagreement(box, hull) < 0.08);
        // Puntos clave: arriba (−y), abajo (+y), izquierda/derecha (x = ∓7,5) y frente (z = 15)
        int[] lm = Measure.landmarks(r, box);
        for (int l : lm) assertTrue(l >= 0);
        assertEquals("arriba", -10, r.points[lm[0]][1], 1.0);
        assertEquals("abajo", 10, r.points[lm[1]][1], 1.0);
        assertEquals("izquierda y derecha en lados opuestos", 15, Math.abs(r.points[lm[2]][0] - r.points[lm[3]][0]), 1.5);
        assertEquals("frente", 15, r.points[lm[4]][2], 1.0);
        // El ZIP de "Guardar" (sin depuración: sin gráficos de Android)
        MeasureReport.Data d = new MeasureReport.Data();
        d.choice = new Measure.Choice();
        d.choice.result = r;
        d.choice.box = box;
        d.hull = hull;
        d.scaleCm = 1; // el modelo ya está en cm
        d.hullCmPerUnit = 1;
        d.knownName = "el alto";
        d.videoSeconds = 4;
        d.fps = 15;
        d.tracks = r.pointTracks.size();
        d.segments.add(new double[]{0, 3.9, r.poses.size(), r.points.length, -1, 1, 0});
        r.parts.add(new int[]{0, frames - 1, r.poses.size(), r.points.length});
        d.masks = masks;
        d.maskW = mw;
        d.maskH = mh;
        java.io.ByteArrayOutputStream zipped = new java.io.ByteArrayOutputStream();
        try {
            MeasureReport.write(zipped, d, false);
            java.util.Map<String, byte[]> files = new java.util.HashMap<>();
            try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zipped.toByteArray()))) {
                for (java.util.zip.ZipEntry e; (e = zin.getNextEntry()) != null; ) {
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    for (int n; (n = zin.read(buf)) > 0; ) bo.write(buf, 0, n);
                    files.put(e.getName(), bo.toByteArray());
                }
            }
            assertTrue(files.keySet().toString(), files.keySet().containsAll(java.util.Arrays.asList("informe.md", "nube_objeto.ply", "nube_completa.ply", "volumen.stl",
                    "modelo_base.obj", "modelo_lowpoly.obj", "poses.json", "colmap/sparse/0/cameras.txt", "colmap/sparse/0/images.txt",
                    "colmap/sparse/0/points3D.txt")));
            assertTrue(files.keySet().stream().anyMatch(n -> n.startsWith("colmap/mascaras/cuadro_") && n.endsWith(".jpg.png")));
            // Modelos para Blender: pocas caras, apoyados en Y = 0 y centrados
            for (String name : new String[]{"modelo_base.obj", "modelo_lowpoly.obj"}) {
                String o = new String(files.get(name), java.nio.charset.StandardCharsets.US_ASCII);
                int faces = 0;
                double minY = 1e9, maxY = -1e9, minX = 1e9, maxX = -1e9;
                for (String line : o.split("\n")) {
                    if (line.startsWith("f ")) faces++;
                    if (line.startsWith("v ")) {
                        String[] v = line.split(" ");
                        minX = Math.min(minX, Double.parseDouble(v[1]));
                        maxX = Math.max(maxX, Double.parseDouble(v[1]));
                        minY = Math.min(minY, Double.parseDouble(v[2]));
                        maxY = Math.max(maxY, Double.parseDouble(v[2]));
                    }
                }
                assertTrue(name + " caras " + faces, faces > 12 && faces <= (name.contains("low") ? 400 : 3000));
                assertEquals(name + " base", 0, minY, 2);
                assertEquals(name + " alto (mm: la caja mide 20 cm)", 200, maxY - minY, 15);
                assertEquals(name + " centrado", 0, minX + maxX, 5);
            }
            String informe = new String(files.get("informe.md"), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(informe.contains("## 6. Puntos clave") && informe.contains("| arriba |") && informe.contains("**usado**"));
            String ply = new String(files.get("nube_objeto.ply"), java.nio.charset.StandardCharsets.US_ASCII);
            // Nube limpia: un punto por vóxel (menos que los del objeto), con normales
            int nv = Integer.parseInt(ply.split("element vertex ")[1].split("\n")[0]);
            assertTrue("vóxeles " + nv + " de " + box.objectPoints.length, nv > 0 && nv <= box.objectPoints.length);
            assertTrue(ply.contains("property float nx\n"));
            assertTrue(informe.contains("Puntos sueltos (atípicos) del objeto"));
            // STL binario: 80 de cabecera + cantidad + 50 por triángulo
            byte[] stl = files.get("volumen.stl");
            int tris = java.nio.ByteBuffer.wrap(stl, 80, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
            assertTrue(tris > 100);
            assertEquals(84 + 50 * tris, stl.length);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        // Si la agrupación hubiera perdido medio objeto, el volumen lo recupera (sólo agrega puntos)
        int[] all = box.objectPoints.clone();
        double anchoBien = box.ancho;
        box.objectPoints = java.util.Arrays.stream(all).filter(i -> r.points[i][0] < 0).toArray();
        assertTrue(Measure.refineWithHull(r, box, hull));
        assertEquals("ancho recuperado", anchoBien, box.ancho, 0.05 * anchoBien);
        assertTrue(box.objectPoints.length >= all.length * 0.9);
        // Pasos entre fotos: una cámara cada 2 cuadros, ~3° de arco por paso más el vaivén vertical
        List<com.josexato.navaja.measure.core.Motion.Step> steps = com.josexato.navaja.measure.core.Motion.steps(r);
        assertEquals(r.poses.size() - 1, steps.size());
        for (com.josexato.navaja.measure.core.Motion.Step st : steps) {
            assertEquals(2, st.toFrame - st.fromFrame);
            assertTrue("giro " + st.turnDeg, st.turnDeg > 2 && st.turnDeg < 9); // 3° horizontales + el vaivén vertical
            assertTrue(st.shared > 100);
        }
        // Siluetas malas (fotos movidas): 4 manchas en otro lugar y 4 desbordadas por toda la imagen. Se descartan
        // justo esas y el casco sigue midiendo la caja
        byte[][] bad = masks.clone();
        for (int c = 0; c < 8; c++) {
            byte[] m = new byte[mw * mh];
            if (c % 2 == 0) for (int y = 5; y < 40; y++) for (int x = 5; x < 40; x++) m[y * mw + x] = 1;
            else java.util.Arrays.fill(m, (byte) 1);
            bad[3 * c + 1] = m;
        }
        byte[][] used = Carve.validate(r, box, bad, mw, mh);
        int off = 0;
        for (int c = 0; c < used.length; c++) {
            if (used[c] == null) off++;
            if (c % 3 != 1 || c > 22) assertNotNull("buena " + c, used[c]);
        }
        assertEquals("descartadas", 8, off);
        Carve.Result hb = Carve.carve(r, box, used, mw, mh);
        assertNotNull(hb);
        assertEquals("ancho con siluetas malas", 15, hb.ancho, 1.0);
        assertEquals("alto con siluetas malas", 20, hb.alto, 1.5);
        assertEquals("frente con siluetas malas", 15, hb.front, 1.0);
        // Sin siluetas suficientes no hay casco
        byte[][] few = new byte[masks.length][];
        System.arraycopy(masks, 0, few, 0, 5);
        assertNull(Carve.carve(r, box, few, mw, mh));
    }

    /** ¿El segmento cámara → punto de la pared cruza la caja? */
    static boolean behindBox(double[] cam, double[] x) {
        for (int s = 1; s < 200; s++) {
            double t = s / 200.0;
            double px = cam[0] + t * (x[0] - cam[0]), py = cam[1] + t * (x[1] - cam[1]), pz = cam[2] + t * (x[2] - cam[2]);
            if (Math.abs(px) < 7.5 && Math.abs(py) < 10 && pz > 5 && pz < 15) return true;
        }
        return false;
    }

    static byte[] boxMask(Geometry.Pose p, double f, int w, int h, int mw, int mh) {
        double[][] uv = new double[8][];
        for (int i = 0; i < 8; i++) {
            double[] x = {(i & 1) == 0 ? -7.5 : 7.5, (i & 2) == 0 ? -10 : 10, (i & 4) == 0 ? 5 : 15};
            double[] xc = p.apply(x);
            uv[i] = new double[]{(f * xc[0] / xc[2] + w / 2.0) * mw / w, (f * xc[1] / xc[2] + h / 2.0) * mh / h};
        }
        // Envolvente convexa (cadena monótona) y relleno
        java.util.Arrays.sort(uv, (a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        double[][] hull = new double[16][];
        int k = 0;
        for (int pass = 0; pass < 2; pass++) {
            int start = k;
            for (int n = 0; n < 8; n++) {
                double[] q = uv[pass == 0 ? n : 7 - n];
                while (k >= start + 2 && cross(hull[k - 2], hull[k - 1], q) <= 0) k--;
                hull[k++] = q;
            }
            k--;
        }
        byte[] m = new byte[mw * mh];
        for (int y = 0; y < mh; y++)
            for (int x = 0; x < mw; x++) {
                boolean in = true;
                for (int e = 0; e < k && in; e++) in = cross(hull[e], hull[(e + 1) % k], new double[]{x + 0.5, y + 0.5}) >= 0;
                if (in) m[y * mw + x] = 1;
            }
        return m;
    }

    static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    /** Seguimiento por segundo: activos mínimos, perdidos, nuevos y el cuadro más brusco. */
    @Test
    public void indicadoresDelSeguimiento() {
        List<int[]> pf = new ArrayList<>();
        for (int f = 0; f < 30; f++) pf.add(new int[]{f == 20 ? 50 : 400, f == 20 ? 360 : 10, f == 20 ? 10 : 10});
        List<double[]> ps = com.josexato.navaja.measure.core.Motion.perSecond(pf, 15);
        assertEquals(2, ps.size());
        assertEquals(400, ps.get(0)[1], 0);
        assertEquals(50, ps.get(1)[1], 0);
        assertEquals(20, com.josexato.navaja.measure.core.Motion.worstFrame(pf));
        assertEquals(0.9, com.josexato.navaja.measure.core.Motion.lostFraction(pf, 20), 1e-9);
        assertEquals(0.9, ps.get(1)[4], 1e-9);
    }

    /** La entrada del modelo: color en [0, 1] y el toque como mancha con máximo 1 en su lugar. */
    @Test
    public void entradaDelModelo() {
        int w = 100, h = 200;
        int[] argb = new int[w * h];
        for (int i = 0; i < argb.length; i++) argb[i] = 0xFF000000 | (255 << 16) | (128 << 8);
        FloatBuffer in = ByteBuffer.allocateDirect(4 * 512 * 512 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        SilhouetteIO.input(argb, w, h, 25, 150, in);
        assertEquals(1.0, in.get(0), 1e-6);
        assertEquals(128 / 255.0, in.get(1), 1e-3);
        assertEquals(0.0, in.get(2), 1e-6);
        // Toque en (25, 150) de 100×200 → (128, 384) de 512
        int peak = (384 * 512 + 128) * 4 + 3;
        assertEquals(1.0, in.get(peak), 0.01);
        assertTrue(in.get(3) < 1e-6);
    }

    /** Silueta: umbral, y sólo las regiones con puntos del objeto (las manchas sueltas se descartan). */
    @Test
    public void siluetaSinManchasSueltas() {
        FloatBuffer prob = FloatBuffer.allocate(512 * 512);
        for (int y = 0; y < 512; y++)
            for (int x = 0; x < 512; x++) {
                boolean objeto = Math.hypot(x - 200, y - 200) < 80;
                boolean mancha = x > 420 && y > 420;
                prob.put(y * 512 + x, objeto || mancha ? 0.9f : 0.1f);
            }
        byte[] m = SilhouetteIO.mask(prob, 256, 256, new float[]{100, 100}, 256, 256);
        assertNotNull(m);
        assertEquals(1, m[100 * 256 + 100]);
        assertEquals("mancha descartada", 0, m[230 * 256 + 230]);
        assertEquals(0, m[10 * 256 + 10]);
        // Sin puntos dentro de ninguna región: sin silueta
        assertNull(SilhouetteIO.mask(prob, 256, 256, new float[]{10, 10}, 256, 256));
    }
}
