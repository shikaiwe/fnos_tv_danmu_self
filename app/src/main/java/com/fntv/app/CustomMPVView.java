package com.fntv.app;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.File;

import dev.jdtech.mpv.MPVLib;

/**
 * mpv 播放器 Surface 封装
 * 继承 SurfaceView，负责 mpv 实例生命周期、Surface 绑定与播控 API 封装
 *
 * libmpv 初始化顺序（强制）：
 * MPVLib.create(ctx) -> MPVLib.setOptionString(...) -> MPVLib.init() -> MPVLib.attachSurface(...)
 */
public class CustomMPVView extends SurfaceView implements SurfaceHolder.Callback {

    private static final String TAG = "CustomMPVView";

    /** mpv 实例是否已完成 create + init */
    private boolean mpvInitialized = false;
    /** Surface 是否可用 */
    private boolean surfaceReady = false;
    /** 当前是否为硬件解码 */
    private boolean hwDecodeEnabled = true;

    public CustomMPVView(Context context) {
        super(context);
        init();
    }

    public CustomMPVView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CustomMPVView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    /**
     * 初始化：注册 SurfaceHolder 回调
     */
    private void init() {
        getHolder().addCallback(this);
    }

    // ==================== mpv 实例生命周期 ====================

    /**
     * 创建并初始化 mpv 实例
     * 必须在 loadFile 之前调用；可重复调用（已初始化时直接返回）
     *
     * @param context  应用 Context
     * @param hwDecode 是否启用硬件解码
     * @return 是否初始化成功
     */
    public boolean initializeMpv(Context context, boolean hwDecode) {
        if (mpvInitialized) {
            Log.d(TAG, "mpv already initialized");
            return true;
        }
        try {
            this.hwDecodeEnabled = hwDecode;
            MPVLib.create(context.getApplicationContext());
            // 本构建的 libmpv 未编译 fontconfig（enabled features 无 fontconfig），
            // libass 在 Android 上没有任何系统字体来源，必须提供内置字体目录，
            // 否则所有字幕（含 SRT）因找不到字形渲染为空白。
            setupBundledFonts(context.getApplicationContext());
            applyOptions(hwDecode);
            MPVLib.init();
            observeProperties();
            mpvInitialized = true;
            Log.d(TAG, "mpv initialized, hwDecode=" + hwDecode);
            if (surfaceReady) {
                MPVLib.attachSurface(getHolder().getSurface());
            }
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "Failed to initialize mpv", e);
            mpvInitialized = false;
            return false;
        }
    }

    /**
     * 释放 mpv 实例
     * 解绑 Surface 并销毁底层上下文
     */
    public void destroyMpv() {
        if (!mpvInitialized) return;
        try {
            MPVLib.detachSurface();
        } catch (Throwable ignored) {
        }
        try {
            MPVLib.destroy();
        } catch (Throwable e) {
            Log.e(TAG, "destroy failed", e);
        }
        mpvInitialized = false;
        Log.d(TAG, "mpv destroyed");
    }

    /**
     * mpv 实例是否可用
     */
    public boolean isMpvReady() {
        return mpvInitialized;
    }

    /**
     * 当前是否为硬件解码
     */
    public boolean isHwDecodeEnabled() {
        return hwDecodeEnabled;
    }

    /**
     * 运行时切换硬/软解码（无需重建播放器）
     *
     * @param hwDecode true=硬解，false=软解
     */
    public void setHwDecode(boolean hwDecode) {
        this.hwDecodeEnabled = hwDecode;
        if (!mpvInitialized) return;
        MPVLib.setPropertyString("hwdec", hwDecode ? "mediacodec-copy" : "no");
        Log.d(TAG, "hwdec switched to " + (hwDecode ? "mediacodec-copy" : "no"));
    }

    /**
     * 解压 APK 内置字体到应用私有目录并通过 mpv "fonts-dir" 选项交给 libass。
     * libass 会把该目录下的字体注册为回退字体（不依赖 fontconfig），
     * 保证所有设备上无内嵌字体的字幕（含 SRT）用同一字体渲染。
     */
    private void setupBundledFonts(Context ctx) {
        try {
            File fontsDir = new File(ctx.getFilesDir(), "mpvfonts");
            if (!fontsDir.exists()) fontsDir.mkdirs();
            String[] bundled = {"NotoSansSC-Regular.otf"};
            for (String name : bundled) {
                File out = new File(fontsDir, name);
                long expected = 0;
                try (java.io.InputStream probe = ctx.getAssets().open("fonts/" + name)) {
                    expected = probe.available();
                }
                if (!out.exists() || out.length() != expected) {
                    try (java.io.InputStream in = ctx.getAssets().open("fonts/" + name);
                         java.io.FileOutputStream outStream = new java.io.FileOutputStream(out)) {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) != -1) outStream.write(buf, 0, n);
                    }
                }
            }
            MPVLib.setOptionString("sub-fonts-dir", fontsDir.getAbsolutePath());
            MPVLib.setOptionString("osd-fonts-dir", fontsDir.getAbsolutePath());
            Log.d(TAG, "sub-fonts-dir=" + fontsDir.getAbsolutePath());
        } catch (Throwable e) {
            Log.w(TAG, "内置字体准备失败，字幕可能无法渲染", e);
        }
    }

    /**
     * 配置 mpv 启动选项
     * 必须在 MPVLib.init() 之前调用（部分选项为 init-only）
     *
     * @param hwDecode 是否启用硬件解码
     */
    private void applyOptions(boolean hwDecode) {
        // 保持 mpv 空闲存活，避免播放结束后上下文自动关闭
        MPVLib.setOptionString("idle", "yes");
        MPVLib.setOptionString("force-window", "no");
        // GPU 渲染
        MPVLib.setOptionString("vo", "gpu");
        MPVLib.setOptionString("gpu-context", "android");
        MPVLib.setOptionString("opengl-es", "yes");
        // 解码方式
        MPVLib.setOptionString("hwdec", hwDecode ? "mediacodec-copy" : "no");
        MPVLib.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1");
        // 音频输出
        MPVLib.setOptionString("ao", "audiotrack,opensles");
        // 帧率同步
        MPVLib.setOptionString("video-sync", "audio");
        // 网络流缓存
        MPVLib.setOptionString("cache", "yes");
        MPVLib.setOptionString("cache-secs", "300");
        MPVLib.setOptionString("demuxer-max-bytes", "32MiB");
        MPVLib.setOptionString("demuxer-readahead-secs", "20");
        // 字幕（libass 原生渲染；回退字体由 sub-fonts-dir 提供内置字体，见 setupBundledFonts）。
        // SRT 转换与 OSD 用 sub-font/osd-font 指定族名，必须与打包字体的内部族名一致，
        // libass 不会为未知字体族做任意回退。
        String fallbackFont = BilingualAssProcessor.FALLBACK_FONT_FAMILY;
        MPVLib.setOptionString("sub-font", fallbackFont);
        MPVLib.setOptionString("osd-font", fallbackFont);
        MPVLib.setOptionString("sub-auto", "fuzzy");
        MPVLib.setOptionString("sub-scale-with-window", "yes");
        // GPU 缩放质量
        MPVLib.setOptionString("scale", "lanczos");
        MPVLib.setOptionString("cscale", "lanczos");
        // 保持播放结束后不退出
        MPVLib.setOptionString("keep-open", "yes");
    }

    /**
     * 注册需要监听变化的 mpv 属性
     * 属性变化会通过 MPVLib.EventObserver.eventProperty 回调
     */
    private void observeProperties() {
        MPVLib.observeProperty("time-pos", MPVLib.MPV_FORMAT_DOUBLE);
        MPVLib.observeProperty("duration", MPVLib.MPV_FORMAT_DOUBLE);
        MPVLib.observeProperty("pause", MPVLib.MPV_FORMAT_FLAG);
        MPVLib.observeProperty("paused-for-cache", MPVLib.MPV_FORMAT_FLAG);
        MPVLib.observeProperty("seeking", MPVLib.MPV_FORMAT_FLAG);
        MPVLib.observeProperty("eof-reached", MPVLib.MPV_FORMAT_FLAG);
        MPVLib.observeProperty("speed", MPVLib.MPV_FORMAT_DOUBLE);
        MPVLib.observeProperty("video-params/w", MPVLib.MPV_FORMAT_INT64);
        MPVLib.observeProperty("video-params/h", MPVLib.MPV_FORMAT_INT64);
        MPVLib.observeProperty("track-list/count", MPVLib.MPV_FORMAT_INT64);
    }

    // ==================== Surface 回调 ====================

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(TAG, "surfaceCreated");
        surfaceReady = true;
        if (mpvInitialized) {
            MPVLib.attachSurface(holder.getSurface());
            // Surface 重建（进出画中画、后台返回前台）后必须恢复视频输出，否则只有声音没有画面
            MPVLib.setOptionString("force-window", "yes");
            MPVLib.setPropertyString("vo", "gpu");
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "surfaceChanged: " + width + "x" + height);
        if (mpvInitialized) {
            MPVLib.setPropertyString("android-surface-size", width + "x" + height);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(TAG, "surfaceDestroyed");
        surfaceReady = false;
        if (mpvInitialized) {
            // 先摘掉视频输出再解绑 Surface，避免 mpv 渲染线程持有已销毁的 Surface
            MPVLib.setPropertyString("vo", "null");
            MPVLib.setOptionString("force-window", "no");
            MPVLib.detachSurface();
        }
    }

    // ==================== 播放控制 ====================

    /**
     * 加载并播放媒体
     *
     * @param path        视频文件路径或网络 URL
     * @param resumePosMs 起播位置（毫秒），0 表示从头播放
     */
    public void loadFile(String path, long resumePosMs) {
        if (!mpvInitialized) {
            Log.w(TAG, "loadFile ignored: mpv not initialized");
            return;
        }
        Log.d(TAG, "loadFile: " + path + " resumePosMs=" + resumePosMs);
        if (resumePosMs > 0) {
            // loadfile 命令签名：loadfile <url> [<flags> [<index> [<options>]]]
            // flags 之后必须有一个 index（整数）占位，才能把 start= 传到 options
            long startSec = resumePosMs / 1000;
            MPVLib.command(new String[]{"loadfile", path, "replace", "0", "start=" + startSec});
        } else {
            MPVLib.command(new String[]{"loadfile", path, "replace", "0"});
        }
    }

    /**
     * 为下一次网络请求设置自定义 HTTP 头
     *
     * @param headers 形如 "Key: Value" 的头列表，逗号分隔
     */
    public void setHttpHeaders(String headers) {
        if (!mpvInitialized || headers == null || headers.isEmpty()) return;
        MPVLib.setOptionString("http-header-fields", headers);
    }

    /**
     * 停止播放并卸载当前文件
     */
    public void stop() {
        if (!mpvInitialized) return;
        MPVLib.command(new String[]{"stop"});
    }

    /**
     * 加载外挂字幕并立即选中
     *
     * @param subtitlePath 字幕文件绝对路径或 URL
     */
    public void addSubtitle(String subtitlePath) {
        if (!mpvInitialized) return;
        Log.d(TAG, "addSubtitle: " + subtitlePath);
        MPVLib.command(new String[]{"sub-add", subtitlePath, "select"});
    }

    /**
     * 移除所有外挂字幕轨
     */
    public void clearSubtitles() {
        if (!mpvInitialized) return;
        int trackCount = getPropertyIntSafe("track-list/count", 0);
        for (int i = trackCount - 1; i >= 0; i--) {
            String type = MPVLib.getPropertyString("track-list/" + i + "/type");
            Boolean external = MPVLib.getPropertyBoolean("track-list/" + i + "/external");
            if ("sub".equals(type) && external != null && external) {
                int id = getPropertyIntSafe("track-list/" + i + "/id", -1);
                if (id >= 0) {
                    MPVLib.command(new String[]{"sub-remove", String.valueOf(id)});
                }
            }
        }
    }

    /**
     * 切换字幕轨道
     *
     * @param trackId mpv 字幕轨道 ID，负数表示关闭字幕
     */
    public void selectSubtitle(int trackId) {
        if (!mpvInitialized) return;
        if (trackId < 0) {
            MPVLib.setPropertyString("sid", "no");
        } else {
            MPVLib.setPropertyInt("sid", trackId);
        }
    }

    /**
     * 切换音频轨道
     *
     * @param trackId mpv 音频轨道 ID，负数表示静音轨关闭
     */
    public void selectAudioTrack(int trackId) {
        if (!mpvInitialized) return;
        if (trackId < 0) {
            MPVLib.setPropertyString("aid", "no");
        } else {
            MPVLib.setPropertyInt("aid", trackId);
        }
    }

    /**
     * 设置播放速度
     *
     * @param speed 速度倍率（0.25 ~ 4.0）
     */
    public void setPlaybackSpeed(float speed) {
        if (!mpvInitialized) return;
        MPVLib.setPropertyDouble("speed", (double) speed);
    }

    /**
     * 设置音量
     *
     * @param volume 音量 0~100
     */
    public void setVolume(int volume) {
        if (!mpvInitialized) return;
        MPVLib.setPropertyInt("volume", volume);
    }

    /**
     * 设置画面亮度
     *
     * @param brightness 亮度 -100 ~ 100（0 为原始亮度）
     */
    public void setBrightness(int brightness) {
        if (!mpvInitialized) return;
        MPVLib.setPropertyInt("brightness", Math.max(-100, Math.min(100, brightness)));
    }

    /**
     * 跳转到绝对位置
     *
     * @param posMs 目标位置（毫秒）
     */
    public void seekTo(long posMs) {
        if (!mpvInitialized) return;
        MPVLib.command(new String[]{"seek", String.valueOf(posMs / 1000.0), "absolute"});
    }

    /**
     * 相对跳转
     *
     * @param offsetMs 偏移量（毫秒，正=快进，负=快退）
     */
    public void seekRel(long offsetMs) {
        if (!mpvInitialized) return;
        MPVLib.command(new String[]{"seek", String.valueOf(offsetMs / 1000.0), "relative"});
    }

    /**
     * 播放/暂停切换
     */
    public void togglePause() {
        if (!mpvInitialized) return;
        Boolean paused = MPVLib.getPropertyBoolean("pause");
        MPVLib.setPropertyBoolean("pause", paused == null || !paused);
    }

    /**
     * 设置暂停状态
     *
     * @param paused true=暂停，false=播放
     */
    public void setPause(boolean paused) {
        if (!mpvInitialized) return;
        MPVLib.setPropertyBoolean("pause", paused);
    }

    // ==================== 状态查询 ====================

    /**
     * 获取当前播放位置（毫秒）
     */
    public long getCurrentPosition() {
        if (!mpvInitialized) return 0;
        Double pos = MPVLib.getPropertyDouble("time-pos");
        return pos == null ? 0 : Math.round(pos * 1000);
    }

    /**
     * 获取视频总时长（毫秒），未知返回 0
     */
    public long getDuration() {
        if (!mpvInitialized) return 0;
        Double dur = MPVLib.getPropertyDouble("duration");
        return dur == null ? 0 : Math.round(dur * 1000);
    }

    /**
     * 是否正在播放（非暂停）
     */
    public boolean isPlaying() {
        if (!mpvInitialized) return false;
        Boolean paused = MPVLib.getPropertyBoolean("pause");
        return paused != null && !paused;
    }

    /**
     * 获取视频宽度，未知返回 0
     */
    public int getVideoWidth() {
        return getPropertyIntSafe("video-params/w", 0);
    }

    /**
     * 获取视频高度，未知返回 0
     */
    public int getVideoHeight() {
        return getPropertyIntSafe("video-params/h", 0);
    }

    /**
     * 获取视频帧率，未知返回 0
     */
    public float getVideoFps() {
        if (!mpvInitialized) return 0f;
        Double fps = MPVLib.getPropertyDouble("container-fps");
        if (fps == null) fps = MPVLib.getPropertyDouble("estimated-vf-fps");
        return fps == null ? 0f : fps.floatValue();
    }

    /**
     * 获取当前播放速度倍率
     */
    public float getPlaybackSpeed() {
        if (!mpvInitialized) return 1.0f;
        Double speed = MPVLib.getPropertyDouble("speed");
        return speed == null ? 1.0f : speed.floatValue();
    }

    /**
     * 是否处于缓冲等待状态
     */
    public boolean isBuffering() {
        if (!mpvInitialized) return false;
        Boolean cacheWait = MPVLib.getPropertyBoolean("paused-for-cache");
        if (cacheWait != null && cacheWait) return true;
        Boolean seeking = MPVLib.getPropertyBoolean("seeking");
        return seeking != null && seeking;
    }

    /**
     * 是否已播放到结尾
     */
    public boolean isAtEnd() {
        if (!mpvInitialized) return false;
        Boolean eof = MPVLib.getPropertyBoolean("eof-reached");
        return eof != null && eof;
    }

    /**
     * 是否存在视频轨
     */
    public boolean hasVideoTrack() {
        if (!mpvInitialized) return false;
        return getPropertyIntSafe("video-params/w", 0) > 0;
    }

    /**
     * 获取视频编码格式名（如 h264 / hevc）
     */
    public String getVideoCodec() {
        if (!mpvInitialized) return null;
        return MPVLib.getPropertyString("video-codec");
    }

    /**
     * 获取音频编码格式名（如 aac / eac3）
     */
    public String getAudioCodec() {
        if (!mpvInitialized) return null;
        return MPVLib.getPropertyString("audio-codec-name");
    }

    /**
     * 获取当前使用的硬件解码器名称，软解时返回 "no"
     */
    public String getCurrentHwdec() {
        if (!mpvInitialized) return null;
        return MPVLib.getPropertyString("hwdec-current");
    }

    /**
     * 判断当前视频是否为 HDR
     * 依据 mpv 上报的色彩传输函数（pq / hlg 判定为 HDR）
     */
    public boolean isHdrVideo() {
        if (!mpvInitialized) return false;
        String gamma = MPVLib.getPropertyString("video-params/gamma");
        if (gamma == null) return false;
        return gamma.contains("pq") || gamma.contains("hlg") || gamma.contains("smpte");
    }

    /**
     * 设置画面比例模式
     *
     * @param mode 0=适应（保持比例，留黑边）1=拉伸（铺满，形变）2=缩放（保持比例，裁切铺满）3=强制16:9
     */
    public void setAspectRatioMode(int mode) {
        if (!mpvInitialized) return;
        switch (mode) {
            case 1: // 拉伸：不保持宽高比，直接铺满窗口
                MPVLib.setPropertyString("keepaspect", "no");
                MPVLib.setPropertyDouble("panscan", 0.0);
                MPVLib.setPropertyString("video-aspect-override", "no");
                break;
            case 2: // 缩放：保持宽高比并裁切，铺满窗口
                MPVLib.setPropertyString("keepaspect", "yes");
                MPVLib.setPropertyDouble("panscan", 1.0);
                MPVLib.setPropertyString("video-aspect-override", "no");
                break;
            case 3: // 强制 16:9
                MPVLib.setPropertyString("keepaspect", "yes");
                MPVLib.setPropertyDouble("panscan", 0.0);
                MPVLib.setPropertyString("video-aspect-override", "16:9");
                break;
            default: // 适应
                MPVLib.setPropertyString("keepaspect", "yes");
                MPVLib.setPropertyDouble("panscan", 0.0);
                MPVLib.setPropertyString("video-aspect-override", "no");
                break;
        }
    }

    /**
     * 设置视频渲染边距（占窗口宽/高的比例），mpv 会在扣掉边距后的区域内铺视频。
     * 用于把画面限制在"屏幕减去刘海/导航栏 inset"的可视区内，使默认"适应"模式下
     * 16:9 视频在全面屏上左右不再出现黑边，同时不占用刘海和手势导航条区域。
     *
     * @param left   左边距比例（0~1）
     * @param top    上边距比例（0~1）
     * @param right  右边距比例（0~1）
     * @param bottom 下边距比例（0~1）
     */
    public void setVideoMarginRatio(double left, double top, double right, double bottom) {
        if (!mpvInitialized) return;
        MPVLib.setPropertyDouble("video-margin-ratio-left", left);
        MPVLib.setPropertyDouble("video-margin-ratio-top", top);
        MPVLib.setPropertyDouble("video-margin-ratio-right", right);
        MPVLib.setPropertyDouble("video-margin-ratio-bottom", bottom);
    }

    /** mpv track-list 中的一条轨道信息 */
    public static class TrackInfo {
        /** mpv 轨道 ID（写入 aid / sid 时使用） */
        public int id;
        /** 轨道类型：video / audio / sub */
        public String type;
        /** 轨道标题 */
        public String title;
        /** 语言标记 */
        public String lang;
        /** 编码名 */
        public String codec;
        /** 声道数（仅音轨有效） */
        public int channels;
        /** 采样率（仅音轨有效） */
        public int sampleRate;
        /** 是否为当前选中轨道 */
        public boolean selected;
        /** 是否为外挂轨道 */
        public boolean external;
        /** 是否带默认标记（mkv default flag） */
        public boolean defaultTrack;
        /** 是否带强制标记（mkv forced flag，通常为听障/注释轨或强制字幕） */
        public boolean forced;
    }

    /**
     * 枚举 mpv 轨道列表
     *
     * @param type 轨道类型过滤（"video" / "audio" / "sub"），传 null 返回全部
     * @return 匹配的轨道列表；mpv 未就绪时返回空列表
     */
    public java.util.List<TrackInfo> getTracks(String type) {
        java.util.List<TrackInfo> result = new java.util.ArrayList<>();
        if (!mpvInitialized) return result;
        int count = getPropertyIntSafe("track-list/count", 0);
        for (int i = 0; i < count; i++) {
            String prefix = "track-list/" + i + "/";
            String t = MPVLib.getPropertyString(prefix + "type");
            if (type != null && !type.equals(t)) continue;
            TrackInfo info = new TrackInfo();
            info.id = getPropertyIntSafe(prefix + "id", -1);
            info.type = t;
            info.title = MPVLib.getPropertyString(prefix + "title");
            info.lang = MPVLib.getPropertyString(prefix + "lang");
            info.codec = MPVLib.getPropertyString(prefix + "codec");
            info.channels = getPropertyIntSafe(prefix + "demux-channel-count", 0);
            info.sampleRate = getPropertyIntSafe(prefix + "demux-samplerate", 0);
            Boolean sel = MPVLib.getPropertyBoolean(prefix + "selected");
            info.selected = sel != null && sel;
            Boolean ext = MPVLib.getPropertyBoolean(prefix + "external");
            info.external = ext != null && ext;
            Boolean def = MPVLib.getPropertyBoolean(prefix + "default");
            info.defaultTrack = def != null && def;
            Boolean forced = MPVLib.getPropertyBoolean(prefix + "forced");
            info.forced = forced != null && forced;
            if (info.id >= 0) result.add(info);
        }
        return result;
    }

    /**
     * 空安全读取整型属性
     *
     * @param name     属性名
     * @param fallback 读取失败时的默认值
     * @return 属性值或默认值
     */
    public int getPropertyIntSafe(String name, int fallback) {
        if (!mpvInitialized) return fallback;
        try {
            Integer v = MPVLib.getPropertyInt(name);
            return v == null ? fallback : v;
        } catch (Throwable e) {
            return fallback;
        }
    }
}
