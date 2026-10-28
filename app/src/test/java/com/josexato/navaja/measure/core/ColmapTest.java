package com.josexato.navaja.measure.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class ColmapTest {

    @Test
    public void cuaternionIdaYVuelta() {
        Random rnd = new Random(1);
        for (int k = 0; k < 200; k++) {
            double[] w = {rnd.nextGaussian() * 2, rnd.nextGaussian() * 2, rnd.nextGaussian() * 2};
            double[] r = Lin.rodrigues(w), q = Colmap.quaternion(r);
            assertTrue(q[0] >= 0);
            double qw = q[0], qx = q[1], qy = q[2], qz = q[3];
            double[] back = {
                    1 - 2 * (qy * qy + qz * qz), 2 * (qx * qy - qz * qw), 2 * (qx * qz + qy * qw),
                    2 * (qx * qy + qz * qw), 1 - 2 * (qx * qx + qz * qz), 2 * (qy * qz - qx * qw),
                    2 * (qx * qz - qy * qw), 2 * (qy * qz + qx * qw), 1 - 2 * (qx * qx + qy * qy)};
            for (int i = 0; i < 9; i++) assertEquals(r[i], back[i], 1e-9);
        }
    }

    /** Lo exportado reproyecta: con el cuaternión y t de images.txt, cada punto de points3D.txt cae en su observación. */
    @Test
    public void elModeloDeTextoEsCoherente() {
        Random rnd = new Random(2);
        Sfm.Result r = new Sfm.Result();
        r.width = 540;
        r.height = 960;
        r.focal = 700;
        r.rms = 0.5;
        for (int c = 0; c < 4; c++) {
            double a = Math.toRadians(-20 + 13 * c);
            r.poses.add(PlanesTest.lookAt(new double[]{40 * Math.sin(a), -5, 40 * Math.cos(a)}, new double[]{0, 0, 0}));
            r.camFrames.add(3 * c);
        }
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            double[] x = {rnd.nextGaussian() * 5, rnd.nextGaussian() * 5, rnd.nextGaussian() * 5};
            Sfm.Track t = null;
            for (int fr = 0; fr <= 9; fr++) {
                Geometry.Pose p = r.poses.get(Math.min(3, fr / 3));
                double[] xc = p.apply(x);
                float u = (float) (r.focal * xc[0] / xc[2] + r.width / 2.0), v = (float) (r.focal * xc[1] / xc[2] + r.height / 2.0);
                if (t == null) t = new Sfm.Track(fr, u, v, 0xFF102030);
                else t.add(u, v);
            }
            pts.add(x);
            List<Sfm.Track> l = new ArrayList<>();
            l.add(t);
            r.pointTracks.add(l);
        }
        r.points = pts.toArray(new double[0][]);
        r.colors = new int[r.points.length];
        String[] names = {"a.jpg", "b.jpg", "c.jpg", "d.jpg"};
        String[] txt = Colmap.text(r, names);
        assertTrue(txt[0].contains("1 SIMPLE_PINHOLE 540 960 700.000000 270.500000 480.500000"));
        // images.txt: línea de pose + línea de puntos 2D por imagen
        String[] il = txt[1].split("\n");
        List<double[]> q = new ArrayList<>();
        List<String[]> obs = new ArrayList<>();
        for (int k = 2; k < il.length; k += 2) {
            String[] h = il[k].split(" ");
            q.add(new double[]{Double.parseDouble(h[1]), Double.parseDouble(h[2]), Double.parseDouble(h[3]), Double.parseDouble(h[4]),
                    Double.parseDouble(h[5]), Double.parseDouble(h[6]), Double.parseDouble(h[7])});
            obs.add(il[k + 1].split(" "));
        }
        assertEquals(4, q.size());
        int checked = 0;
        for (String line : txt[2].split("\n")) {
            if (line.startsWith("#")) continue;
            String[] f = line.split(" ");
            double[] x = {Double.parseDouble(f[1]), Double.parseDouble(f[2]), Double.parseDouble(f[3])};
            for (int k = 8; k + 1 < f.length; k += 2) {
                int im = Integer.parseInt(f[k]) - 1, idx = Integer.parseInt(f[k + 1]);
                double[] a = q.get(im);
                double qw = a[0], qx = a[1], qy = a[2], qz = a[3];
                double[] rm = {
                        1 - 2 * (qy * qy + qz * qz), 2 * (qx * qy - qz * qw), 2 * (qx * qz + qy * qw),
                        2 * (qx * qy + qz * qw), 1 - 2 * (qx * qx + qz * qz), 2 * (qy * qz - qx * qw),
                        2 * (qx * qz - qy * qw), 2 * (qy * qz + qx * qw), 1 - 2 * (qx * qx + qy * qy)};
                double[] xc = Lin.add(Lin.mulV(rm, x), new double[]{a[4], a[5], a[6]});
                double u = 700 * xc[0] / xc[2] + 270.5, v = 700 * xc[1] / xc[2] + 480.5;
                String[] o = obs.get(im);
                assertEquals(u, Double.parseDouble(o[3 * idx]), 0.02);
                assertEquals(v, Double.parseDouble(o[3 * idx + 1]), 0.02);
                assertEquals(f[0], o[3 * idx + 2]);
                checked++;
            }
        }
        assertEquals(50 * 4, checked);
        String json = Colmap.json(r, names, 15, null, null, null, 0);
        assertTrue(json.contains("\"archivo\": \"d.jpg\", \"cuadro\": 9"));
    }

    @Test
    public void mascaraPngSeLee() throws Exception {
        int mw = 10, mh = 20, w = 40, h = 80;
        byte[] m = new byte[mw * mh];
        for (int y = 5; y < 15; y++) for (int x = 2; x < 8; x++) m[y * mw + x] = 1;
        byte[] png = Colmap.maskPng(m, mw, mh, w, h);
        // Leer el PNG a mano: firma, fragmentos con CRC, IHDR y los datos comprimidos
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(png);
        assertEquals(0x89504E47, bb.getInt());
        bb.getInt();
        java.io.ByteArrayOutputStream idat = new java.io.ByteArrayOutputStream();
        int iw = 0, ih = 0;
        while (bb.remaining() > 0) {
            int len = bb.getInt();
            byte[] type = new byte[4], data = new byte[len];
            bb.get(type);
            bb.get(data);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(type);
            crc.update(data);
            assertEquals((int) crc.getValue(), bb.getInt());
            String t = new String(type, java.nio.charset.StandardCharsets.US_ASCII);
            if (t.equals("IHDR")) {
                java.nio.ByteBuffer d = java.nio.ByteBuffer.wrap(data);
                iw = d.getInt();
                ih = d.getInt();
                assertEquals(8, d.get());
                assertEquals(0, d.get());
            }
            if (t.equals("IDAT")) idat.write(data);
        }
        assertEquals(w, iw);
        assertEquals(h, ih);
        java.util.zip.Inflater inf = new java.util.zip.Inflater();
        inf.setInput(idat.toByteArray());
        byte[] raw = new byte[(w + 1) * h];
        assertEquals(raw.length, inf.inflate(raw));
        java.util.function.BiFunction<Integer, Integer, Integer> at = (x, y) -> raw[y * (w + 1) + 1 + x] & 255;
        assertEquals(255, (int) at.apply(20, 40));
        assertEquals(0, (int) at.apply(1, 1));
        assertEquals(0, (int) at.apply(35, 70));
    }

    @Test
    public void nitidezBajaConLaImagenMovida() {
        int w = 200, h = 150;
        Random rnd = new Random(3);
        float[] g = new float[w * h];
        for (int i = 0; i < g.length; i++) g[i] = ((i % w) / 6 + (i / w) / 6) % 2 == 0 ? 40 : 200;
        for (int i = 0; i < g.length; i++) g[i] += (float) rnd.nextGaussian() * 2;
        // Movida: promedio horizontal de 9 px (como un barrido de la cámara)
        float[] b = new float[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                float s = 0;
                for (int k = -4; k <= 4; k++) s += g[y * w + Math.max(0, Math.min(w - 1, x + k))];
                b[y * w + x] = s / 9;
            }
        assertTrue(Sfm.sharpness(g, w, h) > 3 * Sfm.sharpness(b, w, h));
        // El cuadro clave se corre al siguiente si está mucho más nítido y conserva los puntos
        List<Float> sharp = new ArrayList<>();
        for (float v : new float[]{100, 100, 30, 100, 90}) sharp.add(v);
        List<Sfm.Track> tracks = new ArrayList<>();
        for (int k = 0; k < 20; k++) {
            Sfm.Track t = new Sfm.Track(0, k, k, 0);
            for (int fr = 1; fr < 5; fr++) t.add(k, k);
            tracks.add(t);
        }
        assertEquals(3, Sfm.sharpest(sharp, 2, 5, tracks, 0));
        assertEquals(1, Sfm.sharpest(sharp, 1, 5, tracks, 0));
    }
}
