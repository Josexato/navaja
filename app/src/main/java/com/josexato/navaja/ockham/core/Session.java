package com.josexato.navaja.ockham.core;

import com.josexato.navaja.pantonera.core.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lo que la app recuerda de un servidor: su dirección, la llave pública de la app y la sesión del usuario. Se guarda
 * como texto ({@link #toText()}); en el teléfono ese texto va cifrado con una llave del Keystore.
 */
public final class Session {

    /** Dirección del servidor, sin barra final: https://ockham.ejemplo.com */
    public String server = "";
    public String appKey = "";
    public String bucket = "capturas";
    public String userId = "";
    public String email = "";
    public String access = "";
    public String refresh = "";
    /** Cuándo vence el credencial de acceso, en milisegundos desde 1970. */
    public long expiresAtMs;

    public boolean signedIn() {
        return !refresh.isEmpty() && !userId.isEmpty();
    }

    /** Olvida al usuario; el servidor queda anotado para volver a entrar. */
    public void signOut() {
        userId = access = refresh = "";
        expiresAtMs = 0;
    }

    public String toText() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("server", server);
        m.put("appKey", appKey);
        m.put("bucket", bucket);
        m.put("userId", userId);
        m.put("email", email);
        m.put("access", access);
        m.put("refresh", refresh);
        m.put("expiresAtMs", (double) expiresAtMs);
        return Json.writeCompact(m);
    }

    /** Lee una sesión guardada; un texto vacío o dañado da una sesión nueva, sin servidor. */
    public static Session fromText(String text) {
        Session s = new Session();
        if (text == null || text.isEmpty()) return s;
        try {
            Object o = Json.parse(text);
            if (!(o instanceof Map)) return s;
            Map<?, ?> m = (Map<?, ?>) o;
            s.server = str(m.get("server"));
            s.appKey = str(m.get("appKey"));
            String b = str(m.get("bucket"));
            if (!b.isEmpty()) s.bucket = b;
            s.userId = str(m.get("userId"));
            s.email = str(m.get("email"));
            s.access = str(m.get("access"));
            s.refresh = str(m.get("refresh"));
            Object e = m.get("expiresAtMs");
            s.expiresAtMs = e instanceof Number ? ((Number) e).longValue() : 0;
        } catch (Json.ParseException | RuntimeException ignored) {
            return new Session();
        }
        return s;
    }

    private static String str(Object o) {
        return o instanceof String ? (String) o : "";
    }
}
