package com.josexato.navaja.measure.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Modo "Hoja": la reconstrucción con las poses que dan las marcas de la
 * hoja en vez de las del SfM. Cada cuadro con al menos
 * {@link Lens#MIN_MARKERS} marcas y error de esquinas bajo
 * {@link SheetPose#MAX_RMS} queda ubicado en mm, en el sistema de la hoja
 * (Z hacia arriba); de esos se eligen los cuadros clave y las pistas KLT
 * que se ven en 3 o más se triangulan. El resultado es un {@link Sfm.Result}
 * común, así que siluetas, tallado, nube densa, textura y ZIP se reutilizan
 * sin cambios, y todo queda en mm.
 */
public final class SheetScan {

    private SheetScan() { }

    /** Cámaras como máximo (el tallado y la textura crecen con ellas); separación mínima entre cuadros clave. */
    static final int MAX_CAMS = 150, MIN_GAP = 2;
    /** Error de reproyección (px) y paralaje (°) mínimos para aceptar un punto triangulado. */
    static final double MAX_REPROJ = 2, MIN_PARALLAX = 2;
    /** Puntos del plano usados para reajustar las cámaras: paralaje mínimo (°). */
    static final double ANCHOR_PARALLAX = 4;
    /** Cuánto (mm) puede salirse el objeto del borde de la hoja. */
    static final double PAGE_MARGIN = 30;
    /** Usar que la hoja es plana (puntos del plano en Z = 0 y cámaras reajustadas con ellos) y cuántas vueltas de reajuste. */
    static boolean PLANE = true;
    static int PLANE_ROUNDS = 3;
    /** Quitar la peor observación de una pista (y volver a triangular) en vez de descartarla entera. */
    static boolean DROP_BAD_OBS = true;

    /**
     * Mayor ángulo (°) bajo el que dos de las cámaras ven el punto, medido
     * contra la primera. No basta comparar la primera con la última: en una
     * vuelta completa quedan casi juntas y un punto visto todo el tiempo
     * parecería sin paralaje.
     */
    static double parallax(double[] x, Geometry.Pose[] ps) {
        double[] c0 = ps[0].center();
        double best = 0;
        for (int i = 1; i < ps.length; i++) best = Math.max(best, Geometry.angleDeg(x, c0, ps[i].center()));
        return best;
    }

    /** Desvío robusto (1,4826 × mediana de |x − mediana|); 0 sin datos. */
    static double robustSd(List<Double> v) {
        if (v.isEmpty()) return 0;
        double[] a = v.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double m = a[a.length / 2];
        double[] d = new double[a.length];
        for (int i = 0; i < a.length; i++) d[i] = Math.abs(a[i] - m);
        Arrays.sort(d);
        return 1.4826 * d[d.length / 2];
    }

    /** Lo que pasó al armar la reconstrucción (para el diagnóstico). */
    public static final class Report {
        public int frames, withMarkers, posed, keyframes, tracks, triangulated;
        /** Error de las esquinas de las marcas en los cuadros ubicados (px): mediana y p90. */
        public double rmsMedian, rmsP90;
        public Lens lens;
        /** Error de reproyección de los puntos triangulados (px): mediana. */
        public double pointRms;
        /** De dónde salió el lente y, si hubo que corregirlo, por qué (para el diagnóstico). */
        public String lensNote = "";
        /** Puntos triangulados que salen de pistas KLT y de pistas XFeat. */
        public int kltPoints, xfeatPoints;
        /** XFeat: pistas que entraron (0 si no corrió) y cómo le fue (delegado, tiempos, pares; vacío si no corrió). */
        public int xfeatTracks;
        public String xfeatNote = "";
        /** Segunda pasada de XFeat sobre el objeto ampliado: fotos ampliables, aumento medio, pistas, puntos sumados, segundos y aviso si falló. */
        public int zoomFrames, zoomTracks, zoomPoints;
        public double zoomScale, zoomSeconds;
        public String zoomNote = "";
        /**
         * Hoja plana: puntos que quedaron en el plano Z = 0, cuántos anclaron el
         * reajuste de las cámaras, su ruido de altura triangulados libres (mm,
         * desvío robusto) antes y después del reajuste, cuánto se movieron las
         * cámaras (mm, mediana) y el error de las esquinas después (px, mediana).
         */
        public int planePoints, planeAnchors;
        /** Siluetas corregidas con la hoja ({@link SheetMasks}): fotos, píxeles borrados y agregados, puntos que agregaron. */
        public int maskFixed, maskPoints;
        public long maskClipped, maskAdded;
        /** Consenso entre siluetas: parecido mediano (IoU) con lo que predicen las demás, cuántas no calzaron, cuántas se repararon y cuántas se descartaron. */
        public double maskIou;
        public int maskFlagged, maskRepaired, maskDropped;
        /** Cobertura de las fotos clave: vuelta recorrida (°), altura de la cámara (°), paso entre fotos (°) y huecos {cuadro desde, cuadro hasta, grados}. */
        public double turnDeg, elevMin, elevMax, stepMedian, stepMax;
        public final List<double[]> gaps = new ArrayList<>();
        /** Observaciones malas quitadas de pistas (en vez de descartar la pista entera). */
        public int droppedObs;
        public double sheetNoiseBefore, sheetNoiseAfter, poseShiftMm, cornerRmsAfter;
    }

    /**
     * El lente para el video: la focal de la cámara ({@code cameraF}, de la
     * grabación en la app; 0 si no hay) si explica las marcas casi tan bien
     * como la que se estima con la hoja (error a lo sumo 10 % mayor); si no,
     * o sin dato de la cámara (video de la galería), la estimada con la
     * hoja, y lo deja anotado en {@code rep.lensNote}. Null si la hoja se vio
     * en muy pocos cuadros para estimarla.
     */
    public static Lens chooseLens(List<List<Markers.Marker>> markers, double cameraF, int w, int h, Report rep) {
        Lens init = new Lens(cameraF > 0 ? cameraF : 0.85 * Math.max(w, h), w / 2.0, h / 2.0);
        Lens est = Lens.estimate(markers, init);
        String dist = "";
        if (est != null) {
            // El resto del proceso (siluetas, tallado, textura) supone una cámara sin distorsión: si es chica, se descarta
            if (Math.abs(est.k1) < 0.03) est.k1 = 0;
            else dist = String.format(java.util.Locale.ROOT, " · ⚠ distorsión del lente k1 = %.3f: las poses la corrigen, las siluetas y la textura no", est.k1);
        }
        if (cameraF > 0) {
            Lens cam = init.copy();
            cam.source = "cámara";
            if (est == null) {
                rep.lensNote = String.format(java.util.Locale.ROOT, "focal de la cámara (%.0f px); muy pocos cuadros con la hoja para comprobarla", cameraF);
                return cam;
            }
            List<List<Markers.Marker>> use = new ArrayList<>();
            for (List<Markers.Marker> ms : markers) if (ms.size() >= Lens.MIN_MARKERS) use.add(ms);
            double cc = Lens.cost(use, cam), ce = Lens.cost(use, est);
            if (cc <= 1.1 * ce + 0.05) {
                rep.lensNote = String.format(java.util.Locale.ROOT, "focal de la cámara (%.0f px); la hoja la confirma (la estimada con la hoja: %.0f px)", cameraF, est.f);
                return cam;
            }
            rep.lensNote = String.format(java.util.Locale.ROOT, "⚠ la focal de la cámara (%.0f px) no calza con la hoja (%.2f px de error contra %.2f): se usa la estimada "
                    + "con la hoja, %.0f px (la estabilización del video suele recortar la imagen)", cameraF, cc, ce, est.f) + dist;
            return est;
        }
        if (est == null) return null;
        rep.lensNote = String.format(java.util.Locale.ROOT, "⚠ video sin datos de la cámara (de la galería): focal estimada con la hoja, %.0f px", est.f) + dist;
        return est;
    }

    /**
     * Cómo girar los cuadros (grados, horario: 0, 90, 180 o 270) para que la
     * hoja quede "derecha": que la vertical del mundo (+Z de la hoja) apunte
     * hacia arriba en la imagen. Sirve cuando no hay sensores (un video de
     * la galería grabado con el celular apaisado). Con una focal aproximada
     * alcanza: sólo importa hacia dónde apunta "arriba". 0 si la hoja no se
     * vio en ningún cuadro.
     */
    public static int uprightTurn(List<List<Markers.Marker>> markers, int w, int h) {
        Lens l = new Lens(0.85 * Math.max(w, h), w / 2.0, h / 2.0);
        int[] votes = new int[4];
        for (List<Markers.Marker> ms : markers) {
            if (ms.size() < Lens.MIN_MARKERS) continue;
            SheetPose.Fix fx = SheetPose.solve(ms, l);
            if (fx == null) continue;
            // "Arriba" del mundo en la cámara (y de la imagen crece hacia abajo)
            double[] up = Lin.mulV(fx.pose.r, new double[]{0, 0, 1});
            if (Math.hypot(up[0], up[1]) < 0.3) continue; // mirando casi derecho hacia abajo: no se sabe
            double a = Math.toDegrees(Math.atan2(up[0], -up[1])); // 0 = hacia arriba, 90 = hacia la derecha
            int q = (int) Math.round(a / 90);
            votes[((-q % 4) + 4) % 4]++;
        }
        int best = 0;
        for (int k = 1; k < 4; k++) if (votes[k] > votes[best]) best = k;
        return votes[best] == 0 ? 0 : 90 * best;
    }

    /**
     * La pose de cada cuadro ({@code markers.get(f)}: las marcas del cuadro
     * f); null donde no se pudo. Llena los conteos de {@code rep}.
     */
    public static SheetPose.Fix[] poses(List<List<Markers.Marker>> markers, Lens lens, Report rep) {
        SheetPose.Fix[] out = new SheetPose.Fix[markers.size()];
        List<Double> rms = new ArrayList<>();
        for (int f = 0; f < out.length; f++) {
            List<Markers.Marker> ms = markers.get(f);
            if (ms.size() < Lens.MIN_MARKERS) continue;
            rep.withMarkers++;
            SheetPose.Fix fx = SheetPose.solve(ms, lens);
            if (fx == null || fx.rms > SheetPose.MAX_RMS) continue;
            out[f] = fx;
            rms.add(fx.rms);
        }
        rep.frames = out.length;
        rep.posed = rms.size();
        if (!rms.isEmpty()) {
            double[] r = rms.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            rep.rmsMedian = r[r.length / 2];
            rep.rmsP90 = r[r.length * 9 / 10];
        }
        return out;
    }

    /** Cambio de punto de vista (°, visto desde el centro de la hoja) entre cuadros clave, y a partir de cuánto es un hueco que se avisa. */
    public static final double VIEW_STEP = 2.5, GAP_WARN = 10;
    /** Punto que se mira para medir el cambio de vista: sobre el centro de la hoja, a media altura de un objeto típico (mm). */
    static final double[] TARGET = {0, 0, 40};

    /** Dirección desde el centro de la hoja hacia la cámara del cuadro f. */
    private static double[] viewDir(SheetPose.Fix fx) { return Lin.unit(Lin.sub(fx.pose.center(), TARGET)); }

    private static double angle(double[] a, double[] b) {
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, Lin.dot(a, b)))));
    }

    /**
     * Los cuadros clave, <b>por ángulo</b>: entre los cuadros ubicados, uno
     * cada {@link #VIEW_STEP}° de cambio de punto de vista (más, si hace
     * falta para no pasar de {@link #MAX_CAMS}); entre los cuadros que
     * cumplen se toma el más nítido ({@code sharp}: nitidez por cuadro, o
     * null). Así una pausa no gasta fotos repetidas y un tramo rápido usa
     * todos los cuadros que tenga. Null con menos de 8 cuadros ubicados.
     */
    public static List<Integer> keyframes(SheetPose.Fix[] fixes, List<Float> sharp) {
        List<Integer> posed = new ArrayList<>();
        for (int f = 0; f < fixes.length; f++) if (fixes[f] != null) posed.add(f);
        if (posed.size() < 8) return null;
        double[][] dir = new double[posed.size()][];
        for (int i = 0; i < dir.length; i++) dir[i] = viewDir(fixes[posed.get(i)]);
        double total = 0;
        for (int i = 1; i < dir.length; i++) total += angle(dir[i - 1], dir[i]);
        double step = Math.max(VIEW_STEP, total / MAX_CAMS);
        List<Integer> out = new ArrayList<>();
        out.add(posed.get(0));
        int last = 0;
        for (int i = 1; i < dir.length; i++) {
            if (angle(dir[last], dir[i]) < step) continue;
            // Entre este y los siguientes que todavía están cerca del paso (hasta 1,4×), el más nítido
            int best = i;
            if (sharp != null)
                for (int j = i + 1; j < dir.length && angle(dir[last], dir[j]) < 1.4 * step; j++)
                    if (val(sharp, posed.get(j)) > val(sharp, posed.get(best))) best = j;
            out.add(posed.get(best));
            last = best;
            i = best;
        }
        if (out.size() < 8) {
            // Casi sin movimiento: al menos los cuadros repartidos en el tiempo (dará pocos puntos, pero se informa)
            out.clear();
            int gap = Math.max(MIN_GAP, posed.size() / 30);
            for (int i = 0; i < posed.size(); i += gap) out.add(posed.get(i));
        }
        return out;
    }

    private static float val(List<Float> sharp, int f) { return f < sharp.size() ? sharp.get(f) : 0; }

    /** Los cuadros clave por ángulo, sin mirar la nitidez. */
    public static List<Integer> keyframes(SheetPose.Fix[] fixes) { return keyframes(fixes, null); }

    /**
     * Cobertura de los cuadros clave, para el informe: llena
     * {@code rep.coverage} (vuelta recorrida alrededor de la hoja, altura de
     * la cámara, paso típico) y {@code rep.gaps} (saltos de más de
     * {@link #GAP_WARN}° entre fotos seguidas: {cuadro desde, cuadro hasta, grados}).
     */
    public static void coverage(SheetPose.Fix[] fixes, List<Integer> keys, Report rep) {
        rep.gaps.clear();
        if (keys == null || keys.size() < 2) return;
        double[] steps = new double[keys.size() - 1];
        double az0 = 0, azMin = 0, azMax = 0, prevAz = 0, elMin = 90, elMax = -90;
        for (int i = 0; i < keys.size(); i++) {
            double[] d = viewDir(fixes[keys.get(i)]);
            double az = Math.toDegrees(Math.atan2(d[1], d[0])), el = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, d[2]))));
            elMin = Math.min(elMin, el);
            elMax = Math.max(elMax, el);
            if (i == 0) { az0 = az; prevAz = az; continue; }
            // Azimut continuo (sin saltos de 360°)
            double da = az - (prevAz - Math.floor((prevAz + 180) / 360) * 360);
            da -= 360 * Math.round(da / 360);
            prevAz += da;
            azMin = Math.min(azMin, prevAz - az0);
            azMax = Math.max(azMax, prevAz - az0);
            steps[i - 1] = angle(viewDir(fixes[keys.get(i - 1)]), d);
            if (steps[i - 1] > GAP_WARN) rep.gaps.add(new double[]{keys.get(i - 1), keys.get(i), steps[i - 1]});
        }
        double[] st = steps.clone();
        Arrays.sort(st);
        rep.turnDeg = Math.min(360, azMax - azMin);
        rep.elevMin = elMin;
        rep.elevMax = elMax;
        rep.stepMedian = st[st.length / 2];
        rep.stepMax = st[st.length - 1];
    }

    /**
     * La reconstrucción: cuadros clave por ángulo entre los ubicados (ver
     * {@link #keyframes}) y las pistas trianguladas con esas poses. Null con
     * menos de 8 cuadros ubicados o 50 puntos.
     */
    public static Sfm.Result build(List<Sfm.Track> tracks, SheetPose.Fix[] fixes, Lens lens, int w, int h, Report rep) {
        return build(tracks, fixes, lens, w, h, rep, keyframes(fixes));
    }

    /** Igual, con los cuadros clave {@code keys} ya elegidos. */
    public static Sfm.Result build(List<Sfm.Track> tracks, SheetPose.Fix[] fixes, Lens lens, int w, int h, Report rep, List<Integer> keys) {
        if (keys == null) return null;
        coverage(fixes, keys, rep);
        Sfm.Result r = new Sfm.Result();
        r.width = w;
        r.height = h;
        r.focal = lens.f;
        r.frames = fixes.length;
        for (int f : keys) {
            r.camFrames.add(f);
            r.poses.add(fixes[f].pose.copy()); // se afinan aquí: no tocar las de cada cuadro
        }
        rep.keyframes = r.poses.size();
        rep.tracks = tracks.size();
        // Observaciones de cada pista en los cuadros clave (una vez)
        List<Sfm.Track> tks = new ArrayList<>();
        List<int[]> tCams = new ArrayList<>();
        List<double[][]> tObs = new ArrayList<>();
        for (Sfm.Track t : tracks) {
            List<Integer> cs = new ArrayList<>();
            for (int c = 0; c < r.camFrames.size(); c++) if (t.visible(r.camFrames.get(c))) cs.add(c);
            if (cs.size() < 3) continue;
            int[] ca = cs.stream().mapToInt(Integer::intValue).toArray();
            double[][] o = new double[ca.length][];
            for (int i = 0; i < ca.length; i++) {
                int fr = r.camFrames.get(ca[i]);
                o[i] = lens.normalize(t.x(fr), t.y(fr));
            }
            tks.add(t);
            tCams.add(ca);
            tObs.add(o);
        }
        // Triangulación; con la hoja plana: los puntos del plano quedan en Z = 0 y anclan un reajuste de cada cámara
        // (esquinas de las marcas + esos puntos), y se vuelve a triangular. Las marcas fijan el sistema: no se corre.
        double[][] pos = new double[tks.size()][];
        int[] kind = new int[tks.size()];
        double[] errOf = new double[tks.size()];
        double[][] c0 = new double[r.poses.size()][];
        for (int c = 0; c < c0.length; c++) c0[c] = r.poses.get(c).center();
        for (int round = 0; ; round++) {
            List<Double> freeZ = new ArrayList<>();
            for (int k = 0; k < tks.size(); k++) {
                int[] ca = tCams.get(k);
                Geometry.Pose[] ps = new Geometry.Pose[ca.length];
                for (int i = 0; i < ca.length; i++) ps[i] = r.poses.get(ca[i]);
                double[][] ob = tObs.get(k);
                pos[k] = null;
                kind[k] = 0;
                double[] x = Geometry.triangulate(ps, ob);
                if (x == null) continue;
                double worst = SheetPlane.worst(ps, ob, x, lens.f);
                // Una observación mala (un par equivocado en una pista larga) se quita, no la pista entera
                while (worst > MAX_REPROJ && ca.length > 3 && DROP_BAD_OBS) {
                    int bad = 0;
                    double be = -1;
                    for (int i = 0; i < ca.length; i++) {
                        double[] xc = ps[i].apply(x);
                        double e = xc[2] <= 0 ? 1e9 : Math.hypot(xc[0] / xc[2] - ob[i][0], xc[1] / xc[2] - ob[i][1]);
                        if (e > be) { be = e; bad = i; }
                    }
                    int[] nc = new int[ca.length - 1];
                    double[][] no = new double[ca.length - 1][];
                    for (int i = 0, j = 0; i < ca.length; i++) if (i != bad) { nc[j] = ca[i]; no[j++] = ob[i]; }
                    ca = nc;
                    ob = no;
                    ps = new Geometry.Pose[ca.length];
                    for (int i = 0; i < ca.length; i++) ps[i] = r.poses.get(ca[i]);
                    x = Geometry.triangulate(ps, ob);
                    if (x == null) break;
                    worst = SheetPlane.worst(ps, ob, x, lens.f);
                }
                if (x == null || worst > MAX_REPROJ) continue;
                if (ca.length != tCams.get(k).length) {
                    tCams.set(k, ca);
                    tObs.set(k, ob);
                }
                if (parallax(x, ps) < MIN_PARALLAX) continue;
                double[] p = PLANE ? SheetPlane.snap(ps, ob, x, lens.f) : null;
                if (p != null) {
                    freeZ.add(x[2]);
                    pos[k] = p;
                    errOf[k] = SheetPlane.worst(ps, ob, p, lens.f);
                    // Ancla para las cámaras sólo si está bien determinado: 4+ vistas y buen ángulo entre ellas
                    kind[k] = ca.length >= 4 && parallax(x, ps) >= ANCHOR_PARALLAX ? 2 : 1;
                    continue;
                }
                if (x[2] < -5) continue; // debajo de la hoja: un error
                pos[k] = x;
                errOf[k] = worst;
                kind[k] = 3;
            }
            double noise = robustSd(freeZ);
            if (round == 0) {
                rep.sheetNoiseBefore = noise;
                r.sheetNoise = noise;
            }
            rep.sheetNoiseAfter = noise;
            if (!PLANE || round >= PLANE_ROUNDS) break;
            // Reajuste de cada cámara: esquinas de las marcas (fijas) + puntos del plano que ve
            List<List<double[]>> xs = new ArrayList<>(), os = new ArrayList<>();
            for (int c = 0; c < r.poses.size(); c++) {
                xs.add(new ArrayList<>());
                os.add(new ArrayList<>());
                SheetPose.Fix fx = fixes[r.camFrames.get(c)];
                if (fx.sheetXs == null) continue;
                for (int i = 0; i < fx.sheetXs.length; i++) {
                    xs.get(c).add(fx.sheetXs[i]);
                    os.get(c).add(fx.obs[i]);
                }
            }
            int anchors = 0;
            for (int k = 0; k < tks.size(); k++) {
                if (kind[k] != 2) continue;
                anchors++;
                int[] ca = tCams.get(k);
                for (int i = 0; i < ca.length; i++) {
                    xs.get(ca[i]).add(pos[k]);
                    os.get(ca[i]).add(tObs.get(k)[i]);
                }
            }
            rep.planeAnchors = anchors;
            for (int c = 0; c < r.poses.size(); c++) {
                if (xs.get(c).size() < 8) continue;
                Geometry.Pose p = r.poses.get(c), before = p.copy();
                Geometry.refinePose(p, xs.get(c).toArray(new double[0][]), os.get(c).toArray(new double[0][]), lens.f, 2, 10);
                // Un reajuste que mueve la cámara más de 2 cm o la pone detrás de la hoja no se acepta
                if (Lin.norm(Lin.sub(p.center(), before.center())) > 20 || p.apply(new double[]{0, 0, 0})[2] <= 0) {
                    p.r = before.r;
                    p.t = before.t;
                }
            }
        }
        // Cuánto se movieron las cámaras al afinarlas (mediana, mm)
        double[] mv = new double[c0.length];
        for (int c = 0; c < c0.length; c++) mv[c] = Lin.norm(Lin.sub(r.poses.get(c).center(), c0[c]));
        Arrays.sort(mv);
        rep.poseShiftMm = mv.length == 0 ? 0 : mv[mv.length / 2];
        // Error de las esquinas con las cámaras finales (mediana por cámara, px)
        double[] cr = new double[r.poses.size()];
        for (int c = 0; c < cr.length; c++) {
            SheetPose.Fix fx = fixes[r.camFrames.get(c)];
            double s2 = 0;
            int n = 0;
            if (fx.sheetXs != null)
                for (int i = 0; i < fx.sheetXs.length; i++) {
                    double[] xc = r.poses.get(c).apply(fx.sheetXs[i]);
                    s2 += Math.pow(lens.f * (xc[0] / xc[2] - fx.obs[i][0]), 2) + Math.pow(lens.f * (xc[1] / xc[2] - fx.obs[i][1]), 2);
                    n++;
                }
            cr[c] = n == 0 ? 0 : Math.sqrt(s2 / n);
        }
        Arrays.sort(cr);
        rep.cornerRmsAfter = cr.length == 0 ? 0 : cr[cr.length / 2];
        r.sheetPlane = PLANE;
        rep.kltPoints = rep.xfeatPoints = rep.planePoints = rep.droppedObs = 0;
        List<double[]> pts = new ArrayList<>();
        List<Integer> cols = new ArrayList<>(), nobs = new ArrayList<>();
        List<List<Sfm.Track>> ptTracks = new ArrayList<>();
        List<Double> errs = new ArrayList<>();
        for (int k = 0; k < tks.size(); k++) {
            if (pos[k] == null) continue;
            Sfm.Track t = tks.get(k);
            pts.add(pos[k]);
            cols.add(t.color);
            nobs.add(tCams.get(k).length);
            List<Sfm.Track> l = new ArrayList<>();
            int[] ca = tCams.get(k);
            int seen = 0;
            for (int c = 0; c < r.camFrames.size(); c++) if (t.visible(r.camFrames.get(c))) seen++;
            if (ca.length < seen) {
                // Se quitaron observaciones malas: la pista que queda, sólo con las fotos usadas (para COLMAP, siluetas, etc.)
                int[] fr = new int[ca.length];
                float[] xs = new float[ca.length], ys = new float[ca.length];
                for (int i = 0; i < ca.length; i++) {
                    fr[i] = r.camFrames.get(ca[i]);
                    xs[i] = t.x(fr[i]);
                    ys[i] = t.y(fr[i]);
                }
                l.add(Sfm.Track.sparse(fr, xs, ys, t.color));
                rep.droppedObs += seen - ca.length;
            } else l.add(t);
            ptTracks.add(l);
            errs.add(errOf[k]);
            if (kind[k] == 1 || kind[k] == 2) rep.planePoints++;
            if (t.isSparse()) rep.xfeatPoints++;
            else rep.kltPoints++;
        }
        rep.triangulated = pts.size();
        if (pts.size() < 50) return null;
        double[] e = errs.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        rep.pointRms = e[e.length / 2];
        r.points = pts.toArray(new double[0][]);
        r.colors = cols.stream().mapToInt(Integer::intValue).toArray();
        r.nObs = nobs.stream().mapToInt(Integer::intValue).toArray();
        r.pointTracks = ptTracks;
        r.rms = rep.pointRms;
        r.startFrame = r.camFrames.get(0);
        r.endFrame = r.camFrames.get(r.camFrames.size() - 1);
        r.keyframes = r.poses.size();
        r.parts.add(new int[]{r.startFrame, r.endFrame, r.poses.size(), r.points.length});
        // "Arriba" es +Z de la hoja en todas las cámaras
        r.camUp = new double[r.poses.size()][];
        for (int c = 0; c < r.camUp.length; c++) r.camUp[c] = Lin.mulV(r.poses.get(c).r, new double[]{0, 0, 1});
        return r;
    }

    /**
     * El objeto medido sobre la hoja: el apoyo es Z = 0 (normal +Z) y su
     * ruido sale de los puntos que caen sobre la hoja (3 desvíos robustos,
     * al menos 1 mm). Null si no hay objeto.
     */
    public static Measure.Choice measure(Sfm.Result r) {
        double thr = 1;
        if (r.sheetPlane) thr = Math.max(1, 3 * r.sheetNoise); // los puntos del plano ya están en Z = 0: el ruido se midió antes
        else {
            List<Double> near = new ArrayList<>();
            for (double[] p : r.points) if (Math.abs(p[2]) < 5) near.add(Math.abs(p[2]));
            if (near.size() > 10) {
                double[] z = near.stream().mapToDouble(Double::doubleValue).toArray();
                Arrays.sort(z);
                thr = Math.max(1, 3 * 1.4826 * z[z.length / 2]);
            }
        }
        // El objeto está sobre la hoja: lo que sobresale fuera de ella (con un margen, por si el objeto se sale un poco) es el fondo
        double mx = Sheet.PAGE_W / 2.0 + PAGE_MARGIN, my = Sheet.PAGE_H / 2.0 + PAGE_MARGIN;
        Measure.Box b = Measure.measureOnPlane(r, new double[]{0, 0, 1}, new double[]{0, 0, 0}, thr, new double[]{-mx, mx, -my, my});
        if (b == null) return null;
        Measure.Choice c = new Measure.Choice();
        c.result = r;
        c.box = b;
        c.segments = 1;
        c.arcDeg = Measure.arc(r, b);
        return c;
    }
}
