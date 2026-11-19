package com.josexato.navaja.measure.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.List;

public class SheetPlaneTest {

    /** Observaciones normalizadas de {@code x} desde las cámaras {@code cams} (cuadros 0, 6, 12, 18 de la órbita). */
    static double[][] see(Geometry.Pose[] cams, double[] x) {
        double[][] o = new double[cams.length][];
        for (int i = 0; i < cams.length; i++) {
            double[] xc = cams[i].apply(x);
            o[i] = new double[]{xc[0] / xc[2], xc[1] / xc[2]};
        }
        return o;
    }

    static Geometry.Pose[] cams() {
        List<Geometry.Pose> orbit = SheetScanTest.orbit();
        return new Geometry.Pose[]{orbit.get(0), orbit.get(6), orbit.get(12), orbit.get(18)};
    }

    @Test
    public void unPuntoDeLaHojaQuedaEnElPlanoExacto() {
        Geometry.Pose[] cs = cams();
        double[] p = {37.5, -82.25, 0};
        double[][] ob = see(cs, p);
        double[] on = SheetPlane.onPlane(cs, ob);
        assertEquals(p[0], on[0], 1e-6);
        assertEquals(p[1], on[1], 1e-6);
        assertEquals(0, on[2], 0);
        // Aunque la triangulación libre lo haya dejado 3 mm arriba, vuelve al plano
        double[] snapped = SheetPlane.snap(cs, ob, new double[]{37.5, -82.25, 3}, SheetScanTest.F);
        assertNotNull(snapped);
        assertEquals(0, snapped[2], 0);
    }

    @Test
    public void unPuntoDelObjetoNoSeAplasta() {
        Geometry.Pose[] cs = cams();
        double[] p = {20, 10, 12}; // 12 mm sobre la hoja: forzado a Z = 0, sus rayos ya no calzan
        assertNull(SheetPlane.snap(cs, see(cs, p), p, SheetScanTest.F));
        // Muy arriba: ni se prueba
        assertNull(SheetPlane.snap(cs, see(cs, new double[]{0, 0, 60}), new double[]{0, 0, 60}, SheetScanTest.F));
    }
}
