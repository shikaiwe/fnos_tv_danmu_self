package com.fntv.app;

import android.content.Context;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 设置面板管理器 — 管理底部双层 Tab 设置面板、弹幕设置面板、字幕设置面板的切换与动画。
 * 所有视图通过构造函数注入，不持有 Activity 引用，便于测试。
 */
public class SettingsPanelManager {

    private static final String TAG = "SettingsPanelManager";

    // ---- 主设置面板（Tab） ----
    private final ViewGroup settingsPanel;
    private final View settingsBackdrop;
    private final LinearLayout tabContentPlay, tabContentQuality, tabContentSubtitle, tabContentDanmu;
    private final Button tabBtnPlay, tabBtnQuality, tabBtnSubtitle, tabBtnDanmu;
    private final Button btnCloseSettings;

    // ---- 弹幕设置抽屉（SideDrawerHelper，按需创建） ----
    private SideDrawerHelper danmuDrawer;
    private View danmuContent;

    // ---- 状态 ----
    private int currentTab = 0; // 0=播放, 1=画质, 2=字幕, 3=弹幕
    private boolean isSettingsOpen = false;
    private boolean isDanmuOpen = false;

    // ---- 回调 ----
    private final OnPanelStateChangeListener stateListener;

    /** 面板状态变化回调 */
    public interface OnPanelStateChangeListener {
        void onSettingsPanelChanged(boolean open, int currentTab);
        void onDanmuPanelChanged(boolean open);
    }

    /**
     * 构造 SettingsPanelManager
     *
     * @param context       上下文
     * @param views         视图容器
     * @param stateListener 状态回调
     */
    public SettingsPanelManager(Context context, Views views, OnPanelStateChangeListener stateListener) {
        this.settingsPanel = views.settingsPanel;
        this.settingsBackdrop = views.settingsBackdrop;
        this.tabContentPlay = views.tabContentPlay;
        this.tabContentQuality = views.tabContentQuality;
        this.tabContentSubtitle = views.tabContentSubtitle;
        this.tabContentDanmu = views.tabContentDanmu;
        this.tabBtnPlay = views.tabBtnPlay;
        this.tabBtnQuality = views.tabBtnQuality;
        this.tabBtnSubtitle = views.tabBtnSubtitle;
        this.tabBtnDanmu = views.tabBtnDanmu;
        this.btnCloseSettings = views.btnCloseSettings;
        this.stateListener = stateListener;

        initTabs(context);
        initCloseButton();
        settingsBackdrop.setOnClickListener(v -> closeSettingsPanel());
    }

    /**
     * 初始化 Tab 切换逻辑
     */
    private void initTabs(Context context) {
        tabBtnPlay.setOnClickListener(v -> switchTab(0));
        tabBtnQuality.setOnClickListener(v -> switchTab(1));
        tabBtnSubtitle.setOnClickListener(v -> switchTab(2));
        tabBtnDanmu.setOnClickListener(v -> switchTab(3));
    }

    /**
     * 切换到指定 Tab
     *
     * @param index Tab 索引（0=播放, 1=画质, 2=字幕, 3=弹幕）
     */
    public void switchTab(int index) {
        if (index < 0 || index > 3) return;
        currentTab = index;

        // 隐藏所有 Tab 内容
        tabContentPlay.setVisibility(View.GONE);
        tabContentQuality.setVisibility(View.GONE);
        tabContentSubtitle.setVisibility(View.GONE);
        tabContentDanmu.setVisibility(View.GONE);

        // 重置所有 Tab 按钮颜色
        resetTabColor(tabBtnPlay);
        resetTabColor(tabBtnQuality);
        resetTabColor(tabBtnSubtitle);
        resetTabColor(tabBtnDanmu);

        // 显示目标 Tab 内容并高亮对应按钮
        switch (index) {
            case 0:
                tabContentPlay.setVisibility(View.VISIBLE);
                highlightTab(tabBtnPlay);
                break;
            case 1:
                tabContentQuality.setVisibility(View.VISIBLE);
                highlightTab(tabBtnQuality);
                break;
            case 2:
                tabContentSubtitle.setVisibility(View.VISIBLE);
                highlightTab(tabBtnSubtitle);
                break;
            case 3:
                tabContentDanmu.setVisibility(View.VISIBLE);
                highlightTab(tabBtnDanmu);
                break;
        }

        if (stateListener != null) {
            stateListener.onSettingsPanelChanged(isSettingsOpen, currentTab);
        }
    }

    /**
     * 高亮指定 Tab 按钮（accent 色文字）
     */
    private void highlightTab(Button tabBtn) {
        tabBtn.setTextColor(tabBtn.getContext().getResources().getColor(android.R.color.white, null));
        tabBtn.setTypeface(null, android.graphics.Typeface.BOLD);
    }

    /**
     * 重置 Tab 按钮颜色（次要文字色）
     */
    private void resetTabColor(Button tabBtn) {
        tabBtn.setTextColor(tabBtn.getContext().getResources().getColor(android.R.color.secondary_text_dark, null));
        tabBtn.setTypeface(null, android.graphics.Typeface.NORMAL);
    }

    /**
     * 关闭设置面板
     */
    public void closeSettingsPanel() {
        if (!isSettingsOpen) return;
        isSettingsOpen = false;
        animateSlideOut(settingsPanel);
        if (stateListener != null) {
            stateListener.onSettingsPanelChanged(false, currentTab);
        }
    }

    /**
     * 打开设置面板，默认选中指定 Tab
     *
     * @param defaultTab 默认选中的 Tab（0=播放, 1=画质, 2=字幕, 3=弹幕）
     */
    public void openSettingsPanel(int defaultTab) {
        if (isSettingsOpen) {
            switchTab(defaultTab);
            return;
        }
        isSettingsOpen = true;
        settingsPanel.clearAnimation();
        settingsBackdrop.setVisibility(View.VISIBLE);
        settingsPanel.setVisibility(View.VISIBLE);
        switchTab(defaultTab);
        animateSlideIn(settingsPanel);
        if (stateListener != null) {
            stateListener.onSettingsPanelChanged(true, defaultTab);
        }
    }

    /**
     * 切换设置面板显隐状态
     */
    public void toggleSettingsPanel() {
        if (isSettingsOpen) {
            closeSettingsPanel();
        } else {
            openSettingsPanel(currentTab);
        }
    }

    /**
     * 打开弹幕设置抽屉（SideDrawerHelper 右侧抽屉样式）。
     * 内容视图的控件接线由 PlayerActivity 的 onDanmuPanelChanged(true) 回调完成
     * （DanmuManager.bindSettingsPanel 绑定到抽屉内容视图）。
     */
    public void openDanmuPanel() {
        if (isDanmuOpen) return;
        isDanmuOpen = true;
        if (danmuDrawer != null) {
            danmuDrawer.dismiss();
            danmuDrawer = null;
        }
        danmuDrawer = new SideDrawerHelper((android.app.Activity) settingsPanel.getContext());
        android.view.View content = android.view.LayoutInflater.from(settingsPanel.getContext())
                .inflate(R.layout.panel_danmu_content, null);
        danmuContent = content;
        danmuDrawer.showCustom("弹幕设置", content);
        danmuDrawer.getDialog().setOnDismissListener(d -> {
            isDanmuOpen = false;
            danmuContent = null;
            if (stateListener != null) {
                stateListener.onDanmuPanelChanged(false);
            }
        });
        if (stateListener != null) {
            stateListener.onDanmuPanelChanged(true);
        }
    }

    /** 获取当前弹幕抽屉的内容视图（打开期间有效，供绑定控件），未打开时返回 null */
    public View getDanmuPanelContent() {
        return danmuContent;
    }

    /**
     * 关闭弹幕设置面板
     */
    public void closeDanmuPanel() {
        if (!isDanmuOpen) return;
        if (danmuDrawer != null) {
            danmuDrawer.dismiss();
            danmuDrawer = null;
        }
        isDanmuOpen = false;
        danmuContent = null;
        if (stateListener != null) {
            stateListener.onDanmuPanelChanged(false);
        }
    }

    /**
     * 切换弹幕设置面板显隐状态
     */
    public void toggleDanmuPanel() {
        if (isDanmuOpen) {
            closeDanmuPanel();
        } else {
            openDanmuPanel();
        }
    }

    /**
     * 获取当前是否打开了设置面板
     */
    public boolean isSettingsPanelOpen() {
        return isSettingsOpen;
    }

    /**
     * 获取当前是否打开了弹幕设置面板
     */
    public boolean isDanmuPanelOpen() {
        return isDanmuOpen;
    }

    /**
     * 获取当前选中的 Tab 索引
     */
    public int getCurrentTab() {
        return currentTab;
    }

    /**
     * 初始化关闭按钮点击事件
     */
    private void initCloseButton() {
        btnCloseSettings.setOnClickListener(v -> closeSettingsPanel());
    }

    /**
     * 执行滑入动画
     */
    private void animateSlideIn(View panel) {
        Animation slideIn = AnimationUtils.loadAnimation(panel.getContext(), R.anim.panel_slide_in);
        slideIn.setAnimationListener(new Animation.AnimationListener() {
            @Override public void onAnimationStart(Animation animation) {}
            @Override public void onAnimationEnd(Animation animation) {}
            @Override public void onAnimationRepeat(Animation animation) {}
        });
        panel.startAnimation(slideIn);
    }

    /**
     * 执行滑出动画
     */
    private void animateSlideOut(View panel) {
        Animation slideOut = AnimationUtils.loadAnimation(panel.getContext(), R.anim.panel_slide_out);
        slideOut.setAnimationListener(new Animation.AnimationListener() {
            @Override public void onAnimationStart(Animation animation) {}
            @Override public void onAnimationEnd(Animation animation) {
                if (!isSettingsOpen) {
                    panel.setVisibility(View.GONE);
                    settingsBackdrop.setVisibility(View.GONE);
                }
            }
            @Override public void onAnimationRepeat(Animation animation) {}
        });
        panel.startAnimation(slideOut);
    }

    // ==================== 数据容器 ====================

    /** 所有视图的容器，便于构造时批量传入 */
    public static class Views {
        public final ViewGroup settingsPanel;
        public final View settingsBackdrop;
        public final LinearLayout tabContentPlay;
        public final LinearLayout tabContentQuality;
        public final LinearLayout tabContentSubtitle;
        public final LinearLayout tabContentDanmu;
        public final Button tabBtnPlay;
        public final Button tabBtnQuality;
        public final Button tabBtnSubtitle;
        public final Button tabBtnDanmu;
        public final Button btnCloseSettings;

        public Views(ViewGroup settingsPanel, View settingsBackdrop,
                     LinearLayout tabContentPlay, LinearLayout tabContentQuality,
                     LinearLayout tabContentSubtitle, LinearLayout tabContentDanmu,
                     Button tabBtnPlay, Button tabBtnQuality, Button tabBtnSubtitle, Button tabBtnDanmu,
                     Button btnCloseSettings) {
            this.settingsPanel = settingsPanel;
            this.settingsBackdrop = settingsBackdrop;
            this.tabContentPlay = tabContentPlay;
            this.tabContentQuality = tabContentQuality;
            this.tabContentSubtitle = tabContentSubtitle;
            this.tabContentDanmu = tabContentDanmu;
            this.tabBtnPlay = tabBtnPlay;
            this.tabBtnQuality = tabBtnQuality;
            this.tabBtnSubtitle = tabBtnSubtitle;
            this.tabBtnDanmu = tabBtnDanmu;
            this.btnCloseSettings = btnCloseSettings;
        }
    }
}
