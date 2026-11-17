package com.josexato.navaja.measure;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;

import com.josexato.navaja.measure.core.Sheet;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * PDF de la hoja del modo "Hoja" (A4, tamaño real): las marcas de
 * {@link Sheet}, el contorno de la zona libre para el objeto y una barra de
 * 100 mm para comprobar que se imprimió al 100 %.
 */
final class SheetPdf {

    private SheetPdf() { }

    private static final float PT_PER_MM = 72f / 25.4f;

    static void write(File out) throws IOException {
        int pw = 595, ph = 842; // A4 en puntos
        PdfDocument doc = new PdfDocument();
        try {
            PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder(pw, ph, 1).create());
            draw(page.getCanvas());
            doc.finishPage(page);
            try (FileOutputStream fo = new FileOutputStream(out)) {
                doc.writeTo(fo);
            }
        } finally {
            doc.close();
        }
    }

    private static float mm(double v) { return (float) (v * PT_PER_MM); }

    private static void draw(Canvas c) {
        c.drawColor(Color.WHITE);
        Paint black = new Paint();
        black.setColor(Color.BLACK);
        Paint white = new Paint();
        white.setColor(Color.WHITE);
        // Marcas: cuadrado negro y las celdas blancas de su código (página en mm, y hacia abajo)
        for (int id = 0; id < Sheet.count(); id++) {
            double[][] k = Sheet.corners(id); // hoja: Y hacia arriba
            double x0 = k[0][0] + Sheet.PAGE_W / 2, y0 = Sheet.PAGE_H / 2 - k[0][1];
            c.drawRect(mm(x0), mm(y0), mm(x0 + Sheet.SIDE), mm(y0 + Sheet.SIDE), black);
            for (int r = 0; r < 4; r++)
                for (int col = 0; col < 4; col++)
                    if (!Sheet.bit(Sheet.CODES[id], r, col)) {
                        double cx = x0 + (col + 1) * Sheet.CELL, cy = y0 + (r + 1) * Sheet.CELL;
                        c.drawRect(mm(cx), mm(cy), mm(cx + Sheet.CELL), mm(cy + Sheet.CELL), white);
                    }
        }
        Paint thin = new Paint(Paint.ANTI_ALIAS_FLAG);
        thin.setColor(Color.rgb(190, 190, 190));
        thin.setStyle(Paint.Style.STROKE);
        thin.setStrokeWidth(mm(0.3));
        double[] fa = Sheet.freeArea();
        c.drawRect(mm(fa[0] + Sheet.PAGE_W / 2), mm(Sheet.PAGE_H / 2 - fa[3]), mm(fa[1] + Sheet.PAGE_W / 2), mm(Sheet.PAGE_H / 2 - fa[2]), thin);
        float cx = mm(Sheet.PAGE_W / 2), cy = mm(Sheet.PAGE_H / 2);
        c.drawLine(cx - mm(6), cy, cx + mm(6), cy, thin);
        c.drawLine(cx, cy - mm(6), cx, cy + mm(6), thin);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.rgb(130, 130, 130));
        text.setTextSize(mm(3));
        text.setTextAlign(Paint.Align.CENTER);
        float top = mm(Sheet.PAGE_H / 2 - fa[3]);
        c.drawText("Navaja · hoja para escanear (A4) · el objeto va dentro del recuadro", cx, top + mm(6), text);
        // Barra de 100 mm al pie de la zona libre: mídela con una regla
        Paint bar = new Paint();
        bar.setColor(Color.rgb(100, 100, 100));
        float by = mm(Sheet.PAGE_H / 2 - fa[2]) - mm(10);
        c.drawRect(cx - mm(50), by - mm(0.5), cx + mm(50), by + mm(0.5), bar);
        c.drawRect(cx - mm(50), by - mm(2), cx - mm(49.5), by + mm(2), bar);
        c.drawRect(cx + mm(49.5), by - mm(2), cx + mm(50), by + mm(2), bar);
        c.drawText("100 mm · imprime al 100 % (tamaño real, sin «ajustar a la página»)", cx, by - mm(4), text);
    }
}
