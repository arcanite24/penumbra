package com.limelight;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.List;

import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.grid.AppGridAdapter;
import com.limelight.grid.HomeAppAdapter;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.profiles.ProfilesManager;
import com.limelight.utils.CacheHelper;
import com.limelight.utils.Dialog;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.SpinnerDialog;
import com.limelight.utils.UiHelper;

import android.app.Activity;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.DataSetObserver;
import android.graphics.Bitmap;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.xmlpull.v1.XmlPullParserException;

public class AppView extends AppCompatActivity implements HomeAppAdapter.Listener {
    private AppGridAdapter appGridAdapter;
    private String uuidString;
    private ShortcutHelper shortcutHelper;

    private ComputerDetails computer;
    private ComputerManagerService.ApplistPoller poller;
    private SpinnerDialog blockingLoadSpinner;
    private String lastRawApplist;
    private int lastRunningAppId;
    private boolean suspendGridUpdates;
    private boolean inForeground;
    private boolean showHiddenApps;
    private HashSet<Integer> hiddenAppIds = new HashSet<>();

    private PreferenceConfiguration prefConfig;

    // Penumbra home (PEN-3): one shared shell, three switchable arrangements
    public final static String HOME_LAYOUT_PREF = "home_layout";
    private final static String[] HOME_LAYOUT_KEYS = {"tiles", "shelf", "grid"};
    private final static int HOME_TILES = 0;
    private final static int HOME_SHELF = 1;
    private final static int HOME_GRID = 2;

    private int homeLayout = HOME_TILES;
    private HomeAppAdapter homeAdapter;
    private RecyclerView homeList;
    private TextView heroTitle, heroSubtitle, heroButton;
    private ImageView heroImage;
    private TextView heroImageText;
    private ViewGroup heroBadges;
    private ImageView homeBackdrop;
    private TextView backdropText;
    private AppObject focusedApp;
    private int lastBackdropAppId = -1;
    private boolean homeObserverRegistered;

    // Target of the context menu, since RecyclerView has no AdapterContextMenuInfo
    private AppObject menuTargetApp;
    private View menuTargetView;

    private final static int START_OR_RESUME_ID = 1;
    private final static int QUIT_ID = 2;
    private final static int START_WITH_QUIT = 4;
    private final static int VIEW_DETAILS_ID = 5;
    private final static int CREATE_SHORTCUT_ID = 6;
    private final static int EXPORT_LAUNCHER_FILE_ID = 7;
    private final static int HIDE_APP_ID = 8;
    private final static int START_WITH_VDISPLAY = 20;
    private final static int START_WITH_QUIT_VDISPLAY = 21;

    public final static String HIDDEN_APPS_PREF_FILENAME = "HiddenApps";

    public final static String NAME_EXTRA = "Name";
    public final static String UUID_EXTRA = "UUID";
    public final static String NEW_PAIR_EXTRA = "NewPair";
    public final static String SHOW_HIDDEN_APPS_EXTRA = "ShowHiddenApps";

    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    ((ComputerManagerService.ComputerManagerBinder)binder);

            // Wait in a separate thread to avoid stalling the UI
            new Thread() {
                @Override
                public void run() {
                    // Wait for the binder to be ready
                    localBinder.waitForReady();

                    // Get the computer object
                    computer = localBinder.getComputer(uuidString);
                    if (computer == null) {
                        finish();
                        return;
                    }

                    // Add a launcher shortcut for this PC (forced, since this is user interaction)
                    shortcutHelper.createAppViewShortcut(computer, true, getIntent().getBooleanExtra(NEW_PAIR_EXTRA, false));
                    shortcutHelper.reportComputerShortcutUsed(computer);

                    try {
                        appGridAdapter = new AppGridAdapter(AppView.this,
                                PreferenceConfiguration.readPreferences(AppView.this),
                                computer, localBinder.getUniqueId(),
                                showHiddenApps);
                    } catch (Exception e) {
                        e.printStackTrace();
                        finish();
                        return;
                    }

                    appGridAdapter.updateHiddenApps(hiddenAppIds, true);

                    // Now make the binder visible. We must do this after appGridAdapter
                    // is set to prevent us from reaching updateUiWithServerinfo() and
                    // touching the appGridAdapter prior to initialization.
                    managerBinder = localBinder;

                    // Load the app grid with cached data (if possible).
                    // This must be done _before_ startComputerUpdates()
                    // so the initial serverinfo response can update the running
                    // icon.
                    populateAppGridWithCache();

                    // Start updates
                    startComputerUpdates();

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isChangingConfigurations()) {
                                return;
                            }

                            setupHome();
                        }
                    });
                }
            }.start();
        }

        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        this.prefConfig = PreferenceConfiguration.readPreferences(this);

        // If appGridAdapter is initialized, let it know about the configuration change.
        // If not, it will pick it up when it initializes.
        if (appGridAdapter != null) {
            // Update the app grid adapter to create grid items with the correct layout
            appGridAdapter.updateLayoutWithPreferences(this, this.prefConfig);

            // Reinflate the arrangement to pick up the new size
            if (homeAdapter != null) {
                applyHomeLayout(homeLayout, true);
            }
        }
        updateConnectionPanel();
    }

    private void startComputerUpdates() {
        // Don't start polling if we're not bound or in the foreground
        if (managerBinder == null || !inForeground) {
            return;
        }

        managerBinder.startPolling(new ComputerManagerListener() {
            @Override
            public void notifyComputerUpdated(final ComputerDetails details) {
                // Do nothing if updates are suspended
                if (suspendGridUpdates) {
                    return;
                }

                // Don't care about other computers
                if (!details.uuid.equalsIgnoreCase(uuidString)) {
                    return;
                }

                if (details.state == ComputerDetails.State.OFFLINE) {
                    // The PC is unreachable now
                    AppView.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // Display a toast to the user and quit the activity
                            com.limelight.utils.PenumbraToast.makeText(AppView.this, R.string.lost_connection, Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    });

                    return;
                }

                // Close immediately if the PC is no longer paired
                if (details.state == ComputerDetails.State.ONLINE && details.pairState != PairingManager.PairState.PAIRED) {
                    AppView.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // Disable shortcuts referencing this PC for now
                            shortcutHelper.disableComputerShortcut(details,
                                    getResources().getString(R.string.scut_not_paired));

                            // Display a toast to the user and quit the activity
                            com.limelight.utils.PenumbraToast.makeText(AppView.this, R.string.scut_not_paired, Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    });

                    return;
                }

                // App list is the same or empty
                if (details.rawAppList == null || details.rawAppList.equals(lastRawApplist)) {

                    // Let's check if the running app ID changed
                    if (details.runningGameId != lastRunningAppId) {
                        // Update the currently running game using the app ID
                        lastRunningAppId = details.runningGameId;
                        updateUiWithServerinfo(details);
                    }

                    return;
                }

                lastRunningAppId = details.runningGameId;
                lastRawApplist = details.rawAppList;

                try {
                    updateUiWithAppList(NvHTTP.getAppListByReader(new StringReader(details.rawAppList)));
                    updateUiWithServerinfo(details);

                    if (blockingLoadSpinner != null) {
                        blockingLoadSpinner.dismiss();
                        blockingLoadSpinner = null;
                    }
                } catch (XmlPullParserException | IOException e) {
                    e.printStackTrace();
                }
            }
        });

        if (poller == null) {
            poller = managerBinder.createAppListPoller(computer);
        }
        poller.start();
    }

    private void stopComputerUpdates() {
        if (poller != null) {
            poller.stop();
        }

        if (managerBinder != null) {
            managerBinder.stopPolling();
        }

        if (appGridAdapter != null) {
            appGridAdapter.cancelQueuedOperations();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Assume we're in the foreground when created to avoid a race
        // between binding to CMS and onResume()
        inForeground = true;

        shortcutHelper = new ShortcutHelper(this);

        UiHelper.setLocale(this);

        setContentView(R.layout.activity_app_view);

        // Allow floating expanded PiP overlays while browsing apps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false);
        }

        UiHelper.notifyNewRootView(this);

        // Setup the profiles button
        findViewById(R.id.profilesButton)
            .setOnClickListener(v -> startActivity(new Intent(this, ProfilesActivity.class)));

        setupHomeShell();

        // Setup wizard (PEN-8)
        findViewById(R.id.tuneButton).setOnClickListener(v -> {
            Intent tune = new Intent(this, com.limelight.wizard.SetupWizardActivity.class);
            tune.putExtra(com.limelight.wizard.SetupWizardActivity.EXTRA_PC_UUID, uuidString);
            tune.putExtra(com.limelight.wizard.SetupWizardActivity.EXTRA_PC_NAME,
                    computer != null ? computer.name : getIntent().getStringExtra(NAME_EXTRA));
            startActivity(tune);
        });

        showHiddenApps = getIntent().getBooleanExtra(SHOW_HIDDEN_APPS_EXTRA, false);
        uuidString = getIntent().getStringExtra(UUID_EXTRA);

        SharedPreferences hiddenAppsPrefs = getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE);
        for (String hiddenAppIdStr : hiddenAppsPrefs.getStringSet(uuidString, new HashSet<String>())) {
            hiddenAppIds.add(Integer.parseInt(hiddenAppIdStr));
        }

        String computerName = getIntent().getStringExtra(NAME_EXTRA);

        TextView label = findViewById(R.id.appListText);
        setTitle(computerName);
        label.setText(computerName);

        this.prefConfig = PreferenceConfiguration.readPreferences(this);
        updateConnectionPanel();

        // Bind to the computer manager service
        bindService(new Intent(this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);
    }

    private void updateHiddenApps(boolean hideImmediately) {
        HashSet<String> hiddenAppIdStringSet = new HashSet<>();

        for (Integer hiddenAppId : hiddenAppIds) {
            hiddenAppIdStringSet.add(hiddenAppId.toString());
        }

        getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .putStringSet(uuidString, hiddenAppIdStringSet)
                .apply();

        appGridAdapter.updateHiddenApps(hiddenAppIds, hideImmediately);
    }

    private void populateAppGridWithCache() {
        try {
            // Try to load from cache
            lastRawApplist = CacheHelper.readInputStreamToString(CacheHelper.openCacheFileForInput(getCacheDir(), "applist", uuidString));
            List<NvApp> applist = NvHTTP.getAppListByReader(new StringReader(lastRawApplist));
            updateUiWithAppList(applist);
            LimeLog.info("Loaded applist from cache");
        } catch (IOException | XmlPullParserException e) {
            if (lastRawApplist != null) {
                LimeLog.warning("Saved applist corrupted: "+lastRawApplist);
                e.printStackTrace();
            }
            LimeLog.info("Loading applist from the network");
            // We'll need to load from the network
            loadAppsBlocking();
        }
    }

    private void loadAppsBlocking() {
        blockingLoadSpinner = SpinnerDialog.displayDialog(this, getResources().getString(R.string.applist_refresh_title),
                getResources().getString(R.string.applist_refresh_msg), true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        SpinnerDialog.closeDialogs(this);
        Dialog.closeDialogs();

        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Display a decoder crash notification if we've returned after a crash
        UiHelper.showDecoderCrashDialog(this);

        inForeground = true;
        startComputerUpdates();

        // The arrangement may have been changed in Settings
        int layout = readHomeLayoutPref();
        if (layout != homeLayout && homeAdapter != null) {
            applyHomeLayout(layout, true);
        }
        homeLayout = layout;

        // Settings may have changed while we were away
        this.prefConfig = PreferenceConfiguration.readPreferences(this);
        updateConnectionPanel();

        TextView profilesButton = findViewById(R.id.profilesButton);
        // User report Samsung and Xiaomi devices have this problem
        // Why just these two brands have the most problems?
        if (profilesButton == null) {
            return;
        }
        String activeProfileName = ProfilesManager.getInstance().getActiveName();
        if (activeProfileName.isEmpty()) {
            profilesButton.setText(R.string.home_profiles);
        } else {
            profilesButton.setText(activeProfileName);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        inForeground = false;
        stopComputerUpdates();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == ShortcutHelper.REQUEST_CODE_EXPORT_ART_FILE) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                ShortcutHelper.writeArtFileToUri(this, uri);
            } else {
                // Clear the content if the user cancelled or if there was an error before this point
                ShortcutHelper.artFileContentToExport = null;
                // Show "File export cancelled." toast only if the user explicitly cancelled.
                if (resultCode == Activity.RESULT_CANCELED) {
                    com.limelight.utils.PenumbraToast.makeText(this, R.string.file_export_cancelled, Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        AppObject selectedApp = menuTargetApp;
        if (selectedApp == null || computer == null) {
            return;
        }

        menu.setHeaderTitle(selectedApp.app.getAppName());

        if (lastRunningAppId == 0) {
            if (prefConfig.useVirtualDisplay) {
                menu.add(Menu.NONE, START_OR_RESUME_ID, 1, getResources().getString(R.string.applist_menu_start_primarydisplay));
            } else {
                menu.add(Menu.NONE, START_WITH_VDISPLAY, 1, getResources().getString(R.string.applist_menu_start_vdisplay));
            }
        } else {
            if (lastRunningAppId == selectedApp.app.getAppId()) {
                menu.add(Menu.NONE, START_OR_RESUME_ID, 1, getResources().getString(R.string.applist_menu_resume));
                menu.add(Menu.NONE, QUIT_ID, 2, getResources().getString(R.string.applist_menu_quit));
            }
            else {
                if (prefConfig.useVirtualDisplay) {
                    menu.add(Menu.NONE, START_WITH_QUIT_VDISPLAY, 1, getResources().getString(R.string.applist_menu_quit_and_start));
                    menu.add(Menu.NONE, START_WITH_QUIT, 2, getResources().getString(R.string.applist_menu_quit_and_start_primarydisplay));
                } else{
                    menu.add(Menu.NONE, START_WITH_QUIT, 1, getResources().getString(R.string.applist_menu_quit_and_start));
                    menu.add(Menu.NONE, START_WITH_QUIT_VDISPLAY, 2, getResources().getString(R.string.applist_menu_quit_and_start_vdisplay));
                }
            }
        }

        // Only show the hide checkbox if this is not the currently running app or it's already hidden
        if (lastRunningAppId != selectedApp.app.getAppId() || selectedApp.isHidden) {
            MenuItem hideAppItem = menu.add(Menu.NONE, HIDE_APP_ID, 3, getResources().getString(R.string.applist_menu_hide_app));
            hideAppItem.setCheckable(true);
            hideAppItem.setChecked(selectedApp.isHidden);
        }

        menu.add(Menu.NONE, VIEW_DETAILS_ID, 4, getResources().getString(R.string.applist_menu_details));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Only add an option to create shortcut if box art is loaded
            // and when we're in grid-mode (not list-mode).
            ImageView appImageView = menuTargetView != null ? menuTargetView.findViewById(R.id.grid_image) : null;
            if (appImageView != null) {
                // We have a grid ImageView, so we must be in grid-mode
                Drawable d = appImageView.getDrawable();
                BitmapDrawable drawable = d instanceof BitmapDrawable ? (BitmapDrawable) d : null;
                if (drawable != null && drawable.getBitmap() != null) {
                    // We have a bitmap loaded too
                    menu.add(Menu.NONE, CREATE_SHORTCUT_ID, 5, getResources().getString(R.string.applist_menu_scut));
                }
            }
        }

        menu.add(Menu.NONE, EXPORT_LAUNCHER_FILE_ID, 6, getResources().getString(R.string.applist_menu_export_launcher));
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        final AppObject app = menuTargetApp;
        if (app == null) {
            return super.onContextItemSelected(item);
        }
        int itemId = item.getItemId();
        switch (itemId) {
            case START_WITH_QUIT:
            case START_WITH_QUIT_VDISPLAY: {
                boolean withVDiaplay = itemId == START_WITH_QUIT_VDISPLAY;
                if (withVDiaplay && !(computer.vDisplaySupported && computer.vDisplayDriverReady)) {
                    UiHelper.displayVdisplayConfirmationDialog(
                        AppView.this,
                        computer,
                        () -> UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                            @Override
                            public void run() {
                                ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, true);
                            }
                        }, null),
                        null
                    );
                } else {
                    // Display a confirmation dialog first
                    UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                        @Override
                        public void run() {
                            ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, withVDiaplay);
                        }
                    }, null);
                }
                return true;
            }

            case START_OR_RESUME_ID:
            case START_WITH_VDISPLAY: {
                boolean withVDiaplay = itemId == START_WITH_VDISPLAY;
                if (withVDiaplay && !(computer.vDisplaySupported && computer.vDisplayDriverReady)) {
                    UiHelper.displayVdisplayConfirmationDialog(
                            AppView.this,
                            computer,
                            () -> ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, true),
                            null
                    );
                } else {
                    // Resume is the same as start for us
                    ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, withVDiaplay);
                }
                return true;
            }

            case QUIT_ID: {
                // Display a confirmation dialog first
                UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                    @Override
                    public void run() {
                        suspendGridUpdates = true;
                        ServerHelper.doQuit(AppView.this, computer,
                                app.app, managerBinder, new Runnable() {
                                    @Override
                                    public void run() {
                                        // Trigger a poll immediately
                                        suspendGridUpdates = false;
                                        if (poller != null) {
                                            poller.pollNow();
                                        }
                                    }
                                });
                    }
                }, null);
                return true;
            }

            case VIEW_DETAILS_ID: {
                Dialog.displayDialog(AppView.this, getResources().getString(R.string.title_details), app.app.toString(), false);
                return true;
            }

            case HIDE_APP_ID: {
                if (item.isChecked()) {
                    // Transitioning hidden to shown
                    hiddenAppIds.remove(app.app.getAppId());
                } else {
                    // Transitioning shown to hidden
                    hiddenAppIds.add(app.app.getAppId());
                }
                updateHiddenApps(false);
                return true;
            }

            case CREATE_SHORTCUT_ID: {
                ImageView appImageView = menuTargetView.findViewById(R.id.grid_image);
                Bitmap appBits = ((BitmapDrawable) appImageView.getDrawable()).getBitmap();
                if (!shortcutHelper.createPinnedGameShortcut(computer, app.app, appBits)) {
                    com.limelight.utils.PenumbraToast.makeText(AppView.this, getResources().getString(R.string.unable_to_pin_shortcut), Toast.LENGTH_LONG).show();
                }
                return true;
            }

            case EXPORT_LAUNCHER_FILE_ID: {
                if (app.app.getAppUUID() == null || (app.app.getAppUUID() != null && app.app.getAppUUID().isEmpty())) {
                    UiHelper.displayConfirmationDialog(
                            AppView.this,
                            getResources().getString(R.string.title_export_sunshine_launcher_file),
                            getResources().getString(R.string.message_export_sunshine_launcher_file),
                            getResources().getString(R.string.proceed),
                            getResources().getString(R.string.cancel),
                            () -> shortcutHelper.exportLauncherFile(computer, app.app),
                            null
                    );
                } else {
                    shortcutHelper.exportLauncherFile(computer, app.app);
                }
                return true;
            }

            default: {
                return super.onContextItemSelected(item);
            }
        }
    }

    private void updateUiWithServerinfo(final ComputerDetails details) {
        AppView.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                boolean updated = false;

                    // Look through our current app list to tag the running app
                for (int i = 0; i < appGridAdapter.getCount(); i++) {
                    AppObject existingApp = (AppObject) appGridAdapter.getItem(i);

                    // There can only be one or zero apps running.
                    if (existingApp.isRunning &&
                            existingApp.app.getAppId() == details.runningGameId) {
                        // This app was running and still is, so we're done now
                        return;
                    }
                    else if (existingApp.app.getAppId() == details.runningGameId) {
                        // This app wasn't running but now is
                        existingApp.isRunning = true;
                        updated = true;
                    }
                    else if (existingApp.isRunning) {
                        // This app was running but now isn't
                        existingApp.isRunning = false;
                        updated = true;
                    }
                    else {
                        // This app wasn't running and still isn't
                    }
                }

                if (updated) {
                    appGridAdapter.notifyDataSetChanged();
                }
            }
        });
    }

    private void updateUiWithAppList(final List<NvApp> appList) {
        AppView.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                boolean updated = false;

                // First handle app updates and additions
                for (NvApp app : appList) {
                    boolean foundExistingApp = false;

                    // Try to update an existing app in the list first
                    for (int i = 0; i < appGridAdapter.getCount(); i++) {
                        AppObject existingApp = (AppObject) appGridAdapter.getItem(i);
                        if (existingApp.app.getAppId() == app.getAppId()) {
                            // Found the app; update its properties
                            if (!existingApp.app.getAppName().equals(app.getAppName())) {
                                existingApp.app.setAppName(app.getAppName());
                                updated = true;
                            }

                            foundExistingApp = true;
                            break;
                        }
                    }

                    if (!foundExistingApp) {
                        // This app must be new
                        appGridAdapter.addApp(new AppObject(app));

                        // We could have a leftover shortcut from last time this PC was paired
                        // or if this app was removed then added again. Enable those shortcuts
                        // again if present.
                        shortcutHelper.enableAppShortcut(computer, app);

                        updated = true;
                    }
                }

                // Next handle app removals
                int i = 0;
                while (i < appGridAdapter.getCount()) {
                    boolean foundExistingApp = false;
                    AppObject existingApp = (AppObject) appGridAdapter.getItem(i);

                    // Check if this app is in the latest list
                    for (NvApp app : appList) {
                        if (existingApp.app.getAppId() == app.getAppId()) {
                            foundExistingApp = true;
                            break;
                        }
                    }

                    // This app was removed in the latest app list
                    if (!foundExistingApp) {
                        shortcutHelper.disableAppShortcut(computer, existingApp.app, getString(R.string.app_removed_from_pc));
                        appGridAdapter.removeApp(existingApp);
                        updated = true;

                        // Check this same index again because the item at i+1 is now at i after
                        // the removal
                        continue;
                    }

                    // Move on to the next item
                    i++;
                }

                if (updated) {
                    appGridAdapter.notifyDataSetChanged();
                }
            }
        });
    }

    // ---------------------------------------------------------------------------------------
    // Penumbra home (PEN-3)
    // ---------------------------------------------------------------------------------------

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Things that don't depend on the app list: backdrop, layout switcher, insets. */
    private void setupHomeShell() {
        ((com.limelight.ui.EclipseBackgroundView) findViewById(R.id.homeEclipse)).setEclipse(0.60f, 0.06f, 0.13f);
        UiHelper.applyNavigationBarPadding(findViewById(R.id.homeContent));
        homeBackdrop = findViewById(R.id.homeBackdrop);
        backdropText = new TextView(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            homeBackdrop.setRenderEffect(RenderEffect.createBlurEffect(48f, 48f, Shader.TileMode.CLAMP));
        } else {
            homeBackdrop.setAlpha(0.25f);
        }

        homeLayout = readHomeLayoutPref();

        // The arrangement is picked in Settings > Interface
        findViewById(R.id.homeSettingsButton).setOnClickListener(v ->
                startActivity(new Intent(this, com.limelight.preferences.StreamSettings.class)));

        // A soft pulsing glow behind the Play button
        startHeroGlow();

    }

    private int readHomeLayoutPref() {
        String saved = PreferenceManager.getDefaultSharedPreferences(this).getString(HOME_LAYOUT_PREF, HOME_LAYOUT_KEYS[0]);
        for (int i = 0; i < HOME_LAYOUT_KEYS.length; i++) {
            if (HOME_LAYOUT_KEYS[i].equals(saved)) {
                return i;
            }
        }
        return HOME_TILES;
    }

    private android.animation.ObjectAnimator heroGlow;

    private void startHeroGlow() {
        if (heroGlow != null) {
            heroGlow.cancel();
        }
        heroGlow = null;
        if (heroButton == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }
        heroButton.setOutlineSpotShadowColor(0xFF6FE7FF);
        heroButton.setOutlineAmbientShadowColor(0xFFA98BFF);
        heroGlow = android.animation.ObjectAnimator.ofFloat(heroButton, "translationZ", dp(2), dp(14));
        heroGlow.setDuration(1600);
        heroGlow.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        heroGlow.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        heroGlow.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        heroGlow.start();
    }

    /** Called once the app grid adapter exists. */
    private void setupHome() {
        if (!homeObserverRegistered) {
            homeObserverRegistered = true;
            appGridAdapter.registerDataSetObserver(new DataSetObserver() {
                @Override
                public void onChanged() {
                    onAppListChanged();
                }
            });
        }
        applyHomeLayout(homeLayout, true);
    }

    private void onAppListChanged() {
        if (homeAdapter == null) {
            return;
        }
        homeAdapter.notifyDataSetChanged();

        // Drop the focused app if it's gone from the list
        if (focusedApp != null && homeAdapter.positionOfAppId(focusedApp.app.getAppId()) == RecyclerView.NO_POSITION) {
            focusedApp = null;
        }

        View focus = getCurrentFocus();
        if (focusedApp == null || focus == null) {
            focusInitialItem();
        } else {
            refreshHero();
        }
    }

    private void applyHomeLayout(int layout, boolean focusList) {
        homeLayout = layout;

        int modeRes, itemRes;
        float scale;
        switch (layout) {
            case HOME_SHELF:
                modeRes = R.layout.home_mode_shelf;
                itemRes = R.layout.home_item_shelf;
                scale = 1.12f;
                break;
            case HOME_GRID:
                modeRes = R.layout.home_mode_grid;
                itemRes = R.layout.home_item_grid;
                scale = 1.04f;
                break;
            case HOME_TILES:
            default:
                modeRes = R.layout.home_mode_tiles;
                itemRes = R.layout.home_item_tile;
                scale = 1.08f;
                break;
        }

        ViewGroup container = findViewById(R.id.appFragmentContainer);
        container.removeAllViews();
        LayoutInflater.from(this).inflate(modeRes, container, true);

        heroTitle = container.findViewById(R.id.heroTitle);
        heroSubtitle = container.findViewById(R.id.heroSubtitle);
        heroButton = container.findViewById(R.id.heroButton);
        heroImage = container.findViewById(R.id.heroImage);
        heroImageText = container.findViewById(R.id.heroImageText);
        heroBadges = container.findViewById(R.id.heroBadges);
        homeList = container.findViewById(R.id.homeList);

        heroButton.setFocusable(false);
        heroButton.setOnClickListener(v -> {
            if (focusedApp != null) {
                onAppClicked(focusedApp, findItemViewForApp(focusedApp));
            }
        });

        homeAdapter = new HomeAppAdapter(appGridAdapter, this, itemRes, scale);
        if (layout == HOME_GRID) {
            final GridLayoutManager glm = new GridLayoutManager(this, 5);
            homeList.setLayoutManager(glm);
            homeList.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                int span = Math.max(2, (r - l - v.getPaddingLeft() - v.getPaddingRight()) / dp(132));
                if (span != glm.getSpanCount()) {
                    v.post(() -> glm.setSpanCount(span));
                }
            });
        } else {
            homeList.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
            if (layout == HOME_TILES) {
                // Pad so the first and last tile can sit in the centre
                final int tileWidth = dp(176 + 20);
                homeList.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                    int pad = Math.max(0, (r - l - tileWidth) / 2);
                    if (v.getPaddingLeft() != pad) {
                        v.post(() -> {
                            v.setPadding(pad, v.getPaddingTop(), pad, v.getPaddingBottom());
                            // Re-anchor so the focused tile sits in the middle
                            int p = focusedApp != null ? homeAdapter.positionOfAppId(focusedApp.app.getAppId()) : 0;
                            ((LinearLayoutManager) homeList.getLayoutManager())
                                    .scrollToPositionWithOffset(Math.max(0, p), 0);
                        });
                    }
                });
            }
        }
        homeList.setItemAnimator(null);
        homeList.setAdapter(homeAdapter);

        // The arrangement slides in as a whole
        container.setAlpha(0f);
        container.setTranslationX(dp(18));
        container.animate().alpha(1f).translationX(0f).setDuration(380)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();

        startHeroGlow();
        refreshHero();
        if (focusList) {
            focusInitialItem();
        }
    }

    private View findItemViewForApp(AppObject app) {
        if (homeList == null || homeAdapter == null) {
            return null;
        }
        RecyclerView.ViewHolder vh = homeList.findViewHolderForItemId(app.app.getAppId());
        return vh != null ? vh.itemView : null;
    }

    private void focusInitialItem() {
        if (homeList == null || homeAdapter == null || homeAdapter.getItemCount() == 0) {
            refreshHero();
            return;
        }

        int pos = RecyclerView.NO_POSITION;
        if (focusedApp != null) {
            pos = homeAdapter.positionOfAppId(focusedApp.app.getAppId());
        }
        if (pos == RecyclerView.NO_POSITION && lastRunningAppId != 0) {
            pos = homeAdapter.positionOfAppId(lastRunningAppId);
        }
        if (pos == RecyclerView.NO_POSITION) {
            pos = 0;
        }

        final int target = pos;
        focusedApp = homeAdapter.getApp(target);
        refreshHero();
        homeList.scrollToPosition(target);
        focusItemWhenReady(target, 0);
    }

    private View defaultFocusView;

    /** RecyclerView may still be laying out; retry a few frames until the tile takes focus. */
    private void focusItemWhenReady(final int target, final int attempt) {
        if (homeList == null) {
            return;
        }
        homeList.postDelayed(() -> {
            if (homeList == null) {
                return;
            }
            RecyclerView.ViewHolder vh = homeList.findViewHolderForAdapterPosition(target);
            if (vh != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // In touch mode nothing can take focus yet; make this tile the one the first
                // controller press lands on instead of the top bar
                if (defaultFocusView != null && defaultFocusView != vh.itemView) {
                    defaultFocusView.setFocusedByDefault(false);
                }
                defaultFocusView = vh.itemView;
                defaultFocusView.setFocusedByDefault(true);
            }
            boolean ok = vh != null && (vh.itemView.requestFocus() || homeList.isInTouchMode());
            if (!ok && attempt < 6) {
                focusItemWhenReady(target, attempt + 1);
            }
        }, attempt == 0 ? 0 : 80);
    }

    private void refreshHero() {
        if (heroTitle == null) {
            return;
        }

        String host = computer != null ? computer.name : getIntent().getStringExtra(NAME_EXTRA);
        AppObject app = focusedApp;
        if (app == null) {
            heroTitle.setText(host);
            heroSubtitle.setText(appGridAdapter != null ?
                    getString(R.string.home_apps_count, appGridAdapter.getCount()) : "");
            heroButton.setVisibility(View.INVISIBLE);
            return;
        }

        boolean running = app.app.getAppId() == lastRunningAppId && lastRunningAppId != 0;
        if (!app.app.getAppName().contentEquals(heroTitle.getText())) {
            // Slide the new title in
            for (View v : new View[]{heroTitle, heroSubtitle}) {
                v.setAlpha(0f);
                v.setTranslationX(dp(14));
                v.animate().alpha(1f).translationX(0f).setDuration(260)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
            }
        }
        heroTitle.setText(app.app.getAppName());
        if (app.isHidden) {
            heroSubtitle.setText(R.string.home_status_hidden);
        } else {
            heroSubtitle.setText(getString(running ? R.string.home_status_running : R.string.home_status_ready, host));
        }
        heroButton.setVisibility(View.VISIBLE);
        heroButton.setText(running ? R.string.home_continue : R.string.home_play);
        if (heroButton.getCompoundDrawablesRelative()[0] == null) {
            Drawable play = getResources().getDrawable(R.drawable.ic_play, getTheme()).mutate();
            play.setBounds(0, 0, dp(18), dp(18));
            play.setTint(0xFF050418);
            heroButton.setCompoundDrawablesRelative(play, null, null, null);
        }

        if (heroBadges != null) {
            heroBadges.removeAllViews();
            addPill(heroBadges, prefConfig.enableHdr ? "HDR" : "SDR");
            addPill(heroBadges, Math.round(prefConfig.fps) + " FPS");
            addPill(heroBadges, prefConfig.height + "p");
        }

        AppGridAdapter source = appGridAdapter;
        if (source != null && source.getLoader() != null) {
            if (heroImage != null) {
                source.getLoader().populateImageView(app.app, heroImage, heroImageText);
            }
            if (lastBackdropAppId != app.app.getAppId()) {
                lastBackdropAppId = app.app.getAppId();
                source.getLoader().populateImageView(app.app, homeBackdrop, backdropText);
                homeBackdrop.animate().cancel();
                homeBackdrop.setAlpha(0.08f);
                homeBackdrop.animate().alpha(0.30f).setDuration(600).start();
            }
        }
    }

    private void addPill(ViewGroup parent, String text) {
        TextView pill = (TextView) LayoutInflater.from(this).inflate(R.layout.home_badge, parent, false);
        pill.setText(text);
        parent.addView(pill);
    }

    private void updateConnectionPanel() {
        ViewGroup panel = findViewById(R.id.homeConnectionPanel);
        if (panel == null || prefConfig == null) {
            return;
        }
        panel.removeAllViews();

        String codec;
        switch (prefConfig.videoFormat) {
            case FORCE_AV1: codec = "AV1"; break;
            case FORCE_HEVC: codec = "HEVC"; break;
            case FORCE_H264: codec = "H.264"; break;
            case FORCE_PYROWAVE: codec = "PyroWave"; break;
            default: codec = getString(R.string.home_codec_auto); break;
        }

        addPill(panel, prefConfig.height + "p");
        addPill(panel, Math.round(prefConfig.fps) + " FPS");
        addPill(panel, Math.round(prefConfig.bitrate / 1000f) + " Mbps");
        addPill(panel, codec);
        addPill(panel, prefConfig.enableHdr ? "HDR ON" : "HDR OFF");
        if (prefConfig.useVirtualDisplay) {
            addPill(panel, "Virtual display");
        }
    }

    @Override
    public void onAppFocused(AppObject app, View view, int position) {
        focusedApp = app;
        refreshHero();

        if (homeLayout == HOME_TILES && homeList != null) {
            // Keep the focused tile in the middle of the carousel
            int dx = (view.getLeft() + view.getRight()) / 2 - homeList.getWidth() / 2;
            if (dx != 0) {
                homeList.smoothScrollBy(dx, 0);
            }
        }
    }

    @Override
    public void onAppClicked(AppObject app, View view) {
        if (computer == null || managerBinder == null) {
            return;
        }

        // Only open the context menu if something is running, otherwise start it
        if (lastRunningAppId != 0) {
            if (prefConfig.resumeWithoutConfirm && lastRunningAppId == app.app.getAppId()) {
                ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, prefConfig.useVirtualDisplay);
            } else {
                onAppMenu(app, view);
            }
        } else {
            if (prefConfig.useVirtualDisplay && !(computer.vDisplaySupported && computer.vDisplayDriverReady)) {
                UiHelper.displayVdisplayConfirmationDialog(
                        AppView.this,
                        computer,
                        () -> ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, true),
                        null
                );
            } else {
                ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, prefConfig.useVirtualDisplay);
            }
        }
    }

    @Override
    public void onAppMenu(AppObject app, View view) {
        menuTargetApp = app;
        menuTargetView = view != null ? view : heroButton;
        if (menuTargetView == null) {
            return;
        }
        menuTargetView.setOnCreateContextMenuListener(this);
        openContextMenu(menuTargetView);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean handled = code == KeyEvent.KEYCODE_BUTTON_Y || code == KeyEvent.KEYCODE_MENU;
        if (!handled || homeAdapter == null) {
            return super.dispatchKeyEvent(event);
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            {
                // Options for whichever app has focus
                View focus = getCurrentFocus();
                RecyclerView.ViewHolder vh = (focus != null && homeList != null) ? homeList.findContainingViewHolder(focus) : null;
                if (vh instanceof HomeAppAdapter.Holder && vh.getBindingAdapterPosition() != RecyclerView.NO_POSITION) {
                    onAppMenu(homeAdapter.getApp(vh.getBindingAdapterPosition()), vh.itemView);
                } else if (focusedApp != null) {
                    onAppMenu(focusedApp, findItemViewForApp(focusedApp));
                }
            }
        }
        return true;
    }

    public static class AppObject {
        public final NvApp app;
        public boolean isRunning;
        public boolean isHidden;

        public AppObject(NvApp app) {
            if (app == null) {
                throw new IllegalArgumentException("app must not be null");
            }
            this.app = app;
        }

        @Override
        public String toString() {
            return app.getAppName();
        }
    }
}
