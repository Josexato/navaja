package com.josexato.navaja.lan.core;

import java.util.ArrayList;
import java.util.List;

/** Aritmética de subredes IPv4 (sin dependencias de Android). */
public final class Subnet {

    /** Tope de direcciones a barrer: una /24 son 254 equipos. Redes mayores se limitan a la /24 propia. */
    public static final int MIN_PREFIX_SCAN = 24;

    public final int address;   // IP propia
    public final int prefix;    // longitud de máscara usada para el barrido

    public Subnet(int address, int prefix) {
        this.address = address;
        this.prefix = Math.max(MIN_PREFIX_SCAN, Math.min(30, prefix));
    }

    public static Subnet parse(String ip, int prefix) {
        return new Subnet(toInt(ip), prefix);
    }

    public int mask() { return prefix == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - prefix)); }

    public int network() { return address & mask(); }

    public int broadcast() { return network() | ~mask(); }

    public int hostCount() {
        long first = (network() & 0xFFFFFFFFL) + 1, last = (broadcast() & 0xFFFFFFFFL) - 1;
        return (int) Math.max(0, last - first + 1);
    }

    /** Direcciones de equipo del rango (sin red ni broadcast), en orden. */
    public List<String> hosts() {
        List<String> out = new ArrayList<>();
        long first = (network() & 0xFFFFFFFFL) + 1, last = (broadcast() & 0xFFFFFFFFL) - 1;
        for (long a = first; a <= last; a++) out.add(toString((int) a));
        return out;
    }

    public boolean contains(String ip) {
        return (toInt(ip) & mask()) == network();
    }

    public String cidr() { return toString(network()) + "/" + prefix; }

    public static int toInt(String ip) {
        String[] p = ip.trim().split("\\.");
        if (p.length != 4) throw new IllegalArgumentException("IPv4 no válida: " + ip);
        int v = 0;
        for (String s : p) {
            int b = Integer.parseInt(s);
            if (b < 0 || b > 255) throw new IllegalArgumentException("IPv4 no válida: " + ip);
            v = (v << 8) | b;
        }
        return v;
    }

    public static String toString(int ip) {
        return ((ip >>> 24) & 255) + "." + ((ip >>> 16) & 255) + "." + ((ip >>> 8) & 255) + "." + (ip & 255);
    }

    /** Clave numérica para ordenar IPs ("192.168.1.10" después de "192.168.1.9"). */
    public static long sortKey(String ip) {
        return toInt(ip) & 0xFFFFFFFFL;
    }
}
