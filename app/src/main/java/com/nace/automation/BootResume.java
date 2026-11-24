package com.nace.automation;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Restores scheduled automation after events that silently erase alarms: a reboot, an app update,
 * and the "Alarms & reminders" (exact alarm) permission being granted or revoked.
 */
public final class BootResume extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED.equals(action)) return;
        DiagnosticLog.log(context, "system", action + " (wake-ups allowed: " + WindowWakeups.canSchedule(context) + ")");
        if (!AutomationService.resumeAfterBoot(context)) return;
        AutomationService.rescheduleFromPreferences(context);
        try {
            context.startForegroundService(new Intent(context, AutomationService.class));
        } catch (IllegalStateException | SecurityException error) {
            Log.w("NaceAutomation", "Android did not allow the background service to restart.", error);
            DiagnosticLog.log(context, "service", "Android REFUSED the restart after " + action + ": " + error);
        }
    }
}
