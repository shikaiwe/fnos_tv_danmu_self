package com.fntv.app;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import dev.jdtech.mpv.MPVLib;

/**
 * mpv 事件监听器
 * 实现 MPVLib.EventObserver，将底层事件与属性变化桥接到上层业务逻辑
 *
 * 回调线程说明：所有回调均来自 mpv 内部线程，需通过 mainHandler 投递到主线程
 *
 * 依赖的属性监听（由 CustomMPVView.observeProperties() 注册）：
 * - time-pos / duration     进度更新
 * - pause                   播放暂停状态
 * - paused-for-cache        缓冲状态
 * - seeking                 seek 中（视为缓冲）
 * - eof-reached             播放结束
 * - video-params/w, /h      视频分辨率变化
 */
public class MPVEventObserver implements MPVLib.EventObserver {

    private static final String TAG = "MPVEventObserver";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 播放状态监听接口 */
    public interface PlaybackListener {
        void onPlaybackStart();
        void onPlaybackPause(boolean paused);
        void onPlaybackEnd();
        void onBufferingChange(boolean buffering);
        void onVideoParamsChanged(int w, int h, float fps);
    }

    /** 进度更新监听接口 */
    public interface ProgressListener {
        void onProgressUpdate(long currentPositionMs, long durationMs);
    }

    private PlaybackListener playbackListener;
    private ProgressListener progressListener;

    // ===== 属性缓存：用于合并进度回调、去重状态变化 =====
    private long currentPositionMs = 0;
    private long durationMs = 0;
    private boolean pausedForCache = false;
    private boolean seeking = false;
    private boolean lastBuffering = false;
    private int videoWidth = 0;
    private int videoHeight = 0;

    /**
     * 设置播放状态监听
     */
    public void setPlaybackListener(PlaybackListener listener) {
        this.playbackListener = listener;
    }

    /**
     * 设置进度更新监听
     */
    public void setProgressListener(ProgressListener listener) {
        this.progressListener = listener;
    }

    // ==================== MPVLib.EventObserver 实现 ====================

    /**
     * mpv 事件回调（无值属性变化，如属性变为不可用）
     *
     * @param property 属性名
     */
    @Override
    public void eventProperty(String property) {
        // 属性值不可用（如未加载文件时的 time-pos），无需处理
    }

    /**
     * 整型属性变化回调
     *
     * @param property 属性名
     * @param value    整型值
     */
    @Override
    public void eventProperty(String property, long value) {
        switch (property) {
            case "video-params/w":
                videoWidth = (int) value;
                notifyVideoParams();
                break;
            case "video-params/h":
                videoHeight = (int) value;
                notifyVideoParams();
                break;
            default:
                break;
        }
    }

    /**
     * 浮点属性变化回调
     *
     * @param property 属性名
     * @param value    浮点值
     */
    @Override
    public void eventProperty(String property, double value) {
        switch (property) {
            case "time-pos":
                currentPositionMs = Math.round(value * 1000);
                notifyProgress();
                break;
            case "duration":
                durationMs = Math.round(value * 1000);
                notifyProgress();
                break;
            default:
                break;
        }
    }

    /**
     * 布尔属性变化回调
     *
     * @param property 属性名
     * @param value    布尔值
     */
    @Override
    public void eventProperty(String property, boolean value) {
        switch (property) {
            case "pause":
                mainHandler.post(() -> {
                    if (playbackListener != null) playbackListener.onPlaybackPause(value);
                });
                break;
            case "paused-for-cache":
                pausedForCache = value;
                notifyBuffering();
                break;
            case "seeking":
                seeking = value;
                notifyBuffering();
                break;
            case "eof-reached":
                if (value) {
                    mainHandler.post(() -> {
                        if (playbackListener != null) playbackListener.onPlaybackEnd();
                    });
                }
                break;
            default:
                break;
        }
    }

    /**
     * 字符串属性变化回调
     *
     * @param property 属性名
     * @param value    字符串值
     */
    @Override
    public void eventProperty(String property, String value) {
        // 当前无需监听字符串类型属性
    }

    /**
     * mpv 事件回调入口
     *
     * @param eventId mpv 事件 ID（MPVLib.MPV_EVENT_* 常量）
     */
    @Override
    public void event(int eventId) {
        switch (eventId) {
            case MPVLib.MPV_EVENT_FILE_LOADED:
                mainHandler.post(() -> {
                    if (playbackListener != null) playbackListener.onPlaybackStart();
                });
                break;

            case MPVLib.MPV_EVENT_START_FILE:
                // 开始加载文件，进入缓冲态
                lastBuffering = true;
                mainHandler.post(() -> {
                    if (playbackListener != null) playbackListener.onBufferingChange(true);
                });
                break;

            case MPVLib.MPV_EVENT_PLAYBACK_RESTART:
                // seek 完成或缓冲结束后恢复渲染，退出缓冲态
                lastBuffering = false;
                mainHandler.post(() -> {
                    if (playbackListener != null) playbackListener.onBufferingChange(false);
                });
                break;

            case MPVLib.MPV_EVENT_END_FILE:
                mainHandler.post(() -> {
                    if (playbackListener != null) playbackListener.onPlaybackEnd();
                });
                break;

            case MPVLib.MPV_EVENT_SEEK:
                Log.d(TAG, "MPV_EVENT_SEEK");
                break;

            case MPVLib.MPV_EVENT_SHUTDOWN:
                Log.d(TAG, "MPV_EVENT_SHUTDOWN");
                break;

            default:
                break;
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 投递进度更新（time-pos 与 duration 合并上报）
     */
    private void notifyProgress() {
        if (progressListener == null) return;
        final long pos = currentPositionMs;
        final long dur = durationMs;
        mainHandler.post(() -> {
            if (progressListener != null) progressListener.onProgressUpdate(pos, dur);
        });
    }

    /**
     * 投递缓冲状态变化（paused-for-cache 与 seeking 取或，状态未变则不上报）
     */
    private void notifyBuffering() {
        final boolean buffering = pausedForCache || seeking;
        if (buffering == lastBuffering) return;
        lastBuffering = buffering;
        mainHandler.post(() -> {
            if (playbackListener != null) playbackListener.onBufferingChange(buffering);
        });
    }

    /**
     * 投递视频参数变化（宽高齐备后才上报）
     */
    private void notifyVideoParams() {
        if (videoWidth <= 0 || videoHeight <= 0) return;
        final int w = videoWidth;
        final int h = videoHeight;
        Double fpsValue = MPVLib.getPropertyDouble("container-fps");
        final float fps = fpsValue == null ? 0f : fpsValue.floatValue();
        mainHandler.post(() -> {
            if (playbackListener != null) playbackListener.onVideoParamsChanged(w, h, fps);
        });
    }

    /**
     * 获取缓存的当前播放位置（毫秒）
     */
    public long getCurrentPositionMs() {
        return currentPositionMs;
    }

    /**
     * 获取缓存的视频总时长（毫秒）
     */
    public long getDurationMs() {
        return durationMs;
    }
}
