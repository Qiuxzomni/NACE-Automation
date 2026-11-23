package com.nace.automation;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class InternetProbe implements AutoCloseable {
    private static final String CHECK_URL = "https://www.google.com/generate_204";
    private static final int SOCKET_TIMEOUT_MS = 2500;
    private static final int DEADLINE_MS = 6000;

    private final ConnectivityManager connectivity;
    private final Handler handler;
    // A timed-out DNS request can keep running after interruption. Limit how many can accumulate.
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(0, 2, 10, TimeUnit.SECONDS,
            new SynchronousQueue<>());
    private Attempt pending;

    InternetProbe(Context context, Handler handler) {
        connectivity = context.getSystemService(ConnectivityManager.class);
        this.handler = handler;
    }

    // Owned by the service handler. All callers on the same active network share an in-flight check.
    void check(Consumer<Boolean> completed) {
        if (worker.isShutdown()) return;
        Network network = activeNetwork();
        if (network == null) {
            completed.accept(false);
            return;
        }
        if (pending != null && network.equals(pending.network)) {
            pending.callbacks.add(completed);
            return;
        }

        Attempt previous = pending;
        if (previous != null) cancel(previous);
        Attempt attempt = new Attempt(network, completed);
        pending = attempt;
        if (previous != null) attempt.callbacks.addAll(previous.callbacks);
        try {
            attempt.task = worker.submit(() -> {
                boolean reachable = reachesInternet(network);
                handler.post(() -> finish(attempt, reachable && network.equals(activeNetwork())));
            });
            handler.postDelayed(attempt.deadline, DEADLINE_MS);
        } catch (RejectedExecutionException unavailable) {
            finish(attempt, false);
        }
    }

    private Network activeNetwork() {
        return connectivity == null ? null : connectivity.getActiveNetwork();
    }

    private void finish(Attempt attempt, boolean reachable) {
        if (pending != attempt) return;
        pending = null;
        handler.removeCallbacks(attempt.deadline);
        for (Consumer<Boolean> callback : attempt.callbacks) callback.accept(reachable);
    }

    private void cancel(Attempt attempt) {
        handler.removeCallbacks(attempt.deadline);
        if (attempt.task != null) attempt.task.cancel(true);
    }

    @Override public void close() {
        if (pending != null) cancel(pending);
        pending = null;
        worker.shutdownNow();
    }

    private static boolean reachesInternet(Network network) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) network.openConnection(new URL(CHECK_URL));
            connection.setConnectTimeout(SOCKET_TIMEOUT_MS);
            connection.setReadTimeout(SOCKET_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Cache-Control", "no-cache");
            return connection.getResponseCode() == HttpURLConnection.HTTP_NO_CONTENT;
        } catch (IOException | SecurityException failure) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private final class Attempt {
        final Network network;
        final List<Consumer<Boolean>> callbacks = new ArrayList<>();
        final Runnable deadline = () -> {
            cancel(this);
            finish(this, false);
        };
        Future<?> task;

        Attempt(Network network, Consumer<Boolean> callback) {
            this.network = network;
            callbacks.add(callback);
        }
    }
}
