package com.josexato.navaja.lan.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Barrido de descubrimiento de la red local: por cada IP del rango prueba unos
 * pocos puertos TCP conocidos; si alguno acepta o rechaza la conexión, hay un
 * equipo vivo. Luego, por equipo, se puede analizar la lista completa de puertos.
 *
 * Toda la lógica de red pasa por {@link HostProbe}, así que el núcleo se prueba
 * con equipos simulados. Diseñado para la PROPIA red del usuario; sólo recorre
 * la subred a la que está conectado el teléfono.
 */
public final class LanScanner {

    public interface Listener {
        void onProgress(int done, int total);
        void onDeviceFound(Device d);
        void onFinished(List<Device> devices, boolean cancelled);
    }

    private final HostProbe probe;
    private final int threads;
    private final int discoveryTimeoutMs;
    private final int portTimeoutMs;
    private volatile boolean cancelled;
    private ExecutorService pool;

    public LanScanner(HostProbe probe) { this(probe, 64, 300, 500); }

    public LanScanner(HostProbe probe, int threads, int discoveryTimeoutMs, int portTimeoutMs) {
        this.probe = probe;
        this.threads = threads;
        this.discoveryTimeoutMs = discoveryTimeoutMs;
        this.portTimeoutMs = portTimeoutMs;
    }

    public void cancel() {
        cancelled = true;
        if (pool != null) pool.shutdownNow();
    }

    /** ¿La IP responde en alguno de los puertos de descubrimiento? Devuelve el Device o null. */
    public Device discover(String ip, String gatewayIp, String selfIp) {
        Device d = new Device(ip);
        long t0 = System.currentTimeMillis();
        boolean alive = false;
        for (int port : Ports.DISCOVERY) {
            if (cancelled) return null;
            HostProbe.State st = probe.probe(ip, port, discoveryTimeoutMs);
            if (st == HostProbe.State.OPEN) {
                d.openPorts.add(port);
                alive = true;
            } else if (st == HostProbe.State.REFUSED) {
                alive = true; // hay alguien, pero ese puerto está cerrado
            } else if (st == HostProbe.State.UNREACHABLE) {
                return null;  // sin ruta: no seguimos probando esta IP
            }
        }
        if (!alive) return null;
        d.latencyMs = System.currentTimeMillis() - t0;
        d.isGateway = ip.equals(gatewayIp);
        d.isSelf = ip.equals(selfIp);
        return d;
    }

    /** Barrido completo del rango. Bloqueante; llamar desde un hilo de trabajo. */
    public List<Device> scan(Subnet subnet, String gatewayIp, String selfIp, Listener listener) {
        cancelled = false;
        List<String> hosts = subnet.hosts();
        int total = hosts.size();
        List<Device> found = new ArrayList<>();
        pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Device>> futures = new ArrayList<>(total);
            for (String ip : hosts) futures.add(pool.submit(() -> discover(ip, gatewayIp, selfIp)));
            int done = 0;
            for (Future<Device> f : futures) {
                if (cancelled) break;
                Device d = null;
                try {
                    d = f.get();
                } catch (Exception ignored) {
                    // una IP que falló no detiene el barrido
                }
                done++;
                if (d != null) {
                    synchronized (found) { found.add(d); }
                    if (listener != null) listener.onDeviceFound(d);
                }
                if (listener != null) listener.onProgress(done, total);
            }
        } finally {
            pool.shutdownNow();
        }
        found.sort((a, b) -> Long.compare(a.sortKey(), b.sortKey()));
        if (listener != null) listener.onFinished(found, cancelled);
        return found;
    }

    /** Analiza en paralelo todos los puertos de {@link Ports#COMMON} de un equipo. */
    public Device scanPorts(Device d) {
        cancelled = false;
        List<Integer> ports = new ArrayList<>(Ports.COMMON.keySet());
        ExecutorService ex = Executors.newFixedThreadPool(Math.min(threads, ports.size()));
        try {
            List<Future<Integer>> fs = new ArrayList<>();
            for (int port : ports) {
                final int p = port;
                fs.add(ex.submit((Callable<Integer>) () ->
                        probe.probe(d.ip, p, portTimeoutMs) == HostProbe.State.OPEN ? p : -1));
            }
            for (Future<Integer> f : fs) {
                try {
                    int p = f.get();
                    if (p > 0) d.openPorts.add(p);
                } catch (Exception ignored) {
                }
            }
        } finally {
            ex.shutdownNow();
        }
        return d;
    }
}
