package com.fntv.app.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fntv.app.R;
import com.fntv.app.RoundedImageView;
import com.fntv.app.api.model.MediaDbItem;
import com.fntv.app.util.SimpleImageLoader;

import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;

/**
 * 首页媒体库文件夹卡片行（≤3 张海报拼贴 + 底部标签）
 */
public class HomeLibrariesAdapter extends RecyclerView.Adapter<HomeLibrariesAdapter.VH> {

    public interface Listener {
        void onLibraryClick(MediaDbItem lib);
    }

    private final List<MediaDbItem> items = new ArrayList<>();
    private final String baseUrl;
    private final OkHttpClient client;
    private final Listener listener;

    public HomeLibrariesAdapter(String baseUrl, OkHttpClient client, Listener listener) {
        this.baseUrl = baseUrl;
        this.client = client;
        this.listener = listener;
    }

    public void setItems(List<MediaDbItem> libs) {
        items.clear();
        if (libs != null) items.addAll(libs);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_home_library, parent, false);
        v.setClipToOutline(true);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        final MediaDbItem lib = items.get(position);

        h.name.setText(lib.title != null ? lib.title : "");

        List<String> posters = new ArrayList<>();
        if (lib.posters != null) posters.addAll(lib.posters);
        if (posters.isEmpty() && lib.poster != null && !lib.poster.isEmpty()) posters.add(lib.poster);

        RoundedImageView[] views = {h.p1, h.p2, h.p3};
        for (int i = 0; i < views.length; i++) {
            RoundedImageView iv = views[i];
            iv.setCornerRadius(0);
            iv.setBackgroundColor(iv.getContext().getColor(R.color.img_placeholder_dark));
            String url = i < posters.size()
                    ? HomePosterAdapter.buildPosterUrl(baseUrl, posters.get(i)) : null;
            iv.setTag(url);
            if (url != null) {
                iv.setVisibility(View.VISIBLE);
                SimpleImageLoader.load(url, iv, client);
            } else if (i == 0) {
                // 无海报：显示首字占位
                iv.setVisibility(View.VISIBLE);
                iv.setImageDrawable(null);
            } else {
                iv.setVisibility(View.GONE);
            }
        }
        if (posters.isEmpty()) {
            h.placeholder.setText(lib.title != null && !lib.title.isEmpty()
                    ? lib.title.substring(0, 1) : "库");
            h.placeholder.setVisibility(View.VISIBLE);
        } else {
            h.placeholder.setVisibility(View.GONE);
        }

        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onLibraryClick(lib);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final RoundedImageView p1, p2, p3;
        final TextView name, placeholder;
        VH(View v) {
            super(v);
            p1 = v.findViewById(R.id.ivLibPoster1);
            p2 = v.findViewById(R.id.ivLibPoster2);
            p3 = v.findViewById(R.id.ivLibPoster3);
            name = v.findViewById(R.id.tvLibName);
            placeholder = new TextView(v.getContext());
            placeholder.setTextColor(v.getContext().getColor(R.color.text_secondary));
            placeholder.setTextSize(24);
            ((LinearLayout) ((ViewGroup) v).getChildAt(0)).addView(placeholder,
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
            placeholder.setGravity(android.view.Gravity.CENTER);
        }
    }
}
