package com.josexato.navaja.ockham.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.josexato.navaja.pantonera.core.Json;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.Test;

/**
 * El cliente de Ockham contra un servidor de mentira (en memoria) que se comporta como el real en lo que importa: sesiones que
 * vencen, tokens de renovación que rotan, subida por partes que se puede cortar, y trabajos que tardan.
 */
public class OckhamClientTest {

    private final List<String> log = new ArrayList<>();
    private final List<Session> saved = new ArrayList<>();
    private long now = 1_000_000;

    // Estado del servidor de mentira
    private int protocol = 1;
    private String access = "acc-1", refresh = "ref-1";
    private int tokens = 1;
    private boolean expired;          // el servidor rechaza el credencial actual
    private ByteArrayOutputStream upload = new ByteArrayOutputStream();
    private long uploadLength;
    private String uploadMeta = "";
    private int cutAfterParts = -1;   // corta la conexión después de recibir tantas partes
    private int parts, polls;
    private String jobBody = "";

    private final String base = "https://ockham.prueba";
    private static final byte[] MESH = "una malla de mentira".getBytes(StandardCharsets.UTF_8);
    private static final String MESH_SHA = "60015644cfac619bc30899aa5c3ebdbe03d704aca42d6e245b73be9558f72243";

    private static Verdict.File mesh(String path, String sha) {
        Verdict.File f = new Verdict.File();
        f.name = "malla.glb";
        f.path = path;
        f.sha256 = sha;
        f.bytes = MESH.length;
        return f;
    }

    private OckhamClient client() {
        return new OckhamClient(this::handle, new Session(), s -> saved.add(Session.fromText(s.toText())), () -> now);
    }

    private OckhamClient signedIn() throws Exception {
        OckhamClient c = client();
        c.connect(base);
        c.signIn("ana@example.com", "una-clave-larga-1");
        return c;
    }

    // ------------------------------------------------------------------ servidor de mentira

    private static Http.Reply reply(int code, String body, String... header) {
        Map<String, String> h = new LinkedHashMap<>();
        for (int i = 0; i + 1 < header.length; i += 2) h.put(header[i].toLowerCase(java.util.Locale.ROOT), header[i + 1]);
        return new Http.Reply(code, h, body.getBytes(StandardCharsets.UTF_8));
    }

    private static Http.Reply problem(int code, String title) {
        return reply(code, "{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + code + "}");
    }

    private Http.Reply handle(String m, String url, Map<String, String> headers, byte[] body, InputStream stream, long length,
                              Http.Progress progress) throws IOException {
        assertTrue(url, url.startsWith(base + "/"));
        String rest = url.substring(base.length());
        int qm = rest.indexOf('?');
        String path = qm < 0 ? rest : rest.substring(0, qm), q = qm < 0 ? null : rest.substring(qm + 1);
        byte[] in = body != null ? body : new byte[0];
        if (stream != null && length >= 0) {
            in = new byte[(int) length];
            int got = 0;
            while (got < in.length) {
                int n = stream.read(in, got, in.length - got);
                if (n < 0) throw new IOException("corto");
                got += n;
                if (progress != null) progress.sent(n);
            }
        }
        String text = new String(in, StandardCharsets.UTF_8);
        log.add(m + " " + path + (q == null ? "" : "?" + q));
        boolean key = "llave-app".equals(headers.get("apikey"));
        boolean user = ("Bearer " + access).equals(headers.get("Authorization")) && !expired;

        if (path.equals("/ockham/v1/salud")) {
            return reply(200, "{\"servicio\":\"ockham\",\"version\":\"0.1.0\",\"protocolo\":" + protocol
                    + ",\"llave_app\":\"llave-app\",\"deposito\":\"capturas\"}");
        }
        if (path.equals("/auth/v1/token") && "grant_type=password".equals(q)) {
            if (!key) return reply(401, "{\"message\":\"No API key found in request\"}");
            if (text.contains("una-clave-larga-1")) return reply(200, sessionJson());
            return reply(400, "{\"code\":400,\"error_code\":\"invalid_credentials\",\"msg\":\"Invalid login credentials\"}");
        }
        if (path.equals("/auth/v1/token") && "grant_type=refresh_token".equals(q)) {
            if (!text.contains("\"" + refresh + "\"")) {
                return reply(400, "{\"code\":400,\"error_code\":\"refresh_token_already_used\",\"msg\":\"Invalid Refresh Token: Already Used\"}");
            }
            tokens++;
            access = "acc-" + tokens;
            refresh = "ref-" + tokens;
            expired = false;
            return reply(200, sessionJson());
        }
        if (path.equals("/auth/v1/verify")) {
            if (text.contains("\"123456\"")) return reply(200, sessionJson());
            return reply(403, "{\"code\":403,\"error_code\":\"otp_expired\",\"msg\":\"Email link is invalid or has expired\"}");
        }
        if (path.equals("/auth/v1/user") && m.equals("PUT")) {
            if (!user) return reply(401, "{}");
            if (text.length() < 30) return reply(422, "{\"error_code\":\"weak_password\",\"msg\":\"x\"}");
            return reply(200, "{}");
        }
        if (path.equals("/auth/v1/logout")) return reply(204, "");
        if (path.equals("/ockham/v1/perfil")) {
            if (!user) return problem(401, "El credencial venció");
            return reply(200, "{\"usuario\":\"u-1\",\"correo\":\"ana@example.com\",\"rol\":\"usuario\","
                    + "\"limites\":{\"trabajos_por_dia\":50},\"capacidades\":[\"eco\"]}");
        }
        if (path.equals("/ockham/v1/recetas")) {
            if (!user) return problem(401, "El credencial venció");
            return reply(200, "{\"recetas\":[{\"nombre\":\"eco\",\"titulo\":\"Eco (prueba)\",\"descripcion\":\"d\","
                    + "\"parametros\":{\"type\":\"object\"},\"entradas\":{\"minimo\":1,\"maximo\":1},\"salidas\":[\"informe.json\"],\"segundos_max\":60}]}");
        }
        if (path.equals("/storage/v1/upload/resumable") && m.equals("POST")) {
            if (!user) return reply(401, "{}");
            uploadLength = Long.parseLong(headers.get("Upload-Length"));
            uploadMeta = headers.get("Upload-Metadata");
            upload = new ByteArrayOutputStream();
            return reply(201, "", "Location", base + "/storage/v1/upload/resumable/abc");
        }
        if (path.equals("/storage/v1/upload/resumable/abc") && m.equals("HEAD")) {
            return reply(200, "", "Upload-Offset", Long.toString(upload.size()));
        }
        if (path.equals("/storage/v1/upload/resumable/abc") && m.equals("POST")) {
            assertEquals("PATCH", headers.get("X-HTTP-Method-Override"));
            if (!user) return reply(401, "{}");
            if (Long.parseLong(headers.get("Upload-Offset")) != upload.size()) return reply(409, "{}");
            parts++;
            if (parts == cutAfterParts) {
                // Llegó la mitad de la parte y se cortó la conexión.
                upload.write(in, 0, in.length / 2);
                throw new IOException("se cortó la conexión");
            }
            upload.write(in);
            return reply(204, "", "Upload-Offset", Long.toString(upload.size()));
        }
        if (path.equals("/storage/v1/object/authenticated/capturas/u-1/resultados/j1/malla.glb") && m.equals("GET")) {
            if (!user) return reply(401, "{}");
            return new Http.Reply(200, new LinkedHashMap<>(), MESH);
        }
        if (path.endsWith("/cancelar") && m.equals("POST")) {
            if (!user) return problem(401, "El credencial venció");
            return reply(200, jobJson(path.substring(20, path.length() - 9), "cancelling", false));
        }
        if (path.startsWith("/ockham/v1/trabajos/") && m.equals("PUT")) {
            if (!user) return problem(401, "El credencial venció");
            if (text.contains("no-existe")) {
                return reply(403, "{\"type\":\"about:blank\",\"title\":\"No tienes habilitada esa receta\",\"status\":403,"
                        + "\"detail\":\"Receta: no-existe.\"}");
            }
            boolean again = text.equals(jobBody);
            jobBody = text;
            return reply(again ? 200 : 202, jobJson(path.substring(20), "queued", false));
        }
        if (path.startsWith("/ockham/v1/trabajos/") && m.equals("GET")) {
            if (!user) return problem(401, "El credencial venció");
            polls++;
            return reply(200, jobJson(path.substring(20), polls < 3 ? "running" : "succeeded", polls >= 3));
        }
        return reply(404, "{}");
    }

    private String sessionJson() {
        return "{\"access_token\":\"" + access + "\",\"refresh_token\":\"" + refresh + "\",\"expires_in\":900,"
                + "\"token_type\":\"bearer\",\"user\":{\"id\":\"u-1\",\"email\":\"ana@example.com\"}}";
    }

    private static String jobJson(String id, String state, boolean done) {
        String result = done ? "{\"elegido\":{\"bytes\":8,\"sha256\":\"abc\"},\"porque\":\"Es una medición directa.\","
                + "\"supuestos\":[\"El archivo es el mismo.\"],\"alternativas\":[],"
                + "\"archivos\":[{\"nombre\":\"informe.json\",\"ruta\":\"u-1/resultados/" + id + "/informe.json\",\"bytes\":40,\"sha256\":\"def\"}]}" : "null";
        return "{\"id\":\"" + id + "\",\"receta\":\"eco\",\"estado\":\"" + state + "\",\"terminado\":" + done
                + ",\"motivo\":null,\"resultado\":" + result + "}";
    }

    private static String message(ThrowingRunnable r) {
        try {
            r.run();
        } catch (OckhamException e) {
            return e.getMessage();
        } catch (Exception e) {
            return "otra excepción: " + e;
        }
        fail("se esperaba un error");
        return null;
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static OckhamClient.Source source(byte[] data) {
        return new OckhamClient.Source() {
            @Override
            public InputStream open() { return new ByteArrayInputStream(data); }

            @Override
            public long size() { return data.length; }
        };
    }

    // ------------------------------------------------------------------ pruebas

    @Test
    public void addressIsCleanedAndMustBeHttps() throws Exception {
        assertEquals("https://ockham.ejemplo.com", OckhamClient.normalize("  Ockham.Ejemplo.com/ "));
        assertEquals("https://ockham.ejemplo.com", OckhamClient.normalize("https://ockham.ejemplo.com"));
        assertEquals("http://localhost:8080", OckhamClient.normalize("http://localhost:8080"));
        for (String bad : new String[]{"", "http://ockham.ejemplo.com", "ftp://x.com", "https://x.com/ruta", "https://usuario@x.com", "https://x.com?a=1"}) {
            assertNotNull(bad, message(() -> OckhamClient.normalize(bad)));
        }
    }

    @Test
    public void connectLearnsTheAppKeyAndChecksTheProtocol() throws Exception {
        OckhamClient c = client();
        c.connect(base + "/");
        assertEquals(base, c.session.server);
        assertEquals("llave-app", c.session.appKey);
        assertEquals("llave-app", saved.get(saved.size() - 1).appKey);
        protocol = 2;
        assertTrue(message(() -> client().connect(base)).contains("actualizar Navaja"));
    }

    @Test
    public void signInKeepsTheSessionAndWrongPasswordIsExplained() throws Exception {
        OckhamClient c = client();
        c.connect(base);
        assertEquals("El correo o la contraseña no coinciden.", message(() -> c.signIn("ana@example.com", "mala")));
        assertFalse(c.session.signedIn());
        c.signIn(" ana@example.com ", "una-clave-larga-1");
        assertTrue(c.session.signedIn());
        assertEquals("u-1", c.session.userId);
        assertEquals(now + 900_000, c.session.expiresAtMs);
        Session last = saved.get(saved.size() - 1);
        assertEquals("ref-1", last.refresh);
        assertEquals(c.session.expiresAtMs, last.expiresAtMs);
    }

    @Test
    public void inviteOpensASessionAndSetsThePassword() throws Exception {
        OckhamClient c = client();
        c.connect(base);
        assertTrue(message(() -> c.redeemInvite("ana@example.com", "000000", "una-clave-larga-1")).startsWith("El código de invitación no vale"));
        assertTrue(message(() -> c.redeemInvite("ana@example.com", "123456", "corta")).startsWith("La contraseña es muy corta"));
        c.redeemInvite("ana@example.com", " 123456 ", "una-clave-larga-1");
        assertTrue(c.session.signedIn());
        assertTrue(log.contains("PUT /auth/v1/user"));
    }

    @Test
    public void renewsBeforeExpiryAndAfterA401() throws Exception {
        OckhamClient c = signedIn();
        assertEquals("usuario", c.profile().role);
        assertEquals(1, tokens);
        // Falta menos de un minuto para que venza: renueva antes de pedir.
        now += 900_000 - 30_000;
        assertEquals(Collections.singletonList("eco"), c.profile().capabilities);
        assertEquals(2, tokens);
        assertEquals("ref-2", saved.get(saved.size() - 1).refresh);
        // El servidor lo rechaza aunque el reloj del teléfono diga que vale: renueva y repite.
        expired = true;
        assertEquals(50, c.profile().jobsPerDay);
        assertEquals(3, tokens);
    }

    @Test
    public void aRejectedRefreshEndsTheSession() throws Exception {
        OckhamClient c = signedIn();
        refresh = "otro"; // el servidor ya no reconoce el token del teléfono
        expired = true;
        assertEquals("La sesión venció. Hay que entrar de nuevo.", message(c::profile));
        assertFalse(c.session.signedIn());
        assertFalse(saved.get(saved.size() - 1).signedIn());
        assertEquals(base, c.session.server); // el servidor queda anotado para volver a entrar
        assertTrue(message(c::profile).contains("Hay que entrar"));
    }

    @Test
    public void uploadsInPartsOfSixMegabytes() throws Exception {
        OckhamClient c = signedIn();
        byte[] data = new byte[OckhamClient.CHUNK + 1000];
        new Random(7).nextBytes(data);
        long[] last = {0};
        String path = c.upload("mi captura (1).zip", "application/zip", source(data), n -> last[0] = n);
        assertEquals("u-1/mi_captura__1_.zip", path);
        assertArrayEquals(data, upload.toByteArray());
        assertEquals(2, parts);
        assertEquals(data.length, uploadLength);
        assertEquals(data.length, last[0]);
        assertTrue(uploadMeta, uploadMeta.contains("bucketName " + OckhamClient.b64("capturas")));
        assertTrue(uploadMeta, uploadMeta.contains("objectName " + OckhamClient.b64(path)));
    }

    @Test
    public void uploadResumesWhereTheServerSaysAfterACut() throws Exception {
        OckhamClient c = signedIn();
        byte[] data = new byte[2 * OckhamClient.CHUNK + 5000];
        new Random(8).nextBytes(data);
        cutAfterParts = 2; // la segunda parte llega a medias y se corta
        c.upload("captura.zip", "application/zip", source(data), null);
        assertArrayEquals(data, upload.toByteArray());
        assertTrue(log.contains("HEAD /storage/v1/upload/resumable/abc"));
    }

    @Test
    public void uploadRenewsTheSessionWhenItExpiresHalfway() throws Exception {
        OckhamClient c = signedIn();
        byte[] data = new byte[OckhamClient.CHUNK + 10];
        new Random(9).nextBytes(data);
        expired = true;
        message(() -> c.upload("a.bin", "application/octet-stream", source(data), null)); // el inicio se rechaza con 401
        expired = false;
        c.upload("a.bin", "application/octet-stream", source(data), null);
        assertArrayEquals(data, upload.toByteArray());
        assertTrue(message(() -> c.upload("vacio.bin", "x/y", source(new byte[0]), null)).contains("vacío"));
    }

    @Test
    public void safeNamesStayInsideTheUserFolder() {
        assertEquals("a_b.zip", OckhamClient.safeName("a/b.zip"));
        assertEquals("_.._.._etc_passwd", OckhamClient.safeName("/../../etc/passwd").replaceFirst("^_", "_"));
        assertFalse(OckhamClient.safeName("../../x").contains("/"));
        assertEquals("archivo", OckhamClient.safeName(""));
        assertEquals("archivo", OckhamClient.safeName("..."));
        assertEquals("torito_n__1.zip", OckhamClient.safeName("torito nº 1.zip"));
        assertEquals(120, OckhamClient.safeName(String.join("", Collections.nCopies(300, "a"))).length());
    }

    @Test
    public void jobIsSubmittedOnceAndFollowedToItsVerdict() throws Exception {
        OckhamClient c = signedIn();
        String id = OckhamClient.newJobId();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("nota", "prueba \"con comillas\"");
        OckhamClient.Job j = c.submit(id, "eco", params, Arrays.asList("u-1/captura.zip"));
        assertEquals("queued", j.state);
        assertFalse(j.done);
        Map<?, ?> sent = (Map<?, ?>) Json.parse(jobBody);
        assertEquals("eco", sent.get("receta"));
        assertEquals("prueba \"con comillas\"", ((Map<?, ?>) sent.get("parametros")).get("nota"));
        assertEquals(Arrays.asList("u-1/captura.zip"), sent.get("entradas"));
        c.submit(id, "eco", params, Arrays.asList("u-1/captura.zip")); // repetir no falla
        while (!j.done) j = c.job(id);
        assertTrue(j.succeeded());
        assertEquals("Es una medición directa.", j.verdict.why);
        assertEquals(1, j.verdict.assumptions.size());
        assertEquals("informe.json", j.verdict.files.get(0).name);
        String text = j.verdict.text();
        assertTrue(text, text.contains("bytes: 8") && text.contains("POR QUÉ") && text.contains("SUPUESTOS")
                && text.contains("ALTERNATIVAS CONSIDERADAS\n  Ninguna."));
        assertEquals("No se pudo pedir el trabajo: No tienes habilitada esa receta. Receta: no-existe.",
                message(() -> c.submit(OckhamClient.newJobId(), "no-existe", null, Arrays.asList("u-1/x"))));
    }

    @Test
    public void sessionSurvivesBeingSavedAndADamagedCopyIsEmpty() throws Exception {
        OckhamClient c = signedIn();
        Session back = Session.fromText(c.session.toText());
        assertEquals(c.session.server, back.server);
        assertEquals(c.session.refresh, back.refresh);
        assertEquals(c.session.expiresAtMs, back.expiresAtMs);
        assertFalse(Session.fromText("{dañado").signedIn());
        assertFalse(Session.fromText("").signedIn());
        assertFalse(Session.fromText("[1,2]").signedIn());
        c.signOut();
        assertFalse(c.session.signedIn());
        assertTrue(log.contains("POST /auth/v1/logout?scope=local"));
        assertEquals("b2NraGFt", OckhamClient.b64("ockham"));
        assertEquals("YQ==", OckhamClient.b64("a"));
        assertEquals("YcOx", OckhamClient.b64("añ".substring(0, 2)));
    }

    @Test
    public void downloadsAResultAndChecksItIsWhatTheServerStored() throws Exception {
        OckhamClient c = signedIn();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long[] got = {0};
        expired = true; // el credencial venció: renueva y baja igual, sin escribir nada de más
        c.download(mesh("u-1/resultados/j1/malla.glb", MESH_SHA), out, n -> got[0] += n);
        assertArrayEquals(MESH, out.toByteArray());
        assertEquals(MESH.length, got[0]);
        // Si lo que llega no es lo que el servidor dijo que guardó, se avisa.
        assertTrue(message(() -> c.download(mesh("u-1/resultados/j1/malla.glb", "00ff"), new ByteArrayOutputStream(), null)).contains("distinto"));
        // Sólo se baja de la carpeta propia.
        for (String bad : new String[]{"u-2/resultados/j1/malla.glb", "u-1/../u-2/malla.glb", "u-1/a b.glb?x=1"}) {
            int before = log.size();
            assertTrue(bad, message(() -> c.download(mesh(bad, ""), new ByteArrayOutputStream(), null)).contains("no está en tu carpeta"));
            assertEquals(before, log.size()); // ni siquiera se le pregunta al servidor
        }
        assertTrue(message(() -> c.download(mesh("u-1/no-existe.glb", ""), new ByteArrayOutputStream(), null)).startsWith("No se pudo bajar el archivo"));
    }

    @Test
    public void cancelAsksTheServer() throws Exception {
        OckhamClient c = signedIn();
        OckhamClient.Job j = c.cancel("j9");
        assertEquals("cancelling", j.state);
        assertFalse(j.done);
        assertTrue(log.contains("POST /ockham/v1/trabajos/j9/cancelar"));
    }
}
