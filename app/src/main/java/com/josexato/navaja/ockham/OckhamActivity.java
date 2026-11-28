package com.josexato.navaja.ockham;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.josexato.navaja.ockham.core.Http;
import com.josexato.navaja.ockham.core.OckhamClient;
import com.josexato.navaja.ockham.core.OckhamException;
import com.josexato.navaja.ockham.core.Session;
import com.josexato.navaja.ui.NavajaUi;
import com.josexato.navaja.ui.Palette;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ockham: la parte de Navaja que usa un servidor. Aquí se elige el servidor, se entra (con una invitación la primera
 * vez), se ve el perfil y se manda un archivo a una receta del servidor para recibir su veredicto.
 *
 * <p>Es la única pantalla de Navaja que sale a internet por cuenta de Ockham, y sólo si el interruptor del inicio
 * está encendido.
 */
public final class OckhamActivity extends Activity {

    private static final String TAG = "Ockham";
    private static final int REQ_FILE = 71;
    /** La receta que esta versión sabe usar: recibe un archivo y devuelve su tamaño y su huella. */
    private static final String RECIPE = "eco";

    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private OckhamClient client;
    private NavajaUi.Screen screen;
    private TextView status;
    private boolean busy, invite;

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

        TextView[] tabs = NavajaUi.tabs(card, new String[]{"Entrar", "Tengo una invitación"}, invite ? 1 : 0, i -> {
            invite = i == 1;
            render();
        });
        NavajaUi.topMargin((View) tabs[0].getParent(), 12);

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

    private TextView profileText, verdictText;
    private Button sendBtn;

    private void renderSignedIn(LinearLayout root) {
        LinearLayout card = NavajaUi.card(this);
        card.addView(NavajaUi.text(this, "Tu perfil", NavajaUi.Style.LABEL));
        profileText = NavajaUi.text(this, client.session.email, NavajaUi.Style.BODY);
        card.addView(profileText);
        root.addView(card);

        LinearLayout send = NavajaUi.card(this);
        NavajaUi.topMargin(send, 12);
        send.addView(NavajaUi.text(this, "Probar el servidor", NavajaUi.Style.LABEL));
        send.addView(NavajaUi.text(this, "Elige un archivo (por ejemplo un escaneo guardado). Navaja lo sube, le pide al "
                + "servidor que lo revise y muestra lo que respondió.", NavajaUi.Style.SECONDARY));
        sendBtn = NavajaUi.primary(this, "Elegir un archivo y enviarlo", v -> pickFile());
        NavajaUi.topMargin(sendBtn, 10);
        send.addView(sendBtn);
        verdictText = NavajaUi.text(this, "", NavajaUi.Style.MONO);
        verdictText.setTextIsSelectable(true);
        verdictText.setVisibility(View.GONE);
        NavajaUi.topMargin(verdictText, 10);
        send.addView(verdictText);
        root.addView(send);

        Button out = NavajaUi.secondary(this, "Cerrar sesión", v -> run("Cerrando la sesión…", client::signOut, this::render));
        NavajaUi.topMargin(out, 12);
        root.addView(out);
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
            String error = null;
            try {
                task.run();
            } catch (OckhamException e) {
                error = e.getMessage();
            } catch (IOException e) {
                Log.w(TAG, doing, e);
                error = "No se pudo llegar al servidor. Revisar la conexión y la dirección.";
            } catch (RuntimeException e) {
                Log.w(TAG, doing, e);
                error = "Error inesperado: " + e;
            }
            final String problem = error;
            runOnUiThread(() -> {
                busy = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (isFinishing() || isDestroyed()) return;
                if (problem == null) {
                    say("", false);
                    then.run();
                } else if (!client.session.signedIn() && profileText != null && profileText.isAttachedToWindow()) {
                    render(); // la sesión venció mientras se trabajaba: vuelve a la pantalla de entrar
                    say(problem, true);
                } else {
                    say(problem, true);
                }
            });
        });
    }

    private void loadProfile() {
        final OckhamClient.Profile[] p = new OckhamClient.Profile[1];
        run("Leyendo tu perfil…", () -> p[0] = client.profile(), () -> {
            if (profileText == null) return;
            boolean canTry = p[0].capabilities.contains(RECIPE);
            profileText.setText(p[0].email + "\nRol: " + p[0].role + " · hasta " + p[0].jobsPerDay + " trabajos por día\n"
                    + "Puedes usar: " + (p[0].capabilities.isEmpty() ? "ninguna receta todavía" : String.join(", ", p[0].capabilities)));
            sendBtn.setEnabled(canTry);
            sendBtn.setAlpha(canTry ? 1f : 0.45f);
            if (!canTry) say("Este servidor no tiene habilitada la receta de prueba «" + RECIPE + "».", false);
        });
    }

    // ================================================================= mandar un archivo

    private void pickFile() {
        if (busy) return;
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_FILE);
        } catch (RuntimeException e) {
            say("No hay una app para elegir archivos.", true);
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQ_FILE || result != RESULT_OK || data == null || data.getData() == null) return;
        send(data.getData());
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
        final String fileName = name;
        final long total = size;
        final OckhamClient.Job[] job = new OckhamClient.Job[1];
        verdictText.setVisibility(View.GONE);
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
            String id = OckhamClient.newJobId();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("nota", "desde Navaja");
            job[0] = client.submit(id, RECIPE, params, Collections.singletonList(path));
            for (int i = 0; i < 240 && !job[0].done; i++) { // hasta dos minutos
                final String state = job[0].state;
                runOnUiThread(() -> say("El servidor está trabajando (" + state + ")…", false));
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    throw new IOException("interrumpido");
                }
                job[0] = client.job(id);
            }
        }, () -> {
            OckhamClient.Job j = job[0];
            verdictText.setVisibility(View.VISIBLE);
            if (j.succeeded() && j.verdict != null) {
                verdictText.setText(j.verdict.text());
                say("Listo.", false);
            } else if (!j.done) {
                verdictText.setText("El trabajo sigue en el servidor (" + j.state + ").");
                say("El servidor tarda más de lo esperado.", true);
            } else {
                verdictText.setText("El trabajo no terminó bien: " + j.state + (j.reason.isEmpty() ? "" : "\n" + j.reason));
                say("El servidor no pudo con el trabajo.", true);
            }
        });
    }
}
