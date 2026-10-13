package com.josexato.navaja.lan.core;

import java.util.Set;
import java.util.TreeSet;

/** Un equipo encontrado en la red y lo que se sabe de él. */
public final class Device {

    public final String ip;
    public String hostname;                               // DNS inverso o mDNS (si hay)
    public String mac;                                    // de la tabla ARP (si hay acceso)
    public String vendor;                                 // fabricante por OUI de la MAC
    public final Set<Integer> openPorts = new TreeSet<>();
    public final Set<String> services = new TreeSet<>();  // tipos de servicio mDNS
    public long latencyMs = -1;
    public boolean isGateway, isSelf;

    public Device(String ip) { this.ip = ip; }

    public DeviceType.Kind kind() {
        return DeviceType.classify(isGateway, isSelf, openPorts, services);
    }

    /** URL web del equipo si tiene puerto HTTP/HTTPS abierto, o null. */
    public String webUrl() {
        return Ports.webUrl(ip, openPorts);
    }

    /** Nombre a mostrar: el hostname, o el tipo de equipo si no hay. */
    public String displayName() {
        if (hostname != null && !hostname.isEmpty()) return hostname;
        if (vendor != null && !vendor.isEmpty()) return vendor;
        return kind().label;
    }

    public long sortKey() { return Subnet.sortKey(ip); }
}
