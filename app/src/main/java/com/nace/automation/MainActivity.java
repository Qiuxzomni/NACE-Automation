package com.nace.automation;

import static com.nace.automation.NeonUi.DARK;
import static com.nace.automation.NeonUi.GOLD;
import static com.nace.automation.NeonUi.GREEN;
import static com.nace.automation.NeonUi.MUTED;
import static com.nace.automation.NeonUi.ORANGE;
import static com.nace.automation.NeonUi.PURPLE;
import static com.nace.automation.NeonUi.RED;
import static com.nace.automation.NeonUi.WHITE;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.widget.EditText;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;
import com.nace.automation.AutomationService.RunResult;
import com.nace.automation.AutomationService.Snapshot;
import com.nace.automation.AutomationService.TradingWindow;
import com.nace.automation.DashboardIcons.Icon;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final String SUPPORT_EMAIL = "VibeCodeDrop@pm.me";
    private static final Uri KO_FI_PAGE = Uri.parse("https://ko-fi.com/vibedevdrop");
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int WATCH_VIEWER = 1;
    private static final int CONTROL_VIEWER = 2;
    private static final String SAVED_VIEWER = "open_browser_mode";
    private static final String STARTUP_SIGN_IN_COMPLETE = "startup_sign_in_complete";
    private static final String NOTIFICATION_PROMPT_SHOWN = "notification_prompt_shown";
    private static final String TERRACOTTA_THEME = "terracotta_theme";

    private final BroadcastReceiver screenOffReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) return;
            if (pendingViewerMode == CONTROL_VIEWER) pendingViewerMode = 0;
            if (viewerMode == CONTROL_VIEWER) dismissBrowser();
        }
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable update = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 900);
        }
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((AutomationService.LocalBinder) binder).getService();
            service.setDashboardVisible(resumed);
            restoreVisibleSession();
            refresh();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            dismissBrowser();
            service = null;
            refresh();
        }
    };

    private AutomationService service;
    private boolean bound;
    private boolean resumed;
    private boolean startupSignInComplete;
    private Dialog startupDialog;
    private LinearLayout startupWakeupPanel;
    private Button startupSignInButton;
    private Dialog signInDialog;
    private String viewerSignInIssue;
    private LinearLayout startupNotificationPanel;
    private TextView startupWakeupStep;
    private TextView startupNotificationStep;
    private TextView startupBatteryStep;
    private LinearLayout startupBatteryPanel;
    private TextView startupSignInStep;
    private TextView startupSignInLock;
    private LinearLayout startupSignInPanel;
    private Dialog viewer;
    private int viewerMode;
    private int pendingViewerMode;
    private WebView viewerBrowser;
    private AutomationService viewerService;
    private TextView viewerStatus;
    private TextView viewerCaption;
    private ImageView hero;
    private TextView automation;
    private TextView background;
    private LinearLayout backgroundPill;
    private LinearLayout statusBanner;
    private TextView balance;
    private TextView session;
    private TextView zone;
    private Button btc;
    private Button eth;
    private Button watch;
    private Button control;
    private TextView countdown;
    private Dialog exchangeLinkDialog;
    private LinearLayout windows;
    private String priorWindows;
    private Boolean priorReadyGraphic;
    private Boolean priorBackground;
    private Boolean priorWatch;
    private AutomationService.SessionState priorSession;
    private String priorPair;
    private float panelWidth;
    private boolean terracotta;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguage.localize(base));
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        startupSignInComplete = getPreferences(MODE_PRIVATE).getBoolean(STARTUP_SIGN_IN_COMPLETE, false);
        terracotta = getPreferences(MODE_PRIVATE).getBoolean(TERRACOTTA_THEME, false);
        IntentFilter screenOff = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOffReceiver, screenOff, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenOffReceiver, screenOff);
        }
        if (state != null) pendingViewerMode = state.getInt(SAVED_VIEWER);
        getWindow().setStatusBarColor(terracotta ? TerracottaUi.PAPER : DARK);
        getWindow().setNavigationBarColor(terracotta ? TerracottaUi.PAPER : DARK);
        getWindow().getDecorView().setSystemUiVisibility(terracotta
                ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
        panelWidth = Math.min(getResources().getConfiguration().screenWidthDp - 20f, 446f);
        View content = terracotta ? terracottaDashboard() : dashboard();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            content.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }
        setContentView(content);
        bound = bindService(new Intent(this, AutomationService.class), connection, BIND_AUTO_CREATE);
        UsagePing.schedule(this);
        refresh();
    }

    @Override public void onStart() {
        super.onStart();
        handler.post(update);
    }

    @Override public void onResume() {
        super.onResume();
        resumed = true;
        restoreVisibleSession();
        if (service != null) {
            service.setDashboardVisible(true);
            service.refreshNotificationLanguage();
        }
        postponedAttention.clear();
        refresh();
    }

    @Override public void onPause() {
        resumed = false;
        if (service != null) service.setDashboardVisible(false);
        super.onPause();
    }

    @Override public void onStop() {
        handler.removeCallbacks(update);
        if (viewerMode == WATCH_VIEWER) {
            pendingViewerMode = WATCH_VIEWER;
            dismissBrowser();
        }
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt(SAVED_VIEWER, viewerMode != 0 ? viewerMode : pendingViewerMode);
        super.onSaveInstanceState(state);
    }

    @Override public void onDestroy() {
        dismissAttention();
        if (guideDialog != null) guideDialog.dismiss();
        for (Dialog open : new ArrayList<>(trackedDialogs)) {
            if (open.isShowing()) open.dismiss();
        }
        if (signInDialog != null) signInDialog.dismiss();
        if (exchangeLinkDialog != null) exchangeLinkDialog.dismiss();
        dismissStartupDialog();
        dismissBrowser();
        if (bound) unbindService(connection);
        unregisterReceiver(screenOffReceiver);
        super.onDestroy();
    }

    private void restoreVisibleSession() {
        if (!resumed || service == null) return;
        if (service.shouldStartMonitoring()) {
            try {
                startForegroundService(new Intent(this, AutomationService.class));
            } catch (RuntimeException exception) {
                note(safeMessage(exception, getString(R.string.monitor_start_error)));
            }
        }
        if (pendingViewerMode != 0) {
            boolean manual = pendingViewerMode == CONTROL_VIEWER;
            pendingViewerMode = 0;
            showBrowser(manual);
        }
    }

    private View dashboard() {
        DashboardArtwork artwork = new DashboardArtwork(this);
        boolean compact = compactDashboard();
        DashboardColumn shell = new DashboardColumn(this);
        shell.shrinkScheduleToFit = !stackedDashboard();
        shell.setBackground(NeonUi.backdrop());
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        shell.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        FrameLayout center = new FrameLayout(this);
        scroll.addView(center, new ScrollView.LayoutParams(MATCH, WRAP));

        LinearLayout page = column();
        page.setPadding(0, dp(7), 0, dp(2));
        center.addView(page, new FrameLayout.LayoutParams(dp(panelWidth), WRAP, Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        LinearLayout header = row();
        Button history = button("", ORANGE, 13, this::showHistory);
        setLeadingIcon(history, icon(Icon.HISTORY, GOLD, 28), 0);
        history.setPadding(dp(8), 0, 0, 0);
        history.setContentDescription(getString(R.string.run_history));
        header.addView(history, new LinearLayout.LayoutParams(dp(44), dp(44)));
        ImageView logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setImageDrawable(DashboardArtwork.logo(this));
        logo.setContentDescription("NACE");
        LinearLayout masthead = column();
        masthead.setGravity(Gravity.CENTER);
        masthead.addView(logo, new LinearLayout.LayoutParams(MATCH, dp(41)));
        TextView subtitle = centered(getString(R.string.dashboard_title), 10, WHITE, false);
        subtitle.setLetterSpacing(.18f);
        LinearLayout titleLine = row();
        titleLine.setGravity(Gravity.CENTER);
        View leftRule = new View(this);
        leftRule.setBackgroundColor(GOLD);
        titleLine.addView(leftRule, new LinearLayout.LayoutParams(dp(17), dp(1)));
        subtitle.setPadding(dp(8), 0, dp(8), 0);
        titleLine.addView(subtitle, new LinearLayout.LayoutParams(WRAP, WRAP));
        View rightRule = new View(this);
        rightRule.setBackgroundColor(GOLD);
        titleLine.addView(rightRule, new LinearLayout.LayoutParams(dp(17), dp(1)));
        masthead.addView(titleLine, new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout.LayoutParams middle = new LinearLayout.LayoutParams(0, WRAP, 1f);
        middle.setMargins(dp(5), 0, dp(5), 0);
        header.addView(masthead, middle);
        Button settings = button("", ORANGE, 13, this::showSettings);
        setLeadingIcon(settings, icon(Icon.SETTINGS, GOLD, 28), 0);
        settings.setPadding(dp(8), 0, 0, 0);
        settings.setContentDescription(getString(R.string.settings));
        header.addView(settings, new LinearLayout.LayoutParams(dp(44), dp(44)));
        add(page, header, WRAP, 0);

        hero = DashboardArtwork.hero(this);
        hero.setScaleType(compact ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
        hero.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        add(page, hero, dp(compact ? Math.min(92f, panelWidth / 2.98f) : panelWidth / 2.98f), 1);
        automation = centered(getString(R.string.offline), 24, RED, true);
        automation.setLetterSpacing(.025f);
        automation.setAutoSizeTextTypeUniformWithConfiguration(16, 27, 1, TypedValue.COMPLEX_UNIT_SP);
        automation.setMaxLines(1);
        add(page, automation, dp(31), 0);
        background = centered(getString(R.string.background_inactive), 13, RED, true);
        background.setLetterSpacing(.05f);
        backgroundPill = row();
        backgroundPill.setGravity(Gravity.CENTER);
        if (stackedDashboard()) {
            // Large system text: let the label shrink to fit the pill instead of being cut off.
            background.setMaxLines(1);
            background.setAutoSizeTextTypeUniformWithConfiguration(7, 13, 1, TypedValue.COMPLEX_UNIT_SP);
            backgroundPill.addView(background, new LinearLayout.LayoutParams(MATCH, MATCH));
        } else {
            backgroundPill.addView(background, new LinearLayout.LayoutParams(WRAP, WRAP));
        }
        LinearLayout.LayoutParams backgroundSize = new LinearLayout.LayoutParams(dp(panelWidth * .75f), dp(34));
        backgroundSize.gravity = Gravity.CENTER_HORIZONTAL;
        backgroundSize.topMargin = dp(1);
        page.addView(backgroundPill, backgroundSize);

        LinearLayout readings = row();
        readings.setPadding(dp(13), dp(5), dp(13), dp(5));
        readings.setBackground(NeonUi.frame(this, PURPLE, 17));
        LinearLayout balanceGroup = column();
        balanceGroup.setGravity(Gravity.CENTER);
        balanceGroup.setPadding(dp(13), 0, dp(13), 0);
        add(balanceGroup, centered(getString(R.string.balance), 10, WHITE, true), WRAP, 0);
        balance = centered("—", 17, GOLD, true);
        balance.setMaxLines(1);
        balance.setAutoSizeTextTypeUniformWithConfiguration(11, 19, 1, TypedValue.COMPLEX_UNIT_SP);
        add(balanceGroup, balance, dp(23), 0);
        FrameLayout account = new FrameLayout(this);
        account.addView(balanceGroup, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_VERTICAL));
        ImageView coins = new ImageView(this);
        coins.setImageDrawable(artwork.coin(DashboardArtwork.Coin.BALANCE, dp(29)));
        coins.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams coinLayout = new FrameLayout.LayoutParams(dp(30), dp(34), Gravity.START | Gravity.CENTER_VERTICAL);
        coinLayout.setMarginStart(dp(4));
        account.addView(coins, coinLayout);
        readings.addView(account, new LinearLayout.LayoutParams(0, WRAP, 1f));
        View line = new View(this);
        line.setBackgroundColor(0xaaeac7f4);
        readings.addView(line, new LinearLayout.LayoutParams(dp(1), dp(39)));
        LinearLayout sessionGroup = column();
        sessionGroup.setGravity(Gravity.CENTER);
        sessionGroup.addView(centered(getString(R.string.session_status), 11, WHITE, true));
        session = centered(getString(R.string.unavailable_upper), 15, RED, true);
        session.setMaxLines(1);
        session.setAutoSizeTextTypeUniformWithConfiguration(10, 15, 1, TypedValue.COMPLEX_UNIT_SP);
        addSessionStatus(sessionGroup, 26);
        readings.addView(sessionGroup, new LinearLayout.LayoutParams(0, WRAP, 1f));
        add(page, readings, WRAP, compact ? 4 : 7);

        btc = button("BTC", PURPLE, 25, () -> choosePair("BTC/USDT"));
        eth = button("ETH", PURPLE, 25, () -> choosePair("ETH/USDT"));
        styleCurrency(btc, artwork.coin(DashboardArtwork.Coin.BTC, dp(26)));
        styleCurrency(eth, artwork.coin(DashboardArtwork.Coin.ETH, dp(26)));
        btc.setContentDescription(getString(R.string.select_btc));
        eth.setContentDescription(getString(R.string.select_eth));
        add(page, two(btc, eth, 7, 47), WRAP, compact ? 3 : 5);

        LinearLayout schedule = column();
        schedule.setPadding(dp(10), dp(8), dp(10), dp(7));
        schedule.setBackground(NeonUi.frame(this, PURPLE, 18));
        LinearLayout scheduleHeader = row();
        scheduleHeader.setGravity(Gravity.CENTER);
        ImageView calendar = new ImageView(this);
        calendar.setImageDrawable(icon(Icon.CALENDAR, 0xfff2b8ff, 38));
        calendar.setScaleType(ImageView.ScaleType.CENTER);
        calendar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams calendarSize = new LinearLayout.LayoutParams(dp(48), dp(37));
        calendarSize.rightMargin = dp(6);
        scheduleHeader.addView(calendar, calendarSize);
        LinearLayout scheduleTitle = column();
        scheduleTitle.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView nextRuns = label(getString(R.string.next_runs), 15, WHITE, true);
        nextRuns.setGravity(Gravity.CENTER);
        scheduleTitle.addView(nextRuns);
        zone = label("", 12, WHITE, false);
        zone.setGravity(Gravity.CENTER);
        add(scheduleTitle, zone, WRAP, 3);
        scheduleHeader.addView(scheduleTitle, new LinearLayout.LayoutParams(WRAP, WRAP));
        add(schedule, scheduleHeader, WRAP, 0);
        addScheduleList(shell, schedule, 2);
        Button plus = button(getString(R.string.add_time_button), PURPLE, 14, () -> showInterval(null));
        plus.setTextColor(0xffefb9ff);
        plus.setBackground(NeonUi.addTimeFrame(this));
        add(schedule, plus, dp(38), 4);
        addScheduleCard(stackedDashboard() ? page : shell, schedule);
        int warningRed = 0xffff535f;
        TextView controlNotice = centered(getString(R.string.control_warning), compact ? 13 : 15, warningRed, true);
        int warningPadding = dp(compact ? 4 : 8);
        controlNotice.setPadding(dp(8), warningPadding, dp(8), warningPadding);
        controlNotice.setBackground(NeonUi.frame(this, warningRed, 12));

        watch = button(getString(R.string.watch_button), PURPLE, 19, () -> showBrowser(false));
        control = button("CONTROL", ORANGE, 19, () -> showBrowser(true));
        styleDockButton(watch, Icon.WATCH);
        styleDockButton(control, Icon.CONTROL);
        watch.setContentDescription(getString(R.string.watch_accessibility));
        control.setContentDescription(getString(R.string.control_accessibility));
        FrameLayout dock = new FrameLayout(this);
        dock.setPadding(0, dp(5), 0, dp(8));
        dock.setBackgroundColor(DARK);
        LinearLayout actions = column();
        countdown = centered("", compact ? 14 : 16, GOLD, true);
        countdown.setFontFeatureSettings("tnum");
        countdown.setLetterSpacing(.04f);
        countdown.setPadding(dp(8), dp(compact ? 3 : 5), dp(8), dp(compact ? 3 : 5));
        countdown.setBackground(NeonUi.frame(this, GOLD, 12));
        countdown.setVisibility(View.GONE);
        add(actions, countdown, WRAP, 0);
        add(actions, controlNotice, WRAP, 4);
        addCoffeeButton(actions, 4);
        add(actions, two(watch, control, 7, 55), WRAP, 3);
        dock.addView(actions,
                new FrameLayout.LayoutParams(dp(panelWidth), WRAP, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        if (stackedDashboard()) page.addView(dock, new LinearLayout.LayoutParams(MATCH, WRAP));
        else shell.addDock(dock);
        return shell;
    }

    private static final int ACCOUNT_SIDE_PADDING_DP = 13;
    private static final float ACCOUNT_BALANCE_WEIGHT = 1.1f;

    private View terracottaDashboard() {
        DashboardArtwork artwork = new DashboardArtwork(this);
        int ink = TerracottaUi.INK;
        int ivory = TerracottaUi.IVORY;
        boolean compact = compactDashboard();
        DashboardColumn shell = new DashboardColumn(this);
        shell.shrinkScheduleToFit = !stackedDashboard();
        shell.setBackground(TerracottaUi.paper(this));

        ScrollView scroll = new ScrollView(this);
        shell.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        FrameLayout scrollContent = new FrameLayout(this);
        scroll.addView(scrollContent, new ScrollView.LayoutParams(MATCH, WRAP));
        LinearLayout page = column();
        page.setPadding(0, dp(7), 0, dp(6));
        scrollContent.addView(page, new FrameLayout.LayoutParams(dp(panelWidth), WRAP, Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        LinearLayout header = row();
        TextView wordmark = heavy("NACE", 44, ink);
        header.addView(wordmark, new LinearLayout.LayoutParams(0, WRAP, 1f));
        View log = terracottaHeaderButton(Icon.HISTORY, getString(R.string.run_history), this::showHistory);
        View settings = terracottaHeaderButton(Icon.SETTINGS, getString(R.string.settings), this::showSettings);
        header.addView(log, new LinearLayout.LayoutParams(dp(46), dp(46)));
        header.addView(settings, new LinearLayout.LayoutParams(dp(46), dp(46)));
        add(page, header, dp(53), 0);

        statusBanner = row();
        statusBanner.setPadding(dp(15), dp(compact ? 8 : 13), dp(15), dp(compact ? 8 : 13));
        hero = new ImageView(this);
        hero.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        hero.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        statusBanner.addView(hero, new LinearLayout.LayoutParams(dp(53), dp(53)));
        View statusDivider = rule(ivory);
        LinearLayout.LayoutParams bannerDividerSize = new LinearLayout.LayoutParams(dp(1), dp(compact ? 51 : 64));
        bannerDividerSize.setMargins(dp(10), 0, dp(13), 0);
        statusBanner.addView(statusDivider, bannerDividerSize);
        LinearLayout statusCaption = column();
        automation = heavy(getString(R.string.offline), 33, ivory);
        automation.setMaxLines(compact ? 1 : 2);
        automation.setAutoSizeTextTypeUniformWithConfiguration(18, 33, 1, TypedValue.COMPLEX_UNIT_SP);
        add(statusCaption, automation, dp(compact ? 33 : 70), 0);
        background = label(getString(R.string.background_inactive), 14, ivory, false);
        background.setMaxLines(1);
        background.setAutoSizeTextTypeUniformWithConfiguration(10, 15, 1, TypedValue.COMPLEX_UNIT_SP);
        add(statusCaption, background, dp(19), 0);
        statusBanner.addView(statusCaption, new LinearLayout.LayoutParams(0, WRAP, 1f));
        add(page, statusBanner, WRAP, 6);

        LinearLayout account = column();
        account.setBackground(TerracottaUi.frame(this, Color.TRANSPARENT, TerracottaUi.CLAY, 10));
        account.setPadding(dp(ACCOUNT_SIDE_PADDING_DP), dp(5), dp(ACCOUNT_SIDE_PADDING_DP), dp(4));
        LinearLayout accountHeader = row();
        TextView balanceLabel = heavy(getString(R.string.balance), 20, ink);
        balanceLabel.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        balanceLabel.setPadding(0, 0, 0, dp(3));
        balanceLabel.setMaxLines(1);
        balanceLabel.setAutoSizeTextTypeUniformWithConfiguration(13, 20, 1, TypedValue.COMPLEX_UNIT_SP);
        accountHeader.addView(balanceLabel, new LinearLayout.LayoutParams(0, dp(32), ACCOUNT_BALANCE_WEIGHT));
        accountHeader.addView(rule(TerracottaUi.CLAY), new LinearLayout.LayoutParams(dp(1), dp(25)));
        LinearLayout sessionGroup = column();
        sessionGroup.setGravity(Gravity.CENTER_HORIZONTAL);
        add(sessionGroup, centered(getString(R.string.session_status), 11, ink, true), WRAP, 0);
        session = centered(getString(R.string.unavailable_upper), 16, TerracottaUi.RED, true);
        session.setMaxLines(1);
        session.setAutoSizeTextTypeUniformWithConfiguration(10, 16, 1, TypedValue.COMPLEX_UNIT_SP);
        addSessionStatus(sessionGroup, 21);
        accountHeader.addView(sessionGroup, new LinearLayout.LayoutParams(0, WRAP, 1f));
        add(account, accountHeader, WRAP, 0);
        add(account, rule(TerracottaUi.CLAY), dp(1), 3);

        balance = heavy(getString(R.string.unavailable), 34, ink);
        balance.setGravity(Gravity.CENTER);
        balance.setMaxLines(1);
        balance.setAutoSizeTextTypeUniformWithConfiguration(18, 34, 1, TypedValue.COMPLEX_UNIT_SP);
        add(account, balance, dp(40), 0);
        add(page, account, WRAP, 7);

        btc = terracottaPair("BTC", R.string.select_btc, "BTC/USDT");
        eth = terracottaPair("ETH", R.string.select_eth, "ETH/USDT");
        styleCurrency(btc, artwork.coin(DashboardArtwork.Coin.BTC, dp(24)));
        styleCurrency(eth, artwork.coin(DashboardArtwork.Coin.ETH, dp(24)));
        LinearLayout pair = two(btc, eth, 0, compact ? 39 : 43);
        LinearLayout.LayoutParams pairSize = new LinearLayout.LayoutParams(dp(panelWidth * .66f), WRAP);
        pairSize.gravity = Gravity.CENTER_HORIZONTAL;
        pairSize.topMargin = dp(7);
        page.addView(pair, pairSize);

        LinearLayout schedule = column();
        schedule.setBackground(TerracottaUi.frame(this, TerracottaUi.PAPER, TerracottaUi.CLAY, 13));
        schedule.setPadding(dp(10), dp(5), dp(10), dp(3));
        TextView heading = heavy(getString(R.string.next_runs), 25, ink);
        heading.setMaxLines(1);
        heading.setGravity(Gravity.CENTER);
        heading.setAutoSizeTextTypeUniformWithConfiguration(14, 25, 1, TypedValue.COMPLEX_UNIT_SP);
        LinearLayout scheduleHeading = row();
        scheduleHeading.setGravity(Gravity.CENTER);
        ImageView calendar = new ImageView(this);
        calendar.setImageDrawable(icon(Icon.CALENDAR, TerracottaUi.CLAY, 24));
        calendar.setScaleType(ImageView.ScaleType.CENTER);
        calendar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams calendarParams = new LinearLayout.LayoutParams(dp(30), dp(31));
        calendarParams.rightMargin = dp(6);
        scheduleHeading.addView(calendar, calendarParams);
        scheduleHeading.addView(heading, new LinearLayout.LayoutParams(WRAP, dp(31)));
        schedule.addView(scheduleHeading, new LinearLayout.LayoutParams(MATCH, dp(31)));
        zone = label("", 15, TerracottaUi.MUTED, false);
        zone.setGravity(Gravity.CENTER);
        add(schedule, zone, WRAP, 0);
        add(schedule, rule(TerracottaUi.CLAY), dp(1.5f), 9);
        addScheduleList(shell, schedule, 0);
        Button plus = button(getString(R.string.add_time_button), PURPLE, 0, () -> showInterval(null));
        plus.setTextColor(TerracottaUi.CLAY);
        plus.setTextSize(20);
        plus.setBackground(TerracottaUi.button(this, Color.TRANSPARENT, Color.TRANSPARENT, 0));
        add(schedule, plus, dp(44), 0);
        add(page, schedule, WRAP, 8);

        FrameLayout dock = new FrameLayout(this);
        dock.setBackgroundColor(TerracottaUi.PAPER);
        LinearLayout actions = column();
        actions.setPadding(0, dp(5), 0, dp(7));
        TextView warning = centered(getString(R.string.terracotta_control_warning), compact ? 13 : 15,
                TerracottaUi.RED, true);
        countdown = centered("", compact ? 14 : 16, TerracottaUi.CLAY, true);
        countdown.setFontFeatureSettings("tnum");
        countdown.setPadding(dp(8), dp(3), dp(8), dp(3));
        countdown.setBackground(TerracottaUi.frame(this, Color.TRANSPARENT, TerracottaUi.CLAY, 9));
        countdown.setVisibility(View.GONE);
        add(actions, countdown, WRAP, 0);
        add(actions, warning, WRAP, 4);
        watch = button(getString(R.string.watch_button), PURPLE, 9, () -> showBrowser(false));
        control = button("CONTROL", ORANGE, 9, () -> showBrowser(true));
        watch.setContentDescription(getString(R.string.watch_accessibility));
        control.setContentDescription(getString(R.string.control_accessibility));
        styleDockButton(watch, Icon.WATCH);
        styleDockButton(control, Icon.CONTROL);
        addCoffeeButton(actions, 5);
        add(actions, two(watch, control, 7, 52), WRAP, 3);
        dock.addView(actions, new FrameLayout.LayoutParams(dp(panelWidth), WRAP, Gravity.CENTER_HORIZONTAL));
        if (stackedDashboard()) page.addView(dock, new LinearLayout.LayoutParams(MATCH, WRAP));
        else shell.addDock(dock);
        return shell;
    }

    /**
     * With enlarged system text the pinned schedule card and dock leave too little room for the top of
     * the dashboard (BTC/ETH disappeared at 130%). Then everything scrolls as one column. At 100% the
     * layout is exactly as before.
     */
    private boolean stackedDashboard() {
        return getResources().getConfiguration().fontScale > 1.05f;
    }

    private boolean compactDashboard() {
        return getResources().getConfiguration().screenHeightDp < 740;
    }

    private static final class DashboardColumn extends LinearLayout {
        private ScrollView scheduleViewport;

        DashboardColumn(Context context) {
            super(context);
            setOrientation(VERTICAL);
        }

        void addDock(View dock) {
            addView(dock, new LayoutParams(MATCH, WRAP));
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setScheduleHeight(WRAP);
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);

            // Measure the compact schedule first, then give it the unused space above the dock.
            ScrollView overview = (ScrollView) getChildAt(0);
            int extra = overview.getMeasuredHeight() - overview.getChildAt(0).getMeasuredHeight();
            if (extra > 0) {
                setScheduleHeight(scheduleViewport.getMeasuredHeight() + extra);
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            } else if (extra < 0 && shrinkScheduleToFit) {
                // Not enough room (e.g. with the countdown pill): shorten the schedule list so the
                // top of the dashboard (BTC/ETH) stays fully visible, keeping at least one row.
                int minimum = Math.round(62 * getResources().getDisplayMetrics().density);
                int current = scheduleViewport.getMeasuredHeight();
                int shorter = Math.max(minimum, current + extra);
                if (shorter < current) {
                    setScheduleHeight(shorter);
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                }
            }
        }

        /** False when everything scrolls as one column (large system text). */
        boolean shrinkScheduleToFit = true;

        private void setScheduleHeight(int height) {
            ViewGroup.LayoutParams size = scheduleViewport.getLayoutParams();
            if (size.height == height) return;
            size.height = height;
            // Invalidate cached measurements without requesting a new layout during measurement.
            for (View node = scheduleViewport; node != this; node = (View) node.getParent()) {
                node.forceLayout();
            }
        }
    }

    private void addScheduleCard(LinearLayout shell, LinearLayout card) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(panelWidth), WRAP);
        size.gravity = Gravity.CENTER_HORIZONTAL;
        size.topMargin = dp(4);
        size.bottomMargin = dp(2);
        shell.addView(card, size);
    }

    private void addScheduleList(DashboardColumn shell, LinearLayout card, int topMarginDp) {
        int screenHeight = getResources().getConfiguration().screenHeightDp;
        int maximumHeight = dp(screenHeight < 540 ? 48 : screenHeight < 740 ? 88 : 132);
        ScrollView timerScroll = new ScrollView(this) {
            @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                    return;
                }
                int height = maximumHeight;
                if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
                    height = Math.min(height, MeasureSpec.getSize(heightMeasureSpec));
                }
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
            }
        };
        timerScroll.setNestedScrollingEnabled(true);
        timerScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        timerScroll.setScrollbarFadingEnabled(false);
        windows = column();
        timerScroll.addView(windows, new ScrollView.LayoutParams(MATCH, WRAP));
        add(card, timerScroll, WRAP, topMarginDp);
        shell.scheduleViewport = timerScroll;
    }

    private void addCoffeeButton(LinearLayout actions, int topMarginDp) {
        int width = dp(Math.min(194f, panelWidth * .63f));
        Button coffee = NeonUi.button(this, getString(R.string.buy_me_a_coffee));
        coffee.setGravity(Gravity.CENTER);
        coffee.setMaxLines(1);
        // Minimum 7 only matters with very large system text; at 100% the label fits as before.
        coffee.setAutoSizeTextTypeUniformWithConfiguration(7, 16, 1, TypedValue.COMPLEX_UNIT_SP);
        if (terracotta) {
            tintTerracottaButton(coffee, true, 9);
            coffee.setStateListAnimator(null);
            coffee.setPadding(dp(14), 0, dp(14), 0);
            setLeadingIcon(coffee, icon(Icon.COFFEE, TerracottaUi.IVORY, 22), 6);
        } else {
            coffee.setTextColor(0xffffe6bd);
            coffee.setShadowLayer(dp(1), 0, dp(1), 0xff1c0a03);
            GradientDrawable mask = new GradientDrawable();
            mask.setCornerRadius(dp(24));
            mask.setColor(Color.WHITE);
            Drawable copper = new InsetDrawable(getDrawable(R.drawable.coffee_copper), 0, dp(1), 0, dp(1));
            coffee.setBackground(new RippleDrawable(ColorStateList.valueOf(0x35ffd2a0), copper, mask));
            coffee.setPadding(Math.round(width * .28f), 0, Math.round(width * .09f), 0);
        }
        coffee.setOnClickListener(view -> openKoFi());
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(width, dp(48));
        size.gravity = Gravity.CENTER_HORIZONTAL;
        size.topMargin = dp(topMarginDp);
        actions.addView(coffee, size);
    }

    private void openKoFi() {
        Intent intent = new Intent(Intent.ACTION_VIEW, KO_FI_PAGE).addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException error) {
            note(getString(R.string.ko_fi_browser_unavailable));
        }
    }

    private View terracottaHeaderButton(Icon symbol, String caption, Runnable action) {
        ImageView button = new ImageView(this);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setImageDrawable(icon(symbol, TerracottaUi.INK, 32));
        button.setContentDescription(caption);
        button.setBackground(TerracottaUi.button(this, Color.TRANSPARENT, Color.TRANSPARENT, 9));
        button.setOnClickListener(view -> action.run());
        button.setFocusable(true);
        return button;
    }

    private Button terracottaPair(String text, int description, String pair) {
        Button button = button(text, PURPLE, 9, () -> choosePair(pair));
        button.setContentDescription(getString(description));
        button.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        return button;
    }

    private TextView heavy(String text, float size, int color) {
        TextView heading = label(text, size, color, false);
        heading.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        return heading;
    }

    private View rule(int color) {
        View line = new View(this);
        line.setBackgroundColor(color);
        return line;
    }

    private void refresh() {
        if (automation == null) return;
        Snapshot snapshot = service == null ? null : service.getSnapshot();
        boolean isOnline = snapshot != null && snapshot.automationOnline;
        boolean backgroundActive = snapshot != null && snapshot.backgroundActive;
        boolean manualControl = snapshot != null && snapshot.manualControl;
        if (priorReadyGraphic == null || priorReadyGraphic != isOnline) {
            priorReadyGraphic = isOnline;
            renderHero(isOnline);
        }
        if (priorBackground == null || priorBackground != backgroundActive) {
            priorBackground = backgroundActive;
            background.setText(backgroundActive ? getString(R.string.background_active) : getString(R.string.background_inactive));
            if (!terracotta) {
                int tone = backgroundActive ? 0xff31ff38 : RED;
                background.setTextColor(backgroundActive ? 0xffecffe9 : RED);
                background.setShadowLayer(dp(5), 0, 0, tone);
                setLeadingIcon(background, icon(Icon.LAYERS, backgroundActive ? GREEN : RED, 22), 10);
                backgroundPill.setBackground(NeonUi.frame(this, tone, 23));
            }
        }
        automation.setText(isOnline ? getString(R.string.online)
                : manualControl ? getString(R.string.paused) : getString(R.string.offline));
        if (!terracotta) {
            automation.setTextColor(isOnline ? GREEN : manualControl ? GOLD : RED);
            automation.setShadowLayer(dp(9), 0, 0, isOnline ? 0xc970f677 : 0xa1fa3e8e);
        }
        if (manualControl) automation.setContentDescription(getString(R.string.manual_accessibility));
        else automation.setContentDescription(null);

        renderBalance(snapshot);
        renderSession(snapshot);
        String pair = snapshot == null ? "" : snapshot.pair;
        if (!pair.equals(priorPair)) {
            priorPair = pair;
            if (terracotta) {
                setTerracottaPair(btc, true, pair.equals("BTC/USDT"));
                setTerracottaPair(eth, false, pair.equals("ETH/USDT"));
            } else {
                setSelected(btc, pair.equals("BTC/USDT"), 25);
                setSelected(eth, pair.equals("ETH/USDT"), 25);
            }
        }
        if (priorWatch == null) {
            // WATCH / CONTROL are fixed-color raised keys (DockKey); they no longer toggle like BTC/ETH.
            priorWatch = true;
            styleDockKey(watch, true);
            styleDockKey(control, false);
        }
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        zone.setText(getString(R.string.phone_time, now.format(DateTimeFormatter.ofPattern("'UTC'xxx", Locale.ROOT))));
        renderWindows(snapshot, now);
        renderViewerStatus(snapshot);
        renderCountdown();
        updateStartupDialog(snapshot);
        updateAttention(snapshot);
        // Required settings come first; the automatic sign-in / link prompts wait until they are on.
        boolean prerequisitesMet = !startupSignInComplete || missingPrerequisite() == null;
        if (resumed && prerequisitesMet && guideDialog == null
                && exchangeLinkDialog == null && signInDialog == null && service != null) {
            if (service.takeLinkRecoveryPrompt()) {
                if (startupSignInComplete) {
                    showAttention("issue:LINK", new AutomationService.Attention(
                            AutomationService.AttentionKind.LINK, "", "link recovery"));
                } else {
                    showExchangeLinkDialog();
                }
            } else {
                String issue = service.takeSignInHelp();
                if (issue != null) {
                    if (viewerMode == CONTROL_VIEWER && service.isVerificationPrefilled() && viewerCaption != null) {
                        viewerCaption.setText(issue);
                        viewerCaption.setTextColor(uiColor(GOLD));
                        service.scrollViewerToVerification();
                    } else if (viewerMode == CONTROL_VIEWER || snapshot != null && snapshot.session == AutomationService.SessionState.SIGNED_IN) {
                        note(issue);
                    } else {
                        if (viewer != null) dismissBrowser();
                        viewerSignInIssue = issue;
                        showBrowser(true);
                    }
                } else if (startupSignInComplete && viewer == null && service.takeCredentialPrompt()) {
                    // Explain first ("login expired"), then offer Sign in.
                    showAttention("issue:LOGIN", new AutomationService.Attention(
                            AutomationService.AttentionKind.LOGIN, "", "sign-in needed"));
                }
            }
        }
    }

    /** "NEXT TRADE IN 01:23:45", or "TRADE WINDOW OPEN NOW" while a window runs. */
    private void renderCountdown() {
        if (countdown == null) return;
        long ms = service == null ? -1 : service.millisToNextTrade();
        if (ms < 0) {
            countdown.setVisibility(View.GONE);
            return;
        }
        String text;
        if (ms == 0) {
            text = getString(R.string.trade_window_open);
        } else {
            long seconds = (ms + 999) / 1000;
            long hours = seconds / 3600;
            long minutes = seconds % 3600 / 60;
            String clock = hours > 0
                    ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds % 60)
                    : String.format(Locale.ROOT, "%02d:%02d", minutes, seconds % 60);
            text = getString(R.string.next_trade_in, clock);
        }
        if (!text.contentEquals(countdown.getText())) countdown.setText(text);
        int tone = ms == 0 ? GREEN : GOLD;
        countdown.setTextColor(uiColor(tone));
        countdown.setVisibility(View.VISIBLE);
    }

    private void renderBalance(Snapshot snapshot) {
        String amount = snapshot != null && snapshot.balance != null ? snapshot.balance : getString(R.string.unavailable);
        java.math.BigDecimal daily = snapshot == null ? null : snapshot.dailyBalanceChange;
        if (daily == null && !terracotta) {
            if (!amount.contentEquals(balance.getText())) balance.setText(amount);
            balance.setContentDescription(null);
            return;
        }
        int sign = daily == null ? 0 : daily.signum();
        String delta = daily == null ? "" : (sign > 0 ? "+" : "") + daily.toPlainString();
        String display = delta.isEmpty() ? amount : amount + "\u2009" + delta;
        if (display.contentEquals(balance.getText()) && balance.getText() instanceof Spanned) return;
        SpannableString amountWithDelta = new SpannableString(display);
        int color = uiColor(sign > 0 ? GREEN : sign < 0 ? RED : MUTED);
        // The Terracotta amount has the whole card's width for the delta, with the amount
        // centered under the left header cell.
        float cardContentWidth = dp(panelWidth) - 2 * dp(ACCOUNT_SIDE_PADDING_DP);
        float dividerWidth = dp(1);
        float centerOffset = terracotta
                ? (dividerWidth + (cardContentWidth - dividerWidth) / (ACCOUNT_BALANCE_WEIGHT + 1f)) / 2f : 0;
        amountWithDelta.setSpan(new BalanceLineSpan(amount, delta, color, dp(3), centerOffset),
                0, display.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        balance.setText(amountWithDelta);
        balance.setContentDescription(delta.isEmpty() ? null : amount + ". " + getString(R.string.daily_balance_change, delta));
    }

    private void renderHero(boolean isOnline) {
        if (terracotta) {
            statusBanner.setBackground(TerracottaUi.banner(this, isOnline));
            hero.setImageDrawable(icon(isOnline ? Icon.CHECK_CIRCLE : Icon.CROSS_CIRCLE, TerracottaUi.IVORY, 48));
            return;
        }
        int resource = asset(isOnline ? "hero_online" : "hero_offline");
        if (resource != 0) {
            hero.setImageResource(resource);
        } else {
            hero.setImageResource(android.R.drawable.ic_menu_manage);
            hero.setColorFilter(isOnline ? GREEN : RED);
            hero.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        }
    }

    private void addSessionStatus(LinearLayout group, int height) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(WRAP, dp(height));
        size.topMargin = dp(1);
        group.addView(session, size);
    }

    private void renderSession(Snapshot snapshot) {
        AutomationService.SessionState state = snapshot == null ? AutomationService.SessionState.UNAVAILABLE : snapshot.session;
        if (priorSession == state) return;
        priorSession = state;
        session.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null);
        session.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
        switch (state) {
            case SIGNED_IN:
                session.setText(getString(R.string.signed_in));
                session.setTextColor(uiColor(GREEN));
                if (!terracotta) session.setShadowLayer(dp(6), 0, 0, 0xc52ae348);
                session.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
                session.setGravity(Gravity.CENTER);
                session.setPadding(0, 0, 0, 0);
                setLeadingIcon(session, icon(Icon.CHECK_CIRCLE, uiColor(GREEN), terracotta ? 17 : 22), 5);
                break;
            case SIGNED_OUT:
                session.setText(getString(R.string.signed_out));
                session.setTextColor(uiColor(RED));
                break;
            case CHECKING:
                session.setText(getString(R.string.checking));
                session.setTextColor(uiColor(GOLD));
                break;
            default:
                session.setText(getString(R.string.unavailable_upper));
                session.setTextColor(uiColor(RED));
        }
        if (state != AutomationService.SessionState.SIGNED_IN) {
            session.setGravity(Gravity.CENTER);
            session.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
            session.setPadding(0, 0, 0, 0);
        }
    }

    private void renderWindows(Snapshot snapshot, ZonedDateTime now) {
        if (snapshot == null) return;
        ZoneId scheduleZone = service.getScheduleZone();
        Map<TradingWindow, DailySchedule.Occurrence> displayed = new LinkedHashMap<>();
        for (TradingWindow window : snapshot.windows) {
            displayed.put(window, displayOccurrence(now, window, scheduleZone));
        }
        List<TradingWindow> ordered = new ArrayList<>(snapshot.windows);
        ordered.sort(Comparator.comparing(window -> displayed.get(window).from.toInstant()));
        StringBuilder signature = new StringBuilder(now.toLocalDate().toString()).append(now.getZone());
        List<List<RunResult>> results = new ArrayList<>();
        for (TradingWindow window : ordered) {
            DailySchedule.Occurrence occurrence = displayed.get(window);
            List<RunResult> slot = resultsFor(snapshot, occurrence);
            results.add(slot);
            signature.append('|').append(occurrence.interval(now.getZone(), "–"));
            for (RunResult result : slot) signature.append(':').append(result.status).append(result.signalId);
        }
        if (signature.toString().equals(priorWindows)) return;
        priorWindows = signature.toString();
        windows.removeAllViews();
        if (snapshot.windows.isEmpty()) {
            add(windows, label(getString(R.string.empty_schedule), 13, MUTED, false), WRAP, 4);
        }
        for (int index = 0; index < ordered.size(); index++) {
            TradingWindow window = ordered.get(index);
            List<RunResult> slot = results.get(index);
            String caption = displayed.get(window).interval(now.getZone(), " – ");
            String outcome = null;
            int accent = ORANGE;
            int textColor = GOLD;
            if (!slot.isEmpty()) {
                long verified = slot.stream().filter(result -> "submitted".equals(result.status)).count();
                boolean hasFailure = slot.stream().anyMatch(result -> "failed".equals(result.status) || "skipped".equals(result.status));
                boolean hasUnverified = slot.stream().anyMatch(result -> "confirmation_tapped".equals(result.status) || "pending".equals(result.status));
                outcome = slot.size() == 1 ? resultLabel(slot.get(0).status)
                        : getString(R.string.result_signal_count, verified, slot.size());
                if (hasFailure) { accent = RED; textColor = RED; }
                else if (hasUnverified) { accent = GOLD; textColor = GOLD; }
                else if (verified == slot.size()) { accent = GREEN; textColor = GREEN; }
            }
            if (terracotta) {
                if (slot.size() == 1 && "submitted".equals(slot.get(0).status)) outcome = getString(R.string.terracotta_verified);
                addTerracottaRun(window, caption, outcome, textColor);
                continue;
            }
            if (outcome != null) caption += "   ·   " + outcome;
            Button item = button(caption, accent, 21, () -> showInterval(window));
            item.setTextColor(textColor);
            item.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            item.setPadding(dp(19), dp(3), dp(12), dp(3));
            item.setMaxLines(slot.isEmpty() ? 1 : 2);
            item.setAutoSizeTextTypeUniformWithConfiguration(11, slot.isEmpty() ? 19 : 15, 1, TypedValue.COMPLEX_UNIT_SP);
            setLeadingIcon(item, icon(Icon.CLOCK, textColor, 25), 13);
            add(windows, item, dp(40), 3);
        }
    }

    private void addTerracottaRun(TradingWindow window, String time, String outcome, int color) {
        LinearLayout item = row();
        item.setPadding(dp(10), 0, dp(7), 0);
        item.setBackground(TerracottaUi.button(this, Color.TRANSPARENT, Color.TRANSPARENT, 0));
        item.setOnClickListener(view -> showInterval(window));
        item.setFocusable(true);
        item.setContentDescription(outcome == null ? time : time + " · " + outcome);
        TextView timeText = heavy(time, 23, TerracottaUi.INK);
        timeText.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        timeText.setMaxLines(1);
        timeText.setAutoSizeTextTypeUniformWithConfiguration(13, 23, 1, TypedValue.COMPLEX_UNIT_SP);
        item.addView(timeText, new LinearLayout.LayoutParams(0, MATCH, 1.8f));
        if (outcome != null) {
            item.addView(rule(TerracottaUi.RULE), new LinearLayout.LayoutParams(dp(1), dp(24)));
            TextView result = centered(outcome, 17, uiColor(color), true);
            result.setPadding(dp(4), 0, 0, 0);
            result.setMaxLines(2);
            result.setAutoSizeTextTypeUniformWithConfiguration(11, 17, 1, TypedValue.COMPLEX_UNIT_SP);
            item.addView(result, new LinearLayout.LayoutParams(0, MATCH, 1f));
        }
        add(windows, item, dp(43), 0);
        add(windows, rule(TerracottaUi.RULE), dp(.6f), 0);
    }

    private void setTerracottaPair(Button button, boolean first, boolean selected) {
        button.setSelected(selected);
        button.setTextColor(selected ? TerracottaUi.IVORY : TerracottaUi.INK);
        button.setBackground(TerracottaUi.segment(this, first, selected));
    }

    private List<RunResult> resultsFor(Snapshot snapshot, DailySchedule.Occurrence occurrence) {
        List<RunResult> matching = new ArrayList<>();
        RunResult windowResult = null;
        for (RunResult result : snapshot.results) {
            if (snapshot.pair.equals(result.pair) && result.occurrence().overlaps(occurrence)) {
                if (result.signalId == null) windowResult = result;
                else matching.add(result);
            }
        }
        if (matching.isEmpty() && windowResult != null) matching.add(windowResult);
        return matching;
    }

    private String resultLabel(String status) {
        switch (status) {
            case "submitted": return getString(R.string.result_submitted);
            case "confirmation_tapped": return getString(R.string.result_tapped);
            case "dry_run": return getString(R.string.result_dry_run);
            case "skipped": return getString(R.string.result_skipped);
            case "failed": return getString(R.string.result_failed);
            case "manual": return getString(R.string.result_manual);
            default: return getString(R.string.result_pending);
        }
    }

    /** Switching what NACE trades always asks first: an accidental tap must not change the pair. */
    private void choosePair(String pair) {
        if (!hasService()) return;
        Snapshot snapshot = service.getSnapshot();
        String current = snapshot == null ? null : snapshot.pair;
        if (pair.equals(current)) return;
        String next = pair.split("/")[0];
        String previous = current == null ? "" : current.split("/")[0];
        String title = getString(R.string.pair_switch_title, next);
        Dialog dialog = newDialog(title);
        LinearLayout body = dialogPanel(title);
        add(body, label(getString(R.string.pair_switch_body, next, previous), 16, WHITE, false), WRAP, 10);
        Button keep = button(getString(R.string.pair_switch_no, previous), PURPLE, 10, dialog::dismiss);
        Button change = button(getString(R.string.pair_switch_yes), ORANGE, 10, () -> {
            dialog.dismiss();
            if (!hasService()) return;
            try {
                service.setPair(pair);
                refresh();
            } catch (RuntimeException exception) {
                note(safeMessage(exception, getString(R.string.pair_change_error)));
            }
        });
        add(body, two(keep, change, 7, 50), WRAP, 14);
        setScrollableContent(dialog, body);
        showDialog(dialog, false);
    }

    private void showInterval(TradingWindow selected) {
        if (!hasService()) return;
        Dialog dialog = newDialog(getString(R.string.scheduled_interval));
        LinearLayout content = dialogPanel(selected == null ? getString(R.string.add_time) : getString(R.string.edit_interval));
        ZoneId scheduleZone = service.getScheduleZone();
        ZonedDateTime phoneNow = ZonedDateTime.now(ZoneId.systemDefault());
        ZoneId phoneZone = phoneNow.getZone();
        TradingWindow startingWindow = selected == null ? new TradingWindow("12:05", "12:24") : selected;
        DailySchedule.Occurrence displayed = displayOccurrence(phoneNow, startingWindow, scheduleZone);
        ZonedDateTime shownStart = displayed.from.withZoneSameInstant(phoneZone);
        ZonedDateTime shownEnd = displayed.until.withZoneSameInstant(phoneZone);
        LocalTime[] values = {shownStart.toLocalTime(), shownEnd.toLocalTime()};
        TextView timingNote = label(intervalTiming(values), 13, MUTED, false);
        add(content, timingNote, WRAP, 4);
        int[] current = {0};
        Button starts = button(getString(R.string.starts_at, values[0].format(CLOCK)), ORANGE, 12, () -> {});
        Button ends = button(getString(R.string.ends_at, values[1].format(CLOCK)), PURPLE, 12, () -> {});
        add(content, two(starts, ends, 6, 57), WRAP, 10);
        TimePicker picker = new TimePicker(terracotta
                ? new ContextThemeWrapper(this, R.style.NaceTerracottaControls) : this);
        picker.setIs24HourView(true);
        picker.setHour(values[0].getHour());
        picker.setMinute(values[0].getMinute());
        picker.setContentDescription(getString(R.string.start_time));
        add(content, picker, WRAP, 3);
        TextView error = label("", 13, RED, true);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        error.setVisibility(View.GONE);
        add(content, error, WRAP, 3);

        picker.setOnTimeChangedListener((view, hour, minute) -> {
            values[current[0]] = LocalTime.of(hour, minute);
            starts.setText(getString(R.string.starts_at, values[0].format(CLOCK)));
            ends.setText(getString(R.string.ends_at, values[1].format(CLOCK)));
            timingNote.setText(intervalTiming(values));
            error.setVisibility(View.GONE);
        });
        starts.setOnClickListener(view -> pickEndpoint(picker, values, current, 0, starts, ends));
        ends.setOnClickListener(view -> pickEndpoint(picker, values, current, 1, starts, ends));
        Button save = button(getString(R.string.save_interval), ORANGE, 12, () -> {
            picker.clearFocus();
            values[current[0]] = LocalTime.of(picker.getHour(), picker.getMinute());
            if (values[0].equals(values[1])) {
                showError(error, getString(R.string.interval_same_times));
                return;
            }
            if (service == null) {
                showError(error, getString(R.string.service_unavailable));
                return;
            }
            List<TradingWindow> next = new ArrayList<>(service.getWindows());
            if (selected != null) next.removeIf(existing -> sameWindow(existing, selected));
            try {
                LocalDate localDay = shownStart.toLocalDate();
                LocalDate endDay = values[1].isAfter(values[0]) ? localDay : localDay.plusDays(1);
                String start = anchorTime(values[0], localDay, shownStart, phoneZone, scheduleZone);
                String end = anchorTime(values[1], endDay, shownEnd, phoneZone, scheduleZone);
                next.add(new TradingWindow(start, end));
                service.setWindows(next);
                dialog.dismiss();
                refresh();
            } catch (RuntimeException exception) {
                showError(error, safeMessage(exception, getString(R.string.interval_save_error)));
            }
        });
        Button cancel = button(getString(R.string.cancel), PURPLE, 12, dialog::dismiss);
        add(content, two(cancel, save, 7, 44), WRAP, 9);
        if (selected != null) {
            Button remove = button(getString(R.string.remove_interval), PURPLE, 12,
                    () -> confirmRemove(dialog, selected, error));
            add(content, remove, dp(42), 5);
        }
        setScrollableContent(dialog, content);
        showDialog(dialog, false);
    }

    private String intervalTiming(LocalTime[] values) {
        return values[0].isAfter(values[1]) ? getString(R.string.interval_next_day) : getString(R.string.interval_daily);
    }

    private static DailySchedule.Occurrence displayOccurrence(ZonedDateTime now, TradingWindow window, ZoneId scheduleZone) {
        return DailySchedule.forDisplayDay(now, LocalTime.parse(window.start), LocalTime.parse(window.end), scheduleZone);
    }

    private static String anchorTime(LocalTime time, LocalDate day, ZonedDateTime original,
            ZoneId phoneZone, ZoneId scheduleZone) {
        ZonedDateTime endpoint = time.equals(original.toLocalTime()) && day.equals(original.toLocalDate())
                ? original : ZonedDateTime.of(day, time, phoneZone);
        return endpoint.withZoneSameInstant(scheduleZone).format(CLOCK);
    }

    private void pickEndpoint(TimePicker picker, LocalTime[] values, int[] current, int index,
            Button starts, Button ends) {
        picker.clearFocus();
        current[0] = index;
        // The callbacks from setting the hour/minute must not alter the saved endpoint.
        LocalTime time = values[index];
        picker.setHour(time.getHour());
        picker.setMinute(time.getMinute());
        picker.setContentDescription(index == 0 ? getString(R.string.start_time) : getString(R.string.end_time));
        setSelected(starts, index == 0, 12);
        setSelected(ends, index == 1, 12);
    }

    private void confirmRemove(Dialog editor, TradingWindow selected, TextView error) {
        Dialog confirm = newDialog(getString(R.string.remove_interval));
        LinearLayout body = dialogPanel(getString(R.string.remove_interval_title));
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        DailySchedule.Occurrence occurrence = displayOccurrence(now, selected, service.getScheduleZone());
        add(body, label(getString(R.string.interval_remove_confirm,
                        occurrence.from.withZoneSameInstant(now.getZone()).format(CLOCK),
                        occurrence.until.withZoneSameInstant(now.getZone()).format(CLOCK)),
                14, MUTED, false), WRAP, 6);
        Button cancel = button(getString(R.string.cancel), PURPLE, 12, confirm::dismiss);
        Button remove = button(getString(R.string.remove), ORANGE, 12, () -> {
            confirm.dismiss();
            if (service == null) {
                showError(error, getString(R.string.service_unavailable));
                return;
            }
            List<TradingWindow> next = new ArrayList<>(service.getWindows());
            next.removeIf(existing -> sameWindow(existing, selected));
            try {
                service.setWindows(next);
                editor.dismiss();
                refresh();
            } catch (RuntimeException exception) {
                showError(error, safeMessage(exception, getString(R.string.interval_remove_error)));
            }
        });
        add(body, two(cancel, remove, 7, 44), WRAP, 12);
        confirm.setContentView(body);
        showDialog(confirm, false);
    }

    private boolean sameWindow(TradingWindow a, TradingWindow b) {
        return a.start.equals(b.start) && a.end.equals(b.end);
    }

    private void showExchangeLinkDialog() {
        if (!hasService() || exchangeLinkDialog != null) return;
        String title = getString(R.string.exchange_link_may_have_changed);
        Dialog dialog = newDialog(title);
        exchangeLinkDialog = dialog;
        LinearLayout body = dialogPanel(title);
        add(body, label(getString(R.string.exchange_link_instructions), 14, MUTED, false), WRAP, 8);
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setTextColor(uiColor(WHITE));
        input.setHint(R.string.exchange_link_address);
        input.setContentDescription(getString(R.string.exchange_link_address));
        add(body, input, WRAP, 8);
        TextView error = label("", 13, RED, false);
        error.setVisibility(View.GONE);
        add(body, error, WRAP, 6);
        Button cancel = button(getString(R.string.cancel), PURPLE, 10, dialog::dismiss);
        Button send = button(getString(R.string.exchange_link_send), ORANGE, 10, () -> {
            ExchangeLink next;
            try {
                next = ExchangeLink.parse(input.getText().toString());
            } catch (IllegalArgumentException exception) {
                showError(error, getString(R.string.exchange_link_invalid));
                return;
            }
            if (!hasService()) return;
            try {
                dismissBrowser();
                service.setExchangeLink(next);
                dialog.dismiss();
                if (!startupSignInComplete) dismissStartupDialog();
                if (startupPermissionsGranted() && !service.hasSavedSignIn()) showCredentialDialog();
            } catch (IllegalStateException exception) {
                showError(error, safeMessage(exception, getString(R.string.exchange_link_save_error)));
            }
        });
        add(body, two(cancel, send, 7, 46), WRAP, 8);
        body.setFocusableInTouchMode(true);
        body.requestFocus();
        setScrollableContent(dialog, body);
        dialog.setOnDismissListener(ignored -> {
            if (exchangeLinkDialog == dialog) exchangeLinkDialog = null;
        });
        showDialog(dialog, false);
    }

    private void showBrowser(boolean manual) {
        if (!hasService() || viewer != null) return;
        WebView browser;
        AutomationService current = service;
        try {
            browser = current.openViewer(manual);
            if (browser == null) {
                note(getString(R.string.browser_busy));
                return;
            }
        } catch (RuntimeException exception) {
            note(safeMessage(exception, getString(R.string.browser_open_error)));
            return;
        }
        detach(browser);
        Dialog dialog = newDialog(manual ? getString(R.string.take_control) : getString(R.string.watch));
        viewer = dialog;
        viewerMode = manual ? CONTROL_VIEWER : WATCH_VIEWER;
        viewerBrowser = browser;
        viewerService = current;
        LinearLayout root = dialogPanel(manual ? getString(R.string.take_control_title) : getString(R.string.watch_title));
        boolean prefilled = manual && current.isVerificationPrefilled();
        String caption = prefilled ? current.verificationPrefilledCaption()
                : manual && viewerSignInIssue != null ? viewerSignInIssue
                : getString(manual ? R.string.control_caption : R.string.watch_caption);
        viewerSignInIssue = null;
        viewerCaption = label(caption, 13, prefilled ? GOLD : MUTED, prefilled);
        add(root, viewerCaption, WRAP, 4);
        FrameLayout webFrame = new FrameLayout(this) {
            @Override public boolean onInterceptTouchEvent(android.view.MotionEvent event) {
                if (viewerMode == CONTROL_VIEWER) restartControlIdleTimer();
                return false;
            }
        };
        webFrame.setBackgroundColor(terracotta ? TerracottaUi.PAPER : DARK);
        LinearLayout.LayoutParams browserSize = new LinearLayout.LayoutParams(MATCH, 0, 1f);
        browserSize.topMargin = dp(9);
        root.addView(webFrame, browserSize);
        webFrame.addView(browser, new FrameLayout.LayoutParams(MATCH, MATCH));
        viewerStatus = centered("", 14, WHITE, true);
        viewerStatus.setMinHeight(dp(42));
        viewerStatus.setPadding(dp(6), dp(6), dp(6), dp(6));
        viewerStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(root, viewerStatus, WRAP, 8);
        Button done = button(manual ? getString(R.string.exit_control) : getString(R.string.close_viewer),
                manual ? ORANGE : PURPLE, 12, this::dismissBrowser);
        add(root, done, dp(44), 8);
        dialog.setContentView(root);
        dialog.setOnDismissListener(ignored -> releaseBrowser(dialog));
        if (manual) restartControlIdleTimer();
        try {
            showDialog(dialog, true);
            if (prefilled) {
                webFrame.post(() -> {
                    if (viewer == dialog) current.scrollViewerToVerification();
                });
                // Again once the viewer has its final size: code field and Login button both visible.
                webFrame.postDelayed(() -> {
                    if (viewer == dialog) current.scrollViewerToVerification();
                }, 900);
            }
        } catch (RuntimeException exception) {
            dismissBrowser();
            note(safeMessage(exception, getString(R.string.viewer_open_error)));
        }
        refresh();
    }

    private void renderViewerStatus(Snapshot snapshot) {
        if (viewerStatus == null) return;
        if (viewerMode == CONTROL_VIEWER && viewerCaption != null && snapshot != null
                && snapshot.session == AutomationService.SessionState.SIGNED_IN) {
            viewerCaption.setText(R.string.control_caption);
            viewerCaption.setTextColor(uiColor(MUTED));
        }
        boolean watchOpen = viewerMode == WATCH_VIEWER;
        boolean online = watchOpen && snapshot != null && snapshot.automationOnline && service.isWatchPairConfirmed();
        boolean checking = watchOpen && (snapshot == null || snapshot.session == AutomationService.SessionState.CHECKING
                || service.isCheckingWatchPair());
        // Name the pair, so it is clear WATCH is going to (and showing) the pair chosen in the app.
        String chosen = snapshot == null ? "" : snapshot.pair;
        String caption = online ? getString(R.string.viewer_automation_on_pair, chosen)
                : checking ? getString(R.string.viewer_opening_pair, chosen)
                : getString(R.string.viewer_automation_off);
        if (caption.contentEquals(viewerStatus.getText())) return;
        int tone = online ? GREEN : checking ? GOLD : RED;
        viewerStatus.setText(caption);
        viewerStatus.setTextColor(uiColor(tone));
        viewerStatus.setBackground(terracotta
                ? TerracottaUi.frame(this, Color.TRANSPARENT, uiColor(tone), 9)
                : NeonUi.frame(this, tone, 12));
    }

    private void dismissBrowser() {
        Dialog dialog = viewer;
        if (dialog == null) return;
        releaseBrowser(dialog);
        dialog.dismiss();
    }

    private static final long CONTROL_IDLE_MS = 5 * 60 * 1000;
    private final Runnable closeIdleControl = () -> {
        if (viewerMode != CONTROL_VIEWER) return;
        DiagnosticLog.log(this, "control", "closed after 5 minutes without use");
        dismissBrowser();
        note(getString(R.string.control_auto_closed));
    };

    /** A forgotten CONTROL keeps automation paused, so it closes after 5 minutes without a touch. */
    private void restartControlIdleTimer() {
        handler.removeCallbacks(closeIdleControl);
        handler.postDelayed(closeIdleControl, CONTROL_IDLE_MS);
    }

    private void releaseBrowser(Dialog dialog) {
        if (viewer != dialog) return;
        handler.removeCallbacks(closeIdleControl);
        WebView browser = viewerBrowser;
        AutomationService owner = viewerService;
        viewer = null;
        viewerMode = 0;
        viewerBrowser = null;
        viewerService = null;
        viewerStatus = null;
        viewerCaption = null;
        detach(browser);
        try {
            owner.closeViewer();
        } catch (RuntimeException exception) {
            note(safeMessage(exception, getString(R.string.browser_background_error)));
        }
        refresh();
    }

    private void updateStartupDialog(Snapshot snapshot) {
        if (!startupSignInComplete && startupPermissionsGranted() && snapshot != null
                && snapshot.session == AutomationService.SessionState.SIGNED_IN) {
            startupSignInComplete = true;
            getPreferences(MODE_PRIVATE).edit().putBoolean(STARTUP_SIGN_IN_COMPLETE, true).apply();
        }
        if (startupSignInComplete || viewer != null) {
            dismissStartupDialog();
            return;
        }
        if (!resumed) return;
        if (startupDialog == null) showStartupDialog();
        boolean wakeups = hasWakeupPermission();
        boolean notifications = notificationsEnabled();
        boolean battery = batteryUnrestricted();
        boolean unlocked = wakeups && notifications && battery;
        setStartupStep(startupWakeupStep, R.string.setup_step_wakeups, wakeups);
        setStartupStep(startupNotificationStep, R.string.setup_step_notifications, notifications);
        setStartupStep(startupBatteryStep, R.string.setup_step_battery, battery);
        startupBatteryPanel.setVisibility(battery ? View.GONE : View.VISIBLE);
        startupSignInStep.setText(getString(R.string.setup_step_sign_in));
        startupSignInStep.setTextColor(uiColor(unlocked ? GOLD : MUTED));
        startupWakeupPanel.setVisibility(wakeups ? View.GONE : View.VISIBLE);
        startupNotificationPanel.setVisibility(notifications ? View.GONE : View.VISIBLE);
        startupSignInLock.setVisibility(unlocked ? View.GONE : View.VISIBLE);
        setTreeEnabled(startupSignInPanel, unlocked);
        startupSignInPanel.setAlpha(unlocked ? 1f : .4f);
        boolean signingIn = service != null && service.signInPending();
        startupSignInButton.setText(getString(signingIn ? R.string.sign_in_in_progress : R.string.sign_in));
        startupSignInButton.setEnabled(unlocked && !signingIn);
    }

    /** Step header: green with a check mark once done, gold while it still needs doing. */
    private void setStartupStep(TextView step, int title, boolean done) {
        step.setText(getString(done ? R.string.setup_step_done : R.string.setup_step_todo, getString(title)));
        step.setTextColor(uiColor(done ? GREEN : GOLD));
    }

    private static void setTreeEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) setTreeEnabled(group.getChildAt(i), enabled);
    }

    private void showStartupDialog() {
        Dialog dialog = newDialog(getString(R.string.setup));
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        LinearLayout content = dialogPanel(getString(R.string.setup_title));
        addLanguageChoices(content, dialog);
        add(content, label(getString(R.string.setup_caption),
                14, WHITE, false), WRAP, 9);
        startupWakeupStep = label("", 15, GOLD, true);
        add(content, startupWakeupStep, WRAP, 12);
        startupWakeupPanel = column();
        add(startupWakeupPanel, label(getString(R.string.wakeups_description),
                13, MUTED, false), WRAP, 0);
        add(startupWakeupPanel, button(getString(R.string.allow_wakeups), PURPLE, 12, this::openWakeupSettings), dp(44), 5);
        add(content, startupWakeupPanel, WRAP, 4);
        startupNotificationStep = label("", 15, GOLD, true);
        add(content, startupNotificationStep, WRAP, 12);
        startupNotificationPanel = column();
        add(startupNotificationPanel, label(getString(R.string.setup_notifications_description),
                13, MUTED, false), WRAP, 0);
        add(startupNotificationPanel, button(getString(R.string.enable_notifications), PURPLE, 12, this::enableNotifications), dp(44), 5);
        add(content, startupNotificationPanel, WRAP, 4);
        startupBatteryStep = label("", 15, GOLD, true);
        add(content, startupBatteryStep, WRAP, 12);
        startupBatteryPanel = column();
        add(startupBatteryPanel, label(getString(R.string.setup_battery_description),
                13, MUTED, false), WRAP, 0);
        add(startupBatteryPanel, button(getString(R.string.allow_battery), PURPLE, 12, this::requestBatteryExemption), dp(44), 5);
        add(content, startupBatteryPanel, WRAP, 4);
        startupSignInStep = label("", 15, MUTED, true);
        add(content, startupSignInStep, WRAP, 12);
        startupSignInLock = label(getString(R.string.setup_sign_in_locked), 13, RED, true);
        add(content, startupSignInLock, WRAP, 4);
        startupSignInPanel = column();
        startupSignInButton = addCredentialForm(startupSignInPanel, () -> {});
        add(content, startupSignInPanel, WRAP, 0);
        setScrollableContent(dialog, content);
        startupDialog = dialog;
        showDialog(dialog, false);
    }

    private void showCredentialDialog() {
        if (!hasService() || signInDialog != null) return;
        Dialog dialog = newDialog(getString(R.string.sign_in));
        signInDialog = dialog;
        LinearLayout body = dialogPanel(getString(R.string.sign_in));
        addCredentialForm(body, dialog::dismiss);
        add(body, button(getString(R.string.cancel), PURPLE, 10, dialog::dismiss), dp(44), 7);
        setScrollableContent(dialog, body);
        dialog.setOnDismissListener(ignored -> {
            if (signInDialog != dialog) return;
            signInDialog = null;
            if (service != null) service.takeCredentialPrompt();
        });
        showDialog(dialog, false);
    }

    private Button addCredentialForm(LinearLayout body, Runnable started) {
        String address = service == null ? ExchangeLink.DEFAULT_URL : service.getExchangeLink();
        String host = Uri.parse(address).getAuthority();
        add(body, label(getString(R.string.remember_sign_in_description, host), 13, MUTED, false), WRAP, 9);
        EditText dial = credentialField(R.string.sign_in_dialing_code, InputType.TYPE_CLASS_PHONE);
        EditText phone = credentialField(R.string.sign_in_phone, InputType.TYPE_CLASS_PHONE);
        EditText password = credentialField(R.string.sign_in_password,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        dial.setText("+40");
        LinearLayout numbers = row();
        numbers.addView(dial, new LinearLayout.LayoutParams(dp(80), WRAP));
        numbers.addView(phone, new LinearLayout.LayoutParams(0, WRAP, 1f));
        add(body, numbers, WRAP, 5);
        add(body, password, WRAP, 5);
        TextView error = label("", 13, RED, false);
        error.setVisibility(View.GONE);
        add(body, error, WRAP, 4);
        Button submit = button(getString(R.string.sign_in), ORANGE, 12, () -> {
            if (!hasService()) return;
            try {
                String currentHost = Uri.parse(service.getExchangeLink()).getAuthority();
                if (!java.util.Objects.equals(host, currentHost)) {
                    showError(error, getString(R.string.sign_in_address_changed));
                    return;
                }
                if (viewer != null) dismissBrowser();
                service.beginSignIn(dial.getText().toString().trim(),
                        phone.getText().toString().replace(" ", ""), password.getText().toString());
                password.getText().clear();
                error.setVisibility(View.GONE);
                started.run();
                refresh();
            } catch (IllegalArgumentException exception) {
                showError(error, getString(R.string.sign_in_invalid_fields));
            } catch (IllegalStateException exception) {
                showError(error, safeMessage(exception, getString(R.string.browser_open_error)));
            }
        });
        add(body, submit, dp(44), 12);
        return submit;
    }

    private EditText credentialField(int label, int type) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(type);
        field.setTextColor(uiColor(WHITE));
        field.setHintTextColor(uiColor(MUTED));
        field.setHint(label);
        field.setContentDescription(getString(label));
        field.setSaveEnabled(false);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        return field;
    }

    // ---------- "How NACE works" guide (once after first setup, and from Settings) ----------

    private static final String GUIDE_SHOWN = "how_it_works_shown";
    private Dialog guideDialog;

    private void showGuide() {
        if (guideDialog != null) return;
        String title = getString(R.string.guide_title);
        Dialog dialog = newDialog(title);
        dialog.setCanceledOnTouchOutside(false);
        LinearLayout body = dialogPanel(title);
        // Everything in the guide is centered: title, intro, numbers, text and samples.
        if (body.getChildAt(0) instanceof TextView) ((TextView) body.getChildAt(0)).setGravity(Gravity.CENTER);
        add(body, centered(getString(R.string.guide_intro), 15, WHITE, false), WRAP, 8);

        guidePoint(body, 1, getString(R.string.guide_1));
        guideSample(body, guideCurrencies(), dp(44));
        guidePoint(body, 2, getString(R.string.guide_2, plainLabel(R.string.add_time_button)));
        guideSample(body, guideAddTime(), dp(38));
        guidePoint(body, 3, getString(R.string.guide_3, getString(R.string.watch_button)));
        guideSample(body, guideDockKey(true), dp(55));
        guidePoint(body, 4, getString(R.string.guide_4));
        guideSample(body, guideDockKey(false), dp(55));
        guidePoint(body, 5, getString(R.string.guide_5));
        guideSample(body, guideGreenSigns(), WRAP);
        guidePoint(body, 6, getString(R.string.guide_6));
        guidePoint(body, 7, getString(R.string.guide_7));
        guidePoint(body, 8, getString(R.string.guide_8));
        guidePoint(body, 9, getString(R.string.guide_9));
        guidePoint(body, 10, getString(R.string.guide_10));

        add(body, button(getString(R.string.guide_ok), ORANGE, 12, dialog::dismiss), dp(50), 16);
        dialog.setOnDismissListener(ignored -> {
            if (guideDialog == dialog) guideDialog = null;
            getPreferences(MODE_PRIVATE).edit().putBoolean(GUIDE_SHOWN, true).apply();
        });
        setScrollableContent(dialog, body);
        guideDialog = dialog;
        showDialog(dialog, true);
        DiagnosticLog.log(this, "app", "showed How NACE works guide");
    }

    private String plainLabel(int resource) {
        // Non-breaking space keeps "+ Add time" together on one line.
        return getString(resource).replace("\uff0b", "+").trim().replaceAll("\\s+", "\u00a0");
    }

    /** "1" in a gold circle-like badge, then the explanation. */
    /** Gold number badge centered above the centered explanation. */
    private void guidePoint(LinearLayout body, int number, String text) {
        LinearLayout block = column();
        block.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView badge = label(String.valueOf(number), 17, GOLD, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(terracotta ? TerracottaUi.frame(this, Color.TRANSPARENT, TerracottaUi.CLAY, 15)
                : NeonUi.frame(this, GOLD, 15));
        block.addView(badge, new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView words = centered(text, 15, WHITE, false);
        LinearLayout.LayoutParams wordsSize = new LinearLayout.LayoutParams(MATCH, WRAP);
        wordsSize.topMargin = dp(6);
        block.addView(words, wordsSize);
        add(body, block, WRAP, 18);
    }

    /** A real copy of a dashboard element, centered under its point. Not clickable. */
    private void guideSample(LinearLayout body, View sample, int height) {
        disableTree(sample);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(MATCH, height);
        size.topMargin = dp(8);
        body.addView(sample, size);
    }

    private static void disableTree(View view) {
        view.setClickable(false);
        view.setFocusable(false);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) disableTree(group.getChildAt(i));
    }

    private View guideCurrencies() {
        DashboardArtwork artwork = new DashboardArtwork(this);
        Button btc = button("BTC", ORANGE, 22, () -> {});
        Button eth = button("ETH", PURPLE, 22, () -> {});
        styleCurrency(btc, artwork.coin(DashboardArtwork.Coin.BTC, dp(22)));
        styleCurrency(eth, artwork.coin(DashboardArtwork.Coin.ETH, dp(22)));
        if (terracotta) {
            setTerracottaPair(btc, true, true);
            setTerracottaPair(eth, false, false);
        } else {
            setSelected(btc, true, 22);
            setSelected(eth, false, 22);
        }
        LinearLayout pair = row();
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, MATCH, 1f);
        first.rightMargin = dp(7);
        pair.addView(btc, first);
        pair.addView(eth, new LinearLayout.LayoutParams(0, MATCH, 1f));
        return pair;
    }

    private View guideAddTime() {
        Button plus = button(getString(R.string.add_time_button), PURPLE, 14, () -> {});
        if (!terracotta) {
            plus.setTextColor(0xffefb9ff);
            plus.setBackground(NeonUi.addTimeFrame(this));
        }
        return plus;
    }

    private View guideDockKey(boolean isWatch) {
        Button key = button(isWatch ? getString(R.string.watch_button) : "CONTROL", PURPLE, 19, () -> {});
        styleCenteredDashboardButton(key);
        styleDockKey(key, isWatch);
        LinearLayout holder = row();
        holder.setGravity(Gravity.CENTER_HORIZONTAL);
        holder.addView(key, new LinearLayout.LayoutParams(dp(Math.min(230f, panelWidth * .58f)), MATCH));
        return holder;
    }

    /** The green "ready" signs exactly as the dashboard draws them. */
    private View guideGreenSigns() {
        LinearLayout signs = column();
        signs.setGravity(Gravity.CENTER_HORIZONTAL);
        if (terracotta) {
            LinearLayout banner = row();
            banner.setGravity(Gravity.CENTER);
            banner.setBackground(TerracottaUi.banner(this, true));
            ImageView mark = new ImageView(this);
            mark.setImageDrawable(icon(Icon.CHECK_CIRCLE, TerracottaUi.IVORY, 30));
            banner.addView(mark, new LinearLayout.LayoutParams(dp(36), dp(36)));
            add(signs, banner, dp(52), 0);
        } else {
            ImageView hero = new ImageView(this);
            int online = asset("hero_online");
            if (online != 0) hero.setImageResource(online);
            hero.setScaleType(ImageView.ScaleType.FIT_CENTER);
            add(signs, hero, dp(70), 0);
        }
        TextView ready = centered(getString(R.string.online), 18, GREEN, true);
        if (!terracotta) ready.setShadowLayer(dp(9), 0, 0, 0xc970f677);
        add(signs, ready, WRAP, 2);
        TextView background = centered(getString(R.string.background_active), 12, GREEN, true);
        background.setLetterSpacing(.05f);
        setLeadingIcon(background, icon(Icon.LAYERS, uiColor(GREEN), 18), 8);
        if (!terracotta) {
            background.setTextColor(0xffecffe9);
            background.setBackground(NeonUi.frame(this, 0xff31ff38, 23));
        }
        background.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams pill = new LinearLayout.LayoutParams(WRAP, dp(32));
        pill.topMargin = dp(4);
        pill.gravity = Gravity.CENTER_HORIZONTAL;
        signs.addView(background, pill);
        TextView connected = centered(getString(R.string.signed_in), 15, GREEN, true);
        if (!terracotta) connected.setShadowLayer(dp(6), 0, 0, 0xc52ae348);
        setLeadingIcon(connected, icon(Icon.CHECK_CIRCLE, uiColor(GREEN), 20), 5);
        LinearLayout.LayoutParams session = new LinearLayout.LayoutParams(WRAP, WRAP);
        session.topMargin = dp(6);
        session.gravity = Gravity.CENTER_HORIZONTAL;
        signs.addView(connected, session);
        return signs;
    }

    // ---------- Required settings and "NACE needs attention" help ----------

    private Dialog attentionDialog;
    private String attentionKey;
    private TextView attentionStatus;
    private AutomationService.AttentionKind seenAttention;
    private long seenAttentionSince;
    private boolean awaitingRecovery;
    private boolean internetCheckRunning;
    private final java.util.Set<AutomationService.AttentionKind> postponedAttention =
            java.util.EnumSet.noneOf(AutomationService.AttentionKind.class);

    private boolean batteryUnrestricted() {
        PowerManager power = getSystemService(PowerManager.class);
        return power == null || power.isIgnoringBatteryOptimizations(getPackageName());
    }

    /** What a successful trade needs on this phone, in the order the user should fix it. */
    private String missingPrerequisite() {
        if (!notificationsEnabled()) return "notifications";
        if (!hasWakeupPermission()) return "wakeups";
        if (!batteryUnrestricted()) return "battery";
        return null;
    }

    private void updateAttention(Snapshot snapshot) {
        if (!resumed || !startupSignInComplete || viewer != null || signInDialog != null
                || exchangeLinkDialog != null || startupDialog != null || guideDialog != null) return;
        String missing = missingPrerequisite();
        DiagnosticLog.change(this, "prerequisites", missing == null ? "all met" : "MISSING: " + missing);
        if (missing != null) {
            showAttention("pre:" + missing, null);
            return;
        }
        if (service == null) return;
        AutomationService.Attention problem = service.attention();
        DiagnosticLog.change(this, "attention", problem == null ? null : problem.kind + ": " + problem.issue);
        if (problem == null) {
            seenAttention = null;
            boolean signedIn = snapshot != null && snapshot.session == AutomationService.SessionState.SIGNED_IN;
            boolean issueOpen = attentionKey != null && attentionKey.startsWith("issue:");
            if (signedIn && (awaitingRecovery || issueOpen)) {
                awaitingRecovery = false;
                showAttention("ready", null);
            } else if (attentionKey != null && attentionKey.startsWith("pre:")) {
                dismissAttention();
            } else if (signedIn && attentionDialog == null && guideDialog == null
                    && !getPreferences(MODE_PRIVATE).getBoolean(GUIDE_SHOWN, false)) {
                showGuide();
            }
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (problem.kind != seenAttention) {
            seenAttention = problem.kind;
            seenAttentionSince = now;
        }
        // Only for problems that last a few seconds, not for a check that is still running.
        if (now - seenAttentionSince < 4000 || postponedAttention.contains(problem.kind)) return;
        showAttention("issue:" + problem.kind.name(), problem);
    }

    private void showAttention(String key, AutomationService.Attention problem) {
        if (key.equals(attentionKey) && attentionDialog != null && attentionDialog.isShowing()) return;
        dismissAttention();
        attentionKey = key;
        boolean required = key.startsWith("pre:");
        boolean ready = key.equals("ready");
        String title = getString(ready ? R.string.ready_title : required ? R.string.prereq_title : R.string.attention_title);
        Dialog dialog = newDialog(title);
        dialog.setCancelable(!required);
        dialog.setCanceledOnTouchOutside(false);
        LinearLayout body = dialogPanel(title);
        switch (key) {
            case "pre:notifications":
                add(body, label(getString(R.string.prereq_notifications), 16, WHITE, false), WRAP, 10);
                add(body, button(getString(R.string.enable_notifications), PURPLE, 12, this::enableNotifications), dp(48), 12);
                add(body, label(getString(R.string.prereq_notifications_tip), 14, GOLD, false), WRAP, 12);
                break;
            case "pre:wakeups":
                add(body, label(getString(R.string.prereq_wakeups), 16, WHITE, false), WRAP, 10);
                add(body, button(getString(R.string.allow_wakeups), PURPLE, 12, this::openWakeupSettings), dp(48), 12);
                break;
            case "pre:battery":
                add(body, label(getString(R.string.prereq_battery), 16, WHITE, false), WRAP, 10);
                add(body, button(getString(R.string.allow_battery), PURPLE, 12, this::requestBatteryExemption), dp(48), 12);
                break;
            case "ready":
                add(body, label(getString(R.string.ready_for_trade), 20, GREEN, true), WRAP, 12);
                add(body, button(getString(R.string.close), PURPLE, 12, this::dismissAttention), dp(48), 14);
                break;
            default:
                addIssueHelp(body, problem);
        }
        setScrollableContent(dialog, body);
        attentionDialog = dialog;
        showDialog(dialog, false);
        DiagnosticLog.log(this, "app", "showed popup: " + key);
    }

    private void addIssueHelp(LinearLayout body, AutomationService.Attention problem) {
        AutomationService.AttentionKind kind = problem.kind;
        switch (kind) {
            case INTERNET:
                add(body, label(getString(R.string.attention_internet), 17, RED, true), WRAP, 10);
                add(body, label(getString(R.string.attention_internet_help), 15, WHITE, false), WRAP, 8);
                attentionStatus = label("", 16, WHITE, true);
                attentionStatus.setVisibility(View.GONE);
                add(body, attentionStatus, WRAP, 10);
                add(body, button(getString(R.string.check_internet), ORANGE, 12, this::checkInternetFromPopup), dp(48), 12);
                break;
            case LOGIN:
                add(body, label(getString(R.string.attention_login), 17, RED, true), WRAP, 10);
                add(body, button(getString(R.string.sign_in), ORANGE, 12, () -> {
                    awaitingRecovery = true;
                    dismissAttention();
                    if (startupPermissionsGranted()) showCredentialDialog();
                }), dp(48), 12);
                break;
            case LINK:
                add(body, label(getString(R.string.attention_link), 17, RED, true), WRAP, 10);
                if (!problem.reason.isEmpty()) add(body, label(problem.reason, 14, MUTED, false), WRAP, 6);
                add(body, button(getString(R.string.enter_new_link), ORANGE, 12, () -> {
                    awaitingRecovery = true;
                    dismissAttention();
                    showExchangeLinkDialog();
                }), dp(48), 12);
                break;
            default:
                add(body, label(problem.reason, 17, RED, true), WRAP, 10);
                add(body, label(getString(R.string.attention_other_help, getString(R.string.watch)), 15, WHITE, false), WRAP, 8);
                add(body, button(getString(R.string.watch), ORANGE, 12, () -> {
                    dismissAttention();
                    showBrowser(false);
                }), dp(48), 12);
        }
        add(body, button(getString(R.string.later), PURPLE, 12, () -> {
            postponedAttention.add(kind);
            dismissAttention();
        }), dp(44), 8);
    }

    private void checkInternetFromPopup() {
        if (!hasService() || internetCheckRunning || attentionStatus == null) return;
        internetCheckRunning = true;
        attentionStatus.setVisibility(View.VISIBLE);
        attentionStatus.setTextColor(uiColor(WHITE));
        attentionStatus.setText(R.string.checking_internet);
        service.checkInternetNow(online -> runOnUiThread(() -> {
            internetCheckRunning = false;
            if (online) awaitingRecovery = true;
            if (attentionStatus == null) return;
            attentionStatus.setTextColor(uiColor(online ? GREEN : RED));
            attentionStatus.setText(online ? R.string.internet_ok_checking : R.string.internet_still_down);
        }));
    }

    private void requestBatteryExemption() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException | SecurityException error) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void dismissAttention() {
        if (attentionDialog != null) attentionDialog.dismiss();
        attentionDialog = null;
        attentionKey = null;
        attentionStatus = null;
    }

    /** Settings > Share diagnostic log: the recent log as text, to send to support. */
    private void shareDiagnosticLog() {
        DiagnosticLog.log(this, "app", "diagnostic log shared");
        String header = "NACE " + installedVersion() + " · Android " + Build.VERSION.RELEASE + " (SDK "
                + Build.VERSION.SDK_INT + ") · " + Build.MANUFACTURER + " " + Build.MODEL + "\n\n";
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_EMAIL, new String[]{SUPPORT_EMAIL})
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_log_subject))
                .putExtra(Intent.EXTRA_TEXT, header + DiagnosticLog.tail(this, 1500));
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share_log)));
        } catch (ActivityNotFoundException error) {
            note(getString(R.string.support_mail_unavailable, SUPPORT_EMAIL));
        }
    }

    private void dismissStartupDialog() {
        if (startupDialog == null) return;
        startupDialog.dismiss();
        startupDialog = null;
        startupWakeupPanel = null;
        startupSignInButton = null;
        startupNotificationPanel = null;
        startupBatteryStep = null;
        startupBatteryPanel = null;
        startupWakeupStep = null;
        startupNotificationStep = null;
        startupSignInStep = null;
        startupSignInLock = null;
        startupSignInPanel = null;
    }

    private boolean hasWakeupPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || WindowWakeups.canSchedule(this);
    }

    /** Setup steps 1-3: wake-ups, notifications and background running (battery unrestricted). */
    private boolean startupPermissionsGranted() {
        return hasWakeupPermission() && notificationsEnabled() && batteryUnrestricted();
    }

    private boolean needsNotificationPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED;
    }

    private boolean notificationsEnabled() {
        return getSystemService(NotificationManager.class).areNotificationsEnabled();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (service != null) service.refreshNotificationLanguage();
        refresh();
    }

    private void enableNotifications() {
        if (needsNotificationPermission() && !getPreferences(MODE_PRIVATE).getBoolean(NOTIFICATION_PROMPT_SHOWN, false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean(NOTIFICATION_PROMPT_SHOWN, true).apply();
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
        } else {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        }
    }

    private void addLanguageChoices(LinearLayout content, Dialog dialog) {
        String selected = AppLanguage.current(this);
        add(content, label(getString(R.string.language), 13, WHITE, true), WRAP, 12);
        Button english = languageButton("English", AppLanguage.ENGLISH, selected, dialog);
        Button romanian = languageButton("Română", AppLanguage.ROMANIAN, selected, dialog);
        Button spanish = languageButton("Español", AppLanguage.SPANISH, selected, dialog);
        LinearLayout choices = row();
        for (Button choice : new Button[] {english, romanian, spanish}) {
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(42), 1f);
            if (choice != english) size.setMarginStart(dp(6));
            choice.setTextSize(14);
            choice.setPadding(dp(2), 0, dp(2), 0);
            choices.addView(choice, size);
        }
        add(content, choices, WRAP, 5);
    }

    private Button languageButton(String name, String language, String selected, Dialog dialog) {
        Button choice = button(name, language.equals(selected) ? ORANGE : PURPLE, 12, () -> {
            if (!AppLanguage.set(this, language)) return;
            if (service != null) service.refreshNotificationLanguage();
            dialog.dismiss();
            recreate();
        });
        choice.setSelected(language.equals(selected));
        return choice;
    }

    private void openWakeupSettings() {
        Intent permission = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(permission);
        } catch (ActivityNotFoundException | SecurityException exception) {
            note(getString(R.string.wakeups_error));
        }
    }
    private void showSettings() {
        Dialog dialog = newDialog(getString(R.string.settings));
        LinearLayout content = dialogPanel(getString(R.string.settings_title));
        Snapshot status = service == null ? null : service.getSnapshot();
        TextView automationStatus = label(status == null ? getString(R.string.service_connecting) : status.message,
                13, MUTED, false);
        add(content, automationStatus, WRAP, 5);
        if (status != null) {
            if (hasText(status.lastCheck)) {
                add(content, label(getString(R.string.last_session_check, status.lastCheck), 12, MUTED, false), WRAP, 5);
            }
        }
        Button automationSwitch = button("", RED, 12, () -> {});
        refreshAutomationSwitch(automationSwitch);
        automationSwitch.setOnClickListener(view -> {
            if (!hasService()) return;
            try {
                service.setAutomationStopped(!service.isAutomationStopped());
                refreshAutomationSwitch(automationSwitch);
                automationStatus.setText(service.getSnapshot().message);
                refresh();
            } catch (RuntimeException error) {
                note(safeMessage(error, getString(R.string.automation_switch_error)));
            }
        });
        add(content, automationSwitch, dp(44), 10);
        Button alertSounds = button(blockerSoundLabel(), PURPLE, 12, () -> {});
        alertSounds.setOnClickListener(view -> {
            AutomationBlockerAlert.setSoundsEnabled(this, !AutomationBlockerAlert.soundsEnabled(this));
            alertSounds.setText(blockerSoundLabel());
        });
        add(content, alertSounds, dp(44), 6);
        Button usageStats = button(usageStatsLabel(), PURPLE, 12, () -> {});
        usageStats.setOnClickListener(view -> {
            UsagePing.setEnabled(this, !UsagePing.enabled(this));
            usageStats.setText(usageStatsLabel());
        });
        add(content, usageStats, dp(44), 6);
        if (status != null && status.session != AutomationService.SessionState.SIGNED_IN) {
            add(content, button(getString(R.string.sign_in), ORANGE, 12, () -> {
                dialog.dismiss();
                // Same rule as the first screen: notifications and wake-ups first.
                if (startupPermissionsGranted()) showCredentialDialog();
                else refresh();
            }), dp(44), 6);
        }
        addLanguageChoices(content, dialog);
        add(content, label(getString(R.string.appearance_theme), 13, WHITE, true), WRAP, 12);
        add(content, two(themeButton(false, dialog), themeButton(true, dialog), 8, 42), WRAP, 5);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !WindowWakeups.canSchedule(this)) {
            add(content, button(getString(R.string.allow_wakeups), PURPLE, 12, () -> {
                openWakeupSettings();
                dialog.dismiss();
            }), dp(44), 5);
        }
        if (!notificationsEnabled()) {
            add(content, button(getString(R.string.enable_notifications), PURPLE, 12, () -> {
                dialog.dismiss();
                enableNotifications();
            }), dp(44), 5);
        }
        add(content, button(getString(R.string.contact_support), PURPLE, 12, this::contactSupport), dp(44), 9);
        add(content, button(getString(R.string.guide_open), PURPLE, 12, () -> {
            dialog.dismiss();
            showGuide();
        }), dp(44), 5);
        add(content, button(getString(R.string.share_log), PURPLE, 12, this::shareDiagnosticLog), dp(44), 5);
        add(content, button(getString(R.string.eula_title), PURPLE, 12, this::showEula), dp(44), 5);
        add(content, button(getString(R.string.close), PURPLE, 12, dialog::dismiss), dp(44), 5);
        add(content, centered(getString(R.string.app_version, installedVersion()), 11, MUTED, false), WRAP, 8);
        setScrollableContent(dialog, content);
        showDialog(dialog, false);
    }

    private String blockerSoundLabel() {
        return getString(AutomationBlockerAlert.soundsEnabled(this)
                ? R.string.blocker_sounds_on : R.string.blocker_sounds_off);
    }

    private String usageStatsLabel() {
        return getString(UsagePing.enabled(this) ? R.string.usage_stats_on : R.string.usage_stats_off);
    }

    private void refreshAutomationSwitch(Button button) {
        boolean stopped = service != null && service.isAutomationStopped();
        button.setText(stopped ? R.string.start_automation : R.string.stop_automation);
        button.setEnabled(service != null);
        int color = stopped ? GREEN : RED;
        button.setTextColor(uiColor(color));
        button.setBackground(terracotta
                ? TerracottaUi.frame(this, Color.TRANSPARENT, uiColor(color), 12)
                : NeonUi.frame(this, color, 12));
    }

    private void contactSupport() {
        Intent mail = new Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", SUPPORT_EMAIL, null));
        try {
            startActivity(mail);
        } catch (ActivityNotFoundException error) {
            note(getString(R.string.support_mail_unavailable, SUPPORT_EMAIL));
        }
    }

    private String installedVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException error) {
            return getString(R.string.unavailable);
        }
    }

    private void showEula() {
        Dialog dialog = newDialog(getString(R.string.eula_title));
        LinearLayout content = dialogPanel(getString(R.string.eula_title));
        StringBuilder terms = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getResources().openRawResource(R.raw.eula), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) terms.append(line).append('\n');
        } catch (IOException error) {
            note(getString(R.string.eula_unavailable));
            return;
        }
        add(content, label(terms.toString().trim(), 14, WHITE, false), WRAP, 8);
        add(content, button(getString(R.string.close), PURPLE, 12, dialog::dismiss), dp(44), 12);
        setScrollableContent(dialog, content);
        showDialog(dialog, false);
    }

    private Button themeButton(boolean useTerracotta, Dialog dialog) {
        boolean selected = terracotta == useTerracotta;
        Button choice = button(getString(useTerracotta ? R.string.theme_terracotta : R.string.theme_neon),
                selected ? ORANGE : PURPLE, 9, () -> {
                    if (terracotta == useTerracotta) return;
                    getPreferences(MODE_PRIVATE).edit().putBoolean(TERRACOTTA_THEME, useTerracotta).apply();
                    dialog.dismiss();
                    recreate();
                });
        choice.setSelected(selected);
        return choice;
    }

    private void showHistory() {
        Dialog dialog = newDialog(getString(R.string.run_history));
        LinearLayout content = dialogPanel(getString(R.string.history_title));
        Snapshot snapshot = service == null ? null : service.getSnapshot();
        if (snapshot == null || snapshot.results.isEmpty()) {
            add(content, label(getString(R.string.no_runs), 14, MUTED, false), WRAP, 8);
        } else {
            for (RunResult result : snapshot.results) {
                LinearLayout entry = column();
                entry.setBackground(terracotta ? TerracottaUi.frame(this, Color.TRANSPARENT, TerracottaUi.RULE, 9)
                        : NeonUi.frame(this, PURPLE, 12));
                entry.setPadding(dp(9), dp(8), dp(9), dp(8));
                entry.addView(label(resultLabel(result.status) + " · " + result.pair, 14, GOLD, true));
                add(entry, label(result.occurrence().displayLabel(ZoneId.systemDefault()), 12, WHITE, false), WRAP, 4);
                if (result.signalId != null) {
                    add(entry, label(getString(R.string.result_signal_id, result.signalId), 12, MUTED, false), WRAP, 3);
                }
                if (hasText(result.message)) {
                    add(entry, label(result.message, 12, MUTED, false), WRAP, 3);
                }
                add(content, entry, WRAP, 6);
            }
        }
        add(content, button(getString(R.string.close), PURPLE, 12, dialog::dismiss), dp(44), 8);
        setScrollableContent(dialog, content);
        showDialog(dialog, false);
    }

    private final java.util.Set<Dialog> trackedDialogs =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    private Dialog newDialog(String title) {
        Dialog dialog = new Dialog(this);
        trackedDialogs.add(dialog);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setTitle(title);
        return dialog;
    }

    private void showDialog(Dialog dialog, boolean full) {
        dialog.show();
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(terracotta ? .42f : .80f);
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        window.setLayout(full ? width - dp(18) : Math.min(width - dp(20), dp(380)),
                full ? (int) (height * .89f) : WRAP);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    private void setScrollableContent(Dialog dialog, LinearLayout content) {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new ScrollView.LayoutParams(MATCH, WRAP));
        dialog.setContentView(scroll);
    }

    private LinearLayout dialogPanel(String title) {
        LinearLayout result = column();
        result.setBackground(terracotta ? TerracottaUi.frame(this, TerracottaUi.PAPER, TerracottaUi.CLAY, 12)
                : NeonUi.frame(this, PURPLE, 19));
        result.setPadding(dp(13), dp(13), dp(13), dp(13));
        TextView heading = label(title, 18, GOLD, true);
        heading.setLetterSpacing(.035f);
        result.addView(heading);
        return result;
    }

    private void showError(TextView error, String message) {
        error.setText(message);
        error.setVisibility(View.VISIBLE);
    }

    private boolean hasService() {
        if (service != null) return true;
        note(getString(R.string.still_connecting));
        return false;
    }

    private void note(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private String safeMessage(RuntimeException error, String fallback) {
        return (error instanceof IllegalArgumentException || error instanceof IllegalStateException)
                && error.getMessage() != null ? AutomationService.localize(this, error.getMessage()) : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static void detach(View view) {
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
    }

    private int asset(String name) { return getResources().getIdentifier(name, "drawable", getPackageName()); }
    private int dp(float value) { return NeonUi.dp(this, value); }
    private TextView label(String text, float size, int color, boolean bold) {
        return NeonUi.text(this, text, size, uiColor(color), bold);
    }

    private int uiColor(int color) {
        if (!terracotta) return color;
        if (color == WHITE) return TerracottaUi.INK;
        if (color == MUTED) return TerracottaUi.MUTED;
        if (color == GOLD || color == ORANGE) return TerracottaUi.CLAY;
        if (color == GREEN) return TerracottaUi.GREEN;
        if (color == RED) return TerracottaUi.RED;
        return color;
    }
    private TextView centered(String text, float size, int color, boolean bold) {
        TextView result = label(text, size, color, bold);
        result.setGravity(Gravity.CENTER);
        return result;
    }
    private LinearLayout row() {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.HORIZONTAL);
        result.setGravity(Gravity.CENTER_VERTICAL);
        return result;
    }
    private LinearLayout column() {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }
    private Button button(String label, int accent, int radius, Runnable action) {
        Button result = NeonUi.button(this, label);
        if (terracotta) tintTerracottaButton(result, accent == ORANGE || accent == GOLD, radius);
        else NeonUi.setButtonAccent(result, accent, radius);
        result.setOnClickListener(view -> action.run());
        return result;
    }
    private Drawable icon(Icon icon, int color, int sizeDp) {
        return DashboardIcons.icon(icon, color, dp(sizeDp));
    }
    private void setLeadingIcon(TextView view, Drawable drawable, int gapDp) {
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null);
        view.setCompoundDrawablePadding(dp(gapDp));
    }
    private void styleCurrency(Button button, Drawable coin) {
        styleCenteredDashboardButton(button);
        setCenteredButtonIcon(button, coin);
    }

    private void styleDockButton(Button button, Icon symbol) {
        styleCenteredDashboardButton(button);
        setCenteredButtonIcon(button, icon(symbol, WHITE, 29));
    }

    private void styleDockKey(Button button, boolean isWatch) {
        // One shared look for both keys, never the purple/orange that means "selected" on BTC/ETH.
        int text = DockKey.textColor();
        button.setBackground(terracotta ? DockKey.terracotta(this, 9) : DockKey.neon(this, 19));
        button.setTextColor(text);
        button.setShadowLayer(dp(2), 0, dp(1), DockKey.textShadow());
        // Keep the label centered on the key face, above the 5 dp lip.
        button.setPadding(dp(8), 0, dp(8), dp(5));
        setCenteredButtonIcon(button, icon(isWatch ? Icon.WATCH : Icon.CONTROL, text, 29));
    }

    private void styleCenteredDashboardButton(Button button) {
        button.setMaxLines(1);
        button.setGravity(Gravity.CENTER);
        button.setAutoSizeTextTypeUniformWithConfiguration(12, 19, 1, TypedValue.COMPLEX_UNIT_SP);
        button.setPadding(dp(8), 0, dp(8), 0);
    }

    private void setCenteredButtonIcon(Button button, Drawable drawable) {
        SpannableString caption = new SpannableString(button.getText().toString());
        caption.setSpan(new IconLabelSpan(drawable, dp(6)), 0, caption.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        button.setText(caption);
    }

    private static final class BalanceLineSpan extends ReplacementSpan {
        private final String amount;
        private final String delta;
        private final int deltaColor;
        private final int gap;
        private final float centerOffset;

        BalanceLineSpan(String amount, String delta, int deltaColor, int gap, float centerOffset) {
            this.amount = amount;
            this.delta = delta;
            this.deltaColor = deltaColor;
            this.gap = gap;
            this.centerOffset = centerOffset;
        }

        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt metrics) {
            if (metrics != null) paint.getFontMetricsInt(metrics);
            return (int) Math.ceil(paint.measureText(amount) + 2 * sideWidth(paint));
        }

        private float sideWidth(Paint paint) {
            if (delta.isEmpty()) return 0;
            float size = paint.getTextSize();
            paint.setTextSize(size * .55f);
            float width = paint.measureText(delta);
            paint.setTextSize(size);
            return gap + width;
        }

        @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top,
                                   int baseline, int bottom, Paint paint) {
            // Mirror the delta's space on the left so the amount itself stays centered.
            float amountStart = x + sideWidth(paint) - centerOffset;
            canvas.drawText(amount, amountStart, baseline, paint);
            if (delta.isEmpty()) return;
            float deltaStart = amountStart + paint.measureText(amount) + gap;
            float size = paint.getTextSize();
            int color = paint.getColor();
            float deltaBaseline = baseline + paint.ascent() / 2f;
            paint.setTextSize(size * .55f);
            paint.setColor(deltaColor);
            canvas.drawText(delta, deltaStart, deltaBaseline, paint);
            paint.setColor(color);
            paint.setTextSize(size);
        }
    }

    private static final class IconLabelSpan extends ReplacementSpan {
        private final Drawable icon;
        private final int gap;

        IconLabelSpan(Drawable icon, int gap) {
            this.icon = icon;
            this.gap = gap;
            icon.setBounds(0, 0, icon.getIntrinsicWidth(), icon.getIntrinsicHeight());
        }

        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt metrics) {
            if (metrics != null) {
                paint.getFontMetricsInt(metrics);
                int extraHeight = Math.max(0, icon.getBounds().height() - (metrics.descent - metrics.ascent));
                metrics.ascent -= extraHeight / 2;
                metrics.descent += extraHeight - extraHeight / 2;
                metrics.top = Math.min(metrics.top, metrics.ascent);
                metrics.bottom = Math.max(metrics.bottom, metrics.descent);
            }
            return (int) Math.ceil(icon.getBounds().width() + gap + paint.measureText(text, start, end));
        }

        @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top,
                                   int baseline, int bottom, Paint paint) {
            int saved = canvas.save();
            canvas.translate(x, (top + bottom - icon.getBounds().height()) / 2f);
            icon.draw(canvas);
            canvas.restoreToCount(saved);
            canvas.drawText(text, start, end, x + icon.getBounds().width() + gap, baseline, paint);
        }
    }

    private void setSelected(Button target, boolean selected, int radius) {
        target.setSelected(selected);
        if (terracotta) tintTerracottaButton(target, selected, radius);
        else NeonUi.setButtonAccent(target, selected ? ORANGE : PURPLE, radius);
    }
    private void tintTerracottaButton(Button button, boolean selected, int radius) {
        button.setTextColor(selected ? TerracottaUi.IVORY : TerracottaUi.INK);
        button.setBackground(TerracottaUi.button(this, selected ? TerracottaUi.CLAY : Color.TRANSPARENT,
                selected ? TerracottaUi.CLAY : TerracottaUi.INK, radius));
    }
    private void add(LinearLayout parent, View child, int height, int topDp) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(MATCH, height);
        size.topMargin = dp(topDp);
        parent.addView(child, size);
    }
    private LinearLayout two(View left, View right, int gapDp, int heightDp) {
        LinearLayout group = row();
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, dp(heightDp), 1f);
        first.rightMargin = dp(gapDp);
        group.addView(left, first);
        group.addView(right, new LinearLayout.LayoutParams(0, dp(heightDp), 1f));
        return group;
    }
}
