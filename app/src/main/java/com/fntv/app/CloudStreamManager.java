package com.fntv.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.fntv.app.api.FnApiManager;
import com.fntv.app.api.model.ApiResponse;
import com.fntv.app.api.model.StreamResponse;
import com.google.gson.Gson;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.jdtech.mpv.MPVLib;
import retrofit2.Call;
import retrofit2.Response;

/** 云直链管理 — Stream API 请求、质量切换、播放模式管理 */
public class CloudStreamManager {

    public interface Callback {
        String getBaseUrl();
        String getMediaGuid();
        FnApiManager getApiManager();
        Context getContext();
        SharedPreferences getPrefs();
        void onStreamInfoParsed(StreamInfo info);
        void onStreamDataFailed();
        void startPlayback();
        void reloadPlayback();
        void onTrackChanged();
        void probeWithMediaExtractor();
        void onCloudBtnVisibilityChanged(boolean vis);
        void runOnUiThread(Runnable r);
        /** 打开系统文件选择器挑选本地外挂字幕（字幕抽屉"+ 字幕"按钮） */
        void pickExternalSubtitleFile();
        /** 打开字幕样式调整页（字幕抽屉"调整"入口） */
        void showSubtitleStylePanel();
        /** 加载下载好的外挂字幕字节（ASS 双语合并后交给 mpv），返回是否加载成功 */
        boolean loadExternalSubtitleBytes(byte[] bytes, String fileName);
    }

    /** Stream API 解析出的音视频元数据（传给 Activity 更新信息面板） */
    public static class StreamInfo {
        public int bitrate, width, height, bitDepth, duration;
        public long fileSize;
        public String vCodec, vProfile, vPixFmt, vColor, vFps, container;
        public String resolution;
        public boolean vHdr;
        public List<StreamResponse.AudioStreamInfo> audioTracks;
        public List<StreamResponse.SubtitleStreamInfo> subtitleTracks;
    }

    /** 播放配置（getPlaybackConfig 返回） */
    public static class PlaybackConfig {
        public final String url;
        public final boolean hls;

        public PlaybackConfig(String url, boolean hls) {
            this.url = url;
            this.hls = hls;
        }
    }

    private final Callback cb;
    private final Button btnCloudMode;
    private final SharedPreferences prefs;

    // 云直链状态
    private boolean cloudDirectMode = true;
    private String cloudDirectUrl = "";
    private boolean isStrmFile = false;
    private int qualityIndex = 1;
    private String[] qualityLabels;
    private String[] qualityUrls;
    private int qualityCount = 0;

    // mpv 视图引用（用于音轨/字幕切换，由外部注入）
    private CustomMPVView playerView;

    // Stream API 返回的音轨/字幕信息（供对话框显示标签用）
    private List<StreamResponse.AudioStreamInfo> streamAudioTracks;
    private List<StreamResponse.SubtitleStreamInfo> streamSubtitleTracks;

    // 用户最后一次选择的音轨/字幕标签（供信息面板显示）
    private String lastAudioTrackLabel = "";
    private String lastSubtitleTrackLabel = "";

    private static final String TAG = "Player";

    public CloudStreamManager(Callback cb, Button btnCloudMode, SharedPreferences prefs) {
        this.cb = cb;
        this.btnCloudMode = btnCloudMode;
        this.prefs = prefs;
    }

    // ========== 外部调用 ==========

    /** 从 SharedPreferences 恢复初始状态（onCreate 时调用） */
    public void initFromPrefs() {
        cloudDirectMode = prefs.getBoolean("cloud_direct_mode", true);
        qualityIndex = prefs.getInt("cloud_quality_index", 1);
        updateCloudBtnText();
        btnCloudMode.setOnClickListener(v -> showQualityMenu());
    }

    /** 获取当前播放配置（startPlayback 中调用） */
    public PlaybackConfig getPlaybackConfig(String baseUrl, String mediaGuid) {
        String url;
        if (!cloudDirectUrl.isEmpty()) {
            url = cloudDirectUrl;
            Log.d(TAG, "播放模式: 直链 " + url);
        } else if (cloudDirectMode && qualityCount > 0) {
            url = baseUrl + "/v/api/v1/media/range/" + mediaGuid + "?direct_link_quality_index=" + qualityIndex;
            Log.d(TAG, "播放模式: 直链 (NAS代理, index=" + qualityIndex + ") " + url);
        } else {
            url = baseUrl + "/v/api/v1/media/range/" + mediaGuid;
            Log.d(TAG, "播放模式: 代理 " + url);
        }
        boolean hls = url.contains(".m3u8");
        if (!hls && !cloudDirectUrl.isEmpty()) {
            hls = isStrmFile;
            Log.d(TAG, "直链模式: isStrm=" + isStrmFile + " useHls=" + hls);
        }
        return new PlaybackConfig(url, hls);
    }

    /** 获取当前直链 URL（用于画质切换、信息面板等） */
    public String getCloudDirectUrl() { return cloudDirectUrl; }

    /** 是否处于直链播放模式 */
    public boolean hasDirectUrl() { return !cloudDirectUrl.isEmpty(); }

    /** 注入 mpv 视图引用（用于音轨/字幕切换） */
    public void setPlayerView(CustomMPVView playerView) { this.playerView = playerView; }

    /** 获取用户最后选择的音轨标签（供信息面板展示） */
    public String getLastAudioTrackLabel() { return lastAudioTrackLabel; }
    /** 获取用户最后选择的字幕标签（供信息面板展示） */
    public String getLastSubtitleTrackLabel() { return lastSubtitleTrackLabel; }

    // ========== Stream API ==========

    /** 调用 stream API 获取直链并解析（从 loadPlayInfo 回调中调用） */
    public void fetchDirectLink(final String itemGuid, final String mediaGuid) {
        if (mediaGuid == null) {
            cb.onStreamDataFailed();
            return;
        }
        Map<String, Object> streamReq = new HashMap<>();
        Map<String, Object> header = new HashMap<>();
        header.put("User-Agent", new String[]{"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"});
        streamReq.put("header", header);
        int qualityLevel = prefs.getInt("stream_quality_level", 1);
        streamReq.put("level", qualityLevel);
        streamReq.put("media_guid", mediaGuid);
        // ip = 账号的 MD5 哈希
        String account = prefs.getString("user", "video");
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(account.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            streamReq.put("ip", sb.toString());
        } catch (Exception e) {
            streamReq.put("ip", "");
        }
        streamReq.put("nonce", String.valueOf(100000 + (int) (Math.random() * 900000)));
        String reqJson = new Gson().toJson(streamReq);
        Log.d(TAG, "getStream 请求体: " + reqJson);
        Log.d(TAG, "getStream 请求URL: " + cb.getBaseUrl() + "/v/api/v1/stream");

        cb.getApiManager().getApi().getStream(streamReq)
                .enqueue(new retrofit2.Callback<ApiResponse<StreamResponse>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<StreamResponse>> call,
                                           Response<ApiResponse<StreamResponse>> r) {
                        try {
                            Log.d(TAG, "getStream resp code=" + r.code());
                            if (r.isSuccessful() && r.body() != null && r.body().code == 0
                                    && r.body().data != null) {
                                StreamResponse sd = r.body().data;
                                StreamInfo info = new StreamInfo();

                                // 视频流
                                if (sd.videoStream != null) {
                                    info.bitrate = sd.videoStream.bps;
                                    info.vCodec = sd.videoStream.codecName != null ? sd.videoStream.codecName : "";
                                    info.vProfile = sd.videoStream.profile != null ? sd.videoStream.profile : "";
                                    info.width = sd.videoStream.width;
                                    info.height = sd.videoStream.height;
                                    info.bitDepth = sd.videoStream.bitDepth;
                                    info.vHdr = sd.videoStream.dvProfile > 0;
                                    info.vPixFmt = sd.videoStream.pixFmt != null ? sd.videoStream.pixFmt : "";
                                    info.vColor = sd.videoStream.colorPrimaries != null ? sd.videoStream.colorPrimaries : "";
                                    String cs = sd.videoStream.colorSpace != null ? sd.videoStream.colorSpace : "";
                                    if (!info.vColor.isEmpty() && !cs.isEmpty()) info.vColor += " " + cs;
                                    info.vFps = sd.videoStream.rFrameRate != null ? sd.videoStream.rFrameRate : "";
                                    info.duration = sd.videoStream.duration;
                                }

                                // 文件信息
                                boolean isStrm = false;
                                if (sd.fileStream != null) {
                                    info.fileSize = sd.fileStream.size;
                                    String fn = sd.fileStream.fileName != null ? sd.fileStream.fileName : "";
                                    String fp = sd.fileStream.path != null ? sd.fileStream.path : "";
                                    if (fn.contains("."))
                                        info.container = fn.substring(fn.lastIndexOf('.') + 1).toLowerCase();
                                    if (info.duration <= 0) info.duration = sd.fileStream.duration;
                                    isStrm = fp.toLowerCase().endsWith(".strm") || fn.toLowerCase().endsWith(".strm");
                                }

                                // STRM 处理
                                if (isStrm && sd.directLinkQualities != null && !sd.directLinkQualities.isEmpty()) {
                                    isStrmFile = true;
                                    String directUrl = sd.directLinkQualities.get(0).url;
                                    directUrl = directUrl.replace("\\u0026", "&");
                                    cloudDirectUrl = directUrl;
                                    cloudDirectMode = true;
                                    qualityCount = sd.directLinkQualities.size();
                                    qualityLabels = new String[qualityCount];
                                    qualityUrls = new String[qualityCount];
                                    for (int qi = 0; qi < qualityCount; qi++) {
                                        StreamResponse.DirectLinkQuality dlq = sd.directLinkQualities.get(qi);
                                        qualityLabels[qi] = dlq.resolution != null && !dlq.resolution.isEmpty()
                                                ? dlq.resolution : ("画质" + qi);
                                        String u = dlq.url != null ? dlq.url.replace("\\u0026", "&") : "";
                                        qualityUrls[qi] = u;
                                    }
                                    if (qualityIndex >= qualityCount) qualityIndex = 0;
                                    cloudDirectUrl = qualityUrls[qualityIndex];
                                    Log.d(TAG, "STRM 文件，使用直链: " + cloudDirectUrl);
                                    cb.runOnUiThread(() -> {
                                        setCloudBtnVisible(true);
                                        updateCloudBtnText();
                                    });
                                }

                                // 音频/字幕流（保存本地副本供音轨切换用）
                                streamAudioTracks = sd.audioStreams;
                                streamSubtitleTracks = sd.subtitleStreams;
                                info.audioTracks = sd.audioStreams;
                                info.subtitleTracks = sd.subtitleStreams;

                                // 非 STRM 的画质信息
                                qualityCount = sd.directLinkQualities != null ? sd.directLinkQualities.size() : 0;
                                if (qualityCount > 0) {
                                    qualityLabels = new String[qualityCount];
                                    for (int qi = 0; qi < qualityCount; qi++) {
                                        StreamResponse.DirectLinkQuality q = sd.directLinkQualities.get(qi);
                                        qualityLabels[qi] = q.resolution != null && !q.resolution.isEmpty()
                                                ? q.resolution : ("画质" + qi);
                                    }
                                    if (qualityIndex >= qualityCount) qualityIndex = 0;
                                    cb.runOnUiThread(() -> {
                                        setCloudBtnVisible(true);
                                        updateCloudBtnText();
                                    });
                                }

                                // 取 resolution：直链优先，代理取 qualities[0]
                                if (sd.directLinkQualities != null && qualityIndex < sd.directLinkQualities.size()) {
                                    info.resolution = sd.directLinkQualities.get(qualityIndex).resolution;
                                    if (sd.directLinkQualities.get(qualityIndex).bitrate > 0)
                                        info.bitrate = sd.directLinkQualities.get(qualityIndex).bitrate;
                                } else if (sd.qualities != null && !sd.qualities.isEmpty()) {
                                    info.resolution = sd.qualities.get(0).resolution;
                                    info.bitrate = sd.qualities.get(0).bitrate;
                                }
                                // 回调 Activity 更新显示信息
                                cb.onStreamInfoParsed(info);
                            } else {
                                cb.onStreamDataFailed();
                                return;
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "getStream parse error", e);
                            cb.onStreamDataFailed();
                            return;
                        }
                        cb.startPlayback(); // 无论成功/失败都尝试播放
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<StreamResponse>> call, Throwable t) {
                        Log.e(TAG, "getStream onFailure: " + t.getMessage());
                        cb.onStreamDataFailed();
                    }
                });
    }

    /** 重新加载（切换清晰度时调用，重置云直链状态） */
    public void resetForQualitySwitch() {
        cloudDirectUrl = "";
        isStrmFile = false;
        qualityCount = 0;
        qualityLabels = null;
        qualityUrls = null;
    }

    // ========== 画质菜单 ==========

    /** 显示画质/模式切换菜单（右侧抽屉样式） */
    public void showQualityMenu() {
        final SharedPreferences sp = cb.getPrefs();
        java.util.List<SideDrawerHelper.Item> items = new java.util.ArrayList<>();
        if (isStrmFile && qualityCount > 0 && qualityLabels != null) {
            // STRM 文件：只显示画质
            for (int i = 0; i < qualityCount; i++) {
                items.add(new SideDrawerHelper.Item(qualityLabels[i], "", i == qualityIndex));
            }
        } else if (cloudDirectMode && qualityCount > 0 && qualityLabels != null) {
            // 直链模式：画质列表 + 切换代理
            for (int i = 0; i < qualityCount; i++) {
                items.add(new SideDrawerHelper.Item(qualityLabels[i], "", i == qualityIndex));
            }
            items.add(new SideDrawerHelper.Item("切换到代理模式", "", false));
        } else {
            // 代理模式：切换到直链
            items.add(new SideDrawerHelper.Item("切换到直链模式", "", false));
        }

        new SideDrawerHelper((Activity) cb.getContext()).show("播放设置", items,
                null, null, null, null,
                which -> {
                    if (isStrmFile && qualityCount > 0 && qualityLabels != null) {
                        if (which < qualityCount) {
                            qualityIndex = which;
                            sp.edit().putInt("cloud_quality_index", qualityIndex).apply();
                            updateCloudBtnText();
                            Toast.makeText(cb.getContext(), "切换画质：" + qualityLabels[qualityIndex], Toast.LENGTH_SHORT).show();
                            cb.runOnUiThread(() -> reloadPlayback());
                        }
                    } else if (cloudDirectMode && qualityCount > 0 && qualityLabels != null) {
                        if (which < qualityCount) {
                            qualityIndex = which;
                            sp.edit().putInt("cloud_quality_index", qualityIndex)
                                    .putBoolean("cloud_direct_mode", true).apply();
                            updateCloudBtnText();
                            Toast.makeText(cb.getContext(), "切换画质：" + qualityLabels[qualityIndex], Toast.LENGTH_SHORT).show();
                            cb.runOnUiThread(() -> reloadPlayback());
                        } else {
                            cloudDirectMode = false;
                            sp.edit().putBoolean("cloud_direct_mode", false).apply();
                            updateCloudBtnText();
                            Toast.makeText(cb.getContext(), "已切换为代理模式", Toast.LENGTH_SHORT).show();
                            cb.runOnUiThread(() -> reloadPlayback());
                        }
                    } else {
                        cloudDirectMode = true;
                        sp.edit().putBoolean("cloud_direct_mode", true).apply();
                        updateCloudBtnText();
                        Toast.makeText(cb.getContext(), "已切换为直链模式", Toast.LENGTH_SHORT).show();
                        cb.runOnUiThread(() -> reloadPlayback());
                    }
                }, null);
    }

    // ========== UI 按钮 ==========

    public void setCloudBtnVisible(boolean vis) {
        btnCloudMode.setVisibility(vis ? View.VISIBLE : View.GONE);
        cb.onCloudBtnVisibilityChanged(vis);
    }

    public void updateCloudBtnText() {
        String mode = isStrmFile ? "STRM" : (cloudDirectMode ? "直链" : "代理");
        String ql = qualityCount > 0 && qualityIndex < qualityCount && qualityLabels != null
                ? qualityLabels[qualityIndex] : "";
        btnCloudMode.setText(ql.isEmpty() ? mode : mode + "/" + ql);
        btnCloudMode.setTextColor((int)(isStrmFile || cloudDirectMode ? cb.getContext().getColor(R.color.success) : cb.getContext().getColor(R.color.warning)));
    }

    // ========== 音轨/字幕选择 ==========

    /**
     * 显示音轨选择抽屉（轨道来自 mpv track-list）
     *
     * @param activity 承载弹窗的 Activity
     */
    public void showAudioTrackDialog(Activity activity) {
        if (playerView == null || !playerView.isMpvReady()) {
            Toast.makeText(activity, "播放器未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        final java.util.List<CustomMPVView.TrackInfo> tracks = playerView.getTracks("audio");
        if (tracks.isEmpty()) {
            Toast.makeText(activity, "无可用音轨", Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.List<SideDrawerHelper.Item> items = new java.util.ArrayList<>();
        for (int i = 0; i < tracks.size(); i++) {
            items.add(new SideDrawerHelper.Item(buildAudioLabel(tracks.get(i), i), "", tracks.get(i).selected));
        }
        new SideDrawerHelper(activity).show("音频", items,
                null, null, null, null,
                which -> {
                    if (which < 0 || which >= tracks.size()) return;
                    playerView.selectAudioTrack(tracks.get(which).id);
                    lastAudioTrackLabel = items.get(which).title;
                    cb.onTrackChanged();
                    Toast.makeText(activity, "已切换: " + items.get(which).title, Toast.LENGTH_SHORT).show();
                }, null);
    }

    /**
     * 拼装音轨显示标签（mpv 轨道信息为主，stream API 语言字段兜底）
     *
     * @param t   mpv 轨道信息
     * @param idx 列表内序号（0-based）
     * @return 显示文本
     */
    private String buildAudioLabel(CustomMPVView.TrackInfo t, int idx) {
        StringBuilder sb = new StringBuilder();
        sb.append("音轨").append(idx + 1);
        String lang = t.lang;
        if ((lang == null || lang.isEmpty()) && streamAudioTracks != null && idx < streamAudioTracks.size())
            lang = streamAudioTracks.get(idx).language;
        if (lang != null && !lang.isEmpty()) sb.append("  ").append(lang);
        if (t.title != null && !t.title.isEmpty()) sb.append("  ").append(t.title);
        if (t.codec != null && !t.codec.isEmpty()) sb.append("  ").append(FormatUtils.fmtAudioCodec(t.codec));
        if (t.channels > 0)
            sb.append("  ").append(t.channels == 8 ? "7.1" : t.channels == 6 ? "5.1" : t.channels + "ch");
        if (t.sampleRate > 0) sb.append("  ").append(t.sampleRate / 1000).append("kHz");
        return sb.toString();
    }

    /**
     * 显示字幕选择抽屉（mpv 轨道 + 飞牛外挂字幕 + 本地外挂）
     * 外挂字幕通过 subtitle/dl/{guid} 下载后加载进 mpv
     *
     * @param activity 承载弹窗的 Activity
     */
    public void showSubtitleTrackDialog(Activity activity) {
        if (playerView == null || !playerView.isMpvReady()) {
            Toast.makeText(activity, "播放器未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        final java.util.List<CustomMPVView.TrackInfo> tracks = playerView.getTracks("sub");

        // actions 与 items 一一对应：String("close") / TrackInfo / SubtitleStreamInfo
        final java.util.List<Object> actions = new java.util.ArrayList<>();
        java.util.List<SideDrawerHelper.Item> items = new java.util.ArrayList<>();

        boolean anySelected = false;
        for (CustomMPVView.TrackInfo t : tracks) anySelected |= t.selected;

        items.add(new SideDrawerHelper.Item("关闭字幕", "", !anySelected));
        actions.add("close");

        for (int i = 0; i < tracks.size(); i++) {
            CustomMPVView.TrackInfo t = tracks.get(i);
            items.add(new SideDrawerHelper.Item(buildSubtitleLabel(t, i), "", t.selected, t.external));
            actions.add(t);
        }

        // 飞牛影视外挂字幕（媒体库附加字幕文件，mpv 播放原文件时看不到，需下载加载）
        if (streamSubtitleTracks != null) {
            for (StreamResponse.SubtitleStreamInfo s : streamSubtitleTracks) {
                if (s.isExternal != 1) continue; // 内封字幕已在 mpv 轨道列表中
                String label = (s.title != null && !s.title.isEmpty()) ? s.title
                        : (s.language != null && !s.language.isEmpty() ? s.language : "外挂字幕");
                items.add(new SideDrawerHelper.Item(label,
                        s.codecName != null ? s.codecName.toUpperCase() : "外挂", false));
                actions.add(s);
            }
        }

        new SideDrawerHelper(activity).show("字幕", items,
                "调整", cb::showSubtitleStylePanel,
                "＋ 字幕", cb::pickExternalSubtitleFile,
                which -> {
                    if (which < 0 || which >= actions.size()) return;
                    Object a = actions.get(which);
                    if ("close".equals(a)) {
                        playerView.selectSubtitle(-1);
                        lastSubtitleTrackLabel = "关闭字幕";
                        cb.onTrackChanged();
                        Toast.makeText(activity, "已关闭字幕", Toast.LENGTH_SHORT).show();
                    } else if (a instanceof CustomMPVView.TrackInfo) {
                        CustomMPVView.TrackInfo t = (CustomMPVView.TrackInfo) a;
                        playerView.selectSubtitle(t.id);
                        lastSubtitleTrackLabel = items.get(which).title;
                        cb.onTrackChanged();
                        Toast.makeText(activity, "已切换: " + items.get(which).title, Toast.LENGTH_SHORT).show();
                    } else if (a instanceof StreamResponse.SubtitleStreamInfo) {
                        downloadExternalSubtitle((StreamResponse.SubtitleStreamInfo) a, items.get(which).title, false);
                    }
                },
                which -> {
                    // 删除外挂字幕轨（仅 mpv 外挂轨可删）
                    if (which >= 0 && which < actions.size() && actions.get(which) instanceof CustomMPVView.TrackInfo) {
                        CustomMPVView.TrackInfo t = (CustomMPVView.TrackInfo) actions.get(which);
                        MPVLib.command(new String[]{"sub-remove", String.valueOf(t.id)});
                        Toast.makeText(activity, "已移除外挂字幕", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    /**
     * 从飞牛服务器下载外挂字幕并加载进 mpv
     *
     * @param s           字幕流信息（含 guid）
     * @param displayName 显示名（用于缓存文件命名与提示）
     * @param auto        true=播放时自动加载（失败静默）；false=用户手动选择（全程提示）
     */
    public void downloadExternalSubtitle(final StreamResponse.SubtitleStreamInfo s,
                                         final String displayName, final boolean auto) {
        if (s == null || s.guid == null || s.guid.isEmpty()) {
            if (!auto) cb.runOnUiThread(() -> Toast.makeText(cb.getContext(), "字幕下载失败", Toast.LENGTH_SHORT).show());
            return;
        }
        final String name = (displayName != null && !displayName.isEmpty() ? displayName : "subtitle")
                + extForCodec(s.codecName);
        if (!auto) cb.runOnUiThread(() -> Toast.makeText(cb.getContext(), "正在下载字幕…", Toast.LENGTH_SHORT).show());
        cb.getApiManager().getApi().downloadSubtitle(s.guid)
                .enqueue(new retrofit2.Callback<okhttp3.ResponseBody>() {
                    @Override
                    public void onResponse(retrofit2.Call<okhttp3.ResponseBody> call,
                                           retrofit2.Response<okhttp3.ResponseBody> response) {
                        boolean ok = false;
                        if (response.isSuccessful() && response.body() != null) {
                            try {
                                byte[] bytes = response.body().bytes();
                                // 错误信封是 JSON（以 { 开头），真正的字幕（ASS/SSA/SRT）不会是
                                boolean isJsonError = bytes.length > 0 && bytes[0] == '{';
                                if (bytes.length > 0 && !isJsonError) {
                                    ok = cb.loadExternalSubtitleBytes(bytes, name);
                                } else {
                                    Log.e(TAG, "字幕下载返回非字幕内容（长度=" + bytes.length + "）");
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "字幕下载读取失败", e);
                            }
                        } else {
                            Log.e(TAG, "字幕下载 HTTP " + response.code() + " guid=" + s.guid);
                        }
                        final boolean success = ok;
                        if (auto) {
                            if (success) cb.runOnUiThread(() -> Toast.makeText(cb.getContext(),
                                    "已自动加载字幕: " + displayName, Toast.LENGTH_SHORT).show());
                        } else {
                            cb.runOnUiThread(() -> Toast.makeText(cb.getContext(),
                                    success ? "已加载字幕: " + displayName : "字幕下载失败",
                                    Toast.LENGTH_SHORT).show());
                        }
                    }

                    @Override
                    public void onFailure(retrofit2.Call<okhttp3.ResponseBody> call, Throwable t) {
                        Log.e(TAG, "字幕下载请求失败: " + t.getMessage());
                        if (!auto) cb.runOnUiThread(() -> Toast.makeText(cb.getContext(), "字幕下载失败", Toast.LENGTH_SHORT).show());
                    }
                });
    }

    /** 字幕编码名 → 缓存文件后缀（供 mpv 按格式解析） */
    private static String extForCodec(String codec) {
        if (codec == null) return "";
        switch (codec.toLowerCase()) {
            case "ass": return ".ass";
            case "ssa": return ".ssa";
            case "srt":
            case "subrip": return ".srt";
            case "vtt":
            case "webvtt": return ".vtt";
            case "sub": return ".sub";
            default: return "";
        }
    }

    /**
     * 拼装字幕轨显示标签（mpv 轨道信息为主，stream API 语言字段兜底）
     *
     * @param t   mpv 轨道信息
     * @param idx 列表内序号（0-based）
     * @return 显示文本
     */
    private String buildSubtitleLabel(CustomMPVView.TrackInfo t, int idx) {
        StringBuilder sb = new StringBuilder();
        String lang = t.lang;
        if ((lang == null || lang.isEmpty()) && streamSubtitleTracks != null && idx < streamSubtitleTracks.size())
            lang = streamSubtitleTracks.get(idx).language;
        sb.append((lang != null && !lang.isEmpty()) ? lang : ("字幕" + (idx + 1)));
        if (t.title != null && !t.title.isEmpty()) sb.append("  ").append(t.title);
        if (t.codec != null && !t.codec.isEmpty()) sb.append("  ").append(t.codec.toUpperCase());
        if (t.external) sb.append("  [外挂]");
        return sb.toString();
    }

    // ========== 内部 ==========

    public void reloadPlayback() {
        resetForQualitySwitch();
        cb.reloadPlayback(); // triggers loadPlayInfo again
    }
}




