package com.josexato.navaja.measure.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Los sensores grabados junto al video (pantalla de grabación de Navaja):
 * la orientación del celular (vector de rotación, giroscopio + acelerómetro)
 * y la gravedad, con su hora. Dan dos cosas que la imagen sola no da: dónde
 * es "arriba" de verdad (la gravedad) y cuánto giró el celular entre dos
 * fotos (para revisar las poses calculadas con la imagen).
 *
 * <p>Formato (sensores.csv): líneas {@code #clave=valor} de cabecera
 * ({@code inicio_video_ns}: hora del primer cuadro del video, en el mismo
 * reloj que los sensores) y luego {@code r,t_ns,x,y,z,w} (cuaternión
 * dispositivo → mundo, mundo con Z hacia arriba) y {@code g,t_ns,x,y,z}
 * (gravedad en m/s², apunta hacia arriba como la mide el acelerómetro).
 *
 * <p>El video se graba con el celular vertical y la rotación necesaria para
 * verse derecho, así que en cada cuadro la cámara tiene x = x del celular,
 * y = −y (la imagen crece hacia abajo) y z = −z (la cámara trasera mira
 * hacia atrás de la pantalla).
 */
public final class Imu {

    final List<double[]> rot = new ArrayList<>(), grav = new ArrayList<>();
    long start;
    /** Cómo se grabó el lente ("fijo:ID", "lógica" o "" si no se anotó) y cada cambio de lente activo o de focal: {t_ns, lente, focal mm}. */
    public String lensMode = "";
    final List<String[]> lens = new ArrayList<>();

    /** Cambios de lente (no de enfoque) durante el video. */
    public int lensSwitches() {
        int n = 0;
        for (int i = 1; i < lens.size(); i++) if (!lens.get(i)[1].equals(lens.get(i - 1)[1])) n++;
        return n;
    }

    /** Para el informe: qué lente se usó y si cambió (con el segundo del video). */
    public String lensSummary() {
        if (lensMode.isEmpty() && lens.isEmpty()) return "";
        StringBuilder s = new StringBuilder(lensMode.startsWith("fijo:") ? "lente fijo (" + lensMode.substring(5) + ")" : "lente elegido por el teléfono");
        if (!lens.isEmpty()) s.append(String.format(Locale.ROOT, ", focal %s mm", lens.get(0)[2]));
        int sw = lensSwitches();
        if (sw == 0) s.append(" · sin cambios de lente");
        else {
            s.append(String.format(Locale.ROOT, " · ⚠ cambió de lente %d veces:", sw));
            for (int i = 1; i < lens.size(); i++)
                if (!lens.get(i)[1].equals(lens.get(i - 1)[1]))
                    s.append(String.format(Locale.ROOT, " %.1f s (%s → %s, %s mm)", (Long.parseLong(lens.get(i)[0]) - start) / 1e9, lens.get(i - 1)[1], lens.get(i)[1], lens.get(i)[2]));
        }
        return s.toString();
    }

    /**
     * Cómo se sostuvo el celular (giro horario desde vertical: 0, 90, 180 o
     * 270), el más frecuente según la gravedad. La pantalla de grabación
     * guarda el video siempre como si el celular estuviera vertical: si se
     * grabó apaisado, los cuadros se giran esto (horario) para verse
     * derechos, y las direcciones de la cámara de este objeto ya están en
     * los cuadros girados.
     */
    public int turn;

    /** Focal de la cámara en píxeles del video grabado (0 si no se guardó) y tamaño de ese video. */
    public double focalVideoPx;
    public int videoW, videoH;

    /** Focal en píxeles de un cuadro de {@code w}×{@code h} (el video escalado), o 0 si no se conoce. */
    public double focalFor(int w, int h) {
        if (focalVideoPx <= 0 || videoW <= 0 || videoH <= 0) return 0;
        return focalVideoPx * Math.max(w, h) / Math.max(videoW, videoH);
    }

    private Imu() { }

    /** Lee sensores.csv; null si no trae datos útiles. */
    public static Imu parse(String csv) {
        Imu m = new Imu();
        for (String line : csv.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) {
                if (line.startsWith("#inicio_video_ns=")) m.start = Long.parseLong(line.substring(17).trim());
                else if (line.startsWith("#f_video_px=")) m.focalVideoPx = Double.parseDouble(line.substring(12).trim());
                else if (line.startsWith("#lente=")) m.lensMode = line.substring(7).trim();
                else if (line.startsWith("#video=")) {
                    String[] wh = line.substring(7).trim().split("x");
                    if (wh.length == 2) { m.videoW = Integer.parseInt(wh[0].trim()); m.videoH = Integer.parseInt(wh[1].trim()); }
                }
                continue;
            }
            String[] f = line.split(",");
            try {
                if (f[0].equals("r") && f.length >= 6)
                    m.rot.add(new double[]{Long.parseLong(f[1]), d(f[2]), d(f[3]), d(f[4]), d(f[5])});
                else if (f[0].equals("g") && f.length >= 5)
                    m.grav.add(new double[]{Long.parseLong(f[1]), d(f[2]), d(f[3]), d(f[4])});
                else if (f[0].equals("l") && f.length >= 4)
                    m.lens.add(new String[]{f[1], f[2], f[3]});
            } catch (NumberFormatException e) {
                // línea cortada (p. ej. al final): se ignora
            }
        }
        if (m.start == 0 || (m.rot.isEmpty() && m.grav.isEmpty())) return null;
        m.turn = dominantTurn(m.grav);
        return m;
    }

    private static double d(String s) { return Double.parseDouble(s); }

    /**
     * Giro horario del celular desde vertical, el más frecuente (como
     * OrientationEventListener, redondeado a 90°); sólo cuentan las muestras
     * con el celular bastante parado (no acostado sobre la mesa).
     */
    static int dominantTurn(List<double[]> grav) {
        int[] votes = new int[4];
        for (double[] g : grav) {
            if (Math.hypot(g[1], g[2]) < 3) continue;
            double x = -g[1], y = -g[2];
            int o = (int) (90 - Math.round(Math.toDegrees(Math.atan2(-y, x))));
            o = ((o % 360) + 360) % 360;
            votes[((o + 45) / 90) % 4]++;
        }
        int best = 0;
        for (int k = 1; k < 4; k++) if (votes[k] > votes[best]) best = k;
        return 90 * best;
    }

    /** Lleva un vector de la cámara del video vertical a la de los cuadros girados {@link #turn}. */
    double[] toTurned(double[] v) {
        switch (turn) {
            case 90: return new double[]{-v[1], v[0], v[2]};
            case 180: return new double[]{-v[0], -v[1], v[2]};
            case 270: return new double[]{v[1], -v[0], v[2]};
            default: return v;
        }
    }

    public boolean hasRotation() { return rot.size() >= 2; }

    /** Muestras de orientación y de gravedad (para el informe). */
    public int samples() { return rot.size() + grav.size(); }

    /** "Arriba" (unitario, contrario a la gravedad) en coordenadas de la cámara en el segundo {@code t} del video; null si no hay datos. */
    public double[] upCamera(double t) {
        long ns = start + (long) (t * 1e9);
        double[] up;
        if (!grav.isEmpty()) {
            double[] g = interp(grav, ns, false);
            up = new double[]{g[0], g[1], g[2]};
        } else if (!rot.isEmpty()) {
            double[] r = matrix(interp(rot, ns, true));
            up = new double[]{r[6], r[7], r[8]}; // fila z: "arriba" del mundo visto desde el celular
        } else return null;
        double l = Lin.norm(up);
        if (l < 1e-6) return null;
        return toTurned(new double[]{up[0] / l, -up[1] / l, -up[2] / l});
    }

    /**
     * Giro de la cámara entre los segundos {@code t1} y {@code t2} del video,
     * como la matriz que lleva coordenadas de la cámara en t1 a la cámara en
     * t2 (la misma convención que R2·R1ᵀ de dos poses); null sin orientación.
     */
    public double[] relativeCamera(double t1, double t2) {
        if (!hasRotation()) return null;
        double[] a = cameraToWorld(t1), b = cameraToWorld(t2);
        return Lin.mul(Lin.tr(b), a);
    }

    /** Rotación cámara → mundo de los sensores en el segundo {@code t}. */
    double[] cameraToWorld(double t) {
        double[] r = matrix(interp(rot, start + (long) (t * 1e9), true));
        // cámara → celular: x igual, y y z al revés; si los cuadros se giraron, antes se deshace ese giro (Tᵀ)
        double[] c = Lin.mul(r, new double[]{1, 0, 0, 0, -1, 0, 0, 0, -1});
        if (turn == 0) return c;
        double[] tt = new double[9];
        for (int j = 0; j < 3; j++) {
            double[] e = new double[3];
            e[j] = 1;
            double[] col = toTurned(e); // columna j de T = fila j de Tᵀ
            for (int i = 0; i < 3; i++) tt[j * 3 + i] = col[i];
        }
        return Lin.mul(c, tt);
    }

    /** Matriz celular → mundo (por filas) del cuaternión {x, y, z, w}. */
    static double[] matrix(double[] q) {
        double x = q[0], y = q[1], z = q[2], w = q[3];
        double n = Math.sqrt(x * x + y * y + z * z + w * w);
        x /= n; y /= n; z /= n; w /= n;
        return new double[]{
                1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
                2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
                2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)};
    }

    /** Muestra interpolada linealmente en {@code ns} (los cuaterniones se renormalizan y se alinean de signo). */
    static double[] interp(List<double[]> s, long ns, boolean quat) {
        int lo = 0, hi = s.size() - 1;
        if (ns <= s.get(0)[0]) return tail(s.get(0));
        if (ns >= s.get(hi)[0]) return tail(s.get(hi));
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (s.get(mid)[0] <= ns) lo = mid;
            else hi = mid;
        }
        double[] a = s.get(lo), b = s.get(hi);
        double f = (ns - a[0]) / Math.max(1, b[0] - a[0]);
        double sign = 1;
        if (quat) {
            double dot = 0;
            for (int k = 1; k < a.length; k++) dot += a[k] * b[k];
            if (dot < 0) sign = -1;
        }
        double[] out = new double[a.length - 1];
        for (int k = 1; k < a.length; k++) out[k - 1] = a[k] * (1 - f) + sign * b[k] * f;
        return out;
    }

    private static double[] tail(double[] v) {
        double[] out = new double[v.length - 1];
        System.arraycopy(v, 1, out, 0, out.length);
        return out;
    }

    /** "Arriba" de cada cámara de {@code r} según la gravedad, en coordenadas de la cámara ({@code fps}: cuadros por segundo analizados). */
    public double[][] cameraUps(Sfm.Result r, double fps) {
        double[][] out = new double[r.poses.size()][];
        for (int c = 0; c < out.length; c++) out[c] = upCamera(r.camFrames.get(c) / fps);
        return out;
    }

    /**
     * Diferencia (grados) entre el giro del celular según la imagen y según
     * los sensores, entre fotos consecutivas en el tiempo: {mediana, máximo}.
     * Grande = la pose calculada con la imagen se equivocó en ese tramo.
     */
    public double[] poseAgreement(Sfm.Result r, double fps) {
        if (!hasRotation() || r.poses.size() < 2) return null;
        Integer[] o = new Integer[r.poses.size()];
        for (int c = 0; c < o.length; c++) o[c] = c;
        java.util.Arrays.sort(o, (a, b) -> Integer.compare(r.camFrames.get(a), r.camFrames.get(b)));
        double[] diff = new double[o.length - 1];
        for (int k = 1; k < o.length; k++) {
            Geometry.Pose p1 = r.poses.get(o[k - 1]), p2 = r.poses.get(o[k]);
            double[] img = Lin.mul(p2.r, Lin.tr(p1.r));
            double[] sen = relativeCamera(r.camFrames.get(o[k - 1]) / fps, r.camFrames.get(o[k]) / fps);
            diff[k - 1] = Math.toDegrees(Lin.norm(Lin.logRot(Lin.mul(Lin.tr(img), sen))));
        }
        double[] s = diff.clone();
        java.util.Arrays.sort(s);
        return new double[]{s[s.length / 2], s[s.length - 1]};
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%d orientaciones, %d gravedades", rot.size(), grav.size())
                + (turn != 0 ? String.format(Locale.ROOT, "; celular apaisado: los cuadros se giraron %d° para verse derechos", turn) : "");
    }
}
