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
import com.fntv.app.api.model.PlayListItem;
import com.fntv.app.util.SimpleImageLoader;

import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;

/**
 * 首页继续观看卡片行（16:9 缩略图 + 底部蓝色进度条 + 标题 + 第x季·第x集）
 */
public class HomeContinueAdapter extends RecyclerView.Adapter<HomeContinueAdapter.VH> {

    public interface Listener {
        void onContinueClick(PlayListItem item);
    }

    private final List<PlayListItem> items = new ArrayList<>();
    private final String baseUrl;
    private final OkHttpClient client;
    private final Listener listener;

    public HomeContinueAdapter(String baseUrl, OkHttpClient client, Listener listener) {
        this.baseUrl = baseUrl;
        this.client = client;
        this.listener = listener;
    }

    public void setItems(List<PlayListItem> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_home_continue, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        final PlayListItem item = items.get(position);

        h.backdrop.setCornerRadius(8);
        h.backdrop.setBackgroundColor(h.backdrop.getContext().getColor(R.color.img_placeholder_dark));
        String url = HomePosterAdapter.buildPosterUrl(baseUrl, item.poster);
        h.backdrop.setTag(url);
        SimpleImageLoader.load(url, h.backdrop, client);

        int pct = item.duration > 0
                ? Math.max(0, Math.min(100, (int) (item.ts * 100 / item.duration))) : 0;
        LinearLayout.LayoutParams fillLp = (LinearLayout.LayoutParams) h.fill.getLayoutParams();
        fillLp.weight = pct;
        h.fill.setLayoutParams(fillLp);
        LinearLayout.LayoutParams restLp = (LinearLayout.LayoutParams) h.rest.getLayoutParams();
        restLp.weight = 100 - pct;
        h.rest.setLayoutParams(restLp);
        h.container.setVisibility(pct > 0 ? View.VISIBLE : View.GONE);

        String title = item.tvTitle != null && !item.tvTitle.isEmpty() ? item.tvTitle
                : (item.title != null ? item.title : "未知");
        h.title.setText(title);

        StringBuilder sub = new StringBuilder();
        if (item.seasonNumber > 0) sub.append("第").append(item.seasonNumber).append("季");
        if (item.episodeNumber > 0) {
            if (sub.length() > 0) sub.append("·");
            sub.append("第").append(item.episodeNumber).append("集");
        }
        h.sub.setText(sub.toString());
        h.sub.setVisibility(sub.length() > 0 ? View.VISIBLE : View.GONE);

        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onContinueClick(item);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final RoundedImageView backdrop;
        final LinearLayout container;
        final View fill, rest;
        final TextView title, sub;

        VH(View v) {
            super(v);
            backdrop = v.findViewById(R.id.ivContinueBackdrop);
            container = v.findViewById(R.id.progressContainer);
            fill = v.findViewById(R.id.progressFill);
            rest = v.findViewById(R.id.progressRest);
            title = v.findViewById(R.id.tvContinueTitle);
            sub = v.findViewById(R.id.tvContinueSub);
        }
    }
}
