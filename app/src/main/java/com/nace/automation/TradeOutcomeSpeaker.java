package com.nace.automation;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Speaks what the service tells it to: trade outcomes, the "online, waiting for the trade" check
 * before each window, spoken WARNINGs with the reason, and recovery. It does not decide when.
 * Everything plays on the alarm stream (not muted by silent mode) and raises the alarm volume
 * for the utterance when it is lower than the announcement needs.
 */
final class TradeOutcomeSpeaker {
    private static final String TAG = "TradeOutcomeSpeaker";
    private static final long PLAYBACK_TIMEOUT_MS = 20000;
    private static final AudioAttributes SUCCESS_AUDIO = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();
    /** Full volume for results and warnings; status updates use at least 70%. */
    private static final float FULL = 1f;
    private static final float STATUS = .7f;

    private static final class Announcement {
        final String text;
        final float volume;

        Announcement(String text, float volume) {
            this.text = text;
            this.volume = volume;
        }
    }
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Announcement> pending = new ArrayDeque<>();
    private TextToSpeech tts;
    private boolean ready;
    private boolean closed;
    private long utteranceId;
    private String activeUtteranceId;
    private float activeVolume;
    private int savedAlarmVolume = -1;
    private int forcedAlarmVolume = -1;

    private static final String VOLUME_PREFS = "speech_volume";
    private static final String SAVED_VOLUME = "saved_alarm_volume";
    private static final String FORCED_VOLUME = "forced_alarm_volume";

    TradeOutcomeSpeaker(Context context) {
        this.context = context.getApplicationContext();
        restoreVolumeAfterCrash();
        try {
            // Posting also handles an engine callback arriving before the constructor returns.
            tts = new TextToSpeech(this.context, status -> handler.post(() -> initialized(status)));
        } catch (RuntimeException error) {
            closed = true;
            Log.w(TAG, "Text-to-speech initialization failed", error);
        }
    }

    void speak(boolean successful) {
        enqueue(successful
                ? AppLanguage.text(context, "Trade successful.", "Tranzacție reușită.", "Operación completada.")
                : AppLanguage.text(context, "Trade failed.", "Tranzacție eșuată.", "La operación falló."), FULL);
    }

    /** About five minutes before a window, once everything has been verified. */
    void speakOnline() {
        enqueue(AppLanguage.text(context,
                "NACE automation online. Waiting for the trade.",
                "Automatizarea NACE este online. Se așteaptă tranzacția.",
                "Automatización de NACE en línea. Esperando la operación."), STATUS);
    }

    /** A new problem that can stop the trade; the reason is already localized. */
    void speakWarning(String reason) {
        enqueue(AppLanguage.text(context, "Warning! Warning! ", "Atenție! Atenție! ", "¡Atención! ¡Atención! ")
                + reason, FULL);
    }

    /** The warning that was spoken earlier has cleared and automation is ready again. */
    void speakRecovered() {
        enqueue(AppLanguage.text(context,
                "Problem solved. NACE automation is online again.",
                "Problema a fost rezolvată. Automatizarea NACE este din nou online.",
                "Problema resuelto. La automatización de NACE vuelve a estar en línea."), STATUS);
    }

    private void enqueue(String text, float volume) {
        handler.post(() -> {
            if (closed) return;
            pending.addLast(new Announcement(text, volume));
            if (ready) drain();
        });
    }

    void close() {
        handler.post(() -> {
            if (closed) return;
            closed = true;
            ready = false;
            pending.clear();
            restoreAlarmVolume();
            activeUtteranceId = null;
            activeVolume = 0;
            if (tts != null) {
                tts.stop();
                tts.shutdown();
                tts = null;
            }
        });
    }

    private void initialized(int status) {
        if (closed || tts == null) return;
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "Text-to-speech engine unavailable");
            DiagnosticLog.log(context, "speech", "TEXT-TO-SPEECH ENGINE UNAVAILABLE: spoken warnings will not play");
            pending.clear();
            tts.shutdown();
            tts = null;
            closed = true;
            return;
        }
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String id) {
                handler.post(() -> startUtterance(id));
            }

            @Override
            public void onDone(String id) {
                handler.post(() -> finishUtterance(id));
            }

            @Override
            public void onError(String id) {
                handler.post(() -> failUtterance(id));
            }

            @Override
            public void onError(String id, int errorCode) {
                handler.post(() -> failUtterance(id));
            }

            @Override
            public void onStop(String id, boolean interrupted) {
                handler.post(() -> finishUtterance(id));
            }
        });
        ready = true;
        drain();
    }

    private void drain() {
        if (!ready || closed || activeUtteranceId != null) return;
        while (ready && !closed && activeUtteranceId == null && !pending.isEmpty()) {
            Announcement next = pending.removeFirst();
            Locale locale = Locale.forLanguageTag(AppLanguage.text(context, "en-US", "ro-RO", "es-ES"));
            try {
                if (tts.setAudioAttributes(SUCCESS_AUDIO) != TextToSpeech.SUCCESS) {
                    Log.w(TAG, "Text-to-speech could not select the outcome audio stream");
                    continue;
                }
                int language = tts.setLanguage(locale);
                if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Text-to-speech language unavailable: " + locale);
                    DiagnosticLog.log(context, "speech", "VOICE MISSING for " + locale + ": announcement dropped");
                    continue;
                }
                String message = next.text;
                String id = "trade-outcome-" + (++utteranceId);
                activeUtteranceId = id;
                activeVolume = next.volume;
                if (tts.speak(message, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.ERROR) {
                    activeUtteranceId = null;
                    activeVolume = 0;
                    Log.w(TAG, "Text-to-speech could not queue the outcome");
                    continue;
                }
                handler.postDelayed(() -> {
                    if (!id.equals(activeUtteranceId)) return;
                    try {
                        tts.stop();
                    } catch (RuntimeException error) {
                        Log.w(TAG, "Could not stop stalled text-to-speech playback", error);
                    }
                    failUtterance(id);
                }, PLAYBACK_TIMEOUT_MS);
                return;
            } catch (RuntimeException error) {
                restoreAlarmVolume();
                activeUtteranceId = null;
                activeVolume = 0;
                Log.w(TAG, "Text-to-speech could not announce the outcome", error);
            }
        }
    }

    private void startUtterance(String id) {
        if (closed || id == null || !id.equals(activeUtteranceId) || activeVolume <= 0
                || savedAlarmVolume >= 0) return;
        try {
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audio == null) return;
            int before = audio.getStreamVolume(AudioManager.STREAM_ALARM);
            int maximum = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            int wanted = Math.max(1, Math.round(maximum * activeVolume));
            if (before >= wanted) return;
            savedAlarmVolume = before;
            forcedAlarmVolume = wanted;
            // Remember the user's volume on disk too, in case the app dies before it is restored.
            context.getSharedPreferences(VOLUME_PREFS, Context.MODE_PRIVATE).edit()
                    .putInt(SAVED_VOLUME, before).putInt(FORCED_VOLUME, wanted).commit();
            audio.setStreamVolume(AudioManager.STREAM_ALARM, wanted, 0);
        } catch (RuntimeException error) {
            savedAlarmVolume = -1;
            forcedAlarmVolume = -1;
            Log.w(TAG, "Could not temporarily raise alarm volume for speech", error);
        }
    }

    private void finishUtterance(String id) {
        if (id == null || !id.equals(activeUtteranceId)) return;
        restoreAlarmVolume();
        activeUtteranceId = null;
        activeVolume = 0;
        if (!closed) drain();
    }

    private void failUtterance(String id) {
        if (id == null || !id.equals(activeUtteranceId)) return;
        Log.w(TAG, "Text-to-speech failed while announcing an outcome");
        finishUtterance(id);
    }

    /** If the app died mid-announcement, put the alarm volume back to what the user had. */
    private void restoreVolumeAfterCrash() {
        android.content.SharedPreferences saved = context.getSharedPreferences(VOLUME_PREFS, Context.MODE_PRIVATE);
        int previous = saved.getInt(SAVED_VOLUME, -1);
        int forced = saved.getInt(FORCED_VOLUME, -1);
        if (previous < 0) return;
        saved.edit().clear().apply();
        try {
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audio != null && audio.getStreamVolume(AudioManager.STREAM_ALARM) == forced) {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, previous, 0);
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not restore alarm volume after restart", error);
        }
    }

    private void restoreAlarmVolume() {
        if (savedAlarmVolume < 0) return;
        context.getSharedPreferences(VOLUME_PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        int previous = savedAlarmVolume;
        int forced = forcedAlarmVolume;
        savedAlarmVolume = -1;
        forcedAlarmVolume = -1;
        try {
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audio != null && audio.getStreamVolume(AudioManager.STREAM_ALARM) == forced
                    && forced != previous) {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, previous, 0);
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not restore alarm volume after speech", error);
        }
    }
}
