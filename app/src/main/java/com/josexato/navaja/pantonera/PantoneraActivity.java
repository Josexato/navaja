package com.josexato.navaja.pantonera;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.josexato.navaja.pantonera.core.ColorMath;
import com.josexato.navaja.pantonera.core.ColorNames;
import com.josexato.navaja.pantonera.core.Exporters;
import com.josexato.navaja.pantonera.core.Json;
import com.josexato.navaja.pantonera.core.Matcher;
import com.josexato.navaja.pantonera.core.MultiShot;
import com.josexato.navaja.pantonera.core.SampleAnalyzer;
import com.josexato.navaja.pantonera.core.Stjf;
import com.josexato.navaja.ui.NavajaUi;
import com.josexato.navaja.ui.Palette;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pantonera: mide el color (y la composición, si es un terrazzo o similar) de
 * una muestra fotografiada sobre una hoja blanca, la compara con "mi
 * pantonera" y la guarda/exporta en STJF, glTF o paleta GPL. Todo ocurre en
 * el teléfono.
 */
public class PantoneraActivity extends Activity {

    private static final String TAG = "Pantonera";
    private static final int REQ_CAM_PERM = 1, REQ_PHOTO = 10, REQ_GALLERY = 11, REQ_IMPORT = 12, REQ_CATALOG = 13;
    private static final int MAX_SIDE = 1800;
    private static final String[] FINISH_LABELS = {"Mate", "Satinado", "Brillante", "Pulido"};

    /** Una foto analizada. */
    private static final class Shot {
        SampleAnalyzer.Result res;
        Bitmap overlay, texture;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final List<Shot> shots = new ArrayList<>();
    private Library lib;
    private File pendingPhoto;
    private MultiShot.Combined combined;

    private TextView statusView;
    private Button libBtn;
    private LinearLayout shotsRow, resultBox, compareBox;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        lib = new Library(this);
        String problems = lib.load();
        if (b != null && b.getString("pending") != null) pendingPhoto = new File(b.getString("pending"));
        buildUi();
        if (!problems.isEmpty()) info("Problemas en tu pantonera", problems);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (pendingPhoto != null) out.putString("pending", pendingPhoto.getAbsolutePath());
    }

    @Override
    protected void onDestroy() {
        exec.shutdownNow();
        super.onDestroy();
    }

    // ==================================================================== UI

    private void buildUi() {
        libBtn = NavajaUi.headerAction(this, "", v -> showLibrary());
        NavajaUi.Screen sc = NavajaUi.screen(this, "Pantonera", "color · composición · STJF", libBtn);
        LinearLayout root = sc.content;
        TextView help = text("Pon la muestra en el centro de una hoja blanca (A4, Carta u Oficio; mejor papel sin "
                + "«extra blanco»). Si la muestra es blanca o muy clara, ponla AL LADO de la hoja sobre una "
                + "superficie oscura. Toma la foto desde arriba, con la hoja completa y sin sombras ni reflejos. "
                + "Si puedes, saca una foto con cada luz (día, foco, ventana): se combinan.", 13, Palette.TEXT_2);
        help.setPadding(0, dp(6), 0, dp(10));
        root.addView(help);

        root.addView(NavajaUi.buttonRow(this, NavajaUi.primary(this, "📷 Foto", v -> takePhoto()),
                NavajaUi.secondary(this, "🖼 Galería", v -> pickImageMenu())));
        updateLibButton();

        statusView = text("", 13, Palette.TEXT_3);
        statusView.setPadding(0, dp(8), 0, dp(4));
        root.addView(statusView);

        shotsRow = new LinearLayout(this);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.addView(shotsRow);
        root.addView(hs);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        resultBox = new LinearLayout(this);
        resultBox.setOrientation(LinearLayout.VERTICAL);
        compareBox = new LinearLayout(this);
        compareBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(resultBox);
        content.addView(compareBox);
        ScrollView sv = new ScrollView(this);
        sv.addView(content);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(sc.root);
        renderResult();
    }

    private void updateLibButton() {
        libBtn.setText("📚 " + lib.doc.samples.size());
    }

    // ============================================================== captura

    private void takePhoto() {
        // Si la app declara CAMERA, Android exige el permiso también para usar la app de cámara.
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM_PERM);
            return;
        }
        File dir = new File(lib.dir, "fotos");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        pendingPhoto = new File(dir, "foto_" + stamp() + ".jpg");
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", pendingPhoto);
            Intent i = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_PHOTO);
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            toast("No se pudo abrir la cámara: " + e.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_CAM_PERM) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) takePhoto();
            else toast("Sin permiso de cámara. Puedes elegir una foto de la galería.");
        }
    }

    private void pickImageMenu() {
        String[] items = {"Foto con la hoja de referencia", "Imagen de catálogo (sin hoja: la imagen entera es la muestra)"};
        new AlertDialog.Builder(this)
                .setTitle("¿Qué imagen es?")
                .setItems(items, (d, which) -> pickImage(which == 0 ? REQ_GALLERY : REQ_CATALOG))
                .show();
    }

    private void pickImage(int req) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(i, req);
        } catch (ActivityNotFoundException e) {
            toast("No hay galería disponible");
        }
    }

    /** Imagen de catálogo: pide el ancho real (opcional) para medir el tamaño de las piedras. */
    private void askCatalogWidth(Uri uri) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(text("Si sabes cuánto mide de ancho lo que muestra la imagen (p. ej. 600 para una baldosa "
                + "de 60 cm), escríbelo para medir el tamaño de las piedras. Si no, déjalo vacío.\n\n"
                + "El color se toma tal cual viene en la imagen (sin hoja no se puede corregir la luz).",
                13, Palette.TEXT_2));
        EditText mm = edit("Ancho en mm (opcional)");
        mm.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(mm);
        new AlertDialog.Builder(this)
                .setTitle("Imagen de catálogo")
                .setView(box)
                .setPositiveButton("Medir", (d, w) -> {
                    double width = Double.NaN;
                    try {
                        width = Double.parseDouble(mm.getText().toString().trim().replace(',', '.'));
                    } catch (NumberFormatException ignored) {
                        // Sin ancho: se mide color y composición, sin tamaños.
                    }
                    analyze(uri, width > 0 ? width : Double.NaN, true);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result != RESULT_OK) return;
        if (req == REQ_PHOTO && pendingPhoto != null && pendingPhoto.length() > 0) {
            analyze(Uri.fromFile(pendingPhoto));
        } else if (req == REQ_GALLERY && data != null && data.getData() != null) {
            analyze(data.getData());
        } else if (req == REQ_CATALOG && data != null && data.getData() != null) {
            askCatalogWidth(data.getData());
        } else if (req == REQ_IMPORT && data != null && data.getData() != null) {
            importStjf(data.getData());
        }
    }

    // ============================================================== análisis

    private void analyze(Uri uri) {
        analyze(uri, Double.NaN, false);
    }

    /** {@code catalog}: la imagen entera es la muestra (sin hoja); {@code widthMm} su ancho real o NaN. */
    private void analyze(Uri uri, double widthMm, boolean catalog) {
        setStatus("Analizando la foto…");
        exec.submit(() -> {
            try {
                Bitmap bmp = loadBitmap(uri);
                int w = bmp.getWidth(), h = bmp.getHeight();
                int[] px = new int[w * h];
                bmp.getPixels(px, 0, w, 0, 0, w, h);
                bmp.recycle();
                long t0 = System.currentTimeMillis();
                SampleAnalyzer.Result r = catalog ? new SampleAnalyzer().analyzeImage(px, w, h, widthMm)
                        : new SampleAnalyzer().analyze(px, w, h);
                long ms = System.currentTimeMillis() - t0;
                Shot s = new Shot();
                s.res = r;
                Bitmap ov = Bitmap.createBitmap(SampleAnalyzer.overlay(px, r), w, h, Bitmap.Config.ARGB_8888);
                float k = 900f / Math.max(w, h);
                s.overlay = Bitmap.createScaledBitmap(ov, Math.round(w * k), Math.round(h * k), true);
                ov.recycle();
                if (r.texture != null) s.texture = Bitmap.createBitmap(r.texture, r.texW, r.texH, Bitmap.Config.ARGB_8888);
                log(uri, w, h, ms, r);
                ui.post(() -> onAnalyzed(s));
            } catch (Throwable e) {
                Log.e(TAG, "Fallo al analizar", e);
                ui.post(() -> {
                    setStatus("");
                    info("No se pudo analizar", String.valueOf(e.getMessage()));
                });
            }
        });
    }

    private void onAnalyzed(Shot s) {
        if (!s.res.ok()) {
            setStatus("");
            showShot(s, false);
            return;
        }
        shots.add(s);
        setStatus(shots.size() == 1 ? "Listo. Puedes sumar fotos con otras luces para afinar."
                : "Listo: " + shots.size() + " fotos combinadas.");
        recompute();
    }

    private void recompute() {
        List<Stjf.Sample> list = new ArrayList<>();
        for (Shot s : shots) list.add(s.res.sample);
        combined = list.isEmpty() ? null : MultiShot.combine(list);
        renderShots();
        renderResult();
        compareBox.removeAllViews();
    }

    private Bitmap loadBitmap(Uri uri) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        if (o.outWidth <= 0) throw new IOException("El archivo no es una imagen");
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        Bitmap bmp;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            bmp = BitmapFactory.decodeStream(in, null, d);
        }
        if (bmp == null) throw new IOException("No se pudo leer la imagen");
        float k = (float) MAX_SIDE / Math.max(bmp.getWidth(), bmp.getHeight());
        Matrix m = new Matrix();
        if (k < 1) m.postScale(k, k);
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            int or = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (or == ExifInterface.ORIENTATION_ROTATE_90) m.postRotate(90);
            else if (or == ExifInterface.ORIENTATION_ROTATE_180) m.postRotate(180);
            else if (or == ExifInterface.ORIENTATION_ROTATE_270) m.postRotate(270);
        } catch (Exception ignored) {
            // Sin EXIF: la orientación no afecta la medición.
        }
        if (m.isIdentity()) return bmp.copy(Bitmap.Config.ARGB_8888, false);
        Bitmap out = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        if (out != bmp) bmp.recycle();
        return out;
    }

    /** Diagnóstico en pantonera/log.txt (útil para revisar una medición rara). */
    private void log(Uri uri, int w, int h, long ms, SampleAnalyzer.Result r) {
        try (FileWriter fw = new FileWriter(new File(lib.dir, "log.txt"), true)) {
            StringBuilder sb = new StringBuilder();
            sb.append("=== ").append(new Date()).append(" · ").append(uri.getLastPathSegment()).append('\n');
            sb.append(String.format(Locale.US, "imagen %dx%d · %d ms · papel %s · mm/px %.4f%n", w, h, ms, r.paper, r.mmPerPx));
            if (r.reference != null) sb.append(String.format(Locale.US, "blanco medido (lineal) %.3f %.3f %.3f · reflejos %.1f%%%n",
                    r.reference[0], r.reference[1], r.reference[2], 100 * r.specularFraction));
            if (r.error != null) sb.append("ERROR ").append(r.error).append('\n');
            for (String wn : r.warnings) sb.append("aviso: ").append(wn).append('\n');
            if (r.sample != null) sb.append(Json.write(Stjf.sampleToMap(r.sample)));
            fw.write(sb.toString());
        } catch (IOException e) {
            Log.w(TAG, "log.txt", e);
        }
    }

    // ============================================================= resultado

    private void renderShots() {
        shotsRow.removeAllViews();
        for (Shot s : shots) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(s.overlay);
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(96), dp(72));
            lp.rightMargin = dp(8);
            iv.setLayoutParams(lp);
            iv.setOnClickListener(v -> showShot(s, true));
            shotsRow.addView(iv);
        }
    }

    private void renderResult() {
        resultBox.removeAllViews();
        if (combined == null || combined.sample == null) {
            resultBox.addView(text("\nAún no hay mediciones. Toma una foto de la muestra sobre la hoja blanca.\n\n"
                    + "En la foto de diagnóstico verás la hoja en celeste, el borde descartado en rosa y cada "
                    + "grupo de color de la muestra pintado con su color medido.", 13, Palette.TEXT_3));
            return;
        }
        Stjf.Sample s = combined.sample;
        TextView h = text(s.isComposite() ? "Muestra compuesta (" + (s.components.size() + 1) + " colores)" : "Color medido",
                16, Palette.TEXT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setPadding(0, dp(12), 0, dp(6));
        resultBox.addView(h);
        double[] bl = s.baseLab();
        if (bl != null) {
            resultBox.addView(NavajaUi.metricRow(this,
                    NavajaUi.metric(this, s.isComposite() ? "Fondo L*" : "L*").set(String.format(Locale.US, "%.1f", bl[0]), null, 0),
                    NavajaUi.metric(this, "a*").set(String.format(Locale.US, "%.1f", bl[1]), null, 0),
                    NavajaUi.metric(this, "b*").set(String.format(Locale.US, "%.1f", bl[2]), null, 0)));
        }
        resultBox.addView(partsView(s));

        Set<String> warns = new LinkedHashSet<>();
        for (Shot sh : shots) warns.addAll(sh.res.warnings);
        warns.addAll(combined.warnings);
        for (String w : warns) {
            TextView t = text("⚠ " + w, 12, Palette.WARN);
            t.setPadding(0, dp(4), 0, 0);
            resultBox.addView(t);
        }
        if (shots.size() > 1) {
            resultBox.addView(text(String.format(Locale.US, "Variación entre fotos: ΔE %.1f (%s)",
                    combined.spreadDe, ColorMath.verdict(combined.spreadDe)), 12, Palette.TEXT_2));
        }

        LinearLayout row = NavajaUi.buttonRow(this,
                NavajaUi.primary(this, "🔎 Comparar", v -> compare()),
                NavajaUi.secondary(this, "💾 Guardar", v -> saveDialog()),
                NavajaUi.secondary(this, "🗑 Nueva", v -> {
                    shots.clear();
                    recompute();
                    setStatus("");
                }));
        NavajaUi.topMargin(row, 10);
        resultBox.addView(row);
    }

    /** Fila de muestras de color: matriz + componentes con %, tamaño y Lab. */
    private View partsView(Stjf.Sample s) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        List<Stjf.Part> parts = s.signature();
        for (Stjf.Part p : parts) {
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, dp(4), 0, dp(4));
            r.addView(swatch(p.lab, p.isBase ? 56 : 40));
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setPadding(dp(12), 0, 0, 0);
            String name = p.isBase && !s.isComposite() ? ColorNames.describe(p.lab)
                    : (p.isBase ? "Fondo · " + ColorNames.describe(p.lab) : p.name);
            TextView n = text(name, 14, Palette.TEXT);
            n.setTypeface(Typeface.DEFAULT_BOLD);
            info.addView(n);
            StringBuilder d = new StringBuilder(labText(p.lab));
            if (s.isComposite()) d.append(String.format(Locale.US, "  ·  %.0f%% del área", 100 * p.fraction));
            if (p.size != null && p.size.p50 != null) {
                d.append(String.format(Locale.US, "\nPiedras: típica %.1f mm", p.size.p50));
                if (p.size.p10 != null && p.size.p90 != null)
                    d.append(String.format(Locale.US, " (de %.1f a %.1f mm)", p.size.p10, p.size.p90));
            }
            for (Stjf.Component c : s.components) {
                if (!p.isBase && c.name().equals(p.name) && c.shape != null) d.append(" · ").append(shapeEs(c.shape));
            }
            info.addView(text(d.toString(), 12, Palette.TEXT_2));
            r.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
            col.addView(r);
        }
        return col;
    }

    private static String labText(double[] lab) {
        String g = ColorMath.outOfGamut(lab) ? " (fuera de sRGB)" : "";
        return String.format(Locale.US, "L*%.1f a*%.1f b*%.1f · %s%s", lab[0], lab[1], lab[2], ColorMath.labToHex(lab), g);
    }

    private static String shapeEs(String shape) {
        switch (shape) {
            case "angular": return "angulosas";
            case "vein": return "vetas";
            case "flake": return "escamas";
            default: return "redondeadas";
        }
    }

    // ============================================================ comparar

    private void compare() {
        compareBox.removeAllViews();
        if (combined == null) return;
        if (lib.doc.samples.isEmpty()) {
            info("Tu pantonera está vacía", "Guarda muestras medidas o importa un catálogo .stjf "
                    + "(📚 Mi pantonera → Importar) para poder comparar.");
            return;
        }
        List<Matcher.Score> rank = Matcher.rank(combined.sample, lib.doc.samples);
        TextView h = text("Más parecidas en tu pantonera", 16, Palette.TEXT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setPadding(0, dp(16), 0, dp(4));
        compareBox.addView(h);
        if (Matcher.nearTie(rank)) {
            compareBox.addView(text("Las dos primeras son prácticamente el mismo color: elige por marca, precio o "
                    + "disponibilidad; el nombre cambia según el fabricante.", 12, Palette.GOOD));
        }
        for (int i = 0; i < Math.min(6, rank.size()); i++) compareBox.addView(scoreRow(rank.get(i)));
    }

    private View scoreRow(Matcher.Score sc) {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        card.setLayoutParams(lp);
        card.setBackground(NavajaUi.cardBackground(this));
        card.addView(swatch(combined.sample.baseLab(), 36));
        View gap = new View(this);
        card.addView(gap, new LinearLayout.LayoutParams(dp(4), 1));
        double[] cl = sc.candidate.baseLab();
        card.addView(swatch(cl == null ? new double[]{50, 0, 0} : cl, 36));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(12), 0, 0, 0);
        TextView n = text(sc.candidate.name(), 14, Palette.TEXT);
        n.setTypeface(Typeface.DEFAULT_BOLD);
        info.addView(n);
        StringBuilder sub = new StringBuilder();
        if (sc.candidate.brand != null) sub.append(sc.candidate.brand);
        if (sc.candidate.code != null) sub.append(sub.length() > 0 ? " · " : "").append(sc.candidate.code);
        if (sub.length() > 0) info.addView(text(sub.toString(), 12, Palette.TEXT_2));
        info.addView(text(String.format(Locale.US, "ΔE %.1f · %s", sc.total, sc.verdict()), 13, verdictColor(sc.total)));
        if (combined.sample.isComposite() || sc.candidate.isComposite()) {
            StringBuilder d = new StringBuilder(String.format(Locale.US, "Fondo ΔE %.1f · Mezcla %.1f", sc.baseDe, sc.mixDe));
            if (!Double.isNaN(sc.sizeRatio)) d.append(String.format(Locale.US, " · Piedras ×%.2f", sc.sizeRatio));
            d.append(String.format(Locale.US, " · %% piedras ±%.0f", 100 * sc.fractionDiff));
            info.addView(text(d.toString(), 11, Palette.TEXT_3));
        }
        card.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
        card.setOnClickListener(v -> showSample(sc.candidate));
        return card;
    }

    private static int verdictColor(double de) {
        if (de < 2) return Palette.OK;
        if (de < 3.5) return Palette.WARN;
        return Palette.BAD;
    }

    // ============================================================== guardar

    private void saveDialog() {
        if (combined == null || combined.sample == null) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        EditText name = edit("Nombre (p. ej. Terrazzo cocina)");
        EditText brand = edit("Marca (opcional)");
        EditText code = edit("Código (opcional)");
        final int[] finishSel = {0};
        LinearLayout finish = new LinearLayout(this);
        TextView[] chips = new TextView[FINISH_LABELS.length];
        for (int i = 0; i < FINISH_LABELS.length; i++) {
            final int idx = i;
            chips[i] = NavajaUi.chip(this, FINISH_LABELS[i], i == 0, v -> {
                finishSel[0] = idx;
                for (int k = 0; k < chips.length; k++) NavajaUi.setChip(chips[k], k == idx);
            });
            finish.addView(chips[i]);
        }
        box.addView(name);
        box.addView(brand);
        box.addView(code);
        TextView fl = text("Acabado", 13, Palette.TEXT_2);
        fl.setPadding(0, dp(8), 0, 0);
        box.addView(fl);
        HorizontalScrollView fs = new HorizontalScrollView(this);
        fs.addView(finish);
        box.addView(fs);
        new AlertDialog.Builder(this)
                .setTitle("Guardar en mi pantonera")
                .setView(box)
                .setPositiveButton("Guardar", (d, w) -> save(name.getText().toString().trim(),
                        brand.getText().toString().trim(), code.getText().toString().trim(),
                        Stjf.FINISHES[finishSel[0]]))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void save(String name, String brand, String code, String finish) {
        // Copia: la medición en pantalla no debe quedar enlazada con la guardada.
        Stjf.Sample s = Stjf.parse("{\"samples\":[" + Json.writeCompact(Stjf.sampleToMap(combined.sample)) + "]}")
                .doc.samples.get(0);
        if (name.isEmpty()) name = ColorNames.describe(s.baseLab());
        s.id = lib.uniqueId(name);
        s.label = name;
        s.brand = brand.isEmpty() ? null : brand;
        s.code = code.isEmpty() ? null : code;
        s.finish = finish;
        if (s.measurement == null) s.measurement = new Stjf.Measurement();
        s.measurement.date = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).format(new Date());

        // Textura de la foto con menos reflejos.
        Shot best = null;
        for (Shot sh : shots) {
            if (sh.texture == null) continue;
            if (best == null || sh.res.specularFraction < best.res.specularFraction) best = sh;
        }
        if (s.appearance == null) s.appearance = new Stjf.Appearance();
        if (best != null) {
            File f = new File(lib.dir, s.id + ".jpg");
            try (OutputStream out = new FileOutputStream(f)) {
                best.texture.compress(Bitmap.CompressFormat.JPEG, 92, out);
                s.appearance.texture = f.getName();
                s.appearance.mmPerPx = Double.isNaN(best.res.mmPerPx) ? null : best.res.mmPerPx;
            } catch (IOException e) {
                Log.w(TAG, "textura", e);
            }
        }
        lib.put(s);
        try {
            lib.save();
            toast("Guardada como «" + s.label + "»");
        } catch (IOException e) {
            info("No se pudo guardar", String.valueOf(e.getMessage()));
        }
        updateLibButton();
        // Que la próxima medición no pise esta muestra.
        recompute();
    }

    // ============================================================ biblioteca

    private void showLibrary() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        final AlertDialog[] dlg = new AlertDialog[1];
        if (lib.doc.samples.isEmpty()) {
            box.addView(text("Todavía no hay muestras. Guarda una medición o importa un catálogo .stjf "
                    + "(por ejemplo, la carta de colores de una marca escrita a mano).", 13, Palette.TEXT_2));
        }
        for (Stjf.Sample s : lib.doc.samples) {
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, dp(6), 0, dp(6));
            double[] l = s.baseLab();
            r.addView(swatch(l == null ? new double[]{50, 0, 0} : l, 32));
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setPadding(dp(10), 0, 0, 0);
            TextView n = text(s.name(), 14, Palette.TEXT);
            n.setTypeface(Typeface.DEFAULT_BOLD);
            info.addView(n);
            String sub = (s.brand != null ? s.brand + " · " : "") + (s.code != null ? s.code + " · " : "")
                    + (s.isComposite() ? (s.components.size() + 1) + " colores" : (l == null ? "?" : ColorMath.labToHex(l)));
            info.addView(text(sub, 12, Palette.TEXT_2));
            r.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
            r.setOnClickListener(v -> {
                dlg[0].dismiss();
                sampleMenu(s);
            });
            box.addView(r);
        }
        dlg[0] = new AlertDialog.Builder(this)
                .setTitle("Mi pantonera (" + lib.doc.samples.size() + ")")
                .setView(wrapScroll(box))
                .setPositiveButton("Cerrar", null)
                .setNeutralButton("Importar .stjf", (d, w) -> {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    startActivityForResult(i, REQ_IMPORT);
                })
                .setNegativeButton("Exportar", (d, w) -> exportAllMenu())
                .create();
        dlg[0].show();
    }

    private void sampleMenu(Stjf.Sample s) {
        String[] items = {"Ver detalle", "Exportar 3D (glTF .glb)", "Exportar STJF", "Borrar"};
        new AlertDialog.Builder(this)
                .setTitle(s.name())
                .setItems(items, (d, which) -> {
                    List<Stjf.Sample> one = new ArrayList<>();
                    one.add(s);
                    if (which == 0) showSample(s);
                    else if (which == 1) exportGlb(one, s.id);
                    else if (which == 2) exportStjf(one, s.id);
                    else confirmDelete(s);
                })
                .show();
    }

    private void confirmDelete(Stjf.Sample s) {
        new AlertDialog.Builder(this)
                .setMessage("¿Borrar «" + s.name() + "» de tu pantonera?")
                .setPositiveButton("Borrar", (d, w) -> {
                    lib.remove(s);
                    try {
                        lib.save();
                    } catch (IOException e) {
                        toast("No se pudo guardar: " + e.getMessage());
                    }
                    updateLibButton();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void exportAllMenu() {
        if (lib.doc.samples.isEmpty()) {
            toast("No hay muestras para exportar");
            return;
        }
        String[] items = {"STJF (.stjf) — todo, para respaldar o compartir",
                "3D glTF (.glb) — Blender, visores 3D, web",
                "Paleta GPL (.gpl) — GIMP, Inkscape, Krita"};
        new AlertDialog.Builder(this)
                .setTitle("Exportar mi pantonera")
                .setItems(items, (d, which) -> {
                    if (which == 0) exportStjf(lib.doc.samples, "pantonera");
                    else if (which == 1) exportGlb(lib.doc.samples, "pantonera");
                    else exportGpl();
                })
                .show();
    }

    private void exportStjf(List<Stjf.Sample> list, String name) {
        Stjf doc = new Stjf();
        doc.samples.addAll(list);
        share(writeExport(name + ".stjf", doc.write().getBytes(StandardCharsets.UTF_8)), "application/json");
    }

    private void exportGpl() {
        String gpl = Exporters.gpl("Mi pantonera", lib.doc.samples);
        share(writeExport("pantonera.gpl", gpl.getBytes(StandardCharsets.UTF_8)), "text/plain");
    }

    private void exportGlb(List<Stjf.Sample> list, String name) {
        setStatus("Generando glTF…");
        List<Stjf.Sample> copy = new ArrayList<>(list);
        exec.submit(() -> {
            try {
                List<Exporters.Entry> entries = new ArrayList<>();
                for (Stjf.Sample s : copy) {
                    Exporters.Entry e = new Exporters.Entry(s);
                    File tex = lib.texture(s);
                    if (tex != null) {
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inJustDecodeBounds = true;
                        BitmapFactory.decodeFile(tex.getAbsolutePath(), o);
                        double mm = s.appearance.mmPerPx != null ? s.appearance.mmPerPx : 0;
                        e.image(readBytes(tex), "image/jpeg", o.outWidth, o.outHeight, mm);
                    }
                    entries.add(e);
                }
                File f = writeExport(name + ".glb", Exporters.glb(entries));
                ui.post(() -> {
                    setStatus("");
                    share(f, "model/gltf-binary");
                });
            } catch (Throwable t) {
                Log.e(TAG, "glb", t);
                ui.post(() -> {
                    setStatus("");
                    info("No se pudo exportar", String.valueOf(t.getMessage()));
                });
            }
        });
    }

    private File writeExport(String name, byte[] data) {
        File dir = new File(lib.dir, "export");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, name);
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(data);
        } catch (IOException e) {
            toast("No se pudo escribir " + name + ": " + e.getMessage());
            return null;
        }
        return f;
    }

    private void share(File f, String mime) {
        if (f == null) return;
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(mime);
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Compartir " + f.getName()));
        } catch (Exception e) {
            info("Archivo guardado", "Quedó en " + f.getAbsolutePath());
        }
    }

    private void importStjf(Uri uri) {
        String text;
        try {
            text = Library.readText(getContentResolver().openInputStream(uri));
        } catch (IOException | SecurityException e) {
            info("No se pudo abrir", String.valueOf(e.getMessage()));
            return;
        }
        Stjf.Result r = Stjf.parse(text);
        if (!r.ok()) {
            info("El archivo tiene errores", "No se importó nada. Corrige esto y vuelve a intentarlo:\n\n" + r.report());
            return;
        }
        int added = 0, replaced = 0;
        for (Stjf.Sample s : r.doc.samples) {
            if (lib.doc.find(s.id) != null) replaced++;
            else added++;
            lib.put(s);
        }
        try {
            lib.save();
        } catch (IOException e) {
            toast("No se pudo guardar: " + e.getMessage());
        }
        updateLibButton();
        String msg = added + " muestras nuevas" + (replaced > 0 ? ", " + replaced + " reemplazadas (mismo id)" : "") + ".";
        if (!r.issues.isEmpty()) msg += "\n\nAvisos:\n" + r.report();
        info("Importado", msg);
    }

    // ============================================================== detalles

    private void showShot(Shot s, boolean canRemove) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(8));
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(s.overlay);
        iv.setAdjustViewBounds(true);
        box.addView(iv, new LinearLayout.LayoutParams(-1, -2));
        SampleAnalyzer.Result r = s.res;
        StringBuilder sb = new StringBuilder();
        if (r.error != null) sb.append("❌ ").append(r.error).append("\n\n");
        if (r.paper != null) sb.append("Hoja: ").append(r.paper);
        if (!Double.isNaN(r.mmPerPx)) sb.append(String.format(Locale.US, " · %.3f mm por píxel", r.mmPerPx));
        sb.append('\n');
        if (r.reference != null) sb.append(String.format(Locale.US, "Blanco medido: R %.2f G %.2f B %.2f (lineal)\n",
                r.reference[0], r.reference[1], r.reference[2]));
        for (String w : r.warnings) sb.append("⚠ ").append(w).append('\n');
        sb.append("\nCeleste = hoja · rosa = borde/reflejos descartados · colores = grupos medidos · amarillo = contorno de la hoja.");
        TextView t = text(sb.toString().trim(), 12, Palette.TEXT);
        t.setPadding(0, dp(10), 0, 0);
        box.addView(t);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(r.ok() ? "Foto analizada" : "No se pudo medir")
                .setView(wrapScroll(box))
                .setPositiveButton("Cerrar", null);
        if (canRemove) b.setNegativeButton("Quitar foto", (d, w) -> {
            shots.remove(s);
            recompute();
        });
        b.show();
    }

    private void showSample(Stjf.Sample s) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(8));
        File tex = lib.texture(s);
        if (tex != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(BitmapFactory.decodeFile(tex.getAbsolutePath()));
            iv.setAdjustViewBounds(true);
            iv.setMaxHeight(dp(220));
            box.addView(iv, new LinearLayout.LayoutParams(-1, -2));
        }
        LinearLayout dark = new LinearLayout(this);
        dark.setOrientation(LinearLayout.VERTICAL);
        dark.setBackgroundColor(Palette.BG);
        dark.setPadding(dp(10), dp(6), dp(10), dp(6));
        dark.addView(partsView(s));
        box.addView(dark);
        TextView json = text(Json.write(Stjf.sampleToMap(s)).trim(), 11, Palette.TEXT);
        json.setTypeface(Typeface.MONOSPACE);
        json.setTextIsSelectable(true);
        json.setPadding(0, dp(10), 0, 0);
        box.addView(json);
        new AlertDialog.Builder(this)
                .setTitle(s.name())
                .setView(wrapScroll(box))
                .setPositiveButton("Cerrar", null)
                .show();
    }

    // ============================================================ utilidades

    private static byte[] readBytes(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int off = 0;
            for (int n; off < data.length && (n = in.read(data, off, data.length - off)) > 0; ) off += n;
            return data;
        }
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    private View swatch(double[] lab, int sizeDp) {
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setColor(ColorMath.labToArgb(lab));
        g.setCornerRadius(dp(8));
        g.setStroke(1, 0x55FFFFFF);
        v.setBackground(g);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    private void setStatus(String s) { statusView.setText(s); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private void info(String title, String msg) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private EditText edit(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        return e;
    }

    private Button button(String s, View.OnClickListener l) {
        return NavajaUi.secondary(this, s, l);
    }

    private ScrollView wrapScroll(View v) {
        ScrollView s = new ScrollView(this);
        s.addView(v, new ViewGroup.LayoutParams(-1, -2));
        return s;
    }
}
