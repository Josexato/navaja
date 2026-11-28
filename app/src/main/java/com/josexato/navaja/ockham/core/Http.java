package com.josexato.navaja.ockham.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * El transporte: un pedido HTTP y su respuesta. Es una interfaz para que las pruebas puedan poner otro en su lugar;
 * la app usa {@link Url}, que se apoya en el cliente HTTP de la plataforma, sin librerías agregadas.
 */
public interface Http {

    /** Avance de una subida. */
    interface Progress {
        void sent(long bytes);
    }

    final class Reply {
        public final int code;
        /** Cabeceras de la respuesta, con el nombre en minúsculas. */
        public final Map<String, String> headers;
        public final byte[] body;

        public Reply(int code, Map<String, String> headers, byte[] body) {
            this.code = code;
            this.headers = headers;
            this.body = body;
        }

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public boolean ok() {
            return code >= 200 && code < 300;
        }
    }

    /**
     * Manda un pedido. {@code body} puede ser null. Si {@code length} es mayor o igual a cero se leen exactamente
     * esos bytes de {@code stream} y se mandan sin cargarlos en memoria.
     */
    Reply send(String method, String url, Map<String, String> headers, byte[] body, InputStream stream, long length,
               Progress progress) throws IOException;

    /** El transporte real, sobre HttpURLConnection. */
    final class Url implements Http {
        private static final int CONNECT_MS = 15_000, READ_MS = 60_000;

        @Override
        public Reply send(String method, String url, Map<String, String> headers, byte[] body, InputStream stream,
                          long length, Progress progress) throws IOException {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setConnectTimeout(CONNECT_MS);
                c.setReadTimeout(READ_MS);
                c.setInstanceFollowRedirects(false); // un servidor Ockham no redirige: seguirlo llevaría el credencial a otro lado
                c.setRequestMethod(method);
                for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
                if (body != null) {
                    c.setDoOutput(true);
                    c.setFixedLengthStreamingMode(body.length);
                    try (OutputStream out = c.getOutputStream()) {
                        out.write(body);
                    }
                } else if (stream != null && length >= 0) {
                    c.setDoOutput(true);
                    c.setFixedLengthStreamingMode(length);
                    try (OutputStream out = c.getOutputStream()) {
                        byte[] buf = new byte[64 * 1024];
                        long left = length;
                        while (left > 0) {
                            int n = stream.read(buf, 0, (int) Math.min(buf.length, left));
                            if (n < 0) throw new IOException("el archivo terminó antes de lo esperado");
                            out.write(buf, 0, n);
                            left -= n;
                            if (progress != null) progress.sent(n);
                        }
                    }
                }
                int code = c.getResponseCode();
                Map<String, String> hs = new HashMap<>();
                for (Map.Entry<String, List<String>> h : c.getHeaderFields().entrySet()) {
                    if (h.getKey() != null && !h.getValue().isEmpty()) {
                        hs.put(h.getKey().toLowerCase(Locale.ROOT), h.getValue().get(0));
                    }
                }
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                return new Reply(code, hs, in == null ? new byte[0] : readAll(in));
            } finally {
                c.disconnect();
            }
        }

        static byte[] readAll(InputStream in) throws IOException {
            try (InputStream i = in) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                for (int n; (n = i.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    if (out.size() > 8 * 1024 * 1024) throw new IOException("respuesta demasiado grande");
                }
                return out.toByteArray();
            }
        }
    }
}
