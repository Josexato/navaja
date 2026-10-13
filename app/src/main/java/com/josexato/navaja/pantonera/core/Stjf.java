package com.josexato.navaja.pantonera.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * STJF v1.0 — Simple Texture JSON Format (hermano de SDJF de AlmaGag).
 * Modelo, lectura tolerante, validación y escritura. La especificación está en
 * docs/spec/STJF_v1.0_SPEC.md.
 */
public final class Stjf {

    public static final String VERSION = "1.0";
    public static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]+");
    public static final String[] FINISHES = {"matte", "satin", "gloss", "polished"};
    public static final String[] SHAPES = {"round", "angular", "flake", "vein"};

    public final List<Sample> samples = new ArrayList<>();

    // =============================================================== modelo

    /** Granulometría en mm (percentiles del diámetro equivalente). */
    public static final class Size {
        public Double p10, p50, p90;

        public Size() { }

        public Size(Double p10, Double p50, Double p90) {
            this.p10 = p10;
            this.p50 = p50;
            this.p90 = p90;
        }
    }

    /** Algo con color: la matriz (base) o un componente. */
    public static class Layer {
        public String color;   // CSS o hex, tal como vino
        public double[] lab;   // gana sobre color
        public Double fraction;

        /** Lab efectivo: lab, o color convertido, o null. */
        public double[] resolvedLab() {
            if (lab != null) return lab;
            return ColorMath.parseColor(color);
        }
    }

    public static final class Component extends Layer {
        public String id, label, shape;
        public Size size;

        public String name() { return label != null ? label : id; }
    }

    public static final class Appearance {
        public String texture, normalMap;
        public Double roughness, metallic, mmPerPx;
    }

    /** Datos de cómo se midió (lo llena la app al capturar). */
    public static final class Measurement {
        public Integer shots;
        public Double spreadDe;
        public String reference, date;
    }

    public static final class Sample {
        public String id, label, brand, code, finish;
        public String color;
        public double[] lab;
        public Layer base;
        public final List<Component> components = new ArrayList<>();
        public Appearance appearance;
        public Measurement measurement;
        /** Claves desconocidas: se conservan al reescribir el archivo. */
        public final Map<String, Object> unknown = new LinkedHashMap<>();

        public String name() { return label != null ? label : id; }

        public boolean isComposite() { return !components.isEmpty(); }

        /** Lab de la matriz: base.lab/color, o el de la muestra (lisa). */
        public double[] baseLab() {
            double[] l = base == null ? null : base.resolvedLab();
            if (l != null) return l;
            if (lab != null) return lab;
            return ColorMath.parseColor(color);
        }

        /** Rugosidad efectiva: medida, o la típica del acabado (para exportar). */
        public double roughness() {
            if (appearance != null && appearance.roughness != null) return appearance.roughness;
            return defaultRoughness(finish);
        }

        public double metallic() {
            return appearance != null && appearance.metallic != null ? appearance.metallic : 0;
        }

        /**
         * Composición resuelta: matriz + componentes con fracciones que suman 1.
         * Reglas (spec §5): la base sin fracción toma lo que sobra; si varios
         * no la traen, lo que sobra se reparte en partes iguales; si la suma
         * supera 1 se normaliza. Las capas sin color se omiten.
         */
        public List<Part> signature() {
            List<Layer> layers = new ArrayList<>();
            List<String> names = new ArrayList<>();
            double[] bl = baseLab();
            Layer b = new Layer();
            b.lab = bl;
            b.fraction = base != null ? base.fraction : (components.isEmpty() ? Double.valueOf(1) : null);
            if (bl != null) { layers.add(b); names.add("matriz"); }
            for (Component c : components) {
                if (c.resolvedLab() == null) continue;
                layers.add(c);
                names.add(c.name());
            }
            double known = 0;
            int missing = 0;
            for (Layer l : layers) {
                if (l.fraction != null) known += l.fraction;
                else missing++;
            }
            double rest = Math.max(0, 1 - known);
            List<Part> out = new ArrayList<>();
            double total = 0;
            for (int i = 0; i < layers.size(); i++) {
                Layer l = layers.get(i);
                double f = l.fraction != null ? l.fraction : (missing > 0 ? rest / missing : 0);
                Size s = l instanceof Component ? ((Component) l).size : null;
                out.add(new Part(names.get(i), l.resolvedLab(), f, s, i == 0 && bl != null));
                total += f;
            }
            if (total <= 0 && !out.isEmpty()) {
                for (Part p : out) p.fraction = 1.0 / out.size();
            } else if (Math.abs(total - 1) > 1e-9) {
                for (Part p : out) p.fraction /= total;
            }
            return out;
        }
    }

    /** Elemento de la composición resuelta. */
    public static final class Part {
        public final String name;
        public final double[] lab;
        public double fraction;
        public final Size size;
        public final boolean isBase;

        Part(String name, double[] lab, double fraction, Size size, boolean isBase) {
            this.name = name;
            this.lab = lab;
            this.fraction = fraction;
            this.size = size;
            this.isBase = isBase;
        }
    }

    public static double defaultRoughness(String finish) {
        if (finish == null) return 0.9;
        switch (finish) {
            case "satin": return 0.6;
            case "gloss": return 0.3;
            case "polished": return 0.1;
            default: return 0.9;
        }
    }

    public Sample find(String id) {
        for (Sample s : samples) if (s.id != null && s.id.equals(id)) return s;
        return null;
    }

    // ============================================================ validación

    public static final class Issue {
        public final boolean error;
        public final String where, message;

        Issue(boolean error, String where, String message) {
            this.error = error;
            this.where = where;
            this.message = message;
        }

        @Override
        public String toString() { return (error ? "ERROR " : "AVISO ") + where + ": " + message; }
    }

    /** Resultado de leer un archivo: documento + problemas encontrados. */
    public static final class Result {
        public final Stjf doc;
        public final List<Issue> issues;

        Result(Stjf doc, List<Issue> issues) {
            this.doc = doc;
            this.issues = issues;
        }

        public boolean ok() {
            for (Issue i : issues) if (i.error) return false;
            return true;
        }

        public String report() {
            StringBuilder sb = new StringBuilder();
            for (Issue i : issues) sb.append(i).append('\n');
            return sb.toString().trim();
        }
    }

    // ================================================================ lectura

    /** Lee un .stjf. Nunca lanza: los errores vienen en Result.issues. */
    public static Result parse(String text) {
        List<Issue> issues = new ArrayList<>();
        Stjf doc = new Stjf();
        Object root;
        try {
            root = Json.parse(text);
        } catch (Json.ParseException e) {
            issues.add(new Issue(true, "archivo", "JSON inválido: " + e.getMessage()));
            return new Result(doc, issues);
        }
        if (!(root instanceof Map)) {
            issues.add(new Issue(true, "archivo", "La raíz debe ser un objeto { \"samples\": [...] }"));
            return new Result(doc, issues);
        }
        Map<?, ?> m = (Map<?, ?>) root;
        Object ver = m.get("stjf");
        if (ver != null && !String.valueOf(ver).startsWith("1.")) {
            issues.add(new Issue(false, "archivo", "Versión " + ver + "; se lee como 1.0 (claves desconocidas se ignoran)"));
        }
        Object arr = m.get("samples");
        if (!(arr instanceof List)) {
            issues.add(new Issue(true, "archivo", "Falta el array \"samples\""));
            return new Result(doc, issues);
        }
        Set<String> ids = new HashSet<>();
        int idx = 0;
        for (Object o : (List<?>) arr) {
            String where = "samples[" + idx++ + "]";
            if (!(o instanceof Map)) {
                issues.add(new Issue(true, where, "Debe ser un objeto"));
                continue;
            }
            Sample s = readSample((Map<?, ?>) o, where, issues);
            if (s.id != null) {
                where = "muestra '" + s.id + "'";
                if (!ids.add(s.id)) issues.add(new Issue(true, where, "id repetido"));
            }
            validate(s, where, issues);
            doc.samples.add(s);
        }
        return new Result(doc, issues);
    }

    private static final Set<String> SAMPLE_KEYS = new HashSet<>(java.util.Arrays.asList(
            "id", "label", "brand", "code", "finish", "color", "lab", "hex", "base", "components",
            "appearance", "measurement"));

    private static Sample readSample(Map<?, ?> m, String where, List<Issue> issues) {
        Sample s = new Sample();
        s.id = str(m.get("id"));
        s.label = str(m.get("label"));
        s.brand = str(m.get("brand"));
        s.code = str(m.get("code"));
        s.finish = str(m.get("finish"));
        s.color = colorStr(m);
        s.lab = lab(m.get("lab"), where + ".lab", issues);
        Object b = m.get("base");
        if (b instanceof Map) {
            s.base = new Layer();
            readLayer(s.base, (Map<?, ?>) b, where + ".base", issues);
        }
        Object comps = m.get("components");
        if (comps instanceof List) {
            int k = 0;
            for (Object co : (List<?>) comps) {
                String w = where + ".components[" + k++ + "]";
                if (!(co instanceof Map)) {
                    issues.add(new Issue(true, w, "Debe ser un objeto"));
                    continue;
                }
                Map<?, ?> cm = (Map<?, ?>) co;
                Component c = new Component();
                c.id = str(cm.get("id"));
                c.label = str(cm.get("label"));
                c.shape = str(cm.get("shape"));
                readLayer(c, cm, w, issues);
                Object sz = cm.get("size_mm");
                if (sz instanceof Map) {
                    Map<?, ?> zm = (Map<?, ?>) sz;
                    c.size = new Size(dbl(zm.get("p10")), dbl(zm.get("p50")), dbl(zm.get("p90")));
                }
                s.components.add(c);
            }
        }
        Object ap = m.get("appearance");
        if (ap instanceof Map) {
            Map<?, ?> am = (Map<?, ?>) ap;
            Appearance a = new Appearance();
            a.texture = str(am.get("texture"));
            a.normalMap = str(am.get("normal_map"));
            a.roughness = dbl(am.get("roughness"));
            a.metallic = dbl(am.get("metallic"));
            a.mmPerPx = dbl(am.get("mm_per_px"));
            s.appearance = a;
        }
        Object me = m.get("measurement");
        if (me instanceof Map) {
            Map<?, ?> mm = (Map<?, ?>) me;
            Measurement x = new Measurement();
            Double shots = dbl(mm.get("shots"));
            x.shots = shots == null ? null : shots.intValue();
            x.spreadDe = dbl(mm.get("spread_de"));
            x.reference = str(mm.get("reference"));
            x.date = str(mm.get("date"));
            s.measurement = x;
        }
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String k = String.valueOf(e.getKey());
            if (!SAMPLE_KEYS.contains(k)) s.unknown.put(k, e.getValue());
        }
        return s;
    }

    private static void readLayer(Layer l, Map<?, ?> m, String where, List<Issue> issues) {
        l.color = colorStr(m);
        l.lab = lab(m.get("lab"), where + ".lab", issues);
        l.fraction = dbl(m.get("fraction"));
    }

    /** "color" o, por comodidad, "hex". */
    private static String colorStr(Map<?, ?> m) {
        String c = str(m.get("color"));
        return c != null ? c : str(m.get("hex"));
    }

    private static double[] lab(Object o, String where, List<Issue> issues) {
        if (o == null) return null;
        if (o instanceof List && ((List<?>) o).size() == 3) {
            double[] v = new double[3];
            for (int i = 0; i < 3; i++) {
                Object x = ((List<?>) o).get(i);
                if (!(x instanceof Number)) {
                    issues.add(new Issue(true, where, "Debe ser [L, a, b] con números"));
                    return null;
                }
                v[i] = ((Number) x).doubleValue();
            }
            return v;
        }
        issues.add(new Issue(true, where, "Debe ser [L, a, b]"));
        return null;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static Double dbl(Object o) { return o instanceof Number ? ((Number) o).doubleValue() : null; }

    private static void validate(Sample s, String where, List<Issue> issues) {
        if (s.id == null) issues.add(new Issue(true, where, "Falta \"id\""));
        else if (!ID.matcher(s.id).matches())
            issues.add(new Issue(true, where, "id sólo admite letras, números, '-' y '_'"));
        if (s.finish != null && !in(s.finish, FINISHES))
            issues.add(new Issue(false, where, "finish desconocido '" + s.finish + "' (se usa matte)"));
        checkLayer(s.color, s.lab, null, where, issues);
        if (s.base != null) checkLayer(s.base.color, s.base.lab, s.base.fraction, where + ".base", issues);
        if (s.baseLab() == null && s.components.isEmpty())
            issues.add(new Issue(true, where, "Sin color: falta color, lab, base o components"));

        double sum = s.base != null && s.base.fraction != null ? s.base.fraction : 0;
        Set<String> cids = new HashSet<>();
        for (int i = 0; i < s.components.size(); i++) {
            Component c = s.components.get(i);
            String w = where + ".components[" + i + "]";
            if (c.id == null) issues.add(new Issue(true, w, "Falta \"id\""));
            else {
                w = where + " · componente '" + c.id + "'";
                if (!ID.matcher(c.id).matches())
                    issues.add(new Issue(true, w, "id sólo admite letras, números, '-' y '_'"));
                if (!cids.add(c.id)) issues.add(new Issue(true, w, "id de componente repetido"));
            }
            checkLayer(c.color, c.lab, c.fraction, w, issues);
            if (c.resolvedLab() == null) issues.add(new Issue(true, w, "Sin color: falta color o lab"));
            if (c.fraction != null) sum += c.fraction;
            if (c.shape != null && !in(c.shape, SHAPES))
                issues.add(new Issue(false, w, "shape desconocido '" + c.shape + "'"));
            if (c.size != null) {
                Size z = c.size;
                for (Double d : new Double[]{z.p10, z.p50, z.p90})
                    if (d != null && d <= 0) issues.add(new Issue(true, w, "size_mm debe ser > 0"));
                if (z.p10 != null && z.p50 != null && z.p10 > z.p50
                        || z.p50 != null && z.p90 != null && z.p50 > z.p90
                        || z.p10 != null && z.p90 != null && z.p10 > z.p90)
                    issues.add(new Issue(true, w, "size_mm debe cumplir p10 ≤ p50 ≤ p90"));
            }
        }
        if (sum > 1.001)
            issues.add(new Issue(false, where, String.format(Locale.US,
                    "Las fracciones suman %.2f; se normalizan a 1", sum)));
        Appearance a = s.appearance;
        if (a != null) {
            range(a.roughness, 0, 1, "appearance.roughness", where, issues);
            range(a.metallic, 0, 1, "appearance.metallic", where, issues);
            if (a.mmPerPx != null && a.mmPerPx <= 0)
                issues.add(new Issue(true, where, "appearance.mm_per_px debe ser > 0"));
        }
    }

    private static void checkLayer(String color, double[] lab, Double fraction, String where, List<Issue> issues) {
        if (color != null && ColorMath.parseColor(color) == null)
            issues.add(new Issue(lab == null, where, "color no reconocido '" + color + "'"));
        if (lab != null) {
            if (lab[0] < 0 || lab[0] > 100) issues.add(new Issue(true, where, "L* debe estar entre 0 y 100"));
            if (Math.abs(lab[1]) > 128 || Math.abs(lab[2]) > 128)
                issues.add(new Issue(true, where, "a* y b* deben estar entre -128 y 128"));
        }
        range(fraction, 0, 1, "fraction", where, issues);
    }

    private static void range(Double v, double lo, double hi, String name, String where, List<Issue> issues) {
        if (v != null && (v < lo || v > hi))
            issues.add(new Issue(true, where, name + " debe estar entre " + Json.num(lo) + " y " + Json.num(hi)));
    }

    private static boolean in(String s, String[] set) {
        for (String x : set) if (x.equals(s)) return true;
        return false;
    }

    // ============================================================== escritura

    public String write() { return Json.write(toMap()); }

    public Map<String, Object> toMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("stjf", VERSION);
        List<Object> arr = new ArrayList<>();
        for (Sample s : samples) arr.add(sampleToMap(s));
        root.put("samples", arr);
        return root;
    }

    public static Map<String, Object> sampleToMap(Sample s) {
        Map<String, Object> m = new LinkedHashMap<>();
        put(m, "id", s.id);
        put(m, "label", s.label);
        put(m, "brand", s.brand);
        put(m, "code", s.code);
        put(m, "finish", s.finish);
        put(m, "color", s.color);
        put(m, "lab", labList(s.lab));
        if (s.base != null) m.put("base", layerToMap(new LinkedHashMap<>(), s.base));
        if (!s.components.isEmpty()) {
            List<Object> cs = new ArrayList<>();
            for (Component c : s.components) {
                Map<String, Object> cm = new LinkedHashMap<>();
                put(cm, "id", c.id);
                put(cm, "label", c.label);
                layerToMap(cm, c);
                if (c.size != null) {
                    Map<String, Object> z = new LinkedHashMap<>();
                    put(z, "p10", round(c.size.p10, 2));
                    put(z, "p50", round(c.size.p50, 2));
                    put(z, "p90", round(c.size.p90, 2));
                    if (!z.isEmpty()) cm.put("size_mm", z);
                }
                put(cm, "shape", c.shape);
                cs.add(cm);
            }
            m.put("components", cs);
        }
        if (s.appearance != null) {
            Appearance a = s.appearance;
            Map<String, Object> am = new LinkedHashMap<>();
            put(am, "texture", a.texture);
            put(am, "roughness", round(a.roughness, 3));
            put(am, "metallic", round(a.metallic, 3));
            put(am, "mm_per_px", round(a.mmPerPx, 5));
            put(am, "normal_map", a.normalMap);
            if (!am.isEmpty()) m.put("appearance", am);
        }
        if (s.measurement != null) {
            Measurement x = s.measurement;
            Map<String, Object> mm = new LinkedHashMap<>();
            put(mm, "shots", x.shots);
            put(mm, "spread_de", round(x.spreadDe, 2));
            put(mm, "reference", x.reference);
            put(mm, "date", x.date);
            if (!mm.isEmpty()) m.put("measurement", mm);
        }
        m.putAll(s.unknown);
        return m;
    }

    private static Map<String, Object> layerToMap(Map<String, Object> m, Layer l) {
        put(m, "color", l.color);
        put(m, "lab", labList(l.lab));
        put(m, "fraction", round(l.fraction, 4));
        return m;
    }

    private static List<Object> labList(double[] lab) {
        if (lab == null) return null;
        List<Object> a = new ArrayList<>();
        for (double v : lab) a.add(Math.round(v * 100) / 100.0);
        return a;
    }

    private static Double round(Double v, int dec) {
        if (v == null) return null;
        double p = Math.pow(10, dec);
        return Math.round(v * p) / p;
    }

    private static void put(Map<String, Object> m, String k, Object v) {
        if (v != null) m.put(k, v);
    }
}
