package com.josexato.navaja.pantonera.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/** Color (ΔE2000, conversiones), JSON y formato STJF: lectura, validación, escritura. */
public class ColorAndStjfTest {

    static final String TERRAZZO = "{\n"
            + "  \"stjf\": \"1.0\",\n"
            + "  \"samples\": [\n"
            + "    { \"id\": \"tz-204\", \"label\": \"Terrazzo gris\", \"brand\": \"Proveedor X\", \"code\": \"TZ-204\",\n"
            + "      \"finish\": \"polished\",\n"
            + "      \"base\": { \"lab\": [62.0, 0.4, 3.1], \"fraction\": 0.55 },\n"
            + "      \"components\": [\n"
            + "        { \"id\": \"marmol\", \"label\": \"Mármol blanco\", \"lab\": [90.1, 0.2, 2.0], \"fraction\": 0.25,\n"
            + "          \"size_mm\": { \"p10\": 3, \"p50\": 8, \"p90\": 15 }, \"shape\": \"angular\" },\n"
            + "        { \"id\": \"negra\", \"color\": \"#2B2A2C\", \"fraction\": 0.12, \"size_mm\": { \"p50\": 5 } },\n"
            + "        { \"id\": \"vidrio\", \"label\": \"Vidrio verde\", \"color\": \"#4F7A5A\", \"fraction\": 0.08 }\n"
            + "      ],\n"
            + "      \"appearance\": { \"roughness\": 0.15, \"mm_per_px\": 0.1 },\n"
            + "      \"x-proveedor\": { \"lote\": 7 } },\n"
            + "    { \"id\": \"a101\", \"label\": \"Blanco hueso\", \"color\": \"#E3DACB\" }\n"
            + "  ]\n"
            + "}";

    // ----------------------------------------------------------------- color

    @Test
    public void deltaE2000MatchesSharmaTable() {
        // Pares de prueba publicados por Sharma, Wu y Dalal (2005).
        double[][][] pairs = {
                {{50, 2.6772, -79.7751}, {50, 0, -82.7485}},
                {{50, 3.1571, -77.2803}, {50, 0, -82.7485}},
                {{50, 2.8361, -74.0200}, {50, 0, -82.7485}},
                {{50, 0, 0}, {50, -1, 2}},
                {{50, 2.5, 0}, {73, 25, -18}},
                {{60.2574, -34.0099, 36.2677}, {60.4626, -34.1751, 39.4387}},
                {{2.0776, 0.0795, -1.1350}, {0.9033, -0.0636, -0.5514}},
        };
        double[] expected = {2.0425, 2.8615, 3.4412, 2.3669, 27.1492, 1.2644, 0.9082};
        for (int i = 0; i < pairs.length; i++) {
            assertEquals("par " + i, expected[i], ColorMath.deltaE2000(pairs[i][0], pairs[i][1]), 1e-4);
            assertEquals("simétrico " + i, expected[i], ColorMath.deltaE2000(pairs[i][1], pairs[i][0]), 1e-4);
        }
    }

    @Test
    public void srgbLabRoundTrip() {
        double[] red = ColorMath.srgbToLab(1, 0, 0);
        assertEquals(53.24, red[0], 0.05);
        assertEquals(80.09, red[1], 0.1);
        assertEquals(67.20, red[2], 0.1);
        double[] white = ColorMath.srgbToLab(1, 1, 1);
        assertEquals(100, white[0], 0.01);
        assertEquals(0, white[1], 0.01);
        assertEquals(0, white[2], 0.01);
        assertEquals("#E3DACB", ColorMath.labToHex(ColorMath.parseColor("#e3dacb")));
        assertEquals("#FFFFFF", ColorMath.labToHex(ColorMath.parseColor("#fff")));
        assertEquals("#F5F5DC", ColorMath.labToHex(ColorMath.parseColor("beige")));
        assertEquals("#D3D3D3", ColorMath.labToHex(ColorMath.parseColor("LightGray")));
        assertNull(ColorMath.parseColor("no-es-un-color"));
        assertTrue(ColorMath.outOfGamut(new double[]{50, 120, 0}));
        assertFalse(ColorMath.outOfGamut(new double[]{62, 0.4, 3.1}));
    }

    @Test
    public void spanishNames() {
        assertEquals("blanco", ColorNames.describe(new double[]{96, 0, 0}));
        assertEquals("negro", ColorNames.describe(new double[]{15, 0, 0}));
        assertEquals("gris", ColorNames.describe(new double[]{55, 0, 0}));
        assertTrue(ColorNames.describe(ColorMath.parseColor("navy")).startsWith("azul"));
        assertTrue(ColorNames.describe(ColorMath.parseColor("#E3DACB")).matches("beige|crema"));
        assertTrue(ColorNames.describe(ColorMath.parseColor("saddlebrown")).startsWith("marrón")
                || ColorNames.describe(ColorMath.parseColor("saddlebrown")).startsWith("terracota"));
        assertTrue(ColorNames.describe(ColorMath.parseColor("forestgreen")).startsWith("verde"));
    }

    // ------------------------------------------------------------------ JSON

    @Test
    public void jsonRoundTripAndErrors() throws Exception {
        Object v = Json.parse("{\"a\": [1, 2.5, -3e2], \"b\": \"x\\\"y\\u00f1\", \"c\": true, \"d\": null}");
        Map<?, ?> m = (Map<?, ?>) v;
        assertEquals(Arrays.asList(1.0, 2.5, -300.0), m.get("a"));
        assertEquals("x\"yñ", m.get("b"));
        assertEquals(m, Json.parse(Json.write(v)));
        assertEquals(m, Json.parse(Json.writeCompact(v)));
        assertEquals("{\"a\":[1,2.5,-300],\"b\":\"x\\\"yñ\",\"c\":true,\"d\":null}", Json.writeCompact(v));
        for (String bad : new String[]{"{", "{\"a\" 1}", "[1,]", "{\"a\":1} x", "tru"}) {
            try {
                Json.parse(bad);
                throw new AssertionError("debió fallar: " + bad);
            } catch (Json.ParseException expected) {
                assertTrue(expected.pos >= 0);
            }
        }
    }

    // ------------------------------------------------------------------ STJF

    @Test
    public void minimalFileWorks() {
        Stjf.Result r = Stjf.parse("{ \"samples\": [ { \"id\": \"a101\", \"label\": \"Blanco hueso\", \"color\": \"#E3DACB\" } ] }");
        assertTrue(r.report(), r.ok());
        Stjf.Sample s = r.doc.samples.get(0);
        assertFalse(s.isComposite());
        assertEquals("#E3DACB", ColorMath.labToHex(s.baseLab()));
        List<Stjf.Part> sig = s.signature();
        assertEquals(1, sig.size());
        assertEquals(1.0, sig.get(0).fraction, 1e-9);
        assertEquals(0.9, s.roughness(), 1e-9); // matte por defecto
    }

    @Test
    public void terrazzoParsesAndResolves() {
        Stjf.Result r = Stjf.parse(TERRAZZO);
        assertTrue(r.report(), r.ok());
        Stjf.Sample tz = r.doc.find("tz-204");
        assertNotNull(tz);
        assertEquals(3, tz.components.size());
        assertEquals(15.0, tz.components.get(0).size.p90, 0);
        assertEquals("negra", tz.components.get(1).name()); // sin label → id
        assertEquals(0.15, tz.roughness(), 1e-9);
        List<Stjf.Part> sig = tz.signature();
        assertEquals(4, sig.size());
        assertTrue(sig.get(0).isBase);
        double sum = 0;
        for (Stjf.Part p : sig) sum += p.fraction;
        assertEquals(1.0, sum, 1e-9);
        assertEquals(1, tz.unknown.size()); // "x-proveedor" se conserva
    }

    @Test
    public void missingFractionsTakeTheRest() {
        Stjf.Result r = Stjf.parse("{\"samples\":[{\"id\":\"t\",\"color\":\"gray\",\"components\":["
                + "{\"id\":\"a\",\"color\":\"white\",\"fraction\":0.2},{\"id\":\"b\",\"color\":\"black\"}]}]}");
        assertTrue(r.report(), r.ok());
        List<Stjf.Part> sig = r.doc.samples.get(0).signature();
        // Base y "b" sin fracción se reparten el 0,8 que sobra.
        assertEquals(0.4, sig.get(0).fraction, 1e-9);
        assertEquals(0.2, sig.get(1).fraction, 1e-9);
        assertEquals(0.4, sig.get(2).fraction, 1e-9);
    }

    @Test
    public void oversizedFractionsAreNormalizedWithWarning() {
        Stjf.Result r = Stjf.parse("{\"samples\":[{\"id\":\"t\",\"base\":{\"color\":\"gray\",\"fraction\":0.8},"
                + "\"components\":[{\"id\":\"a\",\"color\":\"white\",\"fraction\":0.4}]}]}");
        assertTrue(r.ok());
        assertTrue(r.report().contains("suman 1.20"));
        List<Stjf.Part> sig = r.doc.samples.get(0).signature();
        assertEquals(0.8 / 1.2, sig.get(0).fraction, 1e-9);
    }

    @Test
    public void validationFindsErrors() {
        Stjf.Result r = Stjf.parse("{\"samples\":["
                + "{\"id\":\"a b\",\"color\":\"red\"},"
                + "{\"id\":\"x\",\"lab\":[120,0,0]},"
                + "{\"id\":\"x\",\"color\":\"red\"},"
                + "{\"id\":\"y\"},"
                + "{\"id\":\"z\",\"color\":\"red\",\"components\":[{\"id\":\"c\",\"color\":\"blue\",\"size_mm\":{\"p10\":5,\"p50\":2}}]},"
                + "{\"id\":\"w\",\"color\":\"rojito\",\"finish\":\"brillante\"}"
                + "]}");
        assertFalse(r.ok());
        String rep = r.report();
        assertTrue(rep, rep.contains("'a b'") || rep.contains("id sólo admite"));
        assertTrue(rep, rep.contains("L* debe estar entre 0 y 100"));
        assertTrue(rep, rep.contains("id repetido"));
        assertTrue(rep, rep.contains("muestra 'y': Sin color"));
        assertTrue(rep, rep.contains("p10 ≤ p50 ≤ p90"));
        assertTrue(rep, rep.contains("color no reconocido 'rojito'"));
        assertTrue(rep, rep.contains("AVISO muestra 'w': finish desconocido"));
        // Archivos rotos no revientan.
        assertFalse(Stjf.parse("no es json").ok());
        assertFalse(Stjf.parse("[1,2]").ok());
        assertFalse(Stjf.parse("{\"muestras\":[]}").ok());
    }

    @Test
    public void writeThenReadIsLossless() {
        Stjf doc = Stjf.parse(TERRAZZO).doc;
        String out = doc.write();
        Stjf.Result again = Stjf.parse(out);
        assertTrue(again.report(), again.ok());
        assertEquals(out, again.doc.write());
        assertTrue(out.contains("\"stjf\": \"1.0\""));
        assertTrue(out.contains("\"lab\": [62, 0.4, 3.1]"));
        assertTrue(out.contains("\"x-proveedor\""));
    }

    @Test
    public void repoExampleIsValid() throws Exception {
        // El ejemplo de docs/ debe pasar la validación (el test corre desde app/).
        java.io.File f = new java.io.File("../docs/examples/terrazzo.stjf");
        String text = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        Stjf.Result r = Stjf.parse(text);
        assertTrue(r.report(), r.ok());
        assertTrue(r.report(), r.issues.isEmpty());
        assertEquals(5, r.doc.samples.size());
        // "Blanco hueso" (Marca A) y "Marfil antiguo" (Marca B): nombres distintos, color casi igual.
        Matcher.Score sc = Matcher.compare(r.doc.find("a101"), r.doc.find("b550"));
        assertTrue("ΔE=" + sc.total, sc.total < 2);
    }

    // --------------------------------------------------------------- matcher

    static Stjf.Sample plain(String id, double l, double a, double b) {
        Stjf.Sample s = new Stjf.Sample();
        s.id = id;
        s.lab = new double[]{l, a, b};
        return s;
    }

    @Test
    public void plainRankingAndTies() {
        Stjf.Sample me = plain("yo", 86.2, 1.8, 9.4);
        List<Stjf.Sample> lib = new ArrayList<>(Arrays.asList(
                plain("rojo", 50, 60, 40), plain("casi", 86.0, 2.0, 9.9), plain("igual", 86.2, 1.8, 9.5),
                plain("gris", 60, 0, 0)));
        List<Matcher.Score> rank = Matcher.rank(me, lib);
        assertEquals("igual", rank.get(0).candidate.id);
        assertEquals("casi", rank.get(1).candidate.id);
        assertTrue(rank.get(0).total < 0.2);
        assertEquals(ColorMath.deltaE2000(me.lab, lib.get(1).lab), rank.get(1).total, 1e-9);
        assertTrue(Matcher.nearTie(rank)); // dos marcas, mismo color
        assertEquals("rojo", rank.get(rank.size() - 1).candidate.id);
    }

    @Test
    public void emdBehaves() {
        Stjf doc = Stjf.parse(TERRAZZO).doc;
        Stjf.Sample tz = doc.find("tz-204");
        assertEquals(0, Matcher.emd(tz.signature(), tz.signature()), 1e-9);

        // Mismos colores en otras proporciones: EMD > 0 pero el fondo es idéntico.
        Stjf.Sample other = Stjf.parse(TERRAZZO.replace("\"fraction\": 0.55", "\"fraction\": 0.35")
                .replace("\"fraction\": 0.25", "\"fraction\": 0.45")).doc.find("tz-204");
        Matcher.Score sc = Matcher.compare(tz, other);
        assertEquals(0, sc.baseDe, 1e-9);
        assertTrue(sc.mixDe > 1);
        // Mover 0,2 de la matriz al mármol cuesta 0,2 × ΔE(matriz, mármol).
        double de = ColorMath.deltaE2000(new double[]{62, 0.4, 3.1}, new double[]{90.1, 0.2, 2.0});
        assertEquals(0.2 * de, sc.mixDe, 1e-6);

        // Transporte simple 2×2 con solución conocida.
        double cost = Matcher.transport(new double[]{0.5, 0.5}, new double[]{0.5, 0.5},
                new double[][]{{0, 10}, {10, 0}});
        assertEquals(0, cost, 1e-12);
        cost = Matcher.transport(new double[]{1}, new double[]{0.3, 0.7}, new double[][]{{2, 4}});
        assertEquals(0.3 * 2 + 0.7 * 4, cost, 1e-12);
    }

    @Test
    public void sizeMattersForComposites() {
        String a = TERRAZZO;
        String big = TERRAZZO.replace("\"p10\": 3, \"p50\": 8, \"p90\": 15", "\"p10\": 12, \"p50\": 25, \"p90\": 40")
                .replace("\"p50\": 5 }", "\"p50\": 15 }");
        Stjf.Sample s = Stjf.parse(a).doc.find("tz-204");
        Stjf.Sample bigStones = Stjf.parse(big).doc.find("tz-204");
        bigStones.id = "grande";
        Stjf.Sample same = Stjf.parse(a).doc.find("tz-204");
        same.id = "igual";
        List<Matcher.Score> r = Matcher.rank(s, Arrays.asList(bigStones, same));
        assertEquals("igual", r.get(0).candidate.id);
        assertEquals(0, r.get(0).total, 1e-9);
        assertTrue(r.get(1).sizeRatio > 2.5);
        assertTrue("total=" + r.get(1).total, r.get(1).total > 3); // piedras ×3: "diferencia clara" aunque el color sea igual
    }
}
