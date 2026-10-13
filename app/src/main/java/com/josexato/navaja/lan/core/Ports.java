package com.josexato.navaja.lan.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Puertos TCP conocidos para descubrir e identificar equipos. */
public final class Ports {

    private Ports() {}

    /**
     * Puertos para saber si una IP está ocupada. Basta con que uno responda:
     * aceptar la conexión o rechazarla con RST prueban que hay un equipo en esa IP.
     */
    public static final int[] DISCOVERY = {80, 443, 22, 445, 139, 8080, 62078, 7000, 8009, 9100, 554, 5000, 3389};

    /** Puertos que se revisan al abrir el detalle de un equipo (nombre del servicio habitual). */
    public static final Map<Integer, String> COMMON = new LinkedHashMap<>();

    static {
        COMMON.put(21, "FTP");
        COMMON.put(22, "SSH");
        COMMON.put(23, "Telnet");
        COMMON.put(25, "SMTP");
        COMMON.put(53, "DNS");
        COMMON.put(80, "HTTP (web)");
        COMMON.put(110, "POP3");
        COMMON.put(139, "NetBIOS");
        COMMON.put(143, "IMAP");
        COMMON.put(443, "HTTPS (web segura)");
        COMMON.put(445, "SMB (archivos compartidos)");
        COMMON.put(515, "LPD (impresora)");
        COMMON.put(548, "AFP (Apple)");
        COMMON.put(554, "RTSP (cámara/vídeo)");
        COMMON.put(631, "IPP (impresora)");
        COMMON.put(1883, "MQTT (domótica)");
        COMMON.put(1900, "UPnP");
        COMMON.put(3306, "MySQL");
        COMMON.put(3389, "Escritorio remoto (RDP)");
        COMMON.put(5000, "UPnP / NAS");
        COMMON.put(5001, "NAS (HTTPS)");
        COMMON.put(5060, "SIP (VoIP)");
        COMMON.put(5900, "VNC");
        COMMON.put(7000, "AirPlay");
        COMMON.put(8008, "Chromecast");
        COMMON.put(8009, "Chromecast");
        COMMON.put(8080, "HTTP alternativo");
        COMMON.put(8443, "HTTPS alternativo");
        COMMON.put(8883, "MQTT seguro");
        COMMON.put(9100, "Impresión RAW (JetDirect)");
        COMMON.put(32400, "Plex");
        COMMON.put(62078, "iPhone/iPad (sincronización)");
    }

    public static String name(int port) {
        String n = COMMON.get(port);
        return n != null ? n : "puerto " + port;
    }

    /** URL web del equipo si tiene un puerto HTTP/HTTPS abierto, o null. */
    public static String webUrl(String ip, Iterable<Integer> open) {
        for (int p : open) if (p == 443 || p == 8443 || p == 5001) return "https://" + ip + (p == 443 ? "" : ":" + p);
        for (int p : open) if (p == 80 || p == 8080 || p == 5000 || p == 32400) return "http://" + ip + (p == 80 ? "" : ":" + p);
        return null;
    }
}
