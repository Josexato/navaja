import com.josexato.navaja.ockham.core.Form;
import com.josexato.navaja.ockham.core.Http;
import com.josexato.navaja.ockham.core.OckhamClient;
import com.josexato.navaja.ockham.core.Session;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Corre el cliente de Ockham de Navaja, con su transporte real, contra un servidor de verdad: lo mismo que hará el
 * teléfono, desde una PC. Sirve para comprobar que la app y el servidor hablan el mismo protocolo.
 *
 * Uso:  java OckhamHarness https://servidor correo codigo-de-invitacion contraseña-nueva
 */
public final class OckhamHarness {

    public static void main(String[] a) throws Exception {
        String server = a[0], email = a[1], code = a[2], password = a[3];
        Session session = new Session();
        final String[] saved = {""};
        OckhamClient c = new OckhamClient(new Http.Url(), session, s -> saved[0] = s.toText());

        c.connect(server);
        say("conectado a " + session.server + " (depósito «" + session.bucket + "»)");
        c.redeemInvite(email, code, password);
        say("invitación usada; usuario " + session.userId.substring(0, 8) + "…");

        OckhamClient.Profile p = c.profile();
        say("perfil: rol " + p.role + ", " + p.jobsPerDay + " trabajos por día, capacidades " + p.capabilities);
        for (OckhamClient.Recipe r : c.recipes()) say("receta: " + r.name + " — " + r.title + " (entradas " + r.minInputs + "–" + r.maxInputs + ")");

        byte[] data = new byte[13 * 1024 * 1024 + 321]; // tres partes
        new Random(1).nextBytes(data);
        String hash = hex(MessageDigest.getInstance("SHA-256").digest(data));
        final long[] sent = {0};
        String path = c.upload("captura de prueba.bin", "application/octet-stream", new OckhamClient.Source() {
            @Override
            public InputStream open() { return new ByteArrayInputStream(data); }

            @Override
            public long size() { return data.length; }
        }, n -> sent[0] = n);
        check("subida completa (" + sent[0] + " bytes) a " + path.substring(37), sent[0] == data.length);

        String id = OckhamClient.newJobId();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("nota", "desde el cliente de Navaja");
        OckhamClient.Job j = c.submit(id, "eco", params, Collections.singletonList(path));
        check("trabajo pedido: " + j.state, !j.id.isEmpty());
        c.submit(id, "eco", params, Collections.singletonList(path)); // repetir no duplica
        for (int i = 0; i < 40 && !j.done; i++) {
            Thread.sleep(500);
            j = c.job(id);
        }
        check("trabajo terminado: " + j.state, j.succeeded());
        check("el servidor leyó el mismo archivo", hash.equals(String.valueOf(j.verdict.chosen.get("sha256"))));
        System.out.println("\n" + j.verdict.text());

        // Bajar el archivo de resultado, comprobando su huella.
        java.io.ByteArrayOutputStream informe = new java.io.ByteArrayOutputStream();
        c.download(j.verdict.files.get(0), informe, null);
        check("resultado bajado y comprobado (" + informe.size() + " bytes)", informe.toString("UTF-8").contains(hash));

        // Si el servidor ofrece la receta de Runpod de prueba: el formulario, un trabajo completo y una cancelación.
        for (OckhamClient.Recipe r : c.recipes()) {
            if (!r.name.equals("eco-gpu")) continue;
            java.util.List<Form.Field> campos = Form.of(r.parameters);
            Map<String, String> escrito = new LinkedHashMap<>();
            escrito.put("nota", "con formulario");
            OckhamClient.Job g = c.submit(OckhamClient.newJobId(), "eco-gpu", Form.values(campos, escrito), Collections.singletonList(path));
            for (int i = 0; i < 120 && !g.done; i++) {
                Thread.sleep(1000);
                g = c.job(g.id);
            }
            check("trabajo en Runpod terminado: " + g.state, g.succeeded() && hash.equals(String.valueOf(g.verdict.chosen.get("sha256"))));
            escrito.put("espera_s", "40");
            OckhamClient.Job lento = c.submit(OckhamClient.newJobId(), "eco-gpu", Form.values(campos, escrito), Collections.singletonList(path));
            for (int i = 0; i < 30 && !lento.state.equals("running") && !lento.state.equals("starting"); i++) {
                Thread.sleep(1000);
                lento = c.job(lento.id);
            }
            c.cancel(lento.id);
            for (int i = 0; i < 30 && !lento.done; i++) {
                Thread.sleep(1000);
                lento = c.job(lento.id);
            }
            check("trabajo en Runpod cancelado: " + lento.state, lento.state.equals("cancelled"));
        }

        // La sesión guardada sirve para seguir después de cerrar la app, y se renueva cuando vence.
        OckhamClient later = new OckhamClient(new Http.Url(), Session.fromText(saved[0]), s -> saved[0] = s.toText());
        later.session.expiresAtMs = 0;
        String before = later.session.refresh;
        check("con la sesión guardada se renueva y se lee el perfil", later.profile().userId.equals(session.userId)
                && !later.session.refresh.equals(before));
        later.signOut();
        c.session.signOut();
        c.signIn(email, password);
        check("entra con correo y contraseña", c.session.signedIn());
        try {
            c.signIn(email, "otra-clave-equivocada");
            check("una contraseña equivocada no entra", false);
        } catch (Exception e) {
            say("contraseña equivocada → " + e.getMessage());
        }
        say("TODO BIEN");
    }

    private static void say(String s) { System.out.println("  " + s); }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  bien  " : "  MAL   ") + what);
        if (!ok) System.exit(1);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
