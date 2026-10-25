package com.josexato.navaja.sign.core;

import com.josexato.navaja.sign.core.Pdf.Dict;
import com.josexato.navaja.sign.core.Pdf.Ref;
import com.josexato.navaja.sign.core.Pdf.Stream;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Actualización incremental (ISO 32000-1 §7.5.6): objetos nuevos o
 * reemplazados, un xref y un trailer con /Prev, todo agregado al FINAL. Los
 * primeros N bytes siguen siendo exactamente el archivo original, así que
 * {@code head -c N firmado.pdf | sha256sum} devuelve el resumen de antes.
 */
public final class PdfUpdate {

    private final PdfFile src;
    private final Map<Integer, Object> objs = new TreeMap<>();
    private final Map<Integer, Integer> gens = new TreeMap<>();
    private final Map<Integer, String> comments = new TreeMap<>();
    private int next;

    public PdfUpdate(PdfFile src) {
        this.src = src;
        next = src.size();
    }

    public Ref add(Object value) {
        int n = next++;
        objs.put(n, value);
        gens.put(n, 0);
        return new Ref(n, 0);
    }

    /** Reemplaza un objeto existente conservando su número y generación. */
    public void replace(Ref r, Object value) {
        objs.put(r.num, value);
        gens.put(r.num, r.gen);
    }

    /** Comentario PDF antes del objeto (facilita encontrarlo con un editor de texto). */
    public void comment(Ref r, String text) { comments.put(r.num, text); }

    /** Bytes completos: original + actualización. */
    public byte[] write() {
        byte[] orig = src.data;
        ByteArrayOutputStream out = new ByteArrayOutputStream(orig.length + 4096);
        out.write(orig, 0, orig.length);
        if (orig.length > 0 && orig[orig.length - 1] != '\n' && orig[orig.length - 1] != '\r') out.write('\n');
        Map<Integer, Long> offsets = new TreeMap<>();
        for (Map.Entry<Integer, Object> e : objs.entrySet()) {
            String c = comments.get(e.getKey());
            if (c != null) Pdf.ascii(out, "%" + c + "\n");
            offsets.put(e.getKey(), (long) out.size());
            Pdf.ascii(out, e.getKey() + " " + gens.get(e.getKey()) + " obj\n");
            Pdf.write(out, e.getValue());
            Pdf.ascii(out, "\nendobj\n");
        }
        Dict tr = new Dict();
        int size = next;
        tr.put("Root", src.trailer.get("Root"));
        if (src.trailer.get("Info") != null) tr.put("Info", src.trailer.get("Info"));
        if (src.trailer.get("ID") != null) tr.put("ID", src.trailer.get("ID"));
        tr.put("Prev", src.startxref);
        long xrefAt = out.size();
        if (src.xrefStream) {
            int self = next;
            size = next + 1;
            offsets.put(self, xrefAt);
            gens.put(self, 0);
            List<Object> index = new ArrayList<>();
            ByteArrayOutputStream rows = new ByteArrayOutputStream();
            for (int[] run : runs(offsets)) {
                index.add((long) run[0]);
                index.add((long) run[1]);
                for (int n = run[0]; n < run[0] + run[1]; n++) {
                    long off = offsets.get(n);
                    rows.write(1);
                    for (int k = 3; k >= 0; k--) rows.write((int) (off >> (8 * k)) & 0xFF);
                    int g = gens.get(n);
                    rows.write(g >> 8 & 0xFF);
                    rows.write(g & 0xFF);
                }
            }
            Dict d = new Dict().with("Type", Pdf.name("XRef")).with("Size", (long) size)
                    .with("W", list(1L, 4L, 2L)).with("Index", index);
            d.putAll(tr);
            Pdf.ascii(out, self + " 0 obj\n");
            Pdf.write(out, new Stream(d, rows.toByteArray()));
            Pdf.ascii(out, "\nendobj\n");
        } else {
            // Se incluye la entrada 0 (libre) como hace Acrobat: algunos lectores esperan que cada
            // sección empiece en 0 y si no, "corrigen" los números de objeto.
            Pdf.ascii(out, "xref\n0 1\n0000000000 65535 f\r\n");
            for (int[] run : runs(offsets)) {
                Pdf.ascii(out, run[0] + " " + run[1] + "\n");
                for (int n = run[0]; n < run[0] + run[1]; n++)
                    Pdf.ascii(out, String.format(Locale.US, "%010d %05d n\r\n", offsets.get(n), gens.get(n)));
            }
            tr.put("Size", (long) size);
            Pdf.ascii(out, "trailer\n");
            Pdf.write(out, sizeFirst(tr));
            Pdf.ascii(out, "\n");
        }
        Pdf.ascii(out, "startxref\n" + xrefAt + "\n%%EOF\n");
        return out.toByteArray();
    }

    private static Dict sizeFirst(Dict tr) {
        Dict d = new Dict().with("Size", tr.get("Size"));
        for (Map.Entry<String, Object> e : tr.entrySet()) if (!e.getKey().equals("Size")) d.put(e.getKey(), e.getValue());
        return d;
    }

    private static List<int[]> runs(Map<Integer, Long> offsets) {
        List<int[]> r = new ArrayList<>();
        int start = -1, len = 0;
        for (int n : offsets.keySet()) {
            if (start >= 0 && n == start + len) { len++; continue; }
            if (start >= 0) r.add(new int[]{start, len});
            start = n;
            len = 1;
        }
        if (start >= 0) r.add(new int[]{start, len});
        return r;
    }

    static List<Object> list(Object... v) {
        List<Object> l = new ArrayList<>();
        java.util.Collections.addAll(l, v);
        return l;
    }
}
