package com.josexato.navaja.sign.core.storage;

import com.josexato.navaja.sign.core.Bytes;
import com.josexato.navaja.sign.core.Http;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Almacenamiento de objetos compatible con S3 y firma AWS Signature V4:
 * Amazon S3, Huawei Cloud OBS, MinIO, Cloudflare R2, Backblaze B2, Wasabi,
 * Ceph… Sólo hace PUT de objetos.
 */
public final class S3 implements Backend {

    private final String endpoint, region, bucket, prefix, accessKey, secretKey;
    private final boolean pathStyle;

    /**
     * @param endpoint  p. ej. https://s3.us-east-1.amazonaws.com, https://obs.sa-peru-1.myhuaweicloud.com, https://minio.local:9000
     * @param pathStyle true: endpoint/bucket/objeto (MinIO); false: bucket.endpoint/objeto (AWS, OBS)
     */
    public S3(String endpoint, String region, String bucket, String prefix, String accessKey, String secretKey, boolean pathStyle) {
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.region = region;
        this.bucket = bucket;
        this.prefix = prefix == null ? "" : prefix.replaceAll("^/+|/+$", "");
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.pathStyle = pathStyle;
    }

    @Override public String describe() { return "S3 " + bucket + " @ " + endpoint; }

    @Override
    public void put(String path, byte[] data, String contentType) throws IOException {
        URI ep = URI.create(endpoint);
        String host = pathStyle ? ep.getRawAuthority() : bucket + "." + ep.getRawAuthority();
        String key = prefix.isEmpty() ? path : prefix + "/" + path;
        String canonicalPath = (pathStyle ? "/" + encode(bucket, false) : "") + "/" + encode(key, true);
        String url = ep.getScheme() + "://" + host + canonicalPath;
        String payload = Bytes.hex(Bytes.sha256(data));
        Map<String, String> h = new TreeMap<>();
        h.put("host", host);
        h.put("content-type", contentType);
        h.put("x-amz-content-sha256", payload);
        h.put("x-amz-date", amzDate(new Date()));
        h.put("authorization", authorization("PUT", canonicalPath, "", h, payload, region, accessKey, secretKey));
        h.remove("host"); // lo pone HttpURLConnection
        Http.Response r = Http.request("PUT", url, h, data);
        if (!r.ok()) throw new IOException("S3 respondió HTTP " + r.code + ": " + abbreviate(r.text()));
    }

    private static String abbreviate(String s) { return s.length() > 200 ? s.substring(0, 200) + "…" : s; }

    static String amzDate(Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(d);
    }

    /** Cabecera Authorization SigV4. {@code headers} en minúsculas (se firman todas); debe incluir x-amz-date. */
    static String authorization(String method, String path, String query, Map<String, String> headers, String payloadHash,
                                String region, String accessKey, String secretKey) {
        Map<String, String> sorted = new TreeMap<>(headers);
        StringBuilder canonHeaders = new StringBuilder(), signed = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            canonHeaders.append(e.getKey()).append(':').append(e.getValue().trim()).append('\n');
            if (signed.length() > 0) signed.append(';');
            signed.append(e.getKey());
        }
        String canonical = method + "\n" + path + "\n" + query + "\n" + canonHeaders + "\n" + signed + "\n" + payloadHash;
        String amzDate = sorted.get("x-amz-date"), day = amzDate.substring(0, 8);
        String scope = day + "/" + region + "/s3/aws4_request";
        String toSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + Bytes.hex(Bytes.sha256(canonical.getBytes(StandardCharsets.UTF_8)));
        byte[] k = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), day);
        k = hmac(k, region);
        k = hmac(k, "s3");
        k = hmac(k, "aws4_request");
        String sig = Bytes.hex(hmac(k, toSign));
        return "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope + ",SignedHeaders=" + signed + ",Signature=" + sig;
    }

    static byte[] hmac(byte[] key, String msg) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(msg.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Codificación URI de AWS (RFC 3986, sin codificar "/" si {@code keepSlash}). */
    static String encode(String s, boolean keepSlash) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~'
                    || (keepSlash && c == '/')) sb.append((char) c);
            else sb.append(String.format(Locale.US, "%%%02X", c));
        }
        return sb.toString();
    }
}
