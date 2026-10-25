package com.josexato.navaja.sign.core.storage;

import com.josexato.navaja.sign.core.Bytes;
import com.josexato.navaja.sign.core.Http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** WebDAV (Nextcloud, ownCloud, Synology/QNAP, Box, cualquier NAS que exporte su NFS/SMB también por WebDAV). */
public final class WebDav implements Backend {

    private final String base, user, pass;

    public WebDav(String baseUrl, String user, String pass) {
        this.base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.user = user;
        this.pass = pass;
    }

    @Override public String describe() { return "WebDAV " + base; }

    @Override
    public void put(String path, byte[] data, String contentType) throws IOException {
        // HttpURLConnection no admite el método MKCOL (crear carpeta), así que todo va en la carpeta
        // configurada, con las subcarpetas aplanadas: "firmas/000001.json" → "firmas_000001.json".
        Map<String, String> h = auth();
        h.put("Content-Type", contentType);
        Http.Response r = Http.request("PUT", base + S3.encode(path.replace('/', '_'), false), h, data);
        if (!r.ok()) throw new IOException("WebDAV respondió HTTP " + r.code + (r.code == 409 ? " (¿existe la carpeta?)" : ""));
    }

    private Map<String, String> auth() {
        Map<String, String> h = new HashMap<>();
        if (user != null && !user.isEmpty())
            h.put("Authorization", "Basic " + Bytes.b64((user + ":" + pass).getBytes(StandardCharsets.UTF_8)));
        return h;
    }
}
