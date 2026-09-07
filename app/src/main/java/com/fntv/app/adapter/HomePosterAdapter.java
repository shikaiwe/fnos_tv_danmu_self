package com.fntv.app.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
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
 * 首页媒体海报卡（2:3 海报 + 评分角标 + 清晰度角标 + 标题 + 副标题）
 * 支持按类型客户端过滤（全部/电影/电视节目）
 */
public class HomePosterAdapter extends RecyclerView.Adapter<HomePosterAdapter.VH> {

    public interface Listener {
        void onPosterClick(PlayListItem item);
    }

    public static final String FILTER_ALL = "ALL";
    public static final String FILTER_MOVIE = "Movie";
    public static final String FILTER_TV = "TV";

    private final List<PlayListItem> allItems = new ArrayList<>();
    private final List<PlayListItem> shownItems = new ArrayList<>();
    private final String baseUrl;
    private final OkHttpClient client;
    private final Listener listener;
    /** >0 时固定卡片宽度（横向行），0 时 match_parent（搜索结果网格） */
    private final int fixedWidthPx;
    private String filter = FILTER_ALL;

    public HomePosterAdapter(String baseUrl, OkHttpClient client, Listener listener, int fixedWidthPx) {
        this.baseUrl = baseUrl;
        this.client = client;
        this.listener = listener;
        this.fixedWidthPx = fixedWidthPx;
    }

    public void setItems(List<PlayListItem> items) {
        allItems.clear();
        if (items != null) allItems.addAll(items);
        applyFilter(filter);
    }

    /** 应用过滤，返回过滤后是否还有可见条目 */
    public boolean applyFilter(String newFilter) {
        filter = newFilter == null ? FILTER_ALL : newFilter;
        shownItems.clear();
        for (PlayListItem it : allItems) {
            if (matches(it)) shownItems.add(it);
        }
        notifyDataSetChanged();
        return !shownItems.isEmpty();
    }

    private boolean matches(PlayListItem it) {
        if (FILTER_MOVIE.equals(filter)) return "Movie".equals(it.type);
        if (FILTER_TV.equals(filter)) return "TV".equals(it.type) || "Episode".equals(it.type);
        return true;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_home_poster, parent, false);
        if (fixedWidthPx > 0) {
            RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) v.getLayoutParams();
            lp.width = fixedWidthPx;
            v.setLayoutParams(lp);
        }
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        final PlayListItem item = shownItems.get(position);
        bindPoster(h.itemView, item, baseUrl, client, v -> {
            if (listener != null) listener.onPosterClick(item);
        });
    }

    @Override
    public int getItemCount() {
        return shownItems.size();
    }

    /** 海报 URL 拼接（与 HomeActivity.makePosterUrl 规则一致） */
    public static String buildPosterUrl(String baseUrl, String path) {
        if (path == null || path.isEmpty()) return null;
        String p = path.startsWith("/") ? path : "/" + path;
        return baseUrl + "/v/api/v1/sys/img" + p + "?w=400";
    }

    /** 副标题：TV "共x集·yyyy" / "第x季·yyyy"，取 airDate 前四位年份 */
    public static String buildSubTitle(PlayListItem it) {
        String year = "";
        if (it.airDate != null && it.airDate.length() >= 4) year = it.airDate.substring(0, 4);
        String tail = year.isEmpty() ? "" : "·" + year;
        if (it.numberOfEpisodes > 0) return "共" + it.numberOfEpisodes + "集" + tail;
        if (it.seasonNumber > 0) return "第" + it.seasonNumber + "季" + tail;
        return year;
    }

    /** 绑定海报卡片视图（Adapter 与 HomeActivity 浏览网格共用同一布局） */
    public static void bindPoster(View card, PlayListItem item, String baseUrl,
                                  OkHttpClient client, View.OnClickListener click) {
        RoundedImageView iv = card.findViewById(R.id.ivPoster);
        TextView rating = card.findViewById(R.id.tvRating);
        TextView res = card.findViewById(R.id.tvResolution);
        TextView title = card.findViewById(R.id.tvPosterTitle);
        TextView sub = card.findViewById(R.id.tvPosterSub);

        iv.setCornerRadius(8);
        iv.setBackgroundColor(card.getContext().getColor(R.color.img_placeholder_dark));
        String url = buildPosterUrl(baseUrl, item.poster);
        iv.setTag(url);
        SimpleImageLoader.load(url, iv, client);

        String vote = item.voteAverage;
        boolean ratingShown = false;
        if (vote != null && !vote.isEmpty() && !"0".equals(vote) && !"0.0".equals(vote)) {
            try {
                float v = Float.parseFloat(vote);
                if (v > 0) {
                    rating.setText(String.format("%.1f", v));
                    rating.setVisibility(View.VISIBLE);
                    ratingShown = true;
                }
            } catch (NumberFormatException ignored) {}
        }
        if (!ratingShown) rating.setVisibility(View.GONE);

        if (item.mediaStream != null && item.mediaStream.resolutions != null
                && !item.mediaStream.resolutions.isEmpty()) {
            res.setText(item.mediaStream.resolutions.get(0));
            res.setVisibility(View.VISIBLE);
        } else {
            res.setVisibility(View.GONE);
        }

        title.setText(item.title != null ? item.title : "未知");
        String subStr = buildSubTitle(item);
        sub.setText(subStr);
        sub.setVisibility(subStr.isEmpty() ? View.GONE : View.VISIBLE);

        card.setOnClickListener(click);
    }

    /** 供代码构建网格处复用：inflate 同款海报卡片 */
    public static View inflatePosterCard(Context ctx) {
        return LayoutInflater.from(ctx).inflate(R.layout.item_home_poster, null);
    }

    static class VH extends RecyclerView.ViewHolder {
        final View itemView;
        VH(View v) { super(v); this.itemView = v; }
    }
}
