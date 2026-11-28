package com.josexato.navaja.ockham.core;

import com.josexato.navaja.pantonera.core.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lo que Navaja sabe decirle a un servidor Ockham: entrar, renovar la sesión, ver el perfil, subir un archivo, pedir
 * un trabajo y seguirlo. Sin Android: el transporte se le pasa, y la sesión se guarda a través de {@link Saver}.
 *
 * <p>El servidor tiene tres partes y esta clase habla con las tres: Auth (usuarios y sesiones), Storage (archivos) y
 * el servicio Ockham (perfil, recetas y trabajos).
 */
public final class OckhamClient {

    /** El protocolo del servicio Ockham que esta versión de la app entiende. */
    public static final int PROTOCOL = 1;
    /** Storage pide partes de 6 MB exactos en las subidas reanudables (la última puede ser más chica). */
    public static final int CHUNK = 6 * 1024 * 1024;
    /** Se renueva el credencial si le queda menos que esto. */
    static final long RENEW_BEFORE_MS = 60_000;
    /** Cuántas veces seguidas se reintenta una parte de una subida antes de rendirse. */
    static final int UPLOAD_RETRIES = 5;

    /** Avisa cada vez que la sesión cambia, para guardarla. */
    public interface Saver {
        void save(Session s);
    }

    /** De dónde sale un archivo para subirlo; se puede abrir más de una vez (al reanudar). */
    public interface Source {
        InputStream open() throws IOException;

        long size();
    }

    public interface Clock {
        long now();
    }

    public static final class Profile {
        public String userId = "", email = "", role = "";
        public int jobsPerDay, gpuSecondsPerDay;
        public final List<String> capabilities = new ArrayList<>();
    }

    public static final class Recipe {
        public String name = "", title = "", description = "";
        public int minInputs, maxInputs, maxSeconds;
        /** El esquema JSON de sus parámetros, tal como lo manda el servidor. */
        public Map<String, Object> parameters = Collections.emptyMap();
        /** Los archivos que entrega. */
        public final List<String> outputs = new ArrayList<>();
    }

    public static final class Job {
        public String id = "", recipe = "", state = "", reason = "";
        public boolean done;
        /** Segundos de GPU que lleva gastados, según la plataforma. */
        public double gpuSeconds;
        /** El veredicto cuando terminó bien; null si no. */
        public Verdict verdict;

        public boolean succeeded() {
            return "succeeded".equals(state);
        }
    }

    public final Session session;
    private final Http http;
    private final Saver saver;
    private final Clock clock;

    public OckhamClient(Http http, Session session, Saver saver) {
        this(http, session, saver, System::currentTimeMillis);
    }

    OckhamClient(Http http, Session session, Saver saver, Clock clock) {
        this.http = http;
        this.session = session;
        this.saver = saver;
        this.clock = clock;
    }

    // =================================================================== servidor

    /**
     * Deja la dirección en limpio: sin espacios, con https:// y sin barra final. Sólo se admite HTTPS; la excepción
     * es {@code localhost}, para probar en una máquina de desarrollo.
     */
    public static String normalize(String address) throws OckhamException {
        String a = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
        while (a.endsWith("/")) a = a.substring(0, a.length() - 1);
        if (a.isEmpty()) throw new OckhamException(0, "Falta la dirección del servidor.");
        if (!a.contains("://")) a = "https://" + a;
        boolean local = a.startsWith("http://localhost") || a.startsWith("http://127.0.0.1");
        if (!a.startsWith("https://") && !local) {
            throw new OckhamException(0, "La dirección tiene que empezar con https://");
        }
        String host = a.substring(a.indexOf("://") + 3);
        if (host.isEmpty() || !host.matches("[a-z0-9.-]+(:[0-9]{1,5})?")) {
            throw new OckhamException(0, "La dirección del servidor no es válida: sólo el nombre, sin rutas.");
        }
        return a;
    }

    /** Pregunta al servidor si es un Ockham que esta app entiende y anota lo que hace falta para hablarle. */
    public void connect(String address) throws OckhamException, IOException {
        String server = normalize(address);
        Http.Reply r = http.send("GET", server + "/ockham/v1/salud", headers(null, null), null, null, -1, null);
        if (!r.ok()) throw new OckhamException(r.code, "Ahí no responde un servidor Ockham (código " + r.code + ").");
        Map<String, Object> m = object(r, "leer la respuesta del servidor");
        if (!"ockham".equals(m.get("servicio"))) throw new OckhamException(0, "Ahí no responde un servidor Ockham.");
        int protocol = number(m.get("protocolo"));
        if (protocol != PROTOCOL) {
            throw new OckhamException(0, "El servidor habla el protocolo " + protocol + " y esta versión de Navaja entiende el "
                    + PROTOCOL + ". Hay que actualizar " + (protocol > PROTOCOL ? "Navaja." : "el servidor."));
        }
        String key = text(m.get("llave_app"));
        if (key.isEmpty()) throw new OckhamException(0, "El servidor no entregó la llave de la app.");
        if (!server.equals(session.server)) session.signOut();
        session.server = server;
        session.appKey = key;
        String bucket = text(m.get("deposito"));
        if (!bucket.isEmpty()) session.bucket = bucket;
        saver.save(session);
    }

    // =================================================================== entrar

    /** Primera vez: con el correo y el código de la invitación se abre la sesión y se fija la contraseña. */
    public void redeemInvite(String email, String code, String newPassword) throws OckhamException, IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "invite");
        body.put("email", email.trim());
        body.put("token", code.trim());
        Http.Reply r = post(session.server + "/auth/v1/verify", headers(null, "application/json"), body);
        if (!r.ok()) throw OckhamException.of(r, "usar la invitación");
        take(object(r, "usar la invitación"));
        Map<String, Object> pass = new LinkedHashMap<>();
        pass.put("password", newPassword);
        r = http.send("PUT", session.server + "/auth/v1/user", headers(session.access, "application/json"), json(pass), null, -1, null);
        if (!r.ok()) throw OckhamException.of(r, "guardar la contraseña");
    }

    public void signIn(String email, String password) throws OckhamException, IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email.trim());
        body.put("password", password);
        Http.Reply r = post(session.server + "/auth/v1/token?grant_type=password", headers(null, "application/json"), body);
        if (!r.ok()) throw OckhamException.of(r, "entrar");
        take(object(r, "entrar"));
    }

    /** Cierra la sesión en el servidor (si se puede) y la olvida en el teléfono pase lo que pase. */
    public void signOut() {
        try {
            if (!session.access.isEmpty()) {
                http.send("POST", session.server + "/auth/v1/logout?scope=local", headers(session.access, null), new byte[0], null, -1, null);
            }
        } catch (IOException ignored) {
            // sin red igual se olvida la sesión en el teléfono
        }
        session.signOut();
        saver.save(session);
    }

    /** Pide un credencial nuevo con el token de renovación. Si el servidor lo rechaza, la sesión se da por terminada. */
    void renew() throws OckhamException, IOException {
        if (session.refresh.isEmpty()) throw new OckhamException(401, "No hay una sesión abierta. Hay que entrar.");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("refresh_token", session.refresh);
        Http.Reply r = post(session.server + "/auth/v1/token?grant_type=refresh_token", headers(null, "application/json"), body);
        if (r.code == 400 || r.code == 401 || r.code == 403) {
            session.signOut();
            saver.save(session);
            throw new OckhamException(401, "La sesión venció. Hay que entrar de nuevo.");
        }
        if (!r.ok()) throw OckhamException.of(r, "renovar la sesión");
        take(object(r, "renovar la sesión"));
    }

    private void take(Map<String, Object> m) throws OckhamException {
        String access = text(m.get("access_token")), refresh = text(m.get("refresh_token"));
        Object user = m.get("user");
        if (access.isEmpty() || refresh.isEmpty() || !(user instanceof Map)) {
            throw new OckhamException(0, "El servidor respondió algo inesperado al abrir la sesión.");
        }
        session.access = access;
        session.refresh = refresh;
        session.expiresAtMs = clock.now() + 1000L * Math.max(0, number(m.get("expires_in")));
        session.userId = text(((Map<?, ?>) user).get("id"));
        String email = text(((Map<?, ?>) user).get("email"));
        if (!email.isEmpty()) session.email = email;
        saver.save(session);
    }

    /** Un pedido con sesión: renueva antes si falta poco para que venza, y una vez más si el servidor dice 401. */
    private Http.Reply authed(String method, String url, String contentType, byte[] body) throws OckhamException, IOException {
        if (!session.signedIn()) throw new OckhamException(401, "No hay una sesión abierta. Hay que entrar.");
        if (clock.now() > session.expiresAtMs - RENEW_BEFORE_MS) renew();
        Http.Reply r = http.send(method, url, headers(session.access, contentType), body, null, -1, null);
        if (r.code == 401) {
            renew();
            r = http.send(method, url, headers(session.access, contentType), body, null, -1, null);
        }
        return r;
    }

    // =================================================================== perfil y recetas

    public Profile profile() throws OckhamException, IOException {
        Http.Reply r = authed("GET", session.server + "/ockham/v1/perfil", null, null);
        if (!r.ok()) throw OckhamException.of(r, "leer el perfil");
        Map<String, Object> m = object(r, "leer el perfil");
        Profile p = new Profile();
        p.userId = text(m.get("usuario"));
        p.email = text(m.get("correo"));
        p.role = text(m.get("rol"));
        if (m.get("limites") instanceof Map) {
            p.jobsPerDay = number(((Map<?, ?>) m.get("limites")).get("trabajos_por_dia"));
            p.gpuSecondsPerDay = number(((Map<?, ?>) m.get("limites")).get("segundos_gpu_por_dia"));
        }
        if (m.get("capacidades") instanceof List) for (Object o : (List<?>) m.get("capacidades")) p.capabilities.add(text(o));
        return p;
    }

    @SuppressWarnings("unchecked")
    public List<Recipe> recipes() throws OckhamException, IOException {
        Http.Reply r = authed("GET", session.server + "/ockham/v1/recetas", null, null);
        if (!r.ok()) throw OckhamException.of(r, "leer las recetas");
        List<Recipe> out = new ArrayList<>();
        Object list = object(r, "leer las recetas").get("recetas");
        if (list instanceof List) {
            for (Object o : (List<?>) list) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) o;
                Recipe x = new Recipe();
                x.name = text(m.get("nombre"));
                x.title = text(m.get("titulo"));
                x.description = text(m.get("descripcion"));
                x.maxSeconds = number(m.get("segundos_max"));
                if (m.get("entradas") instanceof Map) {
                    x.minInputs = number(((Map<?, ?>) m.get("entradas")).get("minimo"));
                    x.maxInputs = number(((Map<?, ?>) m.get("entradas")).get("maximo"));
                }
                if (m.get("parametros") instanceof Map) x.parameters = (Map<String, Object>) m.get("parametros");
                if (m.get("salidas") instanceof List) for (Object s : (List<?>) m.get("salidas")) x.outputs.add(text(s));
                out.add(x);
            }
        }
        return out;
    }

    // =================================================================== subir un archivo

    /** Quita de un nombre de archivo lo que no puede ir en una ruta de Storage. */
    public static String safeName(String name) {
        StringBuilder sb = new StringBuilder();
        for (char ch : (name == null ? "" : name).toCharArray()) {
            boolean keep = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '.' || ch == '_' || ch == '-';
            sb.append(keep ? ch : '_');
        }
        String s = sb.toString();
        while (s.startsWith(".")) s = s.substring(1);
        if (s.length() > 120) s = s.substring(s.length() - 120);
        return s.isEmpty() ? "archivo" : s;
    }

    /**
     * Sube un archivo a la carpeta del usuario, por partes, y devuelve su ruta dentro del depósito. Si una parte falla
     * (se cortó la señal), le pregunta al servidor hasta dónde llegó y sigue desde ahí.
     */
    public String upload(String name, String contentType, Source source, Http.Progress progress) throws OckhamException, IOException {
        if (!session.signedIn()) throw new OckhamException(401, "No hay una sesión abierta. Hay que entrar.");
        if (clock.now() > session.expiresAtMs - RENEW_BEFORE_MS) renew();
        long size = source.size();
        if (size <= 0) throw new OckhamException(0, "El archivo está vacío.");
        String path = session.userId + "/" + safeName(name);
        String base = session.server + "/storage/v1/upload/resumable";

        Map<String, String> h = tus();
        h.put("Upload-Length", Long.toString(size));
        h.put("Upload-Metadata", "bucketName " + b64(session.bucket) + ",objectName " + b64(path) + ",contentType " + b64(contentType));
        h.put("x-upsert", "true");
        Http.Reply r = http.send("POST", base, h, new byte[0], null, -1, null);
        if (r.code != 201) throw OckhamException.of(r, "empezar la subida");
        String where = r.headers.get("location");
        if (where == null || where.isEmpty()) throw new OckhamException(0, "El servidor no dijo dónde subir el archivo.");
        if (where.startsWith("/")) where = session.server + where;
        if (!where.startsWith(session.server + "/")) throw new OckhamException(0, "El servidor mandó a subir el archivo a otra dirección.");

        long offset = 0;
        int failures = 0;
        while (offset < size) {
            long part = Math.min(CHUNK, size - offset);
            try (InputStream in = source.open()) {
                skip(in, offset);
                if (clock.now() > session.expiresAtMs - RENEW_BEFORE_MS) renew();
                Map<String, String> p = tus();
                // El cliente HTTP de Android no puede usar PATCH: va POST con esta cabecera, y el servidor lo convierte.
                p.put("X-HTTP-Method-Override", "PATCH");
                p.put("Upload-Offset", Long.toString(offset));
                p.put("Content-Type", "application/offset+octet-stream");
                final long done = offset;
                final long[] sent = {0};
                Http.Reply pr = http.send("POST", where, p, null, in, part, progress == null ? null : n -> {
                    sent[0] += n;
                    progress.sent(done + sent[0]);
                });
                if (pr.code == 204) {
                    String next = pr.headers.get("upload-offset");
                    offset = next == null ? offset + part : Long.parseLong(next.trim());
                    failures = 0;
                    continue;
                }
                if (pr.code == 401) {
                    renew();
                } else if (pr.code != 409 && pr.code < 500) {
                    throw OckhamException.of(pr, "subir el archivo");
                }
            } catch (IOException e) {
                if (++failures > UPLOAD_RETRIES) throw e;
            }
            // Algo falló a mitad: se le pregunta al servidor cuánto recibió.
            if (++failures > UPLOAD_RETRIES * 2) throw new OckhamException(0, "La subida falla una y otra vez. Probar más tarde.");
            offset = uploaded(where, offset);
            if (progress != null) progress.sent(offset);
        }
        return path;
    }

    private long uploaded(String where, long fallback) throws IOException {
        Http.Reply r = http.send("HEAD", where, tus(), null, null, -1, null);
        String at = r.headers.get("upload-offset");
        if (r.ok() && at != null) return Long.parseLong(at.trim());
        return fallback;
    }

    private Map<String, String> tus() {
        Map<String, String> h = headers(session.access, null);
        h.put("Tus-Resumable", "1.0.0");
        return h;
    }

    private static void skip(InputStream in, long n) throws IOException {
        long left = n;
        while (left > 0) {
            long s = in.skip(left);
            if (s <= 0) {
                if (in.read() < 0) throw new IOException("el archivo es más corto de lo esperado");
                s = 1;
            }
            left -= s;
        }
    }

    // =================================================================== trabajos

    /** Un identificador nuevo para un trabajo. Guardarlo antes de pedir: repetir el pedido con el mismo no lo duplica. */
    public static String newJobId() {
        return java.util.UUID.randomUUID().toString();
    }

    public Job submit(String jobId, String recipe, Map<String, Object> parameters, List<String> inputs) throws OckhamException, IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("receta", recipe);
        body.put("parametros", parameters == null ? new LinkedHashMap<String, Object>() : parameters);
        body.put("entradas", new ArrayList<Object>(inputs));
        Http.Reply r = authed("PUT", session.server + "/ockham/v1/trabajos/" + jobId, "application/json", json(body));
        if (r.code != 200 && r.code != 202) throw OckhamException.of(r, "pedir el trabajo");
        return job(object(r, "pedir el trabajo"));
    }

    public Job job(String jobId) throws OckhamException, IOException {
        Http.Reply r = authed("GET", session.server + "/ockham/v1/trabajos/" + jobId, null, null);
        if (!r.ok()) throw OckhamException.of(r, "consultar el trabajo");
        return job(object(r, "consultar el trabajo"));
    }

    /** Pide cancelar un trabajo. Si corre en una plataforma, puede tardar unos segundos en quedar cancelado. */
    public Job cancel(String jobId) throws OckhamException, IOException {
        Http.Reply r = authed("POST", session.server + "/ockham/v1/trabajos/" + jobId + "/cancelar", null, new byte[0]);
        if (!r.ok()) throw OckhamException.of(r, "cancelar el trabajo");
        return job(object(r, "cancelar el trabajo"));
    }

    /**
     * Baja un archivo de resultado y lo escribe en {@code sink}. Comprueba que lo que llegó sea lo que el servidor
     * dijo que guardó (su huella SHA-256): si no coincide, avisa en vez de dejar pasar un archivo dañado.
     */
    public void download(Verdict.File file, OutputStream sink, Http.Progress progress) throws OckhamException, IOException {
        if (!session.signedIn()) throw new OckhamException(401, "No hay una sesión abierta. Hay que entrar.");
        if (!file.path.startsWith(session.userId + "/") || file.path.contains("..") || !file.path.matches("[A-Za-z0-9._/-]+")) {
            throw new OckhamException(0, "El servidor nombró un archivo que no está en tu carpeta.");
        }
        if (clock.now() > session.expiresAtMs - RENEW_BEFORE_MS) renew();
        String url = session.server + "/storage/v1/object/authenticated/" + session.bucket + "/" + file.path;
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        OutputStream counted = new DigestOutputStream(sink, digest);
        Http.Reply r = http.fetch(url, headers(session.access, null), counted, progress);
        if (r.code == 401) {
            renew();
            r = http.fetch(url, headers(session.access, null), counted, progress);
        }
        if (!r.ok()) throw OckhamException.of(r, "bajar el archivo");
        counted.flush();
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
        if (!file.sha256.isEmpty() && !file.sha256.equals(hex.toString())) {
            throw new OckhamException(0, "El archivo llegó distinto de como lo guardó el servidor. Hay que bajarlo de nuevo.");
        }
    }

    private static Job job(Map<String, Object> m) {
        Job j = new Job();
        j.id = text(m.get("id"));
        j.recipe = text(m.get("receta"));
        j.state = text(m.get("estado"));
        j.reason = text(m.get("motivo"));
        j.done = Boolean.TRUE.equals(m.get("terminado"));
        if (m.get("segundos_gpu") instanceof Number) j.gpuSeconds = ((Number) m.get("segundos_gpu")).doubleValue();
        if (m.get("resultado") instanceof Map) j.verdict = Verdict.of((Map<?, ?>) m.get("resultado"));
        return j;
    }

    // =================================================================== utilidades

    private Map<String, String> headers(String bearer, String contentType) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/json");
        if (!session.appKey.isEmpty()) h.put("apikey", session.appKey);
        if (bearer != null && !bearer.isEmpty()) h.put("Authorization", "Bearer " + bearer);
        if (contentType != null) h.put("Content-Type", contentType);
        return h;
    }

    private Http.Reply post(String url, Map<String, String> h, Map<String, Object> body) throws IOException {
        return http.send("POST", url, h, json(body), null, -1, null);
    }

    private static byte[] json(Map<String, Object> m) {
        return Json.writeCompact(m).getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Http.Reply r, String doing) throws OckhamException {
        try {
            Object o = Json.parse(r.text());
            if (o instanceof Map) return (Map<String, Object>) o;
        } catch (Json.ParseException | RuntimeException ignored) {
            // cae al error de abajo
        }
        throw new OckhamException(r.code, "No se pudo " + doing + ": el servidor respondió algo que no se entiende.");
    }

    static String text(Object o) {
        return o instanceof String ? (String) o : "";
    }

    static int number(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }

    /** Base64 estándar. (java.util.Base64 pide Android 8; Navaja corre desde Android 7.) */
    static String b64(String s) {
        final String abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        byte[] in = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < in.length; i += 3) {
            int b0 = in[i] & 255, b1 = i + 1 < in.length ? in[i + 1] & 255 : 0, b2 = i + 2 < in.length ? in[i + 2] & 255 : 0;
            sb.append(abc.charAt(b0 >> 2)).append(abc.charAt(((b0 & 3) << 4) | (b1 >> 4)));
            sb.append(i + 1 < in.length ? abc.charAt(((b1 & 15) << 2) | (b2 >> 6)) : '=');
            sb.append(i + 2 < in.length ? abc.charAt(b2 & 63) : '=');
        }
        return sb.toString();
    }
}
