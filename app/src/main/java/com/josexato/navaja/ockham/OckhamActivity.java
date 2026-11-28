package com.josexato.navaja.ockham;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.josexato.navaja.ockham.core.Form;
import com.josexato.navaja.ockham.core.Http;
import com.josexato.navaja.ockham.core.OckhamClient;
import com.josexato.navaja.ockham.core.OckhamException;
import com.josexato.navaja.ockham.core.Session;
import com.josexato.navaja.ockham.core.Verdict;
import com.josexato.navaja.ui.NavajaUi;
import com.josexato.navaja.ui.Palette;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ockham: la parte de Navaja que usa un servidor. Aquí se elige el servidor, se entra (con una invitación la primera
 * vez), se ve el perfil, se elige una receta del servidor, se le manda un archivo y se recibe su veredicto y sus
 * archivos. Un trabajo largo sigue en el servidor aunque se cierre esta pantalla: al volver, se retoma.
 *
 * <p>Es la única pantalla de Navaja que sale a internet por cuenta de Ockham, y sólo si el interruptor del inicio
 * está encendido.
 */
public final class OckhamActivity extends Activity {

    private static final String TAG = "Ockham";
    private static final int REQ_FILE = 71, REQ_SAVE = 72;
    /** Cada cuánto se le pregunta al servidor por un trabajo que está corriendo. */
    private static final long POLL_MS = 4000;

    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private OckhamClient client;
    private NavajaUi.Screen screen;
    private TextView status;
    private boolean busy, invite, visible;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Una tarea que habla con el servidor. */
    private interface Task {
        void run() throws OckhamException, IOException;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Vault.enabled(this)) { // sólo se llega aquí con el interruptor encendido
            finish();
            return;
        }
        client = new OckhamClient(new Http.Url(), Vault.load(this), s -> Vault.save(this, s));
        screen = NavajaUi.screen(this, "Ockham", "", null, true);
        setContentView(screen.root);
        render();
        if (client.session.signedIn()) loadProfile();
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        pollSoon(0);
    }

    @Override
    protected void onPause() {
        visible = false;
        handler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        work.shutdownNow();
        super.onDestroy();
    }

    // ================================================================= lo que se ve

    private void render() {
        Session s = client.session;
        screen.setSubtitle(s.server.isEmpty() ? "sin servidor" : s.server.replace("https://", ""));
        LinearLayout root = screen.content;
        root.removeAllViews();
        status = NavajaUi.text(this, "", NavajaUi.Style.SECONDARY);
        if (s.signedIn()) renderSignedIn(root);
        else renderSignIn(root);
        NavajaUi.topMargin(status, 12);
        root.addView(status);
    }

    private void renderSignIn(LinearLayout root) {
        Session s = client.session;
        LinearLayout card = NavajaUi.card(this);
        card.addView(NavajaUi.text(this, "Servidor", NavajaUi.Style.LABEL));
        EditText server = field("ockham.ejemplo.com", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(s.server.replace("https://", ""));
        card.addView(server);

        // Las dos pestañas van en una fila propia: NavajaUi.tabs reparte el ancho de la fila que recibe.
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        NavajaUi.tabs(tabs, new String[]{"Entrar", "Tengo una invitación"}, invite ? 1 : 0, i -> {
            invite = i == 1;
            render();
        });
        card.addView(tabs, new LinearLayout.LayoutParams(-1, -2));
        NavajaUi.topMargin(tabs, 12);

        card.addView(label("Correo"));
        EditText email = field("tu@correo.com", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        email.setText(s.email);
        card.addView(email);
        EditText code = null;
        if (invite) {
            card.addView(label("Código de invitación"));
            code = field("el que te dio quien administra el servidor", InputType.TYPE_CLASS_NUMBER);
            card.addView(code);
        }
        card.addView(label(invite ? "Contraseña nueva (12 caracteres o más)" : "Contraseña"));
        EditText password = field("", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        card.addView(password);
        root.addView(card);

        final EditText codeField = code;
        Button go = NavajaUi.primary(this, invite ? "Usar la invitación" : "Entrar", v -> {
            String address = server.getText().toString(), mail = email.getText().toString();
            String pass = password.getText().toString(), otp = codeField == null ? "" : codeField.getText().toString();
            if (mail.trim().isEmpty() || pass.isEmpty() || (invite && otp.trim().isEmpty())) {
                say("Faltan datos.", true);
                return;
            }
            if (invite && pass.length() < 12) {
                say("La contraseña tiene que tener 12 caracteres o más.", true);
                return;
            }
            run("Conectando con el servidor…", () -> {
                client.connect(address);
                if (invite) client.redeemInvite(mail, otp, pass);
                else client.signIn(mail, pass);
            }, () -> {
                invite = false;
                render();
                loadProfile();
            });
        });
        NavajaUi.topMargin(go, 12);
        root.addView(go);
        TextView note = NavajaUi.text(this, "Ockham manda al servidor sólo lo que elijas enviar desde esta pantalla. "
                + "El servidor es de quien te invitó: esa persona puede ver lo que subas.", NavajaUi.Style.MUTED);
        NavajaUi.topMargin(note, 12);
        root.addView(note);
    }

    private OckhamClient.Profile profile;
    private List<OckhamClient.Recipe> recipes = Collections.emptyList();
    private int picked;
    private List<Form.Field> fields = Collections.emptyList();
    /** Lo que se eligió en los campos que no son de escribir (opciones y sí o no), por nombre. */
    private final Map<String, String> choices = new LinkedHashMap<>();
    private final Map<String, EditText> inputs = new LinkedHashMap<>();
    private OckhamClient.Job job;
    private LinearLayout jobBox;
    private Verdict.File saving;

    private void renderSignedIn(LinearLayout root) {
        LinearLayout card = NavajaUi.card(this);
        card.addView(NavajaUi.text(this, "Tu perfil", NavajaUi.Style.LABEL));
        String who = client.session.email;
        if (profile != null) {
            who = profile.email + "\nRol: " + profile.role + " · hasta " + profile.jobsPerDay + " trabajos por día"
                    + (profile.gpuSecondsPerDay > 0 ? " · " + profile.gpuSecondsPerDay / 60 + " min de GPU por día" : "");
        }
        card.addView(NavajaUi.text(this, who, NavajaUi.Style.BODY));
        root.addView(card);

        LinearLayout send = NavajaUi.card(this);
        NavajaUi.topMargin(send, 12);
        send.addView(NavajaUi.text(this, "Pedir un trabajo", NavajaUi.Style.LABEL));
        if (recipes.isEmpty()) {
            send.addView(NavajaUi.text(this, profile == null ? "Leyendo lo que ofrece el servidor…"
                    : "Este servidor todavía no te habilitó ninguna receta.", NavajaUi.Style.SECONDARY));
        } else {
            renderRecipe(send);
        }
        root.addView(send);

        jobBox = NavajaUi.card(this);
        NavajaUi.topMargin(jobBox, 12);
        root.addView(jobBox);
        showJob();

        Button out = NavajaUi.secondary(this, "Cerrar sesión", v -> run("Cerrando la sesión…", () -> {
            client.signOut();
            Vault.setPendingJob(this, "");
        }, () -> {
            profile = null;
            recipes = Collections.emptyList();
            job = null;
            render();
        }));
        NavajaUi.topMargin(out, 12);
        root.addView(out);
    }

    /** La receta elegida: cuál, qué hace y sus parámetros. */
    private void renderRecipe(LinearLayout send) {
        if (picked >= recipes.size()) picked = 0;
        OckhamClient.Recipe r = recipes.get(picked);
        if (recipes.size() > 1) {
            LinearLayout row = new LinearLayout(this);
            for (int i = 0; i < recipes.size(); i++) {
                final int idx = i;
                row.addView(NavajaUi.chip(this, recipes.get(i).title, i == picked, v -> {
                    if (idx == picked) return;
                    picked = idx;
                    choices.clear();
                    render();
                }));
            }
            send.addView(scroll(row));
        } else {
            send.addView(NavajaUi.text(this, r.title, NavajaUi.Style.BODY));
        }
        TextView what = NavajaUi.text(this, r.description, NavajaUi.Style.SECONDARY);
        NavajaUi.topMargin(what, 8);
        send.addView(what);

        fields = Form.of(r.parameters);
        inputs.clear();
        for (Form.Field f : fields) {
            send.addView(label(f.label()));
            if (!f.description.isEmpty()) send.addView(NavajaUi.text(this, f.description, NavajaUi.Style.MUTED));
            if (f.kind == Form.Kind.CHOICE || f.kind == Form.Kind.BOOLEAN) {
                // «por omisión» no manda nada: decide el servidor.
                List<String> values = new ArrayList<>(), labels = new ArrayList<>();
                values.add("");
                labels.add(f.kind == Form.Kind.BOOLEAN ? "no" : "por omisión");
                if (f.kind == Form.Kind.BOOLEAN) {
                    values.add("true");
                    labels.add("sí");
                } else {
                    values.addAll(f.options);
                    labels.addAll(f.options);
                }
                String now = choices.containsKey(f.name) ? choices.get(f.name) : "";
                LinearLayout row = new LinearLayout(this);
                final TextView[] chips = new TextView[values.size()];
                for (int i = 0; i < values.size(); i++) {
                    final int idx = i;
                    chips[i] = NavajaUi.chip(this, labels.get(i), values.get(i).equals(now), v -> {
                        choices.put(f.name, values.get(idx));
                        for (int k = 0; k < chips.length; k++) NavajaUi.setChip(chips[k], k == idx);
                    });
                    row.addView(chips[i]);
                }
                View sc = scroll(row);
                NavajaUi.topMargin(sc, 4);
                send.addView(sc);
            } else {
                int type = f.kind == Form.Kind.TEXT ? InputType.TYPE_CLASS_TEXT
                        : f.kind == Form.Kind.INTEGER ? InputType.TYPE_CLASS_NUMBER
                        : InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
                EditText e = field("por omisión", type);
                inputs.put(f.name, e);
                send.addView(e);
            }
        }
        Button go = NavajaUi.primary(this, "Elegir un archivo y enviarlo", v -> pickFile());
        NavajaUi.topMargin(go, 12);
        send.addView(go);
        if (r.maxSeconds > 120) {
            TextView note = NavajaUi.text(this, "Puede tardar hasta " + r.maxSeconds / 60 + " minutos. Después de subir el "
                    + "archivo se puede cerrar Navaja: el trabajo sigue en el servidor y aquí se retoma.", NavajaUi.Style.MUTED);
            NavajaUi.topMargin(note, 8);
            send.addView(note);
        }
    }

    private View scroll(LinearLayout row) {
        row.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView sc = new HorizontalScrollView(this);
        sc.setHorizontalScrollBarEnabled(false);
        sc.addView(row);
        return sc;
    }

    /** Lo que la persona puso en el formulario, comprobado contra la receta. */
    private Map<String, Object> parameters() throws OckhamException {
        Map<String, String> raw = new LinkedHashMap<>(choices);
        for (Map.Entry<String, EditText> e : inputs.entrySet()) raw.put(e.getKey(), e.getValue().getText().toString());
        return Form.values(fields, raw);
    }

    private TextView label(String s) {
        TextView t = NavajaUi.text(this, s, NavajaUi.Style.LABEL);
        NavajaUi.topMargin(t, 10);
        return t;
    }

    private EditText field(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setTextColor(Palette.TEXT);
        e.setHintTextColor(Palette.TEXT_3);
        e.setTextSize(15);
        return e;
    }

    private void say(String text, boolean error) {
        if (status == null) return;
        status.setText(text);
        status.setTextColor(error ? Palette.BAD : Palette.TEXT_2);
    }

    // ================================================================= hablar con el servidor

    /** Corre una tarea fuera del hilo de la pantalla; al terminar bien llama a {@code then} en el hilo de la pantalla. */
    private void run(String doing, Task task, Runnable then) {
        if (busy) return;
        busy = true;
        say(doing, false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        work.execute(() -> {
            final String problem = attempt(doing, task);
            runOnUiThread(() -> {
                busy = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (isFinishing() || isDestroyed()) return;
                if (problem == null) {
                    say("", false);
                    then.run();
                } else if (!client.session.signedIn() && jobBox != null && jobBox.isAttachedToWindow()) {
                    render(); // la sesión venció mientras se trabajaba: vuelve a la pantalla de entrar
                    say(problem, true);
                } else {
                    say(problem, true);
                }
            });
        });
    }

    /** Corre la tarea y devuelve el problema en palabras, o null si salió bien. */
    private String attempt(String doing, Task task) {
        try {
            task.run();
            return null;
        } catch (OckhamException e) {
            return e.getMessage();
        } catch (IOException e) {
            Log.w(TAG, doing, e);
            return "No se pudo llegar al servidor. Revisar la conexión y la dirección.";
        } catch (RuntimeException e) {
            Log.w(TAG, doing, e);
            return "Error inesperado: " + e;
        }
    }

    private void loadProfile() {
        final OckhamClient.Profile[] p = new OckhamClient.Profile[1];
        final List<OckhamClient.Recipe> list = new ArrayList<>();
        final OckhamClient.Job[] pending = new OckhamClient.Job[1];
        final String id = Vault.pendingJob(this);
        run("Leyendo tu perfil…", () -> {
            p[0] = client.profile();
            list.addAll(client.recipes());
            if (!id.isEmpty()) {
                try {
                    pending[0] = client.job(id);
                } catch (OckhamException e) {
                    if (e.code != 404) throw e; // un trabajo que el servidor ya no tiene se olvida
                }
            }
        }, () -> {
            profile = p[0];
            recipes = list;
            job = pending[0];
            if (job == null) Vault.setPendingJob(this, "");
            render();
            pollSoon(POLL_MS);
        });
    }

    // ================================================================= mandar un archivo

    private void pickFile() {
        if (busy) return;
        if (job != null && !job.done) {
            say("Hay un trabajo en curso. Esperar a que termine o cancelarlo.", true);
            return;
        }
        try {
            parameters(); // avisa de un parámetro mal escrito antes de elegir y subir el archivo
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_FILE);
        } catch (OckhamException e) {
            say(e.getMessage(), true);
        } catch (RuntimeException e) {
            say("No hay una app para elegir archivos.", true);
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == REQ_FILE) send(data.getData());
        else if (request == REQ_SAVE && saving != null) save(saving, data.getData());
    }

    private void send(final Uri uri) {
        String name = "archivo";
        long size = -1;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                if (!c.isNull(0)) name = c.getString(0);
                if (!c.isNull(1)) size = c.getLong(1);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "datos del archivo", e);
        }
        if (size <= 0) {
            say("No se pudo saber el tamaño de ese archivo. Probar con uno guardado en el teléfono.", true);
            return;
        }
        final Map<String, Object> params;
        try {
            params = parameters();
        } catch (OckhamException e) {
            say(e.getMessage(), true);
            return;
        }
        final String recipe = recipes.get(picked).name, fileName = name;
        final long total = size;
        final OckhamClient.Job[] asked = new OckhamClient.Job[1];
        run("Subiendo " + fileName + "…", () -> {
            final long[] shown = {-1};
            String path = client.upload(fileName, "application/octet-stream", new OckhamClient.Source() {
                @Override
                public InputStream open() throws IOException {
                    InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new FileNotFoundException(fileName);
                    return in;
                }

                @Override
                public long size() { return total; }
            }, sent -> {
                long pct = sent * 100 / total;
                if (pct == shown[0]) return;
                shown[0] = pct;
                runOnUiThread(() -> say(String.format(Locale.US, "Subiendo %s… %d %% de %.1f MB", fileName, pct, total / 1048576.0), false));
            });
            runOnUiThread(() -> say("Archivo subido. Pidiendo el trabajo…", false));
            // El identificador se guarda antes de pedir: si la app se cierra justo ahora, al volver se retoma.
            String id = OckhamClient.newJobId();
            Vault.setPendingJob(this, id);
            asked[0] = client.submit(id, recipe, params, Collections.singletonList(path));
        }, () -> {
            job = asked[0];
            showJob();
            pollSoon(job.done ? 0 : 1000);
        });
    }

    // ================================================================= seguir un trabajo

    private static String state(String s) {
        switch (s) {
            case "queued": return "en cola";
            case "submitting": return "enviándose a la máquina que lo procesa";
            case "starting": return "la máquina está arrancando";
            case "running": return "procesando";
            case "collecting": return "recogiendo los resultados";
            case "cancelling": return "cancelando";
            case "succeeded": return "terminó bien";
            case "failed": return "falló";
            case "cancelled": return "cancelado";
            case "timed_out": return "pasó el tiempo máximo";
            default: return s;
        }
    }

    /** Vuelve a preguntar por el trabajo en curso dentro de un rato, si la pantalla está a la vista. */
    private void pollSoon(long delay) {
        handler.removeCallbacksAndMessages(null);
        if (!visible || job == null || job.done) return;
        handler.postDelayed(this::poll, delay);
    }

    private void poll() {
        if (job == null || job.done || !client.session.signedIn()) return;
        if (busy) { // se está subiendo o bajando algo: se pregunta después
            pollSoon(POLL_MS);
            return;
        }
        final String id = job.id;
        final OckhamClient.Job[] got = new OckhamClient.Job[1];
        work.execute(() -> {
            final String problem = attempt("consultar el trabajo", () -> got[0] = client.job(id));
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || job == null || !job.id.equals(id)) return;
                if (problem == null) {
                    job = got[0];
                    showJob();
                } else if (!client.session.signedIn()) {
                    render();
                    say(problem, true);
                    return;
                }
                pollSoon(POLL_MS); // si falló la red, se vuelve a intentar
            });
        });
    }

    private void showJob() {
        if (jobBox == null) return;
        jobBox.removeAllViews();
        jobBox.setVisibility(job == null ? View.GONE : View.VISIBLE);
        if (job == null) return;
        final OckhamClient.Job j = job;
        jobBox.addView(NavajaUi.text(this, "Trabajo · " + j.recipe, NavajaUi.Style.LABEL));
        String line = state(j.state).substring(0, 1).toUpperCase(Locale.ROOT) + state(j.state).substring(1);
        if (j.gpuSeconds > 0) line += String.format(Locale.US, " · %.0f s de GPU", j.gpuSeconds);
        TextView st = NavajaUi.text(this, line, NavajaUi.Style.BODY);
        if (j.done && !j.succeeded()) st.setTextColor(Palette.BAD);
        jobBox.addView(st);
        if (!j.done) {
            if (!"cancelling".equals(j.state)) {
                Button stop = NavajaUi.secondary(this, "Cancelar el trabajo", v -> {
                    final OckhamClient.Job[] got = new OckhamClient.Job[1];
                    run("Pidiendo la cancelación…", () -> got[0] = client.cancel(j.id), () -> {
                        job = got[0];
                        showJob();
                        pollSoon(1000);
                    });
                });
                NavajaUi.topMargin(stop, 10);
                jobBox.addView(stop);
            }
            return;
        }
        Vault.setPendingJob(this, ""); // ya se vio terminar
        if (!j.reason.isEmpty()) jobBox.addView(NavajaUi.text(this, j.reason, NavajaUi.Style.SECONDARY));
        if (!j.succeeded() || j.verdict == null) return;
        for (final Verdict.File f : j.verdict.files) {
            Button b = NavajaUi.secondary(this, String.format(Locale.US, "Guardar %s (%s)", f.name, size(f.bytes)), v -> pickWhere(f));
            NavajaUi.topMargin(b, 8);
            jobBox.addView(b);
        }
        TextView verdict = NavajaUi.text(this, j.verdict.text(), NavajaUi.Style.MONO);
        verdict.setTextIsSelectable(true);
        NavajaUi.topMargin(verdict, 10);
        jobBox.addView(verdict);
    }

    private static String size(long bytes) {
        return bytes < 1024 * 1024 ? String.format(Locale.US, "%.0f KB", Math.max(1, bytes / 1024.0))
                : String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    // ================================================================= guardar un resultado

    private void pickWhere(Verdict.File f) {
        if (busy) return;
        saving = f;
        String type = f.name.endsWith(".glb") ? "model/gltf-binary" : f.name.endsWith(".json") ? "application/json"
                : f.name.endsWith(".txt") ? "text/plain" : "application/octet-stream";
        try {
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(type).putExtra(Intent.EXTRA_TITLE, f.name), REQ_SAVE);
        } catch (RuntimeException e) {
            say("No hay una app para elegir dónde guardar.", true);
        }
    }

    private void save(final Verdict.File f, final Uri where) {
        final long total = Math.max(1, f.bytes);
        final boolean[] ok = {false};
        run("Bajando " + f.name + "…", () -> {
            try (OutputStream out = getContentResolver().openOutputStream(where)) {
                if (out == null) throw new IOException("no se pudo abrir el destino");
                final long[] got = {0}, shown = {-1};
                client.download(f, out, n -> {
                    got[0] += n;
                    long pct = Math.min(100, got[0] * 100 / total);
                    if (pct == shown[0]) return;
                    shown[0] = pct;
                    runOnUiThread(() -> say("Bajando " + f.name + "… " + pct + " %", false));
                });
                ok[0] = true;
            } finally {
                if (!ok[0]) { // no dejar a medias un archivo que parece completo
                    try {
                        DocumentsContract.deleteDocument(getContentResolver(), where);
                    } catch (Exception e) {
                        Log.w(TAG, "no se pudo borrar el archivo a medias", e);
                    }
                }
            }
        }, () -> say(f.name + " guardado.", false));
    }
}
