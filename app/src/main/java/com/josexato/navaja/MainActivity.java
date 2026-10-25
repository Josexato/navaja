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
import com.josexato.navaja.cellular.CellularActivity;
import com.josexato.navaja.compass.CompassActivity;
import com.josexato.navaja.lan.LanActivity;
import com.josexato.navaja.marker.MarkerActivity;
import com.josexato.navaja.pantonera.PantoneraActivity;
import com.josexato.navaja.pulse.PulseActivity;
import com.josexato.navaja.sign.SignActivity;
import com.josexato.navaja.tuner.TunerActivity;

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

    /**
     * ¿Es una petición del modelo escaneado? Se compara host y ruta, no la URL
     * entera: el visor agrega "?t=<hora>" para saltarse la caché.
     */
    static boolean isScanUrl(String url) {
        try {
            java.net.URI u = new java.net.URI(url);
            return "navaja.local".equals(u.getHost()) && "/scan/modelo.obj".equals(u.getPath());
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }
    private WebView webView;
    private ValueCallback<Uri[]> pendingFileCallback;
    private File scanModel;

    /** Puente JS: los botones nativos de index.html (Escanear 3D, Afinador) llaman aquí. */
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

        @JavascriptInterface
        public void openTuner() {
            runOnUiThread(() -> startActivity(new Intent(MainActivity.this, TunerActivity.class)));
        }

        @JavascriptInterface
        public void openLan() {
            runOnUiThread(() -> startActivity(new Intent(MainActivity.this, LanActivity.class)));
        }

        @JavascriptInterface
        public void openCellular() {
            runOnUiThread(() -> startActivity(new Intent(MainActivity.this, CellularActivity.class)));
        }

        @JavascriptInterface
        public void openCompass() {
            runOnUiThread(() -> startActivity(new Intent(MainActivity.this, CompassActivity.class)));
        }

        @JavascriptInterface
        public void openPulse() {
            runOnUiThread(() -> startActivity(new Intent(MainActivity.this, PulseActivity.class)));
        }

        @JavascriptInterface
        public void openMarker() {
            runOnUiThread(() -> startActivity(new Intent(MainActivity.this, MarkerActivity.class)));
        }

        @JavascriptInterface
        public void openSign() {
            runOnUiThread(() -> startActivity(new Intent(MainActivity.this, SignActivity.class)));
        }

        @JavascriptInterface
        public void openPantonera() {
            runOnUiThread(() -> startActivity(new Intent(MainActivity.this, PantoneraActivity.class)));
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
                if (req.getUrl() == null || !isScanUrl(req.getUrl().toString())) return null;
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

    /**
     * Atrás: si se está viendo un archivo, vuelve a la pantalla de inicio de
     * index.html (window.navajaBack devuelve true); si ya está en el inicio, sale.
     *
     * <p>Revisado, no es un error: con targetSdk 34 y sin
     * {@code android:enableOnBackInvokedCallback="true"} en el manifiesto,
     * Android sigue llamando a onBackPressed() también en API 33/34. Registrar
     * un OnBackInvokedCallback sin activar esa opción no tiene efecto, y
     * activarla exige decidir el retroceso ANTES del gesto, mientras que aquí la
     * respuesta llega de forma asíncrona desde el JavaScript del WebView. Migrar
     * cuando una versión de targetSdk lo exija.
     */
    @Override
    @SuppressWarnings("deprecation") // onBackPressed sigue siendo la vía simple hasta API 33 sin androidx.activity
    public void onBackPressed() {
        webView.evaluateJavascript("(window.navajaBack && window.navajaBack()) === true", value -> {
            if (!"true".equals(value)) MainActivity.super.onBackPressed();
        });
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
