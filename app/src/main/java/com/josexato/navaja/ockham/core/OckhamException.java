package com.josexato.navaja.ockham.core;

import com.josexato.navaja.pantonera.core.Json;

import java.util.Map;

/** Algo que el servidor rechazó o que no se pudo hacer, con un texto para mostrarle a la persona. */
public final class OckhamException extends Exception {

    /** Código HTTP, o 0 si el problema no vino del servidor. */
    public final int code;

    public OckhamException(int code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * Arma el error a partir de una respuesta. El servicio Ockham responde con el formato de problemas HTTP (title y
     * detail); Auth y Storage, con sus propios campos. Los mensajes más comunes de Auth se pasan al español.
     */
    static OckhamException of(Http.Reply r, String doing) {
        String msg = null, key = null;
        try {
            Object o = Json.parse(r.text());
            if (o instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) o;
                key = str(m.get("error_code"));
                if (m.get("title") != null) {
                    msg = str(m.get("title"));
                    if (m.get("detail") != null) msg += ". " + str(m.get("detail"));
                } else {
                    for (String k : new String[]{"msg", "error_description", "message", "error"}) {
                        if (m.get(k) instanceof String) {
                            msg = (String) m.get(k);
                            break;
                        }
                    }
                }
            }
        } catch (Json.ParseException | RuntimeException ignored) {
            // la respuesta no era JSON: se arma el mensaje con el código
        }
        String known = spanish(key, msg);
        if (known != null) return new OckhamException(r.code, known);
        return new OckhamException(r.code, "No se pudo " + doing + (msg == null ? " (código " + r.code + ")" : ": " + msg));
    }

    private static String spanish(String key, String msg) {
        String k = key == null ? "" : key, m = msg == null ? "" : msg.toLowerCase(java.util.Locale.ROOT);
        if (k.equals("invalid_credentials") || m.contains("invalid login credentials")) return "El correo o la contraseña no coinciden.";
        if (k.equals("otp_expired") || m.contains("token has expired or is invalid")) return "El código de invitación no vale: ya se usó, venció o está mal escrito.";
        if (k.equals("signup_disabled")) return "Este servidor sólo admite usuarios con invitación.";
        if (k.equals("weak_password") || m.contains("password should be at least")) return "La contraseña es muy corta: el servidor pide 12 caracteres o más.";
        if (k.equals("same_password")) return "La contraseña nueva tiene que ser distinta de la anterior.";
        if (k.equals("over_request_rate_limit") || k.equals("over_email_send_rate_limit")) return "Demasiados intentos seguidos. Espera un minuto y vuelve a probar.";
        if (k.equals("refresh_token_not_found") || k.equals("refresh_token_already_used") || k.equals("session_not_found")
                || k.equals("session_expired")) return "La sesión venció. Hay que entrar de nuevo.";
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
