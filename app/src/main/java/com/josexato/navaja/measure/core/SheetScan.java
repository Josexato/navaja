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
    /** Usar que la hoja es plana (puntos del plano en Z = 0 y cámaras reajustadas con ellos) y cuántas vueltas de reajuste. */
    static boolean PLANE = true;
    static int PLANE_ROUNDS = 3;

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
        /**
         * Hoja plana: puntos que quedaron en el plano Z = 0, cuántos anclaron el
         * reajuste de las cámaras, su ruido de altura triangulados libres (mm,
         * desvío robusto) antes y después del reajuste, cuánto se movieron las
         * cámaras (mm, mediana) y el error de las esquinas después (px, mediana).
         */
        public int planePoints, planeAnchors;
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

    /**
     * Los cuadros clave: entre los ubicados, separados al menos {@code minGap}
     * cuadros (más si hacen falta para no pasar de {@link #MAX_CAMS}). Null
     * con menos de 8 cuadros ubicados.
     */
    public static List<Integer> keyframes(SheetPose.Fix[] fixes, int minGap) {
        List<Integer> posed = new ArrayList<>();
        for (int f = 0; f < fixes.length; f++) if (fixes[f] != null) posed.add(f);
        if (posed.size() < 8) return null;
        int gap = Math.max(minGap, (int) Math.ceil((double) (posed.get(posed.size() - 1) - posed.get(0) + 1) / MAX_CAMS));
        List<Integer> out = new ArrayList<>();
        int last = -1000;
        for (int f : posed) {
            if (f - last < gap) continue;
            out.add(f);
            last = f;
        }
        return out;
    }

    /** Los cuadros clave con la separación de siempre ({@link #MIN_GAP}). */
    public static List<Integer> keyframes(SheetPose.Fix[] fixes) { return keyframes(fixes, MIN_GAP); }

    /**
     * La reconstrucción: cuadros clave entre los ubicados (al menos
     * {@link #MIN_GAP} cuadros entre sí, a lo sumo {@link #MAX_CAMS}), y las
     * pistas trianguladas con esas poses. Null con menos de 8 cuadros
     * ubicados o 50 puntos.
     */
    public static Sfm.Result build(List<Sfm.Track> tracks, SheetPose.Fix[] fixes, Lens lens, int w, int h, Report rep) {
        return build(tracks, fixes, lens, w, h, rep, MIN_GAP);
    }

    /** Igual, con {@code minGap} cuadros como mínimo entre cuadros clave (1 si los cuadros ya son fotos espaciadas). */
    public static Sfm.Result build(List<Sfm.Track> tracks, SheetPose.Fix[] fixes, Lens lens, int w, int h, Report rep, int minGap) {
        List<Integer> keys = keyframes(fixes, minGap);
        if (keys == null) return null;
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
                if (worst > MAX_REPROJ) continue;
                if (Geometry.angleDeg(x, ps[0].center(), ps[ps.length - 1].center()) < MIN_PARALLAX) continue;
                double[] p = PLANE ? SheetPlane.snap(ps, ob, x, lens.f) : null;
                if (p != null) {
                    freeZ.add(x[2]);
                    pos[k] = p;
                    errOf[k] = SheetPlane.worst(ps, ob, p, lens.f);
                    // Ancla para las cámaras sólo si está bien determinado: 4+ vistas y buen ángulo entre ellas
                    kind[k] = ca.length >= 4 && Geometry.angleDeg(x, ps[0].center(), ps[ps.length - 1].center()) >= ANCHOR_PARALLAX ? 2 : 1;
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
        rep.kltPoints = rep.xfeatPoints = rep.planePoints = 0;
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
            l.add(t);
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
        Measure.Box b = Measure.measureOnPlane(r, new double[]{0, 0, 1}, new double[]{0, 0, 0}, thr);
        if (b == null) return null;
        Measure.Choice c = new Measure.Choice();
        c.result = r;
        c.box = b;
        c.segments = 1;
        c.arcDeg = Measure.arc(r, b);
        return c;
    }
}
