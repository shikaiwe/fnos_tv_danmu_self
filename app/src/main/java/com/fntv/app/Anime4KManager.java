package com.fntv.app;

import android.content.Context;
import android.util.Log;

import dev.jdtech.mpv.MPVLib;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Anime4K 实时视频超分管理器
 * 负责着色器文件的复制管理、模式配置、glsl-shaders 注入
 *
 * 着色器来源：bloc97/Anime4K 开源项目（MIT License，见各 .glsl 文件头）
 * 文件存放路径：{filesDir}/shaders/
 * mpv 属性：glsl-shaders（冒号分隔的着色器绝对路径列表）
 */
public class Anime4KManager {

    private static final String TAG = "Anime4KManager";
    private static final String SHADER_DIR = "shaders";
    /** mpv 着色器链属性名（Unix 平台使用冒号分隔多个文件） */
    private static final String PROP_SHADERS = "glsl-shaders";

    private final Context context;
    private File shaderDir;
    private boolean isInitialized = false;

    // ===== 着色器质量等级 =====
    public enum Quality {
        FAST("S"),     // 快速（Small）
        BALANCED("M"), // 平衡（Medium）
        HIGH("L");     // 高质量（Large）

        /** 对应 Anime4K 着色器文件名后缀 */
        public final String suffix;

        /**
         * @param suffix 着色器文件名尺寸后缀（S/M/L）
         */
        Quality(String suffix) {
            this.suffix = suffix;
        }
    }

    // ===== Anime4K 模式 =====
    public enum Mode {
        OFF,      // 禁用
        A,        // 优化1080p动画
        B,        // 优化720p动画
        C,        // 优化480p动画
        A_PLUS,   // A+A 最高感知质量
        B_PLUS,   // B+B 高感知质量
        C_PLUS    // C+A 略高感知质量
    }

    private Mode currentMode = Mode.OFF;
    private Quality currentQuality = Quality.BALANCED;
    private boolean enableDeblur = false;

    public Anime4KManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * 延迟初始化：将着色器从 assets 复制到内部存储
     * 避免每次启动都执行 I/O 操作（懒加载）
     *
     * @return 是否初始化成功
     */
    public boolean initialize() {
        if (isInitialized) {
            Log.d(TAG, "Already initialized, skipping");
            return true;
        }
        try {
            shaderDir = new File(context.getFilesDir(), SHADER_DIR);
            if (!shaderDir.exists()) {
                shaderDir.mkdirs();
            }

            String[] assetFiles = context.getAssets().list(SHADER_DIR);
            if (assetFiles == null || assetFiles.length == 0) {
                Log.w(TAG, "No shader files found in assets/shaders/");
                return false;
            }

            int copiedCount = 0;
            for (String fileName : assetFiles) {
                if (fileName.endsWith(".glsl")) {
                    copyShaderFromAssets(fileName);
                    copiedCount++;
                }
            }
            isInitialized = true;
            Log.d(TAG, "Initialized " + copiedCount + " shader files in " + shaderDir.getAbsolutePath());
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to initialize Anime4K shaders", e);
            return false;
        }
    }

    /**
     * 从 assets 复制单个着色器文件到内部存储
     *
     * @param fileName 文件名（如 Anime4K_Clamp_Highlights.glsl）
     */
    private void copyShaderFromAssets(String fileName) throws IOException {
        File destFile = new File(shaderDir, fileName);
        if (destFile.exists()) {
            return; // 已存在则跳过
        }
        try (InputStream input = context.getAssets().open(SHADER_DIR + "/" + fileName);
             FileOutputStream output = new FileOutputStream(destFile)) {
            copyStream(input, output);
        }
        Log.d(TAG, "Copied shader: " + fileName);
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
     * 构建 Anime4K 着色器链字符串
     * 按照 Anime4K v4.x 官方推荐配置生成冒号分隔的路径列表
     *
     * @param mode 超分模式
     * @param quality 质量等级
     * @return 着色器链（冒号分隔的绝对路径），OFF 时返回空字符串
     */
    public String buildShaderChain(Mode mode, Quality quality) {
        if (mode == Mode.OFF || shaderDir == null) {
            return "";
        }
        currentMode = mode;
        currentQuality = quality;

        String q = quality.suffix;
        // 后置着色器用低一级变体抵消 4x 像素量的性能开销
        String lowerQ = (quality == Quality.HIGH) ? "M" : "S";

        List<String> shaders = new ArrayList<>();
        // 始终添加 Clamp_Highlights 防止振铃伪影
        shaders.add(getShaderPath("Anime4K_Clamp_Highlights.glsl"));

        switch (mode) {
            case A:
                shaders.add(getShaderPath("Anime4K_Restore_CNN_" + q + ".glsl"));
                shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_" + q + ".glsl"));
                shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x2.glsl"));
                shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x4.glsl"));
                shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_" + lowerQ + ".glsl"));
                break;
            case B:
                shaders.add(getShaderPath("Anime4K_Restore_CNN_Soft_" + q + ".glsl"));
                shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_" + q + ".glsl"));
                shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x2.glsl"));
                shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x4.glsl"));
                shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_" + lowerQ + ".glsl"));
                break;
            case C:
                shaders.add(getShaderPath("Anime4K_Upscale_Denoise_CNN_x2_" + q + ".glsl"));
                shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x2.glsl"));
                shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x4.glsl"));
                shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_" + lowerQ + ".glsl"));
                break;
            case A_PLUS:
                shaders.add(getShaderPath("Anime4K_Restore_CNN_" + q + ".glsl"));
                shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_" + q + ".glsl"));
                shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x2.glsl"));
                shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x4.glsl"));
                shaders.add(getShaderPath("Anime4K_Restore_CNN_" + lowerQ + ".glsl"));
                shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_" + lowerQ + ".glsl"));
                break;
            case B_PLUS:
                shaders.add(getShaderPath("Anime4K_Restore_CNN_Soft_" + q + ".glsl"));
                shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_" + q + ".glsl"));
                shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x2.glsl"));
                shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x4.glsl"));
                shaders.add(getShaderPath("Anime4K_Restore_CNN_Soft_" + lowerQ + ".glsl"));
                shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_" + lowerQ + ".glsl"));
                break;
            case C_PLUS:
                shaders.add(getShaderPath("Anime4K_Upscale_Denoise_CNN_x2_" + q + ".glsl"));
                shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x2.glsl"));
                shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x4.glsl"));
                shaders.add(getShaderPath("Anime4K_Restore_CNN_" + lowerQ + ".glsl"));
                shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_" + lowerQ + ".glsl"));
                break;
            case OFF:
                return "";
        }

        // 可选：追加去模糊模块
        if (enableDeblur) {
            shaders.add(getShaderPath("Anime4K_Deblur_DoG.glsl"));
        }

        String chain = android.text.TextUtils.join(":", shaders);
        Log.d(TAG, "Shader chain [" + mode + ", " + quality + "]: " + chain);
        return chain;
    }

    /**
     * 获取着色器文件的绝对路径
     *
     * @param fileName 文件名
     * @return 绝对路径
     */
    private String getShaderPath(String fileName) {
        return new File(shaderDir, fileName).getAbsolutePath();
    }

    /**
     * 应用着色器链到 mpv 播放器
     *
     * @param mode 超分模式
     * @param quality 质量等级
     */
    public void applyShaderChain(Mode mode, Quality quality) {
        String chain = buildShaderChain(mode, quality);
        if (chain.isEmpty()) {
            clearShaders();
        } else {
            MPVLib.setPropertyString(PROP_SHADERS, chain);
            Log.d(TAG, "Anime4K applied: " + chain.length() + " chars");
        }
    }

    /**
     * 清空 mpv 的着色器链（关闭超分）
     */
    public void clearShaders() {
        currentMode = Mode.OFF;
        MPVLib.command(new String[]{"change-list", PROP_SHADERS, "clr", ""});
        Log.d(TAG, "Anime4K disabled, shader list cleared");
    }

    /**
     * 开启/关闭去模糊模块
     */
    public void setDeblurEnabled(boolean enabled) {
        this.enableDeblur = enabled;
        // 如果当前有激活的模式，重新应用着色器链
        if (currentMode != Mode.OFF && isInitialized) {
            applyShaderChain(currentMode, currentQuality);
        }
    }

    public Mode getCurrentMode() { return currentMode; }
    public Quality getCurrentQuality() { return currentQuality; }
    public boolean isInitialized() { return isInitialized; }
    public boolean isDeblurEnabled() { return enableDeblur; }

    /**
     * 获取模式描述文本
     */
    public String getModeDescription(Mode mode) {
        switch (mode) {
            case OFF: return "禁用 Anime4K";
            case A: return "模式 A — 优化 1080p 动画\n高模糊度、重采样伪影";
            case B: return "模式 B — 优化 720p 动画\n低模糊度、下采样振铃";
            case C: return "模式 C — 优化 480p 动画\n最高 PSNR、低感知质量";
            case A_PLUS: return "模式 A+A — 最高感知质量\n更强的线条重建（较慢）";
            case B_PLUS: return "模式 B+B — 高感知质量\n更好的 720p 效果（较慢）";
            case C_PLUS: return "模式 C+A — 略高感知质量\n改进的 480p 效果（较慢）";
            default: return "";
        }
    }
}
