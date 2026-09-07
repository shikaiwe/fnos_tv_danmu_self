package com.fntv.app;

/**
 * mpv 时间源接口
 * 为 DanmuManager 等非播放器组件提供播放进度，解耦弹幕系统与具体播放器实现
 */
public interface MPVTimeSource {
    /**
     * 获取当前播放位置（毫秒）
     *
     * @return 当前位置毫秒数
     */
    long getCurrentPositionMs();

    /**
     * 获取视频总时长（毫秒）
     *
     * @return 总时长毫秒数，未知时返回 0
     */
    long getDurationMs();

    /**
     * 是否正在播放（未暂停）
     *
     * @return true=播放中，false=暂停或停止
     */
    boolean isPlaying();

    /**
     * 获取当前播放速度倍率
     *
     * @return 速度倍率，默认 1.0
     */
    float getPlaybackSpeed();
}
