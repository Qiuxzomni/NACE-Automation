package com.nace.automation;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Persistent on-device diagnostic log, so problems that can disturb a trade (internet, login,
 * NACE link, permissions, alarms, background service, speech) can be found and fixed later.
 * Kept in app-private storage (two files of at most 512 KB). Never logs phone numbers, passwords,
 * balances or trade details. The user can share it from Settings.
 */
final class DiagnosticLog {
    private static final String TAG = "NaceDiagnostics";
    private static final long MAX_BYTES = 512 * 1024;
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx", Locale.ROOT);
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "nace-diagnostics");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<String, String> LAST = new HashMap<>();

    private DiagnosticLog() {}

    /** Appends one line: time, area and what happened. */
    static void log(Context context, String area, String message) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        String line = ZonedDateTime.now().format(STAMP) + "  [" + area + "] " + message;
        Log.i(TAG, line);
        WRITER.execute(() -> append(app, line));
    }

    /** Logs only when the value for this key changed since the last call (state transitions). */
    static void change(Context context, String key, String value) {
        String shown = value == null ? "ok" : value;
        synchronized (LAST) {
            if (shown.equals(LAST.get(key))) return;
            LAST.put(key, shown);
        }
        log(context, key, shown);
    }

    /** Most recent lines (oldest first), for sharing. */
    static String tail(Context context, int maxLines) {
        ArrayDeque<String> lines = new ArrayDeque<>();
        File dir = directory(context.getApplicationContext());
        for (File file : new File[]{new File(dir, "nace-log.1.txt"), new File(dir, "nace-log.txt")}) {
            if (!file.exists()) continue;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.addLast(line);
                    if (lines.size() > maxLines) lines.removeFirst();
                }
            } catch (IOException error) {
                Log.w(TAG, "Could not read diagnostic log", error);
            }
        }
        return String.join("\n", lines);
    }

    private static File directory(Context context) {
        File dir = new File(context.getFilesDir(), "diagnostics");
        if (!dir.exists() && !dir.mkdirs()) Log.w(TAG, "Could not create diagnostics folder");
        return dir;
    }

    private static void append(Context context, String line) {
        File dir = directory(context);
        File current = new File(dir, "nace-log.txt");
        if (current.length() > MAX_BYTES) {
            File previous = new File(dir, "nace-log.1.txt");
            if (previous.exists() && !previous.delete()) Log.w(TAG, "Could not rotate diagnostic log");
            if (!current.renameTo(previous)) Log.w(TAG, "Could not rotate diagnostic log");
        }
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(current, true), StandardCharsets.UTF_8)) {
            writer.write(line);
            writer.write('\n');
        } catch (IOException error) {
            Log.w(TAG, "Could not write diagnostic log", error);
        }
    }
}
