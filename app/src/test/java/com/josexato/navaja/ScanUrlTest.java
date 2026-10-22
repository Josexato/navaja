package com.josexato.navaja;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** El visor pide el escaneo con "?t=<hora>"; la intercepción debe reconocerlo igual. */
public class ScanUrlTest {

    @Test
    public void acceptsCacheBustingQuery() {
        assertTrue(MainActivity.isScanUrl("https://navaja.local/scan/modelo.obj"));
        assertTrue(MainActivity.isScanUrl("https://navaja.local/scan/modelo.obj?t=1761100000000"));
    }

    @Test
    public void rejectsOtherUrls() {
        assertFalse(MainActivity.isScanUrl("https://navaja.local/scan/otro.obj?t=1"));
        assertFalse(MainActivity.isScanUrl("https://ejemplo.com/scan/modelo.obj"));
        assertFalse(MainActivity.isScanUrl("file:///android_asset/index.html"));
        assertFalse(MainActivity.isScanUrl("no es una url"));
    }
}
