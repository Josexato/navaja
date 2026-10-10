package com.josexato.navaja.tuner;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.josexato.navaja.tuner.core.BandFilter;
import com.josexato.navaja.tuner.core.PitchDetector;
import com.josexato.navaja.tuner.core.PitchTracker;
import com.josexato.navaja.tuner.core.Tunings;

import java.util.Locale;

/**
 * Afinador de guitarra y violín: escucha por el micrófono, detecta la
 * frecuencia (YIN) y muestra la cuerda más cercana (o la elegida) con la
 * desviación en cents. Incluye tono de referencia por cuerda y La4 ajustable.
 */
public class TunerActivity extends Activity {

    private static final int REQ_MIC = 1;

    private Tunings.Instrument instrument = Tunings.GUITAR;
    private double a4 = 440;
    private int lockedString = -1;   // -1 = detección automática de la cuerda
    private PitchTracker.Response response = PitchTracker.Response.NORMAL;
    private double shownCents = Double.NaN;
    private int shownString = -1;
    private Button responseBtn;

    private TextView noteView, centsView, freqView, hintView, refView;
    private GaugeView gauge;
    private LinearLayout instrumentRow, stringRow;
    private Button toneBtn;

    private Thread audioThread;
    private volatile boolean running;
    private AudioTrack tone;
    private int toneString = -1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setBackgroundColor(0xFF15171A);

        TextView title = text("Afinador", 22, 0xFFFFFFFF);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        instrumentRow = row();
        root.addView(scroll(instrumentRow));

        noteView = text("—", 72, 0xFFFFFFFF);
        noteView.setGravity(Gravity.CENTER);
        noteView.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(noteView, new LinearLayout.LayoutParams(-1, -2));

        gauge = new GaugeView(this);
        root.addView(gauge, new LinearLayout.LayoutParams(-1, -2));

        centsView = text("", 26, 0xFFFFFFFF);
        centsView.setGravity(Gravity.CENTER);
        root.addView(centsView, new LinearLayout.LayoutParams(-1, -2));
        freqView = text("", 15, 0xAAFFFFFF);
        freqView.setGravity(Gravity.CENTER);
        root.addView(freqView, new LinearLayout.LayoutParams(-1, -2));

        TextView sl = text("Cuerda (toca una para fijarla; otra vez = automático)", 13, 0xAAFFFFFF);
        sl.setPadding(0, dp(12), 0, dp(4));
        root.addView(sl);
        stringRow = row();
        root.addView(scroll(stringRow));

        LinearLayout refRow = row();
        refRow.setGravity(Gravity.CENTER_VERTICAL);
        refRow.addView(button("−", v -> setA4(a4 - 1)));
        refView = text("", 15, 0xFFFFFFFF);
        refView.setPadding(dp(8), 0, dp(8), 0);
        refRow.addView(refView);
        refRow.addView(button("+", v -> setA4(a4 + 1)));
        refRow.addView(button("440", v -> setA4(440)));
        toneBtn = button("♪ Tono de referencia", v -> toggleTone());
        refRow.addView(toneBtn);
        responseBtn = button("", v -> {
            PitchTracker.Response[] all = PitchTracker.Response.values();
            response = all[(response.ordinal() + 1) % all.length];
            updateResponseLabel();
            restartAudio();
        });
        refRow.addView(responseBtn);
        updateResponseLabel();
        root.addView(scroll(refRow));

        hintView = text("", 13, 0x99FFFFFF);
        hintView.setPadding(0, dp(12), 0, 0);
        root.addView(hintView);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(0xFF15171A);
        sv.addView(root);
        setContentView(sv);
        rebuildRows();
        setA4(440);
    }

    private void rebuildRows() {
        instrumentRow.removeAllViews();
        for (Tunings.Instrument ins : Tunings.ALL) {
            Button bt = button(ins.name, v -> {
                instrument = ins;
                lockedString = -1;
                stopTone();
                rebuildRows();
                restartAudio();
            });
            styleToggle(bt, ins == instrument);
            instrumentRow.addView(bt);
        }
        stringRow.removeAllViews();
        for (int i = 0; i < instrument.strings.length; i++) {
            final int idx = i;
            int m = instrument.strings[i];
            Button bt = button(instrument.labels[i] + "\n" + Tunings.name(m), v -> {
                lockedString = lockedString == idx ? -1 : idx;
                if (tone != null) startTone(currentToneString());
                rebuildRows();
                restartAudio(); // el filtro se estrecha alrededor de la cuerda fija
            });
            styleToggle(bt, i == lockedString);
            stringRow.addView(bt);
        }
        hintView.setText(instrument == Tunings.VIOLIN
                ? "Violín: G3 · D4 · A4 · E5. Afina primero el La (II) y luego las demás. "
                + "Con arco suave o pizzicato; acerca el teléfono al instrumento."
                : "Guitarra: E2 · A2 · D3 · G3 · B3 · E4 (Drop D: la 6ª en D2). "
                + "Pulsa una cuerda a la vez y deja que suene; el afinador reconoce la cuerda.");
    }

    private void updateResponseLabel() {
        String[] names = {"Rápida", "Normal", "Estable"};
        responseBtn.setText("Respuesta: " + names[response.ordinal()]);
    }

    private void setA4(double v) {
        a4 = Math.max(415, Math.min(466, v));
        restartAudio();
        refView.setText(String.format(Locale.US, "La4 = %.0f Hz", a4));
        if (tone != null) startTone(currentToneString());
    }

    // ------------------------------------------------------------ micrófono

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        startAudio();
    }

    @Override
    protected void onPause() {
        stopAudio();
        stopTone();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (code == REQ_MIC && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) startAudio();
        else noteView.setText("Sin micrófono");
    }

    private void restartAudio() {
        if (!running) return;
        stopAudio();
        startAudio();
    }

    private void startAudio() {
        if (running) return;
        running = true;
        final Tunings.Instrument ins = instrument;
        final int locked = lockedString;
        final double ref = a4;
        final PitchTracker.Response resp = response;
        audioThread = new Thread(() -> audioLoop(ins, locked, ref, resp), "afinador");
        audioThread.start();
    }

    private void stopAudio() {
        running = false;
        if (audioThread != null) {
            try {
                audioThread.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        audioThread = null;
    }

    @SuppressWarnings("MissingPermission") // sólo se llama con el permiso concedido
    private void audioLoop(Tunings.Instrument ins, int locked, double ref, PitchTracker.Response resp) {
        int sr = 48000;
        int minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            sr = 44100;
            minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        }
        // UNPROCESSED evita el control de ganancia y la supresión de ruido, que deforman la nota.
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int source = "true".equals(am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED))
                ? MediaRecorder.AudioSource.UNPROCESSED : MediaRecorder.AudioSource.VOICE_RECOGNITION;
        PitchDetector det = new PitchDetector(sr, 2048, ins.minFreq, ins.maxFreq);
        // Preferencia por las cuerdas (o sólo la fija) y filtro pasabanda acorde.
        double[] targets;
        BandFilter filter;
        if (locked >= 0) {
            targets = new double[]{Tunings.freq(ins.strings[locked], ref)};
            filter = BandFilter.forString(sr, targets[0]);
        } else {
            targets = new double[ins.strings.length];
            for (int i = 0; i < targets.length; i++) targets[i] = Tunings.freq(ins.strings[i], ref);
            filter = BandFilter.forInstrument(sr, ins, ref);
        }
        det.setTargets(targets);
        int need = det.bufferSize();
        AudioRecord rec;
        try {
            rec = new AudioRecord(source, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuf, need * 4));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("AudioRecord");
            rec.startRecording();
        } catch (Exception e) {
            runOnUiThread(() -> noteView.setText("Micrófono no disponible"));
            running = false;
            return;
        }
        float[] win = new float[need];
        short[] hop = new short[1024];
        PitchTracker tracker = new PitchTracker(resp, 600);
        int filled = 0;
        long lastUi = 0;
        try {
            while (running) {
                int n = rec.read(hop, 0, hop.length);
                if (n <= 0) continue;
                // Ventana deslizante: se desplaza y se añaden las muestras nuevas.
                int keep = Math.min(filled, need - n);
                System.arraycopy(win, filled - keep, win, 0, keep);
                for (int i = 0; i < n; i++) win[keep + i] = filter.process(hop[i] / 32768f);
                filled = keep + n;
                if (filled < need) continue;
                PitchDetector.Result r = det.detect(win);
                long now = SystemClock.elapsedRealtime();
                double f = tracker.push(r.pitched() && r.clarity > 0.7 ? r.freq : Double.NaN, now);
                if (now - lastUi >= 50) {
                    lastUi = now;
                    final double ff = f;
                    runOnUiThread(() -> show(ff));
                }
            }
        } finally {
            rec.stop();
            rec.release();
        }
    }

    private void show(double f) {
        if (Double.isNaN(f)) {
            shownCents = Double.NaN;
            gauge.setCents(Double.NaN);
            centsView.setText("Toca una cuerda");
            centsView.setTextColor(0xAAFFFFFF);
            freqView.setText("");
            return;
        }
        int s = lockedString >= 0 ? lockedString : Tunings.nearestString(instrument, f, a4);
        int target = instrument.strings[s];
        double raw = Tunings.cents(f, target, a4);
        // Suavizado exponencial de los cents mientras se afina la misma cuerda.
        shownCents = s != shownString || Double.isNaN(shownCents) ? raw : shownCents + 0.3 * (raw - shownCents);
        shownString = s;
        double c = shownCents;
        noteView.setText(instrument.labels[s] + " · " + Tunings.name(target));
        noteView.setTextColor(GaugeView.colorFor(c));
        gauge.setCents(c);
        String dir = Math.abs(c) <= GaugeView.IN_TUNE_CENTS ? "✓ afinada"
                : c < 0 ? "baja: tensa la cuerda ↑" : "alta: afloja la cuerda ↓";
        centsView.setText(String.format(Locale.US, "%+.0f cents · %s", c, dir));
        centsView.setTextColor(GaugeView.colorFor(c));
        int near = Tunings.nearestMidi(f, a4);
        freqView.setText(String.format(Locale.US, "%.2f Hz (objetivo %.2f Hz) · nota oída: %s",
                f, Tunings.freq(target, a4), Tunings.name(near)));
        if (lockedString < 0) highlightString(s);
    }

    private void highlightString(int s) {
        for (int i = 0; i < stringRow.getChildCount(); i++)
            styleToggle((Button) stringRow.getChildAt(i), i == s);
    }

    // ------------------------------------------------------ tono de referencia

    private int currentToneString() {
        if (lockedString >= 0) return lockedString;
        return toneString >= 0 && toneString < instrument.strings.length ? toneString : instrument.strings.length - 1;
    }

    private void toggleTone() {
        if (tone != null) stopTone();
        else startTone(currentToneString());
    }

    /** Seno puro en bucle a la frecuencia de la cuerda (útil para afinar de oído). */
    private void startTone(int s) {
        stopTone();
        toneString = s;
        int sr = 48000;
        double f = Tunings.freq(instrument.strings[s], a4);
        // Un número entero de períodos para que el bucle no haga clic.
        int periods = Math.max(1, (int) Math.round(f));
        int n = (int) Math.round(periods * sr / f);
        short[] buf = new short[n];
        for (int i = 0; i < n; i++) buf[i] = (short) (Math.sin(2 * Math.PI * periods * i / n) * 9000);
        tone = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(n * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();
        tone.write(buf, 0, n);
        tone.setLoopPoints(0, n, -1);
        tone.play();
        toneBtn.setText(String.format(Locale.US, "■ %s %.1f Hz", Tunings.name(instrument.strings[s]), f));
    }

    private void stopTone() {
        if (tone != null) {
            try {
                tone.stop();
            } catch (IllegalStateException ignored) {
            }
            tone.release();
            tone = null;
        }
        if (toneBtn != null) toneBtn.setText("♪ Tono de referencia");
    }

    // ------------------------------------------------------------- utilidades

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private HorizontalScrollView scroll(View v) {
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.addView(v);
        return h;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private void styleToggle(Button b, boolean on) {
        b.setBackgroundColor(on ? 0xFF3F7DE0 : 0xFF2A2E33);
        b.setTextColor(Color.WHITE);
    }
}
