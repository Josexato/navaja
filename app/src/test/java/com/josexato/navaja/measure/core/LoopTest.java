package com.josexato.navaja.measure.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class LoopTest {

    static Geometry.Pose pose(double[] w, double[] c) {
        Geometry.Pose p = new Geometry.Pose();
        p.r = Lin.rodrigues(w);
        p.t = Lin.scale(Lin.mulV(p.r, c), -1);
        return p;
    }

    /** Cámaras vistas en los dos sistemas (b = a llevado por una similitud): se recupera la similitud. */
    @Test
    public void similitudDesdeCamaras() {
        double s = 0.37;
        double[] rot = Lin.rodrigues(new double[]{0.2, -0.5, 0.1}), t = {1.5, -0.3, 2.0};
        List<Geometry.Pose> inA = new ArrayList<>(), inB = new ArrayList<>();
        for (int k = 0; k < 3; k++) {
            Geometry.Pose pa = pose(new double[]{0.1 * k, 0.3 - 0.2 * k, 0.05}, new double[]{2 * k - 2, 0.5 * k, 5});
            // Misma cámara en el sistema de b: x_a = s·R·x_b + t  →  centro_b = Rᵀ(centro_a − t)/s, R_b = R_a·R
            Geometry.Pose pb = new Geometry.Pose();
            pb.r = Lin.mul(pa.r, rot);
            double[] cb = Lin.scale(Lin.mulV(Lin.tr(rot), Lin.sub(pa.center(), t)), 1 / s);
            pb.t = Lin.scale(Lin.mulV(pb.r, cb), -1);
            inA.add(pa);
            inB.add(pb);
        }
        double[] sim = Loop.similarityFromCameras(inA, inB);
        assertNotNull(sim);
        assertEquals(s, sim[0], 1e-9);
        for (int q = 0; q < 9; q++) assertEquals(rot[q], sim[1 + q], 1e-9);
        for (int q = 0; q < 3; q++) assertEquals(t[q], sim[10 + q], 1e-9);
        for (int k = 0; k < 3; k++) assertTrue(Loop.coherent(sim, inA.get(k), inB.get(k), 5));
        // Una cámara mal ubicada (girada 20°) no es coherente
        Geometry.Pose bad = inA.get(0).copy();
        bad.r = Lin.mul(Lin.rodrigues(new double[]{0, Math.toRadians(20), 0}), bad.r);
        assertFalse(Loop.coherent(sim, bad, inB.get(0), 5));
    }

    /** Pose entre dos cuadros clave: interpolada; lejos de todos, ninguna. */
    @Test
    public void poseInterpolada() {
        Sfm.Result r = new Sfm.Result();
        r.camFrames.addAll(Arrays.asList(10, 14));
        r.poses.add(pose(new double[]{0, 0, 0}, new double[]{0, 0, 0}));
        r.poses.add(pose(new double[]{0, 0.4, 0}, new double[]{4, 0, 0}));
        Geometry.Pose m = Loop.poseAt(r, 12);
        assertNotNull(m);
        assertEquals(2, m.center()[0], 1e-9);
        assertEquals(0.2, Lin.norm(Lin.logRot(m.r)), 1e-9);
        assertEquals(null, Loop.poseAt(r, 40));
    }
}
