package com.josexato.navaja.measure.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Del modelo 3D al objeto medido: encuentra el plano de apoyo (la pared o la
 * mesa: el plano con más puntos), separa los puntos que sobresalen de él,
 * agrupa por cercanía y se queda con el grupo que el video tuvo al centro
 * (lo que se estaba filmando). Mide su caja: alto, ancho y profundidad.
 * "Arriba" sale del primer cuadro (el celular estaba derecho al empezar).
 */
public final class Measure {

    private Measure() { }


    public static final class Box {
        /** Ejes de la caja (unitarios) y su largo, en unidades del modelo. */
        public double[] axisH, axisW, axisD;
        public double alto, ancho, prof, separation;
        public double[] center;
        /** Índices de los puntos del objeto. */
        public int[] objectPoints;
        /** true si el apoyo es una pared (si no, una mesa o el piso). */
        public boolean onWall;
        /**
         * true si "arriba" se conoce (el tramo empieza con el video, cuando el
         * celular estaba derecho). Si no, el alto es el eje más largo del objeto.
         */
        public boolean upKnown;
        public double[] planeN, planeP;
        /** Otros planos quitados antes de agrupar: {nx, ny, nz, px, py, pz, puntos quitados, grados con el apoyo}. */
        public List<double[]> otherPlanes = new ArrayList<>();

        public double[][] corners() {
            double[][] c = new double[8][];
            int k = 0;
            for (int a = -1; a <= 1; a += 2)
                for (int b = -1; b <= 1; b += 2)
                    for (int d = -1; d <= 1; d += 2)
                        c[k++] = Lin.add(Lin.add(Lin.add(center, Lin.scale(axisH, a * alto / 2)), Lin.scale(axisW, b * ancho / 2)),
                                Lin.scale(axisD, d * prof / 2));
            return c;
        }
    }

    /** El tramo elegido y su caja. */
    public static final class Choice {
        public Sfm.Result result;
        public Box box;
        public int segment, segments;
        /** Ángulo (grados) que recorrió el celular alrededor del objeto en ese tramo. */
        public double arcDeg;
    }

    /**
     * Mide el objeto en cada tramo y elige el mejor: el que lo vio con más
     * puntos y desde más ángulos (lo que más condiciona la profundidad).
     */
    public static Choice best(java.util.List<Sfm.Result> segs) {
        Choice c = null;
        double bestScore = -1;
        for (int i = 0; i < segs.size(); i++) {
            Sfm.Result r = segs.get(i);
            Box b = measure(r);
            if (b == null) continue;
            double arc = arc(r, b);
            double score = b.objectPoints.length * Math.sqrt(r.poses.size()) * Math.max(10, arc);
            if (score > bestScore) {
                bestScore = score;
                c = new Choice();
                c.result = r;
                c.box = b;
                c.segment = i;
                c.arcDeg = arc;
            }
        }
        if (c != null) c.segments = segs.size();
        return c;
    }

    /** Arco horizontal (grados) que cubren las cámaras vistas desde el centro del objeto. */
    public static double arc(Sfm.Result r, Box b) {
        double lo = 1e9, hi = -1e9;
        for (Geometry.Pose p : r.poses) {
            double[] v = Lin.sub(p.center(), b.center);
            double a = Math.toDegrees(Math.atan2(Lin.dot(v, b.axisW), Lin.dot(v, b.axisD)));
            lo = Math.min(lo, a);
            hi = Math.max(hi, a);
        }
        return hi - lo;
    }

    public static Box measure(Sfm.Result r) {
        double[][] p = r.points;
        int n = p.length;
        if (n < 50) return null;
        Random rnd = new Random(3);
        double[] med = median3(p);
        double[] dist = new double[n];
        for (int i = 0; i < n; i++) dist[i] = Lin.norm(Lin.sub(p[i], med));
        double sc = percentile(dist, 50);
        // 1) Plano de apoyo: RANSAC
        //    Una mesa o pared real deja a todas las cámaras de un mismo lado: los planos que las cortan (un corte
        //    oblicuo por el mouse pad y el fondo, con la taza del video "Feliz día papá") no valen, salvo que ninguno lo cumpla
        double thr = 0.012 * sc, bestCount = -1, anyCount = -1;
        double[] bn = null, bp = null, an = null, ap = null;
        double[][] eyes = new double[r.poses.size()][];
        for (int c = 0; c < eyes.length; c++) eyes[c] = r.poses.get(c).center();
        for (int it = 0; it < 1500; it++) {
            double[] a = p[rnd.nextInt(n)], b = p[rnd.nextInt(n)], c = p[rnd.nextInt(n)];
            double[] nn = Lin.cross(Lin.sub(b, a), Lin.sub(c, a));
            double l = Lin.norm(nn);
            if (l < 1e-12) continue;
            nn = Lin.scale(nn, 1 / l);
            int cnt = 0;
            for (double[] q : p) if (Math.abs(Lin.dot(Lin.sub(q, a), nn)) < thr) cnt++;
            if (cnt > anyCount) { anyCount = cnt; an = nn; ap = a; }
            if (cnt > bestCount && camerasOnOneSide(eyes, a, nn, SIDE_MARGIN * sc)) { bestCount = cnt; bn = nn; bp = a; }
        }
        if (bn == null) { bn = an; bp = ap; }
        // Reajuste del plano por mínimos cuadrados con sus puntos, y su ruido real (MAD)
        for (int pass = 0; pass < 2; pass++) {
            List<double[]> in = new ArrayList<>();
            for (double[] q : p) if (Math.abs(Lin.dot(Lin.sub(q, bp), bn)) < thr) in.add(q);
            double[] m = mean(in.toArray(new double[0][]));
            double[][] cov = new double[3][3];
            for (double[] q : in) {
                double[] d = Lin.sub(q, m);
                for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) cov[i][j] += d[i] * d[j];
            }
            bn = Lin.unit(((double[][]) Lin.eigSym(cov)[1])[0]);
            bp = m;
            double[] ad = new double[in.size()];
            for (int k = 0; k < ad.length; k++) ad[k] = Math.abs(Lin.dot(Lin.sub(in.get(k), bp), bn));
            double sigma = 1.4826 * percentile(ad, 50);
            thr = Math.max(thr, 3 * sigma);
        }
        // La normal apunta hacia las cámaras
        double side = 0;
        for (Geometry.Pose ps : r.poses) side += Lin.dot(Lin.sub(ps.center(), bp), bn);
        if (side < 0) bn = Lin.scale(bn, -1);
        // 2) Lo que sobresale del plano (más allá de 2× el ruido del plano)
        List<Integer> front = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double d = Lin.dot(Lin.sub(p[i], bp), bn);
            if (d > 2 * thr && dist[i] < 4 * sc) front.add(i);
        }
        if (front.size() < 20) return null;
        // 2b) Otros planos grandes entre lo que sobresale (la mesa bajo una máscara colgada, la pared detrás de algo
        //     sobre la mesa): si tocan el objeto, la agrupación por cercanía los une a él e infla la caja
        List<double[]> extra = otherPlanes(r, front, bn, thr, rnd);
        if (front.size() < 20) return null;
        // Separación típica entre vecinos: define qué tan cerca deben estar dos puntos para ser del mismo objeto
        // (con una muestra de hasta 500 puntos: la mediana no cambia y con nubes densas evita un cálculo n²)
        int step = Math.max(1, front.size() / 500);
        double[] nnd = new double[(front.size() + step - 1) / step];
        for (int a = 0, k = 0; a < front.size(); a += step, k++) {
            double b2 = Double.MAX_VALUE;
            for (int b = 0; b < front.size(); b++) {
                if (a == b) continue;
                double[] d = Lin.sub(p[front.get(a)], p[front.get(b)]);
                b2 = Math.min(b2, Lin.dot(d, d));
            }
            nnd[k] = Math.sqrt(b2);
        }
        // 3) Grupos por cercanía (grilla de vóxeles, vecinos de 26)
        double vox = 5 * percentile(nnd, 50);
        Map<Long, List<Integer>> grid = new HashMap<>();
        for (int i : front) grid.computeIfAbsent(key(p[i], vox), k -> new ArrayList<>()).add(i);
        Map<Integer, Integer> parent = new HashMap<>();
        for (int i : front) parent.put(i, i);
        for (int i : front) {
            long[] c = cell(p[i], vox);
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        List<Integer> nb = grid.get(pack(c[0] + dx, c[1] + dy, c[2] + dz));
                        if (nb == null) continue;
                        for (int j : nb) if (Lin.norm(Lin.sub(p[i], p[j])) < vox) union(parent, i, j);
                    }
        }
        Map<Integer, List<Integer>> groups = new HashMap<>();
        for (int i : front) groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        // 4) El grupo que estuvo al centro de la imagen más veces
        double cx = r.width / 2.0, cy = r.height / 2.0;
        List<Integer> best = null;
        double bestScore = -1;
        for (List<Integer> g : groups.values()) {
            if (g.size() < 15) continue;
            double score = 0;
            for (Geometry.Pose ps : r.poses)
                for (int i : g) {
                    double[] xc = ps.apply(p[i]);
                    if (xc[2] <= 0) continue;
                    double u = r.focal * xc[0] / xc[2], v = r.focal * xc[1] / xc[2];
                    if (Math.abs(u) < 0.3 * r.width && Math.abs(v) < 0.3 * r.height) score++;
                }
            if (score > bestScore) { bestScore = score; best = g; }
        }
        if (best == null) return null;
        // 4b) Completar: todo lo que sobresale del plano dentro del contorno del grupo (ampliado 10 %)
        //     y a una distancia de la pared parecida. Recupera las zonas con pocos puntos (nariz, mentón).
        double[] e1 = Lin.unit(Math.abs(bn[0]) < 0.9 ? Lin.cross(bn, new double[]{1, 0, 0}) : Lin.cross(bn, new double[]{0, 1, 0}));
        double[] e2 = Lin.cross(bn, e1);
        double[][] uv = new double[best.size()][];
        double dMin = 1e18, dMax = -1e18;
        for (int k = 0; k < uv.length; k++) {
            double[] q = Lin.sub(p[best.get(k)], bp);
            uv[k] = new double[]{Lin.dot(q, e1), Lin.dot(q, e2)};
            double d = Lin.dot(q, bn);
            dMin = Math.min(dMin, d);
            dMax = Math.max(dMax, d);
        }
        double[][] hull = Hull.convex(uv);
        double[] hc = new double[2];
        for (double[] h : hull) { hc[0] += h[0] / hull.length; hc[1] += h[1] / hull.length; }
        for (double[] h : hull) { h[0] = hc[0] + (h[0] - hc[0]) * 1.1; h[1] = hc[1] + (h[1] - hc[1]) * 1.1; }
        java.util.Set<Integer> inObj = new java.util.HashSet<>(best);
        double margin = 0.15 * (dMax - dMin);
        for (int i : front) {
            if (inObj.contains(i)) continue;
            double[] q = Lin.sub(p[i], bp);
            double d = Lin.dot(q, bn);
            if (d < dMin - margin || d > dMax + margin) continue;
            if (Hull.contains(hull, Lin.dot(q, e1), Lin.dot(q, e2))) { best.add(i); inObj.add(i); }
        }
        Box box = buildBox(r, bn, bp, best);
        box.otherPlanes = extra;
        return box;
    }

    /** Planos adicionales que se buscan, y lo mínimo para quitar uno: ángulo con el apoyo, puntos y qué tan "de borde" es. */
    static final int MAX_OTHER_PLANES = 2;
    static final double OTHER_MIN_DEG = 45, OTHER_MIN_FRACTION = 0.1, OTHER_CENTRALITY = 0.6;

    /**
     * RANSAC repetido sobre los puntos que sobresalen del plano de apoyo
     * ({@code front}, que se modifica): saca hasta 2 planos más y los puntos
     * del lado de atrás de cada uno (el objeto está del lado de las cámaras).
     * Para no confundir una cara plana del objeto (una caja) con una pared,
     * el plano debe formar ≥ 45° con el apoyo, tener ≥ 10 % de los puntos y
     * ser "de borde": sus puntos se ven al centro de las fotos bastante
     * menos que el resto (< 0,6 veces), porque lo que se filma está al centro.
     * Devuelve cada plano quitado como {nx, ny, nz, px, py, pz, puntos, grados}.
     */
    static List<double[]> otherPlanes(Sfm.Result r, List<Integer> front, double[] bn, double thr, Random rnd) {
        double[][] p = r.points;
        List<double[]> out = new ArrayList<>();
        double[] central = new double[p.length];
        for (int i : front) central[i] = centrality(r, p[i]);
        for (int k = 0; k < MAX_OTHER_PLANES && front.size() >= 40; k++) {
            int m = front.size(), bestCount = -1;
            double[] pn = null, pp = null;
            for (int it = 0; it < 800; it++) {
                double[] a = p[front.get(rnd.nextInt(m))], b = p[front.get(rnd.nextInt(m))], c = p[front.get(rnd.nextInt(m))];
                double[] nn = Lin.cross(Lin.sub(b, a), Lin.sub(c, a));
                double l = Lin.norm(nn);
                if (l < 1e-12) continue;
                nn = Lin.scale(nn, 1 / l);
                if (Math.abs(Lin.dot(nn, bn)) > Math.cos(Math.toRadians(OTHER_MIN_DEG))) continue;
                int cnt = 0;
                for (int i : front) if (Math.abs(Lin.dot(Lin.sub(p[i], a), nn)) < thr) cnt++;
                if (cnt > bestCount) { bestCount = cnt; pn = nn; pp = a; }
            }
            if (pn == null || bestCount < Math.max(30, OTHER_MIN_FRACTION * m)) break;
            // Reajuste por mínimos cuadrados
            List<double[]> in = new ArrayList<>();
            for (int i : front) if (Math.abs(Lin.dot(Lin.sub(p[i], pp), pn)) < thr) in.add(p[i]);
            pp = mean(in.toArray(new double[0][]));
            double[][] cov = new double[3][3];
            for (double[] q : in) {
                double[] d = Lin.sub(q, pp);
                for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) cov[i][j] += d[i] * d[j];
            }
            pn = Lin.unit(((double[][]) Lin.eigSym(cov)[1])[0]);
            double deg = Math.toDegrees(Math.acos(Math.min(1, Math.abs(Lin.dot(pn, bn)))));
            if (deg < OTHER_MIN_DEG) break;
            // Hacia las cámaras
            double side = 0;
            for (Geometry.Pose ps : r.poses) side += Lin.dot(Lin.sub(ps.center(), pp), pn);
            if (side < 0) pn = Lin.scale(pn, -1);
            // ¿De borde? centralidad media de sus puntos contra la del resto
            double cin = 0, cout = 0;
            int nin = 0, nout = 0;
            for (int i : front) {
                if (Math.abs(Lin.dot(Lin.sub(p[i], pp), pn)) < thr) { cin += central[i]; nin++; } else { cout += central[i]; nout++; }
            }
            if (nout == 0 || cin / nin >= OTHER_CENTRALITY * (cout / nout)) break;
            List<Integer> keep = new ArrayList<>();
            for (int i : front) if (Lin.dot(Lin.sub(p[i], pp), pn) > 2 * thr) keep.add(i);
            out.add(new double[]{pn[0], pn[1], pn[2], pp[0], pp[1], pp[2], front.size() - keep.size(), deg});
            front.clear();
            front.addAll(keep);
        }
        return out;
    }

    /** Fracción de las cámaras en las que el punto cae en el centro de la imagen (60 % central de cada lado). */
    static double centrality(Sfm.Result r, double[] x) {
        int c = 0;
        for (Geometry.Pose ps : r.poses) {
            double[] xc = ps.apply(x);
            if (xc[2] <= 0) continue;
            double u = r.focal * xc[0] / xc[2], v = r.focal * xc[1] / xc[2];
            if (Math.abs(u) < 0.3 * r.width && Math.abs(v) < 0.3 * r.height) c++;
        }
        return (double) c / Math.max(1, r.poses.size());
    }

    /**
     * Fracción máxima de cámaras que puede quedar claramente del otro lado
     * del plano de apoyo, y qué es "claramente": más allá del 30 % de la
     * escala de la escena. Filmando casi a ras de la mesa, el error de pose
     * deja algunas cámaras apenas debajo de ella: ésas no cuentan.
     */
    static final double OTHER_SIDE = 0.05, SIDE_MARGIN = 0.3;

    /** ¿Casi todas las cámaras (todas menos el 5 %) del mismo lado del plano, salvo las que están a menos de {@code margin} de él? */
    static boolean camerasOnOneSide(double[][] eyes, double[] p, double[] n, double margin) {
        int pos = 0, neg = 0;
        for (double[] e : eyes) {
            double d = Lin.dot(Lin.sub(e, p), n);
            if (d > margin) pos++;
            else if (d < -margin) neg++;
        }
        return Math.min(pos, neg) <= OTHER_SIDE * eyes.length;
    }

    /**
     * La caja del objeto: ejes (normal del plano de apoyo y "arriba" de las
     * cámaras) y medidas con los puntos {@code best}.
     */
    static Box buildBox(Sfm.Result r, double[] bn, double[] bp, List<Integer> best) {
        double[][] p = r.points;
        // 5) Ejes: normal del plano + "arriba" de la imagen. El celular (o la vista de un visor 3D) se mantiene
        //    más o menos derecho, así que se promedia el "arriba" de todas las cámaras del tramo: si coinciden,
        //    vale aunque el tramo no empiece con el video (antes sólo se usaba el primer cuadro del video).
        Box box = new Box();
        box.planeN = bn;
        box.planeP = bp;
        double[] up = gravityUp(r);
        if (up != null) {
            // Con sensores: "arriba" es la gravedad. Mesa si su normal está a menos de 60° de la vertical
            box.upKnown = true;
            box.onWall = Math.abs(Lin.dot(up, bn)) < GRAVITY_TABLE;
        } else {
            up = new double[3];
            for (Geometry.Pose ps : r.poses) up = Lin.add(up, new double[]{-ps.r[3], -ps.r[4], -ps.r[5]});
            double agree = Lin.norm(up) / r.poses.size(); // 1 = todas las cámaras con el mismo "arriba"
            up = Lin.unit(up);
            box.upKnown = r.startFrame <= 2 || agree >= UP_AGREE;
            // Pared o mesa: por la dirección media del "arriba" respecto del plano. Al orbitar mirando desde arriba, el
            // "arriba" de cada foto se inclina y el promedio se acorta (~0,5), pero su dirección sigue siendo clara.
            box.onWall = agree < 0.25 || Math.abs(Lin.dot(up, bn)) < 0.7;
        }
        double[][] obj = new double[best.size()][];
        for (int k = 0; k < obj.length; k++) obj[k] = p[best.get(k)];
        if (box.onWall && box.upKnown) {
            box.axisD = bn;
            double[] hUp = Lin.sub(up, Lin.scale(bn, Lin.dot(up, bn)));
            box.axisH = Lin.unit(hUp);
            box.axisW = Lin.cross(box.axisH, box.axisD);
        } else if (box.onWall) {
            // Sin "arriba" confiable: el alto es el eje más largo del objeto en el plano
            box.axisD = bn;
            box.axisH = inPlaneMajor(obj, bn);
            box.axisW = Lin.cross(box.axisH, box.axisD);
        } else {
            // En una mesa: el alto es la normal; largo y ancho por componentes principales en el plano
            box.axisH = Lin.dot(up, bn) >= 0 ? bn : Lin.scale(bn, -1);
            box.axisD = inPlaneMajor(obj, bn);
            box.axisW = Lin.cross(box.axisH, box.axisD);
        }
        extents(box, obj);
        box.objectPoints = best.stream().mapToInt(Integer::intValue).toArray();
        return box;
    }

    /**
     * Suma al objeto los puntos {@code from..} (los del seguimiento denso) que
     * caen dentro de su caja (con 5 % de margen) y no son de la pared o mesa.
     * La caja no cambia: la medida sale de la nube rala, que es la más
     * estable; los puntos densos sólo completan la vista y la exportación.
     */
    public static void include(Sfm.Result r, Box b, int from) {
        double[][] axes = {b.axisH, b.axisW, b.axisD};
        double[] half = {b.alto / 2 * 1.05, b.ancho / 2 * 1.05, b.prof / 2 * 1.05};
        double off = 0.02 * Math.max(b.alto, Math.max(b.ancho, b.prof));
        List<Integer> add = new ArrayList<>();
        for (int i = from; i < r.points.length; i++) {
            double[] d = Lin.sub(r.points[i], b.center);
            boolean in = Lin.dot(Lin.sub(r.points[i], b.planeP), b.planeN) > off;
            for (int a = 0; a < 3 && in; a++) in = Math.abs(Lin.dot(d, axes[a])) <= half[a];
            if (in) add.add(i);
        }
        int[] all = Arrays.copyOf(b.objectPoints, b.objectPoints.length + add.size());
        for (int k = 0; k < add.size(); k++) all[b.objectPoints.length + k] = add.get(k);
        b.objectPoints = all;
    }

    /** Con gravedad: el plano es mesa si |arriba · normal| ≥ 0,5 (normal a menos de 60° de la vertical). */
    static final double GRAVITY_TABLE = 0.5;

    /**
     * "Arriba" en el sistema del modelo según la gravedad de los sensores
     * (media de las cámaras: arriba de cada una llevado al modelo con su
     * pose); null si no hay sensores o no coinciden entre sí (poses malas).
     */
    public static double[] gravityUp(Sfm.Result r) {
        if (r.camUp == null) return null;
        double[] up = new double[3];
        int n = 0;
        for (int c = 0; c < r.poses.size() && c < r.camUp.length; c++) {
            if (r.camUp[c] == null) continue;
            up = Lin.add(up, Lin.mulV(Lin.tr(r.poses.get(c).r), r.camUp[c]));
            n++;
        }
        if (n < 3 || Lin.norm(up) < 0.8 * n) return null;
        return Lin.unit(up);
    }

    /** Coincidencia mínima del "arriba" de las cámaras para confiar en él (media de vectores unitarios). */
    static final double UP_AGREE = 0.9;

    /** Nombres de los puntos clave, en el orden de {@link #landmarks}. */
    public static final String[] LANDMARKS = {"arriba", "abajo", "izquierda", "derecha", "frente"};

    /**
     * Cinco puntos que marcan la geometría del objeto: el más alto, el más
     * bajo, el de más a la izquierda, el de más a la derecha y el más
     * alejado de la pared o mesa. Sólo puntos con pistas vistos desde 3 o
     * más cámaras; entre el 5 % más extremo, el más visto (el extremo
     * absoluto suele ser ruido y se ve en pocas fotos). Devuelve índices en
     * {@code r.points} (−1 si no hay).
     */
    public static int[] landmarks(Sfm.Result r, Box b) { return landmarks(r, b, null); }

    /**
     * Igual, y si hay volumen por siluetas ({@code hull}) sólo puntos que
     * caen dentro de él (con 2 vóxeles de margen): los puntos de la pared o
     * del piso que quedaron mal asignados al objeto no pueden ser puntos clave.
     */
    public static int[] landmarks(Sfm.Result r, Box b, Carve.Result hull) {
        List<Integer> good = new ArrayList<>();
        for (int i : b.objectPoints)
            if (i < r.pointTracks.size() && r.nObs[i] >= 3 && (hull == null || Carve.inside(hull, r.points[i], 2))) good.add(i);
        int[] out = new int[LANDMARKS.length];
        Arrays.fill(out, -1);
        if (good.isEmpty()) return out;
        double[][] key = new double[LANDMARKS.length][good.size()];
        for (int k = 0; k < good.size(); k++) {
            double[] q = Lin.sub(r.points[good.get(k)], b.center);
            double hh = Lin.dot(q, b.axisH), ww = Lin.dot(q, b.axisW);
            double front = Lin.dot(Lin.sub(r.points[good.get(k)], b.planeP), b.planeN);
            key[0][k] = hh;
            key[1][k] = -hh;
            key[2][k] = -ww;
            key[3][k] = ww;
            key[4][k] = front;
        }
        for (int l = 0; l < LANDMARKS.length; l++) {
            Integer[] order = new Integer[good.size()];
            for (int k = 0; k < order.length; k++) order[k] = k;
            final double[] kv = key[l];
            Arrays.sort(order, (x, y) -> Double.compare(kv[y], kv[x]));
            // Entre el 5 % más extremo, el que se vio desde más cámaras (para verlo en más fotos)
            // (sin repetir un punto ya elegido para otro nombre)
            java.util.Set<Integer> used = new java.util.HashSet<>();
            for (int u = 0; u < l; u++) used.add(out[u]);
            int best = -1, top = Math.max(1, (int) Math.ceil(0.05 * order.length));
            for (int k = 0; k < order.length && (k < top || best < 0); k++) {
                int i = good.get(order[k]);
                if (used.contains(i)) continue;
                if (best < 0 || r.nObs[i] > r.nObs[good.get(best)]) best = order[k];
            }
            if (best >= 0) out[l] = good.get(best);
        }
        return out;
    }

    /** Alto, ancho, profundidad (percentiles 1–99 en los ejes de la caja), centro y separación del plano. */
    static void extents(Box box, double[][] obj) {
        double[][] lim = new double[3][];
        double[][] axes = {box.axisH, box.axisW, box.axisD};
        for (int a = 0; a < 3; a++) {
            double[] proj = new double[obj.length];
            for (int k = 0; k < obj.length; k++) proj[k] = Lin.dot(obj[k], axes[a]);
            lim[a] = new double[]{percentile(proj, 1), percentile(proj, 99)};
        }
        box.alto = lim[0][1] - lim[0][0];
        box.ancho = lim[1][1] - lim[1][0];
        box.prof = lim[2][1] - lim[2][0];
        box.center = new double[3];
        for (int a = 0; a < 3; a++) box.center = Lin.add(box.center, Lin.scale(axes[a], (lim[a][0] + lim[a][1]) / 2));
        double[] dPlane = new double[obj.length];
        for (int k = 0; k < obj.length; k++) dPlane[k] = Lin.dot(Lin.sub(obj[k], box.planeP), box.planeN);
        box.separation = Math.max(0, percentile(dPlane, 1));
    }

    /**
     * Nueva caja con los puntos que las siluetas dicen que son del objeto
     * ({@link Carve#silhouetteMembers}), con el mismo plano de apoyo. No
     * depende de la agrupación por cercanía, que con objetos vistos desde
     * todos lados puede quedarse con una parte. Null si son muy pocos.
     */
    public static Box rebox(Sfm.Result r, Box b, List<Integer> members) {
        if (members.size() < 30) return null;
        Box nb = buildBox(r, b.planeN, b.planeP, new ArrayList<>(members));
        nb.otherPlanes = b.otherPlanes;
        return nb;
    }

    /**
     * Con las siluetas se sabe mejor qué puntos son del objeto: los que están
     * sobre el plano de apoyo y dentro del volumen tallado. La agrupación por
     * cercanía puede dejar fuera una parte del objeto (con un nudo visto
     * desde todos lados perdía un lóbulo y la planta salía 29 % corta). Se
     * suman esos puntos y se recalcula la caja con los mismos ejes. Devuelve
     * false si no había nada que sumar.
     */
    public static boolean refineWithHull(Sfm.Result r, Box b, Carve.Result h) {
        // Sólo se agregan puntos: los que ya eran del objeto se quedan (la silueta puede quedar justa en los
        // bordes y quitar puntos buenos; con la máscara eso achicaba el ancho un 10 %)
        java.util.Set<Integer> in = new java.util.TreeSet<>();
        for (int i : b.objectPoints) in.add(i);
        int before = in.size();
        double minD = 0.02 * Math.max(h.front, Math.max(h.ancho, h.alto));
        for (int i = 0; i < r.pointTracks.size() && i < r.points.length; i++)
            if (Lin.dot(Lin.sub(r.points[i], b.planeP), b.planeN) > minD && Carve.inside(h, r.points[i], 1)) in.add(i);
        if (in.size() == before) return false;
        double[][] obj = new double[in.size()][];
        int k = 0;
        for (int i : in) obj[k++] = r.points[i];
        extents(b, obj);
        b.objectPoints = in.stream().mapToInt(Integer::intValue).toArray();
        return true;
    }

    // ------------------------------------------------------------------ utilidades

    /** Eje principal (componentes principales) de los puntos proyectados en el plano de normal {@code n}. */
    static double[] inPlaneMajor(double[][] obj, double[] n) {
        double[] m = mean(obj);
        double[][] cov = new double[3][3];
        for (double[] q : obj) {
            double[] d = Lin.sub(q, m);
            d = Lin.sub(d, Lin.scale(n, Lin.dot(d, n)));
            for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) cov[i][j] += d[i] * d[j];
        }
        return Lin.unit(((double[][]) Lin.eigSym(cov)[1])[2]);
    }

    static double percentile(double[] v, double pct) {
        double[] s = v.clone();
        java.util.Arrays.sort(s);
        if (s.length == 0) return 0;
        double pos = pct / 100 * (s.length - 1);
        int i = (int) pos;
        return i + 1 < s.length ? s[i] + (s[i + 1] - s[i]) * (pos - i) : s[i];
    }

    static double[] median3(double[][] p) {
        double[] m = new double[3];
        for (int a = 0; a < 3; a++) {
            double[] v = new double[p.length];
            for (int i = 0; i < p.length; i++) v[i] = p[i][a];
            m[a] = percentile(v, 50);
        }
        return m;
    }

    static double[] mean(double[][] p) {
        double[] m = new double[3];
        for (double[] q : p) m = Lin.add(m, q);
        return Lin.scale(m, 1.0 / p.length);
    }

    private static long[] cell(double[] q, double s) {
        return new long[]{(long) Math.floor(q[0] / s), (long) Math.floor(q[1] / s), (long) Math.floor(q[2] / s)};
    }

    private static long pack(long x, long y, long z) { return ((x & 0x1FFFFF) << 42) | ((y & 0x1FFFFF) << 21) | (z & 0x1FFFFF); }

    private static long key(double[] q, double s) {
        long[] c = cell(q, s);
        return pack(c[0], c[1], c[2]);
    }

    private static int find(Map<Integer, Integer> par, int i) {
        int r = i;
        while (par.get(r) != r) r = par.get(r);
        while (par.get(i) != r) {
            int nx = par.get(i);
            par.put(i, r);
            i = nx;
        }
        return r;
    }

    private static void union(Map<Integer, Integer> par, int a, int b) {
        int ra = find(par, a), rb = find(par, b);
        if (ra != rb) par.put(ra, rb);
    }
}
