package com.josexato.navaja.measure;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.josexato.navaja.measure.core.Measure;
import com.josexato.navaja.measure.core.Sfm;
import com.josexato.navaja.ui.NavajaUi;
import com.josexato.navaja.ui.Palette;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;
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
    private static final int REQ_PICK = 1, REQ_RECORD = 2;
    /** Cuadros por segundo analizados y lado mayor de cada cuadro (validado con un video real de 19 s). */
    private static final double FPS = 15;
    private static final int LONG_SIDE = 960, MAX_POINTS = 600;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancel = new AtomicBoolean();
    private File recorded;
    private Measure.Choice choice;
    private double videoSeconds;
    private int known = 0;     // 0 alto, 1 ancho, 2 profundidad
    private double scaleCm = 0; // cm por unidad del modelo (0 = sin escala)

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
        NavajaUi.Screen scr = NavajaUi.screen(this, "Medir desde video", "Sin base giratoria: te mueves tú", null, true);
        LinearLayout c = scr.content;
        LinearLayout card = NavajaUi.card(this);
        card.addView(NavajaUi.text(this, "Cómo filmar", NavajaUi.Style.LABEL));
        card.addView(NavajaUi.text(this,
                "1. Filma 10–20 s moviéndote ALREDEDOR del objeto (no basta con girar el celular en el lugar).\n"
                        + "2. Despacio y sin sacudidas; que nada pase por delante y tape la vista.\n"
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
                int[] dims = new int[2];
                long[] dur = new long[1];
                VideoFrames.read(this, uri, FPS, LONG_SIDE, (argb, w, h, t, d) -> {
                    if (cancel.get()) return false;
                    if (dims[0] != 0 && (dims[0] != w || dims[1] != h)) return true; // cuadro de otro tamaño: se ignora
                    dims[0] = w;
                    dims[1] = h;
                    dur[0] = d;
                    tr.feed(argb, w, h);
                    int pct = d > 0 ? (int) Math.min(100, 100 * t / d) : 0;
                    post(String.format(Locale.getDefault(), "1/2 · Siguiendo puntos cuadro a cuadro: %d %%  (%d puntos activos)", pct, tr.activeCount()));
                    return true;
                });
                if (cancel.get()) return;
                videoSeconds = dur[0] / 1e6;
                if (tr.frames() < 20) throw new IOException("El video es muy corto: filma al menos 10 segundos");
                List<Sfm.Result> segs = Sfm.reconstruct(tr.tracks, tr.frames(), dims[0], dims[1], 0.8 * Math.max(dims[0], dims[1]),
                        (what, done, total) -> post("2/2 · " + what + ": " + done + " de ~" + total));
                Measure.Choice ch = Measure.best(segs);
                if (ch == null) throw new IOException("No pude reconstruir el objeto. Prueba moviéndote más alrededor de él, "
                        + "más despacio, y con algo de textura detrás.");
                ui.post(() -> {
                    choice = ch;
                    scaleCm = 0;
                    showResult();
                });
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "medir", e);
                if (!cancel.get()) ui.post(() -> error(e.getMessage()));
            }
        });
    }

    private void post(String s) { ui.post(() -> { if (progress != null) progress.setText(s); }); }

    // ============================================================ resultado

    private NavajaUi.Metric mAlto, mAncho, mProf;
    private TextView extra;

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
        c.addView(NavajaUi.text(this, "Arrastra para girar. En color, lo que se midió; la caja naranja es la medida.", NavajaUi.Style.MUTED));
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
        extra = NavajaUi.text(this, "", NavajaUi.Style.SECONDARY);
        NavajaUi.topMargin(extra, 8);
        c.addView(extra);
        c.addView(NavajaUi.buttonRow(this,
                NavajaUi.secondary(this, "Exportar nube (PLY)", v -> exportPly()),
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
        if (choice.segments > 1)
            sb.append("\nEl video se cortó en ").append(choice.segments).append(" tramos (algo tapó la vista o el movimiento fue muy brusco); se usó el mejor.");
        if (!b.upKnown) sb.append("\nEse tramo no empieza con el video: el «alto» es el lado más largo del objeto.");
        if (b.separation > 0.02 * b.alto && scaleCm > 0)
            sb.append(String.format(Locale.getDefault(), "\nSeparación entre el objeto y %s: %s.", b.onWall ? "la pared" : "la mesa", cm(b.separation)));
        sb.append(String.format(Locale.getDefault(), "\nCalidad: error de reproyección %.1f px · %d puntos del objeto. Precisión típica ±3–5 %% en alto y ancho, "
                + "±10 %% en profundidad.", r.rms, b.objectPoints.length));
        if (scaleCm <= 0) sb.append("\nSin una medida conocida sólo hay proporciones: el tamaño real no se puede saber desde un video.");
        extra.setText(sb);
    }

    private String cm(double modelUnits) { return String.format(Locale.getDefault(), "%.1f cm", modelUnits * scaleCm); }

    private static String ratio(double v) { return String.format(Locale.getDefault(), "%.2f", v); }

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
        new AlertDialog.Builder(this).setTitle("Medir desde video").setMessage(msg)
                .setPositiveButton("OK", (d, w) -> showIntro()).show();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
