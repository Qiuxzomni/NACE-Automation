package com.nace.automation;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Anonymous install / active-user heartbeat: at most one small request per day, sent from the
 * background. It carries only a random install ID, the app version, Android SDK level and the
 * app language. No phone number, credentials, balances, trades or pairs, ever.
 * A failed ping is not retried; the next day's ping covers it.
 */
final class UsagePing {
    private static final String ENDPOINT = "https://qplex.tail6cba94.ts.net/v1/ping";
    private static final String APP_KEY = "e1ad6104f8e978829d7cfa984a18f454";
    private static final String APP_ID = "nace";

    private static final String PREFERENCES = "usage_stats";
    private static final String ENABLED = "enabled";
    private static final String INSTALL_ID = "install_id";
    private static final int JOB_ID = 7301;

    private UsagePing() {}

    static boolean enabled(Context context) {
        return preferences(context).getBoolean(ENABLED, true);
    }

    static void setEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(ENABLED, enabled).apply();
        schedule(context);
    }

    /** Idempotent: keeps an existing daily job, creates it if missing, cancels it when disabled. */
    static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        if (!enabled(context)) {
            scheduler.cancel(JOB_ID);
            return;
        }
        if (scheduler.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, Job.class))
                .setPeriodic(TimeUnit.DAYS.toMillis(1), TimeUnit.HOURS.toMillis(6))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build();
        try {
            scheduler.schedule(job);
        } catch (RuntimeException error) {
            Log.w("NaceAutomation", "Usage stats job could not be scheduled.", error);
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static synchronized String installId(Context context) {
        SharedPreferences preferences = preferences(context);
        String id = preferences.getString(INSTALL_ID, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            preferences.edit().putString(INSTALL_ID, id).apply();
        }
        return id;
    }

    private static String version(Context context) {
        try {
            String name = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            return name == null ? "0" : name.replaceAll("[^0-9A-Za-z.+-]", "");
        } catch (PackageManager.NameNotFoundException error) {
            return "0";
        }
    }

    static void send(Context context) throws Exception {
        String body = "{\"app_id\":\"" + APP_ID + "\""
                + ",\"install_id\":\"" + installId(context) + "\""
                + ",\"version\":\"" + version(context) + "\""
                + ",\"sdk\":" + Build.VERSION.SDK_INT
                + ",\"lang\":\"" + AppLanguage.current(context) + "\"}";
        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("X-App-Key", APP_KEY);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
            int code = connection.getResponseCode();
            if (code != 204) Log.i("NaceAutomation", "Usage stats ping returned HTTP " + code);
        } finally {
            connection.disconnect();
        }
    }

    public static final class Job extends JobService {
        @Override public boolean onStartJob(JobParameters params) {
            if (!enabled(this)) return false;
            new Thread(() -> {
                try {
                    send(getApplicationContext());
                } catch (Exception error) {
                    Log.i("NaceAutomation", "Usage stats ping failed; the next daily ping will cover it.");
                } finally {
                    jobFinished(params, false);
                }
            }, "nace-usage-ping").start();
            return true;
        }

        @Override public boolean onStopJob(JobParameters params) {
            return false;
        }
    }
}
