package com.limelight;

import android.app.Application;
import android.widget.Toast;

import com.limelight.profiles.ProfilesManager;

public class ArtemisApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ProfilesManager profilesManager = ProfilesManager.getInstance();
        if (!profilesManager.load(this)) {
            com.limelight.utils.PenumbraToast.makeText(this, R.string.profile_manager_failed_to_load, Toast.LENGTH_LONG).show();
        }

        // Penumbra (PEN-3): every screen is fullscreen; Game manages its own system UI
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(android.app.Activity activity) {
                if (!(activity instanceof Game)) {
                    com.limelight.utils.UiHelper.applyImmersive(activity.getWindow());
                }
            }

            @Override public void onActivityCreated(android.app.Activity a, android.os.Bundle b) { }
            @Override public void onActivityStarted(android.app.Activity a) { }
            @Override public void onActivityPaused(android.app.Activity a) { }
            @Override public void onActivityStopped(android.app.Activity a) { }
            @Override public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) { }
            @Override public void onActivityDestroyed(android.app.Activity a) { }
        });
    }
}