package com.josexato.navaja.lan.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/** Red simulada: un HostProbe en memoria con puertos abiertos por IP. */
public class LanScannerTest {

    /** Equipo simulado: puertos que acepta. El resto rechaza (equipo vivo). */
    static final class FakeNet implements HostProbe {
        final Map<String, Set<Integer>> openByIp = new HashMap<>();
        final Set<String> alive = new TreeSet<>(); // IPs vivas aunque no abran ningún puerto

        FakeNet host(String ip, int... open) {
            Set<Integer> s = new TreeSet<>();
            for (int p : open) s.add(p);
            openByIp.put(ip, s);
            alive.add(ip);
            return this;
        }

        @Override
        public State probe(String ip, int port, int timeoutMs) {
            Set<Integer> open = openByIp.get(ip);
            if (open != null && open.contains(port)) return State.OPEN;
            if (alive.contains(ip)) return State.REFUSED; // vivo, puerto cerrado
            return State.TIMEOUT;                          // nadie en esa IP
        }
    }

    @Test
    public void subnetMath() {
        Subnet s = Subnet.parse("192.168.1.37", 24);
        assertEquals("192.168.1.0/24", s.cidr());
        assertEquals(254, s.hostCount());
        assertEquals("192.168.1.1", s.hosts().get(0));
        assertEquals("192.168.1.254", s.hosts().get(253));
        assertTrue(s.contains("192.168.1.200"));
        assertFalse(s.contains("192.168.2.1"));
        // Redes grandes se acotan a /24 para no barrer miles de direcciones.
        assertEquals(24, Subnet.parse("10.0.0.5", 8).prefix);
    }

    @Test
    public void findsAliveHostsAndClassifies() {
        FakeNet net = new FakeNet()
                .host("192.168.1.1", 80, 443, 53)             // router
                .host("192.168.1.10", 9100, 631)              // impresora
                .host("192.168.1.20", 8009)                   // Chromecast
                .host("192.168.1.30", 445, 139, 3389)         // PC Windows
                .host("192.168.1.40", 22)                     // servidor Linux
                .host("192.168.1.55", 554, 80)                // cámara IP
                .host("192.168.1.60", 5000, 445)              // NAS
                .host("192.168.1.77", 62078)                  // iPhone
                .host("192.168.1.99");                        // vivo, sin puertos abiertos
        // ...y una IP muerta (no la registramos): 192.168.1.2 no responde.

        LanScanner sc = new LanScanner(net, 32, 50, 50);
        AtomicInteger progress = new AtomicInteger();
        List<Device> devices = sc.scan(Subnet.parse("192.168.1.37", 24), "192.168.1.1", "192.168.1.37",
                new LanScanner.Listener() {
                    public void onProgress(int done, int total) { progress.set(total); }
                    public void onDeviceFound(Device d) { }
                    public void onFinished(List<Device> d, boolean cancelled) { }
                });

        assertEquals(254, progress.get());
        assertEquals(9, devices.size());
        Map<String, DeviceType.Kind> byIp = new HashMap<>();
        for (Device d : devices) byIp.put(d.ip, d.kind());
        assertEquals(DeviceType.Kind.ROUTER, byIp.get("192.168.1.1"));
        assertEquals(DeviceType.Kind.PRINTER, byIp.get("192.168.1.10"));
        assertEquals(DeviceType.Kind.TV, byIp.get("192.168.1.20"));
        assertEquals(DeviceType.Kind.PC, byIp.get("192.168.1.30"));
        assertEquals(DeviceType.Kind.SERVER, byIp.get("192.168.1.40"));
        assertEquals(DeviceType.Kind.CAMERA, byIp.get("192.168.1.55"));
        assertEquals(DeviceType.Kind.NAS, byIp.get("192.168.1.60"));
        assertEquals(DeviceType.Kind.APPLE, byIp.get("192.168.1.77"));
        assertEquals(DeviceType.Kind.UNKNOWN, byIp.get("192.168.1.99"));

        // Orden por IP numérica.
        for (int i = 1; i < devices.size(); i++)
            assertTrue(devices.get(i - 1).sortKey() < devices.get(i).sortKey());
    }

    @Test
    public void unreachableIpIsSkippedFast() {
        HostProbe net = (ip, port, t) -> HostProbe.State.UNREACHABLE;
        Device d = new LanScanner(net).discover("192.168.1.5", "192.168.1.1", "192.168.1.37");
        assertNull(d);
    }

    @Test
    public void mdnsServiceOverridesPortGuess() {
        // Un equipo con sólo el 80 abierto, pero que anuncia AirPlay, es una TV.
        Set<Integer> ports = new TreeSet<>();
        ports.add(80);
        Set<String> svc = new TreeSet<>();
        svc.add("_airplay._tcp");
        assertEquals(DeviceType.Kind.TV, DeviceType.classify(false, false, ports, svc));
    }

    @Test
    public void selfAndGatewayWin() {
        Set<Integer> none = new TreeSet<>();
        assertEquals(DeviceType.Kind.PHONE_SELF, DeviceType.classify(false, true, none, null));
        assertEquals(DeviceType.Kind.ROUTER, DeviceType.classify(true, false, none, null));
    }

    @Test
    public void webUrlPrefersHttps() {
        Set<Integer> p = new TreeSet<>();
        p.add(80);
        p.add(443);
        assertEquals("https://192.168.1.1", Ports.webUrl("192.168.1.1", p));
        Set<Integer> q = new TreeSet<>();
        q.add(8080);
        assertEquals("http://192.168.1.1:8080", Ports.webUrl("192.168.1.1", q));
        assertNull(Ports.webUrl("192.168.1.1", new TreeSet<>()));
    }

    @Test
    public void portScanListsOpenServices() {
        FakeNet net = new FakeNet().host("192.168.1.60", 22, 80, 443, 445, 5000, 5001);
        Device d = new LanScanner(net, 16, 50, 50).scanPorts(new Device("192.168.1.60"));
        assertTrue(d.openPorts.contains(22));
        assertTrue(d.openPorts.contains(5001));
        assertFalse(d.openPorts.contains(9100));
        assertEquals("SMB (archivos compartidos)", Ports.name(445));
    }
}
