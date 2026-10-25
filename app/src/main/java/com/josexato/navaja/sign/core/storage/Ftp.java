package com.josexato.navaja.sign.core.storage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Writer;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Cliente FTP mínimo (RFC 959, modo pasivo, binario) con FTPS explícito
 * opcional (AUTH TLS, RFC 4217). En FTP sin TLS la contraseña viaja en
 * claro: úsalo sólo en tu red local. Algunos servidores FTPS exigen reusar
 * la sesión TLS en el canal de datos, cosa que Java no hace; para esos,
 * WebDAV o una app de archivos con SFTP/SMB vía el selector de Android.
 */
public final class Ftp implements Backend {

    private final String host, user, pass, dir;
    private final int port;
    private final boolean tls;

    public Ftp(String host, int port, String user, String pass, String dir, boolean tls) {
        this.host = host;
        this.port = port <= 0 ? 21 : port;
        this.user = user == null || user.isEmpty() ? "anonymous" : user;
        this.pass = pass == null ? "" : pass;
        this.dir = dir == null ? "" : dir.replaceAll("/+$", "");
        this.tls = tls;
    }

    @Override public String describe() { return (tls ? "FTPS " : "FTP ") + user + "@" + host + ":" + port + "/" + dir; }

    private Socket ctl;
    private BufferedReader in;
    private Writer out;

    @Override
    public synchronized void put(String path, byte[] data, String contentType) throws IOException {
        ctl = new Socket();
        ctl.connect(new InetSocketAddress(host, port), 15_000);
        ctl.setSoTimeout(30_000);
        try {
            streams(ctl);
            expect(reply(), 220);
            if (tls) {
                expect(cmd("AUTH TLS"), 234);
                ctl = ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(ctl, host, port, true);
                ((SSLSocket) ctl).startHandshake();
                streams(ctl);
            }
            int r = cmd("USER " + user);
            if (r == 331) r = cmd("PASS " + pass);
            expect(r, 230);
            if (tls) {
                expect(cmd("PBSZ 0"), 200);
                expect(cmd("PROT P"), 200);
            }
            expect(cmd("TYPE I"), 200);
            String full = dir.isEmpty() ? path : dir + "/" + path;
            String[] parts = full.split("/");
            StringBuilder d = new StringBuilder();
            for (int i = 0; i < parts.length - 1; i++) {
                if (parts[i].isEmpty()) { d.append('/'); continue; }
                d.append(parts[i]);
                cmd("MKD " + d); // 550 si ya existe: se ignora
                d.append('/');
            }
            int pasv = cmd("PASV");
            expect(pasv, 227);
            InetSocketAddress dataAddr = parsePasv(lastLine);
            Socket ds = new Socket();
            ds.connect(dataAddr, 15_000);
            if (tls) {
                ds = ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(ds, host, dataAddr.getPort(), true);
            }
            int st = cmd("STOR " + full);
            if (st != 150 && st != 125) { ds.close(); throw new IOException("STOR rechazado: " + lastLine); }
            try (OutputStream o = ds.getOutputStream()) { o.write(data); }
            ds.close();
            expect(reply(), 226);
            cmd("QUIT");
        } finally {
            ctl.close();
        }
    }

    private void streams(Socket s) throws IOException {
        in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
    }

    private String lastLine = "";

    private int cmd(String c) throws IOException {
        out.write(c + "\r\n");
        out.flush();
        return reply();
    }

    /** Lee una respuesta (incluidas las multilínea "123-…" hasta "123 …"). */
    private int reply() throws IOException {
        String line = in.readLine();
        if (line == null || line.length() < 3) throw new IOException("El servidor FTP cerró la conexión");
        String code = line.substring(0, 3);
        if (line.length() > 3 && line.charAt(3) == '-') {
            while ((line = in.readLine()) != null && !(line.startsWith(code) && line.length() > 3 && line.charAt(3) == ' ')) {
                // líneas intermedias
            }
            if (line == null) throw new IOException("Respuesta FTP incompleta");
        }
        lastLine = line;
        try {
            return Integer.parseInt(code);
        } catch (NumberFormatException e) {
            throw new IOException("Respuesta FTP inválida: " + line);
        }
    }

    private void expect(int got, int want) throws IOException {
        if (got != want) throw new IOException("FTP: se esperaba " + want + ", llegó \"" + lastLine + "\"");
    }

    /** "227 Entering Passive Mode (h1,h2,h3,h4,p1,p2)". Se usa el host del control (evita NAT mal configurados). */
    InetSocketAddress parsePasv(String line) throws IOException {
        int a = line.indexOf('('), b = line.indexOf(')', a);
        String[] n = (a >= 0 && b > a ? line.substring(a + 1, b) : line.replaceAll("^.*?(\\d+,\\d+,\\d+,\\d+,\\d+,\\d+).*$", "$1")).split(",");
        if (n.length != 6) throw new IOException("PASV ilegible: " + line);
        int p = Integer.parseInt(n[4].trim()) * 256 + Integer.parseInt(n[5].trim());
        return new InetSocketAddress(host, p);
    }
}
