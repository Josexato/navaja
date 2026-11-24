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
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import com.josexato.navaja.MainActivity;
import com.josexato.navaja.measure.core.PoseJoin;
import com.josexato.navaja.measure.core.Sfm;
import com.josexato.navaja.measure.core.Texture;
import com.josexato.navaja.measure.core.XFeatTracks;
import com.josexato.navaja.scan3d.core.Decimate;
import com.josexato.navaja.scan3d.core.Glb;
import com.josexato.navaja.scan3d.core.Mesh;
import com.josexato.navaja.scan3d.core.SurfaceNets;
import com.josexato.navaja.ui.NavajaUi;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.util.zip.ZipOutputStream;

/**
 * Agregar otra pose: une escaneos guardados del mismo objeto en distintas
 * posiciones (todos del modo hoja). Uno es la referencia; cada uno de los
 * demás se lleva sobre ella con el giro y el desplazamiento que halla
 * {@link PoseJoin}. Dice qué tan fiable salió cada unión, deja agregar más
 * tomas, y con las que calzan talla un solo volumen con textura, que se puede
 * ver en el visor, guardar o compartir.
 */
public class JoinActivity extends Activity {

    /** El escaneo que se lleva sobre la referencia ({@code EXTRA_B}). */
    public static final String EXTRA_A = "zip_a", EXTRA_B = "zip_b", EXTRA_NAME_A = "nombre_a", EXTRA_NAME_B = "nombre_b";
    /** Otros escaneos del modo hoja de la carpeta, para «Agregar otra pose»: sus ZIP, nombres, lo que se muestra y la clave de su miniatura. */
    public static final String EXTRA_OTHERS = "otros_zip", EXTRA_OTHER_NAMES = "otros_nombres", EXTRA_OTHER_LABELS = "otros_textos", EXTRA_OTHER_THUMBS = "otros_miniaturas";
    private static final String TAG = "Unir";
    /** Fotos de cada escaneo que se emparejan (repartidas por la vuelta). */
    private static final int PHOTOS = 12;
    private static final int[] TURNS = {0, 90, 180, 270};
    /** La unión se da por buena si las nubes quedan a menos de esta parte de la distancia sin girar… */
    private static final double GAP_SHARE = 0.6;
    /** …y menos de esta parte de los puntos queda a más de UNDER_MM por debajo de la hoja. */
    private static final double UNDER_SHARE = 0.1, UNDER_MM = 5;
    /** Triángulos del modelo simplificado (GLB) y lado máximo de su textura (px). */
    private static final int MODEL_TRIS = 3000, TEXTURE_PX = 2048;

    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancel = new AtomicBoolean();
    private TextView progress;

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

    /** Un escaneo de la unión: la referencia (sin movimiento) o una toma llevada sobre ella. */
    private static final class Part {
        String name;
        Loaded data;
        Bitmap thumb;
        final Set<Integer> object = new LinkedHashSet<>();
        /** El movimiento hacia la referencia; null en la referencia o si no hubo ajuste. */
        PoseJoin.Fit fit;
        boolean ok;
        /** Cómo salió la unión (para la pantalla y el informe). */
        String text = "";
    }

    private Part ref;
    private List<View3> refViews;
    private final List<Part> parts = new ArrayList<>();
    /** Dónde corrieron XFeat y el emparejador la última vez. */
    private String where = "";
    /** El texto del volumen tallado, si se talló con las tomas actuales. */
    private String carveText;
    private File glbFile, stlFile;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        NavajaUi.window(this);
        Uri a = getIntent().getParcelableExtra(EXTRA_A), bb = getIntent().getParcelableExtra(EXTRA_B);
        String nameA = getIntent().getStringExtra(EXTRA_NAME_A), nameB = getIntent().getStringExtra(EXTRA_NAME_B);
        if (a == null || bb == null) {
            busy(nameA + "  →  " + nameB, "Faltan los escaneos a unir.");
            return;
        }
        busy(nameA + "  →  " + nameB, "Leyendo los dos escaneos…");
        work.execute(() -> guard(() -> {
            Part r = new Part();
            r.name = nameB;
            r.data = load(bb);
            r.thumb = firstPhoto(r.data);
            r.ok = true;
            if (r.data.scan == null) throw new IOException(noScan(nameB));
            ref = r;
            add(a, nameA);
        }));
    }

    @Override
    protected void onDestroy() {
        cancel.set(true);
        work.shutdownNow();
        super.onDestroy();
    }

    private interface Job { void run() throws IOException; }

    private void guard(Job j) {
        try {
            j.run();
        } catch (IOException | RuntimeException | LinkageError | OutOfMemoryError e) {
            Log.w(TAG, "unir", e);
            if (cancel.get()) return;
            ui.post(() -> {
                if (ref == null || parts.isEmpty()) busy("No se pudo unir", String.valueOf(e.getMessage()));
                else show("No se pudo: " + e.getMessage());
            });
        }
    }

    private static String noScan(String name) {
        return name + " no trae la reconstrucción (colmap/sparse/0). Tiene que ser un escaneo del modo hoja guardado con Navaja 1.0.39 o posterior.";
    }

    private void busy(String title, String text) {
        NavajaUi.Screen scr = NavajaUi.screen(this, "Unir poses", "El mismo objeto en varias posiciones", null, true);
        LinearLayout c = NavajaUi.card(this);
        c.addView(NavajaUi.text(this, title, NavajaUi.Style.LABEL));
        if (ref != null) c.addView(thumbs(null));
        progress = NavajaUi.text(this, text, NavajaUi.Style.BODY);
        c.addView(progress);
        scr.content.addView(c);
        setContentView(scr.root);
    }

    private void post(String s) { ui.post(() -> { if (progress != null) progress.setText(s); }); }

    private void toast(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show(); }

    // ------------------------------------------------------------ unir una toma con la referencia

    /** Lee el escaneo {@code zip} y lo une con la referencia. Corre en el hilo de trabajo. */
    private void add(Uri zip, String name) throws IOException {
        Part p = new Part();
        p.name = name;
        p.data = load(zip);
        p.thumb = firstPhoto(p.data);
        if (p.data.scan == null) throw new IOException(noScan(name));
        ui.post(() -> {
            NavajaUi.Screen scr = NavajaUi.screen(this, "Unir poses", "El mismo objeto en varias posiciones", null, true);
            LinearLayout c = NavajaUi.card(this);
            c.addView(NavajaUi.text(this, name + "  →  " + ref.name, NavajaUi.Style.LABEL));
            c.addView(thumbs(p.thumb));
            progress = NavajaUi.text(this, "Preparando…", NavajaUi.Style.BODY);
            c.addView(progress);
            scr.content.addView(c);
            setContentView(scr.root);
        });
        List<Integer> pa = pick(p.data), pb = pick(ref.data);
        if (pa.size() < 4 || pb.size() < 4) throw new IOException("muy pocas fotos con silueta (" + pa.size() + " y " + pb.size() + ")");
        long t0 = System.nanoTime();
        post("Probando la tarjeta gráfica y el chip de IA…");
        try (XFeat xf = new XFeat(this)) {
            List<View3> va = new ArrayList<>();
            int n = 0, total = pa.size() + (refViews == null ? pb.size() : 0);
            for (int i : pa) {
                if (cancel.get()) return;
                post(String.format(Locale.getDefault(), "Buscando puntos sobre el objeto: foto %d de %d", ++n, total));
                views(xf, p.data, i, new int[]{0}, va, p.object);
            }
            if (refViews == null) { // las de la referencia sirven para todas las tomas que se agreguen
                List<View3> vb = new ArrayList<>();
                for (int i : pb) {
                    if (cancel.get()) return;
                    post(String.format(Locale.getDefault(), "Buscando puntos sobre el objeto: foto %d de %d", ++n, total));
                    views(xf, ref.data, i, TURNS, vb, ref.object);
                }
                refViews = vb;
            }
            // Cada foto de la toma contra cada foto de la referencia en sus cuatro giros
            List<double[]> ps = new ArrayList<>(), qs = new ArrayList<>();
            int[] perTurn = new int[4];
            int done = 0, all = va.size() * refViews.size();
            for (View3 y : refViews)
                for (View3 x : va) {
                    if (cancel.get()) return;
                    if (done++ % 8 == 0) post(String.format(Locale.getDefault(), "Emparejando las dos tomas: %d %%", 100 * done / Math.max(1, all)));
                    for (int[] m : xf.match(x.frame, y.frame)) {
                        int i3 = x.point[m[0]], j3 = y.point[m[1]];
                        if (i3 < 0 || j3 < 0) continue;
                        ps.add(p.data.scan.points[i3]);
                        qs.add(ref.data.scan.points[j3]);
                        perTurn[y.turn / 90]++;
                    }
                }
            post("Ajustando el giro…");
            PoseJoin.Fit f = PoseJoin.fit(ps, qs, 1);
            List<double[]> ca = cloud(p.data.scan, p.object), cb = cloud(ref.data.scan, ref.object);
            double gapLoose = PoseJoin.cloudGap(ca, cb, null), gap = f == null ? Double.NaN : PoseJoin.cloudGap(ca, cb, f);
            double under = f == null ? Double.NaN : PoseJoin.below(ca, f, UNDER_MM);
            String ran = xf.netDelegate + " / " + xf.glueDelegate;
            // Los pares solos pueden engañar: en un objeto simétrico (un animal, una botella), un costado de una toma se empareja con
            // el costado opuesto de la otra y sale un giro coherente pero falso. Por eso además tienen que acercarse las dos nubes.
            boolean closer = gap < GAP_SHARE * gapLoose, above = under < UNDER_SHARE;
            p.fit = f;
            p.ok = f != null && f.ok() && closer && above;
            StringBuilder sb = new StringBuilder();
            sb.append(p.ok ? "Calza." : f != null && f.ok() ? "Hay pares, pero la unión no es de fiar." : "No se pudo unir.").append('\n');
            sb.append(String.format(Locale.ROOT, "Fotos emparejadas: %d × %d, las de la referencia en 4 giros. Pares de puntos 3D: %d (sin girar %d, 90° %d, 180° %d, 270° %d).%n",
                    va.size(), refViews.size() / TURNS.length, ps.size(), perTurn[0], perTurn[1], perTurn[2], perTurn[3]));
            if (f == null) sb.append("No hubo pares suficientes para ajustar un giro.\n");
            else {
                sb.append(String.format(Locale.ROOT, "Pares coherentes (a menos de %.0f mm): %d de %d (%.0f %%), error mediano %.1f mm. Hacen falta %d o más.%n", PoseJoin.TOL_MM,
                        f.inliers, f.pairs, 100.0 * f.inliers / f.pairs, f.error, PoseJoin.MIN_INLIERS));
                sb.append(String.format(Locale.ROOT, "Movimiento hacia la referencia: giro de %.0f°, que inclina el objeto %.0f° respecto de la vertical.%n", f.angleDeg, f.tiltDeg));
                sb.append(String.format(Locale.ROOT, "Comprobación con las nubes de puntos (no usa los pares): cada punto queda a %.1f mm del más cercano de la referencia; sin "
                        + "girar, a %.1f mm. %s%n", gap, gapLoose, closer ? "Se acercan: lo confirma."
                        : "No se acercan: lo más probable es que el giro sea falso (pasa con objetos simétricos cuando las dos tomas muestran costados opuestos)."));
                sb.append(String.format(Locale.ROOT, "Comprobación con la hoja: %.1f %% de los puntos quedan a más de %.0f mm por debajo de la hoja de la referencia. %s%n",
                        100 * under, UNDER_MM, above ? "El objeto queda apoyado: bien." : "El objeto quedaría hundido en la mesa: el giro no puede ser."));
            }
            sb.append(String.format(Locale.ROOT, "Puntos del objeto: %d y %d · %.0f s.", p.object.size(), ref.object.size(), (System.nanoTime() - t0) / 1e9));
            p.text = sb.toString();
            ui.post(() -> {
                where = ran;
                parts.add(p);
                carveText = null; // con una toma más, el volumen anterior ya no vale
                show(null);
            });
        }
    }

    // ------------------------------------------------------------ pantalla

    private List<Part> joined() {
        List<Part> ok = new ArrayList<>();
        for (Part p : parts) if (p.ok) ok.add(p);
        return ok;
    }

    private String base() {
        StringBuilder sb = new StringBuilder("union");
        for (Part p : joined()) sb.append('_').append(p.name.replace("medida_", ""));
        return sb.append('_').append(ref.name.replace("medida_", "")).toString();
    }

    /** Las primeras fotos lado a lado: las tomas agregadas (y la que se está agregando, si hay) y, al final, la referencia. */
    private LinearLayout thumbs(Bitmap adding) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        List<Bitmap> bs = new ArrayList<>();
        for (Part p : parts) bs.add(p.thumb);
        if (adding != null) bs.add(adding);
        bs.add(ref.thumb);
        int k = 0;
        for (Bitmap b : bs) {
            ImageView iv = new ImageView(this);
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (b != null) iv.setImageBitmap(b);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (k++ > 0) lp.leftMargin = NavajaUi.dp(this, 8);
            lp.topMargin = NavajaUi.dp(this, 6);
            lp.bottomMargin = NavajaUi.dp(this, 6);
            row.addView(iv, lp);
        }
        return row;
    }

    private void show(String error) {
        List<Part> ok = joined();
        NavajaUi.Screen scr = NavajaUi.screen(this, "Unir poses", "Referencia: " + ref.name, null, true);
        LinearLayout c = NavajaUi.card(this);
        c.addView(thumbs(null));
        c.addView(NavajaUi.text(this, ok.isEmpty() ? "Ninguna toma calza con la referencia" : ok.size() == 1 && parts.size() == 1 ? "Las dos tomas calzan"
                : ok.size() + " de " + parts.size() + " tomas calzan con la referencia", NavajaUi.Style.SUBTITLE));
        if (error != null) c.addView(NavajaUi.text(this, error, NavajaUi.Style.SECONDARY));
        for (Part p : parts) {
            c.addView(NavajaUi.text(this, p.name + "  →  " + ref.name, NavajaUi.Style.LABEL));
            TextView t = NavajaUi.text(this, p.text, NavajaUi.Style.BODY);
            t.setTextIsSelectable(true);
            c.addView(t);
        }
        c.addView(NavajaUi.text(this, "XFeat y emparejador en " + where + ".", NavajaUi.Style.MUTED));
        if (ok.size() < parts.size())
            c.addView(NavajaUi.text(this, "Para que calcen: los escaneos tienen que ser del mismo objeto, con textura, y con partes que se vean en las dos tomas "
                    + "(por ejemplo parado y acostado; no acostado sobre un lado y luego sobre el otro). Un objeto liso o brillante no da puntos que emparejar.",
                    NavajaUi.Style.SECONDARY));
        // Una toma más del mismo objeto: cada una recorta otra parte que las demás no veían
        ArrayList<Uri> others = getIntent().getParcelableArrayListExtra(EXTRA_OTHERS);
        if (others != null && !others.isEmpty())
            c.addView(NavajaUi.buttonRow(this, NavajaUi.secondary(this, ok.isEmpty() ? "Probar con otra toma…" : "Agregar otra pose…", v -> chooseMore(others))));
        if (!ok.isEmpty()) c.addView(NavajaUi.buttonRow(this, NavajaUi.secondary(this, "Compartir la nube unida (PLY)", v -> sharePly())));
        scr.content.addView(c);
        if (!ok.isEmpty()) {
            LinearLayout k = NavajaUi.card(this);
            k.addView(NavajaUi.text(this, "Un solo volumen con las " + (ok.size() + 1) + " tomas", NavajaUi.Style.SUBTITLE));
            TextView note = NavajaUi.text(this, carveText != null ? carveText : "Cada toma no ve la parte sobre la que el objeto está apoyado. Con las siluetas de "
                    + "todas, cada una recorta lo que las otras no veían.", NavajaUi.Style.BODY);
            note.setTextIsSelectable(true);
            k.addView(note);
            if (carveText != null) results(k);
            else k.addView(NavajaUi.buttonRow(this, NavajaUi.primary(this, "Tallar el volumen", v -> {
                v.setEnabled(false);
                note.setText("Tallando con las siluetas de las " + (ok.size() + 1) + " tomas…");
                work.execute(() -> {
                    try {
                        carve(note);
                    } catch (IOException | RuntimeException | OutOfMemoryError e) {
                        Log.w(TAG, "tallar", e);
                        ui.post(() -> note.setText("No se pudo tallar: " + e.getMessage()));
                    }
                });
            })));
            scr.content.addView(k);
        }
        setContentView(scr.root);
    }

    /** Los botones del volumen ya tallado. */
    private void results(LinearLayout card) {
        File glb = glbFile, stl = stlFile;
        card.addView(NavajaUi.buttonRow(this, NavajaUi.primary(this, "Ver en el visor", v -> startActivity(new Intent(this, MainActivity.class)
                        .putExtra(MainActivity.EXTRA_OPEN_MODEL, glb.getAbsolutePath()))),
                NavajaUi.secondary(this, "Guardar todo", v -> saveAll())));
        card.addView(NavajaUi.buttonRow(this, NavajaUi.secondary(this, "Compartir modelo (GLB)", v -> shareFile(glb)),
                NavajaUi.secondary(this, "Compartir volumen (STL)", v -> shareFile(stl))));
        card.addView(NavajaUi.text(this, "GLB: el modelo de " + MODEL_TRIS + " triángulos con textura, en metros (base para modelar). STL: el volumen completo, en mm, Z "
                + "hacia arriba. «Guardar todo» deja un ZIP con los dos, la nube unida y el informe en la carpeta de los escaneos.", NavajaUi.Style.MUTED));
    }

    /** Elegir otra toma del mismo objeto entre los demás escaneos de la carpeta. */
    private void chooseMore(ArrayList<Uri> zips) {
        String[] names = getIntent().getStringArrayExtra(EXTRA_OTHER_NAMES), labels = getIntent().getStringArrayExtra(EXTRA_OTHER_LABELS),
                keys = getIntent().getStringArrayExtra(EXTRA_OTHER_THUMBS);
        if (names == null || labels == null || keys == null) return;
        Set<String> used = new LinkedHashSet<>();
        used.add(ref.name);
        for (Part p : parts) used.add(p.name);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = NavajaUi.dp(this, 16);
        list.setPadding(pad, NavajaUi.dp(this, 8), pad, 0);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(list);
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this).setTitle("¿Qué otra toma del mismo objeto?").setView(scroll)
                .setNegativeButton("Cancelar", null).create();
        int shown = 0;
        for (int i = 0; i < zips.size() && i < names.length && i < labels.length && i < keys.length; i++) {
            if (used.contains(names[i])) continue;
            Uri zip = zips.get(i);
            String name = names[i];
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, NavajaUi.dp(this, 10), 0, NavajaUi.dp(this, 10));
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(0x22808080);
            // La miniatura que ya leyó «Mis escaneos» (si no está, queda el recuadro vacío)
            File tf = new File(new File(getCacheDir(), "miniaturas"), keys[i].replaceAll("[^A-Za-z0-9_.-]", "_") + ".jpg");
            if (tf.exists()) iv.setImageBitmap(BitmapFactory.decodeFile(tf.getPath()));
            int side = NavajaUi.dp(this, 96);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(side, side * 3 / 4);
            lp.rightMargin = NavajaUi.dp(this, 12);
            row.addView(iv, lp);
            row.addView(NavajaUi.text(this, labels[i], NavajaUi.Style.BODY), new LinearLayout.LayoutParams(0, -2, 1f));
            row.setClickable(true);
            row.setOnClickListener(v -> {
                dlg.dismiss();
                busy(name + "  →  " + ref.name, "Leyendo el escaneo…");
                work.execute(() -> guard(() -> add(zip, name)));
            });
            list.addView(row);
            shown++;
        }
        if (shown == 0) {
            toast("No quedan otros escaneos del modo hoja en la carpeta");
            return;
        }
        dlg.show();
    }

    // ------------------------------------------------------------ leer los escaneos

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

    private static byte[] bytes(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return all(in);
        }
    }

    /** La primera foto del ZIP (reducida), o null. */
    private static Bitmap firstPhoto(Loaded l) {
        byte[] jpg = null;
        if (l.scan != null) for (String n : l.scan.names) if ((jpg = l.photos.get(n)) != null) break;
        if (jpg == null && !l.photos.isEmpty()) jpg = l.photos.get(new java.util.TreeSet<>(l.photos.keySet()).first());
        if (jpg == null) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 2;
        return BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
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
        int[] argb = pixels(l.photos.get(name), w, h);
        byte[] mask = mask(l, i);
        if (argb == null || mask == null) return;
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

    /** La silueta de la foto {@code i} (≠ 0 = objeto), o null si no hay o no es del tamaño de la reconstrucción. */
    private static byte[] mask(Loaded l, int i) {
        int[] mk = pixels(l.masks.get(l.scan.names.get(i)), l.scan.width, l.scan.height);
        if (mk == null) return null;
        byte[] mask = new byte[mk.length];
        for (int k = 0; k < mask.length; k++) mask[k] = (byte) ((mk[k] & 255) > 127 ? 1 : 0);
        return mask;
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

    // ------------------------------------------------------------ un solo volumen

    /** Las siluetas de un escaneo como cámaras para tallar (las de una toma agregada, ya en los ejes de la referencia y ensanchadas). */
    private void cams(Part p, int scan, List<PoseJoin.Cam> out, List<byte[]> photos) {
        PoseJoin.Scan s = p.data.scan;
        for (int i = 0; i < s.names.size(); i++) {
            if (cancel.get()) return;
            byte[] mask = mask(p.data, i);
            if (mask == null) continue;
            PoseJoin.Cam c = new PoseJoin.Cam();
            c.scan = scan;
            c.w = s.width;
            c.h = s.height;
            c.f = s.focal;
            c.cx = s.cx;
            c.cy = s.cy;
            c.pose = p.fit == null ? s.poses.get(i) : PoseJoin.moved(s.poses.get(i), p.fit);
            c.mask = p.fit == null ? mask : PoseJoin.grow(mask, c.w, c.h, PoseJoin.GROW_A);
            out.add(c);
            photos.add(p.data.photos.get(s.names.get(i)));
        }
    }

    /** Las mismas cámaras, pero sólo las siluetas del escaneo {@code only} recortan: el volumen de esa toma sola. */
    private static List<PoseJoin.Cam> alone(List<PoseJoin.Cam> cams, int only) {
        List<PoseJoin.Cam> out = new ArrayList<>();
        byte[] full = null;
        for (PoseJoin.Cam c : cams) {
            if (c.scan == only) { out.add(c); continue; }
            if (full == null || full.length != c.w * c.h) { full = new byte[c.w * c.h]; java.util.Arrays.fill(full, (byte) 1); }
            PoseJoin.Cam d = new PoseJoin.Cam();
            d.scan = c.scan;
            d.w = c.w;
            d.h = c.h;
            d.f = c.f;
            d.cx = c.cx;
            d.cy = c.cy;
            d.pose = c.pose;
            d.mask = full;
            out.add(d);
        }
        return out;
    }

    /** Talla un solo volumen con las siluetas de la referencia y de las tomas que calzaron, y le pone textura. */
    private void carve(TextView note) throws IOException {
        long t0 = System.nanoTime();
        List<Part> ok = joined();
        List<PoseJoin.Cam> cams = new ArrayList<>();
        List<byte[]> photos = new ArrayList<>();
        List<PoseJoin.Fit> sheets = new ArrayList<>();
        List<double[]> pts = new ArrayList<>();
        for (int k = 0; k < ok.size(); k++) {
            Part p = ok.get(k);
            cams(p, k, cams, photos);
            sheets.add(p.fit);
            for (int id : p.object) pts.add(p.fit.apply(p.data.scan.points[id]));
        }
        cams(ref, ok.size(), cams, photos);
        for (int id : ref.object) pts.add(ref.data.scan.points[id]);
        if (cancel.get()) return;
        double[][] box = PoseJoin.box(pts);
        PoseJoin.Hull h = PoseJoin.carve(cams, box, sheets);
        if (h == null) throw new IOException("no quedó volumen (pocas siluetas, o no coinciden)");
        StringBuilder each = new StringBuilder();
        for (int k = 0; k <= ok.size(); k++) {
            PoseJoin.Hull one = PoseJoin.carve(alone(cams, k), box, sheets);
            each.append(k > 0 ? ", " : "").append(k < ok.size() ? ok.get(k).name : ref.name).append(' ').append(one == null ? "sin volumen" : Math.round(one.volume) + " cm³");
        }
        // Malla en mm, Z hacia arriba (ejes de la referencia)
        Mesh m = SurfaceNets.extract(h.grid);
        m.taubinSmooth(8);
        Mesh low = Decimate.simplify(m, MODEL_TRIS);
        // Textura del modelo simplificado con las fotos de todas las tomas (cada triángulo toma la foto que mejor lo ve)
        ui.post(() -> note.setText("Poniendo la textura con las fotos de todas las tomas…"));
        Texture.Result tex = null;
        String texNote;
        try {
            tex = texture(low, cams, photos);
            texNote = tex == null ? "sin textura (las fotos de las tomas no son del mismo tamaño)"
                    : String.format(Locale.ROOT, "textura de %d × %d px con %d fotos, %d triángulos sin ver (gris)", tex.width, tex.height, tex.photosUsed, tex.unseen);
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "textura", e);
            texNote = "sin textura (" + e.getMessage() + ")";
        }
        // Apoyada en Z = 0 y centrada (después de la textura, que necesita las coordenadas de las cámaras)
        float[] bd = m.bounds();
        float cx = (bd[0] + bd[1]) / 2, cy = (bd[2] + bd[3]) / 2;
        for (Mesh mm : new Mesh[]{m, low})
            for (int i = 0; i < mm.pos.length; i += 3) {
                mm.pos[i] -= cx;
                mm.pos[i + 1] -= cy;
                mm.pos[i + 2] -= bd[4];
            }
        File dir = new File(getCacheDir(), "union");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String base = base();
        File stl = new File(dir, base + ".stl"), glb = new File(dir, base + ".glb");
        m.writeStl(stl);
        try (FileOutputStream o = new FileOutputStream(glb)) {
            o.write(Glb.write(low.pos, low.tri, tex == null ? null : tex.uv, tex == null ? null : jpeg(tex), null, true, 0.001, base));
        }
        StringBuilder views = new StringBuilder();
        for (int v : h.views) views.append(views.length() > 0 ? " + " : "").append(v);
        String text = String.format(Locale.ROOT, "Volumen con las %d tomas: %.0f cm³ · %.0f × %.0f × %.0f mm (en los ejes de la referencia; el último es el alto).%n"
                        + "Cada toma sola, tallada igual: %s. La diferencia es lo que cada una recorta de las otras.%n"
                        + "Siluetas usadas: %s · malla de %d triángulos · modelo de %d con %s · %.1f s.%n"
                        + "Sigue siendo un volumen tallado con contornos: no ve huecos que ningún contorno muestra, y las partes finas pueden salir pegadas.",
                ok.size() + 1, h.volume, h.size[0], h.size[1], h.size[2], each, views, m.triangleCount(), low.triangleCount(), texNote, (System.nanoTime() - t0) / 1e9);
        ui.post(() -> {
            carveText = text;
            glbFile = glb;
            stlFile = stl;
            show(null);
        });
    }

    /** Textura del modelo con las fotos de todas las tomas; null si no son del mismo tamaño. */
    private Texture.Result texture(Mesh low, List<PoseJoin.Cam> cams, List<byte[]> photos) {
        int w = cams.get(0).w, h = cams.get(0).h;
        Sfm.Result r = new Sfm.Result();
        r.width = w;
        r.height = h;
        double[] focal = new double[cams.size()];
        for (int c = 0; c < cams.size(); c++) {
            PoseJoin.Cam k = cams.get(c);
            if (k.w != w || k.h != h) return null;
            r.poses.add(k.pose);
            r.camFrames.add(c);
            focal[c] = k.f;
        }
        r.focal = focal[0];
        double[][] verts = new double[low.vertexCount()][];
        for (int v = 0; v < verts.length; v++) verts[v] = new double[]{low.pos[3 * v], low.pos[3 * v + 1], low.pos[3 * v + 2]};
        return Texture.build(r, verts, low.tri, null, c -> pixels(photos.get(c), w, h), TEXTURE_PX, focal);
    }

    private static byte[] jpeg(Texture.Result t) {
        Bitmap bmp = Bitmap.createBitmap(t.atlas, t.width, t.height, Bitmap.Config.ARGB_8888);
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, o);
            return o.toByteArray();
        } finally {
            bmp.recycle();
        }
    }

    // ------------------------------------------------------------ guardar y compartir

    private String report() {
        StringBuilder sb = new StringBuilder("# Poses unidas\n\nReferencia: " + ref.name + "\n");
        for (Part p : parts) sb.append("\n## ").append(p.name).append("  →  ").append(ref.name).append("\n\n").append(p.text).append('\n');
        if (carveText != null) sb.append("\n## Un solo volumen\n\n").append(carveText).append('\n');
        String base = base();
        sb.append("\n## Archivos\n\n- `").append(base).append(".glb`: modelo de ").append(MODEL_TRIS).append(" triángulos con textura, en metros, Y hacia arriba.\n- `")
                .append(base).append(".stl`: el volumen completo, en mm, Z hacia arriba.\n- `").append(base)
                .append(".ply`: la nube unida, en mm, en los ejes de la referencia.\n- `union.json`: el movimiento de cada toma hacia la referencia (x_ref = R·x + t, en mm).\n");
        return sb.toString();
    }

    private String json() {
        StringBuilder sb = new StringBuilder("{\n \"formato\": \"navaja-union-2\",\n \"referencia\": \"" + ref.name + "\",\n \"tomas\": [");
        boolean first = true;
        for (Part p : joined()) {
            sb.append(first ? "\n" : ",\n").append(String.format(Locale.ROOT, "  {\"escaneo\": \"%s\", \"R\": [%s], \"t_mm\": [%s], \"pares_coherentes\": %d, \"pares\": %d, "
                    + "\"error_mm\": %.2f}", p.name, nums(p.fit.r), nums(p.fit.t), p.fit.inliers, p.fit.pairs, p.fit.error));
            first = false;
        }
        return sb.append("\n ]\n}\n").toString();
    }

    private static String nums(double[] v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) sb.append(i > 0 ? ", " : "").append(String.format(Locale.ROOT, "%.9f", v[i]));
        return sb.toString();
    }

    /** Guarda en la carpeta de los escaneos un ZIP con el modelo, el volumen, la nube unida, el movimiento y el informe. */
    private void saveAll() {
        String t = getSharedPreferences("medir", MODE_PRIVATE).getString("carpeta", null);
        Uri tree = null;
        if (t != null)
            for (android.content.UriPermission p : getContentResolver().getPersistedUriPermissions())
                if (p.getUri().equals(Uri.parse(t)) && p.isWritePermission()) tree = p.getUri();
        if (tree == null) {
            toast("No hay carpeta de escaneos con permiso de escritura: elígela en «Mis escaneos» y vuelve a intentar");
            return;
        }
        Uri folder = tree;
        String base = base(), report = report(), json = json();
        File glb = glbFile, stl = stlFile;
        ByteArrayOutputStream ply = new ByteArrayOutputStream();
        try (Writer w = new OutputStreamWriter(ply, StandardCharsets.US_ASCII)) {
            ply(w);
        } catch (IOException e) {
            toast("No se pudo guardar: " + e.getMessage());
            return;
        }
        toast("Guardando…");
        work.execute(() -> {
            try {
                Uri parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(folder, android.provider.DocumentsContract.getTreeDocumentId(folder));
                Uri doc = android.provider.DocumentsContract.createDocument(getContentResolver(), parent, "application/zip", base + ".zip");
                if (doc == null) throw new IOException("no se pudo crear el archivo");
                try (OutputStream os = getContentResolver().openOutputStream(doc)) {
                    if (os == null) throw new IOException("no se pudo abrir el archivo");
                    ZipOutputStream z = new ZipOutputStream(os);
                    entry(z, "informe.md", report.getBytes(StandardCharsets.UTF_8));
                    entry(z, base + ".glb", bytes(glb));
                    entry(z, base + ".stl", bytes(stl));
                    entry(z, base + ".ply", ply.toByteArray());
                    entry(z, "union.json", json.getBytes(StandardCharsets.UTF_8));
                    z.finish();
                }
                ui.post(() -> toast("Guardado en la carpeta de los escaneos: " + base + ".zip"));
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "guardar", e);
                ui.post(() -> toast("No se pudo guardar: " + e.getMessage()));
            }
        });
    }

    private static void entry(ZipOutputStream z, String name, byte[] data) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
    }

    private void shareFile(File f) {
        try {
            Uri u = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, u)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Compartir"));
        } catch (RuntimeException e) {
            Log.w(TAG, "compartir", e);
            toast("No se pudo compartir: " + e.getMessage());
        }
    }

    /** La nube unida, en mm y en los ejes de la referencia (Z hacia arriba, la hoja en Z = 0). */
    private void sharePly() {
        try {
            File dir = new File(getCacheDir(), "union");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File out = new File(dir, base() + ".ply");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.US_ASCII)) {
                ply(w);
            }
            shareFile(out);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "ply", e);
            toast("No se pudo compartir: " + e.getMessage());
        }
    }

    private void ply(Writer w) throws IOException {
        List<Part> ok = joined();
        int n = ref.object.size();
        for (Part p : ok) n += p.object.size();
        w.write("ply\nformat ascii 1.0\ncomment Navaja: poses unidas, mm, ejes de la toma de referencia\nelement vertex " + n
                + "\nproperty float x\nproperty float y\nproperty float z\nproperty uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n");
        for (Part p : ok) for (int id : p.object) line(w, p.fit.apply(p.data.scan.points[id]), p.data.scan.colors[id]);
        for (int id : ref.object) line(w, ref.data.scan.points[id], ref.data.scan.colors[id]);
    }

    private static void line(Writer w, double[] x, int color) throws IOException {
        w.write(String.format(Locale.ROOT, "%.2f %.2f %.2f %d %d %d%n", x[0], x[1], x[2], (color >> 16) & 255, (color >> 8) & 255, color & 255));
    }
}
