package com.josexato.navaja.lan.core;

import java.util.Locale;
import java.util.Set;

/**
 * Clasifica un equipo por sus pistas (puertos abiertos y servicios mDNS
 * anunciados). Es una heurística orientativa, no una identificación segura.
 */
public final class DeviceType {

    private DeviceType() {}

    public enum Kind {
        ROUTER("Router / puerta de enlace", "📶"),
        PHONE_SELF("Este teléfono", "📱"),
        APPLE("iPhone / iPad / Mac", "📱"),
        PRINTER("Impresora", "🖨️"),
        TV("TV / Chromecast", "📺"),
        CAMERA("Cámara IP", "📷"),
        NAS("NAS / servidor de archivos", "🗄️"),
        PC("Computadora (Windows)", "💻"),
        SERVER("Servidor / Linux (SSH)", "🖥️"),
        IOT("Domótica / IoT", "💡"),
        AUDIO("Altavoz / audio", "🔊"),
        UNKNOWN("Equipo", "❔");

        public final String label, icon;

        Kind(String label, String icon) { this.label = label; this.icon = icon; }
    }

    /**
     * @param isGateway  es la puerta de enlace de la red
     * @param isSelf     es el propio teléfono
     * @param ports      puertos TCP abiertos
     * @param services   tipos de servicio mDNS vistos (p. ej. "_ipp._tcp", "_googlecast._tcp")
     */
    public static Kind classify(boolean isGateway, boolean isSelf, Set<Integer> ports, Set<String> services) {
        if (isSelf) return Kind.PHONE_SELF;
        if (isGateway) return Kind.ROUTER;
        String s = services == null ? "" : String.join(" ", services).toLowerCase(Locale.ROOT);
        if (p(ports, 9100, 631, 515) || c(s, "_ipp", "_printer", "_pdl", "_scanner")) return Kind.PRINTER;
        if (p(ports, 8008, 8009) || c(s, "googlecast", "_androidtvremote")) return Kind.TV;
        if (c(s, "_airplay") && !p(ports, 62078)) return Kind.TV;
        if (p(ports, 62078) || c(s, "_apple-mobdev", "_companion-link")) return Kind.APPLE;
        if (c(s, "_raop", "_spotify-connect", "_sonos")) return Kind.AUDIO;
        if (p(ports, 554) || c(s, "_rtsp")) return Kind.CAMERA;
        if (p(ports, 5000, 5001, 548) || c(s, "_afpovertcp", "_adisk", "_nas")) return Kind.NAS;
        if (p(ports, 3389) || (p(ports, 445, 139) && !p(ports, 22))) return Kind.PC;
        if (p(ports, 1883, 8883) || c(s, "_hap", "_matter", "_mqtt", "_hue")) return Kind.IOT;
        if (p(ports, 22)) return Kind.SERVER;
        if (c(s, "_workstation", "_smb")) return Kind.PC;
        return Kind.UNKNOWN;
    }

    private static boolean p(Set<Integer> ports, int... any) {
        if (ports == null) return false;
        for (int port : any) if (ports.contains(port)) return true;
        return false;
    }

    private static boolean c(String s, String... any) {
        for (String t : any) if (s.contains(t)) return true;
        return false;
    }
}
