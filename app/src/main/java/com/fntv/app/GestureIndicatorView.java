package com.fntv.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Interpolator;

/**
 * 手势指示器圆形进度环 View。
 * 绘制一个半透明背景圆环 + 带颜色的进度弧，随手势实时更新进度。
 *
 * 用法：在 XML 中通过 <view> 标签引用，或代码中实例化后 addView。
 */
public class GestureIndicatorView extends View {

    /** 进度环画笔 */
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 进度弧画笔 */
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 进度矩形区域 */
    private final RectF arcRect = new RectF();
    /** 当前进度 0~100 */
    private float progress = 0f;
    /** 动画插值器 */
    private final AccelerateDecelerateInterpolator interpolator = new AccelerateDecelerateInterpolator();

    /** 环宽 (px) */
    private static final float RING_WIDTH = 4f;
    /** 背景环透明度 */
    private static final int BG_ALPHA = 60;
    /** 进度弧颜色：accent 珊瑚粉 */
    private static final int PROGRESS_COLOR = Color.parseColor("#FB6F92");

    public GestureIndicatorView(Context context) {
        super(context);
        init();
    }

    public GestureIndicatorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public GestureIndicatorView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    /**
     * 初始化画笔属性。
     */
    private void init() {
        // 背景环：半透明浅色
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(RING_WIDTH);
        ringPaint.setColor(Color.parseColor("#33FFFFFF"));
        ringPaint.setStrokeCap(Paint.Cap.ROUND);

        // 进度弧：accent 色，实心描边
        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeWidth(RING_WIDTH);
        progressPaint.setColor(PROGRESS_COLOR);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    /**
     * 设置当前进度（0~100），并触发重绘。
     * @param pct 进度百分比
     */
    public void setProgress(float pct) {
        this.progress = Math.max(0f, Math.min(100f, pct));
        invalidate();
    }

    /**
     * 渐入到目标进度（带动画）。
     * @param targetPct 目标进度 0~100
     * @param durationMs 动画时长（毫秒）
     */
    public void animateProgress(float targetPct, long durationMs) {
        float start = this.progress;
        float end = Math.max(0f, Math.min(100f, targetPct));
        android.view.animation.Animation anim = new android.view.animation.Animation() {
            @Override
            protected void applyTransformation(float interpolatedTime, android.view.animation.Transformation t) {
                setProgress(start + (end - start) * interpolatedTime);
            }
        };
        anim.setDuration(durationMs);
        anim.setInterpolator(interpolator);
        startAnimation(anim);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // 强制正方形
        int size = Math.min(MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.getSize(heightMeasureSpec));
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getMeasuredWidth() / 2f;
        float cy = getMeasuredHeight() / 2f;
        float radius = cx - RING_WIDTH;
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);

        // 绘制背景环
        canvas.drawArc(arcRect, 270f, 360f, false, ringPaint);
        // 绘制进度弧（从顶部 270° 开始顺时针）
        float sweep = (progress / 100f) * 360f;
        canvas.drawArc(arcRect, 270f, sweep, false, progressPaint);
    }
}
