package com.josexato.navaja.measure;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Log;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.josexato.navaja.measure.core.Carve;
import com.josexato.navaja.measure.core.Lin;
import com.josexato.navaja.measure.core.Measure;
import com.josexato.navaja.measure.core.Motion;
import com.josexato.navaja.measure.core.Sfm;
import com.josexato.navaja.scan3d.core.Mesh;
import com.josexato.navaja.scan3d.core.SurfaceNets;
import com.josexato.navaja.ui.NavajaUi;
import com.josexato.navaja.ui.Palette;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Medir un objeto desde un video: el celular se mueve alrededor del objeto
 * (sin base giratoria ni escenario impreso), se siguen cientos de puntos
 * cuadro a cuadro, se reconstruye la escena en 3D y se mide la caja del
 * objeto. La escala la pone el usuario con una medida conocida.
 */
public class MeasureActivity extends Activity {

    private static final String TAG = "Medir";
    private static final int REQ_PICK = 1, REQ_RECORD = 2, REQ_FOLDER = 3;
    /** Cuadros por segundo analizados y lado mayor de cada cuadro (validado con un video real de 19 s). */
    private static final double FPS = 15;
    private static final int LONG_SIDE = 960, MAX_POINTS = 600;
    /** Segunda pasada, sólo por el tramo elegido: más puntos para el objeto (no cambia la medida). */
    private static final int DENSE_POINTS = 2000;
    /** Reproceso de una zona: puntos a la vez y celda de siembra (px), más fina que la normal. */
    private static final int ZONE_POINTS = 1500, ZONE_CELL = 4;
    /** Las siluetas se guardan a la mitad de resolución del cuadro (sobra para tallar). */
    private static final int MASK_DIV = 2;
    /** Radio de la zona como fracción del lado mayor de la caja: chica, mediana, grande. */
    private static final double[] ZONE_SIZES = {0.10, 0.15, 0.25};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancel = new AtomicBoolean();
    private File recorded;
    private Measure.Choice choice;
    private double videoSeconds;
    private int known = 0;     // 0 alto, 1 ancho, 2 profundidad
    private double scaleCm = 0; // cm por unidad del modelo (0 = sin escala)
    /** Video medido y tamaño de sus cuadros, para reprocesar una zona. */
    private Uri videoUri;
    private int[] videoDims;
    private int zoneSize = 1;
    /** Centro de la zona elegida (índice en la nube), se conserva al volver del reproceso. */
    private int zonePoint = -1;
    /** Segunda opinión: volumen tallado con las siluetas (null si no se pudo). */
    private Carve.Result hull;
    /** Para el informe: fotos de los cuadros clave, siluetas, tramos y pistas. */
    private File[] photos;
    private byte[][] masks;
    private int maskW, maskH, trackCount;
    private final List<double[]> segInfo = new ArrayList<>();
    /** Por cuadro analizado: puntos activos, perdidos y nuevos (para ver dónde el movimiento fue brusco). */
    private final List<int[]> perFrame = new ArrayList<>();
    private boolean debug;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (b != null && b.getString("rec") != null) recorded = new File(b.getString("rec"));
        showIntro();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (recorded != null) out.putString("rec", recorded.getAbsolutePath());
    }

    @Override
    protected void onDestroy() {
        cancel.set(true);
        work.shutdownNow();
        super.onDestroy();
    }

    // ============================================================== inicio

    private void showIntro() {
        NavajaUi.Screen scr = NavajaUi.screen(this, "Escáner 3D desde video", "Sin escenario ni base giratoria: te mueves tú", null, true);
        LinearLayout c = scr.content;
        LinearLayout card = NavajaUi.card(this);
        card.addView(NavajaUi.text(this, "Cómo filmar", NavajaUi.Style.LABEL));
        card.addView(NavajaUi.text(this,
                "1. Filma 10–20 s moviéndote ALREDEDOR del objeto (no basta con girar el celular en el lugar).\n"
                        + "2. Despacio, sin sacudidas y a una distancia parecida; que nada pase por delante y tape la vista.\n"
                        + "   Mientras rodeas, sube y baja un poco el celular: así también se recortan bien arriba y abajo.\n"
                        + "3. Empieza con el celular derecho: así sé dónde es «arriba».\n"
                        + "4. Ayuda que el objeto y lo que hay detrás tengan textura (no paredes lisas ni vidrio).\n"
                        + "5. Al final pon una medida que conozcas (por ejemplo el alto) para tener centímetros.",
                NavajaUi.Style.BODY));
        c.addView(card);
        Button rec = NavajaUi.primary(this, "Grabar video", v -> record());
        NavajaUi.topMargin(rec, 12);
        c.addView(rec, new LinearLayout.LayoutParams(-1, -2));
        c.addView(NavajaUi.buttonRow(this, NavajaUi.secondary(this, "Elegir un video", v -> pick())));
        TextView note = NavajaUi.text(this, "Todo se calcula en el teléfono; el video no sale de aquí. Toma uno o dos minutos.",
                NavajaUi.Style.MUTED);
        NavajaUi.topMargin(note, 12);
        c.addView(note);
        setContentView(scr.root);
    }

    private void record() {
        File dir = new File(getExternalFilesDir(null), "medidas");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        recorded = new File(dir, "video_" + System.currentTimeMillis() + ".mp4");
        Uri out = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", recorded);
        Intent i = new Intent(MediaStore.ACTION_VIDEO_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, out)
                .putExtra(MediaStore.EXTRA_DURATION_LIMIT, 30)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_RECORD);
        } catch (ActivityNotFoundException e) {
            toast("No hay app de cámara para grabar video");
        }
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*");
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (ActivityNotFoundException e) {
            toast("No hay selector de archivos");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK) return;
        if (req == REQ_FOLDER && data != null && data.getData() != null) {
            Uri tree = data.getData();
            getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            prefs().edit().putString("carpeta", tree.toString()).apply();
            updateFolderText();
            if (pendingSave) save();
            return;
        }
        if (req == REQ_PICK && data != null && data.getData() != null) process(data.getData());
        else if (req == REQ_RECORD && recorded != null && recorded.length() > 0) process(Uri.fromFile(recorded));
        else if (req == REQ_RECORD && data != null && data.getData() != null) process(data.getData());
    }

    // ============================================================ proceso

    private TextView progress;

    private void process(Uri uri) {
        cancel.set(false);
        NavajaUi.Screen scr = NavajaUi.screen(this, "Midiendo…", "Esto toma uno o dos minutos", null, true);
        progress = NavajaUi.text(this, "Leyendo el video…", NavajaUi.Style.BODY);
        scr.content.addView(progress);
        scr.content.addView(NavajaUi.buttonRow(this, NavajaUi.secondary(this, "Cancelar", v -> {
            cancel.set(true);
            showIntro();
        })));
        setContentView(scr.root);
        work.execute(() -> {
            try {
                Sfm.Tracker tr = new Sfm.Tracker(MAX_POINTS);
                perFrame.clear();
                int[] dims = new int[2];
                long[] dur = new long[1];
                VideoFrames.read(this, uri, FPS, LONG_SIDE, (argb, w, h, t, d) -> {
                    if (cancel.get()) return false;
                    if (dims[0] != 0 && (dims[0] != w || dims[1] != h)) return true; // cuadro de otro tamaño: se ignora
                    dims[0] = w;
                    dims[1] = h;
                    dur[0] = d;
                    tr.feed(argb, w, h);
                    perFrame.add(new int[]{tr.activeCount(), tr.lastLost, tr.lastNew});
                    int pct = d > 0 ? (int) Math.min(100, 100 * t / d) : 0;
                    post(String.format(Locale.getDefault(), "1/3 · Siguiendo puntos cuadro a cuadro: %d %%  (%d puntos activos)", pct, tr.activeCount()));
                    return true;
                });
                if (cancel.get()) return;
                videoSeconds = dur[0] / 1e6;
                if (tr.frames() < 20) throw new IOException("El video es muy corto: filma al menos 10 segundos");
                List<Sfm.Result> segs = Sfm.reconstruct(tr.tracks, tr.frames(), dims[0], dims[1], 0.8 * Math.max(dims[0], dims[1]),
                        (what, done, total) -> post("2/3 · " + what + ": " + done + " de ~" + total));
                Measure.Choice ch = Measure.best(segs);
                segInfo.clear();
                for (int i = 0; i < segs.size(); i++) {
                    Sfm.Result g = segs.get(i);
                    // Pistas que cruzan el corte hacia el tramo siguiente (vivas al final de éste y al inicio del otro)
                    int cross = -1;
                    if (i + 1 < segs.size()) {
                        cross = 0;
                        int a = g.endFrame, b = segs.get(i + 1).startFrame;
                        for (Sfm.Track t : tr.tracks) if (t.visible(a) && t.visible(b)) cross++;
                    }
                    segInfo.add(new double[]{g.startFrame / FPS, g.endFrame / FPS, g.poses.size(), g.points.length, cross});
                }
                trackCount = tr.tracks.size();
                if (ch == null) throw new IOException("No pude reconstruir el objeto. Prueba moviéndote más alrededor de él, "
                        + "más despacio, y con algo de textura detrás.");
                videoUri = uri;
                videoDims = dims;
                hull = null;
                try {
                    densify(uri, ch, dims);
                } catch (IOException | RuntimeException e) {
                    // Sin la segunda pasada la medida sigue valiendo: sólo hay menos puntos
                    Log.w(TAG, "denso", e);
                }
                if (cancel.get()) return;
                ui.post(() -> {
                    choice = ch;
                    scaleCm = 0;
                    zonePoint = -1;
                    showResult();
                });
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "medir", e);
                if (!cancel.get()) ui.post(() -> error(e.getMessage()));
            }
        });
    }

    /**
     * Reprocesa una zona: vuelve a leer el tramo usado siguiendo puntos sólo
     * dentro de la esfera (centro = punto {@code center} de la nube) vista en
     * cada cuadro, con celdas más finas. Los puntos nuevos se suman al objeto
     * como los de la segunda pasada; la caja no cambia.
     */
    private void refine(int center, double radius) {
        if (center < 0 || videoUri == null) return;
        Measure.Choice ch = choice;
        Sfm.Result r = ch.result;
        double[] c0 = r.points[center];
        cancel.set(false);
        NavajaUi.Screen scr = NavajaUi.screen(this, "Más puntos en la zona…", "Leyendo el tramo de nuevo", null, true);
        progress = NavajaUi.text(this, "Leyendo el video…", NavajaUi.Style.BODY);
        scr.content.addView(progress);
        scr.content.addView(NavajaUi.buttonRow(this, NavajaUi.secondary(this, "Cancelar", v -> {
            cancel.set(true);
            showResult();
        })));
        setContentView(scr.root);
        Uri uri = videoUri;
        int[] dims = videoDims;
        work.execute(() -> {
            try {
                Sfm.Tracker zt = new Sfm.Tracker(ZONE_POINTS, new Sfm.Zone(r, c0, radius, r.startFrame), ZONE_CELL);
                int[] idx = {-1};
                int span = r.endFrame - r.startFrame + 1;
                VideoFrames.read(this, uri, FPS, LONG_SIDE, (argb, w, h, t, d) -> {
                    if (cancel.get()) return false;
                    if (w != dims[0] || h != dims[1]) return true;
                    if (++idx[0] < r.startFrame) return true;
                    zt.feed(argb, w, h);
                    post(String.format(Locale.getDefault(), "Siguiendo puntos en la zona: %d %%  (%d activos)",
                            100 * (idx[0] - r.startFrame + 1) / span, zt.activeCount()));
                    return idx[0] < r.endFrame;
                });
                if (cancel.get()) return;
                // Se aplica en el hilo de la interfaz: si justo se canceló, la nube que se ve no cambia a medias
                ui.post(() -> {
                    if (cancel.get()) return;
                    int first = r.points.length, objBefore = ch.box.objectPoints.length;
                    int added = Sfm.densify(r, zt.tracks, r.startFrame);
                    Measure.include(r, ch.box, first);
                    int inZone = 0;
                    for (int i = first; i < r.points.length; i++) if (Lin.norm(Lin.sub(r.points[i], c0)) <= radius) inZone++;
                    int toObj = ch.box.objectPoints.length - objBefore;
                    Log.i(TAG, "zona: " + zt.tracks.size() + " pistas, +" + added + " puntos, " + inZone + " en la zona, +" + toObj + " al objeto");
                    showResult();
                    toast(toObj == 0 ? "No se encontraron puntos nuevos: la zona tiene poca textura o se ve poco en el tramo."
                            : "+" + toObj + " puntos del objeto (" + inZone + " dentro del círculo)");
                });
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "zona", e);
                if (!cancel.get()) ui.post(() -> {
                    showResult();
                    toast("No se pudo reprocesar la zona: " + e.getMessage());
                });
            }
        });
    }

    /**
     * Vuelve a leer el video, sólo el tramo elegido, siguiendo muchos más
     * puntos; con las poses ya conocidas cada uno sólo se triangula. Los
     * cuadros se cuentan igual que en la primera pasada (mismo muestreo y
     * mismos descartes), así que el índice coincide.
     */
    private void densify(Uri uri, Measure.Choice ch, int[] dims) throws IOException {
        Sfm.Result r = ch.result;
        Sfm.Tracker dt = new Sfm.Tracker(DENSE_POINTS, false);
        // En la misma pasada: la silueta del objeto en cada cuadro clave (segunda opinión de la medida)
        Map<Integer, Integer> camOf = new HashMap<>();
        for (int c = 0; c < r.camFrames.size(); c++) camOf.put(r.camFrames.get(c), c);
        float[][] seeds = Carve.seeds(r, ch.box);
        byte[][] cuts = new byte[r.poses.size()][];
        int mw = dims[0] / MASK_DIV, mh = dims[1] / MASK_DIV;
        File photoDir = new File(getCacheDir(), "medida/fotos");
        deleteTree(photoDir.getParentFile());
        //noinspection ResultOfMethodCallIgnored
        photoDir.mkdirs();
        File[] shots = new File[r.poses.size()];
        Silhouettes sil = null;
        try {
            sil = new Silhouettes(this);
        } catch (IOException | RuntimeException | LinkageError e) {
            Log.w(TAG, "siluetas", e); // sin modelo (o sin su librería nativa): sólo la medida por puntos
        }
        Silhouettes model = sil;
        int[] idx = {-1};
        int span = r.endFrame - r.startFrame + 1;
        try {
            VideoFrames.read(this, uri, FPS, LONG_SIDE, (argb, w, h, t, d) -> {
                if (cancel.get()) return false;
                if (w != dims[0] || h != dims[1]) return true;
                if (++idx[0] < r.startFrame) return true;
                dt.feed(argb, w, h);
                Integer c = camOf.get(idx[0]);
                if (c != null) shots[c] = savePhoto(argb, w, h, new File(photoDir, MeasureReport.photoName(idx[0])));
                if (model != null && c != null) {
                    try {
                        cuts[c] = model.segment(argb, w, h, seeds[c], mw, mh);
                    } catch (RuntimeException e) {
                        Log.w(TAG, "silueta del cuadro " + idx[0], e); // se pierde sólo esta silueta
                    }
                }
                post(String.format(Locale.getDefault(), "3/3 · Agregando puntos y siluetas del objeto: %d %%", 100 * (idx[0] - r.startFrame + 1) / span));
                return idx[0] < r.endFrame;
            });
        } finally {
            if (model != null) model.close();
        }
        if (cancel.get()) return;
        // El casco usa sólo los puntos ralos (con sus pistas): se talla antes de sumar los densos
        // Con siluetas se sabe mejor qué puntos son del objeto: los que caen dentro de la silueta en casi todas
        // las vistas (la agrupación por cercanía puede quedarse con una parte). Con ellos, caja nueva y tallado.
        if (model != null) {
            Measure.Box nb = Measure.rebox(r, ch.box, Carve.silhouetteMembers(r, ch.box, cuts, mw, mh));
            if (nb != null) {
                Log.i(TAG, "objeto según las siluetas: " + nb.objectPoints.length + " puntos (antes " + ch.box.objectPoints.length + ")");
                ch.box = nb;
            }
        }
        hull = model == null ? null : Carve.carve(r, ch.box, cuts, mw, mh);
        if (hull != null) Measure.refineWithHull(r, ch.box, hull);
        photos = shots;
        this.masks = cuts;
        maskW = mw;
        maskH = mh;
        int first = r.points.length;
        int added = Sfm.densify(r, dt.tracks, r.startFrame);
        Measure.include(r, ch.box, first);
        Log.i(TAG, "denso: " + dt.tracks.size() + " pistas, +" + added + " puntos, objeto " + ch.box.objectPoints.length
                + (hull == null ? " · sin casco" : String.format(Locale.ROOT, " · casco %d siluetas, diferencia %.1f %%", hull.views,
                100 * Carve.disagreement(ch.box, hull))));
    }

    /** Guarda el cuadro como JPEG (para el informe); null si no se pudo. */
    private static File savePhoto(int[] argb, int w, int h, File f) {
        Bitmap bmp = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888);
        try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, fo);
            return f;
        } catch (IOException e) {
            return null;
        } finally {
            bmp.recycle();
        }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private void post(String s) { ui.post(() -> { if (progress != null) progress.setText(s); }); }

    // ============================================================ resultado

    private NavajaUi.Metric mAlto, mAncho, mProf;
    private TextView extra, hullText, folderText;

    private void showResult() {
        Sfm.Result r = choice.result;
        Measure.Box b = choice.box;
        NavajaUi.Screen scr = NavajaUi.screen(this, "Medidas del objeto", b.onWall ? "Apoyado en una pared" : "Sobre una mesa o el piso",
                NavajaUi.headerAction(this, "Otro", v -> showIntro()), true);
        LinearLayout c = scr.content;
        CloudView cloud = new CloudView(this);
        cloud.set(r.points, r.colors, b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, NavajaUi.dp(this, 320));
        c.addView(cloud, lp);
        c.addView(NavajaUi.text(this, "Arrastra para girar. En color, lo que se midió; la caja naranja es la medida. "
                + "Toca el objeto para elegir una zona.", NavajaUi.Style.MUTED));
        mAlto = NavajaUi.metric(this, "Alto");
        mAncho = NavajaUi.metric(this, "Ancho");
        mProf = NavajaUi.metric(this, b.onWall ? "Profundidad" : "Largo");
        c.addView(NavajaUi.metricRow(this, mAlto, mAncho, mProf));
        // Medida conocida → escala
        LinearLayout card = NavajaUi.card(this);
        card.addView(NavajaUi.text(this, "Una medida que conozcas", NavajaUi.Style.LABEL));
        LinearLayout chips = new LinearLayout(this);
        String[] names = {"Alto", "Ancho", b.onWall ? "Profundidad" : "Largo"};
        TextView[] tabs = NavajaUi.tabs(chips, names, known, i -> known = i);
        card.addView(chips);
        EditText cm = new EditText(this);
        cm.setHint("en centímetros, p. ej. 25");
        cm.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        cm.setTextColor(Palette.TEXT);
        cm.setHintTextColor(Palette.TEXT_3);
        card.addView(cm);
        card.addView(NavajaUi.buttonRow(this, NavajaUi.primary(this, "Calcular en cm", v -> {
            try {
                double val = Double.parseDouble(cm.getText().toString().replace(',', '.'));
                double model = known == 0 ? b.alto : known == 1 ? b.ancho : b.prof;
                if (val <= 0 || model <= 0) throw new NumberFormatException();
                scaleCm = val / model;
                updateMetrics();
            } catch (NumberFormatException e) {
                toast("Escribe un número en centímetros");
            }
        })));
        c.addView(card);
        if (tabs.length > 0) NavajaUi.setChip(tabs[known], true);
        // Segunda opinión: el volumen tallado con las siluetas (IA en el teléfono)
        if (hull != null) {
            LinearLayout hc = NavajaUi.card(this);
            hc.addView(NavajaUi.text(this, "Segunda opinión: siluetas", NavajaUi.Style.LABEL));
            hullText = NavajaUi.text(this, "", NavajaUi.Style.BODY);
            hc.addView(hullText);
            hc.addView(NavajaUi.buttonRow(this, NavajaUi.secondary(this, "Compartir volumen (STL)", v -> exportStl())));
            c.addView(hc);
        } else {
            hullText = null;
        }
        // Más puntos en una zona: se vuelve a leer el tramo siguiendo puntos sólo ahí
        LinearLayout zc = NavajaUi.card(this);
        zc.addView(NavajaUi.text(this, "Más puntos en una zona", NavajaUi.Style.LABEL));
        TextView zHint = NavajaUi.text(this, "", NavajaUi.Style.SECONDARY);
        zc.addView(zHint);
        LinearLayout zChips = new LinearLayout(this);
        double side = Math.max(b.alto, Math.max(b.ancho, b.prof));
        TextView[] zTabs = NavajaUi.tabs(zChips, new String[]{"Chica", "Mediana", "Grande"}, zoneSize, i -> {
            zoneSize = i;
            cloud.setZoneRadius(ZONE_SIZES[i] * side);
        });
        zc.addView(zChips);
        Button zBtn = NavajaUi.primary(this, "Reprocesar la zona", v -> refine(cloud.picked(), ZONE_SIZES[zoneSize] * side));
        zc.addView(NavajaUi.buttonRow(this, zBtn));
        c.addView(zc);
        if (zTabs.length > 0) NavajaUi.setChip(zTabs[zoneSize], true);
        cloud.setZoneRadius(ZONE_SIZES[zoneSize] * side);
        Runnable zState = () -> {
            boolean ok = cloud.picked() >= 0 && videoUri != null;
            zBtn.setEnabled(ok);
            zBtn.setAlpha(ok ? 1f : 0.5f);
            zHint.setText(videoUri == null ? "No se puede volver a leer el video."
                    : cloud.picked() < 0 ? "Toca un punto del objeto en la nube; el círculo blanco es la zona."
                    : "Vuelve a leer el tramo usado siguiendo puntos sólo en el círculo (unos segundos). La medida no cambia.");
        };
        cloud.setOnPick(() -> {
            zonePoint = cloud.picked();
            zState.run();
        });
        if (zonePoint >= 0) cloud.select(zonePoint);
        zState.run();
        extra = NavajaUi.text(this, "", NavajaUi.Style.SECONDARY);
        NavajaUi.topMargin(extra, 8);
        c.addView(extra);
        // Guardar todo en una carpeta elegida (también la raíz de Archivos)
        LinearLayout sc = NavajaUi.card(this);
        sc.addView(NavajaUi.text(this, "Guardar", NavajaUi.Style.LABEL));
        sc.addView(NavajaUi.text(this, "Un ZIP con el informe paso a paso, la nube (PLY), el volumen (STL) y las fotos usadas.",
                NavajaUi.Style.SECONDARY));
        CheckBox dbg = new CheckBox(this);
        dbg.setText("Depuración: dibujar la silueta y 5 puntos clave en cada foto");
        dbg.setTextColor(Palette.TEXT);
        dbg.setChecked(debug);
        dbg.setOnCheckedChangeListener((v, on) -> debug = on);
        sc.addView(dbg);
        folderText = NavajaUi.text(this, "", NavajaUi.Style.MUTED);
        sc.addView(folderText);
        sc.addView(NavajaUi.buttonRow(this, NavajaUi.primary(this, "Guardar (ZIP)", v -> save()),
                NavajaUi.secondary(this, "Cambiar carpeta", v -> chooseFolder())));
        c.addView(sc);
        updateFolderText();
        c.addView(NavajaUi.buttonRow(this,
                NavajaUi.secondary(this, "Compartir nube (PLY)", v -> exportPly()),
                NavajaUi.secondary(this, "Otro video", v -> showIntro())));
        setContentView(scr.root);
        updateMetrics();
    }

    private void updateMetrics() {
        Measure.Box b = choice.box;
        Sfm.Result r = choice.result;
        if (scaleCm > 0) {
            mAlto.set(cm(b.alto), known == 0 ? "tu medida" : null, known == 0 ? 0 : Palette.OK);
            mAncho.set(cm(b.ancho), known == 1 ? "tu medida" : null, known == 1 ? 0 : Palette.OK);
            mProf.set(cm(b.prof), known == 2 ? "tu medida" : null, known == 2 ? 0 : Palette.OK);
        } else {
            mAlto.set("1", "referencia", 0);
            mAncho.set(ratio(b.ancho / b.alto), "× el alto", 0);
            mProf.set(ratio(b.prof / b.alto), "× el alto", 0);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.getDefault(), "Tramo usado: %.1f–%.1f s de %.1f s · %d posiciones del celular · %.0f° alrededor del objeto",
                r.startFrame / FPS, r.endFrame / FPS, videoSeconds, r.poses.size(), choice.arcDeg));
        double usedS = (r.endFrame - r.startFrame) / FPS;
        sb.append(String.format(Locale.getDefault(), "\nVideo usado: %.0f %% (%.1f de %.1f s).", videoSeconds > 0 ? 100 * usedS / videoSeconds : 0, usedS,
                videoSeconds));
        if (choice.segments > 1)
            sb.append(" El video se cortó en ").append(choice.segments).append(" tramos (algo tapó la vista o el movimiento fue muy brusco); "
                    + "se usó el mejor y los otros se descartan.");
        int wf = Motion.worstFrame(perFrame);
        if (wf > 0)
            sb.append(String.format(Locale.getDefault(), "\nCambio más brusco: a los %.1f s se perdió el %.0f %% de los puntos de un cuadro al siguiente.",
                    wf / FPS, 100 * Motion.lostFraction(perFrame, wf)));
        if (!b.upKnown) sb.append("\nEse tramo no empieza con el video: el «alto» es el lado más largo del objeto.");
        if (b.separation > 0.02 * b.alto && scaleCm > 0)
            sb.append(String.format(Locale.getDefault(), "\nSeparación entre el objeto y %s: %s.", b.onWall ? "la pared" : "la mesa", cm(b.separation)));
        sb.append(String.format(Locale.getDefault(), "\nCalidad: error de reproyección %.1f px · %d puntos del objeto. Precisión típica ±3–5 %% en alto y ancho, "
                + "±10 %% en profundidad.", r.rms, b.objectPoints.length));
        if (scaleCm <= 0) sb.append("\nSin una medida conocida sólo hay proporciones: el tamaño real no se puede saber desde un video.");
        extra.setText(sb);
        if (hullText != null) hullText.setText(hullSummary());
    }

    /**
     * Cuántos cm por unidad del casco: cada método se escala con su propia
     * dimensión de la medida conocida (los puntos dispersos agrandan un poco la
     * caja por igual en los tres ejes; las proporciones son lo comparable).
     */
    private double hullCmPerUnit() {
        Measure.Box b = choice.box;
        double pts = known == 0 ? pointsHeight(b) : known == 1 ? b.ancho : pointsThird(b);
        double hl = known == 0 ? hullHeight(b) : known == 1 ? hull.ancho : hullThird(b);
        return scaleCm * pts / hl;
    }

    /** Alto: en una pared, a lo largo de ella; sobre una mesa, del apoyo al tope (incluye la separación). */
    private static double pointsHeight(Measure.Box b) { return b.onWall ? b.alto : b.separation + b.alto; }

    private double hullHeight(Measure.Box b) { return b.onWall ? hull.alto : hull.front; }

    /** Tercera medida: en una pared, de la pared al frente; sobre una mesa, el largo. */
    private static double pointsThird(Measure.Box b) { return b.onWall ? b.separation + b.prof : b.prof; }

    private double hullThird(Measure.Box b) { return b.onWall ? hull.front : hull.prof; }

    private CharSequence hullSummary() {
        Measure.Box b = choice.box;
        double d = Carve.disagreement(b, hull);
        String third = b.onWall ? "De la pared al frente" : "Largo";
        double hh = hullHeight(b), ht = hullThird(b);
        StringBuilder sb = new StringBuilder();
        if (scaleCm > 0) {
            double k = hullCmPerUnit();
            sb.append(String.format(Locale.getDefault(), "Alto %s · Ancho %s · %s %s", cmOf(hh * k), cmOf(hull.ancho * k), third, cmOf(ht * k)));
            sb.append(String.format(Locale.getDefault(), "\nVolumen ≈ %,.0f cm³ (macizo: sin huecos que no se vean desde fuera)", hull.volume * k * k * k));
        } else {
            sb.append(String.format(Locale.getDefault(), "Ancho %s × el alto · %s %s × el alto", ratio(hull.ancho / hh),
                    third.toLowerCase(Locale.ROOT), ratio(ht / hh)));
        }
        int pct = (int) Math.round(100 * d);
        if (d <= 0.08) sb.append("\n✓ Coincide con la medida por puntos (diferencia ").append(pct).append(" %).");
        else if (d <= 0.15) sb.append("\n≈ Coincide a medias (diferencia ").append(pct).append(" %): toma la medida con cautela.");
        else sb.append("\n⚠ No coincide (diferencia ").append(pct).append(" %): el video puede venir mal (poco recorrido, objeto cortado o "
                    + "imagen movida). Prueba con otro video.");
        sb.append("\nTallado con ").append(hull.views).append(" siluetas del objeto (IA en el teléfono) y el espacio libre que dejan los puntos.");
        return sb;
    }

    /** El volumen tallado como STL: en mm si hay medida conocida; si no, con el alto = 100 mm. */
    private void exportStl() {
        if (hull == null) return;
        File dir = new File(getExternalFilesDir(null), "medidas");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, "volumen_" + System.currentTimeMillis() + ".stl");
        try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f)) {
            fo.write(MeasureReport.stl(reportData()));
        } catch (IOException e) {
            toast("No se pudo guardar: " + e.getMessage());
            return;
        }
        if (scaleCm <= 0) toast("Sin medida conocida: el STL tiene el alto = 100 mm");
        Uri u = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
        Intent i = new Intent(Intent.ACTION_SEND).setType("model/stl").putExtra(Intent.EXTRA_STREAM, u)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Compartir volumen (STL)"));
    }

    private String cm(double modelUnits) { return cmOf(modelUnits * scaleCm); }

    private static String cmOf(double cm) { return String.format(Locale.getDefault(), "%.1f cm", cm); }

    private static String ratio(double v) { return String.format(Locale.getDefault(), "%.2f", v); }

    // ============================================================ guardar

    private boolean pendingSave;

    private SharedPreferences prefs() { return getSharedPreferences("medir", MODE_PRIVATE); }

    /** La carpeta elegida, si todavía tenemos permiso para escribir en ella. */
    private Uri folder() {
        String t = prefs().getString("carpeta", null);
        if (t == null) return null;
        Uri u = Uri.parse(t);
        for (android.content.UriPermission p : getContentResolver().getPersistedUriPermissions())
            if (p.getUri().equals(u) && p.isWritePermission()) return u;
        return null;
    }

    private void chooseFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_FOLDER);
        } catch (ActivityNotFoundException e) {
            toast("Este teléfono no permite elegir carpetas");
        }
    }

    private void updateFolderText() {
        if (folderText == null) return;
        Uri u = folder();
        folderText.setText(u == null ? "Carpeta: la eliges la primera vez (puedes crear una, p. ej. «Navaja», en la raíz de Archivos)."
                : "Carpeta: " + folderName(u));
    }

    private String folderName(Uri tree) {
        Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        try (Cursor cur = getContentResolver().query(doc, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (RuntimeException ignored) {
            // sin nombre: se muestra el identificador
        }
        return DocumentsContract.getTreeDocumentId(tree);
    }

    MeasureReport.Data reportData() {
        MeasureReport.Data d = new MeasureReport.Data();
        d.choice = choice;
        d.hull = hull;
        d.scaleCm = scaleCm;
        d.hullCmPerUnit = hull != null && scaleCm > 0 ? hullCmPerUnit() : 0;
        d.knownName = new String[]{"el alto", "el ancho", choice.box.onWall ? "la profundidad" : "el largo"}[known];
        d.videoSeconds = videoSeconds;
        d.fps = FPS;
        d.tracks = trackCount;
        d.segments.addAll(segInfo);
        d.perFrame.addAll(perFrame);
        d.photos = photos;
        d.masks = masks;
        d.maskW = maskW;
        d.maskH = maskH;
        return d;
    }

    /** ZIP con todo en la carpeta elegida: medida_AAAAMMDD_HHMMSS.zip. */
    private void save() {
        Uri tree = folder();
        if (tree == null) {
            pendingSave = true;
            toast("Elige la carpeta donde guardar (la recordaré)");
            chooseFolder();
            return;
        }
        pendingSave = false;
        MeasureReport.Data d = reportData();
        boolean withDebug = debug;
        String name = "medida_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date()) + ".zip";
        toast("Guardando…");
        work.execute(() -> {
            try {
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
                Uri doc = DocumentsContract.createDocument(getContentResolver(), parent, "application/zip", name);
                if (doc == null) throw new IOException("no se pudo crear el archivo");
                try (OutputStream os = getContentResolver().openOutputStream(doc)) {
                    if (os == null) throw new IOException("no se pudo abrir el archivo");
                    MeasureReport.write(os, d, withDebug);
                }
                String where = folderName(tree) + "/" + name;
                ui.post(() -> toast("Guardado en " + where));
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "guardar", e);
                ui.post(() -> toast("No se pudo guardar: " + e.getMessage() + ". Prueba «Cambiar carpeta»."));
            }
        });
    }

    private void exportPly() {
        Sfm.Result r = choice.result;
        double k = scaleCm > 0 ? scaleCm : 1;
        File dir = new File(getExternalFilesDir(null), "medidas");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, "nube_" + System.currentTimeMillis() + ".ply");
        boolean[] obj = new boolean[r.points.length];
        for (int i : choice.box.objectPoints) obj[i] = true;
        try (PrintWriter pw = new PrintWriter(f)) {
            pw.print("ply\nformat ascii 1.0\ncomment Navaja: nube del objeto medido" + (scaleCm > 0 ? " (cm)" : " (escala arbitraria)") + "\n");
            pw.print("element vertex " + choice.box.objectPoints.length + "\nproperty float x\nproperty float y\nproperty float z\n"
                    + "property uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n");
            for (int i = 0; i < r.points.length; i++) {
                if (!obj[i]) continue;
                double[] q = r.points[i];
                int col = r.colors[i];
                pw.printf(Locale.US, "%.4f %.4f %.4f %d %d %d%n", q[0] * k, q[1] * k, q[2] * k, (col >> 16) & 255, (col >> 8) & 255, col & 255);
            }
        } catch (IOException e) {
            toast("No se pudo guardar: " + e.getMessage());
            return;
        }
        Uri u = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
        Intent i = new Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, u)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Compartir nube de puntos"));
    }

    private void error(String msg) {
        new AlertDialog.Builder(this).setTitle("Escáner 3D desde video").setMessage(msg)
                .setPositiveButton("OK", (d, w) -> showIntro()).show();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
