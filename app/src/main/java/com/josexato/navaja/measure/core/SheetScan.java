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
        List<Integer> posed = new ArrayList<>();
        for (int f = 0; f < fixes.length; f++) if (fixes[f] != null) posed.add(f);
        if (posed.size() < 8) return null;
        int gap = Math.max(minGap, (int) Math.ceil((double) (posed.get(posed.size() - 1) - posed.get(0) + 1) / MAX_CAMS));
        Sfm.Result r = new Sfm.Result();
        r.width = w;
        r.height = h;
        r.focal = lens.f;
        r.frames = fixes.length;
        int last = -1000;
        for (int f : posed) {
            if (f - last < gap) continue;
            r.camFrames.add(f);
            r.poses.add(fixes[f].pose);
            last = f;
        }
        rep.keyframes = r.poses.size();
        rep.tracks = tracks.size();
        // Triangulación con las poses fijas (en mm)
        List<double[]> pts = new ArrayList<>();
        List<Integer> cols = new ArrayList<>(), nobs = new ArrayList<>();
        List<List<Sfm.Track>> ptTracks = new ArrayList<>();
        List<Double> errs = new ArrayList<>();
        List<Geometry.Pose> ps = new ArrayList<>();
        List<double[]> ob = new ArrayList<>();
        for (Sfm.Track t : tracks) {
            ps.clear();
            ob.clear();
            for (int c = 0; c < r.camFrames.size(); c++) {
                int fr = r.camFrames.get(c);
                if (!t.visible(fr)) continue;
                ps.add(r.poses.get(c));
                ob.add(lens.normalize(t.x(fr), t.y(fr)));
            }
            if (ps.size() < 3) continue;
            double[] x = Geometry.triangulate(ps.toArray(new Geometry.Pose[0]), ob.toArray(new double[0][]));
            if (x == null) continue;
            double worst = 0;
            boolean ok = true;
            for (int i = 0; i < ps.size() && ok; i++) {
                double[] xc = ps.get(i).apply(x);
                if (xc[2] <= 0) { ok = false; break; }
                worst = Math.max(worst, lens.f * Math.hypot(xc[0] / xc[2] - ob.get(i)[0], xc[1] / xc[2] - ob.get(i)[1]));
            }
            if (!ok || worst > MAX_REPROJ) continue;
            if (Geometry.angleDeg(x, ps.get(0).center(), ps.get(ps.size() - 1).center()) < MIN_PARALLAX) continue;
            if (x[2] < -5) continue; // debajo de la hoja: un error
            pts.add(x);
            cols.add(t.color);
            nobs.add(ps.size());
            List<Sfm.Track> l = new ArrayList<>();
            l.add(t);
            ptTracks.add(l);
            errs.add(worst);
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
        List<Double> near = new ArrayList<>();
        for (double[] p : r.points) if (Math.abs(p[2]) < 5) near.add(Math.abs(p[2]));
        double thr = 1;
        if (near.size() > 10) {
            double[] z = near.stream().mapToDouble(Double::doubleValue).toArray();
            Arrays.sort(z);
            thr = Math.max(1, 3 * 1.4826 * z[z.length / 2]);
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
