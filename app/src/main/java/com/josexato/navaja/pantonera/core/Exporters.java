package com.josexato.navaja.pantonera.core;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Exportación de STJF a formatos abiertos:
 * <ul>
 * <li><b>glTF 2.0</b> (Khronos): cada muestra es un material PBR sobre un
 *     cuadrado de 1 m, con su textura a escala real (KHR_texture_transform),
 *     rugosidad y metalicidad. Los datos STJF viajan en {@code extras.stjf}
 *     para poder volver a importarlos. Como .glb (binario, un archivo) o
 *     .gltf (JSON con todo incrustado).</li>
 * <li><b>GPL</b> (paleta de GIMP/Inkscape/Krita/Scribus): sólo colores.</li>
 * </ul>
 */
public final class Exporters {

    private Exporters() { }

    /** Una muestra con su imagen de textura (opcional). */
    public static final class Entry {
        public final Stjf.Sample sample;
        public byte[] image;       // PNG o JPEG; null = sintética (compuestos) o color plano (lisos)
        public String mime;        // "image/png" o "image/jpeg"
        public int imgW, imgH;
        public double mmPerPx;     // escala de la imagen

        public Entry(Stjf.Sample sample) { this.sample = sample; }

        public Entry image(byte[] data, String mime, int w, int h, double mmPerPx) {
            this.image = data;
            this.mime = mime;
            this.imgW = w;
            this.imgH = h;
            this.mmPerPx = mmPerPx;
            return this;
        }
    }

    // ================================================================== GPL

    public static String gpl(String paletteName, List<Stjf.Sample> samples) {
        StringBuilder sb = new StringBuilder("GIMP Palette\n");
        sb.append("Name: ").append(clean(paletteName)).append('\n');
        sb.append("Columns: 4\n#\n# Exportado por Navaja (STJF ").append(Stjf.VERSION).append(")\n");
        for (Stjf.Sample s : samples) {
            if (!s.isComposite()) {
                double[] l = s.baseLab();
                if (l != null) line(sb, l, s.id + " " + s.name());
                continue;
            }
            double[] b = s.baseLab();
            if (b != null) line(sb, b, s.id + " matriz");
            for (Stjf.Component c : s.components) {
                double[] l = c.resolvedLab();
                if (l != null) line(sb, l, s.id + " " + c.name());
            }
        }
        return sb.toString();
    }

    private static void line(StringBuilder sb, double[] lab, String name) {
        int[] c = ColorMath.labToSrgb8(lab);
        sb.append(String.format(Locale.US, "%3d %3d %3d\t%s\n", c[0], c[1], c[2], clean(name)));
    }

    private static String clean(String s) { return s == null ? "" : s.replaceAll("[\\r\\n\\t]", " ").trim(); }

    // ================================================================= glTF

    /** .glb: cabecera + JSON + binario (geometría e imágenes dentro). */
    public static byte[] glb(List<Entry> entries) {
        Built b = build(entries, true);
        byte[] json = pad(Json.writeCompact(b.gltf).getBytes(StandardCharsets.UTF_8), (byte) ' ');
        byte[] bin = pad(b.bin, (byte) 0);
        int total = 12 + 8 + json.length + 8 + bin.length;
        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x46546C67).putInt(2).putInt(total);                 // "glTF", versión 2
        out.putInt(json.length).putInt(0x4E4F534A).put(json);           // "JSON"
        out.putInt(bin.length).putInt(0x004E4942).put(bin);             // "BIN\0"
        return out.array();
    }

    /** .gltf: un solo JSON con el buffer y las imágenes como data URI. */
    public static String gltf(List<Entry> entries) {
        return Json.write(build(entries, false).gltf);
    }

    static final class Built {
        Map<String, Object> gltf;
        byte[] bin;
    }

    private static Built build(List<Entry> entries, boolean binary) {
        ByteArrayOutputStream bin = new ByteArrayOutputStream();
        List<Object> bufferViews = new ArrayList<>(), accessors = new ArrayList<>(), images = new ArrayList<>(),
                textures = new ArrayList<>(), materials = new ArrayList<>(), meshes = new ArrayList<>(),
                nodes = new ArrayList<>(), sceneNodes = new ArrayList<>();
        boolean usesTransform = false;

        // Geometría compartida: cuadrado de 1 m en el plano XZ, normal +Y.
        float[] pos = {-0.5f, 0, -0.5f, 0.5f, 0, -0.5f, 0.5f, 0, 0.5f, -0.5f, 0, 0.5f};
        float[] nor = {0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0};
        float[] uv = {0, 0, 1, 0, 1, 1, 0, 1};
        short[] idx = {0, 2, 1, 0, 3, 2};
        int vPos = view(bin, bufferViews, floats(pos), 34962);
        int vNor = view(bin, bufferViews, floats(nor), 34962);
        int vUv = view(bin, bufferViews, floats(uv), 34962);
        int vIdx = view(bin, bufferViews, shorts(idx), 34963);
        accessors.add(accessor(vPos, 5126, 4, "VEC3", Arrays.asList(-0.5, 0.0, -0.5), Arrays.asList(0.5, 0.0, 0.5)));
        accessors.add(accessor(vNor, 5126, 4, "VEC3", null, null));
        accessors.add(accessor(vUv, 5126, 4, "VEC2", null, null));
        accessors.add(accessor(vIdx, 5123, 6, "SCALAR", null, null));

        int cols = (int) Math.ceil(Math.sqrt(entries.size()));
        for (int e = 0; e < entries.size(); e++) {
            Entry en = entries.get(e);
            Stjf.Sample s = en.sample;
            byte[] img = en.image;
            String mime = en.mime;
            int iw = en.imgW, ih = en.imgH;
            double mm = en.mmPerPx;
            if (img == null && s.isComposite()) {
                TextureSynth.Texture t = TextureSynth.render(s, 512, s.id == null ? 1 : s.id.hashCode());
                img = Png.encode(t.argb, t.size, t.size);
                mime = "image/png";
                iw = ih = t.size;
                mm = t.mmPerPx;
            }

            Map<String, Object> pbr = new LinkedHashMap<>();
            if (img != null) {
                Map<String, Object> image = new LinkedHashMap<>();
                image.put("name", s.id);
                if (binary) {
                    image.put("bufferView", view(bin, bufferViews, img, -1));
                    image.put("mimeType", mime);
                } else {
                    image.put("uri", "data:" + mime + ";base64," + base64(img));
                }
                images.add(image);
                Map<String, Object> tex = new LinkedHashMap<>();
                tex.put("sampler", 0);
                tex.put("source", images.size() - 1);
                textures.add(tex);
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("index", textures.size() - 1);
                if (mm > 0 && iw > 0 && ih > 0) {
                    // El cuadrado mide 1000 mm; la imagen cubre iw·mm × ih·mm.
                    Map<String, Object> tt = new LinkedHashMap<>();
                    tt.put("scale", Arrays.asList(1000 / (iw * mm), 1000 / (ih * mm)));
                    Map<String, Object> ext = new LinkedHashMap<>();
                    ext.put("KHR_texture_transform", tt);
                    ref.put("extensions", ext);
                    usesTransform = true;
                }
                pbr.put("baseColorTexture", ref);
            } else {
                double[] l = s.baseLab();
                double[] lin = l == null ? new double[]{0.5, 0.5, 0.5} : ColorMath.labToLinear(l);
                pbr.put("baseColorFactor", Arrays.asList(r4(lin[0]), r4(lin[1]), r4(lin[2]), 1.0));
            }
            pbr.put("metallicFactor", s.metallic());
            pbr.put("roughnessFactor", s.roughness());
            Map<String, Object> mat = new LinkedHashMap<>();
            mat.put("name", s.id);
            mat.put("pbrMetallicRoughness", pbr);
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("stjf", Stjf.sampleToMap(s));
            mat.put("extras", extras);
            materials.add(mat);

            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("POSITION", 0);
            attrs.put("NORMAL", 1);
            attrs.put("TEXCOORD_0", 2);
            Map<String, Object> prim = new LinkedHashMap<>();
            prim.put("attributes", attrs);
            prim.put("indices", 3);
            prim.put("material", materials.size() - 1);
            Map<String, Object> mesh = new LinkedHashMap<>();
            mesh.put("name", s.id);
            mesh.put("primitives", java.util.Collections.singletonList(prim));
            meshes.add(mesh);

            Map<String, Object> node = new LinkedHashMap<>();
            node.put("name", s.name());
            node.put("mesh", meshes.size() - 1);
            if (entries.size() > 1) {
                node.put("translation", Arrays.asList(1.2 * (e % cols), 0.0, 1.2 * (e / cols)));
            }
            nodes.add(node);
            sceneNodes.add(nodes.size() - 1);
        }

        Map<String, Object> g = new LinkedHashMap<>();
        Map<String, Object> asset = new LinkedHashMap<>();
        asset.put("version", "2.0");
        asset.put("generator", "Navaja STJF " + Stjf.VERSION);
        g.put("asset", asset);
        if (usesTransform) g.put("extensionsUsed", java.util.Collections.singletonList("KHR_texture_transform"));
        g.put("scene", 0);
        Map<String, Object> scene = new LinkedHashMap<>();
        scene.put("nodes", sceneNodes);
        g.put("scenes", java.util.Collections.singletonList(scene));
        g.put("nodes", nodes);
        g.put("meshes", meshes);
        g.put("materials", materials);
        if (!textures.isEmpty()) {
            Map<String, Object> sampler = new LinkedHashMap<>();
            sampler.put("magFilter", 9729);   // LINEAR
            sampler.put("minFilter", 9987);   // LINEAR_MIPMAP_LINEAR
            sampler.put("wrapS", 10497);      // REPEAT
            sampler.put("wrapT", 10497);
            g.put("samplers", java.util.Collections.singletonList(sampler));
            g.put("textures", textures);
            g.put("images", images);
        }
        g.put("accessors", accessors);
        g.put("bufferViews", bufferViews);
        byte[] data = bin.toByteArray();
        Map<String, Object> buffer = new LinkedHashMap<>();
        buffer.put("byteLength", data.length);
        if (!binary) buffer.put("uri", "data:application/octet-stream;base64," + base64(data));
        g.put("buffers", java.util.Collections.singletonList(buffer));

        Built b = new Built();
        b.gltf = g;
        b.bin = data;
        return b;
    }

    /** Agrega un bufferView alineado a 4 bytes; target -1 = sin target (imágenes). */
    private static int view(ByteArrayOutputStream bin, List<Object> views, byte[] data, int target) {
        while (bin.size() % 4 != 0) bin.write(0);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("buffer", 0);
        v.put("byteOffset", bin.size());
        v.put("byteLength", data.length);
        if (target > 0) v.put("target", target);
        bin.write(data, 0, data.length);
        views.add(v);
        return views.size() - 1;
    }

    private static Map<String, Object> accessor(int view, int type, int count, String kind, List<Double> min, List<Double> max) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("bufferView", view);
        a.put("componentType", type);
        a.put("count", count);
        a.put("type", kind);
        if (min != null) a.put("min", min);
        if (max != null) a.put("max", max);
        return a;
    }

    private static byte[] floats(float[] f) {
        ByteBuffer b = ByteBuffer.allocate(f.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : f) b.putFloat(v);
        return b.array();
    }

    private static byte[] shorts(short[] s) {
        ByteBuffer b = ByteBuffer.allocate(s.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short v : s) b.putShort(v);
        return b.array();
    }

    private static byte[] pad(byte[] d, byte fill) {
        int n = (d.length + 3) & ~3;
        if (n == d.length) return d;
        byte[] o = Arrays.copyOf(d, n);
        Arrays.fill(o, d.length, n, fill);
        return o;
    }

    private static double r4(double v) { return Math.round(ColorMath.clamp01(v) * 10000) / 10000.0; }

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    /** Base64 propio (java.util.Base64 sólo existe desde Android 8). */
    static String base64(byte[] d) {
        StringBuilder sb = new StringBuilder((d.length + 2) / 3 * 4);
        for (int i = 0; i < d.length; i += 3) {
            int b0 = d[i] & 255, b1 = i + 1 < d.length ? d[i + 1] & 255 : 0, b2 = i + 2 < d.length ? d[i + 2] & 255 : 0;
            int v = (b0 << 16) | (b1 << 8) | b2;
            sb.append(B64[(v >> 18) & 63]).append(B64[(v >> 12) & 63]);
            sb.append(i + 1 < d.length ? B64[(v >> 6) & 63] : '=');
            sb.append(i + 2 < d.length ? B64[v & 63] : '=');
        }
        return sb.toString();
    }
}
