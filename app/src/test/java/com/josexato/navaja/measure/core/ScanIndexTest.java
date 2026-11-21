package com.josexato.navaja.measure.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ScanIndexTest {

    static Object[] f(String name, long size) { return new Object[]{name, size, 1000L}; }

    @Test
    public void agrupaLosArchivosPorEscaneo() {
        List<Object[]> files = new ArrayList<>();
        files.add(f("medida_20261119_234612.zip", 30_000_000));
        files.add(f("medida_20261119_234612.mp4", 40_000_000));
        files.add(f("medida_20261119_234612.sensores.csv", 200_000));
        files.add(f("medida_20261119_234612.json", 900));
        files.add(f("medida_20261120_005838.zip", 20_000_000));
        files.add(f("video_20261120_010000.mp4", 35_000_000));
        files.add(f("video_20261120_010000.sensores.csv", 100_000));
        files.add(f("notas.txt", 10));
        List<ScanIndex.Loose> loose = new ArrayList<>();
        List<ScanIndex.Entry> e = ScanIndex.group(files, loose);
        assertEquals(2, e.size());
        assertEquals("medida_20261120_005838", e.get(0).base); // el más nuevo primero
        assertEquals("2026-11-20 00:58:38", e.get(0).saved);
        assertEquals(0, e.get(0).video);
        assertEquals(40_000_000, e.get(1).video);
        assertEquals(200_000, e.get(1).sensors);
        assertEquals(900, e.get(1).json);
        assertEquals(1, loose.size());
        assertEquals("video_20261120_010000.mp4", loose.get(0).name);
    }

    @Test
    public void leeDelInformeLoQueSeMuestra() {
        String r = "# Escáner 3D · video sobre la hoja con marcas — informe\n\n"
                + "Generado por Navaja 1.0.45 (compilación 45 del CI, commit a774ff2) el 2026-11-20 00:58.\n"
                + "Medidas en cm (escala con la hoja impresa).\n\n## 1. Video\n\n- Duración: 30.1 s · se analizan 15 cuadros por segundo, 540 × 960 px.\n"
                + "- Cuadros: 451 analizados · 451 con 4 marcas o más · 449 ubicados (error de las esquinas: mediana 1.18 px).\n"
                + "- XFeat + LighterGlue (puntos aprendidos; cada foto clave con las 2 anteriores): XFeat en CPU (4 hilos): 60 ms por foto (113 fotos) · "
                + "LighterGlue en CPU (4 hilos): 213 ms por par (223 pares) · pares de puntos 143376.\n"
                + "- Sin sensores: el video no se grabó con la pantalla de grabación de Navaja.\n"
                + "- Por puntos: alto (del apoyo al tope) 13.1 · ancho 8.7 · largo 12.1 cm.\n"
                + "- Por siluetas (62 fotos): alto (del apoyo al tope) 12.1 · ancho 8.9 · largo 11.7 cm · volumen 951 cm³.\n"
                + "- Diferencia entre los dos métodos: 5.1 %.\n";
        ScanIndex.Summary s = ScanIndex.parse(r);
        assertEquals("Video sobre la hoja con marcas", s.mode);
        assertEquals("1.0.45 (compilación 45 del CI, commit a774ff2)", s.version);
        assertEquals("2026-11-20 00:58", s.generated);
        assertEquals("30.1 s", s.duration);
        assertEquals("449 de 451 cuadros ubicados por la hoja", s.sheet);
        assertEquals("XFeat en CPU (4 hilos) (60 ms) · LighterGlue en CPU (4 hilos) (213 ms)", s.accelerator);
        assertTrue(s.sensors.startsWith("no"));
        assertTrue(s.byPoints.startsWith("alto (del apoyo al tope) 13.1"));
        assertEquals("5.1 %.", s.difference);
        // Un informe viejo, sin versión
        ScanIndex.Summary old = ScanIndex.parse("# Escáner 3D desde video — informe\n\nGenerado por Navaja el 2026-11-19 07:19.\n- Grabado con sensores (10 orientaciones).\n");
        assertEquals("Video alrededor del objeto", old.mode);
        assertTrue(old.version.startsWith("anterior"));
        assertEquals("2026-11-19 07:19", old.generated);
        assertEquals("sí", old.sensors);
    }
}
