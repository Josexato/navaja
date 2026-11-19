package com.josexato.navaja.measure;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.util.Log;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.josexato.navaja.measure.core.Markers;
import com.josexato.navaja.measure.core.Sheet;
import com.josexato.navaja.ui.NavajaUi;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Modo "Hoja" (experimental): el objeto quieto sobre una hoja impresa con
 * marcas y el celular moviéndose alrededor; las marcas dan la pose de la
 * cámara en cada cuadro.
 *
 * <p>Escáner 3D, modo 2 (ver docs/ESCANER_3D.md): esta pantalla da la hoja
 * (PDF) y abre el escaneo, que corre en {@link MeasureActivity} con las poses
 * de la hoja. En «Diagnóstico» queda la prueba del detector de marcas sobre
 * un video: cuántas marcas se ven por cuadro, qué tan grandes, cuáles fallan,
 * y algunos cuadros con las marcas dibujadas.
 */
public class SheetActivity extends Activity {

    private static final String TAG = "NavajaHoja";
    private static final int REQ_RECORD = 1, REQ_PICK = 2;
    /** Cuadros por segundo analizados y lado mayor (px): más resolución que el modo video, las celdas de las marcas son chicas. */
    private static final double FPS = 15;
    private static final int LONG_SIDE = 1280;

    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancel = new AtomicBoolean();
    private TextView progress;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        NavajaUi.window(this);
        showIntro();
    }

    @Override
    protected void onDestroy() {
        cancel.set(true);
        work.shutdownNow();
        super.onDestroy();
    }

    private void showIntro() {
        NavajaUi.Screen scr = NavajaUi.screen(this, "Video sobre la hoja con marcas", "Escáner 3D · modo 2 (experimental) · la hoja da la posición del celular y los mm", null, true);
        LinearLayout c = scr.content;
        LinearLayout card = NavajaUi.card(this);
        card.addView(NavajaUi.text(this, "Cómo se usa", NavajaUi.Style.LABEL));
        card.addView(NavajaUi.text(this, "1. Abre la hoja e imprímela en A4 al 100 % (sin «ajustar a la página»). Mide la barra de 100 mm con una regla.\n"
                + "2. Ponla sobre la mesa y el objeto en el recuadro del centro (hasta ~11 × 19 cm de base).\n"
                + "3. «Grabar y escanear»: graba 15–30 s rodeando el objeto, con la hoja entera o casi entera en la imagen.\n"
                + "Las medidas salen en mm sin escribir ninguna medida. Para más puntos sobre el objeto se usa además XFeat (IA en el teléfono).",
                NavajaUi.Style.BODY));
        c.addView(card);
        c.addView(NavajaUi.buttonRow(this, NavajaUi.secondary(this, "Abrir la hoja (PDF)", v -> openPdf())));
        c.addView(NavajaUi.text(this, "Escanear el objeto", NavajaUi.Style.LABEL));
        c.addView(NavajaUi.buttonRow(this, NavajaUi.primary(this, "Grabar y escanear", v -> scan("grabar")),
                NavajaUi.secondary(this, "Escanear un video", v -> scan("elegir"))));
        // Diagnóstico: la prueba de la etapa 1 (sólo detecta las marcas y guarda las fotos anotadas)
        LinearLayout dg = NavajaUi.card(this);
        NavajaUi.topMargin(dg, 24);
        dg.addView(NavajaUi.text(this, "Diagnóstico", NavajaUi.Style.LABEL));
        dg.addView(NavajaUi.text(this, "Sólo revisa cuántas marcas de la hoja se ven en cada cuadro (no escanea el objeto) y guarda un ZIP con las marcas dibujadas. "
                + "Útil si el escaneo dice que la hoja casi no se vio.", NavajaUi.Style.MUTED));
        dg.addView(NavajaUi.buttonRow(this, NavajaUi.secondary(this, "Probar marcas: grabar", v -> startActivityForResult(new Intent(this, SensorRecordActivity.class), REQ_RECORD)),
                NavajaUi.secondary(this, "Probar marcas: elegir video", v -> pick())));
        c.addView(dg);
        setContentView(scr.root);
    }

    /** El escáner desde video en modo hoja, empezando por grabar o por elegir el video. */
    private void scan(String start) {
        startActivity(new Intent(this, MeasureActivity.class).putExtra(MeasureActivity.EXTRA_SHEET, true).putExtra(MeasureActivity.EXTRA_START, start));
    }

    private void openPdf() {
        try {
            File dir = new File(getExternalFilesDir(null), "print");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, "hoja_navaja_A4.pdf");
            SheetPdf.write(f);
            Uri u = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Abrir la hoja para imprimir"));
        } catch (IOException | RuntimeException e) {
            toast("No se pudo crear el PDF: " + e.getMessage());
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
        if (res != RESULT_OK || data == null) {
            if (req == REQ_RECORD && data != null && data.getStringExtra(SensorRecordActivity.EXTRA_ERROR) != null)
                toast(data.getStringExtra(SensorRecordActivity.EXTRA_ERROR));
            return;
        }
        if (req == REQ_RECORD && data.getStringExtra(SensorRecordActivity.EXTRA_VIDEO) != null)
            analyze(Uri.fromFile(new File(data.getStringExtra(SensorRecordActivity.EXTRA_VIDEO))));
        else if (req == REQ_PICK && data.getData() != null) analyze(data.getData());
    }

    // ============================================================ análisis

    /** Lo que se junta del video. */
    private static final class Stats {
        int frames, w, h;
        double seconds;
        final List<Integer> perFrame = new ArrayList<>();
        final int[] seen = new int[Sheet.count()];
        final List<Double> sides = new ArrayList<>();
        final List<File> shots = new ArrayList<>();
        final List<String> shotInfo = new ArrayList<>();
        long detectNs;
    }

    private void analyze(Uri uri) {
        cancel.set(false);
        NavajaUi.Screen scr = NavajaUi.screen(this, "Revisando la hoja…", "Buscando las marcas en cada cuadro", null, true);
        progress = NavajaUi.text(this, "Leyendo el video…", NavajaUi.Style.BODY);
        scr.content.addView(progress);
        scr.content.addView(NavajaUi.buttonRow(this, NavajaUi.secondary(this, "Cancelar", v -> {
            cancel.set(true);
            showIntro();
        })));
        setContentView(scr.root);
        File shotDir = new File(getCacheDir(), "hoja");
        File[] old = shotDir.listFiles();
        if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
            f.delete();
        //noinspection ResultOfMethodCallIgnored
        shotDir.mkdirs();
        work.execute(() -> {
            Stats st = new Stats();
            double[] next = {0.1};
            try {
                VideoFrames.read(this, uri, FPS, LONG_SIDE, (argb, w, h, t, d) -> {
                    if (cancel.get()) return false;
                    st.w = w;
                    st.h = h;
                    st.seconds = d / 1e6;
                    long t0 = System.nanoTime();
                    List<Markers.Marker> ms = Markers.detect(argb, w, h);
                    st.detectNs += System.nanoTime() - t0;
                    st.frames++;
                    st.perFrame.add(ms.size());
                    for (Markers.Marker m : ms) {
                        st.seen[m.id]++;
                        st.sides.add(m.side);
                    }
                    // Cinco cuadros repartidos en el video, con las marcas dibujadas
                    if (d > 0 && t >= next[0] * d && st.shots.size() < 5) {
                        next[0] += 0.2;
                        File f = new File(shotDir, String.format(Locale.ROOT, "cuadro_%03d.jpg", st.frames - 1));
                        if (annotate(argb, w, h, ms, f)) {
                            st.shots.add(f);
                            st.shotInfo.add(String.format(Locale.ROOT, "%.1f s · %d marcas", t / 1e6, ms.size()));
                        }
                    }
                    if (st.frames % 5 == 0) {
                        int pct = d > 0 ? (int) Math.min(100, 100 * t / d) : 0;
                        post(String.format(Locale.getDefault(), "%d %% · cuadro %d · %d marcas", pct, st.frames, ms.size()));
                    }
                    return true;
                });
                if (cancel.get()) return;
                ui.post(() -> showResult(st));
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "análisis", e);
                ui.post(() -> {
                    toast("No se pudo leer el video: " + e.getMessage());
                    showIntro();
                });
            }
        });
    }

    /** Guarda el cuadro con cada marca dibujada (contorno verde, esquina de arriba-izquierda marcada, ID). */
    private static boolean annotate(int[] argb, int w, int h, List<Markers.Marker> ms, File f) {
        Bitmap bmp = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true);
        Canvas c = new Canvas(bmp);
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Math.max(2, w / 300f));
        line.setColor(Color.rgb(40, 230, 90));
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(Color.rgb(255, 70, 70));
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.rgb(255, 230, 0));
        text.setTextSize(Math.max(14, w / 30f));
        text.setShadowLayer(3, 0, 0, Color.BLACK);
        for (Markers.Marker m : ms) {
            Path p = new Path();
            p.moveTo((float) m.corners[0][0], (float) m.corners[0][1]);
            for (int k = 1; k < 4; k++) p.lineTo((float) m.corners[k][0], (float) m.corners[k][1]);
            p.close();
            c.drawPath(p, line);
            c.drawCircle((float) m.corners[0][0], (float) m.corners[0][1], line.getStrokeWidth() * 2, dot);
            double cx = 0, cy = 0;
            for (double[] k : m.corners) { cx += k[0] / 4; cy += k[1] / 4; }
            c.drawText(String.valueOf(m.id), (float) cx, (float) cy, text);
        }
        try (FileOutputStream fo = new FileOutputStream(f)) {
            return bmp.compress(Bitmap.CompressFormat.JPEG, 85, fo);
        } catch (IOException e) {
            return false;
        } finally {
            bmp.recycle();
        }
    }

    // ============================================================ resultado

    private void showResult(Stats st) {
        NavajaUi.Screen scr = NavajaUi.screen(this, "Diagnóstico de la hoja", "Detección de marcas cuadro a cuadro", NavajaUi.headerAction(this, "Otro", v -> showIntro()), true);
        LinearLayout c = scr.content;
        String report = report(st);
        c.addView(NavajaUi.text(this, summary(st), NavajaUi.Style.BODY));
        for (int i = 0; i < st.shots.size(); i++) {
            ImageView iv = new ImageView(this);
            iv.setAdjustViewBounds(true);
            iv.setImageBitmap(android.graphics.BitmapFactory.decodeFile(st.shots.get(i).getAbsolutePath()));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = NavajaUi.dp(this, 10);
            c.addView(iv, lp);
            c.addView(NavajaUi.text(this, st.shotInfo.get(i), NavajaUi.Style.MUTED));
        }
        c.addView(NavajaUi.buttonRow(this, NavajaUi.primary(this, "Guardar informe (ZIP)", v -> save(st, report))));
        setContentView(scr.root);
    }

    private static String summary(Stats st) {
        int ok4 = 0, sum = 0;
        for (int n : st.perFrame) { if (n >= 4) ok4++; sum += n; }
        double[] s = st.sides.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return String.format(Locale.getDefault(), "%d cuadros (%.0f s, %d×%d px) · con 4 marcas o más: %.0f %% · marcas por cuadro: %.1f · "
                        + "lado de una marca: %s px · %.0f ms por cuadro",
                st.frames, st.seconds, st.w, st.h, st.frames > 0 ? 100.0 * ok4 / st.frames : 0, st.frames > 0 ? (double) sum / st.frames : 0,
                s.length > 0 ? String.format(Locale.getDefault(), "%.0f (de %.0f a %.0f)", s[s.length / 2], s[s.length / 20], s[s.length - 1 - s.length / 20]) : "—",
                st.frames > 0 ? st.detectNs / 1e6 / st.frames : 0);
    }

    private static String report(Stats st) {
        StringBuilder r = new StringBuilder("# Hoja con marcas · prueba del detector (etapa 1)\n\n");
        r.append("Generado por Navaja el ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date())).append(".\n\n");
        r.append("- ").append(summary(st)).append("\n");
        r.append("- Una marca necesita unos 30 px de lado para leerse bien (celdas de 5 px); bajo eso, acércate o graba con más resolución.\n\n");
        r.append("## Marcas por cuadro\n\n| Marcas | Cuadros |\n|---|---|\n");
        int[] hist = new int[Sheet.count() + 1];
        for (int n : st.perFrame) hist[n]++;
        for (int n = 0; n < hist.length; n++) if (hist[n] > 0) r.append("| ").append(n).append(" | ").append(hist[n]).append(" |\n");
        r.append("\n## Cada marca\n\nEn cuántos cuadros se detectó (IDs en el sentido del reloj desde la esquina de arriba a la izquierda: arriba 0–4, "
                + "derecha 5–8, abajo 9–13, izquierda 14–17). Una que nunca aparece y se vio en el video: revisa la impresión.\n\n| ID | Cuadros | % |\n|---|---|---|\n");
        for (int id = 0; id < Sheet.count(); id++)
            r.append(String.format(Locale.ROOT, "| %d | %d | %.0f %% |\n", id, st.seen[id], st.frames > 0 ? 100.0 * st.seen[id] / st.frames : 0));
        r.append("\n## Cuadro a cuadro\n\n");
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < st.perFrame.size(); i++) line.append(i % 30 == 0 ? (i > 0 ? "\n" : "") : " ").append(st.perFrame.get(i));
        r.append("```\n").append(line).append("\n```\n\n`fotos/`: cuadros con las marcas detectadas (verde; punto rojo = esquina de arriba a la izquierda de la marca).\n");
        return r.toString();
    }

    /** ZIP con el informe y los cuadros, en la carpeta de guardado del escáner (o compartido si no hay carpeta). */
    private void save(Stats st, String report) {
        String name = "hoja_prueba_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date()) + ".zip";
        work.execute(() -> {
            try {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try (ZipOutputStream zip = new ZipOutputStream(bo)) {
                    zip.putNextEntry(new ZipEntry("informe.md"));
                    zip.write(report.getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                    for (File f : st.shots) {
                        zip.putNextEntry(new ZipEntry("fotos/" + f.getName()));
                        try (InputStream in = new FileInputStream(f)) {
                            byte[] buf = new byte[1 << 16];
                            for (int n; (n = in.read(buf)) > 0; ) zip.write(buf, 0, n);
                        }
                        zip.closeEntry();
                    }
                }
                Uri tree = folder();
                if (tree != null) {
                    Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
                    Uri doc = DocumentsContract.createDocument(getContentResolver(), parent, "application/zip", name);
                    if (doc == null) throw new IOException("no se pudo crear el archivo");
                    try (OutputStream os = getContentResolver().openOutputStream(doc)) {
                        if (os == null) throw new IOException("no se pudo abrir el archivo");
                        os.write(bo.toByteArray());
                    }
                    ui.post(() -> toast("Guardado: " + name + " (en la carpeta del escáner desde video)"));
                } else {
                    File f = new File(getCacheDir(), name);
                    try (FileOutputStream fo = new FileOutputStream(f)) {
                        fo.write(bo.toByteArray());
                    }
                    ui.post(() -> {
                        Uri u = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
                        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, u)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Compartir informe"));
                    });
                }
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "guardar", e);
                ui.post(() -> toast("No se pudo guardar: " + e.getMessage()));
            }
        });
    }

    /** La carpeta de guardado del escáner desde video, si todavía hay permiso para escribir en ella. */
    private Uri folder() {
        SharedPreferences prefs = getSharedPreferences("medir", MODE_PRIVATE);
        String t = prefs.getString("carpeta", null);
        if (t == null) return null;
        Uri u = Uri.parse(t);
        for (android.content.UriPermission p : getContentResolver().getPersistedUriPermissions())
            if (p.getUri().equals(u) && p.isWritePermission()) return u;
        return null;
    }

    private void post(String s) { ui.post(() -> { if (progress != null) progress.setText(s); }); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
