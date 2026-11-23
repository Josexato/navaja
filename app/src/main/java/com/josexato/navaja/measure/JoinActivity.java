package com.josexato.navaja.measure;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import com.josexato.navaja.measure.core.PoseJoin;
import com.josexato.navaja.measure.core.XFeatTracks;
import com.josexato.navaja.ui.NavajaUi;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Agregar otra pose: une dos escaneos guardados del mismo objeto en dos
 * posiciones (los dos del modo hoja). Halla el giro y el desplazamiento que
 * llevan el primero sobre el segundo (ver {@link PoseJoin}), dice qué tan
 * fiable salió y deja compartir la nube unida. Todavía no talla un volumen
 * con las dos tomas: es el primer paso.
 */
public class JoinActivity extends Activity {

    public static final String EXTRA_A = "zip_a", EXTRA_B = "zip_b", EXTRA_NAME_A = "nombre_a", EXTRA_NAME_B = "nombre_b";
    private static final String TAG = "Unir";
    /** Fotos de cada escaneo que se emparejan (repartidas por la vuelta). */
    private static final int PHOTOS = 12;
    private static final int[] TURNS = {0, 90, 180, 270};
    /** La unión se da por buena si las nubes quedan a menos de esta parte de la distancia sin girar… */
    private static final double GAP_SHARE = 0.6;
    /** …y menos de esta parte de los puntos queda a más de UNDER_MM por debajo de la hoja. */
    private static final double UNDER_SHARE = 0.1, UNDER_MM = 5;

    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancel = new AtomicBoolean();
    private TextView progress;
    private String nameA, nameB;

    /** Lo que se lee del ZIP de un escaneo. */
    private static final class Loaded {
        PoseJoin.Scan scan;
        final Map<String, byte[]> photos = new HashMap<>(), masks = new HashMap<>();
    }

    /** Una foto ya preparada: los puntos de XFeat sobre el recorte y el punto 3D de cada uno. */
    private static final class View3 {
        XFeatTracks.Frame frame;
        int[] point;
        int turn;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        NavajaUi.window(this);
        Uri a = getIntent().getParcelableExtra(EXTRA_A), bb = getIntent().getParcelableExtra(EXTRA_B);
        nameA = getIntent().getStringExtra(EXTRA_NAME_A);
        nameB = getIntent().getStringExtra(EXTRA_NAME_B);
        NavajaUi.Screen scr = NavajaUi.screen(this, "Unir dos poses", "El mismo objeto en dos posiciones", null, true);
        LinearLayout c = NavajaUi.card(this);
        c.addView(NavajaUi.text(this, nameA + "  →  " + nameB, NavajaUi.Style.LABEL));
        progress = NavajaUi.text(this, "Leyendo los dos escaneos…", NavajaUi.Style.BODY);
        c.addView(progress);
        scr.content.addView(c);
        setContentView(scr.root);
        if (a == null || bb == null) {
            progress.setText("Faltan los escaneos a unir.");
            return;
        }
        work.execute(() -> {
            try {
                run(a, bb);
            } catch (IOException | RuntimeException | LinkageError | OutOfMemoryError e) {
                Log.w(TAG, "unir", e);
                if (!cancel.get()) post("No se pudo unir: " + e.getMessage());
            }
        });
    }

    @Override
    protected void onDestroy() {
        cancel.set(true);
        work.shutdownNow();
        super.onDestroy();
    }

    private void post(String s) { ui.post(() -> { if (progress != null) progress.setText(s); }); }

    private void run(Uri ua, Uri ub) throws IOException {
        Loaded a = load(ua), b = load(ub);
        if (a.scan == null || b.scan == null)
            throw new IOException("uno de los ZIP no trae la reconstrucción (colmap/sparse/0). Tiene que ser un escaneo del modo hoja guardado con Navaja 1.0.39 o posterior.");
        List<Integer> pa = pick(a), pb = pick(b);
        if (pa.size() < 4 || pb.size() < 4) throw new IOException("muy pocas fotos con silueta (" + pa.size() + " y " + pb.size() + ")");
        long t0 = System.nanoTime();
        post("Probando la tarjeta gráfica y el chip de IA…");
        try (XFeat xf = new XFeat(this)) {
            List<View3> va = new ArrayList<>(), vb = new ArrayList<>();
            Set<Integer> objA = new LinkedHashSet<>(), objB = new LinkedHashSet<>();
            int n = 0, total = pa.size() + pb.size();
            for (int i : pa) {
                if (cancel.get()) return;
                post(String.format(Locale.getDefault(), "Buscando puntos sobre el objeto: foto %d de %d", ++n, total));
                views(xf, a, i, new int[]{0}, va, objA);
            }
            for (int i : pb) {
                if (cancel.get()) return;
                post(String.format(Locale.getDefault(), "Buscando puntos sobre el objeto: foto %d de %d", ++n, total));
                views(xf, b, i, TURNS, vb, objB);
            }
            // Cada foto de A contra cada foto de B en sus cuatro giros
            List<double[]> p = new ArrayList<>(), q = new ArrayList<>();
            int[] perTurn = new int[4];
            int done = 0, all = va.size() * vb.size();
            for (View3 y : vb)
                for (View3 x : va) {
                    if (cancel.get()) return;
                    if (done++ % 8 == 0) post(String.format(Locale.getDefault(), "Emparejando las dos tomas: %d %%", 100 * done / Math.max(1, all)));
                    for (int[] m : xf.match(x.frame, y.frame)) {
                        int i3 = x.point[m[0]], j3 = y.point[m[1]];
                        if (i3 < 0 || j3 < 0) continue;
                        p.add(a.scan.points[i3]);
                        q.add(b.scan.points[j3]);
                        perTurn[y.turn / 90]++;
                    }
                }
            post("Ajustando el giro…");
            PoseJoin.Fit f = PoseJoin.fit(p, q, 1);
            List<double[]> ca = cloud(a.scan, objA), cb = cloud(b.scan, objB);
            double gapLoose = PoseJoin.cloudGap(ca, cb, null), gap = f == null ? Double.NaN : PoseJoin.cloudGap(ca, cb, f);
            double under = f == null ? Double.NaN : PoseJoin.below(ca, f, UNDER_MM);
            double secs = (System.nanoTime() - t0) / 1e9;
            String where = xf.netDelegate + " / " + xf.glueDelegate;
            ui.post(() -> show(a, b, objA, objB, f, p.size(), perTurn, va.size(), vb.size() / TURNS.length, gap, gapLoose, under, secs, where));
        }
    }

    /** Lee del ZIP la reconstrucción, las fotos y las siluetas. */
    private Loaded load(Uri u) throws IOException {
        Loaded l = new Loaded();
        String cams = null, imgs = null, pts = null;
        try (InputStream in = getContentResolver().openInputStream(u)) {
            if (in == null) throw new IOException("no se pudo abrir el ZIP");
            ZipInputStream z = new ZipInputStream(in);
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String n = e.getName();
                if (n.equals("colmap/sparse/0/cameras.txt")) cams = new String(all(z), StandardCharsets.UTF_8);
                else if (n.equals("colmap/sparse/0/images.txt")) imgs = new String(all(z), StandardCharsets.UTF_8);
                else if (n.equals("colmap/sparse/0/points3D.txt")) pts = new String(all(z), StandardCharsets.UTF_8);
                else if (n.startsWith("fotos/") && n.endsWith(".jpg")) l.photos.put(n.substring(6), all(z));
                else if (n.startsWith("colmap/mascaras/") && n.endsWith(".png")) l.masks.put(n.substring(16, n.length() - 4), all(z));
            }
        }
        if (cams != null && imgs != null && pts != null) l.scan = PoseJoin.parse(cams, imgs, pts);
        return l;
    }

    private static byte[] all(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
        return o.toByteArray();
    }

    /** Hasta {@link #PHOTOS} fotos con silueta, repartidas por la vuelta. */
    private static List<Integer> pick(Loaded l) {
        List<Integer> ok = new ArrayList<>();
        for (int i = 0; i < l.scan.names.size(); i++) {
            String n = l.scan.names.get(i);
            if (l.photos.containsKey(n) && l.masks.containsKey(n)) ok.add(i);
        }
        if (ok.size() <= PHOTOS) return ok;
        List<Integer> out = new ArrayList<>();
        for (int k = 0; k < PHOTOS; k++) out.add(ok.get((int) Math.round(k * (ok.size() - 1) / (double) (PHOTOS - 1))));
        return out;
    }

    /** Prepara la foto {@code i}: sólo el objeto, recortado y en cada giro; XFeat y el punto 3D de cada punto clave. */
    private void views(XFeat xf, Loaded l, int i, int[] turns, List<View3> out, Set<Integer> object) {
        PoseJoin.Scan s = l.scan;
        String name = s.names.get(i);
        int w = s.width, h = s.height;
        int[] argb = pixels(l.photos.get(name), w, h), mk = pixels(l.masks.get(name), w, h);
        if (argb == null || mk == null) return;
        byte[] mask = new byte[w * h];
        for (int k = 0; k < mask.length; k++) mask[k] = (byte) ((mk[k] & 255) > 127 ? 1 : 0);
        double[] rect = PoseJoin.rect(mask, w, h);
        if (rect == null) return;
        // Los puntos del objeto que ve esta foto (para comparar después las dos nubes)
        float[] ob = s.obsXY.get(i);
        int[] pt = s.obsPoint.get(i);
        for (int k = 0; k < pt.length; k++) {
            int x = Math.round(ob[2 * k]), y = Math.round(ob[2 * k + 1]);
            if (x >= 0 && y >= 0 && x < w && y < h && mask[y * w + x] != 0) object.add(pt[k]);
        }
        for (int turn : turns) {
            int[] wh = PoseJoin.cutSize(w, h, turn);
            View3 v = new View3();
            v.turn = turn;
            v.frame = xf.detect(out.size(), PoseJoin.cut(argb, mask, w, h, rect, turn), wh[0], wh[1]);
            double[][] xy = new double[XFeatTracks.N][];
            for (int k = 0; k < xy.length; k++)
                xy[k] = k < v.frame.n ? PoseJoin.toPhoto(v.frame.x[k], v.frame.y[k], rect, w, h, turn) : new double[]{-1e9, -1e9};
            v.point = PoseJoin.to3d(s, i, mask, xy);
            out.add(v);
        }
    }

    /** Los píxeles de una imagen del ZIP, o null si no es del tamaño de la reconstrucción. */
    private static int[] pixels(byte[] data, int w, int h) {
        if (data == null) return null;
        Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (bmp == null) return null;
        try {
            if (bmp.getWidth() != w || bmp.getHeight() != h) return null;
            int[] px = new int[w * h];
            bmp.getPixels(px, 0, w, 0, 0, w, h);
            return px;
        } finally {
            bmp.recycle();
        }
    }

    private static List<double[]> cloud(PoseJoin.Scan s, Set<Integer> ids) {
        List<double[]> c = new ArrayList<>();
        // Hasta unos 2500 puntos: la comparación de nubes es de todos contra todos
        int step = Math.max(1, ids.size() / 2500), k = 0;
        for (int id : ids) if (k++ % step == 0) c.add(s.points[id]);
        return c;
    }

    private void show(Loaded a, Loaded b, Set<Integer> objA, Set<Integer> objB, PoseJoin.Fit f, int pairs, int[] perTurn, int na, int nb, double gap,
                      double gapLoose, double under, double secs, String where) {
        NavajaUi.Screen scr = NavajaUi.screen(this, "Unir dos poses", nameA + "  →  " + nameB, null, true);
        LinearLayout c = NavajaUi.card(this);
        // Los pares solos pueden engañar: en un objeto simétrico (un animal, una botella), un costado de una toma se empareja con el
        // costado opuesto de la otra y sale un giro coherente pero falso. Por eso además tienen que acercarse las dos nubes.
        boolean closer = gap < GAP_SHARE * gapLoose, above = under < UNDER_SHARE;
        boolean ok = f != null && f.ok() && closer && above;
        c.addView(NavajaUi.text(this, ok ? "Las dos tomas calzan" : f != null && f.ok() ? "Hay pares, pero la unión no es de fiar" : "No se pudieron unir",
                NavajaUi.Style.SUBTITLE));
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "Fotos emparejadas: %d de la primera × %d de la segunda, cada una de la segunda en 4 giros.%n", na, nb));
        sb.append(String.format(Locale.ROOT, "Pares de puntos 3D: %d (segunda toma sin girar %d, girada 90° %d, 180° %d, 270° %d).%n", pairs, perTurn[0], perTurn[1],
                perTurn[2], perTurn[3]));
        if (f == null) sb.append("No hubo pares suficientes para ajustar un giro.\n");
        else {
            sb.append(String.format(Locale.ROOT, "Pares coherentes (a menos de %.0f mm): %d de %d (%.0f %%), error mediano %.1f mm. Hacen falta %d o más.%n", PoseJoin.TOL_MM,
                    f.inliers, f.pairs, 100.0 * f.inliers / f.pairs, f.error, PoseJoin.MIN_INLIERS));
            sb.append(String.format(Locale.ROOT, "Movimiento de la primera a la segunda: giro de %.0f°, que inclina el objeto %.0f° respecto de la vertical.%n", f.angleDeg,
                    f.tiltDeg));
            sb.append(String.format(Locale.ROOT, "Comprobación con las nubes de puntos (no usa los pares): cada punto de la primera queda a %.1f mm del más cercano de la "
                    + "segunda; sin girar, a %.1f mm. %s%n", gap, gapLoose, closer ? "Se acercan: lo confirma."
                    : "No se acercan: lo más probable es que el giro sea falso (pasa con objetos simétricos cuando las dos tomas muestran costados opuestos)."));
            sb.append(String.format(Locale.ROOT, "Comprobación con la hoja: %.1f %% de los puntos de la primera quedan a más de %.0f mm por debajo de la hoja de la segunda. %s%n",
                    100 * under, UNDER_MM, above ? "El objeto queda apoyado: bien." : "El objeto quedaría hundido en la mesa: el giro no puede ser."));
        }
        sb.append(String.format(Locale.ROOT, "Puntos del objeto: %d y %d · %.0f s · XFeat y emparejador en %s.", objA.size(), objB.size(), secs, where));
        TextView t = NavajaUi.text(this, sb.toString(), NavajaUi.Style.BODY);
        t.setTextIsSelectable(true);
        c.addView(t);
        if (!ok) c.addView(NavajaUi.text(this, "Para que calcen: los dos escaneos tienen que ser del mismo objeto, con textura, y con partes que se vean en las dos "
                + "tomas (por ejemplo parado y acostado; no acostado sobre un lado y luego sobre el otro). Un objeto liso o brillante no da puntos que emparejar.",
                NavajaUi.Style.SECONDARY));
        if (ok)
            c.addView(NavajaUi.buttonRow(this, NavajaUi.primary(this, "Compartir la nube unida (PLY)", v -> sharePly(a.scan, b.scan, objA, objB, f))));
        c.addView(NavajaUi.text(this, "Por ahora sólo se alinean las dos tomas y se puede ver la nube unida. Tallar un solo volumen con las siluetas de las dos es el "
                + "paso siguiente.", NavajaUi.Style.MUTED));
        scr.content.addView(c);
        setContentView(scr.root);
    }

    /** La nube unida, en mm y en los ejes de la segunda toma (Z hacia arriba, la hoja en Z = 0). */
    private void sharePly(PoseJoin.Scan a, PoseJoin.Scan b, Set<Integer> objA, Set<Integer> objB, PoseJoin.Fit f) {
        try {
            File dir = new File(getCacheDir(), "union");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File out = new File(dir, "union_" + nameA + "_" + nameB + ".ply");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.US_ASCII)) {
                w.write("ply\nformat ascii 1.0\ncomment Navaja: dos poses unidas, mm, ejes de la segunda toma\nelement vertex " + (objA.size() + objB.size())
                        + "\nproperty float x\nproperty float y\nproperty float z\nproperty uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n");
                for (int id : objA) line(w, f.apply(a.points[id]), a.colors[id]);
                for (int id : objB) line(w, b.points[id], b.colors[id]);
            }
            Uri u = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", out);
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, u)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Compartir la nube unida"));
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "ply", e);
            android.widget.Toast.makeText(this, "No se pudo compartir: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private static void line(Writer w, double[] x, int color) throws IOException {
        w.write(String.format(Locale.ROOT, "%.2f %.2f %.2f %d %d %d%n", x[0], x[1], x[2], (color >> 16) & 255, (color >> 8) & 255, color & 255));
    }
}
