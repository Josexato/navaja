package com.josexato.navaja;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.josexato.navaja.scan3d.ScanActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collections;

/**
 * Envoltorio mínimo: toda la app vive en assets/index.html (three.js local).
 * Lo nativo es el selector de archivos que el WebView no trae solo y el
 * escáner 3D por fotos (Camera2), cuyo modelo se muestra en el visor web.
 */
public class MainActivity extends Activity {

    private static final int REQ_FILE_CHOOSER = 1;
    private static final int REQ_SCAN = 2;
    /** URL virtual (servida por shouldInterceptRequest) del último modelo escaneado. */
    private static final String SCAN_URL = "https://navaja.local/scan/modelo.obj";
    private WebView webView;
    private ValueCallback<Uri[]> pendingFileCallback;
    private File scanModel;

    /** Puente JS: el botón "Escanear 3D" de index.html llama a NavajaNative.openScanner(). */
    private final class NativeBridge {
        @JavascriptInterface
        public void openScanner() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT < 30) {
                    Toast.makeText(MainActivity.this, "El escáner 3D requiere Android 11 o superior",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                startActivityForResult(new Intent(MainActivity.this, ScanActivity.class), REQ_SCAN);
            });
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);

        webView.addJavascriptInterface(new NativeBridge(), "NavajaNative");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                if (!SCAN_URL.equals(req.getUrl().toString())) return null;
                try {
                    if (scanModel == null) throw new IOException("sin modelo");
                    WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8",
                            new FileInputStream(scanModel));
                    // La página es file://: el fetch cruzado necesita CORS.
                    r.setResponseHeaders(Collections.singletonMap("Access-Control-Allow-Origin", "*"));
                    return r;
                } catch (IOException e) {
                    return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                            Collections.singletonMap("Access-Control-Allow-Origin", "*"), null);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (pendingFileCallback != null) {
                    pendingFileCallback.onReceiveValue(null);
                }
                pendingFileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                // Los gestores de archivos no siempre conocen los MIME de STL/OBJ,
                // así que se acepta todo y el visor valida por extensión.
                intent.setType("*/*");
                try {
                    startActivityForResult(
                            Intent.createChooser(intent, "Elige un archivo STL u OBJ"),
                            REQ_FILE_CHOOSER);
                } catch (android.content.ActivityNotFoundException e) {
                    pendingFileCallback = null;
                    callback.onReceiveValue(null);
                    return false;
                }
                return true;
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_SCAN) {
            String path = data == null ? null : data.getStringExtra(ScanActivity.EXTRA_MODEL);
            if (resultCode == RESULT_OK && path != null) {
                scanModel = new File(path);
                String label = "escaneo " + scanModel.getParentFile().getName().replace("scan_", "");
                webView.evaluateJavascript("window.navajaLoadScan && window.navajaLoadScan('"
                        + SCAN_URL + "?t=" + System.currentTimeMillis() + "', '" + label + "')", null);
            }
            return;
        }
        if (requestCode == REQ_FILE_CHOOSER && pendingFileCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
            pendingFileCallback.onReceiveValue(result);
            pendingFileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }
}
