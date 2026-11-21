package com.josexato.navaja.scan3d.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class GlbTest {

    /** Un tetraedro de 10 mm (Y hacia arriba). */
    static final float[] POS = {0, 0, 0, 10, 0, 0, 0, 10, 0, 0, 0, 10};
    static final int[] TRI = {0, 2, 1, 0, 1, 3, 0, 3, 2, 1, 2, 3};

    /** Parte el GLB en {json, binario} comprobando la cabecera y los largos. */
    static Object[] split(byte[] glb) {
        ByteBuffer b = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("glTF", 0x46546C67, b.getInt());
        assertEquals(2, b.getInt());
        assertEquals(glb.length, b.getInt());
        int jl = b.getInt();
        assertEquals(0x4E4F534A, b.getInt());
        assertEquals("JSON alineado a 4", 0, jl % 4);
        byte[] j = new byte[jl];
        b.get(j);
        int bl = b.getInt();
        assertEquals(0x004E4942, b.getInt());
        assertEquals("binario alineado a 4", 0, bl % 4);
        assertEquals(glb.length, b.position() + bl);
        byte[] bin = new byte[bl];
        b.get(bin);
        return new Object[]{new String(j, StandardCharsets.UTF_8), bin};
    }

    @Test
    public void conColorPorVerticeUsaIndicesYPasaAMetrosConYArriba() {
        float[] col = {1, 0, 0, 0, 1, 0, 0, 0, 1, 1, 1, 0};
        // La malla viene en mm y con Z hacia arriba: el vértice (0, 0, 10) debe quedar en (0, 0.010, 0)
        Object[] g = split(Glb.write(POS, TRI, null, null, col, true, 0.001, "prueba"));
        String json = (String) g[0];
        byte[] bin = (byte[]) g[1];
        assertTrue(json, json.contains("\"COLOR_0\"") && json.contains("\"indices\"") && !json.contains("TEXCOORD_0"));
        assertTrue(json, json.contains("\"count\":4,\"type\":\"VEC3\",\"min\""));
        assertTrue(json, json.contains("\"byteLength\":" + bin.length));
        // posiciones (4 × 12) + normales (48) + colores (48) + índices (12 × 4)
        assertEquals(48 + 48 + 48 + 48, bin.length);
        ByteBuffer b = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0.010f, b.getFloat(3 * 12 + 4), 1e-7f); // y del vértice 3
        assertEquals(0f, b.getFloat(3 * 12 + 8), 1e-7f);
        // (0, 10, 0) con Z arriba pasa a (0, 0, −0.010)
        assertEquals(-0.010f, b.getFloat(2 * 12 + 8), 1e-7f);
        // normales unitarias
        for (int v = 0; v < 4; v++) {
            double l = Math.sqrt(Math.pow(b.getFloat(48 + 12 * v), 2) + Math.pow(b.getFloat(48 + 12 * v + 4), 2) + Math.pow(b.getFloat(48 + 12 * v + 8), 2));
            assertEquals(1, l, 1e-5);
        }
        // el primer índice
        assertEquals(0, b.getInt(144));
        assertEquals(2, b.getInt(148));
    }

    @Test
    public void conTexturaRepiteLosVerticesEInvierteV() {
        float[] uv = new float[6 * 4];
        for (int i = 0; i < uv.length; i++) uv[i] = 0.25f;
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3, (byte) 0xFF, (byte) 0xD9}; // 7 bytes: obliga a rellenar
        Object[] g = split(Glb.write(POS, TRI, uv, jpeg, null, false, 0.001, "con \"comillas\""));
        String json = (String) g[0];
        byte[] bin = (byte[]) g[1];
        assertTrue(json, json.contains("\"TEXCOORD_0\"") && json.contains("\"image/jpeg\"") && json.contains("baseColorTexture"));
        assertTrue("sin índices", !json.contains("\"indices\""));
        assertTrue("12 vértices (3 por triángulo)", json.contains("\"count\":12,\"type\":\"VEC3\",\"min\""));
        assertTrue("el nombre no rompe el JSON", json.contains("\"name\":\"con comillas\""));
        // posiciones (12 × 12) + normales (144) + uv (12 × 8) + imagen (7 → 8)
        assertEquals(144 + 144 + 96 + 8, bin.length);
        ByteBuffer b = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0.25f, b.getFloat(288), 0);
        assertEquals("v invertida", 0.75f, b.getFloat(292), 0);
        assertEquals((byte) 0xFF, bin[384]);
        assertTrue(json, json.contains("\"byteOffset\":384,\"byteLength\":7"));
    }
}
