package com.josexato.navaja.sign.core;

import com.josexato.navaja.sign.core.Pdf.Dict;
import com.josexato.navaja.sign.core.Pdf.Name;
import com.josexato.navaja.sign.core.Pdf.Ref;
import com.josexato.navaja.sign.core.Pdf.Str;
import com.josexato.navaja.sign.core.Pdf.Stream;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Lector PDF mínimo, lo justo para agregar una actualización incremental:
 * tabla xref clásica o stream xref (PDF 1.5+, con predictores PNG), cadena
 * /Prev, streams de objetos, árbol de páginas con atributos heredados.
 * No modifica nada: el archivo original queda intacto byte a byte.
 */
public final class PdfFile {

    public static final class PdfException extends Exception {
        public PdfException(String m) { super(m); }
    }

    /** Página con sus atributos heredables ya resueltos. */
    public static final class Page {
        public final Ref ref;
        public final Dict dict;
        public final Dict resources;
        public final double[] mediaBox, cropBox;
        public final int rotate;

        Page(Ref ref, Dict dict, Dict resources, double[] mediaBox, double[] cropBox, int rotate) {
            this.ref = ref;
            this.dict = dict;
            this.resources = resources;
            this.mediaBox = mediaBox;
            this.cropBox = cropBox;
            this.rotate = rotate;
        }

        /** Caja visible (CropBox recortada a MediaBox). */
        public double[] box() { return cropBox != null ? cropBox : mediaBox; }
    }

    private static final class Entry {
        final int type; // 1 = offset, 2 = en stream de objetos
        final long a;
        final int b;

        Entry(int type, long a, int b) { this.type = type; this.a = a; this.b = b; }
    }

    public final byte[] data;
    public final Dict trailer;
    /** Offset del último xref (el /Prev de la próxima actualización). */
    public final long startxref;
    /** true si el último xref es un stream (la actualización debe usar también un stream xref). */
    public final boolean xrefStream;
    private final Map<Integer, Entry> xref = new HashMap<>();
    private final Map<Integer, Object> cache = new HashMap<>();
    private final Map<Integer, List<Object>> objStmCache = new HashMap<>();
    private List<Page> pages;

    public PdfFile(byte[] data) throws PdfException {
        this.data = data;
        int sx = Bytes.lastIndexOf(data, ascii("startxref"), data.length);
        if (sx < 0) throw new PdfException("No es un PDF (falta startxref)");
        Lexer lx = new Lexer(data, sx + 9);
        Object off = lx.next();
        if (!(off instanceof Long)) throw new PdfException("startxref inválido");
        startxref = (Long) off;
        Dict first = null;
        boolean firstIsStream = false;
        Set<Long> seen = new HashSet<>();
        List<Long> queue = new ArrayList<>();
        queue.add(startxref);
        while (!queue.isEmpty()) {
            long at = queue.remove(0);
            if (at < 0 || at >= data.length || !seen.add(at)) continue;
            Dict tr;
            boolean isStream;
            Lexer l = new Lexer(data, (int) at);
            if (l.peekKeyword("xref")) {
                tr = readClassic(l);
                isStream = false;
                Object xs = tr.get("XRefStm"); // archivos híbridos
                if (xs instanceof Long) queue.add(0, (Long) xs);
            } else {
                tr = readXrefStream((int) at);
                isStream = true;
            }
            if (first == null) { first = tr; firstIsStream = isStream; }
            Object prev = tr.get("Prev");
            if (prev instanceof Long) queue.add((Long) prev);
        }
        if (first == null) throw new PdfException("xref ilegible");
        trailer = first;
        xrefStream = firstIsStream;
        if (trailer.get("Encrypt") != null) throw new PdfException("El PDF está cifrado o tiene contraseña; no se puede firmar sin alterar el original");
        if (!(trailer.get("Root") instanceof Ref)) throw new PdfException("PDF sin catálogo (/Root)");
    }

    // ---------------------------------------------------------------- xref

    private Dict readClassic(Lexer l) throws PdfException {
        l.keyword(); // xref
        while (true) {
            if (l.peekKeyword("trailer")) {
                l.keyword();
                Object t = l.next();
                if (!(t instanceof Dict)) throw new PdfException("trailer inválido");
                return (Dict) t;
            }
            Object s = l.next(), c = l.next();
            if (!(s instanceof Long) || !(c instanceof Long)) throw new PdfException("Subsección xref inválida");
            long start = (Long) s, count = (Long) c;
            for (long i = 0; i < count; i++) {
                Object off = l.next(), gen = l.next();
                String kind = l.keyword();
                if (!(off instanceof Long) || !(gen instanceof Long)) throw new PdfException("Entrada xref inválida");
                int num = (int) (start + i);
                if (xref.containsKey(num)) continue; // gana la actualización más nueva
                if ("n".equals(kind)) xref.put(num, new Entry(1, (Long) off, (int) (long) (Long) gen));
                else xref.put(num, new Entry(0, 0, 0));
            }
        }
    }

    private Dict readXrefStream(int at) throws PdfException {
        Object o = parseIndirectAt(at, -1);
        if (!(o instanceof Stream)) throw new PdfException("xref no encontrado en " + at);
        Stream s = (Stream) o;
        Dict d = s.dict;
        byte[] b = decode(s);
        List<?> w = (List<?>) d.get("W");
        int w0 = num(w.get(0)), w1 = num(w.get(1)), w2 = num(w.get(2));
        List<?> index = (List<?>) d.get("Index");
        List<long[]> ranges = new ArrayList<>();
        if (index == null) ranges.add(new long[]{0, num(d.get("Size"))});
        else for (int i = 0; i + 1 < index.size(); i += 2) ranges.add(new long[]{num(index.get(i)), num(index.get(i + 1))});
        int p = 0, row = w0 + w1 + w2;
        for (long[] r : ranges) {
            for (long i = 0; i < r[1] && p + row <= b.length; i++, p += row) {
                int type = w0 == 0 ? 1 : (int) field(b, p, w0);
                long f2 = field(b, p + w0, w1);
                int f3 = (int) field(b, p + w0 + w1, w2);
                int num = (int) (r[0] + i);
                if (xref.containsKey(num)) continue;
                if (type == 1) xref.put(num, new Entry(1, f2, f3));
                else if (type == 2) xref.put(num, new Entry(2, f2, f3));
                else xref.put(num, new Entry(0, 0, 0));
            }
        }
        return d;
    }

    private static long field(byte[] b, int p, int w) {
        long v = 0;
        for (int i = 0; i < w; i++) v = v << 8 | (b[p + i] & 0xFF);
        return v;
    }

    private static int num(Object o) { return o instanceof Number ? ((Number) o).intValue() : 0; }

    // ------------------------------------------------------------ objetos

    public int size() {
        Object s = trailer.get("Size");
        int n = s instanceof Long ? (int) (long) (Long) s : 0;
        for (int k : xref.keySet()) n = Math.max(n, k + 1);
        return n;
    }

    public Set<Integer> objectNumbers() { return xref.keySet(); }

    public int generation(int num) {
        Entry e = xref.get(num);
        return e != null && e.type == 1 ? e.b : 0;
    }

    public Object get(Ref r) throws PdfException { return get(r.num); }

    public Object get(int num) throws PdfException {
        if (cache.containsKey(num)) return cache.get(num);
        Entry e = xref.get(num);
        Object v = null;
        if (e != null && e.type == 1) v = parseIndirectAt((int) e.a, num);
        else if (e != null && e.type == 2) {
            List<Object> objs = objStm((int) e.a);
            if (e.b < objs.size()) v = objs.get(e.b);
        }
        cache.put(num, v);
        return v;
    }

    /** Sigue referencias hasta un objeto directo. */
    public Object resolve(Object o) throws PdfException {
        for (int i = 0; i < 32 && o instanceof Ref; i++) o = get((Ref) o);
        return o;
    }

    public Dict dict(Object o) throws PdfException {
        o = resolve(o);
        if (o instanceof Dict) return (Dict) o;
        if (o instanceof Stream) return ((Stream) o).dict;
        return null;
    }

    private List<Object> objStm(int num) throws PdfException {
        List<Object> c = objStmCache.get(num);
        if (c != null) return c;
        Object o = get(num);
        if (!(o instanceof Stream)) throw new PdfException("Stream de objetos " + num + " ilegible");
        Stream s = (Stream) o;
        byte[] b = decode(s);
        int n = num(s.dict.get("N")), first = num(s.dict.get("First"));
        Lexer l = new Lexer(b, 0);
        int[] offs = new int[n];
        for (int i = 0; i < n; i++) {
            l.next();
            offs[i] = num(l.next());
        }
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new Lexer(b, first + offs[i]).next());
        objStmCache.put(num, out);
        return out;
    }

    private Object parseIndirectAt(int at, int expect) throws PdfException {
        Lexer l = new Lexer(data, at);
        Object n = l.next(), g = l.next();
        String kw = l.keyword();
        if (!(n instanceof Long) || !(g instanceof Long) || !"obj".equals(kw)) throw new PdfException("Objeto inválido en " + at);
        if (expect >= 0 && (Long) n != expect) throw new PdfException("xref apunta a otro objeto en " + at);
        Object v = l.next();
        if (v instanceof Dict && l.peekKeyword("stream")) {
            l.keyword();
            int p = l.pos;
            if (p < data.length && data[p] == '\r') p++;
            if (p < data.length && data[p] == '\n') p++;
            Dict d = (Dict) v;
            Object lenO = d.get("Length");
            int len = -1;
            if (lenO instanceof Long) len = (int) (long) (Long) lenO;
            else if (lenO instanceof Ref) {
                Object r = get((Ref) lenO);
                if (r instanceof Long) len = (int) (long) (Long) r;
            }
            if (len < 0 || p + len > data.length || Bytes.indexOf(data, ascii("endstream"), p + len) < 0
                    || Bytes.indexOf(data, ascii("endstream"), p + len) > p + len + 4) {
                // /Length errado: se busca "endstream".
                int end = Bytes.indexOf(data, ascii("endstream"), p);
                if (end < 0) throw new PdfException("Stream sin fin en " + at);
                len = end - p;
                while (len > 0 && (data[p + len - 1] == '\n' || data[p + len - 1] == '\r')) len--;
            }
            byte[] raw = new byte[len];
            System.arraycopy(data, p, raw, 0, len);
            return new Stream(d, raw);
        }
        return v;
    }

    /** Decodifica FlateDecode (con predictor PNG) o sin filtro. */
    public static byte[] decode(Stream s) throws PdfException {
        Object f = s.dict.get("Filter");
        if (f instanceof List && ((List<?>) f).size() == 1) f = ((List<?>) f).get(0);
        if (f == null) return s.raw;
        if (!(f instanceof Name) || !((Name) f).v.equals("FlateDecode")) throw new PdfException("Filtro no soportado: " + f);
        byte[] b = inflate(s.raw);
        Object dp = s.dict.get("DecodeParms");
        if (dp instanceof List && !((List<?>) dp).isEmpty()) dp = ((List<?>) dp).get(0);
        if (dp instanceof Dict) {
            int pred = num(((Dict) dp).get("Predictor"));
            if (pred >= 10) {
                Object c = ((Dict) dp).get("Columns");
                b = unpredictPng(b, c == null ? 1 : num(c));
            }
        }
        return b;
    }

    static byte[] inflate(byte[] in) throws PdfException {
        Inflater inf = new Inflater();
        inf.setInput(in);
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length * 3);
        byte[] buf = new byte[8192];
        try {
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break; // datos truncados: lo que haya
                out.write(buf, 0, n);
            }
        } catch (DataFormatException e) {
            throw new PdfException("Flate corrupto: " + e.getMessage());
        } finally {
            inf.end();
        }
        return out.toByteArray();
    }

    private static byte[] unpredictPng(byte[] b, int cols) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length);
        byte[] prev = new byte[cols], cur = new byte[cols];
        for (int p = 0; p + cols < b.length + 1 && p < b.length; p += cols + 1) {
            int type = b[p] & 0xFF;
            for (int i = 0; i < cols; i++) {
                int x = p + 1 + i < b.length ? b[p + 1 + i] & 0xFF : 0;
                int left = i > 0 ? cur[i - 1] & 0xFF : 0, up = prev[i] & 0xFF, ul = i > 0 ? prev[i - 1] & 0xFF : 0;
                int v;
                switch (type) {
                    case 1: v = x + left; break;
                    case 2: v = x + up; break;
                    case 3: v = x + (left + up) / 2; break;
                    case 4: {
                        int pa = Math.abs(up - ul), pb = Math.abs(left - ul), pc = Math.abs(left + up - 2 * ul);
                        v = x + (pa <= pb && pa <= pc ? left : pb <= pc ? up : ul);
                        break;
                    }
                    default: v = x;
                }
                cur[i] = (byte) v;
            }
            out.write(cur, 0, cols);
            byte[] t = prev; prev = cur; cur = t;
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------- páginas

    public Dict catalog() throws PdfException {
        Dict c = dict(trailer.get("Root"));
        if (c == null) throw new PdfException("Catálogo ilegible");
        return c;
    }

    public List<Page> pages() throws PdfException {
        if (pages != null) return pages;
        List<Page> out = new ArrayList<>();
        Object root = catalog().get("Pages");
        walk(root, null, null, null, 0, out, new HashSet<>(), 0);
        pages = out;
        return out;
    }

    private void walk(Object node, Object res, double[] mb, double[] cb, int rot, List<Page> out, Set<Ref> seen, int depth)
            throws PdfException {
        if (depth > 64 || !(node instanceof Ref) || !seen.add((Ref) node)) return;
        Dict d = dict(node);
        if (d == null) return;
        if (d.get("Resources") != null) res = d.get("Resources");
        double[] m = rect(d.get("MediaBox"));
        if (m != null) mb = m;
        double[] c = rect(d.get("CropBox"));
        if (c != null) cb = c;
        Object r = resolve(d.get("Rotate"));
        if (r instanceof Number) rot = ((Number) r).intValue();
        Object kids = resolve(d.get("Kids"));
        if (kids instanceof List && !d.isName("Type", "Page")) {
            for (Object k : (List<?>) kids) walk(k, res, mb, cb, rot, out, seen, depth + 1);
        } else {
            if (mb == null) mb = new double[]{0, 0, 612, 792};
            if (cb != null) cb = new double[]{Math.max(cb[0], mb[0]), Math.max(cb[1], mb[1]), Math.min(cb[2], mb[2]), Math.min(cb[3], mb[3])};
            Dict resD = dict(res);
            out.add(new Page((Ref) node, d, resD == null ? new Dict() : resD, mb, cb, ((rot % 360) + 360) % 360));
        }
    }

    private double[] rect(Object o) throws PdfException {
        o = resolve(o);
        if (!(o instanceof List) || ((List<?>) o).size() != 4) return null;
        double[] r = new double[4];
        for (int i = 0; i < 4; i++) {
            Object v = resolve(((List<?>) o).get(i));
            if (!(v instanceof Number)) return null;
            r[i] = ((Number) v).doubleValue();
        }
        return new double[]{Math.min(r[0], r[2]), Math.min(r[1], r[3]), Math.max(r[0], r[2]), Math.max(r[1], r[3])};
    }

    static byte[] ascii(String s) { return s.getBytes(StandardCharsets.ISO_8859_1); }

    // ------------------------------------------------------------- lexer

    /** Analizador léxico/sintáctico de objetos PDF directos. */
    public static final class Lexer {
        final byte[] b;
        int pos;

        public Lexer(byte[] b, int pos) { this.b = b; this.pos = pos; }

        static boolean ws(int c) { return c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32; }

        static boolean delim(int c) { return Pdf.DELIMS.indexOf(c) >= 0; }

        void skip() {
            while (pos < b.length) {
                int c = b[pos] & 0xFF;
                if (ws(c)) pos++;
                else if (c == '%') { while (pos < b.length && b[pos] != '\n' && b[pos] != '\r') pos++; }
                else break;
            }
        }

        boolean peekKeyword(String k) {
            skip();
            int save = pos;
            String w = word();
            pos = save;
            return k.equals(w);
        }

        String keyword() { skip(); return word(); }

        private String word() {
            int s = pos;
            while (pos < b.length && !ws(b[pos] & 0xFF) && !delim(b[pos] & 0xFF)) pos++;
            return new String(b, s, pos - s, StandardCharsets.ISO_8859_1);
        }

        /** Siguiente objeto; las referencias "n g R" se reconocen aquí. */
        public Object next() throws PdfException {
            skip();
            if (pos >= b.length) throw new PdfException("Fin inesperado del PDF");
            int c = b[pos] & 0xFF;
            switch (c) {
                case '/': return name();
                case '(': return literal();
                case '[': {
                    pos++;
                    List<Object> a = new ArrayList<>();
                    while (true) {
                        skip();
                        if (pos >= b.length) throw new PdfException("Array sin cerrar");
                        if (b[pos] == ']') { pos++; return a; }
                        a.add(next());
                    }
                }
                case '<': {
                    if (pos + 1 < b.length && b[pos + 1] == '<') {
                        pos += 2;
                        Dict d = new Dict();
                        while (true) {
                            skip();
                            if (pos + 1 >= b.length) throw new PdfException("Diccionario sin cerrar");
                            if (b[pos] == '>' && b[pos + 1] == '>') { pos += 2; return d; }
                            Object k = next();
                            if (!(k instanceof Name)) throw new PdfException("Clave de diccionario inválida en " + pos);
                            skip();
                            if (b[pos] == '>' && pos + 1 < b.length && b[pos + 1] == '>') { d.put(((Name) k).v, null); continue; }
                            d.put(((Name) k).v, next());
                        }
                    }
                    return hexString();
                }
                default:
                    if (c == '+' || c == '-' || c == '.' || (c >= '0' && c <= '9')) return number();
                    String w = word();
                    if (w.isEmpty()) { pos++; throw new PdfException("Carácter inesperado en " + (pos - 1)); }
                    switch (w) {
                        case "true": return Boolean.TRUE;
                        case "false": return Boolean.FALSE;
                        case "null": return null;
                        default: return new Keyword(w);
                    }
            }
        }

        private Object number() {
            int s = pos;
            if (b[pos] == '+' || b[pos] == '-') pos++;
            boolean real = false;
            while (pos < b.length && ((b[pos] >= '0' && b[pos] <= '9') || b[pos] == '.')) {
                if (b[pos] == '.') real = true;
                pos++;
            }
            String t = new String(b, s, pos - s, StandardCharsets.ISO_8859_1);
            if (real) {
                try { return Double.parseDouble(t); } catch (NumberFormatException e) { return 0.0; }
            }
            long v;
            try { v = Long.parseLong(t.startsWith("+") ? t.substring(1) : t); } catch (NumberFormatException e) { return 0L; }
            // ¿Es una referencia "n g R"?
            if (v >= 0) {
                int save = pos;
                skip();
                int s2 = pos;
                while (pos < b.length && b[pos] >= '0' && b[pos] <= '9') pos++;
                if (pos > s2) {
                    int gEnd = pos;
                    skip();
                    if (pos < b.length && b[pos] == 'R' && (pos + 1 >= b.length || ws(b[pos + 1] & 0xFF) || delim(b[pos + 1] & 0xFF))) {
                        pos++;
                        return new Ref((int) v, Integer.parseInt(new String(b, s2, gEnd - s2, StandardCharsets.ISO_8859_1)));
                    }
                }
                pos = save;
            }
            return v;
        }

        private Name name() {
            pos++;
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            while (pos < b.length && !ws(b[pos] & 0xFF) && !delim(b[pos] & 0xFF)) {
                int c = b[pos++] & 0xFF;
                if (c == '#' && pos + 1 < b.length) {
                    int h = Character.digit(b[pos], 16), l = Character.digit(b[pos + 1], 16);
                    if (h >= 0 && l >= 0) { o.write(h << 4 | l); pos += 2; continue; }
                }
                o.write(c);
            }
            return new Name(new String(o.toByteArray(), StandardCharsets.ISO_8859_1));
        }

        private Str literal() {
            pos++;
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            int depth = 1;
            while (pos < b.length) {
                int c = b[pos++] & 0xFF;
                if (c == '\\' && pos < b.length) {
                    int e = b[pos++] & 0xFF;
                    switch (e) {
                        case 'n': o.write('\n'); break;
                        case 'r': o.write('\r'); break;
                        case 't': o.write('\t'); break;
                        case 'b': o.write('\b'); break;
                        case 'f': o.write('\f'); break;
                        case '\r': if (pos < b.length && b[pos] == '\n') pos++; break;
                        case '\n': break;
                        default:
                            if (e >= '0' && e <= '7') {
                                int v = e - '0';
                                for (int k = 0; k < 2 && pos < b.length && b[pos] >= '0' && b[pos] <= '7'; k++) v = v * 8 + (b[pos++] - '0');
                                o.write(v & 0xFF);
                            } else o.write(e);
                    }
                    continue;
                }
                if (c == '(') depth++;
                if (c == ')' && --depth == 0) break;
                o.write(c);
            }
            return new Str(o.toByteArray(), false);
        }

        private Str hexString() {
            pos++;
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            int hi = -1;
            while (pos < b.length && b[pos] != '>') {
                int d = Character.digit(b[pos++], 16);
                if (d < 0) continue;
                if (hi < 0) hi = d;
                else { o.write(hi << 4 | d); hi = -1; }
            }
            pos++;
            if (hi >= 0) o.write(hi << 4);
            return new Str(o.toByteArray(), true);
        }
    }

    /** Palabra clave suelta (obj, stream, R…) devuelta por el lexer. */
    public static final class Keyword {
        public final String v;

        Keyword(String v) { this.v = v; }

        @Override public String toString() { return v; }
    }
}
