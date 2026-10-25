package com.josexato.navaja.sign.core;

import com.josexato.navaja.pantonera.core.Json;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cambio de clave SSJF: la clave anterior autoriza a la nueva, y la nueva
 * prueba que existe (prueba de posesión). Así quien confía en la clave
 * vieja puede confiar en la nueva sin otro canal, y las firmas antiguas
 * siguen siendo tuyas.
 *
 * <pre>
 * posesión:     SSJF1-POP;newpk=&lt;pk nueva&gt;;t=&lt;unix&gt;                         firmado por la clave NUEVA
 * autorización: SSJF1-ROT;oldpk=&lt;pk vieja&gt;;newpk=&lt;pk nueva&gt;;t=&lt;unix&gt;;pop=&lt;t posesión&gt;;r=&lt;motivo&gt;
 *                                                                       firmado por la clave VIEJA
 * </pre>
 *
 * Las claves van comprimidas (33 bytes) en base64url y las firmas r‖s
 * (64 bytes) en base64url, como en el QR. Separar la posesión permite el
 * paso a un teléfono nuevo: el nuevo genera su clave y manda una
 * {@link #requestText solicitud}; el viejo la autoriza.
 */
public final class KeyChange {

    public String oldPk, newPk, reason = "rotacion";
    public long t, popT;
    public byte[] sigOld, sigNew;

    public static final String REASON_ROTATION = "rotacion", REASON_NEW_PHONE = "telefono-nuevo";

    public String oldKid() { return Ecdsa.keyId(Bytes.unb64url(oldPk)); }

    public String newKid() { return Ecdsa.keyId(Bytes.unb64url(newPk)); }

    public static String popMessage(String newPk, long popT) { return "SSJF1-POP;newpk=" + newPk + ";t=" + popT; }

    public String rotMessage() {
        return "SSJF1-ROT;oldpk=" + oldPk + ";newpk=" + newPk + ";t=" + t + ";pop=" + popT + ";r=" + reason;
    }

    /** Ambas firmas válidas, claves distintas y motivo con formato correcto. */
    public boolean verify() {
        try {
            if (oldPk == null || newPk == null || oldPk.equals(newPk) || sigOld == null || sigNew == null) return false;
            if (!reason.matches("[a-z0-9-]{1,32}")) return false;
            return Ecdsa.verify(Ecdsa.decompress(Bytes.unb64url(newPk)), utf8(popMessage(newPk, popT)), sigNew)
                    && Ecdsa.verify(Ecdsa.decompress(Bytes.unb64url(oldPk)), utf8(rotMessage()), sigOld);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] utf8(String s) { return s.getBytes(java.nio.charset.StandardCharsets.UTF_8); }

    // ------------------------------------------------------------ solicitud

    /** Solicitud que el teléfono nuevo envía al viejo: su clave y la prueba de posesión. */
    public static String requestText(String newPk, long popT, byte[] sigNew) {
        return popMessage(newPk, popT) + ";s=" + Bytes.b64url(sigNew);
    }

    /** Interpreta y verifica una solicitud; devuelve un KeyChange sin la parte vieja, o lanza si no vale. */
    public static KeyChange parseRequest(String text) throws Ssjf.FormatException {
        int at = text == null ? -1 : text.indexOf("SSJF1-POP;");
        if (at < 0) throw new Ssjf.FormatException("No es una solicitud de cambio de clave SSJF");
        String core = text.substring(at).trim().split("\\s")[0];
        Map<String, String> f = fields(core);
        KeyChange k = new KeyChange();
        k.newPk = f.get("newpk");
        try {
            k.popT = Long.parseLong(f.get("t"));
            k.sigNew = Bytes.unb64url(f.get("s"));
            if (!core.equals(requestText(k.newPk, k.popT, k.sigNew))) throw new Ssjf.FormatException("Solicitud con campos extra o fuera de orden");
            if (!Ecdsa.verify(Ecdsa.decompress(Bytes.unb64url(k.newPk)), utf8(popMessage(k.newPk, k.popT)), k.sigNew))
                throw new Ssjf.FormatException("La prueba de posesión de la clave nueva no es válida");
        } catch (Ssjf.FormatException e) {
            throw e;
        } catch (Exception e) {
            throw new Ssjf.FormatException("Solicitud ilegible");
        }
        return k;
    }

    private static Map<String, String> fields(String core) {
        Map<String, String> f = new HashMap<>();
        String[] parts = core.split(";");
        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq > 0) f.put(parts[i].substring(0, eq), parts[i].substring(eq + 1));
        }
        return f;
    }

    // ------------------------------------------------------- JSON e historial

    /** Campos públicos (mismo orden en la declaración exportada y en la entrada "clave" del historial). */
    public Map<String, Object> fieldsMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("old_kid", oldKid());
        m.put("old_pk", oldPk);
        m.put("new_kid", newKid());
        m.put("new_pk", newPk);
        m.put("ct", t);
        m.put("pop_t", popT);
        m.put("reason", reason);
        m.put("sig_old", Bytes.b64url(sigOld));
        m.put("sig_new", Bytes.b64url(sigNew));
        return m;
    }

    /** Declaración autocontenida para publicar o enviar a quien verifica tus firmas. */
    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ssjf_cambio_clave", 1L);
        m.putAll(fieldsMap());
        m.put("guia", Ssjf.DEFAULT_URL);
        return Json.write(m);
    }

    @SuppressWarnings("unchecked")
    public static KeyChange fromJson(String s) throws Json.ParseException {
        return fromMap((Map<String, Object>) Json.parse(s));
    }

    public static KeyChange fromMap(Map<String, Object> m) {
        KeyChange k = new KeyChange();
        k.oldPk = str(m.get("old_pk"));
        k.newPk = str(m.get("new_pk"));
        k.t = m.get("ct") instanceof Number ? ((Number) m.get("ct")).longValue() : -1;
        k.popT = m.get("pop_t") instanceof Number ? ((Number) m.get("pop_t")).longValue() : -1;
        k.reason = str(m.get("reason"));
        if (k.reason == null) k.reason = "";
        try {
            k.sigOld = Bytes.unb64url(str(m.get("sig_old")));
            k.sigNew = Bytes.unb64url(str(m.get("sig_new")));
        } catch (RuntimeException e) {
            k.sigOld = k.sigNew = null;
        }
        return k;
    }

    private static String str(Object o) { return o instanceof String ? (String) o : null; }

    /** Agrega la entrada "clave" al historial. */
    public Ledger.Entry record(Ledger l, long nowUnix) {
        return l.append(Ledger.TYPE_KEY, nowUnix, fieldsMap(), null);
    }

    /** Cambios de clave válidos del historial, en orden. */
    public static List<KeyChange> fromLedger(Ledger l) {
        List<KeyChange> out = new ArrayList<>();
        for (Ledger.Entry e : l.entries()) {
            if (!Ledger.TYPE_KEY.equals(e.type())) continue;
            KeyChange k = fromMap(e.m);
            if (k.verify()) out.add(k);
        }
        return out;
    }

    /**
     * Todas las claves que son "mías" partiendo de {@code current}: se siguen
     * los cambios válidos en ambos sentidos (la que me autorizó y las que yo
     * autoricé). Devuelve claves comprimidas en base64url.
     */
    public static Set<String> myKeys(List<KeyChange> changes, String current) {
        Set<String> mine = new LinkedHashSet<>();
        if (current != null) mine.add(current);
        for (boolean grew = true; grew; ) {
            grew = false;
            for (KeyChange k : changes) {
                if (mine.contains(k.newPk) && mine.add(k.oldPk)) grew = true;
                if (mine.contains(k.oldPk) && mine.add(k.newPk)) grew = true;
            }
        }
        return mine;
    }

    /** Momento en que cada clave fue reemplazada (kid → t), para avisar de firmas posteriores con una clave vieja. */
    public static Map<String, Long> retired(List<KeyChange> changes) {
        Map<String, Long> r = new HashMap<>();
        for (KeyChange k : changes) {
            Long prev = r.get(k.oldKid());
            if (prev == null || k.t < prev) r.put(k.oldKid(), k.t);
        }
        return r;
    }
}
