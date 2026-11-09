package com.limelight.preferences;

import android.app.Activity;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Section rail (PEN-3) shared by Settings and the profile editor. The preference tree stays one
 * screen so all the existing findPreference()/removePreference() logic keeps working; the rail
 * only shows one group of top-level categories at a time.
 */
public final class SettingsSectionRail {

    /** Activities that lay settings out with a section rail. */
    public interface Host {
        void onSettingsReady(StreamSettings.SettingsFragment fragment);
    }

    private static final class Section {
        final int title, subtitle, icon;
        final String[] categories;

        Section(int title, int subtitle, int icon, String... categories) {
            this.title = title;
            this.subtitle = subtitle;
            this.icon = icon;
            this.categories = categories;
        }
    }

    private static final Section[] SECTIONS = {
            new Section(R.string.section_stream, R.string.section_stream_sub, R.drawable.ic_section_stream,
                    "category_video_settings", "category_pyrowave_settings", "category_video_tuning"),
            new Section(R.string.section_audio, R.string.section_audio_sub, R.drawable.ic_section_audio,
                    "category_audio_settings"),
            new Section(R.string.section_controllers, R.string.section_controllers_sub, R.drawable.ic_section_controllers,
                    "category_gamepad_settings"),
            new Section(R.string.section_mouse, R.string.section_mouse_sub, R.drawable.ic_section_mouse,
                    "category_input_settings"),
            new Section(R.string.section_touch, R.string.section_touch_sub, R.drawable.ic_section_touch,
                    "category_onscreen_controls", "category_special_key_layout", "category_virtual_trackpad_settings"),
            new Section(R.string.section_host, R.string.section_host_sub, R.drawable.ic_section_host,
                    "category_host_settings", "category_clipboard_settings"),
            new Section(R.string.section_interface, R.string.section_interface_sub, R.drawable.ic_section_interface,
                    "category_ui_settings"),
            new Section(R.string.section_overlay, R.string.section_overlay_sub, R.drawable.ic_section_overlay,
                    "category_perf_monitor_settings"),
            new Section(R.string.section_advanced, R.string.section_advanced_sub, R.drawable.ic_section_advanced,
                    "category_advanced_settings"),
            new Section(R.string.section_about, R.string.section_about_sub, R.drawable.ic_section_about,
                    "category_settings_misc"),
    };

    private final Activity activity;
    private final ViewGroup rail;
    private final ArrayList<View> sectionViews = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private StreamSettings.SettingsFragment fragment;
    private int currentSection = 0;
    private int shownSection = -1;
    private int generation;

    public SettingsSectionRail(Activity activity, ViewGroup rail) {
        this.activity = activity;
        this.rail = rail;
    }

    /** Called each time a settings fragment (re)creates its view. */
    public void attach(StreamSettings.SettingsFragment fragment) {
        this.fragment = fragment;
        rail.removeAllViews();
        sectionViews.clear();
        handler.removeCallbacksAndMessages(null);

        LayoutInflater inflater = LayoutInflater.from(activity);
        for (int i = 0; i < SECTIONS.length; i++) {
            Section section = SECTIONS[i];
            boolean present = false;
            for (String key : section.categories) {
                present |= fragment.findPreference(key) != null;
            }
            View item = inflater.inflate(R.layout.settings_section_item, rail, false);
            ((ImageView) item.findViewById(R.id.section_icon)).setImageResource(section.icon);
            ((TextView) item.findViewById(R.id.section_title)).setText(section.title);
            ((TextView) item.findViewById(R.id.section_subtitle)).setText(section.subtitle);
            final int index = i;
            item.setOnClickListener(v -> showSection(index));
            // With a controller, moving through the rail previews each section
            item.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus && index != currentSection) {
                    previewSection(index);
                }
            });
            item.setVisibility(present ? View.VISIBLE : View.GONE);
            sectionViews.add(item);
            rail.addView(item);

            // Rail items glide in one after another
            item.setAlpha(0f);
            item.setTranslationX(-dp(24));
            item.animate().alpha(1f).translationX(0f).setStartDelay(30L * i).setDuration(320)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        }

        shownSection = -1;
        if (currentSection >= SECTIONS.length || sectionViews.get(currentSection).getVisibility() != View.VISIBLE) {
            currentSection = 0;
        }
        showSection(currentSection);
        View current = sectionViews.get(currentSection);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // requestFocus() is ignored in touch mode; this makes the first D-pad press land here
            current.setFocusedByDefault(true);
        }
        current.requestFocus();
    }

    private int dp(float v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    private void markSelected(int index) {
        for (int i = 0; i < sectionViews.size(); i++) {
            sectionViews.get(i).setSelected(i == index);
        }
    }

    /** Rail focus moves quickly with a D-pad; only swap the panel once it settles. */
    private void previewSection(int index) {
        markSelected(index);
        currentSection = index;
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(() -> showSection(index), 140);
    }

    private void showSection(int index) {
        if (fragment == null || fragment.getPreferenceScreen() == null) {
            return;
        }
        handler.removeCallbacksAndMessages(null);
        currentSection = index;
        markSelected(index);
        if (index == shownSection) {
            return;
        }
        final int gen = ++generation;
        final StreamSettings.SettingsFragment target = fragment;
        final RecyclerView list = target.getListView();

        Runnable swap = () -> {
            if (gen != generation || target.getPreferenceScreen() == null) {
                return;
            }
            shownSection = index;
            List<String> keys = Arrays.asList(SECTIONS[index].categories);
            PreferenceScreen screen = target.getPreferenceScreen();
            for (int i = 0; i < screen.getPreferenceCount(); i++) {
                Preference p = screen.getPreference(i);
                p.setVisible(p.getKey() != null && keys.contains(p.getKey()));
            }
            if (list == null) {
                return;
            }
            list.scrollToPosition(0);
            // The preference adapter rebuilds on the next frame; fade in once it has
            list.post(() -> list.post(() -> {
                if (gen != generation) {
                    return;
                }
                list.setTranslationY(dp(10));
                list.animate().alpha(1f).translationY(0f).setDuration(200)
                        .setInterpolator(new DecelerateInterpolator(2f)).start();
            }));
        };

        if (list == null || shownSection < 0) {
            if (list != null) {
                list.setAlpha(0f);
            }
            swap.run();
        } else {
            list.animate().cancel();
            list.animate().alpha(0f).translationY(0f).setDuration(90).withEndAction(swap).start();
        }
    }
}
