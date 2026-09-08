package com.fntv.app;

import android.content.Context;
import android.media.AudioManager;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * 播放器手势控制器 — 支持三区域手势、Seek预览浮层、三指手势、长按倍速。
 * 仅在触屏设备上激活，TV 遥控器设备不受影响。
 *
 * 手势区域划分（三区域模型）：
 *   左区 30%  → 垂直滑动调亮度
 *   中区 40%  → 水平滑动 Seek
 *   右区 30%  → 垂直滑动调音量
 *
 * 其他手势：
 *   单击           → 切换控制栏显隐
 *   双击           → 任意区域统一 暂停/继续播放（快进/快退手势已移除）
 *   长按 500ms+    → 临时倍速（倍速值由 PlayerActivity 从设置读取）
 *   三指上滑       → 显示弹幕设置面板
 *   三指下滑       → 显示字幕设置面板
 */
public class PlayerGestureHelper implements View.OnTouchListener {

    /** 手势类型枚举 */
    public enum GestureType { NONE, VOLUME, BRIGHTNESS, PROGRESS, DOUBLE_TAP, LONG_PRESS_SPEED }

    /** 核心回调接口 — 由 PlayerActivity 实现 */
    public interface GestureCallback {
        void onVolumeChange(int percent);
        void onBrightnessChange(int percent);
        /**
         * 水平滑动进度回调
         * @param fraction 滑动比例（dx / 视图宽度），正=快进，负=快退
         * @param x        手指当前 X 坐标（用于 Seek 预览定位）
         * @param y        手指当前 Y 坐标
         */
        void onProgressChange(float fraction, float x, float y);
        void onGestureStart();
        void onGestureEnd();
        /** 单击（非双击的第一下）：切换控制栏显隐 */
        void onSingleTap();
        /** 双击：xRatio 为点击 X 坐标占视图宽度比例（0=最左，1=最右） */
        void onDoubleTap(float xRatio);
        void onLongPressSpeed(boolean active);
        void onThreeFingerUp();
        void onThreeFingerDown();
        void showGestureIndicator(GestureType type, int percent, String label);
        void hideGestureIndicator();
        /**
         * 显示进度预览浮层（仿 YouTube）
         * @param dx         水平位移比例 (-1~1)
         * @param positionMs 目标播放位置（毫秒）
         * @param x          浮层屏幕 X 坐标
         * @param y          浮层屏幕 Y 坐标
         */
        void showSeekPreview(float dx, long positionMs, float x, float y);
        void hideSeekPreview();
    }

    /** 最小滑动距离阈值 (px) */
    private static final int SWIPE_THRESHOLD = 30;
    /** 长按倍速触发时长 (ms) */
    private static final int LONG_PRESS_DELAY_MS = 500;
    /** 左区边界比例 (0~1) */
    private static final float LEFT_ZONE_END = 0.3f;
    /** 右区起始比例 (0~1) */
    private static final float RIGHT_ZONE_START = 0.7f;
    /** 三指手势时间窗口 (ms) — 多指必须在短时间内触发 */
    private static final long THREE_FINGER_WINDOW_MS = 300;
    /** 三指手势最小垂直距离 (px) */
    private static final int THREE_FINGER_SWIPE_THRESHOLD = 60;

    private final Context context;
    private final GestureDetector gestureDetector;
    private final AudioManager audioManager;
    private final DisplayMetrics metrics;
    private final GestureCallback callback;
    private final boolean touchEnabled;
    /** 屏幕高度 */
    private final int screenHeight;

    // 内部状态
    private float startX, startY;
    private int startVolume;
    private int startBrightness;
    private GestureType currentGesture = GestureType.NONE;
    private boolean gestureActive = false;
    /** 视图宽度（用于双击左右区域判定） */
    private int viewWidth = 0;
    /** 长按倍速定时器 */
    private final android.os.Handler longPressHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean longPressing = false;
    /** 当前触摸序列是否已触发长按，用于阻止松手事件被延迟识别为单击 */
    private boolean longPressTriggered = false;
    /** 三指检测 */
    private int recentTouchCount = 0;
    private long lastTouchEventTime = 0;
    private float threeFingerStartY = 0f;

    /**
     * 构造函数
     * @param context  Activity 上下文
     * @param callback 手势回调
     */
    public PlayerGestureHelper(Context context, GestureCallback callback) {
        this.context = context;
        this.callback = callback;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.metrics = new DisplayMetrics();
        ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay().getMetrics(metrics);
        this.screenHeight = metrics.heightPixels;
        this.touchEnabled = context.getPackageManager()
                .hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN);

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                // 长按使用独立定时器识别，必须显式吞掉同一触摸序列迟到的单击确认。
                if (longPressTriggered) return true;
                if (callback != null) callback.onSingleTap();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (callback != null) {
                    float ratio = viewWidth > 0 ? e.getX() / viewWidth : 0.5f;
                    callback.onDoubleTap(ratio);
                }
                return true;
            }
        });
    }

    /** 判断手势系统是否可用（仅触屏设备） */
    public boolean isEnabled() {
        return touchEnabled;
    }

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        if (!touchEnabled) return false;

        int action = event.getAction() & MotionEvent.ACTION_MASK;
        int pointerCount = event.getPointerCount();
        if (view.getWidth() > 0) viewWidth = view.getWidth();

        // 多指加入（含三指手势入口）：取消单指长按倍速定时器，避免多指操作误触发临时倍速
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            longPressHandler.removeCallbacksAndMessages(null);
        }

        // 三指手势检测
        if (pointerCount >= 3) {
            handleThreeFingerGesture(event, action, pointerCount, view);
            return true;
        }

        gestureDetector.onTouchEvent(event);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                // 单指按下
                if (pointerCount == 1) {
                    startX = event.getX(0);
                    startY = event.getY(0);
                    startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                    startBrightness = getScreenBrightness();
                    currentGesture = GestureType.NONE;
                    gestureActive = false;
                    longPressing = false;
                    longPressTriggered = false;
                    // 启动长按倍速定时器
                    scheduleLongPress();
                }
                break;

            case MotionEvent.ACTION_MOVE: {
                if (pointerCount == 1) {
                    float dx = event.getX(0) - startX;
                    float dy = event.getY(0) - startY;

                    // 确定手势类型（三区域模型）
                    if (!gestureActive) {
                        currentGesture = determineGestureType(dx, dy, startX, view.getWidth());
                    }

                    if (currentGesture != GestureType.NONE && !gestureActive) {
                        gestureActive = true;
                        // 取消长按倍速（因为发生了滑动）
                        longPressHandler.removeCallbacksAndMessages(null);
                        if (callback != null) callback.onGestureStart();
                    }

                    if (gestureActive) {
                        switch (currentGesture) {
                            case VOLUME:
                                handleVolume(dy);
                                break;
                            case BRIGHTNESS:
                                handleBrightness(dy);
                                break;
                            case PROGRESS:
                                handleProgress(dx, view.getWidth(), event.getX(0), event.getY(0));
                                break;
                        }
                    }
                }
                break;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pointerCount == 1) {
                    longPressHandler.removeCallbacksAndMessages(null);
                    if (gestureActive && callback != null) {
                        callback.onGestureEnd();
                        callback.hideGestureIndicator();
                    }
                    // 取消长按倍速
                    if (longPressing && callback != null) {
                        callback.onLongPressSpeed(false);
                        longPressing = false;
                    }
                    gestureActive = false;
                    currentGesture = GestureType.NONE;
                }
                break;
        }
        return true;
    }

    /**
     * 根据三区域模型确定手势类型。
     * 左区(0~30%)：垂直滑动→亮度；右区(70%~100%)：垂直滑动→音量；中区：仅水平滑动→Seek。
     *
     * @param dx      水平位移
     * @param dy      垂直位移
     * @param xPos    手指 X 坐标
     * @param viewWid 视图宽度
     * @return 确定的手势类型
     */
    private GestureType determineGestureType(float dx, float dy, float xPos, int viewWid) {
        if (Math.abs(dy) < SWIPE_THRESHOLD && Math.abs(dx) < SWIPE_THRESHOLD) {
            return GestureType.NONE;
        }
        float zoneRatio = viewWid > 0 ? xPos / viewWid : 0.5f;
        // 左区：垂直滑动→亮度
        if (zoneRatio < LEFT_ZONE_END) {
            if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > SWIPE_THRESHOLD) {
                return GestureType.BRIGHTNESS;
            }
            return GestureType.NONE;
        }
        // 右区：垂直滑动→音量
        if (zoneRatio > RIGHT_ZONE_START) {
            if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > SWIPE_THRESHOLD) {
                return GestureType.VOLUME;
            }
            return GestureType.NONE;
        }
        // 中区：仅水平滑动→Seek，垂直滑动不触发手势
        if (Math.abs(dx) > SWIPE_THRESHOLD) {
            return GestureType.PROGRESS;
        }
        return GestureType.NONE;
    }

    /**
     * 处理三指手势（上滑/下滑）。
     */
    private void handleThreeFingerGesture(MotionEvent event, int action, int pointerCount, View view) {
        if (pointerCount < 3) return;
        // 计算所有触点的平均位置
        float avgX = 0, avgY = 0;
        for (int i = 0; i < Math.min(pointerCount, 3); i++) {
            avgX += event.getX(i);
            avgY += event.getY(i);
        }
        avgX /= 3;
        avgY /= 3;

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            recentTouchCount++;
            lastTouchEventTime = System.currentTimeMillis();
            threeFingerStartY = avgY;
        } else if (action == MotionEvent.ACTION_MOVE && recentTouchCount >= 3) {
            float dy = threeFingerStartY - avgY; // 正=上滑
            if (Math.abs(dy) > THREE_FINGER_SWIPE_THRESHOLD) {
                if (dy > 0 && callback != null) {
                    callback.onThreeFingerUp();
                } else if (dy < 0 && callback != null) {
                    callback.onThreeFingerDown();
                }
                recentTouchCount = 0;
            }
        }
    }

    /**
     * 调度长按倍速定时器。
     */
    private void scheduleLongPress() {
        longPressHandler.removeCallbacksAndMessages(null);
        longPressHandler.postDelayed(() -> {
            longPressTriggered = true;
            longPressing = true;
            if (callback != null) callback.onLongPressSpeed(true);
        }, LONG_PRESS_DELAY_MS);
    }

    private void handleVolume(float dy) {
        int maxVol = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        // 屏幕坐标 y 向下，上滑 dy<0，取负使上滑增大音量
        int delta = (int) (-dy / screenHeight * maxVol);
        int newVol = Math.max(0, Math.min(maxVol, startVolume + delta));
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0);
        int percent = maxVol > 0 ? (newVol * 100 / maxVol) : 0;
        if (callback != null) callback.onVolumeChange(percent);
        if (callback != null) callback.showGestureIndicator(
                GestureType.VOLUME, percent, percent + "%");
    }

    private void handleBrightness(float dy) {
        // 屏幕坐标 y 向下，上滑 dy<0，取负使上滑增大亮度
        int delta = (int) (-dy / screenHeight * 100);
        int newBright = Math.max(0, Math.min(255, startBrightness + delta * 255 / 100));
        setScreenBrightness(newBright);
        int percent = newBright * 100 / 255;
        if (callback != null) callback.onBrightnessChange(percent);
        if (callback != null) callback.showGestureIndicator(
                GestureType.BRIGHTNESS, percent, percent + "%");
    }

    /**
     * 水平滑动 → 上报滑动比例及手指坐标，由回调方换算目标进度并显示 Seek 预览
     *
     * @param dx        水平位移（px）
     * @param viewWidth 视图宽度（px）
     * @param x         手指屏幕 X 坐标
     * @param y         手指屏幕 Y 坐标
     */
    private void handleProgress(float dx, int viewWidth, float x, float y) {
        if (callback == null || viewWidth <= 0) return;
        callback.onProgressChange(dx / viewWidth, x, y);
    }

    private int getScreenBrightness() {
        try {
            return android.provider.Settings.System.getInt(
                    context.getContentResolver(),
                    android.provider.Settings.System.SCREEN_BRIGHTNESS, 128);
        } catch (Exception e) {
            return 128;
        }
    }

    private void setScreenBrightness(int value) {
        try {
            WindowManager.LayoutParams lp =
                    ((android.app.Activity) context).getWindow().getAttributes();
            lp.screenBrightness = value / 255f;
            ((android.app.Activity) context).getWindow().setAttributes(lp);
        } catch (Exception e) {
            // 忽略
        }
    }
}
