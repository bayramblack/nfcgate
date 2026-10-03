package work.undernet.nfc.gui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.os.Bundle;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import androidx.core.view.GravityCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.appcompat.widget.Toolbar;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import work.undernet.nfc.R;
import work.undernet.nfc.db.SessionLog;
import work.undernet.nfc.db.pcapng.ISO14443Stream;
import work.undernet.nfc.db.worker.LogInserter;
import work.undernet.nfc.gui.fragment.AboutFragment;
import work.undernet.nfc.gui.fragment.CaptureFragment;
import work.undernet.nfc.gui.fragment.CloneFragment;
import work.undernet.nfc.gui.fragment.StatusFragment;
import work.undernet.nfc.gui.log.LoggingFragment;
import work.undernet.nfc.gui.fragment.RelayFragment;
import work.undernet.nfc.gui.fragment.ReplayFragment;
import work.undernet.nfc.gui.fragment.SettingsFragment;
import work.undernet.nfc.network.UserTrustManager;
import work.undernet.nfc.nfc.NfcManager;
import work.undernet.nfc.util.NfcComm;
import work.undernet.nfc.xposed.InjectionBroadcastWrapper;

public class MainActivity extends AppCompatActivity {
    private AppUpdater updater;
    private BottomNavigationView bottomNavigation;
    private int currentNavigationId = R.id.nav_relay;
    // UI
    DrawerLayout mDrawerLayout;
    NavigationView mNavbar;
    Toolbar mToolbar;
    ActionBarDrawerToggle mToggle;

    // NFC
    NfcManager mNfc;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        updater = new AppUpdater(this);
        // Apply the new replay default once, including installations with an old false value.
        // Future manual choices are preserved if the Settings menu is restored.
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        if (!preferences.getBoolean("advanced_replay_default_v1", false)) {
            preferences.edit().putBoolean("network", true)
                    .putBoolean("advanced_replay_default_v1", true).apply();
        }
        // Initialize before restored fragments inflate their connection views.
        mNfc = new NfcManager(this);
        UserTrustManager.init(this);
        setContentView(R.layout.activity_main);

        // toolbar setup
        mToolbar = findViewById(R.id.toolbar);
        setSupportActionBar(mToolbar);

        // drawer setup
        mDrawerLayout = findViewById(R.id.main_drawer_layout);

        // drawer toggle in toolbar
        mToggle = new ActionBarDrawerToggle(this, mDrawerLayout, mToolbar, R.string.empty, R.string.empty);
        mToggle.setToolbarNavigationClickListener(v -> {
            // when drawer icon is NOT visible (due to fragment on backstack), issue back action
            getOnBackPressedDispatcher().onBackPressed();
        });
        mDrawerLayout.addDrawerListener(mToggle);

        // display "up-arrow" when non-empty backstack, display navigation drawer otherwise
        final FragmentManager fragmentManager = getSupportFragmentManager();
        final ActionBar actionBar = getSupportActionBar();

        fragmentManager.addOnBackStackChangedListener(() -> {
            if (fragmentManager.getBackStackEntryCount() > 0) {
                // https://stackoverflow.com/a/29594947
                actionBar.setDisplayHomeAsUpEnabled(false);
                mToggle.setDrawerIndicatorEnabled(false);
                actionBar.setDisplayHomeAsUpEnabled(true);
            } else {
                actionBar.setDisplayHomeAsUpEnabled(false);
                mToggle.setDrawerIndicatorEnabled(true);
                mToggle.syncState();
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mDrawerLayout.isDrawerOpen(GravityCompat.START)) {
                    mDrawerLayout.closeDrawer(GravityCompat.START);
                    return;
                }
                // reset the subtitle because a fragment might have changed it
                Objects.requireNonNull(getSupportActionBar()).setSubtitle(null);

                // execute the original back action
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });

        // navbar setup actions
        mNavbar = findViewById(R.id.main_navigation);
        bottomNavigation = findViewById(R.id.bottom_navigation);
        bottomNavigation.setOnItemSelectedListener(item -> {
            if (item.getItemId() == R.id.nav_more) {
                mDrawerLayout.openDrawer(GravityCompat.START);
                return false;
            }
            if (item.getItemId() != currentNavigationId || getSupportFragmentManager().getBackStackEntryCount() > 0)
                onNavbarAction(mNavbar.getMenu().findItem(item.getItemId()));
            return true;
        });
        mNavbar.setNavigationItemSelectedListener(item -> {
            if (item.getItemId() == R.id.nav_update) {
                mDrawerLayout.closeDrawers();
                updater.check(true);
                return false;
            }
            if (item.getItemId() == R.id.nav_language) {
                mDrawerLayout.closeDrawers();
                showLanguagePicker();
                return false;
            }
            // do not reselect the same item, avoiding unnecessary fragment recreation
            if (mNavbar.getCheckedItem() == item)
                mDrawerLayout.closeDrawers();
            else
                onNavbarAction(item);

            return true;
        });

        // Begin with connection setup. NFC is checked when a role is selected.
        if (savedInstanceState == null) {
            mNavbar.getMenu().performIdentifierAction(R.id.nav_relay, 0);
            mNavbar.setCheckedItem(R.id.nav_relay);
        } else {
            currentNavigationId = savedInstanceState.getInt("selected_navigation", R.id.nav_relay);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        // pass initial intent to current mode in case it carries a tag
        if (getIntent() != null)
            onNewIntent(getIntent());
    }

    @Override
    protected void onPostCreate(@Nullable Bundle savedInstanceState) {
        mToggle.syncState();
        super.onPostCreate(savedInstanceState);
        syncNavigation();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putInt("selected_navigation", currentNavigationId);
        super.onSaveInstanceState(state);
    }

    private void syncNavigation() {
        MenuItem selected = mNavbar.getMenu().findItem(currentNavigationId);
        if (selected == null) return;
        mNavbar.setCheckedItem(selected);
        if (getSupportFragmentManager().getBackStackEntryCount() == 0)
            getSupportActionBar().setTitle(selected.getTitle());
        boolean mainDestination = currentNavigationId == R.id.nav_relay
                || currentNavigationId == R.id.nav_logging || currentNavigationId == R.id.nav_status;
        bottomNavigation.getMenu().findItem(mainDestination ? currentNavigationId : R.id.nav_more).setChecked(true);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if ("work.undernet.nfc.OPEN_SESSION".equals(intent.getAction())) {
            onNavbarAction(mNavbar.getMenu().findItem(R.id.nav_relay));
            mNavbar.setCheckedItem(R.id.nav_relay);
            return;
        }
        // tech discovered is triggered by XML, tag discovered by foreground dispatch
        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(intent.getAction())
                || NfcAdapter.ACTION_TAG_DISCOVERED.equals(intent.getAction())
                || NfcAdapter.ACTION_NDEF_DISCOVERED.equals(intent.getAction()))
            mNfc.onTagDiscovered(intent.getParcelableExtra(NfcAdapter.EXTRA_TAG));
        else if (Intent.ACTION_SEND.equals(intent.getAction()))
            importPcap(intent.getParcelableExtra(Intent.EXTRA_STREAM));
        else if (Intent.ACTION_VIEW.equals(intent.getAction()))
            importPcap(intent.getData());
        else if (InjectionBroadcastWrapper.UNDERNET_BROADCAST.equals(intent.getAction()))
            mNfc.getDaemon().onResponse(intent);
        else
            super.onNewIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mNfc.onResume();
        work.undernet.nfc.network.SessionCoordinator.get(this).restore();
        updater.check(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mNfc.onPause();
    }

    @Override
    protected void onDestroy() {
        if (updater != null) updater.close();
        super.onDestroy();
    }

    /**
     * Returns a Fragment for every navbar action
     */
    private Fragment getFragmentByAction(int id) {
        if (R.id.nav_clone == id) {
            return new CloneFragment();
        } else if (R.id.nav_relay == id) {
            return new RelayFragment();
        } else if (R.id.nav_replay == id) {
            return new ReplayFragment();
        } else if (R.id.nav_capture == id) {
            return new CaptureFragment();
        } else if (R.id.nav_settings == id) {
            return new SettingsFragment();
        } else if (R.id.nav_status == id) {
            return new StatusFragment();
        } else if (R.id.nav_about == id) {
            return new AboutFragment();
        } else if (R.id.nav_logging == id) {
            return new LoggingFragment();
        }

        throw new IllegalArgumentException("Position out of range");
    }

    /**
     * Handles all navbar actions by creating a new fragment
     */
    private void onNavbarAction(MenuItem item) {
        currentNavigationId = item.getItemId();
        syncNavigation();
        // every fragment must implement BaseFragment
        Fragment fragment = getFragmentByAction(item.getItemId());

        // remove all currently opened on-top fragments (e.g. log entry)
        getSupportFragmentManager().popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        // no fancy animation for now
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.main_content, fragment)
                .commit();

        // for the looks
        getSupportActionBar().setTitle(item.getTitle());
        // reset the subtitle because a fragment might have changed it
        getSupportActionBar().setSubtitle(null);
        // hide status bar
        findViewById(R.id.banner).setVisibility(View.GONE);

        // avoid carrying over actions from previous fragment
        supportInvalidateOptionsMenu();

        mDrawerLayout.closeDrawers();
    }

    private void showLanguagePicker() {
        String[] tags = {"", "fa-IR", "en-US", "ru-RU", "zh-CN", "ar"};
        String[] labels = {getString(R.string.language_system), "فارسی", "English",
                "Русский", "简体中文", "العربية"};
        LocaleListCompat locales = AppCompatDelegate.getApplicationLocales();
        int selected = 0;
        if (!locales.isEmpty()) {
            for (int i = 1; i < tags.length; i++) {
                if (java.util.Locale.forLanguageTag(tags[i]).getLanguage()
                        .equals(locales.get(0).getLanguage())) selected = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_language)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    dialog.dismiss();
                    AppCompatDelegate.setApplicationLocales(
                            LocaleListCompat.forLanguageTags(tags[which]));
                })
                .setNegativeButton(R.string.button_cancel, null)
                .show();
    }

    private void importPcap(Uri uri) {
        try {
            LogInserter inserter = new LogInserter(this, SessionLog.SessionType.RELAY, null);

            for (NfcComm e : new ISO14443Stream().readAll(getContentResolver().openInputStream(uri)))
                inserter.log(e);
            Toast.makeText(this, getString(R.string.pcap_success), Toast.LENGTH_SHORT).show();
        }
        catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, getString(R.string.pcap_error), Toast.LENGTH_SHORT).show();
        }
    }

    public void importCapture(List<byte[]> capture) {
        LogInserter inserter = new LogInserter(this, SessionLog.SessionType.CAPTURE, null);

        for (byte[] b : capture)
            inserter.log(new NfcComm(b));

        Toast.makeText(this, getString(R.string.pcap_log, capture.size()), Toast.LENGTH_SHORT).show();
    }

    /**
     * Displays a warning dialog with the specified message
     */
    public void showWarning(String warning) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.status_warning))
                .setMessage(warning)
                .setNegativeButton(R.string.button_ok, null)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .show();
    }
    /**
     * Display an informational Toast with the specified message
     */
    public void showInfo(String info) {
        Toast.makeText(this, info, Toast.LENGTH_SHORT).show();
    }

    public NfcManager getNfc() {
        return mNfc;
    }
}
