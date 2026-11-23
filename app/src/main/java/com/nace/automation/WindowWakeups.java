package com.nace.automation;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.List;

public final class WindowWakeups extends BroadcastReceiver {
    private static final String ACTION_WAKE = "com.nace.automation.WAKE_FOR_INTERVAL";
    private static final int REQUEST_ID = 4602;
    // The OnePlus grouped alarm deliveries by up to 92 seconds in the first test.
    // Wake the worker earlier while keeping the first actual readiness check at five minutes.
    static final long WORKER_WAKE_LEAD_SECONDS = AutomationService.PREFLIGHT_LEAD_SECONDS + 135;
    static final String WAKE_LEAD_EXTRA = "leadSeconds";
    private static final int EARLY_PREFLIGHT_ID = 4606;
    private static final int FINAL_PREFLIGHT_ID = 4607;

    public static boolean canSchedule(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        return alarms != null && (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms());
    }

    static void schedule(Context context, boolean enabled, List<AutomationService.TradingWindow> windows, ZoneId zone) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) return;

        boolean scheduleEnabled = enabled && !windows.isEmpty() && canSchedule(context);
        ZonedDateTime now = ZonedDateTime.now(zone);
        scheduleNext(context, alarms, windows, now, scheduleEnabled, REQUEST_ID, 0);
        scheduleNext(context, alarms, windows, now, scheduleEnabled, EARLY_PREFLIGHT_ID, WORKER_WAKE_LEAD_SECONDS);
        scheduleNext(context, alarms, windows, now, scheduleEnabled, FINAL_PREFLIGHT_ID, AutomationService.FINAL_PREFLIGHT_LEAD_SECONDS);
    }

    private static void scheduleNext(Context context, AlarmManager alarms, List<AutomationService.TradingWindow> windows,
            ZonedDateTime now, boolean enabled, int requestId, long leadSeconds) {
        Intent due = new Intent(context, WindowWakeups.class).setAction(ACTION_WAKE)
                .putExtra(WAKE_LEAD_EXTRA, leadSeconds);
        if (!enabled) {
            PendingIntent existing = PendingIntent.getBroadcast(
                    context, requestId, due, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE);
            if (existing != null) {
                alarms.cancel(existing);
                existing.cancel();
            }
            return;
        }

        ZonedDateTime next = null;
        for (AutomationService.TradingWindow window : windows) {
            for (int day = 0; day <= 2; day++) {
                ZonedDateTime check = ZonedDateTime.of(now.toLocalDate().plusDays(day),
                        DailySchedule.time(window.start), now.getZone()).minusSeconds(leadSeconds);
                if (check.isAfter(now)) {
                    if (next == null || check.isBefore(next)) next = check;
                    break;
                }
            }
        }
        if (next == null) return;

        PendingIntent pending = PendingIntent.getBroadcast(
                context, requestId, due, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            long trigger = next.toInstant().toEpochMilli();
            if (leadSeconds == 0) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending);
            } else {
                // Leave Android's limited Doze alarm quota for the trading-window wake-up.
                alarms.setExact(AlarmManager.RTC_WAKEUP, trigger, pending);
            }
        } catch (SecurityException error) {
            Log.w("NaceAutomation", "Android revoked exact alarm access before scheduling.", error);
        }
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (!ACTION_WAKE.equals(intent.getAction()) || !AutomationService.scheduledAutomationEnabled(context)) return;
        long leadSeconds = intent.getLongExtra(WAKE_LEAD_EXTRA, -1);
        Intent wakeService = new Intent(context, AutomationService.class).putExtra(WAKE_LEAD_EXTRA, leadSeconds);
        try {
            context.startForegroundService(wakeService);
        } catch (IllegalStateException | SecurityException error) {
            Log.w("NaceAutomation", "Android did not allow the scheduled background service start.", error);
        }
    }
}
