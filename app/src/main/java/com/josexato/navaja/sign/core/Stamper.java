package com.josexato.navaja.sign.core;

import com.josexato.navaja.sign.core.Pdf.Dict;
import com.josexato.navaja.sign.core.Pdf.Ref;
import com.josexato.navaja.sign.core.Pdf.Str;
import com.josexato.navaja.sign.core.Pdf.Stream;
import com.josexato.navaja.sign.core.PdfFile.Page;
import com.josexato.navaja.sign.core.PdfFile.PdfException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Agrega el sello visible (firma dibujada + QR + dos líneas de texto con el
 * enlace a la guía) a una página, como actualización incremental. Todo es
 * vectorial: trazos con el grosor de la presión del lápiz, el QR como
 * rectángulos y texto Helvetica (fuente estándar, no se incrusta nada).
 */
public final class Stamper {

    private Stamper() { }

    /** Ancho/alto del sello: firma a la izquierda (con el texto debajo) y QR cuadrado a la derecha. */
    public static final double ASPECT = 3.3;
    /**
     * Margen mínimo al borde de la página (~6 mm): casi ninguna impresora
     * imprime más cerca, y un QR o un enlace cortado no se puede verificar.
     */
    public static final double PRINT_MARGIN = 17;

    /** Margen efectivo: el de impresión, salvo en páginas tan chicas que el sello no cabría. */
    public static double margin(double pageSide, double stampSide) {
        return Math.max(0, Math.min(PRINT_MARGIN, (pageSide - stampSide) / 2));
    }

    /** Proporción del área de firma dentro del sello. */
    static final double SIG_W = 2.2, GAP = 0.1, TEXT_H = 0.24;

    /** Trazos normalizados: cada trazo es [x0,y0,p0, x1,y1,p1, …], x e y en 0..1 (y hacia abajo), p = presión 0..1. */
    public static final class Ink {
        public final List<float[]> strokes;
        /** Ancho/alto del lienzo donde se dibujó. */
        public final float aspect;

        public Ink(List<float[]> strokes, float aspect) {
            this.strokes = strokes;
            this.aspect = aspect;
        }
    }

    public static final class Spec {
        public int page;
        /** Esquina superior izquierda y ancho, como fracción de la página TAL COMO SE VE (ya rotada). */
        public double u, v, w;
        public Ink ink;
        /** Color de la tinta 0xRRGGBB. */
        public int ink_rgb = 0x14307A;
        public String qrText;
        public String[] lines = new String[0];
        /** URL del enlace clicable sobre la última línea (o null). */
        public String linkUrl;
    }

    /** Tamaño de la página visible (ancho, alto) en puntos, ya considerando /Rotate. */
    public static double[] visualSize(Page p) {
        double[] b = p.box();
        double w = b[2] - b[0], h = b[3] - b[1];
        return p.rotate == 90 || p.rotate == 270 ? new double[]{h, w} : new double[]{w, h};
    }

    /** Matriz [a b c d e f] que lleva coordenadas "visuales" (origen abajo a la izquierda de lo que se ve) al espacio de la página. */
    static double[] visualToUser(Page p) {
        double[] b = p.box();
        double x0 = b[0], y0 = b[1], w = b[2] - b[0], h = b[3] - b[1];
        switch (p.rotate) {
            case 90: return new double[]{0, 1, -1, 0, x0 + w, y0};
            case 180: return new double[]{-1, 0, 0, -1, x0 + w, y0 + h};
            case 270: return new double[]{0, -1, 1, 0, x0, y0 + h};
            default: return new double[]{1, 0, 0, 1, x0, y0};
        }
    }

    public static byte[] stamp(PdfFile f, Spec s) throws PdfException {
        List<Page> pages = f.pages();
        if (s.page < 0 || s.page >= pages.size()) throw new PdfException("Página fuera de rango");
        Page p = pages.get(s.page);
        double[] vs = visualSize(p);
        double bw = s.w * vs[0], bh = bw / ASPECT;
        // El sello siempre queda completo dentro de la página.
        double mx = margin(vs[0], bw), my = margin(vs[1], bh);
        double bx = clamp(s.u * vs[0], mx, vs[0] - bw - mx), by = clamp(vs[1] - s.v * vs[1] - bh, my, vs[1] - bh - my);
        double[] m = visualToUser(p);

        PdfUpdate up = new PdfUpdate(f);
        String font = uniqueName(f, p.resources, "SSJFHelv");
        Ref fontRef = up.add(new Dict().with("Type", Pdf.name("Font")).with("Subtype", Pdf.name("Type1"))
                .with("BaseFont", Pdf.name("Helvetica")).with("Encoding", Pdf.name("WinAnsiEncoding")));

        StringBuilder c = new StringBuilder("Q\nq\n");
        c.append(n(m[0])).append(' ').append(n(m[1])).append(' ').append(n(m[2])).append(' ').append(n(m[3])).append(' ')
                .append(n(m[4])).append(' ').append(n(m[5])).append(" cm\n");
        double qs = bh, qx = bx + bw - qs, qy = by;
        drawQr(c, QrCode.encode(s.qrText, QrCode.Ecc.M), qx, qy, qs);
        double sigW = bh * SIG_W, textH = bh * TEXT_H;
        drawInk(c, s.ink, s.ink_rgb, bx, by + textH, Math.min(sigW, bw - qs - bh * GAP), bh - textH);
        double[] linkRect = drawText(c, s.lines, font, bx, by, Math.min(sigW, bw - qs - bh * GAP), textH);
        c.append("Q\n");

        Ref pre = up.add(new Stream(new Dict(), "q\n".getBytes(StandardCharsets.ISO_8859_1)));
        // Comprimido: el QR son cientos de rectángulos y los trazos miles de segmentos (~40 KB → ~8 KB).
        Ref post = up.add(new Stream(new Dict().with("Filter", Pdf.name("FlateDecode")),
                deflate(c.toString().getBytes(StandardCharsets.ISO_8859_1))));

        Dict page = new Dict(p.dict);
        List<Object> contents = new ArrayList<>();
        contents.add(pre);
        Object oc = page.get("Contents");
        Object ocr = f.resolve(oc);
        if (oc instanceof Ref && ocr instanceof Stream) contents.add(oc);
        else if (ocr instanceof List) contents.addAll((List<?>) ocr);
        contents.add(post);
        page.put("Contents", contents);

        Dict res = new Dict(p.resources);
        Dict fonts = f.dict(res.get("Font"));
        fonts = fonts == null ? new Dict() : new Dict(fonts);
        fonts.put(font, fontRef);
        res.put("Font", fonts);
        page.put("Resources", res);
        // Atributos heredados explícitos: la página nueva no depende del árbol.
        if (page.get("MediaBox") == null) page.put("MediaBox", rect(p.mediaBox));
        if (page.get("CropBox") == null && p.cropBox != null) page.put("CropBox", rect(p.cropBox));
        if (page.get("Rotate") == null && p.rotate != 0) page.put("Rotate", (long) p.rotate);

        if (s.linkUrl != null && linkRect != null) {
            double[] r = transformRect(m, linkRect);
            Dict annot = new Dict().with("Type", Pdf.name("Annot")).with("Subtype", Pdf.name("Link"))
                    .with("Rect", rect(r)).with("Border", PdfUpdate.list(0L, 0L, 0L)).with("F", 4L)
                    .with("A", new Dict().with("S", Pdf.name("URI")).with("URI", Str.of(s.linkUrl)))
                    .with("P", p.ref);
            Ref ar = up.add(annot);
            List<Object> annots = new ArrayList<>();
            Object oa = f.resolve(page.get("Annots"));
            if (oa instanceof List) annots.addAll((List<?>) oa);
            annots.add(ar);
            page.put("Annots", annots);
        }
        up.replace(p.ref, page);
        return up.write();
    }

    /** Segunda actualización: el anexo JSON (con el resumen de después, sellos de tiempo…), referenciado desde el catálogo. */
    public static byte[] addAnnex(PdfFile f, String json) throws PdfException {
        PdfUpdate up = new PdfUpdate(f);
        Dict d = new Dict().with("Type", Pdf.name("SSJF")).with("V", 1L);
        Ref ar = up.add(new Stream(d, json.getBytes(StandardCharsets.UTF_8)));
        up.comment(ar, Annex.MARKER);
        Ref root = (Ref) f.trailer.get("Root");
        Dict cat = new Dict(f.catalog());
        List<Object> list = new ArrayList<>();
        Object old = f.resolve(cat.get("SSJF"));
        if (old instanceof List) list.addAll((List<?>) old);
        list.add(ar);
        cat.put("SSJF", list);
        up.replace(root, cat);
        return up.write();
    }

    // ------------------------------------------------------------- dibujo

    static void drawQr(StringBuilder c, QrCode q, double x, double y, double side) {
        int quiet = 2, n = q.size + 2 * quiet;
        double m = side / n;
        c.append("1 1 1 rg\n").append(n(x)).append(' ').append(n(y)).append(' ').append(n(side)).append(' ').append(n(side)).append(" re f\n");
        c.append("0 0 0 rg\n");
        for (int row = 0; row < q.size; row++) {
            for (int col = 0; col < q.size; ) {
                if (!q.dark(col, row)) { col++; continue; }
                int start = col;
                while (col < q.size && q.dark(col, row)) col++;
                double rx = x + (start + quiet) * m, ry = y + side - (row + quiet + 1) * m;
                // +0.01 de solape: evita líneas finas blancas entre módulos al rasterizar.
                c.append(n(rx)).append(' ').append(n(ry)).append(' ').append(n((col - start) * m + 0.01)).append(' ')
                        .append(n(m + 0.01)).append(" re\n");
            }
        }
        c.append("f\n");
    }

    static void drawInk(StringBuilder c, Ink ink, int rgb, double x, double y, double w, double h) {
        if (ink == null || ink.strokes.isEmpty()) return;
        double a = ink.aspect > 0 ? ink.aspect : 3;
        double s = Math.min(w / a, h); // alto del lienzo escalado
        double ox = x, oy = y + (h - s) / 2;
        c.append(n(((rgb >> 16) & 255) / 255.0)).append(' ').append(n(((rgb >> 8) & 255) / 255.0)).append(' ')
                .append(n((rgb & 255) / 255.0)).append(" RG 1 J 1 j\n");
        double base = s * 0.022, lastW = -1;
        for (float[] st : ink.strokes) {
            int pts = st.length / 3;
            if (pts == 0) continue;
            if (pts == 1) {
                double px = ox + st[0] * s * a, py = oy + (1 - st[1]) * s, lw = base * (0.4 + 1.2 * st[2]);
                c.append(n(lw)).append(" w ").append(n(px)).append(' ').append(n(py)).append(" m ")
                        .append(n(px)).append(' ').append(n(py)).append(" l S\n");
                lastW = lw;
                continue;
            }
            for (int i = 1; i < pts; i++) {
                double p = (st[3 * i - 1] + st[3 * i + 2]) / 2;
                double lw = Math.round(base * (0.4 + 1.2 * p) * 20) / 20.0;
                if (lw != lastW) {
                    c.append(n(Math.max(lw, 0.05))).append(" w\n");
                    lastW = lw;
                }
                c.append(n(ox + st[3 * i - 3] * s * a)).append(' ').append(n(oy + (1 - st[3 * i - 2]) * s)).append(" m ")
                        .append(n(ox + st[3 * i] * s * a)).append(' ').append(n(oy + (1 - st[3 * i + 1]) * s)).append(" l S\n");
            }
        }
    }

    /** Escribe las líneas (ajustadas al ancho) y devuelve el rectángulo de la última, para el enlace. */
    static double[] drawText(StringBuilder c, String[] lines, String font, double x, double y, double w, double h) {
        if (lines == null || lines.length == 0) return null;
        double lead = h / lines.length;
        double[] last = null;
        c.append("0.25 0.27 0.30 rg\n");
        for (int i = 0; i < lines.length; i++) {
            String t = winAnsiSafe(lines[i]);
            double fs = Math.min(lead * 0.72, w * 1000 / Math.max(1, width(t)));
            double ty = y + h - lead * (i + 1) + (lead - fs) * 0.45;
            c.append("BT /").append(font).append(' ').append(n(fs)).append(" Tf ").append(n(x)).append(' ').append(n(ty))
                    .append(" Td ").append(pdfString(t)).append(" Tj ET\n");
            last = new double[]{x, ty - fs * 0.25, x + width(t) * fs / 1000, ty + fs * 0.85};
        }
        return last;
    }

    // Anchos de Helvetica (AFM estándar) para ASCII 32..126, en milésimas de em.
    private static final short[] HELV = {
        278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
        1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
        333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
        556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584,
    };

    static double width(String s) {
        double w = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            w += ch >= 32 && ch <= 126 ? HELV[ch - 32] : 556;
        }
        return w;
    }

    /** Deja sólo caracteres que existen en WinAnsi/Latin-1 (los acentos del español sí). */
    static String winAnsiSafe(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) sb.append(ch >= 32 && ch <= 255 && (ch < 127 || ch > 159) ? ch : '?');
        return sb.toString();
    }

    static String pdfString(String s) {
        StringBuilder sb = new StringBuilder("(");
        for (char ch : s.toCharArray()) {
            if (ch == '(' || ch == ')' || ch == '\\') sb.append('\\').append(ch);
            else if (ch > 126) sb.append(String.format(Locale.US, "\\%03o", (int) ch));
            else sb.append(ch);
        }
        return sb.append(')').toString();
    }

    private static String uniqueName(PdfFile f, Dict res, String base) throws PdfException {
        Dict fonts = f.dict(res.get("Font"));
        String name = base;
        for (int i = 2; fonts != null && fonts.containsKey(name); i++) name = base + i;
        return name;
    }

    static double[] transformRect(double[] m, double[] r) {
        double[] xs = new double[4], ys = new double[4];
        double[][] pts = {{r[0], r[1]}, {r[2], r[1]}, {r[0], r[3]}, {r[2], r[3]}};
        for (int i = 0; i < 4; i++) {
            xs[i] = m[0] * pts[i][0] + m[2] * pts[i][1] + m[4];
            ys[i] = m[1] * pts[i][0] + m[3] * pts[i][1] + m[5];
        }
        return new double[]{min(xs), min(ys), max(xs), max(ys)};
    }

    private static double min(double[] a) { double v = a[0]; for (double x : a) v = Math.min(v, x); return v; }

    private static double max(double[] a) { double v = a[0]; for (double x : a) v = Math.max(v, x); return v; }

    private static List<Object> rect(double[] r) {
        return PdfUpdate.list(r[0], r[1], r[2], r[3]);
    }

    private static String n(double d) { return Pdf.num(d); }

    static byte[] deflate(byte[] b) {
        java.util.zip.Deflater d = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION);
        d.setInput(b);
        d.finish();
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(b.length / 3 + 64);
        byte[] buf = new byte[8192];
        while (!d.finished()) o.write(buf, 0, d.deflate(buf));
        d.end();
        return o.toByteArray();
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(v, Math.max(lo, hi))); }
}
