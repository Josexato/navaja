package com.josexato.navaja.lan;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;

import java.net.InetAddress;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Descubrimiento mDNS/Bonjour con NsdManager: anota, por IP, los tipos de
 * servicio que anuncia cada equipo (impresora, Chromecast, AirPlay, etc.).
 * Es best‑effort y mejora la identificación; el barrido funciona sin esto.
 */
final class MdnsScanner {

    /** Tipos de servicio habituales en una red doméstica/oficina. */
    private static final String[] TYPES = {
            "_http._tcp.", "_ipp._tcp.", "_ipps._tcp.", "_printer._tcp.", "_pdl-datastream._tcp.",
            "_googlecast._tcp.", "_airplay._tcp.", "_raop._tcp.", "_spotify-connect._tcp.",
            "_apple-mobdev2._tcp.", "_companion-link._tcp.", "_rtsp._tcp.", "_smb._tcp.",
            "_afpovertcp._tcp.", "_adisk._tcp.", "_hap._tcp.", "_workstation._tcp.", "_sftp-ssh._tcp.",
    };

    private final NsdManager nsd;
    private final Map<String, java.util.Set<String>> byIp = new ConcurrentHashMap<>();
    private final java.util.List<NsdManager.DiscoveryListener> listeners = new java.util.ArrayList<>();

    MdnsScanner(Context ctx) {
        nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
    }

    /** Servicios vistos para una IP (tipos como "_ipp._tcp"), o conjunto vacío. */
    java.util.Set<String> servicesFor(String ip) {
        java.util.Set<String> s = byIp.get(ip);
        return s == null ? Collections.emptySet() : s;
    }

    void start() {
        if (nsd == null) return;
        for (String type : TYPES) {
            NsdManager.DiscoveryListener l = new NsdManager.DiscoveryListener() {
                @Override public void onDiscoveryStarted(String t) { }
                @Override public void onDiscoveryStopped(String t) { }
                @Override public void onStartDiscoveryFailed(String t, int e) { }
                @Override public void onStopDiscoveryFailed(String t, int e) { }
                @Override public void onServiceLost(NsdServiceInfo s) { }
                @Override public void onServiceFound(NsdServiceInfo s) {
                    resolve(s, type);
                }
            };
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l);
                synchronized (listeners) { listeners.add(l); }
            } catch (Exception ignored) {
            }
        }
    }

    private void resolve(NsdServiceInfo s, String type) {
        String shortType = type.replaceFirst("\\.$", "");
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                nsd.registerServiceInfoCallback(s, Runnable::run, new NsdManager.ServiceInfoCallback() {
                    @Override public void onServiceInfoCallbackRegistrationFailed(int e) { }
                    @Override public void onServiceUpdated(NsdServiceInfo info) { record(info, shortType); }
                    @Override public void onServiceLost() { }
                    @Override public void onServiceInfoCallbackUnregistered() { }
                });
            } else {
                nsd.resolveService(s, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo info, int e) { }
                    @Override public void onServiceResolved(NsdServiceInfo info) { record(info, shortType); }
                });
            }
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("deprecation") // getHost() está disponible en todas las API objetivo
    private void record(NsdServiceInfo info, String type) {
        InetAddress host = info.getHost();
        if (host == null) return;
        String ip = host.getHostAddress();
        if (ip == null) return;
        byIp.computeIfAbsent(ip, k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(type);
    }

    void stop() {
        if (nsd == null) return;
        synchronized (listeners) {
            for (NsdManager.DiscoveryListener l : listeners) {
                try {
                    nsd.stopServiceDiscovery(l);
                } catch (Exception ignored) {
                }
            }
            listeners.clear();
        }
    }
}
