package com.fangjot.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import java.util.ArrayList;
import java.util.List;

/**
 * Foreground service that keeps speech recognition running while the screen is off
 * or another app is in front. Android's recognizer stops after each pause, so the
 * service restarts it in a loop and queues every final phrase until the web UI drains it.
 */
public class RecorderService extends Service {
    public static final String ACTION_START = "com.fangjot.app.START";
    public static final String ACTION_STOP = "com.fangjot.app.STOP";
    private static final String CHANNEL = "fangjot_recording";
    private static final int NOTIF_ID = 4127;

    /** One recognised phrase with the wall-clock time it ended. */
    public static class Phrase {
        public final String text;
        public final long at;
        Phrase(String text, long at) { this.text = text; this.at = at; }
    }

    private static final Object LOCK = new Object();
    private static final List<Phrase> queue = new ArrayList<>();
    private static String partial = "";
    private static volatile boolean running = false;
    private static volatile long startedAt = 0;
    private static volatile String lastError = "";
    /** Called (on the main thread) whenever there is something new for the UI. */
    public static volatile Runnable onChange;

    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private PowerManager.WakeLock wakeLock;
    private String language = "th-TH";
    private int errorStreak = 0;
    private long phraseStart = 0;

    public static boolean isRunning() { return running; }
    public static long getStartedAt() { return startedAt; }

    /** Returns queued phrases and clears the queue. */
    public static List<Phrase> drain() {
        synchronized (LOCK) {
            List<Phrase> out = new ArrayList<>(queue);
            queue.clear();
            return out;
        }
    }

    public static String getPartial() { synchronized (LOCK) { return partial; } }
    public static String takeError() { String e = lastError; lastError = ""; return e; }

    private static void ping() {
        Runnable r = onChange;
        if (r != null) r.run();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (intent != null && intent.getStringExtra("language") != null) {
            language = intent.getStringExtra("language");
        }
        if (!running) {
            running = true;
            startedAt = System.currentTimeMillis();
            errorStreak = 0;
            goForeground();
            acquireWakeLock();
            listen();
        } else {
            // Already recording (e.g. language changed): refresh foreground state and restart the recognizer.
            goForeground();
            restartRecognizer(150);
        }
        ping();
        return START_NOT_STICKY;
    }

    private void goForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "การบันทึกประชุม", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("แสดงขณะฟังจดกำลังฟังและถอดเสียง");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, piFlags);
        Intent stop = new Intent(this, RecorderService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, piFlags);
        Notification n = new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_fangjot)
            .setContentTitle("ฟังจดกำลังบันทึกประชุม")
            .setContentText("ถอดเสียงต่อแม้ปิดจอหรือสลับแอป")
            .setWhen(startedAt)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openPi)
            .addAction(0, "หยุดบันทึก", stopPi)
            .build();
        int type = Build.VERSION.SDK_INT >= 30 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0;
        ServiceCompat.startForeground(this, NOTIF_ID, n, type);
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fangjot:recording");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(6 * 60 * 60 * 1000L); // safety cap: 6 hours
    }

    private Intent recognizerIntent() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000L);
        return i;
    }

    private void listen() {
        if (!running) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            lastError = "no_service";
            shutdown();
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(listener);
        }
        phraseStart = System.currentTimeMillis();
        try {
            recognizer.startListening(recognizerIntent());
        } catch (Exception e) {
            restartRecognizer(800);
        }
    }

    private void restartRecognizer(long delay) {
        main.removeCallbacksAndMessages(null);
        if (recognizer != null) {
            try { recognizer.cancel(); recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
        main.postDelayed(this::listen, delay);
    }

    private void commit(String text) {
        if (text == null) return;
        text = text.trim();
        if (text.isEmpty()) return;
        synchronized (LOCK) {
            queue.add(new Phrase(text, System.currentTimeMillis()));
            partial = "";
        }
        ping();
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rmsdB) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int eventType, Bundle params) { }

        @Override
        public void onPartialResults(Bundle b) {
            ArrayList<String> m = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (m != null && !m.isEmpty() && m.get(0) != null && !m.get(0).trim().isEmpty()) {
                synchronized (LOCK) { partial = m.get(0); }
                ping();
            }
        }

        @Override
        public void onResults(Bundle b) {
            ArrayList<String> m = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            String text = (m != null && !m.isEmpty()) ? m.get(0) : null;
            if (text == null || text.trim().isEmpty()) text = getPartial();
            commit(text);
            errorStreak = 0;
            if (running) main.postDelayed(RecorderService.this::listen, 60);
        }

        @Override
        public void onError(int error) {
            // Keep whatever was heard before the error.
            commit(getPartial());
            if (!running) return;
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                lastError = "permission";
                shutdown();
                return;
            }
            errorStreak++;
            long delay;
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    delay = 60; break;              // just silence: listen again right away
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                case SpeechRecognizer.ERROR_SERVER:
                    lastError = "network";
                    delay = Math.min(10000, 1000L * errorStreak); break;
                default:                            // busy / client: rebuild the recognizer
                    restartRecognizer(Math.min(3000, 300L * errorStreak));
                    return;
            }
            main.postDelayed(RecorderService.this::listen, delay);
        }
    };

    private void shutdown() {
        running = false;
        main.removeCallbacksAndMessages(null);
        if (recognizer != null) {
            try { recognizer.stopListening(); recognizer.cancel(); recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
        commit(getPartial());
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
        ping();
    }

    @Override
    public void onDestroy() {
        if (running) shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
