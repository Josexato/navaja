package com.josexato.navaja.sign;

import android.content.Context;
import android.net.Uri;

import com.josexato.navaja.sign.core.Ledger;
import com.josexato.navaja.sign.core.storage.Backend;
import com.josexato.navaja.sign.core.storage.Ftp;
import com.josexato.navaja.sign.core.storage.S3;
import com.josexato.navaja.sign.core.storage.WebDav;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Respaldo del historial en el destino que elija el usuario. Sube el
 * historial completo ("historial.jsonl") y, donde se puede, cada entrada en
 * su propio archivo ("firmas/000123.json"), que nunca se reescribe: aunque
 * alguien pise el historial remoto, las entradas sueltas quedan.
 */
final class Backup {

    private Backup() { }

    static final String NONE = "none", SAF = "saf", WEBDAV = "webdav", S3_ = "s3", FTP = "ftp";

    /** Archivo elegido con el selector de Android (Drive, OneDrive, Dropbox, USB, apps FTP/SFTP/SMB…). */
    static final class Saf implements Backend {
        private final Context ctx;
        private final Uri uri;

        Saf(Context ctx, Uri uri) {
            this.ctx = ctx.getApplicationContext();
            this.uri = uri;
        }

        @Override public String describe() { return "Archivo " + uri.getLastPathSegment(); }

        @Override
        public void put(String path, byte[] data, String contentType) throws IOException {
            if (!path.equals("historial.jsonl")) return; // un solo archivo: el historial completo
            try (OutputStream o = openTruncated(ctx, uri)) {
                o.write(data);
            } catch (SecurityException e) {
                throw new IOException("Se perdió el permiso sobre el archivo; elígelo de nuevo");
            }
        }
    }

    /** Abre para reescribir: "wt" (truncar) y, si el proveedor no lo admite (p. ej. Drive), "w". */
    static OutputStream openTruncated(Context c, Uri uri) throws IOException {
        OutputStream o;
        try {
            o = c.getContentResolver().openOutputStream(uri, "wt");
        } catch (IllegalArgumentException | UnsupportedOperationException | java.io.FileNotFoundException e) {
            o = c.getContentResolver().openOutputStream(uri, "w");
        }
        if (o == null) throw new IOException("No se pudo abrir " + uri.getLastPathSegment());
        return o;
    }

    static Backend create(Context c, Map<String, String> cfg) {
        String type = cfg.getOrDefault("type", NONE);
        switch (type) {
            case SAF:
                return cfg.get("uri") == null ? null : new Saf(c, Uri.parse(cfg.get("uri")));
            case WEBDAV:
                return new WebDav(cfg.getOrDefault("url", ""), cfg.get("user"), cfg.get("pass"));
            case S3_:
                return new S3(cfg.getOrDefault("endpoint", ""), cfg.getOrDefault("region", "us-east-1"), cfg.getOrDefault("bucket", ""),
                        cfg.get("prefix"), cfg.getOrDefault("access", ""), cfg.getOrDefault("secret", ""),
                        "true".equals(cfg.get("pathStyle")));
            case FTP:
                int port = 21;
                try {
                    port = Integer.parseInt(cfg.getOrDefault("port", "21"));
                } catch (NumberFormatException ignored) {
                    // 21 por defecto
                }
                return new Ftp(cfg.getOrDefault("host", ""), port, cfg.get("user"), cfg.get("pass"), cfg.get("dir"),
                        "true".equals(cfg.get("tls")));
            default:
                return null;
        }
    }

    /** Sube el historial (y la última entrada suelta). Llamar fuera del hilo de la UI. */
    static void upload(Backend b, Ledger l, boolean includePrivate) throws IOException {
        String text = l.toJsonl(includePrivate);
        b.put("historial.jsonl", text.getBytes(StandardCharsets.UTF_8), "application/x-ndjson");
        Ledger.Entry last = l.last();
        if (last != null) {
            String line = includePrivate ? com.josexato.navaja.pantonera.core.Json.writeCompact(last.m) : last.publicJson();
            b.put(String.format(Locale.US, "firmas/%06d.json", last.seq()), line.getBytes(StandardCharsets.UTF_8), "application/json");
        }
    }
}
