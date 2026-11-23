package com.nace.automation;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

/** A single dismissible alert; the service owns outage detection and debouncing. */
final class InternetOutageAlert {
    private static final String CHANNEL = "nace_internet_outage";
    private static final int NOTIFICATION_ID = 4605;

    private InternetOutageAlert() {}

    static void post(Context context, String window, String pair) {
        if (context == null) return;
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        try {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    AppLanguage.text(context, "Internet outage alerts", "Alerte conexiune la internet", "Alertas de conexión a Internet"),
                    NotificationManager.IMPORTANCE_HIGH);
            Uri alarm = Settings.System.DEFAULT_ALARM_ALERT_URI;
            channel.setSound(alarm, new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 300, 200, 300});
            manager.createNotificationChannel(channel);

            String title = AppLanguage.text(context, "Internet is down", "Conexiunea la internet este indisponibilă", "Sin conexión a Internet");
            StringBuilder message = new StringBuilder(AppLanguage.text(context,
                    "Check Wi-Fi or mobile data before the scheduled trade.",
                    "Verificați Wi-Fi sau datele mobile înainte de tranzacția programată.",
                    "Comprueba la red Wi-Fi o los datos móviles antes de la operación programada."));
            if ((window != null && !window.isEmpty()) || (pair != null && !pair.isEmpty())) {
                message.append("\n");
                if (window != null && !window.isEmpty()) message.append(window);
                if (pair != null && !pair.isEmpty()) {
                    if (window != null && !window.isEmpty()) message.append(" · ");
                    message.append(pair);
                }
            }
            Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(title)
                    .setContentText(message.toString())
                    .setStyle(new Notification.BigTextStyle().bigText(message.toString()))
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .setVisibility(Notification.VISIBILITY_PRIVATE);
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            if (launch != null) {
                builder.setContentIntent(PendingIntent.getActivity(context, NOTIFICATION_ID, launch,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            }
            manager.notify(NOTIFICATION_ID, builder.build());
        } catch (SecurityException error) {
            Log.w("InternetOutageAlert", "Notification permission unavailable", error);
        }
    }

    static void clear(Context context) {
        if (context == null) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
    }
}
