package com.fntv.app;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import com.fntv.app.api.FnApiManager;
import com.fntv.app.api.model.ApiResponse;
import com.fntv.app.api.model.PlayListItem;

import java.util.List;

import retrofit2.Call;
import retrofit2.Response;

/** 剧集列表管理 — 加载、切换剧集 */
public class EpisodeManager {

    public interface Callback {
        String getBaseUrl();
        String getParentGuid();
        String getItemGuid();
        int getEpisodeNumber();
        FnApiManager getApiManager();
        Context getContext();
        void onSwitchEpisode(String guid, String title);
    }

    private List<PlayListItem> episodeList;
    private int currentEpIndex = -1;
    private boolean loadingEpisodes = false;
    private final Callback cb;
    private final Button btnEpisodeList;
    private final Button btnPrevEp;
    private final Button btnNextEp;
    private String currentGuid;
    private String currentTitle;

    private static final String TAG = "Player";

    public EpisodeManager(Callback cb, Button btnEpisodeList, Button btnPrevEp, Button btnNextEp) {
        this.cb = cb;
        this.btnEpisodeList = btnEpisodeList;
        this.btnPrevEp = btnPrevEp;
        this.btnNextEp = btnNextEp;
    }

    /** 是否有上一集 */
    public boolean hasPrev() {
        return episodeList != null && currentEpIndex > 0;
    }

    /** 是否有下一集 */
    public boolean hasNext() {
        return episodeList != null && currentEpIndex >= 0 && currentEpIndex < episodeList.size() - 1;
    }

    /** 是否正在加载 */
    public boolean isLoading() { return loadingEpisodes; }

    /** 是否已加载列表 */
    public boolean isLoaded() { return episodeList != null && !episodeList.isEmpty(); }

    /** 加载剧集列表 */
    public void loadList(String parentGuid) {
        if (parentGuid == null || parentGuid.isEmpty()) return;
        loadingEpisodes = true;
        Log.d(TAG, "getEpisodeList 请求: " + cb.getBaseUrl() + "/v/api/v1/episode/list/" + parentGuid);
        cb.getApiManager().getApi().getEpisodeList(parentGuid).enqueue(
                new retrofit2.Callback<ApiResponse<List<PlayListItem>>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<List<PlayListItem>>> call,
                                           Response<ApiResponse<List<PlayListItem>>> resp) {
                        loadingEpisodes = false;
                        Log.d(TAG, "getEpisodeList 响应 code=" + resp.code()
                                + " body=" + (resp.body() != null ? "code=" + resp.body().code + " size="
                                + (resp.body().data != null ? resp.body().data.size() : "null") : "null"));
                        if (resp.isSuccessful() && resp.body() != null && resp.body().code == 0
                                && resp.body().data != null && !resp.body().data.isEmpty()) {
                            episodeList = resp.body().data;
                            currentEpIndex = -1;
                            int epNum = cb.getEpisodeNumber();
                            String itemGuid = cb.getItemGuid();
                            for (int i = 0; i < episodeList.size(); i++) {
                                PlayListItem ep = episodeList.get(i);
                                if (ep.guid.equals(itemGuid)) {
                                    currentEpIndex = i;
                                    break;
                                }
                                if (currentEpIndex < 0 && epNum > 0 && ep.episodeNumber == epNum) {
                                    currentEpIndex = i;
                                }
                            }
                            Log.d(TAG, "getEpisodeList 成功: " + episodeList.size() + " 集, currentIdx=" + currentEpIndex
                                    + " epNum=" + epNum + " itemGuid=" + itemGuid);
                            btnEpisodeList.setVisibility(View.VISIBLE);
                            updateNavButtons();
                        }
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<List<PlayListItem>>> call, Throwable t) {
                        loadingEpisodes = false;
                        Log.e(TAG, "getEpisodeList 失败: " + t.getMessage());
                    }
                });
    }

    /** 播放上一个剧集 */
    public void playPrev() {
        if (!hasPrev()) return;
        PlayListItem prev = episodeList.get(currentEpIndex - 1);
        currentEpIndex--;
        currentGuid = prev.guid;
        currentTitle = prev.title;
        updateNavButtons();
        cb.onSwitchEpisode(currentGuid, currentTitle);
    }

    /** 播放下一个剧集 */
    public void playNext() {
        if (!hasNext()) return;
        PlayListItem next = episodeList.get(currentEpIndex + 1);
        currentEpIndex++;
        currentGuid = next.guid;
        currentTitle = next.title;
        updateNavButtons();
        cb.onSwitchEpisode(currentGuid, currentTitle);
    }

    /** 显示剧集选择抽屉（右侧抽屉样式） */
    public void showPicker() {
        if (episodeList == null || episodeList.isEmpty()) return;
        java.util.List<SideDrawerHelper.Item> items = new java.util.ArrayList<>();
        for (int i = 0; i < episodeList.size(); i++) {
            PlayListItem ep = episodeList.get(i);
            String title = "EP" + (ep.episodeNumber > 0 ? ep.episodeNumber : (i + 1));
            items.add(new SideDrawerHelper.Item(title, ep.title != null ? ep.title : "", i == currentEpIndex));
        }
        new SideDrawerHelper((android.app.Activity) cb.getContext()).show("选集", items,
                null, null, null, null,
                which -> {
                    if (which < 0 || which >= episodeList.size()) return;
                    PlayListItem s = episodeList.get(which);
                    currentEpIndex = which;
                    currentGuid = s.guid;
                    currentTitle = s.title;
                    updateNavButtons();
                    cb.onSwitchEpisode(currentGuid, currentTitle);
                }, null);
    }

    /** 重置状态（切换到新剧时调用） */
    public void reset() {
        episodeList = null;
        currentEpIndex = -1;
        loadingEpisodes = false;
        if (btnPrevEp != null) btnPrevEp.setVisibility(View.GONE);
        if (btnNextEp != null) btnNextEp.setVisibility(View.GONE);
    }

    private void updateNavButtons() {
        if (btnPrevEp != null) btnPrevEp.setVisibility(hasPrev() ? View.VISIBLE : View.GONE);
        if (btnNextEp != null) btnNextEp.setVisibility(hasNext() ? View.VISIBLE : View.GONE);
    }
}
