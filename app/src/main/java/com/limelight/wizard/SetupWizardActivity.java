package com.limelight.wizard;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.limelight.Game;
import com.limelight.R;
import com.limelight.binding.PlatformBinding;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.profiles.ProfilesManager;
import com.limelight.profiles.SettingsProfile;
import com.limelight.ui.EclipseBackgroundView;
import com.limelight.utils.PenumbraToast;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.UiHelper;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Setup wizard (PEN-8): a short survey, device / host / network probes, optional
 * trial streams, then recommended settings that can be applied or saved as a profile.
 */
public class SetupWizardActivity extends AppCompatActivity {

    public static final String EXTRA_PC_UUID = "UUID";
    public static final String EXTRA_PC_NAME = "Name";

    private static final int STEP_SURVEY = 0;
    private static final int STEP_DEVICE = 1;
    private static final int STEP_HOST = 2;
    private static final int STEP_NETWORK = 3;
    private static final int STEP_STREAM = 4;
    private static final int STEP_RESULTS = 5;

    private static final String[] STEP_TITLES = {
            "Your style", "This device", "Host PC", "Network", "Stream tests", "Results"};
    private static final String[] STEP_SUBTITLES = {
            "Four quick questions", "Screen, decoders, power", "Encoders and display",
            "Latency and jitter", "Real streams, measured", "Your settings"};

    private static final int STATE_PENDING = 0;
    private static final int STATE_ACTIVE = 1;
    private static final int STATE_DONE = 2;
    private static final int STATE_SKIPPED = 3;

    private static final int REQUEST_TRIAL = 0x7E57;
    private static final int TRIAL_WARMUP_MS = 4000;
    private static final int TRIAL_MEASURE_MS = 8000;
    private static final int HOST_IDLE_TIMEOUT_MS = 25000;

    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_MUTED = 0xB3E8E6FF;
    private static final int COLOR_FAINT = 0x80E8E6FF;
    private static final int COLOR_CYAN = 0xFF6FE7FF;
    private static final int COLOR_VIOLET = 0xFFA98BFF;
    private static final int COLOR_AMBER = 0xFFFFC46B;
    private static final int COLOR_RED = 0xFFFF6B8B;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final CountDownLatch binderReady = new CountDownLatch(1);
    private final WizardReport report = new WizardReport();
    private final int[] stepState = new int[STEP_TITLES.length];

    private String uuid;
    private volatile ComputerDetails computer;
    private volatile ComputerManagerService.ComputerManagerBinder binder;

    private LinearLayout rail;
    private LinearLayout body;
    private ScrollView scroll;
    private TextView primary;
    private TextView secondary;
    private TextView status;
    private View busy;

    private int step = STEP_SURVEY;
    private int revealDelay;
    private boolean destroyed;

    private final List<WizardReport.Trial> trialQueue = new ArrayList<>();
    private final List<TextView> trialRows = new ArrayList<>();
    private int trialIndex = -1;
    private boolean trialsRunning;
    private WizardRecommender.Plan plan;
    private List<WizardRecommender.Rec> recs;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            final ComputerManagerService.ComputerManagerBinder b = (ComputerManagerService.ComputerManagerBinder) service;
            new Thread(() -> {
                b.waitForReady();
                computer = b.getComputer(uuid);
                binder = b;
                binderReady.countDown();
            }).start();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiHelper.setLocale(this);
        setContentView(R.layout.activity_setup_wizard);

        uuid = getIntent().getStringExtra(EXTRA_PC_UUID);
        String pcName = getIntent().getStringExtra(EXTRA_PC_NAME);

        EclipseBackgroundView eclipse = findViewById(R.id.wizardEclipse);
        eclipse.setIntensity(0.85f);
        eclipse.setEclipse(0.56f, -0.07f, 0.13f);

        rail = findViewById(R.id.wizardRail);
        body = findViewById(R.id.wizardBody);
        scroll = findViewById(R.id.wizardScroll);
        primary = findViewById(R.id.wizardPrimary);
        secondary = findViewById(R.id.wizardSecondary);
        status = findViewById(R.id.wizardStatus);
        busy = findViewById(R.id.wizardBusy);
        ((TextView) findViewById(R.id.wizardHost)).setText(pcName != null ? pcName : "");
        findViewById(R.id.wizardBack).setOnClickListener(v -> finish());
        UiHelper.applyNavigationBarPadding(findViewById(R.id.wizardContent));

        bindService(new Intent(this, ComputerManagerService.class), serviceConnection, BIND_AUTO_CREATE);

        // Sensible survey defaults before the user touches anything
        SharedPreferences prefs = ProfilesManager.getInstance().getOverlayingSharedPreferences(this);
        report.wantHdr = prefs.getBoolean(WizardRecommender.KEY_HDR, true);

        showSurvey();
        UiHelper.notifyNewRootView(this);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        try {
            unbindService(serviceConnection);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    // ================================================================ rail

    private void renderRail() {
        rail.removeAllViews();
        for (int i = 0; i < STEP_TITLES.length; i++) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setBackgroundResource(R.drawable.settings_section_bg);
            item.setPadding(dp(12), dp(4), dp(12), dp(4));
            item.setSelected(i == step);

            TextView badge = new TextView(this);
            badge.setGravity(Gravity.CENTER);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            int state = stepState[i];
            if (state == STATE_DONE) {
                circle.setColor(COLOR_CYAN);
                badge.setTextColor(0xFF050418);
                badge.setText("✓");
            } else if (state == STATE_SKIPPED) {
                circle.setColor(0x26FFFFFF);
                badge.setTextColor(COLOR_MUTED);
                badge.setText("–");
            } else if (state == STATE_ACTIVE || i == step) {
                circle.setColor(COLOR_VIOLET);
                badge.setTextColor(0xFF050418);
                badge.setText(String.valueOf(i + 1));
            } else {
                circle.setColor(0x00000000);
                circle.setStroke(dp(1), 0x66FFFFFF);
                badge.setTextColor(COLOR_MUTED);
                badge.setText(String.valueOf(i + 1));
            }
            badge.setBackground(circle);
            item.addView(badge, new LinearLayout.LayoutParams(dp(24), dp(24)));

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView title = text(STEP_TITLES[i], 14, i == step || state == STATE_DONE ? COLOR_TEXT : COLOR_MUTED);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            texts.addView(title);
            texts.addView(text(STEP_SUBTITLES[i], 11, COLOR_FAINT));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            tlp.setMarginStart(dp(12));
            item.addView(texts, tlp);

            // Share the rail's height so all six steps always fit
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
            lp.bottomMargin = dp(4);
            rail.addView(item, lp);
        }
    }

    private void enterStep(int newStep) {
        step = newStep;
        stepState[newStep] = STATE_ACTIVE;
        renderRail();
        body.removeAllViews();
        scroll.scrollTo(0, 0);
        revealDelay = 0;
        setBusy(false, null);
        secondary.setVisibility(View.GONE);
        primary.setEnabled(true);
        primary.setAlpha(1f);
    }

    private void completeStep(int s) {
        stepState[s] = STATE_DONE;
        renderRail();
    }

    // ================================================================ survey

    private void showSurvey() {
        enterStep(STEP_SURVEY);
        Intent battery = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null && battery.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0) {
            report.power = WizardReport.POWER_PLUGGED;
        }

        addHeading("Let's tune your stream",
                "Answer four questions, then Penumbra tests this device, the host and the network, "
                        + "and can run a few short test streams to measure what really works.");

        addQuestion("What matters most?",
                new String[] {"Lowest latency", "Balanced", "Smoothest", "Sharpest"},
                report.priority, v -> report.priority = v);
        addQuestion("How do you usually play?",
                new String[] {"Plugged in", "On battery", "A bit of both"},
                report.power, v -> report.power = v);
        addQuestion("Where do you play from?",
                new String[] {"Near the router", "Elsewhere at home", "Away from home"},
                report.place, v -> report.place = v);
        addQuestion("HDR",
                new String[] {"Use HDR when possible", "Keep SDR"},
                report.wantHdr ? 0 : 1, v -> report.wantHdr = v == 0);

        setPrimary("Start scan", v -> {
            completeStep(STEP_SURVEY);
            runDeviceStep();
        });
        focusPrimary();
    }

    // ================================================================ scans

    private void runDeviceStep() {
        enterStep(STEP_DEVICE);
        addHeading("This device", "Reading the screen, the video decoders and the power state.");
        setBusy(true, "Scanning this device…");
        primary.setEnabled(false);
        primary.setAlpha(0.4f);
        setPrimary("Continue", v -> runHostStep());

        worker.execute(() -> {
            List<WizardProbes.Fact> facts = WizardProbes.probeDevice(this, report);
            List<WizardProbes.Fact> link = WizardProbes.probeLink(this, report);
            post(() -> {
                addSection("Display and decoding");
                for (WizardProbes.Fact f : facts) {
                    if (f.label.equals("Thermals")) addSection("Power");
                    addFact(f);
                }
                addSection("Connection");
                for (WizardProbes.Fact f : link) addFact(f);
                finishScan(STEP_DEVICE, "Device scanned.");
            });
        });
    }

    private void runHostStep() {
        enterStep(STEP_HOST);
        addHeading("Host PC", "Asking the host what it can encode and how it can display the stream.");
        setBusy(true, "Contacting the host…");
        primary.setEnabled(false);
        primary.setAlpha(0.4f);
        setPrimary("Continue", v -> runNetworkStep());

        worker.execute(() -> {
            List<WizardProbes.Fact> facts;
            try {
                binderReady.await();
            } catch (InterruptedException e) {
                return;
            }
            if (computer == null || binder == null) {
                facts = new ArrayList<>();
                facts.add(new WizardProbes.Fact("Host", "Unknown", "This host is no longer paired", WizardProbes.BAD));
            } else {
                facts = WizardProbes.probeHost(this, computer, binder, report);
            }
            final List<WizardProbes.Fact> result = facts;
            post(() -> {
                for (WizardProbes.Fact f : result) addFact(f);
                finishScan(STEP_HOST, report.hostReachable ? "Host scanned." : "The host didn't answer.");
            });
        });
    }

    @SuppressLint("DefaultLocale")
    private void runNetworkStep() {
        enterStep(STEP_NETWORK);
        addHeading("Network", "Timing 40 round trips to the host to measure delay and how steady it is.");
        setBusy(true, "Measuring…");
        primary.setEnabled(false);
        primary.setAlpha(0.4f);
        setPrimary("Continue", v -> showStreamStep());

        final PingGraphView graph = new PingGraphView(this);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(84));
        glp.topMargin = dp(10);
        glp.bottomMargin = dp(6);
        body.addView(graph, glp);

        worker.execute(() -> {
            if (computer == null) {
                post(() -> finishScan(STEP_NETWORK, "No host to measure."));
                return;
            }
            List<WizardProbes.Fact> facts = WizardProbes.probeNetwork(this, computer, report, 40,
                    (i, total, ms) -> post(() -> {
                        graph.add(ms);
                        setBusy(true, ms < 0 ? String.format("Sample %d of %d · no reply", i + 1, total)
                                : String.format("Sample %d of %d · %.1f ms", i + 1, total, ms));
                    }));
            post(() -> {
                for (WizardProbes.Fact f : facts) addFact(f);
                finishScan(STEP_NETWORK, "Network measured.");
            });
        });
    }

    private void finishScan(int s, String message) {
        if (destroyed) return;
        completeStep(s);
        setBusy(false, message);
        ui.postDelayed(() -> {
            primary.setEnabled(true);
            primary.setAlpha(1f);
            focusPrimary();
        }, revealDelay);
    }

    // ================================================================ trial streams

    private void showStreamStep() {
        enterStep(STEP_STREAM);
        plan = WizardRecommender.plan(report);
        trialQueue.clear();
        trialRows.clear();
        report.trials.clear();
        trialIndex = -1;

        boolean possible = report.hostReachable && report.testApp != null && computer != null;
        String appName = report.testApp != null ? report.testApp.getAppName() : "an app";
        addHeading("Stream tests", possible
                ? String.format(Locale.US, "Penumbra streams %s from %s a few times, about %d seconds each, "
                        + "and measures what arrives and what reaches the screen. The host's screen switches to "
                        + "the stream display while the tests run.",
                appName, report.hostName, (TRIAL_WARMUP_MS + TRIAL_MEASURE_MS) / 1000)
                : "The host isn't reachable, so the results will be based on the scans alone.");

        if (possible) {
            addSection("Planned tests");
            trialQueue.addAll(WizardRecommender.buildTrials(plan));
            for (WizardReport.Trial t : trialQueue) {
                addTrialRow(t);
            }
            setPrimary("Run tests", v -> startTrials());
            setSecondary("Skip", v -> {
                stepState[STEP_STREAM] = STATE_SKIPPED;
                showResults();
            });
        } else {
            setPrimary("See results", v -> {
                stepState[STEP_STREAM] = STATE_SKIPPED;
                showResults();
            });
        }
        focusPrimary();
    }

    private void addTrialRow(WizardReport.Trial t) {
        TextView row = text("", 14, COLOR_TEXT);
        row.setPadding(0, dp(7), 0, dp(7));
        trialRows.add(row);
        body.addView(row);
        updateTrialRow(trialRows.size() - 1);
    }

    @SuppressLint("DefaultLocale")
    private void updateTrialRow(int i) {
        WizardReport.Trial t = trialQueue.get(i);
        TextView row = trialRows.get(i);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int dotColor;
        String detail;
        if (t.failed) {
            dotColor = COLOR_RED;
            detail = t.error != null ? t.error : "Didn't finish";
        } else if (t.done) {
            dotColor = t.networkClean() && t.renderClean() ? COLOR_CYAN : COLOR_AMBER;
            detail = String.format(Locale.US, "%.0f/%d fps · %.0f shown · %.1f%% lost · %.1f ms decode%s%s",
                    t.receivedFps, t.fps, t.renderedFps, t.lossPct, t.decodeMs,
                    Float.isNaN(t.rttMs) ? "" : String.format(Locale.US, " · %.0f ms RTT", t.rttMs),
                    Float.isNaN(t.carriedMbps) ? "" : String.format(Locale.US, " · %.1f Mbps used", t.carriedMbps));
        } else if (i == trialIndex && trialsRunning) {
            dotColor = COLOR_VIOLET;
            detail = "Streaming…";
        } else {
            dotColor = 0x66FFFFFF;
            detail = String.format(Locale.US, "%d×%d · %d fps · %s · %s", t.width, t.height, t.fps,
                    WizardProbes.mbps(t.bitrateKbps), WizardRecommender.pacingName(t.pacing));
        }
        appendColored(sb, "●  ", dotColor);
        appendColored(sb, t.label, COLOR_TEXT);
        sb.append('\n');
        int start = sb.length();
        appendColored(sb, detail, COLOR_MUTED);
        // Indent the whole detail paragraph under the label, wrapped lines included
        sb.setSpan(new android.text.style.LeadingMarginSpan.Standard(dp(19)), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new android.text.style.RelativeSizeSpan(0.9f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        row.setText(sb);
    }

    private void startTrials() {
        trialsRunning = true;
        secondary.setVisibility(View.GONE);
        primary.setEnabled(false);
        primary.setAlpha(0.4f);
        setPrimary("See results", v -> showResults());
        trialIndex = -1;
        nextTrial();
    }

    private void nextTrial() {
        if (destroyed) return;
        trialIndex++;
        if (trialIndex >= trialQueue.size()) {
            // Everything planned has run: maybe one more at a lower frame rate
            WizardReport.Trial extra = WizardRecommender.followUpTrial(plan, report);
            if (extra != null) {
                trialQueue.add(extra);
                addTrialRow(extra);
            } else {
                finishTrials("Tests finished.");
                return;
            }
        }
        launchTrial();
    }

    private void launchTrial() {
        if (destroyed) return;
        WizardReport.Trial t = trialQueue.get(trialIndex);
        updateTrialRow(trialIndex);
        setBusy(true, String.format(Locale.US, "Test %d of %d · %s", trialIndex + 1, trialQueue.size(), t.label));

        Intent intent = ServerHelper.createStartIntent(this, report.testApp, computer, binder, plan.virtualDisplay);
        intent.putExtra(Game.EXTRA_PROBE_OVERRIDES, WizardRecommender.overridesFor(t, plan));
        intent.putExtra(Game.EXTRA_PROBE_WARMUP_MS, TRIAL_WARMUP_MS);
        intent.putExtra(Game.EXTRA_PROBE_MEASURE_MS, TRIAL_MEASURE_MS);
        try {
            startActivityForResult(intent, REQUEST_TRIAL);
        } catch (Exception e) {
            t.failed = true;
            t.error = e.getMessage();
            updateTrialRow(trialIndex);
            finishTrials("Couldn't start a test stream.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_TRIAL || trialIndex < 0 || trialIndex >= trialQueue.size()) {
            return;
        }
        WizardReport.Trial t = trialQueue.get(trialIndex);
        if (resultCode == RESULT_OK && data != null && data.hasExtra(Game.RESULT_PROBE_RECEIVED_FPS)) {
            t.done = true;
            t.receivedFps = data.getFloatExtra(Game.RESULT_PROBE_RECEIVED_FPS, 0);
            t.renderedFps = data.getFloatExtra(Game.RESULT_PROBE_RENDERED_FPS, 0);
            t.totalFps = data.getFloatExtra(Game.RESULT_PROBE_TOTAL_FPS, 0);
            t.decodeMs = data.getFloatExtra(Game.RESULT_PROBE_DECODE_MS, 0);
            t.lossPct = data.getFloatExtra(Game.RESULT_PROBE_LOSS_PCT, 0);
            t.rttMs = data.getFloatExtra(Game.RESULT_PROBE_RTT_MS, Float.NaN);
            t.rttVarMs = data.getFloatExtra(Game.RESULT_PROBE_RTT_VAR_MS, Float.NaN);
            t.carriedMbps = data.getFloatExtra(Game.RESULT_PROBE_MBPS, Float.NaN);
            t.decoder = data.getStringExtra(Game.RESULT_PROBE_DECODER);
            t.hdrActive = data.getBooleanExtra(Game.RESULT_PROBE_HDR, false);
            report.trials.add(t);
            updateTrialRow(trialIndex);
            afterHostIdle(this::nextTrial);
        } else if (resultCode == Game.RESULT_PROBE_FAILED) {
            // The stream failed by itself (often the host still closing the last test): retry once
            String error = data != null ? data.getStringExtra(Game.RESULT_PROBE_ERROR) : null;
            if (!t.retried) {
                t.retried = true;
                setBusy(true, "Host was busy" + (error != null ? " (" + error + ")" : "") + ". Retrying…");
                afterHostIdle(this::launchTrial);
            } else {
                t.failed = true;
                t.error = error != null ? error : "Failed to start";
                report.trials.add(t);
                updateTrialRow(trialIndex);
                afterHostIdle(this::nextTrial);
            }
        } else {
            // Backed out, or the stream failed to start: stop here and tidy up the host
            t.failed = true;
            t.error = "Stopped";
            report.trials.add(t);
            updateTrialRow(trialIndex);
            for (int i = trialIndex + 1; i < trialQueue.size(); i++) {
                trialQueue.get(i).failed = true;
                trialQueue.get(i).error = "Skipped";
                updateTrialRow(i);
            }
            quitHostApp();
            finishTrials("Tests stopped. Results use what was measured.");
            setSecondary("Run again", v -> showStreamStep());
        }
    }

    private void finishTrials(String message) {
        trialsRunning = false;
        completeStep(STEP_STREAM);
        setBusy(false, message);
        primary.setEnabled(true);
        primary.setAlpha(1f);
        focusPrimary();
    }

    /** Runs {@code next} once the host reports nothing running (the last test has fully closed). */
    private void afterHostIdle(Runnable next) {
        setBusy(true, "Waiting for the host to close the test…");
        final ComputerDetails c = computer;
        final ComputerManagerService.ComputerManagerBinder b = binder;
        worker.execute(() -> {
            long deadline = android.os.SystemClock.uptimeMillis() + HOST_IDLE_TIMEOUT_MS;
            android.os.SystemClock.sleep(1500);
            try {
                NvHTTP http = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(c), c.httpsPort,
                        b.getUniqueId(), c.serverCert, PlatformBinding.getCryptoProvider(this));
                while (android.os.SystemClock.uptimeMillis() < deadline) {
                    try {
                        if (http.getCurrentGame(http.getServerInfo(true)) == 0) {
                            break;
                        }
                    } catch (Exception ignored) {
                    }
                    android.os.SystemClock.sleep(1000);
                }
            } catch (Exception ignored) {
            }
            // Give the host's display restore a moment before the next stream reconfigures it
            android.os.SystemClock.sleep(1500);
            post(next);
        });
    }

    private void quitHostApp() {
        final ComputerDetails c = computer;
        final ComputerManagerService.ComputerManagerBinder b = binder;
        if (c == null || b == null) return;
        new Thread(() -> {
            try {
                NvHTTP http = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(c), c.httpsPort,
                        b.getUniqueId(), c.serverCert, PlatformBinding.getCryptoProvider(this));
                http.quitApp();
            } catch (Exception ignored) {
            }
        }).start();
    }

    // ================================================================ results

    private void showResults() {
        enterStep(STEP_RESULTS);
        plan = WizardRecommender.plan(report);
        WizardRecommender.refine(plan, report);
        SharedPreferences current = ProfilesManager.getInstance().getOverlayingSharedPreferences(this);
        recs = WizardRecommender.recommendations(plan, current);

        int changes = 0;
        for (WizardRecommender.Rec r : recs) if (r.changes()) changes++;
        boolean measured = !report.trials.isEmpty();
        addHeading("Recommended settings", (changes == 0
                ? "Your current settings already match. Nothing to change."
                : changes + (changes == 1 ? " setting differs" : " settings differ") + " from what you use now.")
                + (measured ? " Based on " + report.trials.size() + " test streams." : " Based on the scans."));

        for (String note : plan.notes) {
            addFact(new WizardProbes.Fact("Heads up", "", note, WizardProbes.WARN));
        }
        for (WizardRecommender.Rec r : recs) {
            addRecRow(r);
        }

        String active = ProfilesManager.getInstance().getActiveName();
        if (!active.isEmpty()) {
            TextView hint = text("Profile “" + active + "” is active and overrides your base settings. "
                    + "Save these as a profile to use them.", 12, COLOR_AMBER);
            hint.setPadding(0, dp(10), 0, 0);
            body.addView(hint);
        }

        setPrimary(active.isEmpty() ? "Apply" : "Save as profile", v -> {
            if (ProfilesManager.getInstance().getActiveName().isEmpty()) applyToSettings();
            else saveAsProfile();
        });
        setSecondary(active.isEmpty() ? "Save as profile" : "Apply to base settings", v -> {
            if (ProfilesManager.getInstance().getActiveName().isEmpty()) saveAsProfile();
            else applyToSettings();
        });
        completeStep(STEP_RESULTS);
        focusPrimary();
    }

    private void addRecRow(WizardRecommender.Rec r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(r.title, 15, COLOR_TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        SpannableStringBuilder value = new SpannableStringBuilder();
        if (r.changes() && r.current != null) {
            appendColored(value, r.current + "  →  ", COLOR_FAINT);
        }
        appendColored(value, r.display, r.changes() ? COLOR_CYAN : COLOR_MUTED);
        TextView valueView = text("", 15, COLOR_TEXT);
        valueView.setText(value);
        valueView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        top.addView(valueView);
        row.addView(top);

        if (r.reason != null) {
            TextView reason = text(r.reason, 12, COLOR_MUTED);
            reason.setPadding(0, dp(2), dp(120), 0);
            row.addView(reason);
        }
        reveal(row);
    }

    private Map<String, Object> recommendedValues() {
        Map<String, Object> values = new HashMap<>();
        for (WizardRecommender.Rec r : recs) {
            values.put(r.key, r.value);
        }
        return values;
    }

    private void applyToSettings() {
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(this).edit();
        for (Map.Entry<String, Object> e : recommendedValues().entrySet()) {
            Object v = e.getValue();
            if (v instanceof Integer) editor.putInt(e.getKey(), (Integer) v);
            else if (v instanceof Boolean) editor.putBoolean(e.getKey(), (Boolean) v);
            else editor.putString(e.getKey(), String.valueOf(v));
        }
        editor.apply();
        PenumbraToast.makeText(this, "Settings applied", Toast.LENGTH_SHORT).show();
        done();
    }

    private void saveAsProfile() {
        Map<String, Object> options = new HashMap<>(PreferenceManager.getDefaultSharedPreferences(this).getAll());
        options.putAll(recommendedValues());
        long now = System.currentTimeMillis();
        String name = "Tuned for " + (report.hostName != null ? report.hostName : "host") + " · "
                + DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(now));
        SettingsProfile profile = new SettingsProfile(UUID.randomUUID(), name, now, now, options);
        ProfilesManager pm = ProfilesManager.getInstance();
        pm.add(profile);
        pm.setActive(profile.getUuid());
        pm.save(this);
        PenumbraToast.makeText(this, "Saved and activated “" + name + "”", Toast.LENGTH_LONG).show();
        done();
    }

    private void done() {
        secondary.setVisibility(View.GONE);
        setBusy(false, "All set. Your next stream uses these settings.");
        setPrimary("Done", v -> finish());
        focusPrimary();
    }

    // ================================================================ view helpers

    private void addHeading(String title, String subtitle) {
        TextView t = text(title, 24, COLOR_TEXT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        body.addView(t);
        if (subtitle != null) {
            TextView s = text(subtitle, 13, COLOR_MUTED);
            s.setPadding(0, dp(4), dp(40), dp(6));
            body.addView(s);
        }
    }

    private void addSection(String label) {
        TextView t = text(label.toUpperCase(Locale.ROOT), 11, COLOR_CYAN);
        t.setLetterSpacing(0.16f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(0, dp(14), 0, dp(4));
        reveal(t);
    }

    private void addFact(WizardProbes.Fact f) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(5), 0, dp(5));

        View dot = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(statusColor(f.status));
        dot.setBackground(d);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dlp.topMargin = dp(6);
        dlp.setMarginEnd(dp(12));
        row.addView(dot, dlp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        TextView label = text(f.label, 14, COLOR_MUTED);
        line.addView(label, new LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView value = text(f.value, 14, COLOR_TEXT);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        line.addView(value, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        col.addView(line);
        if (f.detail != null) {
            TextView detail = text(f.detail, 12, f.status == WizardProbes.BAD ? COLOR_RED
                    : f.status == WizardProbes.WARN ? COLOR_AMBER : COLOR_FAINT);
            detail.setPadding(dp(150), dp(1), 0, 0);
            col.addView(detail);
        }
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        reveal(row);
    }

    private interface IntSetter {
        void set(int value);
    }

    private void addQuestion(String question, String[] options, int selected, IntSetter setter) {
        TextView q = text(question, 15, COLOR_TEXT);
        q.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        q.setPadding(0, dp(16), 0, dp(8));
        body.addView(q);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        final List<TextView> views = new ArrayList<>();
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            TextView chip = new TextView(this, null, 0, R.style.HomeChip);
            chip.setText(options[i]);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            chip.setMaxLines(1);
            chip.setSelected(i == selected);
            chip.setOnClickListener(v -> {
                for (TextView other : views) other.setSelected(other == v);
                setter.set(index);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
            lp.setMarginEnd(dp(8));
            views.add(chip);
            chips.addView(chip, lp);
        }
        body.addView(chips);
    }

    private void reveal(View v) {
        v.setAlpha(0f);
        v.setTranslationY(dp(8));
        body.addView(v);
        v.animate().alpha(1f).translationY(0).setStartDelay(revealDelay).setDuration(220).start();
        revealDelay += 70;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private static void appendColored(SpannableStringBuilder sb, String s, int color) {
        int start = sb.length();
        sb.append(s);
        sb.setSpan(new ForegroundColorSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static int statusColor(int status) {
        switch (status) {
            case WizardProbes.GOOD: return COLOR_CYAN;
            case WizardProbes.OK: return COLOR_VIOLET;
            case WizardProbes.WARN: return COLOR_AMBER;
            case WizardProbes.BAD: return COLOR_RED;
            default: return 0x80FFFFFF;
        }
    }

    private void setPrimary(String label, View.OnClickListener l) {
        primary.setText(label);
        primary.setOnClickListener(l);
    }

    private void setSecondary(String label, View.OnClickListener l) {
        secondary.setText(label);
        secondary.setOnClickListener(l);
        secondary.setVisibility(View.VISIBLE);
    }

    private void focusPrimary() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            primary.setFocusedByDefault(true);
        }
        primary.requestFocus();
    }

    private void setBusy(boolean on, String message) {
        busy.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        if (message != null) status.setText(message);
    }

    private void post(Runnable r) {
        ui.post(() -> {
            if (!destroyed) r.run();
        });
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    /** Live bar graph of the network probe's round trips. */
    private static class PingGraphView extends View {
        private final List<Float> samples = new ArrayList<>();
        private final android.graphics.Paint bar = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint axis = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        PingGraphView(android.content.Context c) {
            super(c);
            axis.setColor(0x26FFFFFF);
            axis.setStrokeWidth(2);
        }

        void add(float ms) {
            samples.add(ms);
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            float w = getWidth(), h = getHeight();
            canvas.drawLine(0, h - 1, w, h - 1, axis);
            if (samples.isEmpty()) return;
            float max = 4f;
            for (float s : samples) max = Math.max(max, s);
            float slot = w / 40f;
            for (int i = 0; i < samples.size(); i++) {
                float s = samples.get(i);
                float left = i * slot + slot * 0.18f;
                float right = (i + 1) * slot - slot * 0.18f;
                if (s < 0) {
                    bar.setColor(COLOR_RED);
                    canvas.drawRect(left, h * 0.1f, right, h - 2, bar);
                    continue;
                }
                float top = h - 2 - (h - 8) * (s / max);
                bar.setShader(new android.graphics.LinearGradient(0, top, 0, h,
                        s > 15 ? COLOR_AMBER : COLOR_CYAN, COLOR_VIOLET, android.graphics.Shader.TileMode.CLAMP));
                canvas.drawRoundRect(new android.graphics.RectF(left, top, right, h - 2), 3, 3, bar);
                bar.setShader(null);
            }
        }
    }
}
