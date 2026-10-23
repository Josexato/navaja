package com.josexato.navaja.lan;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.josexato.navaja.lan.core.Device;
import com.josexato.navaja.ui.NavajaUi;
import com.josexato.navaja.ui.Palette;
import com.josexato.navaja.lan.core.LanScanner;
import com.josexato.navaja.lan.core.Ports;
import com.josexato.navaja.lan.core.Subnet;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Escáner de la red local (sólo la propia subred Wi‑Fi): muestra los datos de
 * la red y los equipos que va encontrando, con su tipo probable y puertos
 * abiertos. Todo ocurre en el teléfono; nada se envía a internet.
 */
public class LanActivity extends Activity {

    private NetInfo net;
    private MdnsScanner mdns;
    private LanScanner scanner;
    private Thread worker;
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** DNS inverso aparte: una IP sin nombre tarda segundos y no debe frenar el barrido. */
    private final ExecutorService dnsPool = Executors.newFixedThreadPool(4);
    private final List<Device> devices = new ArrayList<>();
    private boolean scanning;

    private TextView netView, statusView;
    private NavajaUi.Screen screen;
    private NavajaUi.Metric mDevices, mTime, mIp;
    private long scanStart;
    private Button scanBtn;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        refreshNet();
    }

    private void buildUi() {
        NavajaUi.Screen sc = NavajaUi.screen(this, "Red local", "sólo tu subred · nada sale a internet", null);
        screen = sc;
        LinearLayout root = sc.content;

        mDevices = NavajaUi.metric(this, "Equipos");
        mTime = NavajaUi.metric(this, "Barrido");
        mIp = NavajaUi.metric(this, "Tu IP");
        root.addView(NavajaUi.metricRow(this, mDevices, mTime, mIp));
        mDevices.set("—", null, 0);
        mTime.set("—", null, 0);
        mIp.set("—", null, 0);

        LinearLayout netCard = NavajaUi.card(this);
        netView = NavajaUi.text(this, "", NavajaUi.Style.MONO);
        netCard.addView(netView);
        root.addView(netCard);

        scanBtn = NavajaUi.primary(this, "Escanear la red", v -> toggleScan());
        root.addView(NavajaUi.buttonRow(this, scanBtn, NavajaUi.secondary(this, "Datos de red", v -> refreshNet())));

        statusView = NavajaUi.text(this, "", NavajaUi.Style.SECONDARY);
        statusView.setPadding(0, dp(8), 0, dp(2));
        root.addView(statusView);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(sc.root);
    }

    private void refreshNet() {
        net = NetInfo.read(this);
        if (!net.valid()) {
            netView.setText("No hay una red IPv4 activa. Conéctate a una Wi‑Fi.");
            scanBtn.setEnabled(false);
            return;
        }
        scanBtn.setEnabled(true);
        mIp.set(net.ipv4, net.subnet().cidr(), 0);
        screen.setSubtitle((net.wifi ? "Wi‑Fi" : "red") + " · " + net.subnet().cidr() + " · nada sale a internet");
        StringBuilder sb = new StringBuilder(net.summary());
        if (!net.wifi) sb.append("\n⚠ No estás en Wi‑Fi: el escaneo de datos móviles no tiene sentido.");
        netView.setText(sb.toString());
    }

    // ------------------------------------------------------------- escaneo

    private void toggleScan() {
        if (scanning) {
            if (scanner != null) scanner.cancel();
            return;
        }
        if (!net.valid()) return;
        devices.clear();
        list.removeAllViews();
        scanning = true;
        scanStart = System.currentTimeMillis();
        scanBtn.setText("Detener");
        mdns = new MdnsScanner(this);
        mdns.start();
        scanner = new LanScanner(new SocketProbe());
        final Subnet subnet = net.subnet();
        final String gw = net.gateway, self = net.ipv4;
        setStatus("Buscando equipos en " + subnet.cidr() + "…");
        worker = new Thread(() -> scanner.scan(subnet, gw, self, new LanScanner.Listener() {
            @Override
            public void onProgress(int done, int total) {
                ui.post(() -> {
                    setStatus(String.format(Locale.US, "Explorando… %d/%d (%d encontrados)", done, total, devices.size()));
                    mTime.set(String.format(Locale.US, "%.0f s", (System.currentTimeMillis() - scanStart) / 1000.0),
                            String.format(Locale.US, "%d%%", 100 * done / Math.max(1, total)), 0);
                });
            }

            @Override
            public void onDeviceFound(Device d) {
                d.services.addAll(mdns.servicesFor(d.ip));
                ui.post(() -> addDeviceRow(d));
                dnsPool.execute(() -> resolveHostname(d));
            }

            @Override
            public void onFinished(List<Device> found, boolean cancelled) {
                ui.post(() -> finishScan(found.size(), cancelled));
            }
        }), "lan-scan");
        worker.start();
    }

    private void finishScan(int count, boolean cancelled) {
        scanning = false;
        scanBtn.setText("Escanear la red");
        if (mdns != null) mdns.stop();
        setStatus((cancelled ? "Detenido. " : "Listo. ") + count + " equipos en la red.");
        mTime.set(String.format(Locale.US, "%.1f s", (System.currentTimeMillis() - scanStart) / 1000.0),
                cancelled ? "detenido" : "completo", 0);
    }

    /** DNS inverso (en dnsPool: puede tardar segundos por IP). */
    private void resolveHostname(Device d) {
        try {
            String name = InetAddress.getByName(d.ip).getCanonicalHostName();
            if (name != null && !name.equals(d.ip)) {
                ui.post(() -> {
                    d.hostname = name; // se escribe en el hilo de UI, que es quien lo lee
                    refreshList();
                });
            }
        } catch (Exception ignored) {
        }
    }

    // --------------------------------------------------------------- lista

    private void refreshList() {
        mDevices.set(Integer.toString(devices.size()), scanning ? "buscando…" : null, 0);
        list.removeAllViews();
        for (Device d : devices) list.addView(buildRow(d));
    }

    private void addDeviceRow(Device d) {
        devices.add(d);
        devices.sort((a, b) -> Long.compare(a.sortKey(), b.sortKey()));
        refreshList();
    }

    private View buildRow(Device d) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(8);
        card.setLayoutParams(clp);
        card.setBackground(NavajaUi.cardBackground(this));

        TextView icon = text(d.kind().icon, 26, Palette.TEXT);
        icon.setPadding(0, 0, dp(12), 0);
        card.addView(icon);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(d.displayName(), 15, Palette.TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(name);
        String sub = d.ip + "  ·  " + d.kind().label;
        TextView subv = text(sub, 12, Palette.TEXT_2);
        subv.setTypeface(Typeface.MONOSPACE);
        col.addView(subv);
        if (!d.openPorts.isEmpty()) {
            StringBuilder ps = new StringBuilder("Puertos: ");
            int i = 0;
            for (int p : d.openPorts) { if (i++ > 0) ps.append(", "); ps.append(p); }
            col.addView(text(ps.toString(), 11, Palette.TEXT_3));
        }
        card.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        if (d.latencyMs >= 0) {
            // Latencia de conexión TCP (no es ping ICMP): verde si responde rápido.
            int c = d.latencyMs < 20 ? Palette.OK : d.latencyMs < 100 ? Palette.WARN : Palette.BAD;
            TextView lat = text(d.latencyMs + " ms", 12, c);
            lat.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            lat.setPadding(dp(8), dp(3), dp(8), dp(3));
            lat.setBackground(NavajaUi.shape(Palette.withAlpha(c, 0x22), c, dp(10), 1));
            card.addView(lat);
        }
        card.setOnClickListener(v -> openDetail(d));
        return card;
    }

    // -------------------------------------------------------------- detalle

    private void openDetail(Device d) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(8));
        TextView head = text(d.kind().icon + "  " + d.displayName(), 17, Palette.TEXT);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(head);
        box.addView(text(d.ip + "  ·  " + d.kind().label, 13, Palette.TEXT_2));

        TextView info = text("Analizando puertos…", 13, Palette.TEXT);
        info.setPadding(0, dp(12), 0, 0);
        box.addView(info);

        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setView(wrapScroll(box))
                .setPositiveButton("Cerrar", null)
                .create();

        String web = d.webUrl();
        if (web != null) {
            dlg.setButton(android.app.AlertDialog.BUTTON_NEUTRAL, "Abrir web", (di, w) -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(web)));
                } catch (Exception e) {
                    Toast.makeText(this, "No se pudo abrir " + web, Toast.LENGTH_SHORT).show();
                }
            });
        }
        dlg.show();

        // Análisis de puertos completo en segundo plano.
        new Thread(() -> {
            LanScanner s = new LanScanner(new SocketProbe());
            s.scanPorts(d);
            if (!d.services.isEmpty()) d.services.addAll(mdns == null ? d.services : mdns.servicesFor(d.ip));
            ui.post(() -> {
                StringBuilder sb = new StringBuilder();
                if (d.hostname != null) sb.append("Nombre: ").append(d.hostname).append('\n');
                if (!d.services.isEmpty()) sb.append("Servicios mDNS: ").append(String.join(", ", d.services)).append('\n');
                sb.append('\n');
                if (d.openPorts.isEmpty()) {
                    sb.append("Sin puertos conocidos abiertos.\nResponde al ping/TCP pero no publica servicios.");
                } else {
                    sb.append("Puertos abiertos:\n");
                    for (int p : d.openPorts) sb.append("  ").append(p).append("  ").append(Ports.name(p)).append('\n');
                }
                info.setText(sb.toString().trim());
                refreshList();
            });
        }, "lan-ports").start();
    }

    // ------------------------------------------------------------ utilidades

    @Override
    protected void onDestroy() {
        if (scanner != null) scanner.cancel();
        if (mdns != null) mdns.stop();
        dnsPool.shutdownNow();
        super.onDestroy();
    }

    private void setStatus(String s) { statusView.setText(s); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private ScrollView wrapScroll(View v) {
        ScrollView s = new ScrollView(this);
        s.addView(v, new ViewGroup.LayoutParams(-1, -2));
        return s;
    }
}
