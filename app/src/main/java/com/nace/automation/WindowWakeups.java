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
    // Wake the worker ~12 minutes early so the readiness check (from 10 minutes before the window,
    // see PREFLIGHT_LEAD_SECONDS) and the "online, waiting" announcement at 5 minutes are on time.
    static final long WORKER_WAKE_LEAD_SECONDS = AutomationService.PREFLIGHT_LEAD_SECONDS + 135;
    /** One hour before each window: a health check, so a problem is found while there is time to fix it. */
    static final long HEALTH_CHECK_LEAD_SECONDS = 3600;
    private static final int HEALTH_CHECK_ID = 4608;
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
        scheduleNext(context, alarms, windows, now, scheduleEnabled, HEALTH_CHECK_ID, HEALTH_CHECK_LEAD_SECONDS);
        if (!scheduleEnabled) {
            DiagnosticLog.change(context, "alarms", enabled && !windows.isEmpty()
                    ? "NOT SCHEDULED: exact-alarm permission (scheduled wake-ups) is missing"
                    : "not scheduled (automation off or no windows)");
        }
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
        DiagnosticLog.change(context, "alarm." + leadSeconds, "next at " + next);

        PendingIntent pending = PendingIntent.getBroadcast(
                context, requestId, due, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            long trigger = next.toInstant().toEpochMilli();
            if (leadSeconds == 0 || leadSeconds == WORKER_WAKE_LEAD_SECONDS || leadSeconds == HEALTH_CHECK_LEAD_SECONDS) {
                // The early wake must survive Doze too: it starts the warm-up that announces
                // "online, waiting for the trade" five minutes before the window, or a WARNING.
                // It is ~12 minutes before the window alarm, so both fit Android's idle quota.
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending);
            } else {
                // The final check one minute before the window runs while the worker is already awake.
                alarms.setExact(AlarmManager.RTC_WAKEUP, trigger, pending);
            }
        } catch (SecurityException error) {
            Log.w("NaceAutomation", "Android revoked exact alarm access before scheduling.", error);
            DiagnosticLog.log(context, "alarms", "FAILED to schedule: exact alarm access revoked");
        }
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (!ACTION_WAKE.equals(intent.getAction()) || !AutomationService.scheduledAutomationEnabled(context)) return;
        long leadSeconds = intent.getLongExtra(WAKE_LEAD_EXTRA, -1);
        DiagnosticLog.log(context, "alarm", "fired (" + leadSeconds + " s before window)");
        // Keep the chain alive even if Android refuses to start the service below.
        AutomationService.rescheduleFromPreferences(context);
        Intent wakeService = new Intent(context, AutomationService.class).putExtra(WAKE_LEAD_EXTRA, leadSeconds);
        try {
            context.startForegroundService(wakeService);
        } catch (IllegalStateException | SecurityException error) {
            Log.w("NaceAutomation", "Android did not allow the scheduled background service start.", error);
            DiagnosticLog.log(context, "service", "Android REFUSED the scheduled start: " + error);
        }
    }
}
