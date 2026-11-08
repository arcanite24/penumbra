package com.limelight;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.widget.ImageButton;

import com.limelight.profiles.ProfilesAdapter;
import com.limelight.profiles.ProfilesManager;
import com.limelight.utils.UiHelper;

public class ProfilesActivity extends AppCompatActivity implements ProfilesManager.ProfileChangeListener {
    private ProfilesAdapter adapter;
    private RecyclerView recyclerView;
    private View emptyState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profiles);

        // Setup RecyclerView
        recyclerView = findViewById(R.id.profilesRecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new ProfilesAdapter(this);
        recyclerView.setAdapter(adapter);

        // Setup empty state
        emptyState = findViewById(R.id.emptyState);

        // Penumbra (PEN-3) chrome
        findViewById(R.id.profilesBack).setOnClickListener(v -> finish());
        com.limelight.ui.EclipseBackgroundView eclipse = findViewById(R.id.profilesEclipse);
        eclipse.setIntensity(0.8f);
        eclipse.setEclipse(0.90f, 0.88f, 0.17f);
        UiHelper.applyNavigationBarPadding(findViewById(R.id.profilesContent));
        recyclerView.setItemAnimator(null);

        View addButton = findViewById(R.id.addProfileButton);
        addButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, EditProfileActivity.class);
            startActivity(intent);
        });

        // Register for profile changes
        ProfilesManager.getInstance().addListener(this);

        updateUI();
        View initialFocus = adapter.getItemCount() > 0 ? recyclerView : addButton;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            initialFocus.setFocusedByDefault(true);
        }

        UiHelper.notifyNewRootView(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ProfilesManager.getInstance().removeListener(this);
    }

    @Override
    public void onProfilesChanged() {
        runOnUiThread(this::updateUI);
    }

    private void updateUI() {
        int profileCount = ProfilesManager.getInstance().getProfiles().size();
        if (profileCount == 0) {
            recyclerView.setVisibility(View.GONE);
            emptyState.setVisibility(View.VISIBLE);
        } else {
            recyclerView.setVisibility(View.VISIBLE);
            emptyState.setVisibility(View.GONE);
        }
        adapter.notifyDataSetChanged();
    }
}