package com.nace.automation;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

/** Posts a separate, dismissible notification for a terminal live result. */
final class TradeResultNotification {
    private static final String CHANNEL = "nace_trade_results";
    private static final int NOTIFICATION_ID = 4604;
    private static final String TAG_PREFIX = "nace-result:";

    private TradeResultNotification() {}

    static void post(Context context, String key, String pair, String window, String status, String detail) {
        if (context == null || key == null || key.isEmpty()) return;
        String title;
        if ("submitted".equals(status)) {
            title = AppLanguage.text(context, "Trade Verified", "Tranzacție verificată", "Operación verificada");
        } else if ("failed".equals(status) || "skipped".equals(status)) {
            title = AppLanguage.text(context, "Trade not placed", "Tranzacție neplasată", "Operación no enviada");
        } else if ("confirmation_tapped".equals(status)) {
            title = AppLanguage.text(context, "Trade result unverified", "Rezultatul tranzacției nu este verificat", "Resultado sin verificar");
        } else {
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        try {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    AppLanguage.text(context, "NACE trade results", "Rezultate tranzacții NACE", "Resultados de operaciones de NACE"),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null);
            channel.enableVibration(false);
            manager.createNotificationChannel(channel);

            StringBuilder summary = new StringBuilder();
            if (pair != null && !pair.isEmpty()) summary.append(pair);
            if (window != null && !window.isEmpty()) {
                if (summary.length() > 0) summary.append(" · ");
                summary.append(window);
            }
            String resultDetail = AutomationService.localizeHistoryLine(context, detail);
            if (resultDetail != null && !resultDetail.isEmpty()) {
                if (summary.length() > 0) summary.append("\n");
                summary.append(resultDetail);
            }
            String generic = AppLanguage.text(context, "A trade result is available.",
                    "Este disponibil un rezultat al tranzacției.", "Hay un resultado disponible.");
            Notification publicVersion = new Notification.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                    .setContentTitle("NACE")
                    .setContentText(generic)
                    .build();
            Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                    .setContentTitle(title)
                    .setContentText(summary.length() == 0 ? generic : summary.toString())
                    .setStyle(new Notification.BigTextStyle().bigText(summary.length() == 0 ? generic : summary.toString()))
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setPublicVersion(publicVersion)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true)
                    .setOngoing(false);
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            if (launch != null) {
                builder.setContentIntent(PendingIntent.getActivity(context, NOTIFICATION_ID, launch,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            }
            manager.notify(TAG_PREFIX + key, NOTIFICATION_ID, builder.build());
        } catch (SecurityException error) {
            Log.w("TradeResultNotification", "Notification permission unavailable", error);
        }
    }
}
