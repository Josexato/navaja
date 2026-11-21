package com.josexato.navaja.measure;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.josexato.navaja.measure.core.ScanIndex;
import com.josexato.navaja.ui.NavajaUi;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
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
        c.addView(NavajaUi.text(this, (it.e.saved.isEmpty() ? it.e.base : it.e.saved) + (s.mode.isEmpty() ? "" : " · " + s.mode), NavajaUi.Style.SUBTITLE));
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
        return c;
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
