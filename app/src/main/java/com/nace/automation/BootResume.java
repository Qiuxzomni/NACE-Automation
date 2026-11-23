package com.nace.automation;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class BootResume extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || !AutomationService.resumeAfterBoot(context)) return;
        try {
            context.startForegroundService(new Intent(context, AutomationService.class));
        } catch (IllegalStateException | SecurityException error) {
            Log.w("NaceAutomation", "Android did not allow the background service to restart.", error);
        }
    }
}
