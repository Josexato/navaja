package com.josexato.navaja.measure.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Locale;

public class ImuTest {

    /**
     * Sensores sintéticos de un recorrido: el mundo de los sensores (Z hacia
     * arriba) es el del modelo girado por {@code m}; cada cámara da su
     * cuaternión celular → mundo y la gravedad en el celular.
     */
    static String csv(Sfm.Result r, double[] m, long start, double fps, boolean withGravity) {
        StringBuilder s = new StringBuilder("#navaja-sensores-1\n#inicio_video_ns=" + start + "\n");
        double[] d = {1, 0, 0, 0, -1, 0, 0, 0, -1};
        for (int c = 0; c < r.poses.size(); c++) {
            long t = start + (long) (r.camFrames.get(c) / fps * 1e9);
            double[] dev = Lin.mul(Lin.mul(m, Lin.tr(r.poses.get(c).r)), d); // celular → mundo de los sensores
            double[] q = Colmap.quaternion(dev); // w, x, y, z
            s.append(String.format(Locale.ROOT, "r,%d,%.9f,%.9f,%.9f,%.9f\n", t, q[1], q[2], q[3], q[0]));
            if (withGravity) {
                double[] g = Lin.mulV(Lin.tr(dev), new double[]{0, 0, 9.81});
                s.append(String.format(Locale.ROOT, "g,%d,%.6f,%.6f,%.6f\n", t, g[0], g[1], g[2]));
            }
        }
        return s.toString();
    }

    static Sfm.Result orbit() {
        Sfm.Result r = new Sfm.Result();
        for (int c = 0; c < 20; c++) {
            double a = Math.toRadians(18 * c);
            r.poses.add(PlanesTest.lookAt(new double[]{30 * Math.sin(a), -15 - 5 * Math.sin(3 * a), 30 * Math.cos(a)}, new double[]{0, 0, 0}));
            r.camFrames.add(6 * c);
        }
        return r;
    }

    @Test
    public void laGravedadDaElArribaDelModelo() {
        Sfm.Result r = orbit();
        // El modelo tiene "arriba" en −y (como las cámaras de lookAt); el mundo de los sensores, en +z. m: modelo → sensores
        double[] m = Lin.mul(Lin.rodrigues(new double[]{0, 0, 0.7}), new double[]{1, 0, 0, 0, 0, 1, 0, -1, 0});
        for (boolean g : new boolean[]{true, false}) {
            Imu imu = Imu.parse(csv(r, m, 123_000_000_000L, 15, g));
            assertNotNull(imu);
            r.camUp = imu.cameraUps(r, 15);
            double[] up = Measure.gravityUp(r);
            assertNotNull(up);
            assertEquals(0, up[0], 1e-3);
            assertEquals(-1, up[1], 1e-3);
            assertEquals(0, up[2], 1e-3);
            double[] agree = imu.poseAgreement(r, 15);
            assertTrue("giro " + agree[1], agree[1] < 0.1);
        }
    }

    @Test
    public void unaPoseMalaSeNota() {
        Sfm.Result r = orbit();
        Imu imu = Imu.parse(csv(r, Lin.rodrigues(new double[]{0.3, 0.2, 0.1}), 5_000_000_000L, 15, true));
        // La cámara 10 girada 8° de más: la diferencia máxima con los sensores lo muestra
        r.poses.get(10).r = Lin.mul(Lin.rodrigues(new double[]{0, Math.toRadians(8), 0}), r.poses.get(10).r);
        double[] agree = imu.poseAgreement(r, 15);
        assertTrue("mediana " + agree[0], agree[0] < 0.1);
        assertEquals(8, agree[1], 0.5);
    }

    @Test
    public void sinDatosNoHaySensores() {
        assertNull(Imu.parse("#navaja-sensores-1\n#inicio_video_ns=10\n"));
        assertNull(Imu.parse("r,1,0,0,0,1\n"));
        Sfm.Result r = orbit();
        assertNull(Measure.gravityUp(r));
    }
}
