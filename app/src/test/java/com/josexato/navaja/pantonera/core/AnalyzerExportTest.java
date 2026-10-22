package com.josexato.navaja.pantonera.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.Test;

/**
 * Análisis con fotos sintéticas (hoja A4 sobre una mesa, luz de color,
 * ruido de sensor) cuyo color, fracciones y tamaños se conocen; y
 * exportadores glTF/GPL.
 */
public class AnalyzerExportTest {

    static final double[] MATRIX = {62.0, 0.4, 3.1};
    static final double[] MARBLE = {90.1, 0.2, 2.0};
    static final double[] BLACK = {22.5, 0.8, -1.2};

    /** Piedra circular en mm (coordenadas de la hoja). */
    static final class Stone {
        final double u, v, d;
        final double[] lab;

        Stone(double u, double v, double d, double[] lab) {
            this.u = u;
            this.v = v;
            this.d = d;
            this.lab = lab;
        }
    }

    /** Escena: mesa oscura, hoja A4 (girada `rotDeg`), muestra cuadrada de `side` mm al centro. */
    static final class Scene {
        int w = 1600, h = 1200;
        double mmPerPx = 297.0 / 1150;      // la hoja ocupa ~1150 px de largo
        double rotDeg = 0;
        double[] light = {1.0, 0.86, 0.70}; // luz cálida (incandescente)
        double exposure = 0.95;
        double noise = 0.008;
        double side = 70;
        double[] matrix = MATRIX;
        List<Stone> stones = new ArrayList<>();
        double paperY = 0.85;
        double halfLong = 148.5, halfShort = 105; // A4
        double sampleU = 0;      // centro de la muestra (mm); fuera de la hoja = "al lado"
        double blurPx = 0;       // desenfoque gaussiano (σ en píxeles)
        double vignette = 0;     // caída de luz hacia las esquinas (0–1)
        double keystone = 0;     // perspectiva: la parte de arriba se ve más chica
        boolean drawSheet = true, drawSample = true;
        long seed = 7;

        int[] render() {
            int[] img = new int[w * h];
            float[][] lin = new float[3][w * h];
            Random rnd = new Random(seed);
            double cos = Math.cos(Math.toRadians(rotDeg)), sin = Math.sin(Math.toRadians(rotDeg));
            double[] matLin = ColorMath.labToLinear(matrix);
            List<double[]> stoneLin = new ArrayList<>();
            for (Stone s : stones) stoneLin.add(ColorMath.labToLinear(s.lab));
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    double yn = (y + 0.5) / h - 0.5;
                    double ks = 1 + keystone * yn * 2; // escala horizontal según la fila
                    double dx = (x + 0.5 - w / 2.0) * mmPerPx / ks, dy = (y + 0.5 - h / 2.0) * mmPerPx;
                    double rr = Math.hypot((x + 0.5) / w - 0.5, yn) / 0.7071;
                    double vig = 1 - vignette * rr * rr;
                    double u = cos * dx + sin * dy, v = -sin * dx + cos * dy; // mm desde el centro de la hoja
                    double[] refl;
                    double su = u - sampleU;
                    if (drawSample && Math.abs(su) <= side / 2 && Math.abs(v) <= side / 2) {
                        refl = matLin;
                        for (int k = 0; k < stones.size(); k++) {
                            Stone s = stones.get(k);
                            if (Math.hypot(su - s.u, v - s.v) <= s.d / 2) { refl = stoneLin.get(k); break; }
                        }
                    } else if (drawSheet && Math.abs(u) <= halfLong && Math.abs(v) <= halfShort) {
                        refl = new double[]{paperY, paperY, paperY};
                    } else {
                        refl = new double[]{0.06, 0.045, 0.03}; // mesa de madera oscura
                    }
                    for (int ch = 0; ch < 3; ch++) {
                        double val = refl[ch] * light[ch] * exposure * vig;
                        lin[ch][y * w + x] = (float) val;
                    }
                }
            }
            if (blurPx > 0) for (float[] chan : lin) blur(chan, w, h, blurPx);
            for (int i = 0; i < w * h; i++) {
                int c = 0xFF000000;
                for (int ch = 0; ch < 3; ch++) {
                    double val = lin[ch][i] + rnd.nextGaussian() * noise;
                    int q = (int) Math.round(ColorMath.toGamma(Math.max(0, Math.min(1, val))) * 255);
                    c |= q << (16 - 8 * ch);
                }
                img[i] = c;
            }
            return img;
        }

        /** Desenfoque gaussiano separable (como la óptica del teléfono). */
        static void blur(float[] a, int w, int h, double sigma) {
            int r = (int) Math.ceil(sigma * 3);
            double[] k = new double[2 * r + 1];
            double sum = 0;
            for (int i = -r; i <= r; i++) sum += k[i + r] = Math.exp(-i * i / (2 * sigma * sigma));
            for (int i = 0; i < k.length; i++) k[i] /= sum;
            float[] t = new float[a.length];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    double v = 0;
                    for (int i = -r; i <= r; i++) v += k[i + r] * a[y * w + Math.min(w - 1, Math.max(0, x + i))];
                    t[y * w + x] = (float) v;
                }
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    double v = 0;
                    for (int i = -r; i <= r; i++) v += k[i + r] * t[Math.min(h - 1, Math.max(0, y + i)) * w + x];
                    a[y * w + x] = (float) v;
                }
        }

        /** Siembra piedras sin solaparse ni tocar el borde de la muestra. */
        void scatter(double d, double[] lab, int count, Random rnd) {
            int placed = 0;
            for (int t = 0; t < 20000 && placed < count; t++) {
                double u = (rnd.nextDouble() - 0.5) * (side - d - 4), v = (rnd.nextDouble() - 0.5) * (side - d - 4);
                boolean ok = true;
                for (Stone s : stones) if (Math.hypot(u - s.u, v - s.v) < (d + s.d) / 2 + 1) { ok = false; break; }
                if (!ok) continue;
                stones.add(new Stone(u, v, d, lab));
                placed++;
            }
        }

        double trueFraction(double[] lab) {
            double a = 0;
            for (Stone s : stones) if (s.lab == lab) a += Math.PI * s.d * s.d / 4;
            return a / (side * side);
        }
    }

    // ---------------------------------------------------------------- análisis

    @Test
    public void plainPaintUnderWarmLight() {
        Scene sc = new Scene();
        sc.matrix = new double[]{86.2, 1.8, 9.4}; // "blanco hueso"
        SampleAnalyzer.Result r = new SampleAnalyzer().analyze(sc.render(), sc.w, sc.h);
        assertTrue(r.error, r.ok());
        assertFalse(r.sample.isComposite());
        double de = ColorMath.deltaE2000(r.sample.baseLab(), sc.matrix);
        assertTrue("ΔE=" + de, de < 1.0);
        assertEquals("A4", r.paper);
        assertEquals(sc.mmPerPx, r.mmPerPx, sc.mmPerPx * 0.02);
        assertTrue(r.warnings.toString(), r.warnings.isEmpty());
        assertNotNull(r.texture);
    }

    @Test
    public void sameColorUnderDifferentLightsGivesSameLab() {
        double[][] lights = {{1.0, 0.86, 0.70}, {0.80, 0.90, 1.0}, {1, 1, 1}};
        double[] exposure = {0.95, 0.7, 0.5};
        List<Stjf.Sample> shots = new ArrayList<>();
        for (int i = 0; i < lights.length; i++) {
            Scene sc = new Scene();
            sc.matrix = new double[]{55, 25, 35}; // terracota
            sc.light = lights[i];
            sc.exposure = exposure[i];
            sc.seed = 100 + i;
            SampleAnalyzer.Result r = new SampleAnalyzer().analyze(sc.render(), sc.w, sc.h);
            assertTrue(r.error, r.ok());
            double de = ColorMath.deltaE2000(r.sample.baseLab(), sc.matrix);
            assertTrue("luz " + i + " ΔE=" + de, de < 1.2);
            shots.add(r.sample);
        }
        MultiShot.Combined c = MultiShot.combine(shots);
        assertTrue(c.warnings.toString(), c.warnings.isEmpty());
        assertTrue(c.spreadDe < 1.5);
        assertEquals(3, (int) c.sample.measurement.shots);
        assertTrue(ColorMath.deltaE2000(c.sample.baseLab(), new double[]{55, 25, 35}) < 1.0);
    }

    @Test
    public void terrazzoCompositionAndGranulometry() {
        Scene sc = new Scene();
        sc.side = 90;
        Random rnd = new Random(3);
        sc.scatter(8, MARBLE, 30, rnd);
        sc.scatter(5, BLACK, 45, rnd);
        SampleAnalyzer.Result r = new SampleAnalyzer().analyze(sc.render(), sc.w, sc.h);
        assertTrue(r.error, r.ok());
        Stjf.Sample s = r.sample;
        assertTrue(s.isComposite());
        assertEquals(2, s.components.size());
        assertTrue("matriz ΔE", ColorMath.deltaE2000(s.base.lab, MATRIX) < 1.5);

        Stjf.Component marble = closest(s, MARBLE), black = closest(s, BLACK);
        assertTrue("mármol ΔE", ColorMath.deltaE2000(marble.lab, MARBLE) < 2.0);
        assertTrue("negra ΔE", ColorMath.deltaE2000(black.lab, BLACK) < 2.0);
        assertEquals(sc.trueFraction(MARBLE), marble.fraction, 0.035);
        assertEquals(sc.trueFraction(BLACK), black.fraction, 0.035);
        assertEquals(8.0, marble.size.p50, 8.0 * 0.12);
        assertEquals(5.0, black.size.p50, 5.0 * 0.12);
        assertEquals("round", marble.shape);
        assertTrue(marble.label, marble.label.startsWith("blanco") || marble.label.startsWith("gris muy claro"));

        // La medición se reconoce contra un catálogo con el terrazzo real.
        Stjf.Sample ref = Stjf.parse(ColorAndStjfTest.TERRAZZO).doc.find("tz-204");
        ref.components.remove(2); // sin vidrio
        ref.components.get(0).size = new Stjf.Size(null, 8.0, null);
        ref.base.fraction = 1 - sc.trueFraction(MARBLE) - sc.trueFraction(BLACK);
        ref.components.get(0).fraction = sc.trueFraction(MARBLE);
        ref.components.get(1).fraction = sc.trueFraction(BLACK);
        Stjf.Sample other = Stjf.parse(ColorAndStjfTest.TERRAZZO).doc.find("tz-204");
        other.id = "otro";
        other.base.lab = new double[]{45, 2, 8};
        List<Matcher.Score> rank = Matcher.rank(s, Arrays.asList(other, ref));
        assertEquals("tz-204", rank.get(0).candidate.id);
        assertTrue("total=" + rank.get(0).total, rank.get(0).total < 3);

        // Y se puede guardar como STJF válido.
        s.id = "medida";
        Stjf doc = new Stjf();
        doc.samples.add(s);
        Stjf.Result back = Stjf.parse(doc.write());
        assertTrue(back.report(), back.ok());
    }

    @Test
    public void realisticPhotoBlurVignetteKeystone() {
        Scene sc = new Scene();
        sc.side = 90;
        sc.blurPx = 1.5;
        sc.vignette = 0.3;
        sc.keystone = 0.04;
        sc.noise = 0.012;
        Random rnd = new Random(11);
        sc.scatter(8, MARBLE, 30, rnd);
        sc.scatter(5, BLACK, 45, rnd);
        SampleAnalyzer.Result r = new SampleAnalyzer().analyze(sc.render(), sc.w, sc.h);
        assertTrue(r.error, r.ok());
        Stjf.Sample s = r.sample;
        assertEquals(Json.write(Stjf.sampleToMap(s)), 2, s.components.size());
        assertTrue("matriz ΔE", ColorMath.deltaE2000(s.base.lab, MATRIX) < 2.0);
        Stjf.Component marble = closest(s, MARBLE), black = closest(s, BLACK);
        assertTrue("mármol ΔE " + ColorMath.deltaE2000(marble.lab, MARBLE), ColorMath.deltaE2000(marble.lab, MARBLE) < 3.0);
        assertTrue("negra ΔE " + ColorMath.deltaE2000(black.lab, BLACK), ColorMath.deltaE2000(black.lab, BLACK) < 3.0);
        assertEquals(sc.trueFraction(MARBLE), marble.fraction, 0.04);
        assertEquals(sc.trueFraction(BLACK), black.fraction, 0.04);
        assertEquals(8.0, marble.size.p50, 8.0 * 0.15);
        assertEquals(5.0, black.size.p50, 5.0 * 0.15);
        assertEquals(sc.mmPerPx, r.mmPerPx, sc.mmPerPx * 0.04);
    }

    @Test
    public void whiteTerrazzoBesideTheSheet() {
        // Terrazzo de fondo blanco: sobre la hoja se confundiría con el papel,
        // así que va al lado, sobre la mesa oscura. La hoja sigue dando luz y escala.
        Scene sc = new Scene();
        sc.mmPerPx = 0.32;
        sc.matrix = new double[]{93, 0.5, 2.5};
        sc.side = 100;
        sc.sampleU = 148.5 + 20 + 50; // 2 cm a la derecha de la hoja
        sc.w = 2000;
        Random rnd = new Random(5);
        double[] blue = {45, -2, -30}, ochre = {68, 12, 40};
        sc.scatter(9, blue, 25, rnd);
        sc.scatter(6, ochre, 30, rnd);
        SampleAnalyzer.Result r = new SampleAnalyzer().analyze(sc.render(), sc.w, sc.h);
        assertTrue(r.error, r.ok());
        assertTrue(r.warnings.toString(), r.warnings.toString().contains("al lado"));
        Stjf.Sample s = r.sample;
        assertEquals(Json.write(Stjf.sampleToMap(s)), 2, s.components.size());
        assertTrue("fondo ΔE", ColorMath.deltaE2000(s.base.lab, sc.matrix) < 1.5);
        assertTrue(ColorMath.deltaE2000(closest(s, blue).lab, blue) < 2.0);
        assertTrue(ColorMath.deltaE2000(closest(s, ochre).lab, ochre) < 2.0);
        assertEquals(9.0, closest(s, blue).size.p50, 9.0 * 0.12);
        assertEquals(sc.mmPerPx, r.mmPerPx, sc.mmPerPx * 0.03);
    }

    @Test
    public void imageModeMeasuresCatalogPhotos() {
        // Una "foto de catálogo" (sin hoja): toda la imagen es la muestra.
        Scene sc = new Scene();
        sc.drawSheet = false;
        sc.side = 400;
        sc.w = 800;
        sc.h = 800;
        sc.mmPerPx = 0.25; // 200 mm de ancho
        sc.light = new double[]{1, 1, 1};
        sc.exposure = 1 / 0.9;
        sc.matrix = new double[]{70, 5, 20};
        Random rnd = new Random(9);
        sc.scatter(8, MARBLE, 60, rnd);
        SampleAnalyzer.Result r = new SampleAnalyzer().analyzeImage(sc.render(), sc.w, sc.h, 200);
        assertTrue(r.error, r.ok());
        assertEquals(1, r.sample.components.size());
        assertEquals(8.0, r.sample.components.get(0).size.p50, 8.0 * 0.12);
        assertEquals("image", r.sample.measurement.reference);
    }

    @Test
    public void rotatedSheetStillGivesScale() {
        Scene sc = new Scene();
        sc.rotDeg = 12;
        sc.mmPerPx = 297.0 / 1000;
        sc.matrix = new double[]{40, -15, -20};
        SampleAnalyzer.Result r = new SampleAnalyzer().analyze(sc.render(), sc.w, sc.h);
        assertTrue(r.error, r.ok());
        assertEquals(sc.mmPerPx, r.mmPerPx, sc.mmPerPx * 0.03);
        assertTrue(ColorMath.deltaE2000(r.sample.baseLab(), sc.matrix) < 1.2);
    }

    @Test
    public void letterPaperIsRecognized() {
        Scene sc = new Scene();
        sc.halfLong = 279.4 / 2;
        sc.halfShort = 215.9 / 2;
        sc.mmPerPx = 279.4 / 1100;
        SampleAnalyzer.Result r = new SampleAnalyzer().analyze(sc.render(), sc.w, sc.h);
        assertTrue(r.error, r.ok());
        assertEquals("Carta", r.paper);
        assertEquals(sc.mmPerPx, r.mmPerPx, sc.mmPerPx * 0.02);
    }

    @Test
    public void helpfulErrors() {
        Scene dark = new Scene();
        dark.drawSheet = false;
        SampleAnalyzer.Result r = new SampleAnalyzer().analyze(dark.render(), dark.w, dark.h);
        assertFalse(r.ok());
        assertTrue(r.error, r.error.contains("hoja"));

        Scene empty = new Scene();
        empty.drawSample = false;
        r = new SampleAnalyzer().analyze(empty.render(), empty.w, empty.h);
        assertFalse(r.ok());
        assertTrue(r.error, r.error.contains("muestra"));

        Scene cut = new Scene();
        cut.mmPerPx = 297.0 / 1900; // la hoja no entra en la foto
        r = new SampleAnalyzer().analyze(cut.render(), cut.w, cut.h);
        assertTrue(r.error, r.ok());
        assertTrue(Double.isNaN(r.mmPerPx));
        assertTrue(r.warnings.toString(), r.warnings.toString().contains("cortada"));

        Scene burnt = new Scene();
        burnt.exposure = 1.25;
        burnt.light = new double[]{1, 1, 1};
        r = new SampleAnalyzer().analyze(burnt.render(), burnt.w, burnt.h);
        assertTrue(r.warnings.toString(), r.warnings.toString().contains("sobreexpuesta"));
    }

    @Test
    public void metamerismWarning() {
        Stjf.Sample a = ColorAndStjfTest.plain("t1", 60, 10, 10);
        Stjf.Sample b = ColorAndStjfTest.plain("t2", 60, 16, 4);
        Stjf.Sample c = ColorAndStjfTest.plain("t3", 61, 10, 11);
        MultiShot.Combined m = MultiShot.combine(Arrays.asList(a, b, c));
        assertFalse(m.warnings.isEmpty());
        assertTrue(m.warnings.get(0).contains("metamerismo"));
    }

    private static Stjf.Component closest(Stjf.Sample s, double[] lab) {
        Stjf.Component best = null;
        double bd = Double.MAX_VALUE;
        for (Stjf.Component c : s.components) {
            double d = ColorMath.deltaE2000(c.lab, lab);
            if (d < bd) { bd = d; best = c; }
        }
        return best;
    }

    // ------------------------------------------------------------- exportación

    @Test
    public void gplPalette() {
        Stjf doc = Stjf.parse(ColorAndStjfTest.TERRAZZO).doc;
        String gpl = Exporters.gpl("Pantonera", doc.samples);
        String[] lines = gpl.split("\n");
        assertEquals("GIMP Palette", lines[0]);
        assertEquals("Name: Pantonera", lines[1]);
        assertTrue(gpl.contains("227 218 203\ta101 Blanco hueso"));
        assertTrue(gpl.contains("tz-204 matriz"));
        assertTrue(gpl.contains(" 43  42  44\ttz-204 negra"));
        int colors = 0;
        for (String l : lines) if (l.matches("^\\s*\\d+\\s+\\d+\\s+\\d+\\t.*")) colors++;
        assertEquals(5, colors);
    }

    @Test
    public void glbIsWellFormed() throws Exception {
        Stjf doc = Stjf.parse(ColorAndStjfTest.TERRAZZO).doc;
        // Una muestra con "foto" (PNG), otra compuesta sin foto (sintética) y una lisa (color plano).
        int[] px = new int[64 * 48];
        Arrays.fill(px, 0xFF9A948E);
        byte[] png = Png.encode(px, 64, 48);
        Stjf.Sample photo = Stjf.parse(ColorAndStjfTest.TERRAZZO).doc.find("tz-204");
        photo.id = "con-foto";
        List<Exporters.Entry> entries = Arrays.asList(
                new Exporters.Entry(photo).image(png, "image/png", 64, 48, 0.5),
                new Exporters.Entry(doc.find("tz-204")),
                new Exporters.Entry(doc.find("a101")));
        byte[] glb = Exporters.glb(entries);

        ByteBuffer bb = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x46546C67, bb.getInt());
        assertEquals(2, bb.getInt());
        assertEquals(glb.length, bb.getInt());
        int jsonLen = bb.getInt();
        assertEquals(0x4E4F534A, bb.getInt());
        assertEquals(0, jsonLen % 4);
        String json = new String(glb, 20, jsonLen, StandardCharsets.UTF_8).trim();
        bb.position(20 + jsonLen);
        int binLen = bb.getInt();
        assertEquals(0x004E4942, bb.getInt());
        assertEquals(glb.length, 28 + jsonLen + binLen);

        Map<?, ?> g = (Map<?, ?>) Json.parse(json);
        assertEquals("2.0", ((Map<?, ?>) g.get("asset")).get("version"));
        List<?> mats = (List<?>) g.get("materials");
        assertEquals(3, mats.size());
        List<?> images = (List<?>) g.get("images");
        assertEquals(2, images.size()); // foto + sintética; la lisa usa baseColorFactor
        Map<?, ?> m0 = (Map<?, ?>) mats.get(0);
        Map<?, ?> pbr0 = (Map<?, ?>) m0.get("pbrMetallicRoughness");
        assertEquals(0.15, (Double) pbr0.get("roughnessFactor"), 1e-9);
        Map<?, ?> tt = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) pbr0.get("baseColorTexture")).get("extensions"))
                .get("KHR_texture_transform");
        // 1000 mm / (64 px × 0,5 mm) = 31,25 repeticiones.
        assertEquals(31.25, (Double) ((List<?>) tt.get("scale")).get(0), 1e-9);
        Map<?, ?> stjf = (Map<?, ?>) ((Map<?, ?>) m0.get("extras")).get("stjf");
        assertEquals("con-foto", stjf.get("id"));
        Map<?, ?> pbr2 = (Map<?, ?>) ((Map<?, ?>) mats.get(2)).get("pbrMetallicRoughness");
        assertEquals(4, ((List<?>) pbr2.get("baseColorFactor")).size());

        // Cada bufferView cabe en el buffer y está alineado.
        long bufLen = ((Number) ((Map<?, ?>) ((List<?>) g.get("buffers")).get(0)).get("byteLength")).longValue();
        assertTrue(bufLen <= binLen);
        for (Object o : (List<?>) g.get("bufferViews")) {
            Map<?, ?> v = (Map<?, ?>) o;
            long off = ((Number) v.get("byteOffset")).longValue(), len = ((Number) v.get("byteLength")).longValue();
            assertEquals(0, off % 4);
            assertTrue(off + len <= bufLen);
        }
        // La imagen incrustada es el PNG original.
        Map<?, ?> img0 = (Map<?, ?>) images.get(0);
        Map<?, ?> v0 = (Map<?, ?>) ((List<?>) g.get("bufferViews")).get(((Number) img0.get("bufferView")).intValue());
        int off = 28 + jsonLen + ((Number) v0.get("byteOffset")).intValue();
        assertTrue(Arrays.equals(png, Arrays.copyOfRange(glb, off, off + png.length)));

        File dir = new File("build/stjf-export");
        dir.mkdirs();
        Files.write(new File(dir, "pantonera.glb").toPath(), glb);
        Files.write(new File(dir, "pantonera.gltf").toPath(), Exporters.gltf(entries).getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "pantonera.gpl").toPath(), Exporters.gpl("Pantonera", doc.samples).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void gltfIsSelfContained() throws Exception {
        Stjf doc = Stjf.parse(ColorAndStjfTest.TERRAZZO).doc;
        String text = Exporters.gltf(Arrays.asList(new Exporters.Entry(doc.find("tz-204"))));
        Map<?, ?> g = (Map<?, ?>) Json.parse(text);
        Map<?, ?> buf = (Map<?, ?>) ((List<?>) g.get("buffers")).get(0);
        String uri = (String) buf.get("uri");
        assertTrue(uri.startsWith("data:application/octet-stream;base64,"));
        byte[] data = Base64.getDecoder().decode(uri.substring(uri.indexOf(',') + 1));
        assertEquals(((Number) buf.get("byteLength")).intValue(), data.length);
        String img = (String) ((Map<?, ?>) ((List<?>) g.get("images")).get(0)).get("uri");
        byte[] png = Base64.getDecoder().decode(img.substring(img.indexOf(',') + 1));
        Object bi = readImage(png);
        assertEquals(512, width(bi));
    }

    @Test
    public void base64AndPng() throws Exception {
        Random rnd = new Random(1);
        for (int n = 0; n < 20; n++) {
            byte[] d = new byte[n * 7 + rnd.nextInt(3)];
            rnd.nextBytes(d);
            assertEquals(Base64.getEncoder().encodeToString(d), Exporters.base64(d));
        }
        int[] px = new int[30 * 20];
        for (int i = 0; i < px.length; i++) px[i] = 0xFF000000 | rnd.nextInt(0xFFFFFF);
        Object bi = readImage(Png.encode(px, 30, 20));
        assertEquals(30, width(bi));
        java.lang.reflect.Method rgb = bi.getClass().getMethod("getRGB", int.class, int.class);
        for (int i = 0; i < px.length; i++) assertEquals(px[i], rgb.invoke(bi, i % 30, i / 30));
    }

    @Test
    public void synthTextureMatchesComposition() {
        Stjf.Sample tz = Stjf.parse(ColorAndStjfTest.TERRAZZO).doc.find("tz-204");
        TextureSynth.Texture t = TextureSynth.render(tz, 512, 5);
        List<Stjf.Part> sig = tz.signature();
        int[] counts = new int[sig.size()];
        for (int c : t.argb) {
            double[] lab = ColorMath.srgbToLab(((c >> 16) & 255) / 255.0, ((c >> 8) & 255) / 255.0, (c & 255) / 255.0);
            int best = 0;
            for (int i = 1; i < sig.size(); i++)
                if (ColorMath.deltaE76(lab, sig.get(i).lab) < ColorMath.deltaE76(lab, sig.get(best).lab)) best = i;
            counts[best]++;
        }
        for (int i = 0; i < sig.size(); i++) {
            assertEquals(sig.get(i).name, sig.get(i).fraction, counts[i] / (double) t.argb.length, 0.03);
        }
        // 15 mm de p90 → cuadro de 180 mm.
        assertEquals(180.0 / 512, t.mmPerPx, 1e-9);
    }

    /**
     * Decodifica con javax.imageio de la JVM (un decodificador independiente).
     * Por reflexión porque los tests compilan contra android.jar, que no trae java.awt.
     */
    private static Object readImage(byte[] png) throws Exception {
        Class<?> io = Class.forName("javax.imageio.ImageIO");
        Object img = io.getMethod("read", java.io.InputStream.class).invoke(null, new ByteArrayInputStream(png));
        assertNotNull("PNG ilegible", img);
        return img;
    }

    private static int width(Object img) throws Exception {
        return (Integer) img.getClass().getMethod("getWidth").invoke(img);
    }
}
