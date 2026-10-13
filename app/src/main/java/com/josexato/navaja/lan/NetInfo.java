package com.josexato.navaja.lan;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;

import com.josexato.navaja.lan.core.Subnet;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Datos de la red Wi‑Fi actual, leídos con ConnectivityManager (sin APIs ocultas). */
final class NetInfo {

    String ipv4;
    int prefix = 24;
    String gateway;
    final List<String> dns = new ArrayList<>();
    String interfaceName;
    boolean wifi;
    boolean metered;

    boolean valid() { return ipv4 != null && gateway != null; }

    Subnet subnet() { return Subnet.parse(ipv4, prefix); }

    static NetInfo read(Context ctx) {
        NetInfo info = new NetInfo();
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return info;
        Network net = cm.getActiveNetwork();
        if (net == null) return info;
        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
        if (caps != null) {
            info.wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            info.metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        }
        LinkProperties lp = cm.getLinkProperties(net);
        if (lp == null) return info;
        info.interfaceName = lp.getInterfaceName();
        for (LinkAddress la : lp.getLinkAddresses()) {
            if (la.getAddress() instanceof Inet4Address && !la.getAddress().isLoopbackAddress()) {
                info.ipv4 = la.getAddress().getHostAddress();
                info.prefix = la.getPrefixLength();
            }
        }
        for (RouteInfo r : lp.getRoutes()) {
            InetAddress gw = r.getGateway();
            if (r.isDefaultRoute() && gw instanceof Inet4Address && !gw.isAnyLocalAddress()) {
                info.gateway = gw.getHostAddress();
            }
        }
        for (InetAddress d : lp.getDnsServers()) {
            if (d instanceof Inet4Address) info.dns.add(d.getHostAddress());
        }
        return info;
    }

    String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("IP: ").append(ipv4).append('\n');
        sb.append("Red: ").append(subnet().cidr()).append(String.format(Locale.US, " (%d equipos)", subnet().hostCount())).append('\n');
        sb.append("Puerta de enlace: ").append(gateway).append('\n');
        if (!dns.isEmpty()) sb.append("DNS: ").append(String.join(", ", dns)).append('\n');
        sb.append("Interfaz: ").append(interfaceName == null ? "?" : interfaceName)
                .append(wifi ? " (Wi‑Fi)" : "").append(metered ? " · medida" : "");
        return sb.toString();
    }
}
