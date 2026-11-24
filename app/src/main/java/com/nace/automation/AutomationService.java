package com.nace.automation;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Presentation;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

public final class AutomationService extends Service {
    public static final String ETH = NaceWebScript.ETH;
    public static final String BTC = NaceWebScript.BTC;

    private static final String PREFS = "nace_automation";
    private static final String INITIALIZED = "automation_initialized";
    private static final String HISTORY_CHECKPOINT = "history_checkpoint_ms";
    private static final String SCHEDULE_ZONE = "schedule_zone";
    private static final String AUTOMATION_STOPPED = "automation_stopped";
    private static final String ONLINE_ANNOUNCED = "online_announced_window";
    private static final long ONLINE_ANNOUNCE_LEAD_SECONDS = 300;
    private static final String MISSED_OFFLINE = "Missed while offline. Automation did not run during this window.";
    private static final String BALANCE_DAY = "balance_day";
    private static final String DAY_OPEN_BALANCE = "day_open_balance";
    private static final String CHANNEL = "nace_worker";
    private static final String EXCHANGE_DNS_ERROR = "The NACE domain could not be resolved. Check the exchange link.";
    private static final String EXCHANGE_UNAVAILABLE = "The NACE exchange page could not be loaded.";
    private static final String INTERNET_DOWN = "Internet is down. Check your Wi-Fi or mobile data.";
    private static final String WAKEUPS_REQUIRED = "Allow scheduled wake-ups in Settings to run automation.";
    private static final String CHECKING_PAIR = "Checking the selected pair before the scheduled run.";
    private static final String STOPPED_BY_USER = "Automation stopped in Settings.";
    private static final String STOPPING_AFTER_CONFIRM = "Automation stopped. Checking the last confirmation.";
    private static final String STEP_PREFIX = "Step · ";
    private static final String CHECK_PREFIX = "Last check · ";
    private static final String TIME_PREFIX = "Recorded at · ";
    private static final int NOTIFICATION_ID = 4601;
    private static final long TICK_MS = 1000;
    private static final long SESSION_REFRESH_MS = 20000;
    private static final long SESSION_FRESHNESS_MS = 45000;
    private static final long CONFIRM_PROOF_FRESHNESS_MS = 12000;
    private static final long PROBE_TIMEOUT_MS = 15000;
    private static final long WORKFLOW_CALLBACK_TIMEOUT_MS = 7000;
    private static final long CONFIRM_RESULT_TIMEOUT_MS = 30000;
    private static final long CONFIRM_RETRY_MS = 25000;
    private static final int LINK_FAILURE_LIMIT = 5;
    private static final long LINK_RECOVERY_REMINDER_MS = 120000;
    private static final long MIN_RELOAD_DELAY_MS = 2000;
    private static final long MAX_RELOAD_DELAY_MS = 20000;
    static final long PREFLIGHT_LEAD_SECONDS = 600;
    static final long FINAL_PREFLIGHT_LEAD_SECONDS = 60;
    private static final int RESULT_LIMIT = 60;

    public enum SessionState { CHECKING, SIGNED_IN, SIGNED_OUT, UNAVAILABLE }

    /** What the user must fix, so the app can offer the right help (Sign in, Check internet, New link). */
    public enum AttentionKind { INTERNET, LOGIN, LINK, OTHER }

    public static final class Attention {
        public final AttentionKind kind;
        /** Localized, for the user. */
        public final String reason;
        /** English source text, for logs and the WARNING notification. */
        final String issue;

        Attention(AttentionKind kind, String reason, String issue) {
            this.kind = kind;
            this.reason = reason;
            this.issue = issue;
        }
    }

    public static final class TradingWindow {
        public final String start;
        public final String end;
        private final LocalTime startTime;
        private final LocalTime endTime;

        public TradingWindow(String start, String end) {
            startTime = DailySchedule.time(start);
            endTime = DailySchedule.time(end);
            if (startTime.equals(endTime)) {
                throw new IllegalArgumentException("An interval needs different start and end times.");
            }
            this.start = start;
            this.end = end;
        }
    }

    public static final class RunResult {
        public final String pair;
        public final String date;
        public final String start;
        public final String end;
        public final String status;
        public final String message;
        public final String signalId;
        public final ZoneId zone;

        private RunResult(String pair, String date, String start, String end, String status, String message,
                String signalId, ZoneId zone) {
            this.pair = pair;
            this.date = date;
            this.start = start;
            this.end = end;
            this.status = status;
            this.message = message;
            this.signalId = signalId;
            this.zone = zone;
        }

        DailySchedule.Occurrence occurrence() {
            return DailySchedule.occurrence(LocalDate.parse(date), DailySchedule.time(start), DailySchedule.time(end), zone);
        }
    }

    public static final class Snapshot {
        public final boolean automationOnline;
        public final boolean backgroundActive;
        public final boolean manualControl;
        public final SessionState session;
        public final String balance;
        public final BigDecimal dailyBalanceChange;
        public final String pair;
        public final String lastCheck;
        public final String message;
        public final List<TradingWindow> windows;
        public final List<RunResult> results;

        private Snapshot(
                boolean automationOnline, boolean backgroundActive,
                boolean manualControl, SessionState session, String balance, BigDecimal dailyBalanceChange, String pair,
                String lastCheck, String message, List<TradingWindow> windows, List<RunResult> results
        ) {
            this.automationOnline = automationOnline;
            this.backgroundActive = backgroundActive;
            this.manualControl = manualControl;
            this.session = session;
            this.balance = balance;
            this.dailyBalanceChange = dailyBalanceChange;
            this.pair = pair;
            this.lastCheck = lastCheck;
            this.message = message;
            this.windows = windows;
            this.results = results;
        }
    }

    public final class LocalBinder extends Binder {
        public AutomationService getService() {
            return AutomationService.this;
        }
    }

    private enum Stage { TAB, SIGNALS, OPEN, DIALOG, CONFIRM, OBSERVE }

    private static final class Run {
        final DailySchedule.Occurrence occurrence;
        final String pair;
        final String nonce = UUID.randomUUID().toString();
        Stage stage = Stage.TAB;
        String signalId;
        boolean confirmationSent;
        boolean attemptedRecovery;
        boolean reserved;
        int requiredProbe;
        long responseDeadline;
        long confirmRetryDeadline;
        long signalOpenDeadline;
        long nextSignalRefreshAt;
        String lastCheckDetail;
        long nextStep;

        Run(DailySchedule.Occurrence occurrence, String pair) {
            this.occurrence = occurrence;
            this.pair = pair;
        }
    }

    private final LocalBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver timeZoneChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_TIMEZONE_CHANGED.equals(intent.getAction())) updateWorkerNotification();
        }
    };
    private final LinkedHashMap<String, RunResult> results = new LinkedHashMap<>();
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            tick();
            if (workerNeeded()) {
                handler.postDelayed(this, TICK_MS);
            } else if (!parked) {
                // The last active tick can clear trackedWindow and activeRun. Give
                // the idle branch one pass to close the browser and release the lock.
                handler.post(this);
            }
        }
    };
    private SharedPreferences preferences;
    private List<TradingWindow> windows = defaults();
    private ZoneId scheduleZone = DailySchedule.DEFAULT_ZONE;
    private String pair = ETH;
    private String balance;
    private String balanceDay;
    private BigDecimal dayOpenBalance;
    private BigDecimal dailyBalanceChange;
    private String message = "Open the browser to sign in to NACE.";
    private SessionState session = SessionState.CHECKING;
    private Instant lastCheck;
    private long verifiedAt;
    private long lastTick;
    private long lastProbeAt;
    private int probeId;
    private int verifiedProbeId;
    private boolean probing;
    private boolean liveAccountVerified;
    private boolean configurationValid = true;
    private boolean foreground;
    private boolean automationStopped;
    private boolean viewerOpen;
    private boolean manualControl;
    private boolean watchPairPending;
    private boolean watchPairChecking;
    private boolean watchPairConfirmed;
    /** WATCH re-tries once (fresh page) when the Copy Trading list could not be shown. */
    private int watchPairRetries;
    private int watchPairCheck;
    private Consumer<Boolean> pairCheckFinished;
    private boolean workflowRequest;
    private long workflowSequence;
    private long finalConfirmationRequest;
    private SharedBrowser finalConfirmationBrowser;
    private boolean mainFrameFailed;
    private int exchangeLoadFailures;
    private long lastLinkRecoveryPromptAt;
    private int exchangeLoadGeneration;
    private boolean exchangeDnsFailed;
    private Boolean internetAtPageFailure;
    private InternetProbe internetProbe;
    private AutomationBlockerAlert blockerAlert;
    private DailySchedule.Occurrence failedTradeWindow;
    private String failedTradePair;
    private String failedTradeIssue;
    private CredentialVault credentialVault;
    private SignInAttempt signInAttempt;
    private boolean attemptedSavedSignIn;
    private static final String VERIFICATION_PREFILLED =
            "Your phone number and password are already filled in. Enter the security code, then tap the Login button under it (not the Login tab at the top). If you don't see the button, scroll down a little.";
    private String signInHelp;
    private boolean signInHelpShown;
    private boolean credentialPromptPending;
    private long lastOnlinePageFailureAt;
    private long retryLoadAt;
    private long reloadDelay = MIN_RELOAD_DELAY_MS;
    private boolean exactAlarmAvailable;
    private long lastVirtualDisplayAttempt;
    private PowerManager.WakeLock wakeLock;
    private SharedBrowser browser;
    private ImageReader renderSurface;
    private VirtualDisplay virtualDisplay;
    private Presentation presentation;
    private FrameLayout renderRoot;
    private DailySchedule.Occurrence trackedWindow;
    private boolean trackedWindowLive;
    private DailySchedule.Occurrence preflightWindow;
    private String preflightPair;
    private String preflightIssue;
    private long preflightStartedAt;
    private int preflightRequiredProbe;
    private long nextPairCheckAt;
    private boolean preflightPairReady;
    private boolean finalPreflight;
    private DailySchedule.Occurrence preflightInternetWindow;
    private boolean preflightInternetFinal;
    private Boolean preflightInternet;
    private int preflightInternetGeneration;
    private long preflightInternetCheckedAt;
    private Run activeRun;
    private TradeOutcomeSpeaker outcomeSpeaker;
    private String notificationTitle;
    private String notificationDetail;

    static boolean resumeAfterBoot(Context context) {
        SharedPreferences saved = context.getSharedPreferences(PREFS, MODE_PRIVATE);
        return scheduledAutomationEnabled(context) && (saved.getBoolean(INITIALIZED, false) || saved.contains("live"));
    }

    /**
     * Rebuilds the wake-up alarms straight from saved settings, without the service running. Used by
     * the alarm receiver itself (so one refused service start cannot break the chain), after boot,
     * after an app update and when the exact-alarm permission changes.
     */
    static void rescheduleFromPreferences(Context context) {
        try {
            SharedPreferences saved = context.getSharedPreferences(PREFS, MODE_PRIVATE);
            boolean stopped = saved.getBoolean(AUTOMATION_STOPPED, false);
            String zoneId = saved.getString(SCHEDULE_ZONE, null);
            String encoded = saved.getString("windows", null);
            ZoneId zone = zoneId != null ? ZoneId.of(zoneId)
                    : encoded == null ? DailySchedule.DEFAULT_ZONE : DailySchedule.ZONE;
            List<TradingWindow> list;
            if (encoded == null) {
                list = defaults();
            } else {
                JSONArray schedule = new JSONArray(encoded);
                List<TradingWindow> read = new ArrayList<>();
                for (int position = 0; position < schedule.length(); position++) {
                    JSONObject entry = schedule.getJSONObject(position);
                    read.add(new TradingWindow(entry.getString("start"), entry.getString("end")));
                }
                list = validatedWindows(read);
            }
            WindowWakeups.schedule(context, !stopped, list, zone);
        } catch (JSONException | RuntimeException error) {
            DiagnosticLog.log(context, "alarms", "could not rebuild alarms from settings: " + error);
        }
    }

    static boolean scheduledAutomationEnabled(Context context) {
        return !context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(AUTOMATION_STOPPED, false);
    }

    @Override public void onCreate() {
        super.onCreate();
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        automationStopped = preferences.getBoolean(AUTOMATION_STOPPED, false);
        UsagePing.schedule(this);
        loadConfiguration();
        internetProbe = new InternetProbe(this, handler);
        blockerAlert = new AutomationBlockerAlert(this);
        credentialVault = new CredentialVault(this);
        balanceDay = preferences.getString(BALANCE_DAY, null);
        dayOpenBalance = parseBalance(preferences.getString(DAY_OPEN_BALANCE, null));
        outcomeSpeaker = new TradeOutcomeSpeaker(this);
        IntentFilter timeZoneFilter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(timeZoneChanged, timeZoneFilter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(timeZoneChanged, timeZoneFilter);
        handler.post(tick);
        DiagnosticLog.log(this, "service", "created");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            Notification notification = workerNotification(AppLanguage.text(this, "AUTOMATION STARTING", "AUTOMATIZAREA PORNEȘTE", "INICIANDO AUTOMATIZACIÓN"), pair);
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            foreground = true;
            notificationTitle = null;
        } catch (RuntimeException error) {
            foreground = false;
            message = "Android could not keep automation active in the background.";
            DiagnosticLog.log(this, "service", "COULD NOT START in the foreground: " + error);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (automationStopped) {
            refreshWakeup();
            stopForegroundIfIdle();
            return START_NOT_STICKY;
        }
        reconcileMissedWindows(Instant.now());
        long lead = intent == null ? -1 : intent.getLongExtra(WindowWakeups.WAKE_LEAD_EXTRA, -1);
        if (lead == WindowWakeups.WORKER_WAKE_LEAD_SECONDS) {
            earlyAlarmReceived = true;
        }
        if (lead == WindowWakeups.HEALTH_CHECK_LEAD_SECONDS) startHealthCheck();
        logEnvironment();
        refreshWakeup();
        wakeWorker();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override public void onDestroy() {
        DiagnosticLog.log(this, "service", "destroyed (automation stopped by user: " + automationStopped + ")");
        internetProbe.close();
        // Keep a live WARNING visible when the service goes away, unless the user stopped automation.
        blockerAlert.close(automationStopped);
        handler.removeCallbacksAndMessages(null);
        unregisterReceiver(timeZoneChanged);
        outcomeSpeaker.close();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        foreground = false;
        closeOffscreenHost();
        discardBrowser();
        super.onDestroy();
    }

    public List<TradingWindow> getWindows() {
        return Collections.unmodifiableList(new ArrayList<>(windows));
    }

    ZoneId getScheduleZone() {
        return scheduleZone;
    }

    public void setWindows(List<TradingWindow> proposed) {
        requireChangeable();
        List<TradingWindow> sorted = validatedWindows(proposed);
        JSONArray encoded = new JSONArray();
        try {
            for (TradingWindow window : sorted) {
                encoded.put(new JSONObject().put("start", window.start).put("end", window.end));
            }
        } catch (JSONException error) {
            throw new IllegalArgumentException(localize(this, "Invalid scheduled intervals."), error);
        }
        Instant changedAt = Instant.now();
        reconcileMissedWindows(changedAt);
        if (!preferences.edit().putString("windows", encoded.toString())
                .putString(SCHEDULE_ZONE, scheduleZone.getId())
                .putLong(HISTORY_CHECKPOINT, changedAt.toEpochMilli()).commit()) {
            throw new IllegalStateException(localize(this, "The schedule could not be saved."));
        }
        cancelBeforeConfirmation();
        windows = sorted;
        refreshWakeup();
        wakeWorker();
    }

    private ExchangeLink exchangeLink;

    String getExchangeLink() {
        return exchangeLink.url;
    }

    boolean takeLinkRecoveryPrompt() {
        long now = SystemClock.elapsedRealtime();
        if (exchangeLoadFailures < LINK_FAILURE_LIMIT
                || !Boolean.TRUE.equals(internetAtPageFailure) || !networkAvailable()
                || now - lastOnlinePageFailureAt > MAX_RELOAD_DELAY_MS
                || lastLinkRecoveryPromptAt != 0 && now - lastLinkRecoveryPromptAt < LINK_RECOVERY_REMINDER_MS) return false;
        lastLinkRecoveryPromptAt = now;
        return true;
    }

    private void resetExchangeLoadFailures() {
        exchangeLoadGeneration++;
        exchangeLoadFailures = 0;
        lastLinkRecoveryPromptAt = 0;
        internetAtPageFailure = null;
        exchangeDnsFailed = false;
    }

    void setExchangeLink(ExchangeLink next) {
        requireChangeable();
        if (viewerOpen) throw new IllegalStateException();
        boolean originChanged = !exchangeLink.origin.equals(next.origin);
        SharedPreferences.Editor editor = preferences.edit().putString(ExchangeLink.PREFERENCE, next.url);
        if (originChanged) editor.remove(BALANCE_DAY).remove(DAY_OPEN_BALANCE);
        if (!editor.commit()) throw new IllegalStateException();
        cancelBeforeConfirmation();
        cancelPairCheck();
        exchangeLink = next;
        signInAttempt = null;
        credentialPromptPending = false;
        signInHelp = null;
        signInHelpShown = false;
        attemptedSavedSignIn = false;
        resetExchangeLoadFailures();
        preflightWindow = null;
        preflightPairReady = false;
        preflightIssue = null;
        watchPairConfirmed = false;
        if (originChanged) {
            balanceDay = null;
            dayOpenBalance = null;
        }
        closeOffscreenHost();
        discardBrowser();
        browserCookiesFlushed = false;
        lastVirtualDisplayAttempt = 0;
        invalidateSession();
        if (configurationValid) message = "Open the browser to sign in to NACE.";
        wakeWorker();
    }

    private static final class SignInAttempt {
        final String nonce = UUID.randomUUID().toString();
        final SharedBrowser target;
        final ExchangeLink link;
        final String dialCode;
        final String phone;
        String password;
        final long deadline = SystemClock.elapsedRealtime() + 20000;
        boolean checking;
        boolean waitingForUser;
        boolean loginRouteRequested;
        long nextCheck;
        JSONObject submittedCredentials;

        SignInAttempt(SharedBrowser target, ExchangeLink link, String dialCode, String phone, String password) {
            this.target = target;
            this.link = link;
            this.dialCode = dialCode;
            this.phone = phone;
            this.password = password;
        }
    }

    boolean hasSavedSignIn() {
        return credentialVault.load(exchangeLink) != null;
    }

    void beginSignIn(String dialCode, String phone, String password) {
        if (!dialCode.matches("\\+[1-9][0-9]{0,3}") || !phone.matches("[0-9]{6,18}")
                || password.isEmpty() || password.length() > 256) throw new IllegalArgumentException();
        requireChangeable();
        if (manualControl) throw new IllegalStateException();
        if (browser == null) createBrowser();
        if (browser == null) throw new IllegalStateException();
        cancelBeforeConfirmation();
        cancelPairCheck();
        credentialPromptPending = false;
        signInHelp = null;
        signInHelpShown = false;
        attemptedSavedSignIn = true;
        signInAttempt = new SignInAttempt(browser, exchangeLink, dialCode, phone, password);
        if (!mainFrameFailed) openSignInRoute(signInAttempt);
        wakeWorker();
    }

    private void openSignInRoute(SignInAttempt attempt) {
        attempt.loginRouteRequested = true;
        invalidateSession();
        attempt.target.loadUrl(attempt.link.signInRoute());
    }

    boolean signInPending() {
        return signInAttempt != null && !signInAttempt.waitingForUser;
    }

    boolean takeCredentialPrompt() {
        if (!credentialPromptPending || session != SessionState.SIGNED_OUT) return false;
        credentialPromptPending = false;
        return true;
    }

    String takeSignInHelp() {
        if (signInHelp == null || signInHelpShown) return null;
        signInHelpShown = true;
        return localize(this, signInHelp);
    }

    boolean isVerificationPrefilled() {
        if (signInAttempt == null || signInAttempt.target != browser || !VERIFICATION_PREFILLED.equals(signInHelp)
                || session == SessionState.SIGNED_IN || browser.getUrl() == null) return false;
        Uri uri = Uri.parse(browser.getUrl());
        String route = uri.getFragment();
        return naceOrigin(uri) && route != null && (route.equals("/login") || route.startsWith("/login?"));
    }

    String verificationPrefilledCaption() {
        return localize(this, VERIFICATION_PREFILLED);
    }

    void scrollViewerToVerification() {
        if (manualControl && isVerificationPrefilled()) {
            browser.evaluateJavascript(NaceSignInScript.scrollToVerification(exchangeLink.origin), null);
        }
    }

    private void requireSignInHelp(SignInAttempt attempt, String issue) {
        if (attempt != signInAttempt) return;
        attempt.waitingForUser = true;
        attempt.password = null;
        signInHelp = issue;
        signInHelpShown = false;
    }

    private void checkSignIn(long elapsed) {
        if (signInAttempt == null && !attemptedSavedSignIn && !manualControl
                && session == SessionState.SIGNED_OUT && networkAvailable() && !verifyingSubmittedTrade()) {
            attemptedSavedSignIn = true;
            CredentialVault.Credentials saved = credentialVault.load(exchangeLink);
            if (saved == null) {
                credentialPromptPending = true;
            } else {
                String[] number = saved.phoneNumber.split("\\|", 2);
                if (number.length == 2 && !watchPairChecking && !workflowRequest) {
                    beginSignIn(number[0], number[1], saved.password);
                } else if (number.length == 2) {
                    attemptedSavedSignIn = false;
                }
            }
        }
        SignInAttempt attempt = signInAttempt;
        if (attempt == null || attempt.target != browser || attempt.checking || mainFrameFailed
                || elapsed < attempt.nextCheck || !networkAvailable()) return;
        if (session == SessionState.SIGNED_IN) {
            finishSignIn(attempt);
            return;
        }
        if (!attempt.loginRouteRequested) {
            if (session == SessionState.SIGNED_OUT) openSignInRoute(attempt);
            return;
        }
        if (!attempt.waitingForUser && elapsed >= attempt.deadline) {
            requireSignInHelp(attempt, "Check your login information and try again.");
        }
        String url = browser.getUrl();
        if (url == null || !naceOrigin(Uri.parse(url))) return;
        if (attempt.waitingForUser && !manualControl) return;
        boolean fill = attempt.password != null && !attempt.waitingForUser && !manualControl;
        attempt.checking = true;
        attempt.nextCheck = elapsed + 1000;
        String script = NaceSignInScript.prepare(attempt.link.origin, attempt.nonce, attempt.dialCode,
                fill ? attempt.phone : "", fill ? attempt.password : "", fill);
        attempt.target.evaluateJavascript(script, raw -> {
            if (signInAttempt != attempt || attempt.target != browser) return;
            attempt.checking = false;
            Object result = decode(raw);
            if ("submitted".equals(result)) {
                attempt.password = null;
                lastProbeAt = 0;
            } else if ("verification_prefilled".equals(result) && !attempt.waitingForUser) {
                requireSignInHelp(attempt, VERIFICATION_PREFILLED);
            } else if ("verification".equals(result) && !attempt.waitingForUser) {
                requireSignInHelp(attempt, "Complete the verification step on the NACE login page.");
            }
        });
    }

    private void finishSignIn(SignInAttempt attempt) {
        if (attempt.submittedCredentials != null) {
            rememberVerifiedSignIn(attempt);
            return;
        }
        attempt.checking = true;
        attempt.target.evaluateJavascript(NaceSignInScript.takeSubmitted(attempt.link.origin, attempt.nonce), raw -> {
            if (signInAttempt != attempt || attempt.target != browser) return;
            attempt.checking = false;
            Object value = decode(raw);
            if (value instanceof JSONObject) attempt.submittedCredentials = (JSONObject) value;
            if (session == SessionState.SIGNED_IN) rememberVerifiedSignIn(attempt);
        });
    }

    private void rememberVerifiedSignIn(SignInAttempt attempt) {
        signInAttempt = null;
        attemptedSavedSignIn = false;
        signInHelp = null;
        signInHelpShown = false;
        JSONObject submitted = attempt.submittedCredentials;
        attempt.submittedCredentials = null;
        if (submitted == null || exchangeLink != attempt.link) return;
        String number = submitted.optString("phone");
        String password = submitted.optString("password");
        if (number.matches("\\+[1-9][0-9]{0,3}\\|[0-9]{6,18}") && !password.isEmpty()
                && password.length() <= 256 && !credentialVault.saveVerified(exchangeLink, number, password)) {
            signInHelp = "Signed in, but the login could not be saved on this phone.";
        }
    }

    public String getPair() {
        return pair;
    }

    public void setPair(String choice) {
        NaceWebScript.instrumentId(choice);
        if (pair.equals(choice)) return;
        DiagnosticLog.log(this, "pair", "user switched from " + pair + " to " + choice);
        requireChangeable();
        Instant changedAt = Instant.now();
        reconcileMissedWindows(changedAt);
        if (!preferences.edit().putString("pair", choice)
                .putLong(HISTORY_CHECKPOINT, changedAt.toEpochMilli()).commit()) {
            throw new IllegalStateException(localize(this, "The selected pair could not be saved."));
        }
        cancelBeforeConfirmation();
        pair = choice;
        wakeWorker();
        cancelPairCheck();
        if (!manualControl && browser != null) {
            navigateToPair(choice, false);
        }
    }

    public boolean shouldStartMonitoring() {
        return !automationStopped && !foreground;
    }

    boolean isAutomationStopped() {
        return automationStopped;
    }

    void setAutomationStopped(boolean stopped) {
        if (automationStopped == stopped) return;
        if (!stopped && !foreground) {
            startForegroundService(new Intent(this, AutomationService.class));
        }
        if (!preferences.edit().putBoolean(AUTOMATION_STOPPED, stopped)
                .putLong(HISTORY_CHECKPOINT, Instant.now().toEpochMilli()).commit()) {
            throw new IllegalStateException(localize(this, "The automation setting could not be saved."));
        }
        automationStopped = stopped;
        if (stopped) {
            trackedWindow = null;
            cancelBeforeConfirmation();
            blockerAlert.clear();
        }
        cancelPairCheck();
        watchPairPending = viewerOpen && !manualControl;
        preflightWindow = null;
        preflightPairReady = false;
        preflightIssue = null;
        refreshWakeup();
        stopForegroundIfIdle();
        wakeWorker();
    }

    private boolean verifyingSubmittedTrade() {
        return activeRun != null && (finalConfirmationBrowser != null || activeRun.confirmationSent);
    }

    private void stopForegroundIfIdle() {
        if (!automationStopped || verifyingSubmittedTrade() || !foreground) return;
        foreground = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    public WebView openViewer(boolean takeControl) {
        if (takeControl) requireChangeable();
        if (browser == null) createBrowser();
        if (browser == null) throw new IllegalStateException(localize(this, "Android could not open the browser."));
        if (takeControl) {
            cancelBeforeConfirmation();
            if (sameWindow(trackedWindow, currentOccurrence())) trackedWindowLive = false;
        }
        viewerOpen = true;
        manualControl = takeControl;
        cancelPairCheck();
        watchPairPending = !takeControl;
        watchPairConfirmed = false;
        watchPairRetries = 0;
        DiagnosticLog.log(this, "viewer", (takeControl ? "CONTROL" : "WATCH") + " opened (session "
                + effectiveSession(SystemClock.elapsedRealtime()) + ", warm-up pair ready " + preflightPairReady + ")");
        preflightPairReady = false;
        detach(browser);
        browser.readOnly = !takeControl;
        browser.setFocusable(takeControl);
        browser.setFocusableInTouchMode(takeControl);
        browser.setImportantForAccessibility(
                takeControl ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        browser.onResume();
        focusWatchPairWhenSafe();
        wakeWorker();
        return browser;
    }

    public void closeViewer() {
        if (manualControl) {
            if (trackedWindow != null && !sameWindow(trackedWindow, currentOccurrence())) closeTrackedWindow();
            trackedWindowLive = true;
            preflightWindow = null;
        }
        if (browser != null) detach(browser);
        viewerOpen = false;
        manualControl = false;
        watchPairPending = false;
        watchPairConfirmed = false;
        cancelPairCheck();
        if (browser != null) {
            browser.readOnly = true;
            browser.setFocusable(false);
            ensureOffscreenHost();
        }
        lastProbeAt = 0;
        wakeWorker();
    }

    public Snapshot getSnapshot() {
        long elapsed = SystemClock.elapsedRealtime();
        boolean hasBackground = !automationStopped
                && (parked ? foreground && WindowWakeups.canSchedule(this) : backgroundReady(elapsed));
        SessionState currentSession = effectiveSession(elapsed);
        String issue = automationIssue(elapsed, currentOccurrence());
        List<RunResult> newest = new ArrayList<>();
        for (RunResult result : results.values()) {
            newest.add(new RunResult(result.pair, result.date, result.start, result.end,
                    result.status, displayMessage(result.message), result.signalId, result.zone));
        }
        newest.sort(Comparator.comparing((RunResult result) -> result.occurrence().from.toInstant()).reversed());
        return new Snapshot(
                 !parked && issue == null, hasBackground, manualControl, currentSession,
                currentSession == SessionState.SIGNED_IN ? balance : null,
                currentSession == SessionState.SIGNED_IN && currentBalanceDay().equals(balanceDay)
                        ? dailyBalanceChange : null, pair,
                lastCheck == null ? null : DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
                        .withZone(ZoneId.systemDefault()).format(lastCheck),
                 displayMessage(issue == null ? message : issue), getWindows(), Collections.unmodifiableList(newest));
    }

    private boolean dashboardVisible;
    private boolean parked;
    private boolean earlyAlarmReceived;
    private boolean browserCookiesFlushed;

    void setDashboardVisible(boolean visible) {
        if (visible && !dashboardVisible && foreground) reconcileMissedWindows(Instant.now());
        dashboardVisible = visible;
        wakeWorker();
    }

    private void wakeWorker() {
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    private boolean scheduledWorkDue() {
        if (automationStopped) return foreground && verifyingSubmittedTrade();
        if (foreground && healthCheckActive()) return true;
        ZonedDateTime now = ZonedDateTime.now(scheduleZone);
        DailySchedule.Occurrence next = nextScheduledWindow();
        // The OnePlus can batch an alarm before its requested time as well as after it.
        long warmupLead = WindowWakeups.WORKER_WAKE_LEAD_SECONDS + (earlyAlarmReceived ? 180 : 0);
        boolean warmupDue = next != null && !next.from.isAfter(now.plusSeconds(warmupLead));
        return foreground && configurationValid && (currentOccurrence() != null
                || warmupDue || trackedWindow != null || activeRun != null
                || finalConfirmationBrowser != null);
    }

    private boolean workerNeeded() {
        return dashboardVisible || viewerOpen || scheduledWorkDue();
    }

    private static final long HEALTH_CHECK_MS = 180_000;
    private long healthCheckUntil;
    private final Runnable healthVerdict = this::finishHealthCheck;

    private boolean healthCheckActive() {
        return healthCheckUntil != 0 && SystemClock.elapsedRealtime() < healthCheckUntil;
    }

    /** About an hour before a window: wake up, check internet, NACE and login for three minutes. */
    private void startHealthCheck() {
        healthCheckUntil = SystemClock.elapsedRealtime() + HEALTH_CHECK_MS;
        lastProbeAt = 0;
        handler.removeCallbacks(healthVerdict);
        handler.postDelayed(healthVerdict, HEALTH_CHECK_MS);
        DiagnosticLog.log(this, "health", "check started (about 1 hour before the next window)");
    }

    private void finishHealthCheck() {
        healthCheckUntil = 0;
        Attention problem = attention();
        DailySchedule.Occurrence next = nextScheduledWindow();
        if (problem != null && problem.kind != AttentionKind.OTHER && next != null && !automationStopped) {
            DiagnosticLog.log(this, "health", "PROBLEM before " + next.start + ": " + problem.issue);
            reportBlocker(next, pair, problem.issue);
        } else {
            DiagnosticLog.log(this, "health", problem == null ? "ok" : "ok for trading (note: " + problem.issue + ")");
        }
        wakeWorker();
    }

    /** Device state that can silently stop warnings or background work. */
    private void logEnvironment() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        DiagnosticLog.change(this, "notifications", manager != null && manager.areNotificationsEnabled()
                ? "enabled" : "DISABLED (warnings cannot be shown)");
        DiagnosticLog.change(this, "wake-ups", WindowWakeups.canSchedule(this)
                ? "allowed" : "NOT ALLOWED (scheduled runs cannot wake the phone)");
        android.os.PowerManager power = getSystemService(android.os.PowerManager.class);
        DiagnosticLog.change(this, "battery", power != null && power.isIgnoringBatteryOptimizations(getPackageName())
                ? "unrestricted" : "OPTIMIZED (Android may stop NACE in the background)");
        DiagnosticLog.change(this, "network", networkAvailable() ? "available" : "NO VALIDATED NETWORK");
    }

    /**
     * The problem the user has to fix now, or null. Transient states (still checking, background
     * still starting) are not reported, so the app does not show a popup for something that is
     * about to resolve itself.
     */
    Attention attention() {
        long elapsed = SystemClock.elapsedRealtime();
        if (automationStopped || manualControl) return null;
        String issue = readinessIssue(elapsed);
        if (issue == null) {
            if (blockerAlert.active() && failedTradeIssue != null) {
                return new Attention(AttentionKind.OTHER, localize(this, failedTradeIssue), failedTradeIssue);
            }
            return null;
        }
        AttentionKind kind;
        if (INTERNET_DOWN.equals(issue)) kind = AttentionKind.INTERNET;
        else if (EXCHANGE_DNS_ERROR.equals(issue) || EXCHANGE_UNAVAILABLE.equals(issue)) kind = AttentionKind.LINK;
        else if ("Use Control to sign in to NACE.".equals(issue)
                || (signInHelp != null && issue.equals(signInHelp))) kind = AttentionKind.LOGIN;
        else if ("Checking the NACE session.".equals(issue)
                || "Open NACE and check that the background service is active.".equals(issue)
                || WAKEUPS_REQUIRED.equals(issue)
                || "Control is open. Scheduled automation is paused.".equals(issue)) return null;
        else kind = AttentionKind.OTHER;
        return new Attention(kind, localize(this, issue), issue);
    }

    /** "Check Internet" in the app: test the connection now and re-check NACE right after. */
    void checkInternetNow(java.util.function.Consumer<Boolean> done) {
        internetProbe.check(online -> {
            DiagnosticLog.log(this, "internet", "check from app: " + (online ? "online" : "OFFLINE"));
            if (online) {
                internetAtPageFailure = null;
                lastProbeAt = 0;
                if (browser != null && mainFrameFailed) browser.reload();
            }
            wakeWorker();
            done.accept(online);
        });
    }

    /** While the app is open: a warning that was raised clears as soon as everything is ready again. */
    private void clearWarningWhenRecovered(long elapsed) {
        if (!blockerAlert.active() || failedTradeIssue != null || parked) return;
        if (currentOccurrence() != null || upcomingPreflight(ZonedDateTime.now(scheduleZone)) != null) return;
        if (readinessIssue(elapsed) != null || effectiveSession(elapsed) != SessionState.SIGNED_IN) return;
        DiagnosticLog.log(this, "warning", "cleared: ready again");
        clearAndAnnounceRecovery(null);
    }

    private void parkWorker() {
        earlyAlarmReceived = false;
        if (parked) return;
        parked = true;
        if (browser != null) {
            closeOffscreenHost();
            discardBrowser();
            browserCookiesFlushed = false;
            probeId++;
            probing = false;
            lastProbeAt = 0;
            lastVirtualDisplayAttempt = 0;
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private void tick() {
        stopForegroundIfIdle();
        if (!workerNeeded()) {
            parkWorker();
            updateWorkerNotification();
            return;
        }
        parked = false;
        if (dashboardVisible) clearWarningWhenRecovered(SystemClock.elapsedRealtime());
        if (scheduledWorkDue()) keepCpuAwake();
        else if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        long elapsed = SystemClock.elapsedRealtime();
        lastTick = elapsed;
        if (foreground && exactAlarmAvailable != WindowWakeups.canSchedule(this)) refreshWakeup();
        if (foreground || viewerOpen || dashboardVisible) {
            if (browser == null) createBrowser();
            if (!viewerOpen && (foreground || dashboardVisible) && elapsed - lastVirtualDisplayAttempt > 10000) {
                ensureOffscreenHost();
            }
            focusWatchPairWhenSafe();
            if (mainFrameFailed) recoverBrowserLoad(elapsed);
            else refreshSession(elapsed);
            checkSignIn(elapsed);
        }
        DailySchedule.Occurrence current = currentOccurrence();
        if (automationStopped) {
            updateWorkerNotification();
            if (verifyingSubmittedTrade()) advance(activeRun, current, elapsed);
            return;
        }
        if (!sameWindow(trackedWindow, current)) {
            closeTrackedWindow();
            if (foreground && configurationValid && current != null) reconcileMissedWindows(Instant.now());
        }
        trackedWindow = foreground && (configurationValid || manualControl) ? current : null;
        trackedWindowLive = configurationValid && !manualControl;
        if (activeRun == null && finalConfirmationBrowser == null) runPreflight(current, elapsed);
        updateWorkerNotification();
        if (manualControl) return;
        if (activeRun != null) {
            advance(activeRun, current, elapsed);
            return;
        }
        if (current == null || automationIssue(elapsed, current) != null) return;

        activeRun = new Run(current, pair);
        message = "Finding the copy trade for " + pair + ".";
    }

    private void closeTrackedWindow() {
        DailySchedule.Occurrence window = trackedWindow;
        trackedWindow = null;
        if (window == null || findResult(window) != null) return;
        Run closingRun = activeRun != null && sameWindow(activeRun.occurrence, window) ? activeRun : null;
        if (closingRun != null && closingRun.signalId != null) return;
        if (!trackedWindowLive) {
            record(window, pair, "manual", historyDetail("Skipped while manual control was open.", closingRun));
            return;
        }
        String issue = sameWindow(window, preflightWindow) ? preflightIssue : null;
        String detail = issue != null ? issue : closingRun != null && closingRun.stage == Stage.SIGNALS
                ? "No identifiable Copy Trading signal was available before the window ended."
                : "No verified confirmation before the interval ended.";
        if (record(window, pair, "skipped", historyDetail(detail, closingRun))) {
            notifyTradeResult(window, pair, "skipped", detail, null);
            outcomeSpeaker.speak(false);
        }
    }

    private void runPreflight(DailySchedule.Occurrence current, long elapsed) {
        if (!foreground) return;
        ZonedDateTime now = ZonedDateTime.now(scheduleZone);
        DailySchedule.Occurrence target = current == null || hasWindowBlock(current) ? upcomingPreflight(now) : current;
        if (target == null || hasWindowBlock(target)) {
            showTradeFailureOrClear(current);
            return;
        }
        boolean finalCheck = !now.isBefore(target.from.minusSeconds(FINAL_PREFLIGHT_LEAD_SECONDS));
        if (!sameWindow(target, preflightWindow) || finalCheck != finalPreflight || !pair.equals(preflightPair)) {
            preflightWindow = target;
            preflightPair = pair;
            finalPreflight = finalCheck;
            preflightPairReady = false;
            preflightIssue = null;
            preflightStartedAt = elapsed;
            DiagnosticLog.log(this, "preflight", (finalCheck ? "final check" : "warm-up") + " for "
                    + target.date + " " + target.start + " " + pair);
            logEnvironment();
            preflightRequiredProbe = probeId + 1;
            nextPairCheckAt = 0;
            lastProbeAt = 0;
        }
        if (!sameWindow(target, preflightInternetWindow) || preflightInternetFinal != finalCheck) {
            preflightInternetWindow = target;
            preflightInternetFinal = finalCheck;
            preflightInternet = null;
            preflightInternetCheckedAt = 0;
            int check = ++preflightInternetGeneration;
            internetProbe.check(online -> {
                if (check != preflightInternetGeneration) return;
                DiagnosticLog.change(this, "internet", online ? "online" : "OFFLINE (probe failed)");
                preflightInternet = online;
                preflightInternetCheckedAt = SystemClock.elapsedRealtime();
                wakeWorker();
            });
        }
        String blocker = readinessIssue(elapsed);
        if (blocker != null) {
            preflightPairReady = false;
            preflightIssue = blocker;
            if (!"Checking the NACE session.".equals(blocker)
                    || elapsed - preflightStartedAt >= PROBE_TIMEOUT_MS) {
                notifyPreflightIssue(blocker);
            }
            return;
        }
        if (preflightInternet == null) return;
        if (verifiedProbeId < preflightRequiredProbe) return;
        if (preflightPairReady) {
            preflightIssue = null;
            clearAndAnnounceRecovery(current);
            announceOnlineBeforeWindow(target, now, current);
            return;
        }
        if (watchPairChecking || elapsed < nextPairCheckAt) return;
        nextPairCheckAt = elapsed + 10000;
        long checkpoint = preflightStartedAt;
        startPairCheck(false, confirmed -> {
            if (checkpoint != preflightStartedAt || !sameWindow(target, preflightWindow)
                    || readinessIssue(SystemClock.elapsedRealtime()) != null) return;
            preflightPairReady = confirmed;
            preflightIssue = confirmed ? null : "Could not verify the selected pair. Open Watch to check it.";
            if (confirmed) clearAndAnnounceRecovery(currentOccurrence());
            else notifyPreflightIssue(preflightIssue);
        });
    }

    private DailySchedule.Occurrence upcomingPreflight(ZonedDateTime now) {
        for (LocalDate date : Arrays.asList(now.toLocalDate(), now.toLocalDate().plusDays(1))) {
            for (TradingWindow window : windows) {
                DailySchedule.Occurrence candidate = DailySchedule.occurrence(date, window.startTime, window.endTime, scheduleZone);
                if (candidate.from.isAfter(now) && !candidate.from.isAfter(now.plusSeconds(PREFLIGHT_LEAD_SECONDS))) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private void advance(Run run, DailySchedule.Occurrence current, long elapsed) {
        if (workflowRequest || watchPairChecking || elapsed < run.nextStep) return;
        String blocker = run.stage == Stage.OBSERVE ? null : beforeConfirmationBlocker(run, current, elapsed);
        if (blocker != null) {
            if (sameWindow(run.occurrence, current)) notifyPreflightIssue(blocker);
            finish(run, run.reserved ? "confirmation_tapped" : "skipped",
                    run.reserved ? "Confirmation outcome unknown. This signal will not be retried."
                            : blocker);
            return;
        }
        if ((run.stage == Stage.OPEN || run.stage == Stage.DIALOG) && elapsed >= run.signalOpenDeadline) {
            finish(run, "failed", "The signal's confirmation dialog could not be opened.");
            return;
        }
        if (run.stage == Stage.OBSERVE && elapsed >= run.responseDeadline) {
            finish(run, "confirmation_tapped", "Confirm was sent, but the Order tab did not verify it within 30 seconds.");
            return;
        }
        if (run.stage == Stage.CONFIRM && run.confirmRetryDeadline != 0
                && elapsed >= run.confirmRetryDeadline) {
            finish(run, "failed", "The Confirm button could not be used before the retry period ended.");
            return;
        }
        if (browser == null) return;
        if (run.stage != Stage.OBSERVE && effectiveSession(elapsed) != SessionState.SIGNED_IN) {
            run.lastCheckDetail = readinessIssue(elapsed);
            if (run.lastCheckDetail != null && !signInPending()) notifyPreflightIssue(run.lastCheckDetail);
            return;
        }
        String action;
        switch (run.stage) {
            case TAB: action = "tab"; break;
            case SIGNALS: action = "signals"; break;
            case OPEN: action = "open"; break;
            case DIALOG: action = "dialog"; break;
            case CONFIRM:
                if (!liveAccountVerified || manualControl) {
                    finish(run, "skipped", manualControl ? "Control is open. Scheduled automation is paused."
                            : "NACE has not verified a live account.");
                    return;
                }
                if (verifiedProbeId < run.requiredProbe) {
                    run.lastCheckDetail = "Waiting for a fresh sign-in check before Confirm.";
                    return;
                }
                if (elapsed - verifiedAt >= CONFIRM_PROOF_FRESHNESS_MS) {
                    requireFreshProbe(run);
                    return;
                }
                if (!run.reserved) {
                    if (run.signalId == null || hasWindowBlock(run.occurrence)
                            || hasSignalResult(run.occurrence, run.pair, run.signalId)) {
                        activeRun = null;
                        return;
                    }
                    if (!recordSignal(run, "pending", "Confirmation is being checked.")) {
                        finish(run, "failed", "The confirmation could not be safely reserved.");
                        return;
                    }
                    run.reserved = true;
                }
                action = "confirm";
                break;
            default: action = "observe";
        }
        workflowRequest = true;
        long request = ++workflowSequence;
        SharedBrowser target = browser;
        if (run.stage == Stage.CONFIRM) {
            finalConfirmationBrowser = target;
            finalConfirmationRequest = request;
        }
        handler.postDelayed(() -> {
            if (activeRun == run && workflowSequence == request && workflowRequest) {
                finish(run, run.reserved ? "confirmation_tapped" : "failed",
                        run.reserved ? "The browser stopped responding. The confirmation will not retry."
                                : "The browser stopped responding before the confirmation.");
                if (isFinalConfirmation(target, request)) terminateQuarantinedRenderer(target);
            }
        }, WORKFLOW_CALLBACK_TIMEOUT_MS);
        target.evaluateJavascript(NaceWebScript.workflow(
                exchangeLink.origin, action, run.pair, run.nonce, run.occurrence.from.toInstant().toEpochMilli(),
                run.occurrence.until.toInstant().toEpochMilli(), verifiedProbeId, run.signalId),
                raw -> {
                    boolean acknowledgedConfirmation = isFinalConfirmation(target, request);
                    if (acknowledgedConfirmation) finalConfirmationBrowser = null;
                    Object parsed = decode(raw);
                    if (activeRun != run || target != browser || workflowSequence != request) {
                        if (acknowledgedConfirmation && "confirmation_tapped".equals(parsed)) {
                            recordSignal(run, "confirmation_tapped",
                                    historyDetail("The previously timed-out confirm tap was sent. NACE did not verify submission.", run));
                        }
                        return;
                    }
                    workflowRequest = false;
                    if (run.stage == Stage.SIGNALS && parsed instanceof JSONArray) {
                        selectSignal(run, (JSONArray) parsed);
                    } else {
                        handleWorkflowResult(run, parsed instanceof String ? (String) parsed : null);
                    }
                });
    }

    private String beforeConfirmationBlocker(Run run, DailySchedule.Occurrence current, long elapsed) {
        if (!sameWindow(run.occurrence, current)) return "The scheduled window ended before Confirm was sent.";
        if (!pair.equals(run.pair)) return "The selected pair changed before Confirm was sent.";
        if (!WindowWakeups.canSchedule(this)) return WAKEUPS_REQUIRED;
        if (!configurationValid) return "The saved schedule or history could not be verified.";
        if (!foreground || wakeLock == null || !wakeLock.isHeld()) return "The background service stopped before Confirm was sent.";
        if (!backgroundReady(elapsed)) return "The browser was unavailable in the background before Confirm was sent.";
        return null;
    }

    private boolean isFinalConfirmation(SharedBrowser target, long request) {
        return finalConfirmationBrowser == target && finalConfirmationRequest == request;
    }

    private void terminateQuarantinedRenderer(SharedBrowser target) {
        if (Build.VERSION.SDK_INT < 29) return;
        try {
            android.webkit.WebViewRenderProcess renderer = target.getWebViewRenderProcess();
            if (renderer != null) renderer.terminate();
        } catch (RuntimeException ignored) {
            // Only the callback or confirmed renderer exit releases the browser quarantine.
        }
    }

    private void requireFreshProbe(Run run) {
        run.requiredProbe = probeId + 1;
        lastProbeAt = 0;
    }

    private void refreshWakeup() {
        exactAlarmAvailable = WindowWakeups.canSchedule(this);
        WindowWakeups.schedule(this, !automationStopped && foreground && configurationValid, windows, scheduleZone);
    }

    private void handleWorkflowResult(Run run, String result) {
        run.nextStep = SystemClock.elapsedRealtime() + 300;
        String detail = browserCheckDetail(result);
        if (detail != null) run.lastCheckDetail = detail;
        if (run.stage == Stage.SIGNALS) {
            waitForMoreSignals(run);
            return;
        }
        if (run.stage == Stage.CONFIRM && !"confirmation_tapped".equals(result)) {
            if ("recheck".equals(result) || "success_toast_visible".equals(result)
                    || "confirm_button_blocked".equals(result) || "confirm_button_missing".equals(result)
                    || "confirm_amount_invalid".equals(result)) {
                retryKnownUntappedConfirmation(run, result);
                return;
            }
            boolean safeFailure = Arrays.asList("verify", "recheck", "waiting", "invalid", "stale_dialog",
                    "dialog_missing").contains(result);
            finish(run, safeFailure ? "failed" : "confirmation_tapped", safeFailure
                    ? "The pre-confirmation check failed. This signal will not be retried."
                    : "Confirmation outcome unknown. This signal will not be retried.");
            return;
        }
        if (result == null) return;
        switch (result) {
            case "tab":
                run.stage = Stage.SIGNALS;
                run.nextSignalRefreshAt = SystemClock.elapsedRealtime() + 10000;
                run.lastCheckDetail = "Copy Trading List opened.";
                break;
            case "first_tap":
                run.stage = Stage.DIALOG;
                run.lastCheckDetail = "The Copy Trading Information button was tapped.";
                break;
            case "dialog":
                requireFreshProbe(run);
                run.stage = Stage.CONFIRM;
                break;
            case "confirmation_tapped":
                run.confirmationSent = true;
                run.stage = Stage.OBSERVE;
                run.responseDeadline = SystemClock.elapsedRealtime() + CONFIRM_RESULT_TIMEOUT_MS;
                run.lastCheckDetail = "Confirm was tapped. Checking the Order tab.";
                recordSignal(run, "confirmation_tapped", "Confirm was tapped. Checking the Order tab.");
                break;
            case "submitted":
                run.lastCheckDetail = null;
                finish(run, "submitted", "An order was visible in the Order tab after Confirm.");
                break;
            case "invalid":
            case "stale_dialog":
                if (run.stage == Stage.TAB && !run.attemptedRecovery) {
                    run.attemptedRecovery = true;
                    navigateToPair(run.pair, true);
                } else {
                    finish(run, "failed", "The expected copy-order controls changed.");
                }
                break;
            default:
                // The expected page or control has not appeared yet. Recheck within the interval.
        }
    }

    private void retryKnownUntappedConfirmation(Run run, String result) {
        // These browser replies are returned before element.click(). Timeouts and unknown replies
        // retain their reservation so a restart never blindly repeats a possible submission.
        LinkedHashMap<String, RunResult> updated = new LinkedHashMap<>(results);
        updated.remove(signalResultKey(run.occurrence, run.pair, run.signalId));
        if (!persistResults(updated, preferences.edit())) {
            finish(run, "failed", "The confirmation could not be safely reserved.");
            return;
        }
        run.reserved = false;
        if (automationStopped) {
            cancelBeforeConfirmation();
            stopForegroundIfIdle();
            return;
        }
        if (run.confirmRetryDeadline == 0) {
            run.confirmRetryDeadline = SystemClock.elapsedRealtime() + CONFIRM_RETRY_MS;
        }
        if ("recheck".equals(result)) requireFreshProbe(run);
    }

    private void selectSignal(Run run, JSONArray available) {
        for (int index = 0; index < available.length(); index++) {
            String signalId = available.optString(index, "");
            if (validSignalId(signalId) && !hasSignalResult(run.occurrence, run.pair, signalId)) {
                run.signalId = signalId;
                run.stage = Stage.OPEN;
                run.signalOpenDeadline = SystemClock.elapsedRealtime() + 15000;
                run.lastCheckDetail = "An eligible copy-trading signal was identified.";
                return;
            }
        }
        if (available.length() == 0) run.lastCheckDetail = "The Copy Trading List had no eligible entries.";
        waitForMoreSignals(run);
    }

    private void waitForMoreSignals(Run run) {
        long now = SystemClock.elapsedRealtime();
        if (now >= run.nextSignalRefreshAt) run.stage = Stage.TAB;
        run.nextStep = now + 3000;
    }

    private void finish(Run run, String status, String resultMessage) {
        if (activeRun != run) return;
        activeRun = null;
        workflowRequest = false;
        if (run.stage == Stage.OBSERVE && viewerOpen && !manualControl) watchPairPending = true;
        if (run.signalId == null && findResult(run.occurrence) != null) return;
        if (recordSignal(run, status, historyDetail(resultMessage, run))) {
            notifyTradeResult(run.occurrence, run.pair, status, resultMessage, run.signalId);
            if ("submitted".equals(status)) {
                failedTradeWindow = null;
                failedTradeIssue = null;
                outcomeSpeaker.speak(true);
            } else if ("failed".equals(status) || ("skipped".equals(status) && !manualControl)) {
                // "Trade failed" first, then the spoken WARNING with the reason (from reportBlocker).
                outcomeSpeaker.speak(false);
                if (!automationStopped && sameWindow(run.occurrence, currentOccurrence())) {
                    failedTradeWindow = run.occurrence;
                    failedTradePair = run.pair;
                    failedTradeIssue = resultMessage;
                    reportBlocker(run.occurrence, run.pair, resultMessage);
                }
            }
        }
        message = resultMessage;
    }

    private String historyDetail(String reason, Run run) {
        String detail = reason;
        if (run != null) {
            detail += "\n" + STEP_PREFIX + run.stage.name();
            if (run.lastCheckDetail != null && !reason.equals(run.lastCheckDetail)) {
                detail += "\n" + CHECK_PREFIX + run.lastCheckDetail;
            }
        }
        return detail + "\n" + TIME_PREFIX
                + DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).format(ZonedDateTime.now(DailySchedule.ZONE));
    }

    private void notifyTradeResult(DailySchedule.Occurrence occurrence, String tradePair, String status, String detail, String signalId) {
        String key = occurrence.date + "/" + occurrence.start + "/" + occurrence.end;
        String window = occurrence.displayLabel(ZoneId.systemDefault());
        if (signalId != null) key += "/" + tradePair + "/" + signalId;
        TradeResultNotification.post(this, key, tradePair, window, status, detail);
    }

    private void requireChangeable() {
        if (finalConfirmationBrowser != null || (activeRun != null && activeRun.confirmationSent)) {
            throw new IllegalStateException(localize(this, "Wait for the confirmation result before changing controls."));
        }
        if (workflowRequest) {
            throw new IllegalStateException(localize(this, "Wait for the browser to finish its current step."));
        }
    }

    private void cancelBeforeConfirmation() {
        Run run = activeRun;
        if (run == null || finalConfirmationBrowser != null || run.confirmationSent) return;
        String nonce = run.nonce;
        if (run.reserved) finish(run, "skipped", "Automation paused before a confirm tap was sent.");
        else {
            activeRun = null;
            workflowRequest = false;
        }
        if (browser != null) browser.evaluateJavascript(NaceWebScript.cancelRun(nonce), null);
    }

    private boolean backgroundReady(long elapsed) {
        return foreground && (!scheduledWorkDue() || wakeLock != null && wakeLock.isHeld())
                && lastTick > 0 && elapsed - lastTick <= TICK_MS * 4
                && browser != null && browser.isAttachedToWindow()
                && (viewerOpen || (presentation != null && presentation.isShowing()));
    }

    private SessionState effectiveSession(long elapsed) {
        if (!parked && session == SessionState.SIGNED_IN
                && (elapsed - verifiedAt > SESSION_FRESHNESS_MS || !networkAvailable())) {
            return SessionState.UNAVAILABLE;
        }
        return session;
    }

    private void refreshSession(long elapsed) {
        if (probing || browser == null || !browser.isAttachedToWindow()
                || (lastProbeAt != 0 && elapsed - lastProbeAt < SESSION_REFRESH_MS)) return;
        lastProbeAt = elapsed;
        if (!networkAvailable()) {
            setSession(SessionState.UNAVAILABLE, null, false);
            return;
        }
        if (!naceOrigin(Uri.parse(browser.getUrl() == null ? "" : browser.getUrl()))) return;
        probing = true;
        int challenge = ++probeId;
        SharedBrowser target = browser;
        long deadline = elapsed + PROBE_TIMEOUT_MS;
        handler.postDelayed(() -> {
            if (probing && probeId == challenge && browser == target) {
                probeId++;
                probing = false;
                setSession(SessionState.UNAVAILABLE, null, false);
            }
        }, PROBE_TIMEOUT_MS);
        target.evaluateJavascript(NaceWebScript.startProbe(exchangeLink.origin, challenge), response -> {
            if (probeId != challenge || browser != target) return;
            if (!"true".equals(response)) {
                probing = false;
                lastProbeAt = 0;
                return;
            }
            pollProbe(target, challenge, deadline);
        });
    }

    private void pollProbe(SharedBrowser target, int challenge, long timeout) {
        if (probeId != challenge || browser != target) return;
        if (SystemClock.elapsedRealtime() >= timeout) {
            probing = false;
            setSession(SessionState.UNAVAILABLE, null, false);
            return;
        }
        target.evaluateJavascript(NaceWebScript.readProbe(challenge), raw -> {
            if (probeId != challenge || browser != target) return;
            Object reply = decode(raw);
            if (!(reply instanceof JSONObject)) {
                handler.postDelayed(() -> pollProbe(target, challenge, timeout), 300);
                return;
            }
            JSONObject response = (JSONObject) reply;
            SessionState next;
            try {
                next = SessionState.valueOf(response.optString("session"));
            } catch (IllegalArgumentException error) {
                next = SessionState.UNAVAILABLE;
            }
            probing = false;
            setSession(next, response.optString("balance", null), response.optBoolean("liveAccount"));
            if (next == SessionState.SIGNED_IN) verifiedProbeId = challenge;
        });
    }

    private void setSession(SessionState next, String amount, boolean realAccount) {
        DiagnosticLog.change(this, "session", next.name());
        session = next;
        lastCheck = Instant.now();
        verifiedAt = next == SessionState.SIGNED_IN ? SystemClock.elapsedRealtime() : 0;
        liveAccountVerified = next == SessionState.SIGNED_IN && realAccount;
        if (liveAccountVerified && !browserCookiesFlushed) {
            browserCookiesFlushed = true;
            // CookieManager.flush can block on I/O. Keep it off the worker's main thread,
            // especially at the end of the window when Android can return to deep idle.
            CookieManager cookies = CookieManager.getInstance();
            Thread flush = new Thread(cookies::flush, "nace-cookie-save");
            flush.start();
        }
        if (next == SessionState.SIGNED_IN) {
            credentialPromptPending = false;
            if (signInAttempt == null) attemptedSavedSignIn = false;
            mainFrameFailed = false;
            reloadDelay = MIN_RELOAD_DELAY_MS;
        }
        BigDecimal verifiedBalance = liveAccountVerified ? parseBalance(amount) : null;
        balance = verifiedBalance == null ? null : verifiedBalance.toBigInteger() + " USDT";
        dailyBalanceChange = verifiedBalance == null ? null : updateDailyBalance(verifiedBalance);
    }

    private void invalidateSession() {
        probeId++;
        probing = false;
        lastProbeAt = 0;
        session = SessionState.CHECKING;
        balance = null;
        dailyBalanceChange = null;
        verifiedAt = 0;
        verifiedProbeId = 0;
        liveAccountVerified = false;
    }

    private boolean networkAvailable() {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        Network network = manager == null ? null : manager.getActiveNetwork();
        NetworkCapabilities capabilities = network == null ? null : manager.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private static String currentBalanceDay() {
        return LocalDate.now(DailySchedule.ZONE).toString();
    }

    private BigDecimal updateDailyBalance(BigDecimal current) {
        String today = currentBalanceDay();
        if (!today.equals(balanceDay) || dayOpenBalance == null) {
            if (!preferences.edit().putString(BALANCE_DAY, today)
                    .putString(DAY_OPEN_BALANCE, current.toPlainString()).commit()) return null;
            balanceDay = today;
            dayOpenBalance = current;
        }
        return current.subtract(dayOpenBalance).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal parseBalance(String amount) {
        if (amount == null || amount.trim().isEmpty()) return null;
        try {
            BigDecimal decimal = new BigDecimal(amount);
            if (decimal.signum() < 0 || decimal.precision() > 30 || Math.abs((long) decimal.scale()) > 12) return null;
            return decimal;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private void createBrowser() {
        if (browser != null) return;
        try {
            SharedBrowser created = new SharedBrowser(this, exchangeLink.origin);
            WebSettings settings = created.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            settings.setSupportMultipleWindows(false);
            settings.setJavaScriptCanOpenWindowsAutomatically(false);
            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(created, false);
            created.setWebViewClient(new WebViewClient() {
                private String documentUrl;

                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    if (!request.isForMainFrame() || naceOrigin(request.getUrl())) return false;
                    if (view == browser && request.isRedirect()) markBrowserLoadFailed(false);
                    return true;
                }

                @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                    if (view != browser) return;
                    documentUrl = url;
                    invalidateSession();
                }

                @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
                    if (!java.util.Objects.equals(documentUrl, url)) {
                        documentUrl = url;
                        invalidateSession();
                    }
                }

                @Override public void onPageFinished(WebView view, String url) {
                    if (view != browser || !java.util.Objects.equals(documentUrl, url)) return;
                    if (!mainFrameFailed && naceOrigin(Uri.parse(url))) resetExchangeLoadFailures();
                    lastProbeAt = 0;
                }

                @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    if (request.isForMainFrame() && view == browser) {
                        markBrowserLoadFailed(error.getErrorCode() == WebViewClient.ERROR_HOST_LOOKUP);
                    }
                }

                @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse error) {
                    if (request.isForMainFrame() && view == browser) markBrowserLoadFailed(false);
                }

                @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                    DiagnosticLog.log(AutomationService.this, "browser", "Android browser renderer stopped"
                            + (detail != null && detail.didCrash() ? " (crash)" : " (killed for memory)"));
                    if (view != browser && view != finalConfirmationBrowser) {
                        // An old, already replaced browser: do not tear down the live one.
                        view.destroy();
                        return true;
                    }
                    if (finalConfirmationBrowser == view) finalConfirmationBrowser = null;
                    if (activeRun != null) {
                        Run run = activeRun;
                        finish(run, run.reserved ? "confirmation_tapped" : "failed",
                                "The Android browser stopped before NACE verified the result.");
                    }
                    discardBrowser();
                    invalidateSession();
                    message = "The Android browser stopped. NACE is reconnecting.";
                    lastVirtualDisplayAttempt = 0;
                    return true;
                }
            });
            mainFrameFailed = false;
            reloadDelay = MIN_RELOAD_DELAY_MS;
            browser = created;
            browser.loadUrl(exchangeLink.url);
        } catch (RuntimeException error) {
            message = "Android could not initialize the browser.";
            setSession(SessionState.UNAVAILABLE, null, false);
        }
    }

    private void markBrowserLoadFailed(boolean dnsFailed) {
        DiagnosticLog.log(this, "nace-link", dnsFailed ? "NACE address could not be resolved (DNS)"
                : "NACE page could not be loaded");
        if (mainFrameFailed) return;
        exchangeDnsFailed = dnsFailed;
        int generation = ++exchangeLoadGeneration;
        internetAtPageFailure = null;
        invalidateSession();
        setSession(SessionState.UNAVAILABLE, null, false);
        retryLoadAt = SystemClock.elapsedRealtime() + reloadDelay;
        mainFrameFailed = true;
        internetProbe.check(online -> {
            if (generation != exchangeLoadGeneration || !mainFrameFailed) return;
            internetAtPageFailure = online;
            if (online) lastOnlinePageFailureAt = SystemClock.elapsedRealtime();
            exchangeLoadFailures = online ? Math.min(LINK_FAILURE_LIMIT, exchangeLoadFailures + 1) : 0;
            wakeWorker();
        });
    }

    private void recoverBrowserLoad(long elapsed) {
        if (browser == null || internetAtPageFailure == null || !networkAvailable() || elapsed < retryLoadAt
                || workflowRequest || finalConfirmationBrowser != null
                || (activeRun != null && (activeRun.reserved || activeRun.confirmationSent))) return;
        cancelBeforeConfirmation();
        cancelPairCheck();
        watchPairPending = viewerOpen;
        watchPairConfirmed = false;
        preflightWindow = null;
        mainFrameFailed = false;
        reloadDelay = Math.min(MAX_RELOAD_DELAY_MS, reloadDelay * 2);
        invalidateSession();
        if (signInAttempt != null) signInAttempt.loginRouteRequested = false;
        browser.loadUrl(exchangeLink.url);
    }

    private void focusWatchPairWhenSafe() {
        if (!watchPairPending || !viewerOpen || manualControl || browser == null || !browser.isAttachedToWindow()
                || (activeRun != null && activeRun.stage != Stage.SIGNALS)
                || workflowRequest || finalConfirmationBrowser != null) return;
        SessionState current = effectiveSession(SystemClock.elapsedRealtime());
        if (current == SessionState.SIGNED_OUT) {
            watchPairPending = false;
            DiagnosticLog.log(this, "watch", "copy list not shown: signed out");
            return;
        }
        if (current != SessionState.SIGNED_IN) return;
        if (watchPairChecking) return;
        watchPairPending = false;
        startPairCheck(true, confirmed -> {
            watchPairConfirmed = confirmed;
            DiagnosticLog.log(this, "watch", confirmed ? "copy trading list shown"
                    : "copy trading list NOT shown within 25 s (retry " + watchPairRetries + ")");
            // The page can be left with the side panel open. Outside a trade window, reload it once and retry.
            if (!confirmed && viewerOpen && !manualControl && watchPairRetries < 1 && activeRun == null
                    && currentOccurrence() == null && !workflowRequest && finalConfirmationBrowser == null) {
                watchPairRetries++;
                navigateToPair(pair, true);
                watchPairPending = true;
                wakeWorker();
            }
        });
    }

    /** The page showed another pair: its own link was reloaded once during this check. */
    private boolean pairCheckReloaded;
    /** The site ignored the pair link: choose the pair in the site's own pair list (throttled). */
    private boolean pairCheckPanelAllowed;

    private void startPairCheck(boolean showCopyList, Consumer<Boolean> finished) {
        watchPairChecking = true;
        pairCheckReloaded = false;
        pairCheckPanelAllowed = false;
        pairCheckFinished = finished;
        SharedBrowser target = browser;
        String targetPair = pair;
        int check = ++watchPairCheck;
        String url = target.getUrl();
        DiagnosticLog.log(this, "pair", "check started (" + (showCopyList ? "watch" : "warm-up") + ", page "
                + (url == null ? "none" : Uri.parse(url).getFragment()) + ", width " + target.getWidth() + ")");
        String route = url == null ? null : Uri.parse(url).getFragment();
        if (route == null || !route.startsWith("/forward?") || !naceOrigin(Uri.parse(url))) {
            navigateToPair(targetPair, false);
        }
        // Room for opening the link and, if needed, choosing the pair in the site's list.
        handler.postDelayed(() -> finishWatchPairCheck(check, false), 25000);
        checkWatchPair(target, targetPair, check, false, showCopyList);
    }

    private void checkWatchPair(SharedBrowser target, String targetPair, int check, boolean selected, boolean showCopyList) {
        if (!watchPairChecking || check != watchPairCheck) return;
        if (browser != target || !pair.equals(targetPair) || session == SessionState.SIGNED_OUT) {
            finishWatchPairCheck(check, false);
            return;
        }
        // The page must always end up on the pair chosen in the app (WATCH included): first its own
        // link, and when the site ignores the link, the site's pair list. Never while a trade runs.
        boolean allowPanel = pairCheckPanelAllowed && !(showCopyList && activeRun != null);
        target.evaluateJavascript(NaceWebScript.selectWatchPair(exchangeLink.origin, targetPair, allowPanel, showCopyList), raw -> {
            if (!watchPairChecking || check != watchPairCheck) return;
            Object state = decode(raw);
            DiagnosticLog.change(this, "pairState", String.valueOf(state));
            if ("matched".equals(state)) {
                finishWatchPairCheck(check, true);
                return;
            }
            if ("mismatch-url".equals(state)) {
                if (!pairCheckReloaded) {
                    // The page is on another pair's address: open the selected pair's own link.
                    pairCheckReloaded = true;
                    if (!(showCopyList && activeRun != null)) {
                        DiagnosticLog.log(this, "pair", "page on another pair: opening the " + targetPair + " link");
                        navigateToPair(targetPair, true);
                    }
                } else if (!pairCheckPanelAllowed) {
                    pairCheckPanelAllowed = true;
                    DiagnosticLog.log(this, "pair", "link did not switch the page: selecting " + targetPair + " in the pair list");
                }
            } else if ("mismatch-header".equals(state) && !pairCheckPanelAllowed) {
                // Right address, but the site still shows another pair (it ignores the link): reloading
                // will not help, so select the pair in the site's pair list straight away.
                pairCheckPanelAllowed = true;
                DiagnosticLog.log(this, "pair", "site shows another pair: selecting " + targetPair + " in the pair list");
            }
            boolean selectionSent = selected || "selected".equals(state);
            handler.postDelayed(() -> checkWatchPair(target, targetPair, check, selectionSent, showCopyList), 650);
        });
    }

    private void finishWatchPairCheck(int check, boolean confirmed) {
        if (!watchPairChecking || check != watchPairCheck) return;
        watchPairChecking = false;
        Consumer<Boolean> finished = pairCheckFinished;
        pairCheckFinished = null;
        finished.accept(confirmed);
    }

    private void cancelPairCheck() {
        if (watchPairChecking) DiagnosticLog.log(this, "pair", "running check cancelled");
        watchPairCheck++;
        watchPairChecking = false;
        pairCheckFinished = null;
    }

    boolean isCheckingWatchPair() { return watchPairPending || watchPairChecking; }

    boolean isWatchPairConfirmed() { return watchPairConfirmed; }

    private void navigateToPair(String targetPair, boolean forceReload) {
        if (browser == null || manualControl) return;
        String desired = exchangeLink.tradeRoute(NaceWebScript.instrumentId(targetPair));
        if (desired.equals(browser.getUrl())) {
            if (forceReload) {
                mainFrameFailed = false;
                browser.reload();
            }
        } else {
            mainFrameFailed = false;
            browser.loadUrl(desired);
        }
    }

    private void ensureOffscreenHost() {
        if (viewerOpen || browser == null) return;
        if (renderRoot != null && presentation != null && presentation.isShowing()) {
            if (browser.getParent() != renderRoot) {
                detach(browser);
                renderRoot.addView(browser, fullSize());
            }
            browser.onResume();
            return;
        }
        lastVirtualDisplayAttempt = SystemClock.elapsedRealtime();
        closeOffscreenHost();
        try {
            DisplayManager manager = getSystemService(DisplayManager.class);
            if (manager == null) return;
            int density = getResources().getDisplayMetrics().densityDpi;
            int width = Math.max(360, 400 * density / 160);
            int height = Math.max(640, 840 * density / 160);
            renderSurface = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            renderSurface.setOnImageAvailableListener(reader -> {
                try (Image image = reader.acquireLatestImage()) {
                    // Draining the buffer allows the offscreen WebView to continue rendering.
                } catch (IllegalStateException ignored) {
                    // The display may already be closing.
                }
            }, handler);
            virtualDisplay = manager.createVirtualDisplay(
                    "NACE background browser", width, height, density, renderSurface.getSurface(),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                            | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
            if (virtualDisplay == null) throw new IllegalStateException(localize(this, "No Android virtual display available."));
            presentation = new Presentation(this, virtualDisplay.getDisplay());
            renderRoot = new FrameLayout(presentation.getContext());
            presentation.setContentView(renderRoot);
            presentation.show();
            detach(browser);
            renderRoot.addView(browser, fullSize());
            browser.onResume();
        } catch (RuntimeException error) {
            closeOffscreenHost();
            message = "Android could not keep the browser visible in the background.";
        }
    }

    private void closeOffscreenHost() {
        if (renderRoot != null && browser != null && browser.getParent() == renderRoot) detach(browser);
        if (presentation != null) {
            presentation.dismiss();
            presentation = null;
        }
        renderRoot = null;
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (renderSurface != null) {
            renderSurface.close();
            renderSurface = null;
        }
    }

    private void discardBrowser() {
        if (browser == null) return;
        SharedBrowser old = browser;
        browser = null;
        if (signInAttempt != null && signInAttempt.target == old) {
            signInAttempt = null;
            if (VERIFICATION_PREFILLED.equals(signInHelp)) {
                signInHelp = null;
                attemptedSavedSignIn = false;
            }
        }
        detach(old);
        old.destroy();
    }

    private static FrameLayout.LayoutParams fullSize() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static void detach(View view) {
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
    }

    private boolean naceOrigin(Uri uri) {
        return exchangeLink.matchesOrigin(uri);
    }

    private void keepCpuAwake() {
        PowerManager manager = getSystemService(PowerManager.class);
        if (manager == null) return;
        if (wakeLock == null) {
            wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nace-automation:scheduled-clicks");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) wakeLock.acquire(120000);
    }


    // Persisted history stays in English; translate only when presenting it.
    static String localize(Context context, String english) {
        if (english == null) return null;
        switch (english) {
            case "Open the browser to sign in to NACE.": return AppLanguage.text(context, english, "Deschideți browserul pentru a vă conecta la NACE.", "Abre el navegador para iniciar sesión en NACE.");
            case "Android could not keep automation active in the background.": return AppLanguage.text(context, english, "Android nu a putut menține automatizarea activă în fundal.", "Android no pudo mantener la automatización activa en segundo plano.");
            case "Live automation could not start. Check the browser status and reopen the app.": return AppLanguage.text(context, english, "Automatizarea reală nu a putut porni. Verificați starea browserului și redeschideți aplicația.", "No se pudo iniciar la automatización. Comprueba el estado del navegador y vuelve a abrir la aplicación.");
            case "Invalid scheduled intervals.": return AppLanguage.text(context, english, "Intervalele programate nu sunt valide.", "Los intervalos programados no son válidos.");
            case "The schedule could not be saved.": return AppLanguage.text(context, english, "Programul nu a putut fi salvat.", "No se pudo guardar el horario.");
            case "The selected pair could not be saved.": return AppLanguage.text(context, english, "Perechea selectată nu a putut fi salvată.", "No se pudo guardar el par seleccionado.");
            case "The automation setting could not be saved.": return AppLanguage.text(context, english, "Setarea automatizării nu a putut fi salvată.", "No se pudo guardar el ajuste de automatización.");
            case "Live automation enabled for the saved intervals.": return AppLanguage.text(context, english, "Automatizarea reală este activată pentru intervalele salvate.", "Automatización activada para los intervalos guardados.");
            case "Automation is paused.": return AppLanguage.text(context, english, "Automatizarea este în pauză.", "La automatización está en pausa.");
            case "New runs are paused. The confirmation already sent to the browser is still being checked.": return AppLanguage.text(context, english, "Rulările noi sunt în pauză. Confirmarea trimisă deja browserului este încă verificată.", "Las nuevas ejecuciones están en pausa. Se sigue comprobando la confirmación que ya se envió al navegador.");
            case "Android could not open the browser.": return AppLanguage.text(context, english, "Android nu a putut deschide browserul.", "Android no pudo abrir el navegador.");
            case "Control is open. Scheduled automation is paused.": return AppLanguage.text(context, english, "Control este deschis. Automatizarea programată este în pauză.", "El modo Control está abierto. La automatización programada está en pausa.");
            case "The background service is stopped.": return AppLanguage.text(context, english, "Serviciul din fundal este oprit.", "El servicio en segundo plano está detenido.");
            case "Android has not verified background operation.": return AppLanguage.text(context, english, "Android nu a verificat funcționarea în fundal.", "Android no ha verificado el funcionamiento en segundo plano.");
            case "The last confirmation is unresolved. Browser control is temporarily locked.": return AppLanguage.text(context, english, "Ultima confirmare este nerezolvată. Controlul browserului este blocat temporar.", "La última confirmación sigue pendiente. El control del navegador está bloqueado temporalmente.");
            case "Use Control to sign in to NACE.": return AppLanguage.text(context, english, "Folosiți Control pentru a vă conecta la NACE.", "Abre Control para iniciar sesión en NACE.");
            case "Checking the NACE session.": return AppLanguage.text(context, english, "Se verifică sesiunea NACE.", "Comprobando la sesión de NACE.");
            case "The NACE session could not be verified.": return AppLanguage.text(context, english, "Sesiunea NACE nu a putut fi verificată.", "No se pudo verificar la sesión de NACE.");
            case "Could not verify the selected pair. Open Watch to check it.": return AppLanguage.text(context, english, "Perechea selectată nu a putut fi verificată. Deschide Vizualizare pentru a o verifica.", "No se pudo verificar el par seleccionado. Abre Visualizar para comprobarlo.");
            case CHECKING_PAIR: return AppLanguage.text(context, english, "Se verifică perechea selectată înaintea rulării.", "Comprobando el par seleccionado antes de la ejecución programada.");
            case MISSED_OFFLINE: return AppLanguage.text(context, english, "Interval ratat offline. Automatizarea nu a rulat în acest interval.", "Intervalo perdido sin conexión. La automatización no se ejecutó durante este intervalo.");
            case "This window is blocked by a previous run.": return AppLanguage.text(context, english, "Acest interval este blocat de o rulare anterioară.", "Una ejecución anterior bloquea este intervalo.");
            case "The Confirm button could not be used before the retry period ended.": return AppLanguage.text(context, english, "Butonul de confirmare nu a putut fi folosit în timpul disponibil pentru reîncercare.", "No se pudo pulsar Confirmar antes de que terminara el plazo de reintento.");
            case "Open NACE and check that the background service is active.": return AppLanguage.text(context, english, "Deschide NACE și verifică dacă serviciul este activ.", "Abre NACE y comprueba que el servicio en segundo plano esté activo.");
            case "Could not verify the NACE session. Check your connection and open Watch.": return AppLanguage.text(context, english, "Sesiunea NACE nu a putut fi verificată. Verifică conexiunea și deschide Vizualizare.", "No se pudo verificar la sesión de NACE. Comprueba tu conexión y abre Visualizar.");
            case "Open Control and check that you are using a live account.": return AppLanguage.text(context, english, "Deschide Control și verifică dacă folosești un cont real.", "Abre Control y comprueba que estés usando una cuenta real.");
            case "Check your login information and try again.": return AppLanguage.text(context, english, "Verifică datele de conectare și încearcă din nou.", "Comprueba tus datos de acceso e inténtalo de nuevo.");
            case VERIFICATION_PREFILLED: return AppLanguage.text(context, english, "Numărul de telefon și parola sunt deja completate. Introdu codul de securitate, apoi apasă butonul Login de sub el (nu fila Login de sus). Dacă nu vezi butonul, derulează puțin în jos.", "Tu número de teléfono y tu contraseña ya están rellenados. Introduce el código de seguridad y pulsa el botón Login que está debajo (no la pestaña Login de arriba). Si no ves el botón, desplázate un poco hacia abajo.");
            case "Complete the verification step on the NACE login page.": return AppLanguage.text(context, english, "Finalizează verificarea pe pagina de conectare NACE.", "Completa la verificación en la página de acceso de NACE.");
            case "Signed in, but the login could not be saved on this phone.": return AppLanguage.text(context, english, "Te-ai conectat, dar datele nu au putut fi salvate pe telefon.", "Has iniciado sesión, pero no se pudieron guardar los datos en el teléfono.");
            case EXCHANGE_DNS_ERROR: return AppLanguage.text(context, english, "Domeniul NACE nu a putut fi rezolvat. Verifică linkul platformei.", "No se pudo resolver el dominio de NACE. Comprueba el enlace de la plataforma.");
            case EXCHANGE_UNAVAILABLE: return AppLanguage.text(context, english, "Pagina platformei NACE nu a putut fi încărcată.", "No se pudo cargar la página de la plataforma NACE.");
            case INTERNET_DOWN: return AppLanguage.text(context, english, "Nu există conexiune la internet. Verifică Wi-Fi sau datele mobile.", "No hay conexión a Internet. Comprueba la red Wi-Fi o los datos móviles.");
            case WAKEUPS_REQUIRED: return AppLanguage.text(context, english, "Permite activarea programată din Setări pentru a rula automatizarea.", "Permite las activaciones programadas en Ajustes para ejecutar la automatización.");
            case STOPPED_BY_USER: return AppLanguage.text(context, english, "Automatizarea a fost oprită din Setări.", "Automatización detenida desde Ajustes.");
            case STOPPING_AFTER_CONFIRM: return AppLanguage.text(context, english, "Automatizarea este oprită. Se verifică ultima confirmare.", "Automatización detenida. Comprobando la última confirmación.");
            case "The pre-confirmation check failed. No further attempt will be made in this interval.": return AppLanguage.text(context, english, "Verificarea dinaintea confirmării a eșuat. Nu se va reîncerca în acest interval.", "Falló la comprobación previa. No se volverá a intentar en este intervalo.");
            case "NACE has not verified a live account.": return AppLanguage.text(context, english, "NACE nu a verificat un cont real.", "NACE no ha verificado una cuenta real.");
            case "Add an interval before automation can run.": return AppLanguage.text(context, english, "Adăugați un interval înainte de a rula automatizarea.", "Añade un intervalo para ejecutar la automatización.");
            case "No verified confirmation before the interval ended.": return AppLanguage.text(context, english, "Nu a existat o confirmare verificată înainte de încheierea intervalului.", "El intervalo terminó sin una confirmación verificada.");
            case "Confirmation outcome unknown. This interval will not retry.": return AppLanguage.text(context, english, "Rezultatul confirmării este necunoscut. Nu se va reîncerca în acest interval.", "Se desconoce el resultado de la confirmación. No se volverá a intentar en este intervalo.");
            case "Confirmation outcome unknown. This signal will not be retried.": return AppLanguage.text(context, english, "Rezultatul confirmării este necunoscut. Nu se va reîncerca pentru acest semnal.", "Se desconoce el resultado de la confirmación. No se volverá a intentar esta señal.");
            case "The pre-confirmation check failed. This signal will not be retried.": return AppLanguage.text(context, english, "Verificarea dinaintea confirmării a eșuat. Nu se va reîncerca pentru acest semnal.", "Falló la comprobación previa. No se volverá a intentar esta señal.");
            case "Automation became unavailable before confirmation.": return AppLanguage.text(context, english, "Automatizarea a devenit indisponibilă înainte de confirmare.", "La automatización dejó de estar disponible antes de la confirmación.");
            case "The confirm tap was sent. NACE did not verify submission.": return AppLanguage.text(context, english, "Apăsarea de confirmare a fost trimisă. NACE nu a verificat trimiterea.", "Se pulsó Confirmar. NACE no pudo verificar el envío.");
            case "Live confirmation is unavailable.": return AppLanguage.text(context, english, "Confirmarea reală nu este disponibilă.", "La confirmación de operaciones reales no está disponible.");
            case "Confirmation is being checked.": return AppLanguage.text(context, english, "Confirmarea este verificată.", "Comprobando la confirmación.");
            case "The confirmation could not be safely reserved.": return AppLanguage.text(context, english, "Confirmarea nu a putut fi rezervată în siguranță.", "No se pudo reservar la confirmación de forma segura.");
            case "The browser stopped responding. The confirmation will not retry.": return AppLanguage.text(context, english, "Browserul nu mai răspunde. Confirmarea nu va fi reîncercată.", "El navegador dejó de responder. No se volverá a intentar la confirmación.");
            case "The browser stopped responding before the confirmation.": return AppLanguage.text(context, english, "Browserul nu a mai răspuns înainte de confirmare.", "El navegador dejó de responder antes de la confirmación.");
            case "The previously timed-out confirm tap was sent. NACE did not verify submission.": return AppLanguage.text(context, english, "Apăsarea de confirmare care depășise timpul de așteptare a fost trimisă. NACE nu a verificat trimiterea.", "Se envió la confirmación que había superado el tiempo de espera. NACE no pudo verificar el envío.");
            case "Automation stopped before NACE verified the last submission.": return AppLanguage.text(context, english, "Automatizarea s-a oprit înainte ca NACE să verifice ultima trimitere.", "La automatización se detuvo antes de que NACE verificara el último envío.");
            case "Copy order opened. Final confirmation was not tapped.": return AppLanguage.text(context, english, "Ordinul de copiere a fost deschis. Confirmarea finală nu a fost apăsată.", "Se abrió la orden de copia. No se pulsó el botón de confirmación final.");
            case "The confirm tap was sent. Waiting for NACE.": return AppLanguage.text(context, english, "Apăsarea de confirmare a fost trimisă. Se așteaptă răspunsul NACE.", "Se pulsó Confirmar. Esperando a NACE.");
            case "NACE confirmed the selected copy order.": return AppLanguage.text(context, english, "NACE a confirmat ordinul de copiere selectat.", "NACE confirmó la orden de copia seleccionada.");
            case "A matching new order appeared in the Order tab.": return AppLanguage.text(context, english, "Un ordin nou corespunzător a apărut în fila Ordine.", "Apareció una nueva orden coincidente en la pestaña Órdenes.");
            case "An order was visible in the Order tab after Confirm.": return AppLanguage.text(context, english, "Un ordin era vizibil în fila Ordine după confirmare.", "Había una orden visible en la pestaña Órdenes después de pulsar Confirmar.");
            case "Confirm was tapped. Checking the Order tab.": return AppLanguage.text(context, english, "Confirmarea a fost apăsată. Se verifică fila Ordine.", "Se pulsó Confirmar. Comprobando la pestaña Órdenes.");
            case "No matching new order appeared in the Order tab. Check the exchange before trying again.": return AppLanguage.text(context, english, "Nu a apărut niciun ordin nou corespunzător în fila Ordine. Verifică platforma înainte de a încerca din nou.", "No apareció una nueva orden coincidente en Órdenes. Comprueba la plataforma antes de volver a intentarlo.");
            case "The signal's confirmation dialog could not be opened.": return AppLanguage.text(context, english, "Fereastra de confirmare a semnalului nu a putut fi deschisă.", "No se pudo abrir el cuadro de confirmación de la señal.");
            case "Confirmation outcome unknown after restart. This signal will not retry.": return AppLanguage.text(context, english, "Rezultatul confirmării este necunoscut după repornire. Nu se va reîncerca pentru acest semnal.", "Se desconoce el resultado tras el reinicio. No se volverá a intentar esta señal.");
            case "No identifiable Copy Trading signal was available before the window ended.": return AppLanguage.text(context, english, "Nu a fost disponibil niciun semnal de copiere identificabil înainte de sfârșitul intervalului.", "No se encontró ninguna señal de copia identificable antes de que terminara el intervalo.");
            case "The expected copy-order controls changed.": return AppLanguage.text(context, english, "Controalele așteptate ale ordinului de copiere s-au schimbat.", "Cambió la interfaz esperada de la orden de copia.");
            case "Wait for the confirmation result before changing controls.": return AppLanguage.text(context, english, "Așteptați rezultatul confirmării înainte de a schimba controalele.", "Espera el resultado de la confirmación antes de cambiar los controles.");
            case "Wait for the browser to finish its current step.": return AppLanguage.text(context, english, "Așteptați ca browserul să termine pasul curent.", "Espera a que el navegador termine el paso actual.");
            case "Automation paused before a confirm tap was sent.": return AppLanguage.text(context, english, "Automatizarea a fost întreruptă înainte de trimiterea apăsării de confirmare.", "La automatización se puso en pausa antes de que se pulsara Confirmar.");
            case "Skipped while manual control was open.": return AppLanguage.text(context, english, "Interval omis cât timp controlul manual era deschis.", "Se omitió mientras el control manual estaba abierto.");
            case "The manual pause could not be saved.": return AppLanguage.text(context, english, "Pauza pentru control manual nu a putut fi salvată.", "No se pudo guardar la pausa del control manual.");
            case "The Android browser stopped before NACE verified the result.": return AppLanguage.text(context, english, "Browserul Android s-a oprit înainte ca NACE să verifice rezultatul.", "El navegador de Android se detuvo antes de que NACE verificara el resultado.");
            case "The Android browser stopped. NACE is reconnecting.": return AppLanguage.text(context, english, "Browserul Android s-a oprit. NACE se reconectează.", "El navegador de Android se detuvo. NACE se está reconectando.");
            case "Android could not initialize the browser.": return AppLanguage.text(context, english, "Android nu a putut inițializa browserul.", "Android no pudo iniciar el navegador.");
            case "Android could not keep the browser visible in the background.": return AppLanguage.text(context, english, "Android nu a putut păstra browserul vizibil în fundal.", "Android no pudo mantener el navegador visible en segundo plano.");
            case "Saved intervals could not be read. Review the schedule before enabling automation.": return AppLanguage.text(context, english, "Intervalele salvate nu au putut fi citite. Verificați programul înainte de activarea automatizării.", "No se pudieron leer los intervalos guardados. Revisa el horario antes de activar la automatización.");
            case "Confirmation outcome unknown after restart. No repeat in this interval.": return AppLanguage.text(context, english, "Rezultatul confirmării este necunoscut după repornire. Nu se va repeta în acest interval.", "Se desconoce el resultado tras el reinicio. No se repetirá en este intervalo.");
            case "Run history could not be verified. Automation is paused.": return AppLanguage.text(context, english, "Istoricul rulărilor nu a putut fi verificat. Automatizarea este în pauză.", "No se pudo verificar el historial de ejecuciones. La automatización está en pausa.");
            case "Provide scheduled intervals.": return AppLanguage.text(context, english, "Introduceți intervalele programate.", "Introduce los intervalos programados.");
            case "An interval is incomplete.": return AppLanguage.text(context, english, "Un interval este incomplet.", "Hay un intervalo incompleto.");
            case "An interval needs different start and end times.": return AppLanguage.text(context, english, "Ora de început și ora de sfârșit ale unui interval trebuie să fie diferite.", "Las horas de inicio y fin del intervalo deben ser distintas.");
            case "Use a 24-hour time in HH:mm format.": return AppLanguage.text(context, english, "Folosiți formatul de 24 de ore HH:mm.", "Usa el formato de 24 horas HH:mm.");
            case "Scheduled intervals cannot overlap.": return AppLanguage.text(context, english, "Intervalele programate nu se pot suprapune.", "Los intervalos programados no pueden superponerse.");
            case "Select ETH/USDT or BTC/USDT.": return AppLanguage.text(context, english, "Selectați ETH/USDT sau BTC/USDT.", "Selecciona ETH/USDT o BTC/USDT.");
            case "No Android virtual display available.": return AppLanguage.text(context, english, "Nu este disponibil niciun ecran virtual Android.", "No hay una pantalla virtual de Android disponible.");
            default: return english;
        }
    }

    private String browserCheckDetail(String result) {
        return translatedBrowserCheck(this, result) == null ? null : result;
    }

    private static String translatedBrowserCheck(Context context, String code) {
        if (code == null) return null;
        switch (code) {
            case "order_context_lost": return AppLanguage.text(context, "The browser lost the confirmation state.", "Browserul a pierdut starea confirmării.", "El navegador perdió el estado de la confirmación.");
            case "order_session_missing": return AppLanguage.text(context, "The exchange session was no longer signed in.", "Sesiunea platformei nu mai era conectată.", "Se cerró la sesión de la plataforma.");
            case "order_pair_changed": return AppLanguage.text(context, "The page no longer showed the selected pair.", "Pagina nu mai afișa perechea selectată.", "La página dejó de mostrar el par seleccionado.");
            case "order_dialog_open": return AppLanguage.text(context, "The confirmation dialog was still open.", "Fereastra de confirmare era încă deschisă.", "El cuadro de confirmación seguía abierto.");
            case "order_tab_missing": return AppLanguage.text(context, "The Order tab was missing.", "Fila Ordine lipsea.", "Faltaba la pestaña Órdenes.");
            case "order_tab_blocked": return AppLanguage.text(context, "The Order tab could not be opened.", "Fila Ordine nu a putut fi deschisă.", "No se pudo abrir la pestaña Órdenes.");
            case "order_tab_opening": return AppLanguage.text(context, "The Order tab was opening.", "Fila Ordine se deschidea.", "Se estaba abriendo la pestaña Órdenes.");
            case "order_rows_empty": return AppLanguage.text(context, "The Order tab showed no orders.", "Fila Ordine nu afișa niciun ordin.", "La pestaña Órdenes estaba vacía.");
            case "order_rows_mismatch": return AppLanguage.text(context, "Orders were visible, but none matched the pair, amount and time.", "Erau afișate ordine, dar niciunul nu corespundea perechii, sumei și orei.", "Había órdenes visibles, pero ninguna coincidía con el par, el importe y la hora.");
            case "order_rows_ambiguous": return AppLanguage.text(context, "More than one order matched.", "Mai multe ordine corespundeau.", "Coincidió más de una orden.");
            case "signal_identity_unavailable": return AppLanguage.text(context, "The signal IDs shown in the list could not be verified.", "ID-urile semnalelor din listă nu au putut fi verificate.", "No se pudieron verificar los identificadores de las señales de la lista.");
            case "signal_disappeared": return AppLanguage.text(context, "The selected signal disappeared from the list.", "Semnalul selectat a dispărut din listă.", "La señal seleccionada desapareció de la lista.");
            case "signal_button_blocked": return AppLanguage.text(context, "The signal's confirmation button was blocked or unavailable.", "Butonul de confirmare al semnalului era blocat sau indisponibil.", "El botón de confirmación de la señal estaba bloqueado o no estaba disponible.");
            case "copy_tab_missing": return AppLanguage.text(context, "The Copy Trading List tab was missing.", "Fila Listă de copiere a tranzacțiilor lipsea.", "Faltaba la pestaña de señales de copia.");
            case "copy_tab_blocked": return AppLanguage.text(context, "The Copy Trading List tab could not be opened.", "Fila Listă de copiere a tranzacțiilor nu a putut fi deschisă.", "No se pudo abrir la lista de señales de copia.");
            case "dialog_missing": return AppLanguage.text(context, "The copy confirmation dialog did not appear.", "Fereastra de confirmare a copierii nu a apărut.", "No apareció el cuadro de confirmación de la orden de copia.");
            case "confirm_button_missing": return AppLanguage.text(context, "The final Confirm button was missing.", "Butonul final de confirmare lipsea.", "Faltaba el botón de confirmación final.");
            case "confirm_amount_invalid": return AppLanguage.text(context, "The prefilled amount could not be read.", "Suma precompletată nu a putut fi citită.", "No se pudo leer el importe indicado.");
            case "confirm_button_blocked": return AppLanguage.text(context, "The final Confirm button could not be tapped.", "Butonul final de confirmare nu a putut fi apăsat.", "No se pudo pulsar el botón de confirmación final.");
            case "success_toast_visible": return AppLanguage.text(context, "An earlier success message was still visible.", "Un mesaj anterior de succes era încă vizibil.", "Todavía se mostraba un aviso de éxito anterior.");
            case "verify": return AppLanguage.text(context, "The browser could not verify the session, pair or window.", "Browserul nu a putut verifica sesiunea, perechea sau intervalul.", "El navegador no pudo verificar la sesión, el par o el intervalo.");
            case "recheck": return AppLanguage.text(context, "The sign-in check needed to be refreshed.", "Verificarea sesiunii trebuia reînnoită.", "Era necesario volver a comprobar la sesión.");
            case "stale_dialog": return AppLanguage.text(context, "An earlier confirmation dialog was still open.", "O fereastră anterioară de confirmare era încă deschisă.", "Seguía abierto un cuadro de confirmación anterior.");
            case "invalid": return AppLanguage.text(context, "The page no longer matched this signal's confirmation state.", "Pagina nu mai corespundea stării de confirmare pentru acest semnal.", "La página dejó de coincidir con el estado de confirmación de esta señal.");
            default: return null;
        }
    }

    static String localizeHistoryLine(Context context, String line) {
        if (line == null) return null;
        String detail = translatedBrowserCheck(context, line);
        if (detail != null) return detail;
        switch (line) {
            case "TAB": return AppLanguage.text(context, "Open Copy Trading List", "Deschidere listă de copiere", "Abrir la lista de señales de copia");
            case "SIGNALS": return AppLanguage.text(context, "Find eligible signals", "Căutare semnale eligibile", "Buscar señales aptas");
            case "OPEN": return AppLanguage.text(context, "Tap Copy Trading Information", "Apăsare informații de copiere", "Pulsar la información de la orden de copia");
            case "DIALOG": return AppLanguage.text(context, "Wait for confirmation dialog", "Așteptare fereastră de confirmare", "Esperar el cuadro de confirmación");
            case "CONFIRM": return AppLanguage.text(context, "Send final Confirm", "Trimitere confirmare finală", "Pulsar el botón de confirmación final");
            case "OBSERVE": return AppLanguage.text(context, "Verify the Order tab", "Verificare filă Ordine", "Comprobar la pestaña Órdenes");
            case "The scheduled window ended before Confirm was sent.": return AppLanguage.text(context, "The scheduled window ended before Confirm was sent.", "Intervalul s-a încheiat înainte de trimiterea confirmării.", "El intervalo terminó antes de que se pulsara Confirmar.");
            case "The selected pair changed before Confirm was sent.": return AppLanguage.text(context, "The selected pair changed before Confirm was sent.", "Perechea selectată s-a schimbat înainte de confirmare.", "El par seleccionado cambió antes de que se pulsara Confirmar.");
            case "The saved schedule or history could not be verified.": return AppLanguage.text(context, "The saved schedule or history could not be verified.", "Programul sau istoricul salvat nu a putut fi verificat.", "No se pudo verificar el horario o el historial guardado.");
            case "The background service stopped before Confirm was sent.": return AppLanguage.text(context, "The background service stopped before Confirm was sent.", "Serviciul din fundal s-a oprit înainte de confirmare.", "El servicio en segundo plano se detuvo antes de que se pulsara Confirmar.");
            case "The browser was unavailable in the background before Confirm was sent.": return AppLanguage.text(context, "The browser was unavailable in the background before Confirm was sent.", "Browserul nu era disponibil în fundal înainte de confirmare.", "El navegador dejó de estar disponible en segundo plano antes de que se pulsara Confirmar.");
            case "Waiting for a fresh sign-in check before Confirm.": return AppLanguage.text(context, "Waiting for a fresh sign-in check before Confirm.", "Se așteaptă o verificare nouă a sesiunii înainte de confirmare.", "Esperando una nueva comprobación de la sesión antes de pulsar Confirmar.");
            case "Copy Trading List opened.": return AppLanguage.text(context, "Copy Trading List opened.", "Lista de copiere a tranzacțiilor s-a deschis.", "Se abrió la lista de señales de copia.");
            case "The Copy Trading Information button was tapped.": return AppLanguage.text(context, "The Copy Trading Information button was tapped.", "Butonul cu informațiile de copiere a fost apăsat.", "Se pulsó el botón de información de la orden de copia.");
            case "An eligible copy-trading signal was identified.": return AppLanguage.text(context, "An eligible copy-trading signal was identified.", "A fost identificat un semnal de copiere eligibil.", "Se identificó una señal de copia apta.");
            case "The Copy Trading List had no eligible entries.": return AppLanguage.text(context, "The Copy Trading List had no eligible entries.", "Lista de copiere nu conținea intrări eligibile.", "La lista de señales de copia no contenía señales aptas.");
            case "Confirm was sent, but the Order tab did not verify it within 30 seconds.": return AppLanguage.text(context, "Confirm was sent, but the Order tab did not verify it within 30 seconds.", "Confirmarea a fost trimisă, dar fila Ordine nu a verificat-o în 30 de secunde.", "Se pulsó Confirmar, pero la pestaña Órdenes no verificó el envío en 30 segundos.");
            default: return localize(context, line);
        }
    }

    private String displayMessage(String value) {
        if (value != null && value.contains("\n")) {
            String[] lines = value.split("\n");
            for (int index = 0; index < lines.length; index++) {
                String line = lines[index];
                if (line.startsWith(STEP_PREFIX)) {
                    lines[index] = AppLanguage.text(this, STEP_PREFIX, "Pas · ", "Paso · ")
                            + localizeHistoryLine(this, line.substring(STEP_PREFIX.length()));
                } else if (line.startsWith(CHECK_PREFIX)) {
                    lines[index] = AppLanguage.text(this, CHECK_PREFIX, "Ultima verificare · ", "Última comprobación · ")
                            + localizeHistoryLine(this, line.substring(CHECK_PREFIX.length()));
                } else if (line.startsWith(TIME_PREFIX)) {
                    lines[index] = AppLanguage.text(this, TIME_PREFIX, "Înregistrat la · ", "Registrado a las · ")
                            + line.substring(TIME_PREFIX.length());
                } else {
                    lines[index] = localizeHistoryLine(this, line);
                }
            }
            return String.join("\n", lines);
        }
        String prefix = "Finding the copy trade for ";
        if (value != null && value.startsWith(prefix) && value.endsWith(".")) {
            String pairName = value.substring(prefix.length(), value.length() - 1);
            return AppLanguage.text(this, prefix + pairName + ".",
                    "Se caută tranzacția de copiere pentru " + pairName + ".", "Buscando la señal de copia para " + pairName + ".");
        }
        return localize(this, value);
    }

    public void refreshNotificationLanguage() {
        notificationTitle = null;
        updateWorkerNotification();
    }

    private String readinessIssue(long elapsed) {
        if (!configurationValid) return message;
        if (!networkAvailable() || Boolean.FALSE.equals(internetAtPageFailure)
                || (preflightWindow != null && Boolean.FALSE.equals(preflightInternet)
                    && verifiedAt <= preflightInternetCheckedAt)) return INTERNET_DOWN;
        if (mainFrameFailed && Boolean.TRUE.equals(internetAtPageFailure)) {
            return exchangeDnsFailed ? EXCHANGE_DNS_ERROR : EXCHANGE_UNAVAILABLE;
        }
        if (signInHelp != null && session != SessionState.SIGNED_IN) return signInHelp;
        if (signInPending()) return "Checking the NACE session.";
        if (!WindowWakeups.canSchedule(this)) return WAKEUPS_REQUIRED;
        if (manualControl) return "Control is open. Scheduled automation is paused.";
        if (!backgroundReady(elapsed)) return "Open NACE and check that the background service is active.";
        switch (effectiveSession(elapsed)) {
            case SIGNED_OUT: return "Use Control to sign in to NACE.";
            case CHECKING: return "Checking the NACE session.";
            case UNAVAILABLE: return "Could not verify the NACE session. Check your connection and open Watch.";
            default: return !liveAccountVerified ? "Open Control and check that you are using a live account." : null;
        }
    }

    private String automationIssue(long elapsed, DailySchedule.Occurrence current) {
        if (automationStopped) return verifyingSubmittedTrade() ? STOPPING_AFTER_CONFIRM : STOPPED_BY_USER;
        if (manualControl) return "Control is open. Scheduled automation is paused.";
        if (!foreground) return "The background service is stopped.";
        if (!configurationValid) return message;
        if (!WindowWakeups.canSchedule(this)) return WAKEUPS_REQUIRED;
        if (windows.isEmpty()) return "Add an interval before automation can run.";
        if (finalConfirmationBrowser != null && activeRun == null) {
            return "The last confirmation is unresolved. Browser control is temporarily locked.";
        }
        if (parked) return null;
        String issue = readinessIssue(elapsed);
        if (issue != null) return issue;
        if (current != null && hasWindowBlock(current)) return "This window is blocked by a previous run.";
        DailySchedule.Occurrence target = current == null
                ? upcomingPreflight(ZonedDateTime.now(scheduleZone)) : current;
        if (target == null) return null;
        if (!sameWindow(target, preflightWindow) || !pair.equals(preflightPair)) return CHECKING_PAIR;
        if (preflightIssue != null) return preflightIssue;
        return preflightPairReady && !watchPairChecking ? null : CHECKING_PAIR;
    }

    private void updateWorkerNotification() {
        if (!foreground) return;
        String issue = automationIssue(SystemClock.elapsedRealtime(), currentOccurrence());
        String title;
        if (automationStopped) {
            title = AppLanguage.text(this, "AUTOMATION STOPPED", "AUTOMATIZARE OPRITĂ", "AUTOMATIZACIÓN DETENIDA");
        } else if (manualControl) {
            title = AppLanguage.text(this, "AUTOMATION PAUSED", "AUTOMATIZARE ÎN PAUZĂ", "AUTOMATIZACIÓN EN PAUSA");
        } else if (parked && issue == null) {
            title = AppLanguage.text(this, "AUTOMATION SCHEDULED", "AUTOMATIZARE PROGRAMATĂ", "AUTOMATIZACIÓN PROGRAMADA");
        } else if (issue != null) {
            title = AppLanguage.text(this, "AUTOMATION NOT READY", "AUTOMATIZARE INDISPONIBILĂ", "AUTOMATIZACIÓN INDISPONIBLE");
        } else {
            title = AppLanguage.text(this, "AUTOMATION LIVE", "AUTOMATIZARE LIVE", "AUTOMATIZACIÓN ACTIVA");
        }
        String detail = manualControl ? localize(this, "Control is open. Scheduled automation is paused.")
                : issue != null ? localize(this, issue) : pair;
        if (!manualControl && !automationStopped) {
            DailySchedule.Occurrence next = nextScheduledWindow();
            if (next != null) {
                detail += "\n" + AppLanguage.text(this, "Next window", "Următorul interval", "Próximo intervalo") + " · "
                         + next.displayLabel(ZoneId.systemDefault());
            }
        }
        if (parked && lastCheck != null) {
            detail += "\n" + AppLanguage.text(this, "Last checked", "Ultima verificare", "Última comprobación") + " · "
                    + DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).withZone(ZoneId.systemDefault()).format(lastCheck);
        }
        DiagnosticLog.change(this, "status", title + (issue == null ? "" : " | " + issue));
        if (title.equals(notificationTitle) && detail.equals(notificationDetail)) return;
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, workerNotification(title, detail));
        notificationTitle = title;
        notificationDetail = detail;
    }

    private void showTradeFailureOrClear(DailySchedule.Occurrence current) {
        if (failedTradeIssue != null && sameWindow(failedTradeWindow, current) && pair.equals(failedTradePair)) {
            reportBlocker(failedTradeWindow, failedTradePair, failedTradeIssue);
        } else {
            failedTradeWindow = null;
            failedTradeIssue = null;
            blockerAlert.clear();
        }
    }

    private void notifyPreflightIssue(String issue) {
        if (preflightWindow != null) reportBlocker(preflightWindow, pair, issue);
    }

    private void reportBlocker(DailySchedule.Occurrence occurrence, String tradePair, String issue) {
        String key = occurrence.date + "/" + occurrence.start + "/" + occurrence.end + "/" + tradePair;
        String reason = localize(this, issue);
        DiagnosticLog.change(this, "warning", "WARNING for " + occurrence.start + " " + tradePair + ": " + issue);
        // A new problem is also spoken once: "Warning! Warning!" + the reason.
        if (blockerAlert.report(key, occurrence.displayLabel(ZoneId.systemDefault()), tradePair, reason)) {
            outcomeSpeaker.speakWarning(reason);
        }
    }

    /** Clears a warning because automation verified it is ready again, and says so out loud. */
    private void clearAndAnnounceRecovery(DailySchedule.Occurrence current) {
        boolean hadWarning = blockerAlert.active();
        showTradeFailureOrClear(current);
        if (hadWarning && !blockerAlert.active()) outcomeSpeaker.speakRecovered();
    }

    /**
     * Once per window, about five minutes before it opens and only after session, internet and pair
     * were verified: "NACE automation online. Waiting for the trade."
     */
    private void announceOnlineBeforeWindow(DailySchedule.Occurrence target, ZonedDateTime now,
            DailySchedule.Occurrence current) {
        if (current != null || automationStopped || manualControl) return;
        if (now.isBefore(target.from.minusSeconds(ONLINE_ANNOUNCE_LEAD_SECONDS))) return;
        String key = target.date + "/" + target.start + "/" + target.end;
        if (key.equals(preferences.getString(ONLINE_ANNOUNCED, null))) return;
        preferences.edit().putString(ONLINE_ANNOUNCED, key).apply();
        outcomeSpeaker.speakOnline();
    }

    private Notification workerNotification(String title, String detail) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, AppLanguage.text(this, "NACE automation", "Automatizare NACE", "Automatización de NACE"), NotificationManager.IMPORTANCE_LOW));
        Notification.Builder notification = new Notification.Builder(this, CHANNEL)
                .setContentTitle(title)
                .setContentText(detail)
                .setStyle(new Notification.BigTextStyle().bigText(detail))
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setOnlyAlertOnce(true)
                .setOngoing(true);
        notification.setContentIntent(openAppNotification());
        return notification.build();
    }

    private PendingIntent openAppNotification() {
        Intent launch = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 40, launch, PendingIntent.FLAG_IMMUTABLE);
    }

    private DailySchedule.Occurrence currentOccurrence() {
        ZonedDateTime now = ZonedDateTime.now(scheduleZone);
        for (LocalDate date : Arrays.asList(now.toLocalDate().minusDays(1), now.toLocalDate())) {
            for (TradingWindow window : windows) {
                DailySchedule.Occurrence occurrence = DailySchedule.occurrence(date, window.startTime, window.endTime, scheduleZone);
                if (occurrence.contains(now)) return occurrence;
            }
        }
        return null;
    }

    private DailySchedule.Occurrence nextScheduledWindow() {
        ZonedDateTime now = ZonedDateTime.now(scheduleZone);
        for (LocalDate date : Arrays.asList(now.toLocalDate(), now.toLocalDate().plusDays(1))) {
            for (TradingWindow window : windows) {
                DailySchedule.Occurrence occurrence = DailySchedule.occurrence(date, window.startTime, window.endTime, scheduleZone);
                if (occurrence.from.isAfter(now)) return occurrence;
            }
        }
        return null;
    }

    /** For the countdown pill: ms until the next window opens, 0 while one is open, -1 if none / stopped. */
    long millisToNextTrade() {
        if (automationStopped || windows.isEmpty()) return -1;
        if (currentOccurrence() != null) return 0;
        DailySchedule.Occurrence next = nextScheduledWindow();
        if (next == null) return -1;
        return Math.max(1, next.from.toInstant().toEpochMilli() - System.currentTimeMillis());
    }

    private static boolean sameWindow(DailySchedule.Occurrence left, DailySchedule.Occurrence right) {
        return left != null && right != null && left.from.equals(right.from) && left.until.equals(right.until);
    }

    private RunResult findResult(DailySchedule.Occurrence occurrence) {
        for (RunResult result : results.values()) {
            if (overlaps(result, occurrence)) return result;
        }
        return null;
    }

    private boolean hasWindowBlock(DailySchedule.Occurrence occurrence) {
        for (RunResult result : results.values()) {
            if ((result.signalId == null || !pair.equals(result.pair)) && overlaps(result, occurrence)) return true;
        }
        return false;
    }

    private boolean hasSignalResult(DailySchedule.Occurrence occurrence, String pair, String signalId) {
        for (RunResult result : results.values()) {
            if (signalId.equals(result.signalId) && pair.equals(result.pair) && overlaps(result, occurrence)) return true;
        }
        return false;
    }

    private static boolean overlaps(RunResult result, DailySchedule.Occurrence occurrence) {
        return result.occurrence().overlaps(occurrence);
    }

    private static boolean validSignalId(String signalId) {
        return signalId.matches("[0-9A-Za-z_-]{1,128}");
    }

    private boolean recordSignal(Run run, String status, String message) {
        return record(run.occurrence, run.pair, status, message, run.signalId);
    }

    private boolean record(DailySchedule.Occurrence occurrence, String pair, String status, String message) {
        return record(occurrence, pair, status, message, null);
    }

    private boolean record(DailySchedule.Occurrence occurrence, String pair, String status, String message, String signalId) {
        DiagnosticLog.log(this, "trade", occurrence.date + " " + occurrence.start + " " + pair + ": " + status
                + (message == null ? "" : " - " + message));
        String date = occurrence.date.toString();
        RunResult entry = new RunResult(pair, date, occurrence.start.toString(), occurrence.end.toString(), status,
                message, signalId, occurrence.from.getZone());
        String key = resultKey(entry);
        LinkedHashMap<String, RunResult> updated = new LinkedHashMap<>(results);
        updated.put(key, entry);
        return persistResults(updated, preferences.edit());
    }

    private boolean persistResults(LinkedHashMap<String, RunResult> updated, SharedPreferences.Editor editor) {
        String oldestProtected = LocalDate.now(DailySchedule.ZONE).minusDays(1).toString();
        while (updated.size() > RESULT_LIMIT) {
            String oldest = Collections.min(updated.keySet());
            if (updated.get(oldest).date.compareTo(oldestProtected) >= 0) break;
            updated.remove(oldest);
        }
        JSONArray stored = new JSONArray();
        try {
            for (RunResult result : updated.values()) {
                stored.put(new JSONObject().put("date", result.date).put("start", result.start).put("end", result.end)
                        .put("pair", result.pair).put("status", result.status).put("message", result.message)
                        .put("signalId", result.signalId).put("zone", result.zone.getId()));
            }
        } catch (JSONException error) {
            return false;
        }
        if (!editor.putString("results", stored.toString()).commit()) return false;
        results.clear();
        results.putAll(updated);
        return true;
    }

    private static String resultKey(RunResult result) {
        String window = result.date + "/" + result.start + "/" + result.end + "/" + result.zone.getId();
        return result.signalId == null ? window : window + "/" + result.pair + "/" + result.signalId;
    }

    private static String signalResultKey(DailySchedule.Occurrence occurrence, String pair, String signalId) {
        return occurrence.date + "/" + occurrence.start + "/" + occurrence.end + "/" + occurrence.from.getZone().getId()
                + "/" + pair + "/" + signalId;
    }

    private void reconcileMissedWindows(Instant now) {
        if (automationStopped) return;
        long previous = preferences.getLong(HISTORY_CHECKPOINT, 0);
        SharedPreferences.Editor editor = preferences.edit().putBoolean(INITIALIZED, true);
        if (!configurationValid) {
            editor.apply();
            return;
        }
        editor.putLong(HISTORY_CHECKPOINT, now.toEpochMilli());
        LinkedHashMap<String, RunResult> updated = new LinkedHashMap<>(results);
        if (previous > 0 && previous < now.toEpochMilli()) {
            ZonedDateTime end = now.atZone(scheduleZone);
            Instant since = Instant.ofEpochMilli(previous);
            LocalDate first = since.atZone(scheduleZone).toLocalDate();
            LocalDate earliest = end.toLocalDate().minusDays(6);
            for (LocalDate date = first.isBefore(earliest) ? earliest : first;
                    !date.isAfter(end.toLocalDate()); date = date.plusDays(1)) {
                for (TradingWindow window : windows) {
                    DailySchedule.Occurrence occurrence = DailySchedule.occurrence(date, window.startTime, window.endTime, scheduleZone);
                    if (!occurrence.from.toInstant().isAfter(since) || occurrence.until.isAfter(end)
                            || findResult(occurrence) != null) continue;
                    RunResult missed = new RunResult(pair, date.toString(), window.start, window.end, "skipped",
                            historyDetail(MISSED_OFFLINE, null), null, scheduleZone);
                    updated.put(resultKey(missed), missed);
                }
            }
        }
        if (updated.size() == results.size()) editor.commit();
        else persistResults(updated, editor);
    }

    private void loadConfiguration() {
        exchangeLink = ExchangeLink.parse(preferences.getString(ExchangeLink.PREFERENCE, ExchangeLink.DEFAULT_URL));
        pair = BTC.equals(preferences.getString("pair", ETH)) ? BTC : ETH;
        String encodedSchedule = preferences.getString("windows", null);
        String zone = preferences.getString(SCHEDULE_ZONE, null);
        if (encodedSchedule == null && zone == null) {
            SharedPreferences.Editor editor = preferences.edit().putString(SCHEDULE_ZONE, scheduleZone.getId());
            if (preferences.contains(HISTORY_CHECKPOINT)) editor.putLong(HISTORY_CHECKPOINT, Instant.now().toEpochMilli());
            editor.commit();
        } else {
            try {
                scheduleZone = zone == null ? DailySchedule.ZONE : ZoneId.of(zone);
            } catch (java.time.DateTimeException error) {
                configurationValid = false;
                message = "Saved intervals could not be read. Review the schedule before enabling automation.";
            }
        }
        if (encodedSchedule != null) {
            try {
                JSONArray schedule = new JSONArray(encodedSchedule);
                List<TradingWindow> saved = new ArrayList<>();
                for (int position = 0; position < schedule.length(); position++) {
                    JSONObject entry = schedule.getJSONObject(position);
                    saved.add(new TradingWindow(entry.getString("start"), entry.getString("end")));
                }
                windows = validatedWindows(saved);
            } catch (JSONException | IllegalArgumentException error) {
                configurationValid = false;
                message = "Saved intervals could not be read. Review the schedule before enabling automation.";
            }
        }
        try {
            JSONArray saved = new JSONArray(preferences.getString("results", "[]"));
            for (int position = 0; position < saved.length(); position++) {
                JSONObject entry = saved.getJSONObject(position);
                String date = LocalDate.parse(entry.getString("date")).toString();
                TradingWindow window = new TradingWindow(entry.getString("start"), entry.getString("end"));
                String status = entry.getString("status");
                String description = entry.optString("message");
                String signalId = entry.optString("signalId", null);
                if (signalId != null && !validSignalId(signalId)) throw new JSONException("Invalid saved signal ID");
                if ("pending".equals(status)) {
                    status = "confirmation_tapped";
                    description = signalId == null ? "Confirmation outcome unknown after restart. No repeat in this interval."
                            : "Confirmation outcome unknown after restart. This signal will not retry.";
                }
                ZoneId resultZone = ZoneId.of(entry.optString("zone", DailySchedule.ZONE.getId()));
                RunResult result = new RunResult(entry.getString("pair"), date, window.start, window.end, status,
                        description, signalId, resultZone);
                results.put(resultKey(result), result);
            }
        } catch (JSONException | java.time.DateTimeException | IllegalArgumentException error) {
            configurationValid = false;
            message = "Run history could not be verified. Automation is paused.";
        }
    }

    private static List<TradingWindow> defaults() {
        return Arrays.asList(
                new TradingWindow("12:05", "12:24"),
                new TradingWindow("17:05", "17:24"),
                new TradingWindow("20:05", "20:24"));
    }

    private static List<TradingWindow> validatedWindows(List<TradingWindow> windows) {
        if (windows == null) throw new IllegalArgumentException("Provide scheduled intervals.");
        List<TradingWindow> sorted = new ArrayList<>(windows);
        BitSet occupied = new BitSet(1440);
        for (TradingWindow window : sorted) {
            if (window == null) throw new IllegalArgumentException("An interval is incomplete.");
            DailySchedule.addInterval(occupied, window.startTime, window.endTime);
        }
        sorted.sort(Comparator.comparing(window -> window.startTime));
        return Collections.unmodifiableList(sorted);
    }

    private static Object decode(String raw) {
        if (raw == null || "null".equals(raw)) return null;
        try {
            return new JSONTokener(raw).nextValue();
        } catch (JSONException error) {
            return null;
        }
    }

    private static final class SharedBrowser extends WebView {
        boolean readOnly = true;
        private final int touchSlop;
        private final String origin;
        private float downX;
        private float downY;
        private float lastY;
        private float scrollX;
        private float scrollY;
        private float pendingScroll;
        private boolean scrolling;
        private boolean scrollPosted;

        SharedBrowser(Context context, String origin) {
            super(context);
            this.origin = origin;
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            if (!readOnly) return super.dispatchTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    lastY = downY;
                    scrolling = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (event.getPointerCount() != 1) break;
                    float y = event.getY();
                    float vertical = Math.abs(y - downY);
                    if (!scrolling && vertical > touchSlop && vertical > Math.abs(event.getX() - downX)) {
                        scrolling = true;
                    }
                    if (scrolling) {
                        scrollX = event.getX();
                        scrollY = y;
                        pendingScroll += lastY - y;
                        lastY = y;
                        if (!scrollPosted) {
                            scrollPosted = true;
                            postOnAnimation(this::applyWatchScroll);
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    scrolling = false;
                    break;
                default:
                    break;
            }
            // Watch consumes every event; only vertical drag distance reaches the page.
            return true;
        }

        private void applyWatchScroll() {
            float delta = pendingScroll;
            pendingScroll = 0;
            scrollPosted = false;
            if (readOnly && isAttachedToWindow() && getWidth() > 0 && getHeight() > 0 && delta != 0) {
                evaluateJavascript(NaceWebScript.scrollWatchBy(origin, scrollX / getWidth(), scrollY / getHeight(),
                        delta / getHeight()), null);
            }
        }

        @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
            return readOnly || super.dispatchGenericMotionEvent(event);
        }

        @Override public boolean dispatchKeyEvent(KeyEvent event) {
            return readOnly || super.dispatchKeyEvent(event);
        }

        @Override public InputConnection onCreateInputConnection(EditorInfo info) {
            return readOnly ? null : super.onCreateInputConnection(info);
        }

        @Override public boolean performAccessibilityAction(int action, Bundle args) {
            return !readOnly && super.performAccessibilityAction(action, args);
        }
    }
}
