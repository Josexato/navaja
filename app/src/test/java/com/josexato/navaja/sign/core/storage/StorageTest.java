package com.josexato.navaja.sign.core.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

public class StorageTest {

    /** Ejemplo "GET Object" de la documentación de AWS Signature V4 para S3. */
    @Test
    public void firmaSigV4IgualAlEjemploDeAws() {
        Map<String, String> h = new TreeMap<>();
        h.put("host", "examplebucket.s3.amazonaws.com");
        h.put("range", "bytes=0-9");
        h.put("x-amz-content-sha256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        h.put("x-amz-date", "20130524T000000Z");
        String auth = S3.authorization("GET", "/test.txt", "", h, h.get("x-amz-content-sha256"), "us-east-1",
                "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");
        assertEquals("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,"
                + "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date,"
                + "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41", auth);
        assertEquals("a%20b/c%C3%B1", S3.encode("a b/cñ", true));
    }

    @Test
    public void s3YWebDavSubenConPut() throws Exception {
        Map<String, byte[]> got = new ConcurrentHashMap<>();
        Map<String, String> auth = new ConcurrentHashMap<>();
        ServerSocket srv = new ServerSocket(0);
        // Servidor HTTP de juguete (com.sun.net.httpserver no está en el classpath de Android).
        Thread t = new Thread(() -> {
            while (!srv.isClosed()) {
                try (Socket s = srv.accept()) {
                    InputStream in = s.getInputStream();
                    String req = line(in), path = req.split(" ")[1];
                    int len = 0;
                    for (String h; !(h = line(in)).isEmpty(); ) {
                        String k = h.substring(0, h.indexOf(':')).trim().toLowerCase(), v = h.substring(h.indexOf(':') + 1).trim();
                        if (k.equals("content-length")) len = Integer.parseInt(v);
                        if (k.equals("authorization")) auth.put(path, v);
                    }
                    byte[] body = new byte[len];
                    for (int off = 0; off < len; ) off += in.read(body, off, len - off);
                    got.put(req.split(" ")[0] + " " + path, body);
                    s.getOutputStream().write("HTTP/1.1 201 Created\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                } catch (Exception e) {
                    return;
                }
            }
        });
        t.start();
        try {
            String base = "http://127.0.0.1:" + srv.getLocalPort();
            byte[] data = "{\"seq\":1}\n".getBytes(StandardCharsets.UTF_8);
            new S3(base, "sa-peru-1", "mis-firmas", "navaja", "AK", "SK", true).put("firmas/000001.json", data, "application/json");
            assertArrayEquals(data, got.get("PUT /mis-firmas/navaja/firmas/000001.json"));
            assertTrue(auth.get("/mis-firmas/navaja/firmas/000001.json").startsWith("AWS4-HMAC-SHA256 Credential=AK/"));
            new WebDav(base + "/dav/", "jose", "clave").put("firmas/000001.json", data, "application/json");
            assertArrayEquals(data, got.get("PUT /dav/firmas_000001.json"));
            assertEquals("Basic am9zZTpjbGF2ZQ==", auth.get("/dav/firmas_000001.json"));
        } finally {
            srv.close();
        }
    }

    private static String line(InputStream in) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        for (int c; (c = in.read()) >= 0 && c != '\n'; ) if (c != '\r') sb.append((char) c);
        return sb.toString();
    }

    /** Servidor FTP de juguete: lo justo para USER/PASS/TYPE/MKD/PASV/STOR. */
    @Test
    public void ftpPasivoSubeElArchivo() throws Exception {
        ServerSocket ctl = new ServerSocket(0);
        Map<String, byte[]> files = new ConcurrentHashMap<>();
        Thread t = new Thread(() -> {
            try (Socket s = ctl.accept()) {
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter out = new PrintWriter(s.getOutputStream(), true);
                out.print("220-Bienvenido\r\n220 listo\r\n");
                out.flush();
                ServerSocket data = null;
                String line;
                while ((line = in.readLine()) != null) {
                    String cmd = line.split(" ")[0];
                    switch (cmd) {
                        case "USER": out.print("331 clave\r\n"); break;
                        case "PASS": out.print("230 ok\r\n"); break;
                        case "TYPE": out.print("200 binario\r\n"); break;
                        case "MKD": out.print("550 ya existe\r\n"); break;
                        case "PASV": {
                            data = new ServerSocket(0);
                            int p = data.getLocalPort();
                            out.print("227 Entering Passive Mode (127,0,0,1," + (p >> 8) + "," + (p & 255) + ")\r\n");
                            break;
                        }
                        case "STOR": {
                            out.print("150 adelante\r\n");
                            out.flush();
                            try (Socket d = data.accept()) {
                                files.put(line.substring(5), readAll(d.getInputStream()));
                            }
                            data.close();
                            out.print("226 listo\r\n");
                            break;
                        }
                        case "QUIT": out.print("221 chao\r\n"); out.flush(); return;
                        default: out.print("502 no\r\n");
                    }
                    out.flush();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        t.start();
        byte[] data = "historial".getBytes(StandardCharsets.UTF_8);
        new Ftp("127.0.0.1", ctl.getLocalPort(), "jose", "x", "respaldos", false).put("firmas/000001.json", data, "application/json");
        t.join(5000);
        ctl.close();
        assertArrayEquals(data, files.get("respaldos/firmas/000001.json"));
    }

    static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        return o.toByteArray();
    }
}
