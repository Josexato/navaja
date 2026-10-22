package com.josexato.navaja.pantonera;

import android.content.Context;
import android.util.Log;

import com.josexato.navaja.pantonera.core.Stjf;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.Locale;

/**
 * "Mi pantonera": un archivo biblioteca.stjf y las texturas (id.jpg) en la
 * carpeta privada de la app (Android/data/com.josexato.navaja/files/pantonera).
 */
final class Library {

    private static final String TAG = "Pantonera";

    final File dir;
    private final File file;
    Stjf doc = new Stjf();

    Library(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        dir = new File(base, "pantonera");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        file = new File(dir, "biblioteca.stjf");
    }

    /** Carga la biblioteca; devuelve el informe de problemas (vacío si todo bien). */
    String load() {
        if (!file.exists()) return "";
        try {
            Stjf.Result r = Stjf.parse(readText(new FileInputStream(file)));
            doc = r.doc;
            return r.report();
        } catch (IOException e) {
            Log.e(TAG, "No se pudo leer la biblioteca", e);
            return "No se pudo leer la biblioteca: " + e.getMessage();
        }
    }

    void save() throws IOException {
        File tmp = new File(dir, "biblioteca.stjf.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(doc.write().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(file)) throw new IOException("no se pudo reemplazar biblioteca.stjf");
    }

    File texture(Stjf.Sample s) {
        if (s.appearance == null || s.appearance.texture == null) return null;
        File f = new File(dir, new File(s.appearance.texture).getName());
        return f.exists() ? f : null;
    }

    void remove(Stjf.Sample s) {
        File t = texture(s);
        if (t != null && !t.delete()) Log.w(TAG, "No se pudo borrar " + t);
        doc.samples.remove(s);
    }

    /** Agrega o reemplaza por id. */
    void put(Stjf.Sample s) {
        Stjf.Sample old = doc.find(s.id);
        if (old != null) doc.samples.set(doc.samples.indexOf(old), s);
        else doc.samples.add(s);
    }

    /** id válido para STJF a partir de un nombre ("Blanco hueso" → "blanco-hueso"), sin repetir. */
    String uniqueId(String name) {
        String base = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (base.isEmpty()) base = "muestra";
        String id = base;
        for (int i = 2; doc.find(id) != null; i++) id = base + "-" + i;
        return id;
    }

    /** Lee todo el flujo como UTF‑8 y lo cierra (java.nio.file exige Android 8). */
    static String readText(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = is.read(buf)) > 0; ) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
