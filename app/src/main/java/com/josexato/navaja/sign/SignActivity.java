package com.josexato.navaja.sign;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.pdf.PdfRenderer;
import android.hardware.biometrics.BiometricPrompt;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.josexato.navaja.sign.core.Annex;
import com.josexato.navaja.sign.core.Bytes;
import com.josexato.navaja.sign.core.Ecdsa;
import com.josexato.navaja.sign.core.Http;
import com.josexato.navaja.sign.core.KeyChange;
import com.josexato.navaja.sign.core.Ledger;
import com.josexato.navaja.sign.core.Ots;
import com.josexato.navaja.sign.core.OtsUpdater;
import com.josexato.navaja.sign.core.PdfFile;
import com.josexato.navaja.sign.core.Signer;
import com.josexato.navaja.sign.core.Ssjf;
import com.josexato.navaja.sign.core.Stamper;
import com.josexato.navaja.sign.core.TimeAnchors;
import com.josexato.navaja.sign.core.Verifier;
import com.josexato.navaja.sign.core.storage.Backend;
import com.josexato.navaja.ui.NavajaUi;
import com.josexato.navaja.ui.Palette;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Firma de PDF verificable (SSJF v1): firma dibujada con el lápiz + QR con
 * el resumen del documento firmado con la clave del teléfono, historial
 * encadenado y respaldo donde el usuario quiera. Ver docs/ssjf/.
 *
 * <p>Privacidad: el documento nunca sale del teléfono. A internet sólo van,
 * si el usuario lo permite en cada firma, resúmenes SHA-256 (sellos de
 * tiempo) y una consulta del último bloque de Bitcoin.
 */
public class SignActivity extends Activity {

    private static final String TAG = "Firma";
    private static final int REQ_PICK_SIGN = 1, REQ_PICK_VERIFY = 2, REQ_SAVE = 3, REQ_AUTH = 4, REQ_BACKUP_FILE = 5,
            REQ_LOC = 6, REQ_EXPORT = 7, REQ_IMPORT = 8;
    private static final int MAX_PDF = 200 << 20;
    private static final int[] INKS = {0xFF14307A, 0xFF111111};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private Store store;
    private NavajaUi.Screen scr;

    // Documento en curso
    private byte[] pdf;
    private String pdfName = "documento.pdf";
    private PdfFile parsed;
    private int page;
    private File renderFile;
    private ParcelFileDescriptor pfd;
    private PdfRenderer renderer;
    private PageView pageView;
    private Stamper.Ink ink;
    private int inkColor = INKS[0];
    /** Posición del sello (se conserva al rehacer la firma o cambiar de página). */
    private double boxU = 0.5, boxV = 0.8, boxW = 0.45;
    private boolean onHome;

    // Pendientes de resultados de otras pantallas
    private Runnable pendingAuth, pendingLoc;
    private byte[] pendingSave;
    private Map<String, String> backupDraft;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        Keys.alias = store.pref("keyAlias", Keys.FIRST);
        inkColor = (int) Long.parseLong(store.pref("ink", Long.toString(INKS[0] & 0xFFFFFFFFL)));
        showHome();
        ensureKey();
        handleIntent(getIntent());
        maybeAutoUpgradeOts();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleIntent(i);
    }

    @Override
    protected void onDestroy() {
        closeRenderer();
        work.shutdown();
        super.onDestroy();
    }

    /** "Abrir con Navaja" de un PDF, o texto compartido (el QR escaneado con la cámara o Google Lens). */
    @SuppressWarnings("deprecation") // getParcelableExtra(String): la variante tipada es API 33+
    private void handleIntent(Intent i) {
        if (i == null) return;
        String act = i.getAction();
        if (Intent.ACTION_VIEW.equals(act) && i.getData() != null) {
            askSignOrVerify(i.getData());
        } else if (Intent.ACTION_SEND.equals(act)) {
            String text = i.getStringExtra(Intent.EXTRA_TEXT);
            Uri stream = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null) askSignOrVerify(stream);
            else if (text != null) verifyQrText(text);
        }
    }

    private void askSignOrVerify(Uri uri) {
        new AlertDialog.Builder(this).setTitle(displayName(uri))
                .setItems(new String[]{"Firmar este PDF", "Verificar sus firmas"}, (d, which) -> {
                    if (which == 0) loadForSigning(uri);
                    else verifyPdf(uri);
                }).show();
    }

    // ================================================================ inicio

    private void showHome() {
        onHome = true;
        scr = NavajaUi.screen(this, "Firma verificable", "SSJF v1 · el documento no sale del teléfono", null, true);
        LinearLayout c = scr.content;

        LinearLayout key = NavajaUi.card(this);
        key.addView(NavajaUi.text(this, "Tu clave de firma", NavajaUi.Style.LABEL));
        TextView keyInfo = NavajaUi.text(this, "…", NavajaUi.Style.BODY);
        key.addView(keyInfo);
        TextView keyProt = NavajaUi.text(this, "", NavajaUi.Style.SECONDARY);
        key.addView(keyProt);
        key.addView(NavajaUi.buttonRow(this,
                NavajaUi.secondary(this, "Compartir clave pública", v -> sharePublicKey()),
                NavajaUi.secondary(this, "Cambiar clave…", v -> keyChangeMenu())));
        c.addView(key);
        work.execute(() -> {
            String id, prot;
            try {
                byte[] pk = Keys.exists() ? Keys.publicKey() : null;
                id = pk == null ? "Se crea al firmar por primera vez" : "Identificador " + Ecdsa.keyId(pk);
                prot = pk == null ? "" : "Guardada en " + Keys.protection() + ". No se puede copiar ni exportar.";
                if (retired()) prot = "⚠ Reemplazada por la clave de tu teléfono nuevo: este teléfono ya no firma.\n" + prot;
            } catch (Exception e) {
                id = "Clave no disponible: " + e.getMessage();
                prot = "";
            }
            String fi = id, fp = prot;
            ui.post(() -> {
                keyInfo.setText(fi);
                keyProt.setText(fp);
            });
        });

        Button sign = NavajaUi.primary(this, "Firmar un PDF", v -> pick(REQ_PICK_SIGN));
        NavajaUi.topMargin(sign, 12);
        c.addView(sign, new LinearLayout.LayoutParams(-1, -2));
        c.addView(NavajaUi.buttonRow(this,
                NavajaUi.secondary(this, "Verificar un PDF", v -> pick(REQ_PICK_VERIFY)),
                NavajaUi.secondary(this, "Verificar un QR", v -> askQrText())));

        LinearLayout hist = NavajaUi.card(this);
        hist.addView(NavajaUi.text(this, "Historial de firmas", NavajaUi.Style.LABEL));
        TextView hInfo = NavajaUi.text(this, "…", NavajaUi.Style.BODY);
        hist.addView(hInfo);
        hist.addView(NavajaUi.buttonRow(this,
                NavajaUi.secondary(this, "Ver historial", v -> showHistory()),
                NavajaUi.secondary(this, "Respaldo", v -> showBackupSettings())));
        c.addView(hist);
        work.execute(() -> {
            String s;
            try {
                Ledger l = store.loadLedger();
                String chain = l.verifyChain();
                long n = 0;
                for (Ledger.Entry e : l.entries()) if (Ledger.TYPE_SIGN.equals(e.type())) n++;
                s = n + (n == 1 ? " firma" : " firmas") + " · " + (chain == null ? "cadena íntegra ✓" : "⚠ " + chain)
                        + "\nRespaldo: " + backupLabel();
            } catch (IOException e) {
                s = "⚠ " + e.getMessage();
            }
            String fs = s;
            ui.post(() -> hInfo.setText(fs));
        });

        TextView about = NavajaUi.text(this, "Cómo funciona: el QR contiene el resumen SHA-256 del PDF tal como lo recibiste, "
                + "la hora, el último bloque de Bitcoin y una firma hecha con una clave que vive dentro del chip seguro del "
                + "teléfono. El PDF original queda intacto byte a byte al inicio del archivo firmado: cualquiera puede "
                + "comprobarlo. Tu historial (con nombre de archivo, ubicación y comentario) sólo está aquí y en el respaldo "
                + "que tú elijas.\nGuía y verificador: " + Ssjf.DEFAULT_URL, NavajaUi.Style.MUTED);
        android.text.util.Linkify.addLinks(about, android.text.util.Linkify.WEB_URLS);
        about.setLinkTextColor(Palette.ACCENT);
        about.setMovementMethod(LinkMovementMethod.getInstance());
        NavajaUi.topMargin(about, 12);
        c.addView(about);
        setContentView(scr.root);
    }

    private String backupLabel() {
        Map<String, String> cfg = store.backupConfig();
        Backend b = Backup.create(this, cfg);
        if (b == null) return "sin configurar";
        String last = store.pref("lastBackup", null);
        return b.describe() + (last == null ? "" : " · " + last);
    }

    private void ensureKey() {
        work.execute(() -> {
            try {
                if (!Keys.exists()) {
                    Keys.create(this);
                    ui.post(() -> {
                        if (onHome) showHome();
                    });
                }
            } catch (Exception e) {
                Log.w(TAG, "clave", e);
            }
        });
    }

    private void sharePublicKey() {
        work.execute(() -> {
            try {
                byte[] pk = Keys.publicKey();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ssjf_key", 1L);
                m.put("alg", "ES256 (ECDSA P-256 / SHA-256)");
                m.put("kid", Ecdsa.keyId(pk));
                m.put("pk", Bytes.b64url(pk));
                m.put("guia", Ssjf.DEFAULT_URL);
                String json = com.josexato.navaja.pantonera.core.Json.write(m);
                ui.post(() -> shareText("Mi clave pública SSJF", json));
            } catch (Exception e) {
                ui.post(() -> toast("Clave no disponible: " + e.getMessage()));
            }
        });
    }

    // ============================================================ selección

    private void pick(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf");
        try {
            startActivityForResult(i, req);
        } catch (ActivityNotFoundException e) {
            toast("No hay selector de archivos");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_AUTH) {
            Runnable r = pendingAuth;
            pendingAuth = null;
            if (res == RESULT_OK && r != null) r.run();
            else toast("Firma cancelada");
            return;
        }
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        switch (req) {
            case REQ_PICK_SIGN: loadForSigning(uri); break;
            case REQ_PICK_VERIFY: verifyPdf(uri); break;
            case REQ_SAVE:
            case REQ_EXPORT:
                byte[] bytes = pendingSave;
                pendingSave = null;
                if (bytes != null) writeTo(uri, bytes);
                break;
            case REQ_IMPORT: importLedger(uri); break;
            case REQ_BACKUP_FILE:
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (SecurityException e) {
                    toast("El proveedor no permite acceso permanente; habrá que elegirlo de nuevo");
                }
                if (backupDraft != null) {
                    backupDraft.put("uri", uri.toString());
                    showBackupSettings();
                }
                break;
            default:
                break;
        }
    }

    private void writeTo(Uri uri, byte[] bytes) {
        work.execute(() -> {
            try (OutputStream o = Backup.openTruncated(this, uri)) {
                o.write(bytes);
                ui.post(() -> toast("Guardado"));
            } catch (IOException | RuntimeException e) {
                ui.post(() -> toast("No se pudo guardar: " + e.getMessage()));
            }
        });
    }

    private void saveAs(String name, String mime, byte[] bytes, int req) {
        pendingSave = bytes;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime)
                .putExtra(Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(i, req);
        } catch (ActivityNotFoundException e) {
            toast("No hay selector de archivos");
        }
    }

    private byte[] readUri(Uri uri) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("No se pudo abrir el archivo");
            return Http.readAll(in, MAX_PDF);
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (RuntimeException ignored) {
            // proveedor sin nombre
        }
        String last = uri.getLastPathSegment();
        return last == null ? "documento.pdf" : last;
    }

    // ======================================================== ubicar la firma

    private void loadForSigning(Uri uri) {
        if (retired()) {
            error("Este teléfono autorizó la clave de tu teléfono nuevo y ya no firma. Firma desde el teléfono nuevo.");
            return;
        }
        busy("Abriendo PDF…");
        work.execute(() -> {
            try {
                byte[] b = readUri(uri);
                PdfFile f = new PdfFile(b);
                if (f.pages().isEmpty()) throw new PdfFile.PdfException("El PDF no tiene páginas");
                File tmp = new File(getCacheDir(), "firmar.pdf");
                try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(b); }
                String name = displayName(uri);
                ui.post(() -> {
                    closeRenderer();
                    pdf = b;
                    parsed = f;
                    pdfName = name;
                    renderFile = tmp;
                    page = 0;
                    try {
                        pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY);
                        renderer = new PdfRenderer(pfd);
                    } catch (IOException | SecurityException e) {
                        closeRenderer();
                        error("Android no pudo mostrar este PDF: " + e.getMessage());
                        return;
                    }
                    showPlace();
                });
            } catch (PdfFile.PdfException e) {
                ui.post(() -> error(e.getMessage()));
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "abrir", e);
                ui.post(() -> error("No se pudo leer el PDF: " + e.getMessage()));
            }
        });
    }

    private void closeRenderer() {
        try {
            if (renderer != null) renderer.close();
            if (pfd != null) pfd.close();
        } catch (IOException | IllegalStateException ignored) {
            // ya cerrado
        }
        renderer = null;
        pfd = null;
        if (renderFile != null) {
            //noinspection ResultOfMethodCallIgnored
            renderFile.delete();
            renderFile = null;
        }
    }

    private TextView pageLabel;
    private Button continueBtn;

    private void showPlace() {
        scr = NavajaUi.screen(this, "Ubicar la firma", pdfName, NavajaUi.headerAction(this, "Inicio", v -> showHome()));
        LinearLayout c = scr.content;
        TextView hint = NavajaUi.text(this, "Toca donde va la firma. Arrastra para moverla; el punto de la esquina cambia el tamaño.",
                NavajaUi.Style.SECONDARY);
        c.addView(hint);
        onHome = false;
        pageView = new PageView(this);
        pageView.u = boxU;
        pageView.v = boxV;
        pageView.w = boxW;
        pageView.onChange = () -> {
            boxU = pageView.u;
            boxV = pageView.v;
            boxW = pageView.w;
        };
        pageView.setInk(ink, inkColor);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
        lp.topMargin = NavajaUi.dp(this, 6);
        c.addView(pageView, lp);
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        Button prev = NavajaUi.secondary(this, "◀", v -> renderPage(page - 1));
        Button next = NavajaUi.secondary(this, "▶", v -> renderPage(page + 1));
        pageLabel = NavajaUi.text(this, "", NavajaUi.Style.BODY);
        pageLabel.setGravity(Gravity.CENTER);
        nav.addView(prev);
        nav.addView(pageLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        nav.addView(next);
        NavajaUi.topMargin(nav, 6);
        c.addView(nav);
        continueBtn = NavajaUi.primary(this, "Continuar", v -> confirmDialog());
        c.addView(NavajaUi.buttonRow(this, NavajaUi.secondary(this, ink == null ? "Dibujar firma" : "Rehacer firma",
                v -> padDialog()), continueBtn));
        continueBtn.setEnabled(ink != null);
        setContentView(scr.root);
        pageView.post(() -> renderPage(page));
        if (ink == null) padDialog();
    }

    private void renderPage(int i) {
        if (renderer == null || parsed == null) return;
        int n = renderer.getPageCount();
        if (i < 0 || i >= n) return;
        page = i;
        try (PdfRenderer.Page p = renderer.openPage(i)) {
            int target = Math.max(600, Math.min(1600, pageView.getWidth()));
            float scale = target / (float) Math.max(p.getWidth(), p.getHeight()) * 1.4f;
            Bitmap bmp = Bitmap.createBitmap(Math.max(1, Math.round(p.getWidth() * scale)),
                    Math.max(1, Math.round(p.getHeight() * scale)), Bitmap.Config.ARGB_8888);
            bmp.eraseColor(Color.WHITE);
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            double[] vs;
            try {
                vs = Stamper.visualSize(parsed.pages().get(i));
            } catch (PdfFile.PdfException e) {
                vs = new double[]{p.getWidth(), p.getHeight()};
            }
            pageView.setPage(bmp, vs[0], vs[1]);
        } catch (IllegalStateException e) {
            toast("No se pudo mostrar la página: " + e.getMessage());
        }
        pageLabel.setText(String.format(Locale.getDefault(), "Página %d de %d", i + 1, n));
    }

    // ============================================================ dibujar

    private void padDialog() {
        Dialog d = new Dialog(this, android.R.style.Theme_Material_NoActionBar);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Palette.BG);
        int p = NavajaUi.dp(this, 16);
        box.setPadding(p, p, p, p);
        box.addView(NavajaUi.text(this, "Firma con el lápiz", NavajaUi.Style.TITLE));
        TextView sub = NavajaUi.text(this, "Gira el teléfono para tener más espacio. Con «Sólo lápiz» puedes apoyar la mano.",
                NavajaUi.Style.SECONDARY);
        box.addView(sub);
        SignaturePad pad = new SignaturePad(this);
        pad.setColor(inkColor);
        boolean stylusOnly = "1".equals(store.pref("stylusOnly", "0"));
        pad.setStylusOnly(stylusOnly);
        LinearLayout chips = new LinearLayout(this);
        NavajaUi.topMargin(chips, 8);
        TextView[] tStylus = new TextView[1];
        tStylus[0] = NavajaUi.chip(this, "Sólo lápiz", stylusOnly, v -> {
            boolean on = !"1".equals(store.pref("stylusOnly", "0"));
            store.setPref("stylusOnly", on ? "1" : "0");
            pad.setStylusOnly(on);
            NavajaUi.setChip(tStylus[0], on);
        });
        chips.addView(tStylus[0]);
        String[] names = {"Azul", "Negro"};
        TextView[] colorChips = new TextView[INKS.length];
        for (int k = 0; k < INKS.length; k++) {
            int col = INKS[k];
            colorChips[k] = NavajaUi.chip(this, names[k], inkColor == col, v -> {
                inkColor = col;
                store.setPref("ink", Long.toString(col & 0xFFFFFFFFL));
                pad.setColor(col);
                for (int j = 0; j < INKS.length; j++) NavajaUi.setChip(colorChips[j], INKS[j] == col);
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
            clp.leftMargin = NavajaUi.dp(this, 6);
            chips.addView(colorChips[k], clp);
        }
        box.addView(chips);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, 0, 1f);
        plp.topMargin = NavajaUi.dp(this, 10);
        LinearLayout padHolder = new LinearLayout(this);
        padHolder.setGravity(Gravity.CENTER);
        padHolder.addView(pad, new LinearLayout.LayoutParams(-1, -1));
        box.addView(padHolder, plp);
        Button done = NavajaUi.primary(this, "Listo", v -> {
            if (pad.isEmpty()) {
                toast("Dibuja tu firma primero");
                return;
            }
            ink = pad.ink();
            d.dismiss();
            showPlace();
        });
        box.addView(NavajaUi.buttonRow(this,
                NavajaUi.secondary(this, "Borrar", v -> pad.clear()),
                NavajaUi.secondary(this, "Cancelar", v -> d.dismiss()),
                done));
        d.setContentView(box);
        d.show();
        Window w = d.getWindow();
        if (w != null) w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    // ============================================================ confirmar

    private void confirmDialog() {
        if (ink == null) {
            padDialog();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = NavajaUi.dp(this, 20);
        box.setPadding(p, NavajaUi.dp(this, 8), p, 0);
        TextView note = new TextView(this);
        note.setText("Esto se guarda SÓLO en tu historial (no en el PDF):");
        note.setTextSize(13);
        box.addView(note);
        EditText name = new EditText(this);
        name.setHint("Nombre del archivo");
        name.setText(pdfName);
        name.setSingleLine(true);
        box.addView(name);
        EditText comment = new EditText(this);
        comment.setHint("Comentario (opcional): a quién, para qué…");
        comment.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        box.addView(comment);
        CheckBox loc = new CheckBox(this);
        loc.setText("Guardar dónde estoy (GPS)");
        loc.setChecked("1".equals(store.pref("saveLoc", "1")));
        box.addView(loc);
        CheckBox online = new CheckBox(this);
        online.setText("Anclar en el tiempo por internet (Bitcoin + sello RFC 3161 + OpenTimestamps). Sólo viajan resúmenes.");
        online.setChecked("1".equals(store.pref("online", "1")));
        box.addView(online);
        CheckBox date = new CheckBox(this);
        date.setText("Mostrar la fecha bajo la firma");
        date.setChecked("1".equals(store.pref("showDate", "1")));
        box.addView(date);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this).setTitle("Firmar «" + pdfName + "»").setView(sv)
                .setPositiveButton("Firmar", (dd, w) -> {
                    store.setPref("saveLoc", loc.isChecked() ? "1" : "0");
                    store.setPref("online", online.isChecked() ? "1" : "0");
                    store.setPref("showDate", date.isChecked() ? "1" : "0");
                    Map<String, String> priv = new LinkedHashMap<>();
                    String nm = name.getText().toString().trim();
                    priv.put("file", nm.isEmpty() ? pdfName : nm);
                    priv.put("comment", comment.getText().toString().trim());
                    priv.put("page", Integer.toString(page + 1));
                    if (loc.isChecked()) withLocation(l -> {
                        if (l != null) {
                            priv.put("lat", String.format(Locale.US, "%.6f", l.getLatitude()));
                            priv.put("lon", String.format(Locale.US, "%.6f", l.getLongitude()));
                            if (l.hasAccuracy()) priv.put("acc_m", Integer.toString(Math.round(l.getAccuracy())));
                        }
                        startSigning(priv, online.isChecked(), date.isChecked());
                    });
                    else startSigning(priv, online.isChecked(), date.isChecked());
                })
                .setNegativeButton("Cancelar", null).show();
    }

    private interface LocCallback { void got(Location l); }

    private void withLocation(LocCallback cb) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingLoc = () -> cb.got(bestLocation());
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
            return;
        }
        cb.got(bestLocation());
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_LOC && pendingLoc != null) {
            Runnable r = pendingLoc;
            pendingLoc = null;
            r.run(); // sin permiso, bestLocation() devuelve null y se firma igual
        }
    }

    /** La última posición conocida más precisa de los últimos 10 minutos. */
    @SuppressWarnings("MissingPermission") // se comprueba antes; SecurityException cubierta
    private Location bestLocation() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) return null;
        Location best = null;
        for (String prov : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location l = lm.getLastKnownLocation(prov);
                if (l == null || System.currentTimeMillis() - l.getTime() > 10 * 60_000) continue;
                if (best == null || (l.hasAccuracy() && (!best.hasAccuracy() || l.getAccuracy() < best.getAccuracy()))) best = l;
            } catch (SecurityException | IllegalArgumentException ignored) {
                // sin permiso o proveedor inexistente
            }
        }
        if (best == null) toast("Sin posición reciente: la firma se guarda sin ubicación");
        return best;
    }

    // ================================================================ firmar

    private void startSigning(Map<String, String> priv, boolean online, boolean showDate) {
        busy(online ? "Consultando el último bloque de Bitcoin…" : "Preparando…");
        work.execute(() -> {
            TimeAnchors.Block btc = null;
            String btcWarn = null;
            if (online) {
                try {
                    btc = TimeAnchors.bitcoinTip();
                } catch (IOException e) {
                    btcWarn = "Bloque Bitcoin no disponible: " + e.getMessage();
                }
            }
            TimeAnchors.Block fb = btc;
            String fw = btcWarn;
            ui.post(() -> authenticate(() -> doSign(priv, online, showDate, fb, fw, true)));
        });
    }

    private void doSign(Map<String, String> priv, boolean online, boolean showDate, TimeAnchors.Block btc, String btcWarn,
                        boolean retryAuth) {
        busy(online ? "Firmando y sellando en el tiempo…" : "Firmando…");
        Signer.Request r = new Signer.Request();
        r.pdf = pdf;
        r.page = page;
        r.u = pageView.u;
        r.v = pageView.v;
        r.w = pageView.w;
        r.ink = ink;
        r.inkRgb = inkColor & 0xFFFFFF;
        r.unix = System.currentTimeMillis() / 1000;
        r.btc = btc;
        String when = new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(new Date(r.unix * 1000));
        String link = Ssjf.DEFAULT_URL.replaceFirst("^https?://", "");
        r.lines = new String[]{showDate ? "Firmado el " + when + " · firma verificable SSJF" : "Firma verificable SSJF", "Verificar: " + link};
        work.execute(() -> {
            try {
                byte[] pk = Keys.publicKey();
                Signer.Result res = Signer.sign(r, pk, Keys::sign, online ? Signer.ONLINE : null);
                if (btcWarn != null) res.warnings.add(0, btcWarn);
                Ledger l = store.loadLedger();
                String chain = l.verifyChain();
                if (chain != null) res.warnings.add("Tu historial tiene un problema previo: " + chain);
                Ledger.Entry e = Signer.record(l, res, priv, null);
                store.saveLedger(l);
                Store.writeAtomic(store.pdfCopy(e.seq()), res.pdf);
                ui.post(() -> {
                    showResult(res, e);
                    saveAs(baseName(pdfName) + "_firmado.pdf", "application/pdf", res.pdf, REQ_SAVE);
                });
                autoBackup(l);
            } catch (Exception ex) {
                if (Keys.needsAuth(ex) && retryAuth) {
                    ui.post(() -> authenticate(() -> doSign(priv, online, showDate, btc, btcWarn, false)));
                    return;
                }
                Log.w(TAG, "firmar", ex);
                ui.post(() -> error("No se pudo firmar: " + ex.getMessage()));
            }
        });
    }

    private static String baseName(String n) {
        String b = n.replaceAll("(?i)\\.pdf$", "");
        return b.isEmpty() ? "documento" : b;
    }

    /** Huella/rostro o PIN antes de usar la clave (si el teléfono tiene bloqueo). */
    @SuppressWarnings("deprecation") // createConfirmDeviceCredentialIntent: la vía en API < 30
    private void authenticate(Runnable ok) {
        if (!Keys.deviceSecure(this)) {
            ok.run();
            return;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            BiometricPrompt bp = new BiometricPrompt.Builder(this)
                    .setTitle("Firmar «" + pdfName + "»")
                    .setSubtitle("Confirma que eres tú")
                    .setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
                            | android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build();
            bp.authenticate(new CancellationSignal(), getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) { ok.run(); }

                @Override
                public void onAuthenticationError(int code, CharSequence msg) {
                    dismissBusy();
                    toast("Firma cancelada: " + msg);
                }
            });
        } else {
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            Intent i = km == null ? null : km.createConfirmDeviceCredentialIntent("Firmar «" + pdfName + "»", "Confirma que eres tú");
            if (i == null) {
                ok.run();
                return;
            }
            pendingAuth = ok;
            dismissBusy();
            startActivityForResult(i, REQ_AUTH);
        }
    }

    private void autoBackup(Ledger l) {
        Map<String, String> cfg = store.backupConfig();
        Backend b = Backup.create(this, cfg);
        if (b == null) return;
        try {
            Backup.upload(b, l, "true".equals(cfg.get("private")));
            store.setPref("lastBackup", "OK " + new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(new Date()));
            ui.post(() -> toast("Historial respaldado en " + b.describe()));
        } catch (IOException | RuntimeException e) {
            store.setPref("lastBackup", "falló: " + e.getMessage());
            ui.post(() -> toast("No se pudo respaldar: " + e.getMessage()));
        }
    }

    // ============================================================ resultado

    private void showResult(Signer.Result res, Ledger.Entry e) {
        dismissBusy();
        onHome = false;
        scr = NavajaUi.screen(this, "Documento firmado", "Firma #" + e.seq() + " · " + pdfName,
                NavajaUi.headerAction(this, "Inicio", v -> showHome()), true);
        LinearLayout c = scr.content;
        NavajaUi.Metric mSeq = NavajaUi.metric(this, "Firma");
        NavajaUi.Metric mBtc = NavajaUi.metric(this, "Bloque Bitcoin");
        mSeq.set("#" + e.seq(), "en tu historial", Palette.OK);
        if (res.annex.btcHeight >= 0) mBtc.set(Long.toString(res.annex.btcHeight), "no antes de este bloque", Palette.OK);
        else mBtc.set("—", "sin conexión", Palette.WARN);
        c.addView(NavajaUi.metricRow(this, mSeq, mBtc));
        NavajaUi.Metric mTsa = NavajaUi.metric(this, "Sello RFC 3161");
        NavajaUi.Metric mOts = NavajaUi.metric(this, "OpenTimestamps");
        if (res.annex.tsr != null) mTsa.set("✓", tsaTime(res.annex.tsr), Palette.OK);
        else mTsa.set("—", "no disponible", Palette.WARN);
        if (res.annex.ots != null) mOts.set("✓", "pendiente de bloque (horas)", Palette.OK);
        else mOts.set("—", "no disponible", Palette.WARN);
        c.addView(NavajaUi.metricRow(this, mTsa, mOts));

        LinearLayout card = NavajaUi.card(this);
        card.addView(NavajaUi.text(this, "Resúmenes", NavajaUi.Style.LABEL));
        card.addView(mono("Antes (h0, en el QR):\n" + res.annex.h0));
        card.addView(mono("Sellado (h1, en el anexo):\n" + res.annex.h1));
        card.addView(mono("Archivo final (h2, en tu historial):\n" + res.h2));
        card.addView(NavajaUi.text(this, "Comprobar el original en una PC:  head -c " + res.annex.n0 + " archivo_firmado.pdf | sha256sum",
                NavajaUi.Style.MUTED));
        c.addView(card);
        for (String w : res.warnings) {
            TextView t = NavajaUi.text(this, "⚠ " + w, NavajaUi.Style.SECONDARY);
            t.setTextColor(Palette.WARN);
            NavajaUi.topMargin(t, 6);
            c.addView(t);
        }
        c.addView(NavajaUi.buttonRow(this,
                NavajaUi.secondary(this, "Guardar PDF", v -> saveAs(baseName(pdfName) + "_firmado.pdf", "application/pdf", res.pdf, REQ_SAVE)),
                NavajaUi.secondary(this, "Compartir PDF", v -> sharePdf(store.pdfCopy(e.seq()), pdfName))));
        c.addView(NavajaUi.buttonRow(this,
                NavajaUi.secondary(this, "Constancia", v -> saveAs(baseName(pdfName) + "_constancia_ssjf.json", "application/json",
                        constancia(null, e).getBytes(StandardCharsets.UTF_8), REQ_EXPORT)),
                NavajaUi.secondary(this, "Enviarme por correo", v -> emailWitness(e))));
        TextView t = NavajaUi.text(this, "«Enviarme por correo» deja un testigo independiente con fecha (firmado por tu proveedor de "
                + "correo con DKIM). Sólo lleva resúmenes, nunca el documento.", NavajaUi.Style.MUTED);
        NavajaUi.topMargin(t, 8);
        c.addView(t);
        setContentView(scr.root);
    }

    private static String tsaTime(String tsrB64) {
        try {
            String g = TimeAnchors.parseTsaResponse(Bytes.unb64(tsrB64)).genTime;
            return g.length() >= 14 ? g.substring(6, 8) + "/" + g.substring(4, 6) + "/" + g.substring(0, 4) + " "
                    + g.substring(8, 10) + ":" + g.substring(10, 12) + " UTC" : g;
        } catch (IOException | RuntimeException e) {
            return "ilegible";
        }
    }

    /** Constancia: la entrada pública del historial (sin datos privados) y sus sellos posteriores, autocontenida. */
    private String constancia(Ledger l, Ledger.Entry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ssjf_constancia", 1L);
        m.put("guia", Ssjf.DEFAULT_URL);
        try {
            m.put("entrada", com.josexato.navaja.pantonera.core.Json.parse(e.publicJson()));
        } catch (com.josexato.navaja.pantonera.core.Json.ParseException ex) {
            m.put("entrada", e.publicJson());
        }
        List<Object> proofs = new ArrayList<>();
        for (Ledger.Entry p : l == null ? new ArrayList<Ledger.Entry>() : l.proofsFor(e.seq())) {
            try {
                proofs.add(com.josexato.navaja.pantonera.core.Json.parse(p.publicJson()));
            } catch (com.josexato.navaja.pantonera.core.Json.ParseException ex) {
                proofs.add(p.publicJson());
            }
        }
        if (!proofs.isEmpty()) m.put("sellos", proofs);
        return com.josexato.navaja.pantonera.core.Json.write(m);
    }

    private void emailWitness(Ledger.Entry e) {
        String body = "Testigo de firma SSJF #" + e.seq() + " (" + Ssjf.iso(e.l("t")) + ")\n\n"
                + "h0 (documento antes de firmar): " + e.s("h0") + "\n"
                + "h2 (archivo firmado): " + e.s("h2") + "\n"
                + "QR: " + e.s("qr") + "\n\n"
                + "Entrada pública del historial:\n" + e.publicJson() + "\n\nGuía: " + Ssjf.DEFAULT_URL + "\n";
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Firma SSJF #" + e.seq() + " · " + e.s("h0").substring(0, 12))
                .putExtra(Intent.EXTRA_TEXT, body);
        startActivity(Intent.createChooser(i, "Enviar testigo"));
    }

    private void sharePdf(File f, String name) {
        if (!f.exists()) {
            toast("No hay copia local de ese PDF");
            return;
        }
        Uri u = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
        Intent i = new Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, u)
                .putExtra(Intent.EXTRA_SUBJECT, name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Compartir PDF firmado"));
    }

    // =========================================================== verificar

    private void verifyPdf(Uri uri) {
        busy("Verificando…");
        work.execute(() -> {
            try {
                byte[] b = readUri(uri);
                String name = displayName(uri);
                Ledger led = store.loadLedger();
                Verifier.Report rep = Verifier.verifyPdf(b, keyRing(led), led);
                ui.post(() -> showReport(rep, b, name));
            } catch (IOException | RuntimeException e) {
                ui.post(() -> error("No se pudo verificar: " + e.getMessage()));
            }
        });
    }

    /** Mis claves: la actual y las enlazadas por cambios de clave válidos del historial. */
    private Verifier.KeyRing keyRing(Ledger l) {
        byte[] pk = null;
        try {
            if (Keys.exists()) pk = Keys.publicKey();
        } catch (Exception e) {
            Log.w(TAG, "clave", e);
        }
        return Verifier.KeyRing.mine(pk, l);
    }

    private void showReport(Verifier.Report rep, byte[] file, String name) {
        dismissBusy();
        onHome = false;
        scr = NavajaUi.screen(this, "Verificación", name, NavajaUi.headerAction(this, "Inicio", v -> showHome()), true);
        LinearLayout c = scr.content;
        if (rep.checks.isEmpty()) {
            LinearLayout card = NavajaUi.card(this);
            card.addView(title("Este PDF no tiene firmas SSJF", Palette.TEXT));
            if (rep.originalOf != null)
                card.addView(body("Es el ORIGINAL sin firmar de tu firma #" + rep.originalOf.seq() + " del " + local(rep.originalOf.l("t"))
                        + describePriv(rep.originalOf)));
            else
                card.addView(body("Si alguien dice que lo firmaste, pídele el PDF firmado o el QR: sin eso no hay firma tuya."));
            c.addView(card);
        }
        for (int i = 0; i < rep.checks.size(); i++) c.addView(checkCard(rep.checks.get(i), file, name, i + 1, rep.checks.size()));
        if (rep.sameAsSaved != null) {
            TextView t = body("Idéntico, byte a byte, al PDF que guardaste en la firma #" + rep.sameAsSaved.seq() + ".");
            t.setTextColor(Palette.OK);
            NavajaUi.topMargin(t, 8);
            c.addView(t);
        }
        LinearLayout card = NavajaUi.card(this);
        card.addView(NavajaUi.text(this, "Este archivo", NavajaUi.Style.LABEL));
        card.addView(mono("SHA-256: " + rep.fileHash + "\nMD5: " + Bytes.hex(Bytes.md5(file)) + "\n" + file.length + " bytes"));
        c.addView(card);
        setContentView(scr.root);
    }

    private View checkCard(Verifier.Check ck, byte[] file, String name, int idx, int total) {
        LinearLayout card = NavajaUi.card(this);
        if (total > 1) card.addView(NavajaUi.text(this, "Firma " + idx + " de " + total, NavajaUi.Style.LABEL));
        String head;
        int color;
        String expl;
        switch (ck.verdict) {
            case MINE:
                head = "✓ Firmado por ti";
                color = Palette.OK;
                expl = "Firma #" + ck.entry.seq() + " de tu historial, el " + local(ck.entry.l("t")) + describePriv(ck.entry);
                break;
            case MINE_NOT_IN_LEDGER:
                head = "✓ Firma tuya, pero no está en tu historial";
                color = Palette.WARN;
                expl = "La firma es matemáticamente tuya (tu clave), pero no aparece en el historial de este teléfono. "
                        + "¿Restauraste el historial? Revisa tu respaldo.";
                break;
            case OTHER_KEY:
                head = ck.sigOk ? "Firma válida de OTRA clave" : "Clave desconocida";
                color = ck.sigOk ? Palette.GOOD : Palette.UNKNOWN;
                expl = (ck.sigOk ? "La firma es correcta, pero no es tuya (clave " + ck.claim.kid + "). " : (ck.detail == null ? "" : ck.detail + ". "))
                        + "No la hiciste con este teléfono.";
                break;
            case BAD_SIGNATURE:
                head = "✗ Firma FALSA o alterada";
                color = Palette.BAD;
                expl = "Los datos del QR no corresponden a la firma: alguien los cambió o la fabricó.";
                break;
            case DOC_MISMATCH:
                head = "✗ No es el documento que se firmó";
                color = Palette.BAD;
                expl = "El QR habla de otro documento (otro resumen h0) o el contenido original se modificó.";
                break;
            case STAMP_ALTERED:
                head = "✗ El sello fue modificado";
                color = Palette.BAD;
                expl = "El documento original coincide, pero la firma dibujada / QR / anexo cambiaron después.";
                break;
            default:
                head = "✗ Firma ilegible";
                color = Palette.BAD;
                expl = ck.detail == null ? "" : ck.detail;
        }
        card.addView(title(head, color));
        card.addView(body(expl));
        if (ck.claim != null) {
            StringBuilder sb = new StringBuilder("Hora declarada: " + local(ck.claim.t));
            if (ck.claim.btcHeight >= 0) sb.append("\nNo antes del bloque Bitcoin ").append(ck.claim.btcHeight);
            if (ck.annex != null && ck.annex.tsr != null) sb.append("\nSello RFC 3161: ").append(tsaTime(ck.annex.tsr));
            card.addView(NavajaUi.text(this, sb.toString(), NavajaUi.Style.SECONDARY));
            if (ck.otsProof != null) {
                TextView o = NavajaUi.text(this, "OpenTimestamps: ✓ existía antes del bloque Bitcoin " + ck.otsProof.l("btc_height")
                        + (ck.otsProof.l("btc_time") > 0 ? " (" + local(ck.otsProof.l("btc_time")) + ")" : ""), NavajaUi.Style.SECONDARY);
                o.setTextColor(Palette.OK);
                card.addView(o);
            } else if (ck.annex != null && ck.annex.ots != null && ck.stampOk) {
                TextView o = NavajaUi.text(this, "OpenTimestamps: sin comprobar todavía", NavajaUi.Style.SECONDARY);
                card.addView(o);
                card.addView(NavajaUi.buttonRow(this, NavajaUi.secondary(this, "Consultar OpenTimestamps", v -> checkAnnexOts(ck, o))));
            }
        }
        if (ck.keyRetiredAt >= 0 && ck.sigOk) {
            TextView w = body("⚠ Firmado con una clave que ya habías reemplazado el " + local(ck.keyRetiredAt)
                    + ". Si no fuiste tú, alguien está usando tu teléfono anterior.");
            w.setTextColor(Palette.WARN);
            NavajaUi.topMargin(w, 6);
            card.addView(w);
        }
        if (ck.unsignedBytesAfter > 0 && ck.valid()) {
            TextView w = body("⚠ Se agregaron cambios DESPUÉS de esta firma (" + ck.unsignedBytesAfter + " bytes sin firmar). "
                    + "Lo que se firmó es la versión que puedes extraer abajo; lo demás no está cubierto por la firma.");
            w.setTextColor(Palette.WARN);
            NavajaUi.topMargin(w, 6);
            card.addView(w);
        }
        if (ck.annex != null && ck.prefixOk) {
            Annex a = ck.annex;
            List<View> btns = new ArrayList<>();
            btns.add(NavajaUi.secondary(this, "Extraer original", v ->
                    saveAs(baseName(name) + "_original.pdf", "application/pdf", Verifier.prefix(file, a.n0), REQ_EXPORT)));
            if (ck.stampOk) btns.add(NavajaUi.secondary(this, "Extraer firmado", v ->
                    saveAs(baseName(name) + "_version_firmada.pdf", "application/pdf", Verifier.prefix(file, a.n1), REQ_EXPORT)));
            card.addView(NavajaUi.buttonRow(this, btns.toArray(new View[0])));
        }
        return card;
    }

    private String describePriv(Ledger.Entry e) {
        StringBuilder sb = new StringBuilder();
        if (e.privS("file") != null) sb.append("\nArchivo: ").append(e.privS("file"));
        if (e.privS("comment") != null) sb.append("\nComentario: ").append(e.privS("comment"));
        if (e.privS("lat") != null) sb.append("\nLugar: ").append(e.privS("lat")).append(", ").append(e.privS("lon"));
        return sb.toString();
    }

    private void askQrText() {
        EditText t = new EditText(this);
        t.setHint("Pega aquí el texto del QR (empieza con https://… o SSJF1;)");
        t.setMinLines(3);
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence clip = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            if (clip != null && clip.toString().contains(Ssjf.MAGIC)) t.setText(clip);
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = NavajaUi.dp(this, 20);
        box.setPadding(p, NavajaUi.dp(this, 8), p, 0);
        TextView help = new TextView(this);
        help.setText("Escanea el QR del papel con la cámara o Google Lens y compártelo con Navaja, o copia y pega su texto.");
        help.setTextSize(13);
        box.addView(help);
        box.addView(t);
        new AlertDialog.Builder(this).setTitle("Verificar un QR").setView(box)
                .setPositiveButton("Verificar", (d, w) -> verifyQrText(t.getText().toString()))
                .setNegativeButton("Cancelar", null).show();
    }

    private void verifyQrText(String text) {
        work.execute(() -> {
            Ledger l;
            try {
                l = store.loadLedger();
            } catch (IOException e) {
                l = new Ledger();
            }
            Verifier.Check ck = Verifier.verifyQr(text, keyRing(l), l);
            ui.post(() -> {
                onHome = false;
                scr = NavajaUi.screen(this, "Verificación de QR", "Sin el PDF: firma y registro", NavajaUi.headerAction(this, "Inicio",
                        v -> showHome()), true);
                scr.content.addView(checkCard(ck, new byte[0], "documento", 1, 1));
                if (ck.claim != null) {
                    LinearLayout card = NavajaUi.card(this);
                    card.addView(NavajaUi.text(this, "Para comprobar el documento", NavajaUi.Style.LABEL));
                    card.addView(body("El documento firmado debe tener " + ck.claim.n0 + " bytes y este SHA-256:"));
                    card.addView(mono(ck.claim.h0));
                    card.addView(NavajaUi.text(this, "En papel: compara el contenido con lo que dice tu historial (archivo y comentario).",
                            NavajaUi.Style.MUTED));
                    scr.content.addView(card);
                }
                setContentView(scr.root);
            });
        });
    }

    // ============================================================ historial

    private void showHistory() {
        busy("Leyendo historial…");
        work.execute(() -> {
            try {
                Ledger l = store.loadLedger();
                ui.post(() -> renderHistory(l));
            } catch (IOException e) {
                ui.post(() -> error(e.getMessage()));
            }
        });
    }

    private void renderHistory(Ledger l) {
        dismissBusy();
        onHome = false;
        scr = NavajaUi.screen(this, "Historial de firmas", l.entries().size() + " entradas",
                NavajaUi.headerAction(this, "Inicio", v -> showHome()), true);
        LinearLayout c = scr.content;
        String chain = l.verifyChain();
        TextView st = title(chain == null ? "✓ Cadena íntegra · cabeza " + l.head().substring(0, 16) + "…" : "⚠ " + chain,
                chain == null ? Palette.OK : Palette.BAD);
        c.addView(st);
        c.addView(NavajaUi.buttonRow(this,
                NavajaUi.secondary(this, "Exportar completo", v -> saveAs("ssjf-historial-completo.jsonl", "application/x-ndjson",
                        l.toJsonl(true).getBytes(StandardCharsets.UTF_8), REQ_EXPORT)),
                NavajaUi.secondary(this, "Exportar público", v -> saveAs("ssjf-historial-publico.jsonl", "application/x-ndjson",
                        l.toJsonl(false).getBytes(StandardCharsets.UTF_8), REQ_EXPORT))));
        c.addView(NavajaUi.buttonRow(this,
                NavajaUi.secondary(this, "Importar", v -> pickImport()),
                NavajaUi.secondary(this, "Actualizar sellos", v -> upgradeOts(false)),
                NavajaUi.secondary(this, "Respaldar ahora", v -> {
                    busy("Respaldando…");
                    work.execute(() -> {
                        autoBackup(l);
                        ui.post(this::dismissBusy);
                    });
                })));
        List<Ledger.Entry> list = new ArrayList<>(l.entries());
        Collections.reverse(list);
        for (Ledger.Entry e : list) {
            if (Ledger.TYPE_KEY.equals(e.type())) {
                c.addView(keyChangeCard(e));
                continue;
            }
            if (!Ledger.TYPE_SIGN.equals(e.type())) continue;
            LinearLayout card = NavajaUi.card(this);
            card.setBackground(NavajaUi.cardBackground(this));
            card.addView(NavajaUi.text(this, "#" + e.seq() + " · " + local(e.l("t")), NavajaUi.Style.SUBTITLE));
            String file = e.privS("file");
            card.addView(NavajaUi.text(this, file == null ? "(sin nombre)" : file, NavajaUi.Style.BODY));
            if (e.privS("comment") != null) card.addView(NavajaUi.text(this, e.privS("comment"), NavajaUi.Style.SECONDARY));
            String stamps = (e.m.get("btc") != null ? "Bitcoin ✓  " : "") + (e.s("rfc3161") != null ? "RFC 3161 ✓  " : "")
                    + otsLabel(l, e) + (e.privS("lat") != null ? "📍 ubicación" : "");
            if (!stamps.isEmpty()) card.addView(NavajaUi.text(this, stamps, NavajaUi.Style.MUTED));
            card.setOnClickListener(v -> entryDialog(l, e));
            c.addView(card);
        }
        setContentView(scr.root);
    }

    private static String otsLabel(Ledger l, Ledger.Entry e) {
        if (e.s("ots") == null) return "";
        Ledger.Entry p = OtsUpdater.completedProof(l, e.seq());
        return p != null ? "OTS ✓ bloque " + p.l("btc_height") + "  " : "OTS ⏳ pendiente  ";
    }

    private void entryDialog(Ledger l, Ledger.Entry e) {
        Ledger.Entry proof = OtsUpdater.completedProof(l, e.seq());
        String ots = e.s("ots") == null ? "no se pidió"
                : proof != null ? "anclado en el bloque Bitcoin " + proof.l("btc_height")
                + (proof.l("btc_time") > 0 ? " (" + local(proof.l("btc_time")) + ")" : "") + " ✓"
                : "pendiente: se completa solo en unas horas, o con «Actualizar sellos»";
        String msg = "Fecha: " + local(e.l("t")) + describePriv(e)
                + (e.privS("acc_m") != null ? " (±" + e.privS("acc_m") + " m)" : "")
                + "\nOpenTimestamps: " + ots
                + "\n\nh0 (antes): " + e.s("h0") + "\nh1 (sellado): " + e.s("h1") + "\nh2 (archivo): " + e.s("h2")
                + "\n\nQR: " + e.s("qr");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = NavajaUi.dp(this, 20);
        box.setPadding(p, p / 2, p, p / 2);
        TextView tv = new TextView(this);
        tv.setText(msg);
        tv.setTextIsSelectable(true);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(12);
        box.addView(tv);
        List<View> row1 = new ArrayList<>(), row2 = new ArrayList<>();
        row1.add(NavajaUi.secondary(this, "Compartir PDF", v -> sharePdf(store.pdfCopy(e.seq()), e.privS("file"))));
        row1.add(NavajaUi.secondary(this, "Copiar QR", v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("QR SSJF", e.s("qr")));
            toast("QR copiado");
        }));
        if (e.s("ots") != null) row2.add(NavajaUi.secondary(this, "Exportar .ots", v -> {
            String b64 = OtsUpdater.latestOts(l, e);
            saveAs(baseName(e.privS("file") == null ? "firma" + e.seq() : e.privS("file")) + "_firma" + e.seq() + ".ots",
                    "application/octet-stream", Bytes.unb64(b64), REQ_EXPORT);
        }));
        row2.add(NavajaUi.secondary(this, "Constancia", v -> saveAs("constancia_ssjf_" + e.seq() + ".json", "application/json",
                constancia(l, e).getBytes(StandardCharsets.UTF_8), REQ_EXPORT)));
        if (e.privS("lat") != null) row2.add(NavajaUi.secondary(this, "Mapa", v -> {
            Uri geo = Uri.parse("geo:" + e.privS("lat") + "," + e.privS("lon") + "?q=" + e.privS("lat") + "," + e.privS("lon")
                    + "(Firma " + e.seq() + ")");
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, geo));
            } catch (ActivityNotFoundException ex) {
                toast("No hay app de mapas");
            }
        }));
        box.addView(NavajaUi.buttonRow(this, row1.toArray(new View[0])));
        box.addView(NavajaUi.buttonRow(this, row2.toArray(new View[0])));
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this).setTitle("Firma #" + e.seq()).setView(sv).setPositiveButton("Cerrar", null).show();
    }

    // ===================================================== OpenTimestamps

    /** Al abrir: si hay sellos pendientes, el usuario usa internet para firmar y pasó 1 h desde el último intento. */
    private void maybeAutoUpgradeOts() {
        if (!"1".equals(store.pref("online", "1"))) return;
        long last;
        try {
            last = Long.parseLong(store.pref("otsLast", "0"));
        } catch (NumberFormatException e) {
            last = 0;
        }
        if (System.currentTimeMillis() / 1000 - last < 3600) return;
        upgradeOts(true);
    }

    /** Consulta los calendarios de los sellos pendientes y registra los que ya están en un bloque. */
    private void upgradeOts(boolean auto) {
        if (!auto) busy("Consultando los calendarios OpenTimestamps…");
        work.execute(() -> {
            OtsUpdater.Summary sum;
            Ledger l;
            try {
                l = store.loadLedger();
                long now = System.currentTimeMillis() / 1000;
                sum = OtsUpdater.run(l, Ots.HTTPS, TimeAnchors.EXPLORERS, now);
                store.setPref("otsLast", Long.toString(now));
                if (sum.completed > 0) store.saveLedger(l);
            } catch (IOException | RuntimeException e) {
                if (!auto) ui.post(() -> error("No se pudo actualizar: " + e.getMessage()));
                return;
            }
            if (sum.completed > 0) autoBackup(l);
            OtsUpdater.Summary fs = sum;
            Ledger fl = l;
            ui.post(() -> {
                if (auto) {
                    if (fs.completed > 0) toast(fs.completed + (fs.completed == 1 ? " firma quedó anclada" : " firmas quedaron ancladas")
                            + " en Bitcoin (OpenTimestamps)");
                    return;
                }
                dismissBusy();
                String msg = fs.nothingToDo() ? "No hay sellos pendientes." : android.text.TextUtils.join("\n", fs.lines);
                new AlertDialog.Builder(this).setTitle(fs.completed > 0 ? "✓ Sellos actualizados" : "Sellos OpenTimestamps")
                        .setMessage(msg).setPositiveButton("OK", (d, w) -> renderHistory(fl)).show();
            });
        });
    }

    /** Desde la verificación: completa el .ots del anexo (de cualquier firmante) y lo comprueba contra el bloque real. */
    private void checkAnnexOts(Verifier.Check ck, TextView out) {
        out.setText("OpenTimestamps: consultando…");
        work.execute(() -> {
            String text;
            int color;
            try {
                Ots.Proof p = Ots.Proof.parse(Bytes.unb64(ck.annex.ots));
                if (!p.complete()) Ots.upgrade(p, Ots.HTTPS);
                Ots.Status st = Ots.check(p, Bytes.unhex(ck.annex.h1), TimeAnchors.EXPLORERS);
                if (st.verified) {
                    text = "OpenTimestamps: ✓ existía antes del bloque Bitcoin " + st.height + " (" + local(st.blockTime) + ")";
                    color = Palette.OK;
                    if (ck.entry != null) {
                        Ledger l = store.loadLedger();
                        if (OtsUpdater.completedProof(l, ck.entry.seq()) == null) {
                            OtsUpdater.record(l, ck.entry.seq(), ck.annex.h1, p, st, System.currentTimeMillis() / 1000);
                            store.saveLedger(l);
                        }
                    }
                } else {
                    text = "OpenTimestamps: " + st.message;
                    color = st.complete ? Palette.BAD : Palette.WARN;
                }
            } catch (IOException | RuntimeException e) {
                text = "OpenTimestamps: no se pudo consultar (" + e.getMessage() + ")";
                color = Palette.WARN;
            }
            String ft = text;
            int fc = color;
            ui.post(() -> {
                out.setText(ft);
                out.setTextColor(fc);
            });
        });
    }

    // ======================================================= cambio de clave

    private boolean retired() { return "1".equals(store.pref("keyRetired", "0")); }

    private void keyChangeMenu() {
        String[] items = {
            "Nueva clave en este teléfono",
            "Soy el teléfono nuevo: pedir autorización",
            "Autorizar a mi teléfono nuevo",
            "Importar historial (de otro teléfono o respaldo)",
        };
        new AlertDialog.Builder(this).setTitle("Cambiar clave")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: confirmRotate(); break;
                        case 1: requestFromOldPhone(); break;
                        case 2: askAuthorize(); break;
                        default: pickImport();
                    }
                }).setNegativeButton("Cancelar", null).show();
    }

    private void confirmRotate() {
        if (retired()) {
            error("Este teléfono ya cedió su clave a otro teléfono.");
            return;
        }
        new AlertDialog.Builder(this).setTitle("Nueva clave en este teléfono")
                .setMessage("Se crea una clave nueva en el chip seguro y la actual la autoriza con su firma. "
                        + "Tus firmas anteriores siguen siendo tuyas y verificables; las nuevas usarán la clave nueva.\n\n"
                        + "Úsalo si sospechas que alguien usó tu teléfono o cada cierto tiempo. Para cambiar de teléfono usa "
                        + "«Soy el teléfono nuevo».")
                .setPositiveButton("Crear y autorizar", (d, w) -> authenticate(() -> rotateLocal(null, true)))
                .setNegativeButton("Cancelar", null).show();
    }

    /** Rotación local. {@code newAlias} se conserva entre reintentos para no crear dos claves. */
    private void rotateLocal(String newAlias, boolean retryAuth) {
        busy("Creando la clave nueva…");
        work.execute(() -> {
            String na = newAlias;
            try {
                String old = Keys.alias;
                if (na == null) {
                    na = Keys.nextAlias();
                    Keys.create(this, na);
                }
                byte[] newPk = Keys.publicKey(na), oldPk = Keys.publicKey(old);
                KeyChange k = new KeyChange();
                k.newPk = Bytes.b64url(newPk);
                k.oldPk = Bytes.b64url(oldPk);
                k.popT = k.t = System.currentTimeMillis() / 1000;
                k.reason = KeyChange.REASON_ROTATION;
                k.sigNew = Ecdsa.derToRaw(Keys.sign(na, KeyChange.popMessage(k.newPk, k.popT).getBytes(StandardCharsets.UTF_8)));
                k.sigOld = Ecdsa.derToRaw(Keys.sign(old, k.rotMessage().getBytes(StandardCharsets.UTF_8)));
                if (!k.verify()) throw new IllegalStateException("La declaración no verifica");
                Ledger l = store.loadLedger();
                k.record(l, k.t);
                store.saveLedger(l);
                store.setPref("keyAlias", na);
                Keys.alias = na;
                autoBackup(l);
                ui.post(() -> keyChangeDone(k, "Clave nueva " + k.newKid() + " autorizada por " + k.oldKid() + "."));
            } catch (Exception e) {
                String fna = na;
                if (Keys.needsAuth(e) && retryAuth) {
                    ui.post(() -> authenticate(() -> rotateLocal(fna, false)));
                    return;
                }
                ui.post(() -> error("No se pudo cambiar la clave: " + e.getMessage()));
            }
        });
    }

    private void keyChangeDone(KeyChange k, String msg) {
        dismissBusy();
        new AlertDialog.Builder(this).setTitle("✓ Cambio de clave registrado")
                .setMessage(msg + "\n\nComparte la declaración con quien verifica tus firmas (o publícala): prueba que la "
                        + "clave nueva es tuya, firmada con la anterior.")
                .setPositiveButton("Compartir declaración", (d, w) -> shareText("Cambio de clave SSJF", k.toJson()))
                .setNegativeButton("Cerrar", (d, w) -> showHome()).show();
    }

    /** Teléfono nuevo: genera la solicitud (clave nueva + prueba de posesión) para que la autorice el anterior. */
    private void requestFromOldPhone() {
        authenticate(() -> work.execute(() -> {
            try {
                if (!Keys.exists()) Keys.create(this);
                String pk = Bytes.b64url(Keys.publicKey());
                long t = System.currentTimeMillis() / 1000;
                byte[] sig = Ecdsa.derToRaw(Keys.sign(KeyChange.popMessage(pk, t).getBytes(StandardCharsets.UTF_8)));
                String req = KeyChange.requestText(pk, t, sig);
                ui.post(() -> new AlertDialog.Builder(this).setTitle("Solicitud para tu teléfono anterior")
                        .setMessage("1. Envía este texto a tu teléfono anterior (correo, WhatsApp, Quick Share…).\n"
                                + "2. Allí: Firma verificable → Cambiar clave → «Autorizar a mi teléfono nuevo» y pégalo.\n"
                                + "3. Exporta su historial completo e impórtalo aquí (Cambiar clave → Importar historial).\n\n"
                                + "Clave de este teléfono: " + Ecdsa.keyId(Bytes.unb64url(pk)))
                        .setPositiveButton("Compartir solicitud", (d, w) -> shareText("Solicitud de cambio de clave SSJF", req))
                        .setNegativeButton("Cerrar", null).show());
            } catch (Exception e) {
                ui.post(() -> error(Keys.needsAuth(e) ? "Vuelve a intentarlo y confirma con tu huella o PIN."
                        : "No se pudo crear la solicitud: " + e.getMessage()));
            }
        }));
    }

    private void askAuthorize() {
        if (retired()) {
            error("Este teléfono ya cedió su clave a otro teléfono.");
            return;
        }
        EditText t = new EditText(this);
        t.setHint("Pega la solicitud (empieza con SSJF1-POP;)");
        t.setMinLines(3);
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence clip = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            if (clip != null && clip.toString().contains("SSJF1-POP;")) t.setText(clip);
        }
        LinearLayout box = new LinearLayout(this);
        int p = NavajaUi.dp(this, 20);
        box.setPadding(p, NavajaUi.dp(this, 8), p, 0);
        box.addView(t, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle("Autorizar a mi teléfono nuevo").setView(box)
                .setPositiveButton("Revisar", (d, w) -> {
                    KeyChange req;
                    try {
                        req = KeyChange.parseRequest(t.getText().toString());
                    } catch (Ssjf.FormatException e) {
                        error(e.getMessage());
                        return;
                    }
                    new AlertDialog.Builder(this).setTitle("¿Autorizar la clave " + req.newKid() + "?")
                            .setMessage("Comprueba que ese identificador es el que muestra tu teléfono nuevo.\n\n"
                                    + "Después de autorizar, ESTE teléfono deja de firmar: las firmas nuevas se hacen en el otro. "
                                    + "Tus firmas anteriores siguen siendo verificables.")
                            .setPositiveButton("Autorizar", (d2, w2) -> authenticate(() -> authorize(req, true)))
                            .setNegativeButton("Cancelar", null).show();
                })
                .setNegativeButton("Cancelar", null).show();
    }

    private void authorize(KeyChange k, boolean retryAuth) {
        busy("Firmando la autorización…");
        work.execute(() -> {
            try {
                k.oldPk = Bytes.b64url(Keys.publicKey());
                k.t = System.currentTimeMillis() / 1000;
                k.reason = KeyChange.REASON_NEW_PHONE;
                k.sigOld = Ecdsa.derToRaw(Keys.sign(k.rotMessage().getBytes(StandardCharsets.UTF_8)));
                if (!k.verify()) throw new IllegalStateException("La declaración no verifica");
                Ledger l = store.loadLedger();
                k.record(l, k.t);
                store.saveLedger(l);
                store.setPref("keyRetired", "1");
                autoBackup(l);
                ui.post(() -> {
                    dismissBusy();
                    new AlertDialog.Builder(this).setTitle("✓ Teléfono nuevo autorizado")
                            .setMessage("Ahora exporta el historial COMPLETO e impórtalo en el teléfono nuevo "
                                    + "(Cambiar clave → Importar historial). Desde allí seguirás firmando.")
                            .setPositiveButton("Exportar historial", (d, w) -> saveAs("ssjf-historial-completo.jsonl",
                                    "application/x-ndjson", l.toJsonl(true).getBytes(StandardCharsets.UTF_8), REQ_EXPORT))
                            .setNeutralButton("Compartir declaración", (d, w) -> shareText("Cambio de clave SSJF", k.toJson()))
                            .setNegativeButton("Cerrar", (d, w) -> showHome()).show();
                });
            } catch (Exception e) {
                if (Keys.needsAuth(e) && retryAuth) {
                    ui.post(() -> authenticate(() -> authorize(k, false)));
                    return;
                }
                ui.post(() -> error("No se pudo autorizar: " + e.getMessage()));
            }
        });
    }

    private void pickImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (ActivityNotFoundException e) {
            toast("No hay selector de archivos");
        }
    }

    /** Restaura un historial exportado (completo o público) si continúa el local. */
    private void importLedger(Uri uri) {
        busy("Leyendo historial…");
        work.execute(() -> {
            try {
                Ledger imported = Ledger.parse(new String(readUri(uri), StandardCharsets.UTF_8));
                String chain = imported.verifyChain();
                if (chain != null) throw new IOException("El historial importado está dañado: " + chain);
                Ledger local = store.loadLedger();
                Ledger keep = Ledger.adopt(local, imported);
                if (keep == null) throw new IOException("Los historiales no coinciden: este teléfono ya tiene firmas que el "
                        + "importado no tiene. No se mezclan para no romper la cadena.");
                ui.post(() -> {
                    dismissBusy();
                    if (keep == local) {
                        toast("Tu historial ya contiene todo lo importado");
                        return;
                    }
                    new AlertDialog.Builder(this).setTitle("Importar historial")
                            .setMessage("Se reemplaza el historial local (" + local.entries().size() + " entradas) por el importado ("
                                    + imported.entries().size() + " entradas, cadena íntegra).")
                            .setPositiveButton("Importar", (d, w) -> work.execute(() -> {
                                try {
                                    store.saveLedger(imported);
                                    String link = linkStatus(imported);
                                    ui.post(() -> new AlertDialog.Builder(this).setTitle("✓ Historial importado").setMessage(link)
                                            .setPositiveButton("OK", (d2, w2) -> showHome()).show());
                                } catch (IOException e) {
                                    ui.post(() -> error(e.getMessage()));
                                }
                            }))
                            .setNegativeButton("Cancelar", null).show();
                });
            } catch (Exception e) {
                ui.post(() -> error("No se pudo importar: " + e.getMessage()));
            }
        });
    }

    /** ¿La clave de este teléfono está enlazada con las del historial? */
    private String linkStatus(Ledger l) {
        try {
            if (!Keys.exists()) return "Historial restaurado.";
            String pk = Bytes.b64url(Keys.publicKey());
            List<KeyChange> ch = KeyChange.fromLedger(l);
            if (ch.isEmpty()) return "Historial restaurado. No hay cambios de clave registrados.";
            if (KeyChange.myKeys(ch, pk).size() > 1)
                return "Tu clave actual (" + Ecdsa.keyId(Bytes.unb64url(pk)) + ") está enlazada con las anteriores: "
                        + "tus firmas viejas se reconocen como tuyas.";
            return "Historial restaurado, pero la clave de este teléfono aún no está autorizada por la anterior: "
                    + "usa «Soy el teléfono nuevo».";
        } catch (Exception e) {
            return "Historial restaurado.";
        }
    }

    private View keyChangeCard(Ledger.Entry e) {
        LinearLayout card = NavajaUi.card(this);
        card.setBackground(NavajaUi.cardBackground(this));
        KeyChange k = KeyChange.fromMap(e.m);
        boolean ok = k.verify();
        card.addView(NavajaUi.text(this, "#" + e.seq() + " · " + local(e.l("t")) + " · cambio de clave", NavajaUi.Style.SUBTITLE));
        TextView t = NavajaUi.text(this, (ok ? "✓ " : "✗ ") + e.s("old_kid") + " → " + e.s("new_kid")
                + (KeyChange.REASON_NEW_PHONE.equals(k.reason) ? " (teléfono nuevo)" : ""), NavajaUi.Style.BODY);
        t.setTextColor(ok ? Palette.TEXT : Palette.BAD);
        card.addView(t);
        card.setOnClickListener(v -> shareText("Cambio de clave SSJF", k.toJson()));
        return card;
    }

    // ============================================================= respaldo

    private static final String[] TYPES = {Backup.NONE, Backup.SAF, Backup.WEBDAV, Backup.S3_, Backup.FTP};
    private static final String[] TYPE_LABELS = {"Ninguno", "Archivo", "WebDAV", "S3 / OBS", "FTP"};

    private void showBackupSettings() {
        if (backupDraft == null) backupDraft = new LinkedHashMap<>(store.backupConfig());
        onHome = false;
        scr = NavajaUi.screen(this, "Respaldo del historial", "Tú eliges dónde · sólo resúmenes por defecto",
                NavajaUi.headerAction(this, "Inicio", v -> {
                    backupDraft = null;
                    showHome();
                }), true);
        LinearLayout c = scr.content;
        LinearLayout tabs = new LinearLayout(this);
        int cur = Math.max(0, java.util.Arrays.asList(TYPES).indexOf(backupDraft.getOrDefault("type", Backup.NONE)));
        NavajaUi.tabs(tabs, TYPE_LABELS, cur, i -> {
            collect();
            backupDraft.put("type", TYPES[i]);
            showBackupSettings();
        });
        c.addView(tabs);
        fields.clear();
        String type = TYPES[cur];
        LinearLayout card = NavajaUi.card(this);
        switch (type) {
            case Backup.SAF:
                card.addView(body("Un archivo en cualquier lugar que muestre el selector de Android: almacenamiento del teléfono, "
                        + "memoria USB, Google Drive, OneDrive, Dropbox, o servidores FTP/SFTP/SMB/NFS a través de apps de archivos "
                        + "que se integran al selector. Se reescribe con el historial completo."));
                String u = backupDraft.get("uri");
                card.addView(NavajaUi.text(this, u == null ? "Sin archivo elegido" : "Archivo: " + Uri.parse(u).getLastPathSegment(),
                        NavajaUi.Style.MONO));
                card.addView(NavajaUi.buttonRow(this, NavajaUi.secondary(this, "Elegir archivo…", v -> {
                    collect();
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, "ssjf-historial.jsonl");
                    startActivityForResult(i, REQ_BACKUP_FILE);
                })));
                break;
            case Backup.WEBDAV:
                card.addView(body("Nextcloud, ownCloud, Box, o tu NAS (Synology, QNAP, TrueNAS…: si exporta NFS o SMB, actívale "
                        + "también WebDAV). Ejemplo Nextcloud: https://nube.ejemplo.com/remote.php/dav/files/usuario/firmas"));
                field(card, "url", "URL de la carpeta", false);
                field(card, "user", "Usuario", false);
                field(card, "pass", "Contraseña o token de app", true);
                break;
            case Backup.S3_:
                card.addView(body("Cualquier almacenamiento de objetos compatible con S3: Amazon S3, Huawei Cloud OBS, MinIO, "
                        + "Cloudflare R2, Backblaze B2, Wasabi… Usa una clave de acceso limitada a ese bucket."));
                field(card, "endpoint", "Endpoint (https://obs.sa-peru-1.myhuaweicloud.com)", false);
                field(card, "region", "Región (sa-peru-1, us-east-1…)", false);
                field(card, "bucket", "Bucket", false);
                field(card, "prefix", "Carpeta dentro del bucket (opcional)", false);
                field(card, "access", "Access key", false);
                field(card, "secret", "Secret key", true);
                check(card, "pathStyle", "Estilo de ruta (endpoint/bucket): MinIO y similares");
                break;
            case Backup.FTP:
                card.addView(body("Servidor FTP propio. Sin TLS la contraseña viaja en claro: úsalo sólo dentro de tu red. "
                        + "Para SFTP o SMB usa «Archivo» con una app que los integre al selector."));
                field(card, "host", "Servidor", false);
                field(card, "port", "Puerto (21)", false);
                field(card, "user", "Usuario", false);
                field(card, "pass", "Contraseña", true);
                field(card, "dir", "Carpeta (opcional)", false);
                check(card, "tls", "FTPS explícito (AUTH TLS)");
                break;
            default:
                card.addView(body("El historial queda sólo en este teléfono (excluido del respaldo automático de Google). "
                        + "Si pierdes el teléfono pierdes el historial: la firma seguirá siendo verificable, pero no podrás "
                        + "decir a qué documento corresponde. Recomendado: configura un respaldo."));
        }
        if (!Backup.NONE.equals(type)) {
            check(card, "private", "Incluir datos privados (nombre de archivo, ubicación, comentario). Si no, sólo resúmenes "
                    + "y el compromiso que permite demostrarlos después.");
        }
        c.addView(card);
        c.addView(NavajaUi.buttonRow(this,
                NavajaUi.secondary(this, "Probar", v -> {
                    collect();
                    testBackup(new LinkedHashMap<>(backupDraft));
                }),
                NavajaUi.primary(this, "Guardar", v -> {
                    collect();
                    try {
                        store.saveBackupConfig(backupDraft);
                        backupDraft = null;
                        toast("Respaldo guardado");
                        showHome();
                    } catch (Exception e) {
                        error("No se pudo guardar: " + e.getMessage());
                    }
                })));
        TextView t = NavajaUi.text(this, "Las contraseñas se guardan cifradas con una clave del Keystore de este teléfono.",
                NavajaUi.Style.MUTED);
        NavajaUi.topMargin(t, 8);
        c.addView(t);
        setContentView(scr.root);
    }

    private final Map<String, View> fields = new LinkedHashMap<>();

    private void field(LinearLayout card, String key, String hint, boolean secret) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextColor(Palette.TEXT);
        e.setHintTextColor(Palette.TEXT_3);
        e.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        String v = backupDraft.get(key);
        if (v != null) e.setText(v);
        card.addView(e);
        fields.put(key, e);
    }

    private void check(LinearLayout card, String key, String label) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextColor(Palette.TEXT_2);
        cb.setChecked("true".equals(backupDraft.get(key)));
        card.addView(cb);
        fields.put(key, cb);
    }

    private void collect() {
        for (Map.Entry<String, View> f : fields.entrySet()) {
            View v = f.getValue();
            if (v instanceof CheckBox) backupDraft.put(f.getKey(), ((CheckBox) v).isChecked() ? "true" : "false");
            else if (v instanceof EditText) backupDraft.put(f.getKey(), ((EditText) v).getText().toString().trim());
        }
    }

    private void testBackup(Map<String, String> cfg) {
        Backend b = Backup.create(this, cfg);
        if (b == null) {
            toast("Elige un destino primero");
            return;
        }
        busy("Probando " + b.describe() + "…");
        work.execute(() -> {
            try {
                Backup.upload(b, store.loadLedger(), "true".equals(cfg.get("private")));
                ui.post(() -> {
                    dismissBusy();
                    new AlertDialog.Builder(this).setTitle("✓ Funciona").setMessage("Se subió el historial a " + b.describe() + ".")
                            .setPositiveButton("OK", null).show();
                });
            } catch (IOException | RuntimeException e) {
                ui.post(() -> error("No funcionó: " + e.getMessage()));
            }
        });
    }

    // ============================================================ utilidades

    private AlertDialog busyDialog;

    private void busy(String msg) {
        dismissBusy();
        busyDialog = new AlertDialog.Builder(this).setMessage(msg).setCancelable(false).show();
    }

    private void dismissBusy() {
        if (busyDialog != null && busyDialog.isShowing()) busyDialog.dismiss();
        busyDialog = null;
    }

    private void error(String msg) {
        dismissBusy();
        new AlertDialog.Builder(this).setTitle("Firma").setMessage(msg).setPositiveButton("OK", null).show();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private TextView title(String s, int color) {
        TextView t = NavajaUi.text(this, s, NavajaUi.Style.SUBTITLE);
        t.setTextColor(color);
        NavajaUi.topMargin(t, 4);
        return t;
    }

    private TextView body(String s) {
        TextView t = NavajaUi.text(this, s, NavajaUi.Style.BODY);
        NavajaUi.topMargin(t, 4);
        return t;
    }

    private TextView mono(String s) {
        TextView t = NavajaUi.text(this, s, NavajaUi.Style.MONO);
        t.setTextSize(11);
        t.setTextIsSelectable(true);
        NavajaUi.topMargin(t, 6);
        return t;
    }

    private static String local(long unix) {
        return new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(new Date(unix * 1000));
    }

    private void shareText(String subject, String text) {
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject)
                .putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(i, subject));
    }
}
