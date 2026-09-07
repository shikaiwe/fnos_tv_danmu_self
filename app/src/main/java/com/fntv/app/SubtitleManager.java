package com.fntv.app;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import dev.jdtech.mpv.MPVLib;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * mpv ASS/SRT 字幕管理器
 * 负责外挂字幕加载、轨道切换、样式调整
 * mpv 原生支持 ASS/SSA 完整样式渲染（包括字体、颜色、动画）
 */
public class SubtitleManager {

    private static final String TAG = "SubtitleManager";
    private static final String SUBTITLE_CACHE_DIR = "subtitles";

    private String lastAddedPath;

    // ===== ASS 样式自定义参数 =====
    private float fontSizeScale = 1.0f;   // 字号缩放
    private int verticalOffset = 0;        // 垂直偏移（正值上移）

    /**
     * 从 URI 添加外挂字幕
     * 支持 file:// 和 content:// 两种 URI scheme
     *
     * @param context Android Context
     * @param subtitleUri 字幕文件 URI
     * @return 是否添加成功
     */
    public boolean addExternalSubtitle(Context context, Uri subtitleUri) {
        String path = resolveSubtitlePath(context, subtitleUri);
        if (path == null) {
            Log.w(TAG, "Cannot resolve subtitle path from URI: " + subtitleUri);
            return false;
        }
        return addSubtitleFromFile(path);
    }

    /**
     * 从文件路径直接添加外挂字幕
     *
     * @param filePath 字幕文件绝对路径
     * @return 是否添加成功
     */
    public boolean addSubtitleFromFile(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                Log.w(TAG, "Subtitle file not found: " + filePath);
                return false;
            }
            int before = getSubtitleTrackCount();
            // sub-add <path> [select|insert-next|append] [title]
            // "select" 标志：加载后自动选中并显示
            MPVLib.command(new String[]{"sub-add", filePath, "select", "外挂字幕"});
            int after = getSubtitleTrackCount();
            Integer sid = MPVLib.getPropertyInt("sid");
            Log.d(TAG, "Added external subtitle: " + filePath
                    + " 轨道数 " + before + "->" + after + " sid=" + sid);
            if (after <= before) {
                // mpv 的 sub-add 是静默失败型命令：文件无法解析时轨道数不会增加
                Log.e(TAG, "mpv 未能解析该字幕文件（轨道数未增加）: " + filePath);
                return false;
            }
            // 确保字幕可见（防止此前的显示开关残留为关）
            MPVLib.setPropertyBoolean("sub-visibility", true);
            lastAddedPath = filePath;
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to add subtitle: " + filePath, e);
            return false;
        }
    }

    /**
     * 添加网络字幕 URL（mpv 支持直接从 http(s) 加载字幕）
     *
     * @param url 字幕文件 URL
     * @return 是否发出加载命令
     */
    public boolean addSubtitleFromUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            MPVLib.command(new String[]{"sub-add", url, "select"});
            lastAddedPath = url;
            Log.d(TAG, "Added external subtitle from url: " + url);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to add subtitle url: " + url, e);
            return false;
        }
    }

    /**
     * 隐藏/显示字幕
     *
     * @param visible true=显示，false=隐藏
     */
    public void setVisible(boolean visible) {
        MPVLib.setPropertyBoolean("sub-visibility", visible);
    }

    /**
     * 调整字幕样式
     * ASS 字幕需将 sub-ass-override 设为 scale 才能让 sub-scale/sub-pos 生效
     *
     * @param fontSizeScale 字号缩放比例（0.5~2.0）
     * @param verticalOffset 垂直偏移（正值上移，范围 -50~50）
     */
    public void adjustStyle(float fontSizeScale, int verticalOffset) {
        this.fontSizeScale = fontSizeScale;
        this.verticalOffset = verticalOffset;
        // scale：仅覆盖缩放与位置，保留 ASS 原有字体/颜色/特效
        MPVLib.setPropertyString("sub-ass-override", "scale");
        MPVLib.setPropertyDouble("sub-scale", (double) fontSizeScale);
        // sub-pos: 0=顶部 100=底部，偏移量为正时字幕上移
        int subPos = Math.max(0, Math.min(150, 100 - verticalOffset));
        MPVLib.setPropertyInt("sub-pos", subPos);
        Log.d(TAG, "Subtitle style adjusted: scale=" + fontSizeScale + " sub-pos=" + subPos);
    }

    /**
     * 设置字幕时间偏移（音画不同步时校正）
     *
     * @param delaySeconds 延迟秒数，正值表示字幕延后出现
     */
    public void setDelay(double delaySeconds) {
        MPVLib.setPropertyDouble("sub-delay", delaySeconds);
    }

    /**
     * 获取当前字幕轨道数量
     *
     * @return 字幕轨道数量
     */
    public int getSubtitleTrackCount() {
        int count = getPropertyIntSafe("track-list/count", 0);
        int subCount = 0;
        for (int i = 0; i < count; i++) {
            if ("sub".equals(MPVLib.getPropertyString("track-list/" + i + "/type"))) {
                subCount++;
            }
        }
        return subCount;
    }

    /**
     * 获取当前活跃字幕轨道 ID
     *
     * @return 字幕轨道 ID，未选中返回 -1
     */
    public int getCurrentSubtitleId() {
        return getPropertyIntSafe("sid", -1);
    }

    /**
     * 获取最后添加的字幕路径
     */
    public String getLastAddedPath() {
        return lastAddedPath;
    }

    /**
     * 获取当前字号缩放比例
     */
    public float getFontSizeScale() {
        return fontSizeScale;
    }

    /**
     * 获取当前垂直偏移量
     */
    public int getVerticalOffset() {
        return verticalOffset;
    }

    // ==================== 私有方法 ====================

    /**
     * 空安全读取整型 mpv 属性
     *
     * @param name 属性名
     * @param fallback 读取失败时的默认值
     * @return 属性值或默认值
     */
    private int getPropertyIntSafe(String name, int fallback) {
        try {
            Integer v = MPVLib.getPropertyInt(name);
            return v == null ? fallback : v;
        } catch (Throwable e) {
            return fallback;
        }
    }

    /**
     * 解析字幕 URI 为文件系统路径
     * content:// URI 需复制到本地缓存目录
     *
     * @param context Android Context
     * @param uri 字幕 URI
     * @return 文件绝对路径，失败返回 null
     */
    private String resolveSubtitlePath(Context context, Uri uri) {
        String scheme = uri.getScheme();
        if ("file".equals(scheme)) {
            String path = uri.getPath();
            return (path != null && new File(path).exists()) ? path : null;
        } else if ("content".equals(scheme)) {
            return copyContentUriToFile(context, uri);
        }
        return null;
    }

    /**
     * 将 content:// URI 复制到本地缓存目录
     *
     * @param context Android Context
     * @param uri 内容 URI
     * @return 缓存文件路径
     */
    private String copyContentUriToFile(Context context, Uri uri) {
        try {
            File cacheDir = new File(context.getFilesDir(), SUBTITLE_CACHE_DIR);
            if (!cacheDir.exists()) {
                cacheDir.mkdirs();
            }
            String displayName = getDisplayName(context, uri);
            String fileName = uri.hashCode() + "_" + displayName;
            File outFile = new File(cacheDir, fileName);

            try (InputStream in = context.getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(outFile)) {
                if (in == null) {
                    Log.w(TAG, "openInputStream returned null for " + uri);
                    return null;
                }
                copyStream(in, out);
            }
            Log.d(TAG, "Copied subtitle to cache: " + outFile.getAbsolutePath());
            return outFile.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy subtitle URI to file", e);
            return null;
        }
    }

    /**
     * 字节流拷贝（Java 无 Kotlin 的 copyTo 扩展）
     *
     * @param in 输入流
     * @param out 输出流
     */
    private void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int len;
        while ((len = in.read(buffer)) != -1) {
            out.write(buffer, 0, len);
        }
        out.flush();
    }

    /**
     * 获取字幕文件的显示名称
     */
    private String getDisplayName(Context context, Uri uri) {
        String name = uri.getLastPathSegment();
        if (name != null) return name;
        try {
            android.database.Cursor cursor = context.getContentResolver()
                    .query(uri, null, null, null, null);
            if (cursor != null) {
                int idx = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DISPLAY_NAME);
                if (idx >= 0 && cursor.moveToFirst()) {
                    name = cursor.getString(idx);
                }
                cursor.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get display name from content URI", e);
        }
        return name != null ? name : "subtitle";
    }
}
