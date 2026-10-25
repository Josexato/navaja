package com.josexato.navaja.sign.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/** HTTP mínimo sobre HttpURLConnection (igual en Android y en la JVM de los tests). */
public final class Http {

    private Http() { }

    public static final class Response {
        public final int code;
        public final byte[] body;

        Response(int code, byte[] body) { this.code = code; this.body = body; }

        public boolean ok() { return code >= 200 && code < 300; }

        public String text() { return new String(body, java.nio.charset.StandardCharsets.UTF_8); }
    }

    public static Response request(String method, String url, Map<String, String> headers, byte[] body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15_000);
            c.setReadTimeout(30_000);
            c.setRequestMethod(method);
            c.setRequestProperty("User-Agent", "Navaja-SSJF/1");
            if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            if (body != null) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream o = c.getOutputStream()) { o.write(body); }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Response(code, in == null ? new byte[0] : readAll(in, 8 << 20));
        } finally {
            c.disconnect();
        }
    }

    public static byte[] readAll(InputStream in, int max) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = i.read(buf)) > 0) {
                o.write(buf, 0, n);
                if (o.size() > max) throw new IOException("Respuesta demasiado grande");
            }
            return o.toByteArray();
        }
    }
}
