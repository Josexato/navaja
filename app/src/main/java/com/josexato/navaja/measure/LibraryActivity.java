package com.josexato.navaja.measure;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.josexato.navaja.measure.core.ScanIndex;
import com.josexato.navaja.ui.NavajaUi;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * «Mis escaneos»: la lista de lo que hay en la carpeta de guardado, sin
 * tener que abrir el explorador de archivos. Por cada escaneo muestra
 * cuándo se guardó, con qué modo y versión de Navaja, las medidas, si tiene
 * video, sensores y depuración, y deja ver el informe o compartir el ZIP.
 *
 * <p>Los datos salen de la ficha {@code medida_….json} que se guarda junto
 * al ZIP; para los escaneos anteriores a las fichas, del informe.md que
 * está al principio del ZIP. También lista los videos sueltos y los
 * escaneos del modo 3 (base giratoria), que viven en la carpeta de la app.
 */
public class LibraryActivity extends Activity {

    private static final String TAG = "Escaneos";
    private static final int REQ_FOLDER = 1;
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    /** Los escaneos en pantalla (para elegir con cuál unir otro). */
    private List<Item> shown = new ArrayList<>();
    /** Miniaturas ya leídas (por nombre y tamaño del ZIP) y el hilo que las lee, aparte del que arma la lista. */
    private final Map<String, Bitmap> thumbs = new java.util.concurrent.ConcurrentHashMap<>();
    private final ExecutorService thumbWork = Executors.newSingleThreadExecutor();
    /** Lado mayor aproximado de la miniatura (px). */
    private static final int THUMB_PX = 320;

    /** Un escaneo con lo que se sabe de él. */
    private static final class Item {
        ScanIndex.Entry e;
        Uri zip, json;
        ScanIndex.Summary s;
        String version = "", origin = "", videoDate = "", debug = "desconocido (escaneo anterior a las fichas)", report;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        NavajaUi.window(this);
        NavajaUi.Screen scr = NavajaUi.screen(this, "Mis escaneos", "Lo que hay guardado, sin abrir el explorador de archivos",
                NavajaUi.headerAction(this, "Actualizar", v -> load()), true);
        content = scr.content;
        setContentView(scr.root);
        load();
    }

    @Override
    protected void onDestroy() {
        work.shutdownNow();
        thumbWork.shutdownNow();
        super.onDestroy();
    }

    private SharedPreferences prefs() { return getSharedPreferences("medir", MODE_PRIVATE); }

    /** La carpeta de guardado, si todavía hay permiso para leerla. */
    private Uri folder() {
        String t = prefs().getString("carpeta", null);
        if (t == null) return null;
        Uri u = Uri.parse(t);
        for (android.content.UriPermission p : getContentResolver().getPersistedUriPermissions())
            if (p.getUri().equals(u) && p.isReadPermission()) return u;
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

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FOLDER && data != null && data.getData() != null) {
            Uri tree = data.getData();
            getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            prefs().edit().putString("carpeta", tree.toString()).apply();
            load();
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    // ============================================================ lectura

    private void load() {
        content.removeAllViews();
        Uri tree = folder();
        if (tree == null) {
            LinearLayout card = NavajaUi.card(this);
            card.addView(NavajaUi.text(this, "Todavía no hay carpeta de guardado", NavajaUi.Style.LABEL));
            card.addView(NavajaUi.text(this, "Es la carpeta donde «Guardar (ZIP)» deja los escaneos. Elígela aquí (por ejemplo una carpeta «Navaja» "
                    + "en la raíz de Archivos) o al guardar el primer escaneo.", NavajaUi.Style.BODY));
            card.addView(NavajaUi.buttonRow(this, NavajaUi.primary(this, "Elegir carpeta", v -> chooseFolder())));
            content.addView(card);
            turntable();
            return;
        }
        content.addView(NavajaUi.text(this, "Leyendo la carpeta…", NavajaUi.Style.BODY));
        work.execute(() -> {
            List<Item> items = new ArrayList<>();
            List<ScanIndex.Loose> loose = new ArrayList<>();
            String name = "", error = null;
            try {
                name = folderName(tree);
                Map<String, Uri> uris = new HashMap<>();
                List<Object[]> files = new ArrayList<>();
                Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
                try (Cursor c = getContentResolver().query(kids, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
                    while (c != null && c.moveToNext()) {
                        String n = c.getString(1);
                        if (n == null) continue;
                        files.add(new Object[]{n, c.getLong(2), c.getLong(3)});
                        uris.put(n, DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)));
                    }
                }
                for (ScanIndex.Entry e : ScanIndex.group(files, loose)) {
                    Item it = new Item();
                    it.e = e;
                    it.zip = uris.get(e.base + ".zip");
                    it.json = uris.get(e.base + ".json");
                    describe(it);
                    items.add(it);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "leer", e);
                error = e.getMessage();
            }
            String fn = name, err = error;
            ui.post(() -> show(fn, items, loose, err));
        });
    }

    /** Completa lo que se sabe del escaneo: de su ficha si la tiene; si no, del informe que está al principio del ZIP. */
    private void describe(Item it) {
        try {
            if (it.json != null) {
                JSONObject j = new JSONObject(new String(read(it.json, 4 << 20), "UTF-8"));
                it.report = j.optString("informe", "");
                it.version = j.optString("version", "");
                it.origin = j.optString("video_origen", "");
                it.videoDate = j.optString("video_fecha", "");
                it.debug = j.optBoolean("depuracion", false) ? "sí (fotos anotadas y siluetas de la IA)" : "no";
            } else if (it.zip != null) {
                try (InputStream in = getContentResolver().openInputStream(it.zip); ZipInputStream z = new ZipInputStream(in)) {
                    // informe.md es la primera entrada: no hace falta recorrer el ZIP
                    ZipEntry e = z.getNextEntry();
                    if (e != null && e.getName().equals("informe.md")) it.report = new String(all(z, 4 << 20), "UTF-8");
                }
            }
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(TAG, "ficha de " + it.e.base, e);
        }
        it.s = ScanIndex.parse(it.report == null ? "" : it.report);
        if (it.version.isEmpty()) it.version = it.s.version;
    }

    private byte[] read(Uri u, int max) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(u)) {
            if (in == null) throw new IOException("no se pudo abrir");
            return all(in, max);
        }
    }

    private static byte[] all(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        for (int n; bo.size() < max && (n = in.read(buf)) > 0; ) bo.write(buf, 0, n);
        return bo.toByteArray();
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

    private static String mb(long bytes) { return bytes <= 0 ? "—" : String.format(Locale.ROOT, "%.1f MB", bytes / 1e6); }

    // ============================================================ pantalla

    private void show(String folder, List<Item> items, List<ScanIndex.Loose> loose, String error) {
        content.removeAllViews();
        long total = 0;
        int withVideo = 0, withDebug = 0;
        for (Item it : items) {
            total += it.e.zip + it.e.video + it.e.sensors;
            if (it.e.video > 0) withVideo++;
            if (it.debug.startsWith("sí")) withDebug++;
        }
        LinearLayout head = NavajaUi.card(this);
        head.addView(NavajaUi.text(this, "Carpeta: " + folder, NavajaUi.Style.LABEL));
        head.addView(NavajaUi.text(this, error != null ? "No se pudo leer la carpeta: " + error
                : String.format(Locale.ROOT, "%d escaneos · %s en total · %d con su video · %d con depuración · %d videos sueltos", items.size(), mb(total),
                withVideo, withDebug, loose.size()), NavajaUi.Style.BODY));
        head.addView(NavajaUi.buttonRow(this, NavajaUi.secondary(this, "Cambiar carpeta", v -> chooseFolder())));
        content.addView(head);
        shown = items;
        for (Item it : items) content.addView(card(it));
        if (!loose.isEmpty()) {
            LinearLayout lc = NavajaUi.card(this);
            lc.addView(NavajaUi.text(this, "Videos sueltos (grabados, sin escaneo guardado con ese nombre)", NavajaUi.Style.LABEL));
            StringBuilder sb = new StringBuilder();
            for (ScanIndex.Loose l : loose)
                sb.append(l.name).append(" · ").append(mb(l.size)).append(l.modified > 0 ? " · " + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date(l.modified)) : "").append('\n');
            lc.addView(NavajaUi.text(this, sb.toString().trim(), NavajaUi.Style.SECONDARY));
            content.addView(lc);
        }
        turntable();
    }

    private LinearLayout card(Item it) {
        ScanIndex.Summary s = it.s;
        LinearLayout c = NavajaUi.card(this);
        // La primera foto del escaneo al lado del título: para reconocer el objeto y la posición de un vistazo
        c.addView(titleRow(it, (it.e.saved.isEmpty() ? it.e.base : it.e.saved) + (s.mode.isEmpty() ? "" : " · " + s.mode), NavajaUi.Style.SUBTITLE, 112));
        StringBuilder sb = new StringBuilder();
        line(sb, "Versión de Navaja", it.version.isEmpty() ? "no se pudo leer" : it.version);
        line(sb, "Medidas por puntos", s.byPoints);
        line(sb, "Volumen", s.bySilhouettes + (s.difference.isEmpty() ? "" : " Diferencia " + s.difference));
        line(sb, "Video", (it.e.video > 0 ? "guardado (" + mb(it.e.video) + ")" : "no está junto al ZIP") + (s.duration.isEmpty() ? "" : " · dura " + s.duration)
                + (it.origin.isEmpty() ? "" : " · " + it.origin) + (it.videoDate.isEmpty() ? "" : " · grabado el " + it.videoDate)
                + (s.turned ? " · celular apaisado" : ""));
        line(sb, "Sensores", s.sensors + (it.e.sensors > 0 ? " · archivo guardado" : ""));
        line(sb, "Hoja", s.sheet);
        line(sb, "IA", s.accelerator);
        line(sb, "Depuración", it.debug);
        line(sb, "Archivos", it.e.base + ".zip (" + mb(it.e.zip) + ")" + (it.json != null ? " + ficha" : " · sin ficha (anterior)"));
        c.addView(NavajaUi.text(this, sb.toString().trim(), NavajaUi.Style.BODY));
        c.addView(NavajaUi.buttonRow(this, NavajaUi.secondary(this, "Ver informe", v -> showReport(it)),
                NavajaUi.secondary(this, "Compartir ZIP", v -> share(it.zip, "application/zip"))));
        // Agregar otra pose: el mismo objeto en otra posición (los dos escaneos del modo hoja)
        if (it.zip != null && sheet(it))
            c.addView(NavajaUi.buttonRow(this, NavajaUi.secondary(this, "Unir con otra pose…", v -> chooseOther(it))));
        return c;
    }

    private static boolean sheet(Item it) { return it.s != null && it.s.mode != null && it.s.mode.toLowerCase(Locale.ROOT).contains("hoja"); }

    /** Elegir el otro escaneo del mismo objeto y abrir la unión ({@link JoinActivity}). */
    private void chooseOther(Item first) {
        List<Item> others = new ArrayList<>();
        for (Item o : shown) if (o != first && o.zip != null && sheet(o)) others.add(o);
        if (others.isEmpty()) {
            toast("No hay otro escaneo del modo hoja en esta carpeta");
            return;
        }
        // Lista con la primera foto de cada escaneo: sin verla es difícil saber cuál es la otra posición del mismo objeto
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = NavajaUi.dp(this, 16);
        list.setPadding(pad, NavajaUi.dp(this, 8), pad, 0);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(list);
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this).setTitle("¿Con cuál escaneo del mismo objeto?").setView(scroll)
                .setNegativeButton("Cancelar", null).create();
        list.addView(titleRow(first, "Vas a unir éste:\n" + (first.e.saved.isEmpty() ? first.e.base : first.e.saved), NavajaUi.Style.SECONDARY, 96));
        for (Item o : others) {
            LinearLayout row = titleRow(o, (o.e.saved.isEmpty() ? o.e.base : o.e.saved) + (o.s.bySilhouettes.isEmpty() ? "" : "\n" + o.s.bySilhouettes),
                    NavajaUi.Style.BODY, 96);
            row.setPadding(0, NavajaUi.dp(this, 10), 0, NavajaUi.dp(this, 10));
            row.setClickable(true);
            row.setOnClickListener(v -> {
                dlg.dismiss();
                // Los demás escaneos del modo hoja van también, para poder agregar una tercera toma desde la pantalla de unión
                ArrayList<Uri> zips = new ArrayList<>();
                List<String> names = new ArrayList<>(), labels = new ArrayList<>(), keys = new ArrayList<>();
                for (Item x : shown) {
                    if (x.zip == null || !sheet(x)) continue;
                    zips.add(x.zip);
                    names.add(x.e.base);
                    labels.add((x.e.saved.isEmpty() ? x.e.base : x.e.saved) + (x.s.bySilhouettes.isEmpty() ? "" : "\n" + x.s.bySilhouettes));
                    keys.add(x.e.base + "_" + x.e.zip);
                }
                startActivity(new Intent(this, JoinActivity.class)
                        .putExtra(JoinActivity.EXTRA_A, first.zip).putExtra(JoinActivity.EXTRA_B, o.zip)
                        .putExtra(JoinActivity.EXTRA_NAME_A, first.e.base).putExtra(JoinActivity.EXTRA_NAME_B, o.e.base)
                        .putParcelableArrayListExtra(JoinActivity.EXTRA_OTHERS, zips)
                        .putExtra(JoinActivity.EXTRA_OTHER_NAMES, names.toArray(new String[0]))
                        .putExtra(JoinActivity.EXTRA_OTHER_LABELS, labels.toArray(new String[0]))
                        .putExtra(JoinActivity.EXTRA_OTHER_THUMBS, keys.toArray(new String[0]))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            });
            list.addView(row);
        }
        dlg.show();
    }

    /** Una fila con la miniatura del escaneo (su primera foto) y un texto al lado. */
    private LinearLayout titleRow(Item it, String text, NavajaUi.Style style, int thumbDp) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundColor(0x22808080);
        int side = NavajaUi.dp(this, thumbDp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(side, side * 3 / 4);
        lp.rightMargin = NavajaUi.dp(this, 12);
        row.addView(iv, lp);
        row.addView(NavajaUi.text(this, text, style), new LinearLayout.LayoutParams(0, -2, 1f));
        thumb(it, iv);
        return row;
    }

    /**
     * Pone en {@code iv} la miniatura del escaneo: la primera foto de su ZIP
     * ({@code fotos/}), reducida. Leer el ZIP es lento (hay que pasar por todo
     * lo que viene antes), así que se hace aparte y queda guardada en la
     * carpeta temporal de la app para la próxima vez.
     */
    private void thumb(Item it, ImageView iv) {
        if (it.zip == null) return;
        String key = it.e.base + "_" + it.e.zip;
        iv.setTag(key);
        Bitmap ready = thumbs.get(key);
        if (ready != null) {
            iv.setImageBitmap(ready);
            return;
        }
        thumbWork.execute(() -> {
            Bitmap b = thumbs.get(key);
            if (b == null) b = loadThumb(it.zip, key);
            if (b == null) return;
            thumbs.put(key, b);
            Bitmap show = b;
            ui.post(() -> { if (key.equals(iv.getTag())) iv.setImageBitmap(show); });
        });
    }

    private Bitmap loadThumb(Uri zip, String key) {
        File dir = new File(getCacheDir(), "miniaturas"), f = new File(dir, key.replaceAll("[^A-Za-z0-9_.-]", "_") + ".jpg");
        if (f.exists()) {
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
            if (b != null) return b;
        }
        try (InputStream in = getContentResolver().openInputStream(zip)) {
            if (in == null) return null;
            ZipInputStream z = new ZipInputStream(in);
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if (!e.getName().startsWith("fotos/") || !e.getName().endsWith(".jpg")) continue;
                byte[] jpg = all(z, 8 << 20);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
                o.inSampleSize = Math.max(1, Math.max(o.outWidth, o.outHeight) / THUMB_PX);
                o.inJustDecodeBounds = false;
                Bitmap b = BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
                if (b == null) return null;
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                try (FileOutputStream fo = new FileOutputStream(f)) {
                    b.compress(Bitmap.CompressFormat.JPEG, 82, fo);
                } catch (IOException ex) {
                    Log.w(TAG, "guardar miniatura", ex); // se muestra igual; la próxima vez se vuelve a leer
                }
                return b;
            }
        } catch (IOException | RuntimeException | OutOfMemoryError ex) {
            Log.w(TAG, "miniatura de " + key, ex);
        }
        return null;
    }

    private static void line(StringBuilder sb, String label, String value) {
        if (value == null || value.trim().isEmpty()) return;
        sb.append(label).append(": ").append(value.trim()).append('\n');
    }

    private void showReport(Item it) {
        if (it.report == null || it.report.isEmpty()) {
            toast("Este escaneo no tiene informe legible");
            return;
        }
        NavajaUi.Screen scr = NavajaUi.screen(this, "Informe", it.e.base, NavajaUi.headerAction(this, "Volver", v -> {
            NavajaUi.Screen back = NavajaUi.screen(this, "Mis escaneos", "Lo que hay guardado, sin abrir el explorador de archivos",
                    NavajaUi.headerAction(this, "Actualizar", w -> load()), true);
            content = back.content;
            setContentView(back.root);
            load();
        }), true);
        TextView t = NavajaUi.text(this, it.report, NavajaUi.Style.MONO);
        t.setTextIsSelectable(true);
        scr.content.addView(t);
        setContentView(scr.root);
    }

    private void share(Uri u, String mime) {
        if (u == null) return;
        try {
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, u)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Compartir"));
        } catch (RuntimeException e) {
            toast("No se pudo compartir: " + e.getMessage());
        }
    }

    /** Los escaneos del modo 3 (base giratoria): carpetas scan_… en el almacenamiento de la app. */
    private void turntable() {
        File root = new File(getExternalFilesDir(null), "scans");
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null || dirs.length == 0) return;
        Arrays.sort(dirs, (a, b) -> b.getName().compareTo(a.getName()));
        LinearLayout c = NavajaUi.card(this);
        c.addView(NavajaUi.text(this, "Base giratoria (modo 3) · " + dirs.length + " escaneos", NavajaUi.Style.LABEL));
        StringBuilder sb = new StringBuilder();
        for (File d : dirs) {
            long size = 0;
            int photos = 0;
            boolean log = false, model = false;
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) {
                size += f.length();
                String n = f.getName();
                if (n.equals("log.txt")) log = true;
                else if (n.startsWith("modelo.")) model = true;
                else if (n.endsWith(".jpg")) photos++;
            }
            sb.append(d.getName()).append(" · ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date(d.lastModified())))
                    .append(" · ").append(mb(size)).append(model ? " · con modelo" : " · sin modelo").append(log ? " · con log" : " · sin log")
                    .append(photos > 0 ? " · " + photos + " fotos" : "").append('\n');
        }
        c.addView(NavajaUi.text(this, sb.toString().trim(), NavajaUi.Style.SECONDARY));
        c.addView(NavajaUi.text(this, "Están en Android/data/com.josexato.navaja/files/scans/.", NavajaUi.Style.MUTED));
        content.addView(c);
    }
}
