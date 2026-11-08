package com.limelight.grid;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.AppView;
import com.limelight.R;

/**
 * RecyclerView adapter for the Penumbra home screen (PEN-3). It shows the apps held by
 * {@link AppGridAdapter}, which stays the single source of truth for the app list, sorting
 * and hidden state, and reuses its asset loader for box art.
 */
public class HomeAppAdapter extends RecyclerView.Adapter<HomeAppAdapter.Holder> {

    public interface Listener {
        void onAppFocused(AppView.AppObject app, View view, int position);
        void onAppClicked(AppView.AppObject app, View view);
        void onAppMenu(AppView.AppObject app, View view);
    }

    private final AppGridAdapter source;
    private final Listener listener;
    private int itemLayoutId;
    private float focusScale;

    public HomeAppAdapter(AppGridAdapter source, Listener listener, int itemLayoutId, float focusScale) {
        this.source = source;
        this.listener = listener;
        this.itemLayoutId = itemLayoutId;
        this.focusScale = focusScale;
        setHasStableIds(true);
    }

    public AppView.AppObject getApp(int position) {
        return (AppView.AppObject) source.getItem(position);
    }

    public int positionOfAppId(int appId) {
        for (int i = 0; i < source.getCount(); i++) {
            if (getApp(i).app.getAppId() == appId) {
                return i;
            }
        }
        return RecyclerView.NO_POSITION;
    }

    @Override
    public int getItemCount() {
        return source.getCount();
    }

    @Override
    public long getItemId(int position) {
        return getApp(position).app.getAppId();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(itemLayoutId, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull final Holder holder, int position) {
        final AppView.AppObject obj = getApp(position);
        holder.app = obj;

        source.getLoader().populateImageView(obj.app, holder.image, holder.text);
        holder.live.setVisibility(obj.isRunning ? View.VISIBLE : View.GONE);
        holder.itemView.setAlpha(obj.isHidden ? 0.4f : 1.0f);
        holder.itemView.setContentDescription(obj.app.getAppName());

        boolean focused = holder.itemView.hasFocus() || holder.itemView.isFocused();
        holder.itemView.setScaleX(focused ? focusScale : 1f);
        holder.itemView.setScaleY(focused ? focusScale : 1f);
    }

    public class Holder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView text;
        final TextView live;
        AppView.AppObject app;

        Holder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.grid_image);
            text = itemView.findViewById(R.id.grid_text);
            live = itemView.findViewById(R.id.home_item_live);

            itemView.setOnClickListener(v -> {
                if (app != null) {
                    listener.onAppClicked(app, v);
                }
            });
            itemView.setOnLongClickListener(v -> {
                if (app != null) {
                    listener.onAppMenu(app, v);
                    return true;
                }
                return false;
            });
            itemView.setOnFocusChangeListener((v, hasFocus) -> {
                float s = hasFocus ? focusScale : 1f;
                v.animate().scaleX(s).scaleY(s).setDuration(140).start();
                if (hasFocus) {
                    v.setTranslationZ(8f);
                    int pos = getBindingAdapterPosition();
                    if (app != null && pos != RecyclerView.NO_POSITION) {
                        listener.onAppFocused(app, v, pos);
                    }
                } else {
                    v.setTranslationZ(0f);
                }
            });
        }
    }
}
