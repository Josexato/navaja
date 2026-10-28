package com.josexato.navaja.measure.core;

import static org.junit.Assert.assertTrue;

import com.josexato.navaja.scan3d.core.Carver;
import com.josexato.navaja.scan3d.core.Decimate;
import com.josexato.navaja.scan3d.core.Mesh;
import com.josexato.navaja.scan3d.core.SurfaceNets;

import org.junit.Test;

public class TextureTest {

    static final double[] HALF = {5, 4, 3};
    /** Color de cada cara de la caja: +x, −x, +y, −y, +z, −z. */
    static final int[] COLORS = {0xFFE02020, 0xFF20C020, 0xFF2040E0, 0xFFE0E020, 0xFFE020E0, 0xFF20E0E0};

    /** Foto de la caja desde la cámara {@code p}: cada píxel con el color de la cara que ve (fondo negro). */
    static int[] render(Geometry.Pose p, int w, int h, double f) {
        int[] img = new int[w * h];
        double[] o = p.center();
        double[] rt = Lin.tr(p.r);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                double[] d = Lin.mulV(rt, new double[]{(x + 0.5 - w / 2.0) / f, (y + 0.5 - h / 2.0) / f, 1});
                double tn = -1e18, tf = 1e18;
                int face = -1;
                for (int a = 0; a < 3; a++) {
                    if (Math.abs(d[a]) < 1e-12) { if (Math.abs(o[a]) > HALF[a]) { tn = 1e18; break; } continue; }
                    double t1 = (-HALF[a] - o[a]) / d[a], t2 = (HALF[a] - o[a]) / d[a];
                    int f1 = 2 * a + 1, f2 = 2 * a;
                    if (t1 > t2) { double tt = t1; t1 = t2; t2 = tt; int ff = f1; f1 = f2; f2 = ff; }
                    if (t1 > tn) { tn = t1; face = f1; }
                    tf = Math.min(tf, t2);
                }
                img[y * w + x] = tn <= tf && tn > 0 && face >= 0 ? COLORS[face] : 0xFF000000;
            }
        return img;
    }

    @Test
    public void cadaCaraTomaSuColor() {
        // Malla de la caja (vóxeles de 0,5) simplificada como para Blender
        Carver.Grid g = new Carver.Grid();
        g.step = 0.5;
        g.nx = 26; g.ny = 22; g.nz = 18;
        g.x0 = -6.5; g.y0 = -5.5; g.z0 = -4.5;
        g.occ = new byte[g.nx * g.ny * g.nz];
        for (int k = 0; k < g.nz; k++)
            for (int j = 0; j < g.ny; j++)
                for (int i = 0; i < g.nx; i++) {
                    double x = g.x0 + (i + 0.5) * g.step, y = g.y0 + (j + 0.5) * g.step, z = g.z0 + (k + 0.5) * g.step;
                    if (Math.abs(x) < HALF[0] && Math.abs(y) < HALF[1] && Math.abs(z) < HALF[2]) g.occ[g.idx(i, j, k)] = 1;
                }
        Mesh m = Decimate.simplify(SurfaceNets.extract(g), 1500);
        double[][] verts = new double[m.vertexCount()][];
        for (int v = 0; v < verts.length; v++) verts[v] = new double[]{m.pos[3 * v], m.pos[3 * v + 1], m.pos[3 * v + 2]};
        // 16 cámaras alrededor, subiendo y bajando para ver también arriba y abajo
        Sfm.Result r = new Sfm.Result();
        r.width = 320;
        r.height = 240;
        r.focal = 300;
        for (int c = 0; c < 16; c++) {
            double a = Math.toRadians(22.5 * c), el = Math.toRadians(c % 2 == 0 ? 35 : -35);
            double[] eye = {30 * Math.cos(el) * Math.sin(a), 30 * Math.sin(el), 30 * Math.cos(el) * Math.cos(a)};
            r.poses.add(PlanesTest.lookAt(eye, new double[]{0, 0, 0}));
        }
        Texture.Result t = Texture.build(r, verts, m.tri, null, c -> render(r.poses.get(c), r.width, r.height, r.focal), 1024);
        int checked = 0, ok = 0;
        for (int k = 0; k < m.triangleCount(); k++) {
            double[] a = verts[m.tri[3 * k]], b = verts[m.tri[3 * k + 1]], c = verts[m.tri[3 * k + 2]];
            double[] ctr = Lin.scale(Lin.add(Lin.add(a, b), c), 1.0 / 3);
            // Sólo triángulos bien dentro de una cara (lejos de aristas redondeadas)
            int face = -1;
            for (int ax = 0; ax < 3; ax++) {
                boolean inner = true;
                for (int o = 0; o < 3; o++) if (o != ax) inner &= Math.abs(ctr[o]) < HALF[o] - 1;
                if (inner && Math.abs(ctr[ax]) > HALF[ax] - 0.6) face = 2 * ax + (ctr[ax] > 0 ? 0 : 1);
            }
            if (face < 0) continue;
            checked++;
            float u = (t.uv[6 * k] + t.uv[6 * k + 2] + t.uv[6 * k + 4]) / 3, v = (t.uv[6 * k + 1] + t.uv[6 * k + 3] + t.uv[6 * k + 5]) / 3;
            int px = Math.min(t.width - 1, (int) (u * t.width)), py = Math.min(t.height - 1, (int) ((1 - v) * t.height));
            if (close(t.atlas[py * t.width + px], COLORS[face])) ok++;
        }
        assertTrue("revisados " + checked, checked > 100);
        assertTrue("con su color " + ok + " de " + checked, ok > 0.95 * checked);
        assertTrue("sin ver " + t.unseen, t.unseen < 0.02 * m.triangleCount());
        assertTrue("parches " + t.charts, t.charts < m.triangleCount() / 5);
        assertTrue(t.width <= 1024 && t.height <= 1024);
    }

    static boolean close(int a, int b) {
        for (int s = 0; s <= 16; s += 8) if (Math.abs(((a >> s) & 255) - ((b >> s) & 255)) > 40) return false;
        return true;
    }
}
