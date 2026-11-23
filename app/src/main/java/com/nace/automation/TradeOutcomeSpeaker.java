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

/** Speaks outcomes supplied by the service; it does not decide which outcomes are final. */
final class TradeOutcomeSpeaker {
    private static final String TAG = "TradeOutcomeSpeaker";
    private static final long PLAYBACK_TIMEOUT_MS = 20000;
    private static final AudioAttributes SUCCESS_AUDIO = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();
    private static final AudioAttributes FAILURE_AUDIO = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Boolean> pending = new ArrayDeque<>();
    private TextToSpeech tts;
    private boolean ready;
    private boolean closed;
    private long utteranceId;
    private String activeUtteranceId;
    private boolean activeSuccess;
    private int savedAlarmVolume = -1;
    private int forcedAlarmVolume = -1;

    TradeOutcomeSpeaker(Context context) {
        this.context = context.getApplicationContext();
        try {
            // Posting also handles an engine callback arriving before the constructor returns.
            tts = new TextToSpeech(this.context, status -> handler.post(() -> initialized(status)));
        } catch (RuntimeException error) {
            closed = true;
            Log.w(TAG, "Text-to-speech initialization failed", error);
        }
    }

    void speak(boolean successful) {
        handler.post(() -> {
            if (closed) return;
            pending.addLast(successful);
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
            activeSuccess = false;
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
            boolean successful = pending.removeFirst();
            Locale locale = Locale.forLanguageTag(AppLanguage.text(context, "en-US", "ro-RO", "es-ES"));
            try {
                if (tts.setAudioAttributes(successful ? SUCCESS_AUDIO : FAILURE_AUDIO) != TextToSpeech.SUCCESS) {
                    Log.w(TAG, "Text-to-speech could not select the outcome audio stream");
                    continue;
                }
                int language = tts.setLanguage(locale);
                if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Text-to-speech language unavailable: " + locale);
                    continue;
                }
                String message = successful
                        ? AppLanguage.text(context, "Trade successful.", "Tranzacție reușită.", "Operación completada.")
                        : AppLanguage.text(context, "Trade failed.", "Tranzacție eșuată.", "La operación falló.");
                String id = "trade-outcome-" + (++utteranceId);
                activeUtteranceId = id;
                activeSuccess = successful;
                if (tts.speak(message, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.ERROR) {
                    activeUtteranceId = null;
                    activeSuccess = false;
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
                activeSuccess = false;
                Log.w(TAG, "Text-to-speech could not announce the outcome", error);
            }
        }
    }

    private void startUtterance(String id) {
        if (closed || id == null || !id.equals(activeUtteranceId) || !activeSuccess
                || savedAlarmVolume >= 0) return;
        try {
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audio == null) return;
            int before = audio.getStreamVolume(AudioManager.STREAM_ALARM);
            int maximum = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            savedAlarmVolume = before;
            forcedAlarmVolume = maximum;
            if (before != maximum) audio.setStreamVolume(AudioManager.STREAM_ALARM, maximum, 0);
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
        activeSuccess = false;
        if (!closed) drain();
    }

    private void failUtterance(String id) {
        if (id == null || !id.equals(activeUtteranceId)) return;
        Log.w(TAG, "Text-to-speech failed while announcing an outcome");
        finishUtterance(id);
    }

    private void restoreAlarmVolume() {
        if (savedAlarmVolume < 0) return;
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
