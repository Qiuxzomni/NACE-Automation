package com.nace.automation;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.UUID;

/** Announces a current trading blocker until it clears or the user acknowledges it. */
public final class AutomationBlockerAlert {
    // New id: channel sound/vibration cannot be changed after creation, so the old silent one is removed.
    private static final String CHANNEL = "nace_warnings";
    private static final String OLD_CHANNEL = "nace_automation_blockers";
    private static final long[] VIBRATION = {0, 700, 300, 700, 300, 700};
    private static final String PREFERENCES = "automation_blocker_alert";
    private static final String SOUNDS = "sounds";
    private static final String OCCURRENCE = "occurrence";
    private static final String ISSUE = "issue";
    private static final String ACTIVE_TOKEN = "active_token";
    private static final String ACKNOWLEDGED_TOKEN = "acknowledged_token";
    private static final String TOKEN_EXTRA = "alert_token";
    private static final int NOTIFICATION_ID = 4606;
    private static final int WARNING_RED = 0xffd32f2f;
    private static final int ACKNOWLEDGE_ACTION = 46;
    private static final long BEEP_INTERVAL_MS = 30_000;
    private static final int BEEP_DURATION_MS = 400;

    private final Context context;
    private final NotificationManager notifications;
    private final SharedPreferences preferences;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable repeatBeep = this::repeatBeep;
    private final Runnable releaseTone = this::releaseTone;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
            (unused, key) -> onPreferenceChanged(key);
    private String token;
    private String occurrence;
    private String issue;
    private String detail;
    private boolean posted;
    private boolean sounding;
    private ToneGenerator tone;

    AutomationBlockerAlert(Context context) {
        this.context = context.getApplicationContext();
        notifications = this.context.getSystemService(NotificationManager.class);
        preferences = preferences(this.context);
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
    }

    static boolean soundsEnabled(Context context) {
        return preferences(context).getBoolean(SOUNDS, true);
    }

    static void setSoundsEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(SOUNDS, enabled).apply();
    }

    /** @return true when this is a new problem (not one already being shown), so it should be spoken. */
    boolean report(String occurrenceKey, String window, String pair, String issue) {
        String detail = issue + "\n" + window + " · " + pair;
        if (occurrenceKey.equals(occurrence) && issue.equals(this.issue)) {
            if (!posted && !acknowledged()) posted = postNotification();
            refreshSound();
            return false;
        }
        boolean fresh = false;
        stopSound();
        occurrence = occurrenceKey;
        this.issue = issue;
        this.detail = detail;
        token = preferences.getString(ACTIVE_TOKEN, null);
        if (token == null || !occurrenceKey.equals(preferences.getString(OCCURRENCE, null))
                || !issue.equals(preferences.getString(ISSUE, null))) {
            token = UUID.randomUUID().toString();
            fresh = true;
            preferences.edit().putString(ACTIVE_TOKEN, token).putString(OCCURRENCE, occurrenceKey)
                    .putString(ISSUE, issue).remove(ACKNOWLEDGED_TOKEN).apply();
        }
        posted = !acknowledged() && postNotification();
        refreshSound();
        return fresh;
    }

    /** True while a warning is being shown (acknowledged or not). */
    boolean active() {
        return token != null;
    }

    /** @return true when a warning was showing and is now cleared. */
    boolean clear() {
        if (token == null && !preferences.contains(ACTIVE_TOKEN)) return false;
        token = null;
        occurrence = null;
        issue = null;
        detail = null;
        preferences.edit().remove(ACTIVE_TOKEN).remove(OCCURRENCE).remove(ISSUE)
                .remove(ACKNOWLEDGED_TOKEN).apply();
        dismissNotification();
        stopSound();
        return true;
    }

    /** @param dismiss false keeps a live WARNING on screen after the service is gone. */
    void close(boolean dismiss) {
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        if (dismiss) dismissNotification();
        stopSound();
    }

    private void onPreferenceChanged(String key) {
        if (ACKNOWLEDGED_TOKEN.equals(key) && acknowledged()) dismissNotification();
        if (SOUNDS.equals(key) || ACKNOWLEDGED_TOKEN.equals(key)) refreshSound();
    }

    private boolean acknowledged() {
        return token != null && token.equals(preferences.getString(ACKNOWLEDGED_TOKEN, null));
    }

    private boolean canNotify() {
        if (notifications == null || !notifications.areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false;
        NotificationChannel channel = notifications.getNotificationChannel(CHANNEL);
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    private boolean postNotification() {
        // Audio requires an available notification so the user always has an Acknowledge action.
        if (!canNotify()) return false;
        try {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    AppLanguage.text(context, "NACE automation alerts", "Alerte de automatizare NACE",
                            "Alertas de automatización de NACE"), NotificationManager.IMPORTANCE_HIGH);
            channel.setSound(null, null);
            channel.enableVibration(true);
            channel.setVibrationPattern(VIBRATION);
            channel.setBypassDnd(true);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            notifications.createNotificationChannel(channel);
            if (notifications.getNotificationChannel(OLD_CHANNEL) != null) {
                notifications.deleteNotificationChannel(OLD_CHANNEL);
            }

            Intent acknowledge = new Intent(context, AcknowledgeReceiver.class)
                    .setData(Uri.parse("nace-alert://acknowledge/" + token))
                    .putExtra(TOKEN_EXTRA, token);
            PendingIntent acknowledgeButton = PendingIntent.getBroadcast(context, ACKNOWLEDGE_ACTION, acknowledge,
                    PendingIntent.FLAG_IMMUTABLE);
            String acknowledgeLabel = AppLanguage.text(context, "Acknowledge", "Confirmă alerta", "Entendido");
            Notification.Action action = new Notification.Action.Builder(
                    null, acknowledgeLabel, acknowledgeButton).build();
            Intent open = new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent openApp = PendingIntent.getActivity(context, NOTIFICATION_ID, open, PendingIntent.FLAG_IMMUTABLE);
            String title = AppLanguage.text(context, "⚠ WARNING · NACE needs attention",
                    "⚠ ATENȚIE · Verifică aplicația NACE", "⚠ ATENCIÓN · Revisa NACE");
            Notification publicVersion = new Notification.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setColor(WARNING_RED)
                    .setContentTitle(title)
                    .addAction(action)
                    .build();
            notifications.notify(NOTIFICATION_ID, new Notification.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setColor(WARNING_RED)
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setContentTitle(title)
                    .setContentText(issue)
                    .setStyle(new Notification.BigTextStyle().bigText(detail))
                    .setPublicVersion(publicVersion)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setContentIntent(openApp)
                    .addAction(action)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build());
            return true;
        } catch (SecurityException error) {
            Log.w("AutomationBlockerAlert", "Notification permission unavailable", error);
            return false;
        }
    }

    private void dismissNotification() {
        if (notifications != null) notifications.cancel(NOTIFICATION_ID);
        posted = false;
    }

    private void refreshSound() {
        if (!posted || acknowledged() || !preferences.getBoolean(SOUNDS, true)) {
            stopSound();
        } else if (!sounding) {
            sounding = true;
            beep();
        }
    }

    private void repeatBeep() {
        posted = postNotification();
        beep();
    }

    private void beep() {
        if (!posted || acknowledged() || !preferences.getBoolean(SOUNDS, true)) {
            stopSound();
            return;
        }
        try {
            tone = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, BEEP_DURATION_MS);
            handler.postDelayed(releaseTone, BEEP_DURATION_MS + 100L);
        } catch (RuntimeException error) {
            releaseTone();
            Log.w("AutomationBlockerAlert", "Trade blocker alert sound unavailable", error);
        }
        handler.postDelayed(repeatBeep, BEEP_INTERVAL_MS);
    }

    private void stopSound() {
        sounding = false;
        handler.removeCallbacks(repeatBeep);
        handler.removeCallbacks(releaseTone);
        releaseTone();
    }

    private void releaseTone() {
        if (tone == null) return;
        tone.release();
        tone = null;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public static final class AcknowledgeReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            String token = intent.getStringExtra(TOKEN_EXTRA);
            SharedPreferences preferences = preferences(context);
            if (token == null || !token.equals(preferences.getString(ACTIVE_TOKEN, null))) return;
            preferences.edit().putString(ACKNOWLEDGED_TOKEN, token).apply();
            NotificationManager notifications = context.getSystemService(NotificationManager.class);
            if (notifications != null) notifications.cancel(NOTIFICATION_ID);
        }
    }
}
