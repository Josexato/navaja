package com.josexato.navaja.scan3d;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.josexato.navaja.measure.MeasureActivity;
import com.josexato.navaja.measure.SheetActivity;
import com.josexato.navaja.ui.NavajaUi;

/**
 * Entrada única al escáner 3D: explica los tres modos de captura (cuándo
 * conviene cada uno, qué hace falta y qué se obtiene) y abre el elegido.
 * Guía completa en docs/ESCANER_3D.md.
 *
 * <p>El modo con base giratoria devuelve el modelo a la pantalla principal
 * (lo abre el visor 3D), así que su resultado se reenvía tal cual.
 */
public class Scan3dHubActivity extends Activity {

    private static final int REQ_TURNTABLE = 1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        NavajaUi.window(this);
        NavajaUi.Screen scr = NavajaUi.screen(this, "Escáner 3D", "Medir un objeto y sacar su modelo 3D: elige cómo capturarlo", null, true);
        LinearLayout c = scr.content;
        // Lo ya escaneado: lista con fecha, modo, versión, medidas y qué archivos tiene cada uno
        Button lib = NavajaUi.secondary(this, "Mis escaneos (lo que ya guardé)", v -> startActivity(new Intent(this, com.josexato.navaja.measure.LibraryActivity.class)));
        c.addView(NavajaUi.buttonRow(this, lib));

        mode(c, "1 · Video alrededor del objeto",
                "Grabas 10–30 s rodeando el objeto, apoyado en una mesa o colgado en una pared. No hay que imprimir nada.",
                "Lo más rápido. Da proporciones; para tener cm escribes una medida que conozcas (por ejemplo el alto).",
                "Nube de puntos, volumen (STL), modelos para Blender (OBJ con textura) e informe en un ZIP.",
                "Grabar o elegir video", v -> startActivity(new Intent(this, MeasureActivity.class)));

        mode(c, "2 · Video sobre la hoja con marcas (experimental)",
                "Imprimes una hoja A4 con marcas en el borde, pones el objeto en el centro y grabas rodeándolo.",
                "Todo sale en mm sin medida conocida. Suma puntos de IA (XFeat) a los del video: más puntos sobre el objeto.",
                "Lo mismo que el modo 1, en mm, más diagnóstico de la hoja y de XFeat.",
                "Usar la hoja", v -> startActivity(new Intent(this, SheetActivity.class)));

        mode(c, "3 · Fotos con base giratoria y trípode",
                "Imprimes un tapete y un disco giratorio, fijas el celular en un trípode y vas girando el objeto. Android 11 o superior.",
                "Lo más preciso y repetible para piezas chicas; puede usar varias cámaras del celular a la vez.",
                "Modelo en mm (OBJ, PLY, STL) que se abre en el visor 3D con medidas, volumen y peso PLA.",
                "Escanear con base giratoria", v -> turntable());

        LinearLayout t = NavajaUi.card(this);
        t.addView(NavajaUi.text(this, "¿Cuál elegir?", NavajaUi.Style.LABEL));
        t.addView(NavajaUi.text(this,
                "• Algo grande, fijo o en una pared → 1.\n"
                        + "• Medidas en mm sin regla, objeto de hasta ~11 × 19 cm de base → 2.\n"
                        + "• Pieza chica para reproducir o imprimir en 3D, con tiempo para armar → 3.\n"
                        + "• Ninguno funciona bien con objetos transparentes, espejados o totalmente lisos.",
                NavajaUi.Style.BODY));
        c.addView(t);
        c.addView(NavajaUi.text(this, "Todo se calcula en el teléfono, sin internet. Guía completa: docs/ESCANER_3D.md en el repositorio de Navaja.",
                NavajaUi.Style.MUTED));
        setContentView(scr.root);
    }

    private void mode(LinearLayout into, String title, String how, String why, String gives, String action, android.view.View.OnClickListener open) {
        LinearLayout k = NavajaUi.card(this);
        k.addView(NavajaUi.text(this, title, NavajaUi.Style.SUBTITLE));
        k.addView(NavajaUi.text(this, how, NavajaUi.Style.BODY));
        k.addView(NavajaUi.text(this, why, NavajaUi.Style.SECONDARY));
        k.addView(NavajaUi.text(this, "Obtienes: " + gives, NavajaUi.Style.SECONDARY));
        Button b = NavajaUi.primary(this, action, open);
        NavajaUi.topMargin(b, 8);
        k.addView(b, new LinearLayout.LayoutParams(-1, -2));
        into.addView(k);
    }

    private void turntable() {
        if (Build.VERSION.SDK_INT < 30) {
            Toast.makeText(this, "El escáner con base giratoria requiere Android 11 o superior", Toast.LENGTH_LONG).show();
            return;
        }
        startActivityForResult(new Intent(this, ScanActivity.class), REQ_TURNTABLE);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        // El modelo lo abre el visor de la pantalla principal: se devuelve y se cierra el selector
        if (req == REQ_TURNTABLE && res == RESULT_OK && data != null && data.getStringExtra(ScanActivity.EXTRA_MODEL) != null) {
            setResult(RESULT_OK, data);
            finish();
        }
    }
}
