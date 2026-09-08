package com.fntv.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.app.PictureInPictureParams;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.KeyEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import android.content.pm.ActivityInfo;
import com.fntv.app.api.FnApiManager;
import com.fntv.app.api.FnAuthUtils;
import com.fntv.app.api.model.*;
import android.view.animation.AnimationUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import dev.jdtech.mpv.MPVLib;

public class PlayerActivity extends AppCompatActivity implements MPVTimeSource {

    private CustomMPVView playerView;
    private TextView tvBuffering, tvTime;
    /** 信息抽屉键值网格分组容器（updateInfo 填充，抽屉关闭时置空） */
    private LinearLayout infoText, infoTextAudio, infoTextExtra;
    private SeekBar seekBar;
    private Button btnPlayPause, btnRewind, btnForward, btnSpeed, btnRatio, btnInfo, btnEpisodeList, btnBack, btnDanmu, btnQuality;
    private ImageView btnLock;
    private TextView tvTitle, tvDanmuStatus, tvDanmuMatch, tvSpeedHint;
    private Button btnCloudMode, btnBrightness, btnSettings;
    private boolean introSkipped = false, outroSkipped = false;
    private float speedBeforeLongPress = 1.0f;
    private DanmuView danmuView;
    private View controller, topBar;
    private final Runnable finishHideControls = this::finishHideControls;
    /** 信息抽屉（⋯ 菜单） */
    private android.app.Dialog infoDrawer;
    private SideDrawerHelper skipDrawer;
    private SideDrawerHelper subtitleDrawer;
    private boolean isLocked = false;
    private DanmuManager danmuManager;
    private QualitySelectHelper qualityHelper;
    private SubtitleManager subtitleManager;
    private Anime4KManager anime4kManager;
    private SettingsPanelManager settingsPanelManager;
    /** 外挂字幕文件选择器请求码 */
    private static final int REQ_PICK_SUBTITLE = 1001;

    private Handler handler = new Handler(Looper.getMainLooper());
    // 手势控制（新手势浮层结构）
    private PlayerGestureHelper gestureHelper;
    private FrameLayout gestureOverlay;
    private FrameLayout lockOverlay;
    // 手势指示器子视图
    private FrameLayout gestureBrightness, gestureVolume, gestureSeek, gestureDoubleTap;
    // 横滑 seek 状态：手势起点位置与待提交的目标位置（-1 表示无待提交 seek）
    private long gestureSeekBase = -1;
    private long gestureSeekTarget = -1;
    /** 全屏宽度横滑对应的进度跨度（毫秒） */
    private static final long GESTURE_SEEK_SPAN_MS = 90_000L;
    /** 长按倍速手势生效中/刚结束：窗口内抑制单击切 UI（单击确认回调与倍速触发交错会多弹一次 UI） */
    private boolean speedGestureActive = false;
    private static final long SPEED_GESTURE_UI_GUARD_MS = 450;
    // 加载状态浮层
    private View loadingOverlay;
    private TextView loadingText;
    private Button btnRetry;
    private String itemGuid, baseUrl, itemTitle, itemTV, itemPoster, itemCategory, parentGuid;
    private long itemDuration;
    private FnApiManager apiManager;
    private String mediaGuid, videoGuid, audioGuid, subtitleGuid, resolution;
    private boolean ctrlVis = false;
    private long seekTs = 0;
    // 倍速选项 (0.5x ~ 4.0x 共 8 档)
    private static final float[] SPEED_OPTIONS = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f, 4.0f};
    private float currentSpeed = 1.0f;
    private int ratioIdx = 0;
    private boolean isHwDecode = true;
    private EpisodeManager episodeManager;
    private int seasonNumber = 1;
    private long backPressedTime = 0;
    private CloudStreamManager cloudStreamManager;
    private boolean useHls = false;
    private int seekStep = 10000;
    private int streamBitrate = 0; // bps 来自 stream API
    private Runnable seekCommitR;
    private long pendingSeekMs = -1;
    private String savedPlaybackUrl = null; // 当前播放地址（用于硬解失败后切软解重试）
    private String customQualityRes = "";   // 非原画时的分辨率
    private int customQualityBitrate = 0;   // 非原画时的码率
    private String customPlayLink = "";     // 非原画时的 play_link
    private static final String TAG = "Player";

    private static final int[] RATIO_MODES = {0, 1, 2};
    private static final String[] RATIO_LABELS = {"适应", "拉伸", "缩放"};
    private String actualVideoDecoder = "";
    private String actualAudioDecoder = "";
    // 流 API 探测数据
    private String streamVCodec = "", streamVProfile = "", streamVPixFmt = "", streamVColor = "", streamVFps = "";
    private int streamVWidth = 0, streamVHeight = 0, streamVBitDepth = 0;
    private boolean streamVHdr = false;
    private long streamFileSize = 0;
    private int streamDuration = 0; // 秒
    private String streamContainer = "";
    private String streamResolution = "";
    private boolean hdrNotified = false; // HDR 已提示过一次
    private boolean firstReady = true;   // 首次进入 READY（用于控制初始 UI 显示）
    private java.util.List<StreamResponse.AudioStreamInfo> streamAudioTracks;
    private java.util.List<StreamResponse.SubtitleStreamInfo> streamSubtitleTracks;
    /** 本次播放会话是否已尝试自动加载飞牛外挂字幕（画质切换/换集时重置） */
    private boolean autoSubTried = false;
    private MPVEventObserver eventObserver;   // mpv 事件监听器
    private Runnable loadTimeoutR;            // 加载超时检测任务


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // API 21+: 透明系统栏，实现真正的沉浸式全屏
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
        // API 28+: 允许内容延伸到刘海/挖孔区域，避免横屏下 cutout inset 把画面顶出黑边
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        setContentView(R.layout.activity_player);

        // 视频始终基于完整屏幕居中显示；刘海/导航栏安全区只约束控制 UI，
        // 不作为 mpv 视频边距，否则横屏左侧刘海会把画面整体推向右侧。

        apiManager = FnApiManager.getInstance();
        SharedPreferences prefs = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        baseUrl = prefs.getString("host", "").replaceAll("/+$", "");
        // 进程被杀后恢复任务时可能直接重建 PlayerActivity，此时 apiService 尚未初始化，
        // 需在此兜底构建（与 HomeActivity 保持一致），否则 getApi() 返回 null 导致 NPE
        if (apiManager.getApi() == null && !baseUrl.isEmpty()) {
            apiManager.updateBaseUrl(baseUrl);
        }
        isHwDecode = "hardware".equals(prefs.getString("decoder_mode", "hardware"));

        itemGuid = getIntent().getStringExtra("guid");
        seekTs = getIntent().getLongExtra("ts", 0) * 1000L;
        itemDuration = getIntent().getLongExtra("duration", 0);
        itemTitle = getIntent().getStringExtra("title");
        itemTV = getIntent().getStringExtra("tv_title");
        itemPoster = getIntent().getStringExtra("poster");
        itemCategory = getIntent().getStringExtra("category");
        parentGuid = getIntent().getStringExtra("parent_guid");

        playerView = findViewById(R.id.playerView);
        tvBuffering = findViewById(R.id.tvBuffering);
        tvTime = findViewById(R.id.tvTime);
        seekBar = findViewById(R.id.seekBar);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnRewind = findViewById(R.id.btnRewind);
        btnForward = findViewById(R.id.btnForward);
        btnSpeed = findViewById(R.id.btnSpeed);
        btnSpeed.setText(formatSpeed(1.0f));
        btnRatio = findViewById(R.id.btnRatio);
        btnInfo = findViewById(R.id.btnInfo);
        btnQuality = findViewById(R.id.btnQuality);
        btnEpisodeList = findViewById(R.id.btnEpisodeList);
        btnBack = findViewById(R.id.btnBack);
        btnDanmu = findViewById(R.id.btnDanmu);
        danmuView = findViewById(R.id.danmuView);
        btnLock = (ImageView) findViewById(R.id.btnLock);
        tvTitle = findViewById(R.id.tvTitle);
        tvDanmuStatus = findViewById(R.id.tvDanmuStatus);
        btnCloudMode = findViewById(R.id.btnCloudMode);
        btnSettings = findViewById(R.id.btnSettings);
        tvDanmuMatch = findViewById(R.id.tvDanmuMatch);
        // 纯图标按钮：Button 不支持 android:src，统一用复合图标设置（Material Symbols）
        // 选集/弹幕/倍速/比例/音频/字幕/超分/原画 已改为底部纯文字选项，不再设图标
        setButtonIcon(btnBack, R.drawable.ic_back);
        setButtonIcon(btnInfo, R.drawable.ic_more);
        setButtonIcon(btnSettings, R.drawable.ic_settings);
        setButtonIcon(btnRewind, R.drawable.ic_rewind);
        setButtonIcon(btnForward, R.drawable.ic_next);
        setButtonIcon(btnPlayPause, R.drawable.ic_pause);
        tvSpeedHint = findViewById(R.id.tvSpeedHint);
        topBar = findViewById(R.id.topBar);
        controller = findViewById(R.id.controller);
        controller.setOnTouchListener((v, e) -> true);
        topBar.setOnTouchListener((v, e) -> true);
        // 信息面板已改为右侧抽屉（showInfoDrawer），不再有常驻视图
        infoText = null;
        infoTextAudio = null;
        infoTextExtra = null;

        // 字幕管理器（mpv 由 libass 原生渲染 ASS/SSA，无需 Java 层覆层）
        subtitleManager = new SubtitleManager();
        // Anime4K 超分管理器（着色器懒复制到 filesDir/shaders/）
        anime4kManager = new Anime4KManager(this);

        // 底部设置面板管理器（面板视图已在 activity_player.xml 中 include；
        // 弹幕/字幕设置已迁移为 SideDrawerHelper 右侧抽屉，不再使用 in-layout 面板）
        settingsPanelManager = new SettingsPanelManager(this, new SettingsPanelManager.Views(
                findViewById(R.id.settingsPanel),
                findViewById(R.id.settingsBackdrop),
                findViewById(R.id.tabContentPlay),
                findViewById(R.id.tabContentQuality),
                findViewById(R.id.tabContentSubtitle),
                findViewById(R.id.tabContentDanmu),
                findViewById(R.id.tabPlay), findViewById(R.id.tabQuality),
                findViewById(R.id.tabSubtitle), findViewById(R.id.tabDanmu),
                findViewById(R.id.btnCloseSettings)),
                new SettingsPanelManager.OnPanelStateChangeListener() {
                    @Override public void onSettingsPanelChanged(boolean open, int currentTab) {
                        if (open) {
                            syncSettingsPanelState();
                            focusPanelTab(currentTab);
                        } else if (playerView != null) {
                            playerView.requestFocus();
                        }
                    }
                    @Override public void onDanmuPanelChanged(boolean open) {
                        if (open) {
                            View content = settingsPanelManager.getDanmuPanelContent();
                            if (content != null) {
                                danmuManager.bindSettingsPanel(content,
                                        () -> settingsPanelManager.closeDanmuPanel());
                            }
                        } else if (playerView != null) {
                            playerView.requestFocus();
                        }
                    }
                });

        initPlayer();

        danmuManager = new DanmuManager(this, new DanmuManager.DataProvider() {
            @Override public MPVTimeSource getTimeSource() { return PlayerActivity.this; }
            @Override public long getItemDuration() { return itemDuration; }
            @Override public String getItemTV() { return itemTV; }
            @Override public String getItemTitle() { return itemTitle; }
            @Override public String getItemGuid() { return itemGuid; }
            @Override public String getParentGuid() { return parentGuid; }
        }, danmuView, tvDanmuStatus, tvDanmuMatch, btnDanmu, prefs);
        danmuManager.initFromPrefs();
        // 弹幕开启状态：同步按钮颜色
        if (danmuManager != null) danmuManager.updateButtonState();

        // 底部设置面板 / 弹幕面板 / 字幕面板控件接线
        initSettingsPanelControls();

        // 单击屏幕切换控制栏显隐（长按倍速由 PlayerGestureHelper 处理）
        findViewById(android.R.id.content).setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, android.view.MotionEvent event) {
                if (isLocked) {
                    if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                        showCtrl(true);
                    }
                    return true;
                }
                if (event.getAction() == android.view.MotionEvent.ACTION_UP
                        || event.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                    // 长按倍速手势窗口内不切 UI（本次 UP 与倍速手势交错会多弹一次 UI）
                    if (!speedGestureActive) {
                        if (ctrlVis) {
                            showCtrl(false);
                        } else {
                            showCtrl(true);
                        }
                    }
                }
                return true;
            }
        });

        episodeManager = new EpisodeManager(new EpisodeManager.Callback() {
            @Override public String getBaseUrl() { return baseUrl; }
            @Override public String getParentGuid() { return parentGuid; }
            @Override public String getItemGuid() { return itemGuid; }
            @Override public int getEpisodeNumber() { return getIntent().getIntExtra("episode_number", 0); }
            @Override public FnApiManager getApiManager() { return apiManager; }
            @Override public Context getContext() { return PlayerActivity.this; }
            @Override public void onSwitchEpisode(String guid, String title) {
                introSkipped = false;
                outroSkipped = false;
                itemGuid = guid;
                itemTitle = title;
                mediaGuid = null;
                seekTs = 0;
                episodeManager.reset();
                loadPlayInfo();
            }
        }, btnEpisodeList, btnRewind, btnForward);

        btnPlayPause.setOnClickListener(v -> togglePlay());
        seekStep = getSharedPreferences("fntv_prefs", MODE_PRIVATE).getInt("seek_step", 10) * 1000;
        btnRewind.setOnClickListener(v -> episodeManager.playPrev());
        btnForward.setOnClickListener(v -> episodeManager.playNext());
        btnSpeed.setOnClickListener(v -> cycleSpeed());
        btnRatio.setOnClickListener(v -> cycleRatio());
        btnInfo.setOnClickListener(v -> toggleInfo());
        qualityHelper = new QualitySelectHelper(this, apiManager, getSharedPreferences("fntv_prefs", MODE_PRIVATE),
                new QualitySelectHelper.QualityCallback() {
                    @Override public void onQualityChanged(int level) {
                        customQualityRes = "";
                        customQualityBitrate = 0;
                        customPlayLink = "";
                        if (btnQuality != null) btnQuality.setText(qualityHelper.getCurrentLabel());
                        if (cloudStreamManager != null) {
                            getSharedPreferences("fntv_prefs", MODE_PRIVATE)
                                    .edit().putInt("stream_quality_level", level).apply();
                            cloudStreamManager.reloadPlayback();
                        }
                    }
                    @Override public void onPlayLinkChanged(String playLink, String res, int bps) {
                        customQualityRes = res;
                        customQualityBitrate = bps;
                        customPlayLink = playLink;
                        if (btnQuality != null) btnQuality.setText(qualityHelper.getCurrentLabel());
                        // 记录切换前的播放位置（毫秒），mpv 通过 loadfile start= 直接续播
                        final long seekPosMs = playerView != null ? Math.max(0, playerView.getCurrentPosition()) : 0;
                        String fullUrl = baseUrl + playLink;
                        Log.d(TAG, "画质切换新链接: " + fullUrl + " res=" + res + " bitrate=" + bps + " seek=" + seekPosMs);
                        if (playerView != null && playerView.isMpvReady()) {
                            savedPlaybackUrl = fullUrl;
                            useHls = fullUrl.contains(".m3u8");
                            autoSubTried = false; // 换链接重载后外挂字幕轨会被清掉，允许重新自动选择
                            playerView.setHttpHeaders(buildStreamHttpHeaders(fullUrl));
                            playerView.loadFile(fullUrl, seekPosMs);
                            playerView.setPause(false);
                        }
                    }
                    @Override public String getMediaGuid() { return mediaGuid; }
                    @Override public String getAccount() {
                        return getSharedPreferences("fntv_prefs", MODE_PRIVATE).getString("user", "video");
                    }
                    @Override public long getPlaybackPosition() {
                        return playerView != null ? playerView.getCurrentPosition() / 1000 : 0;
                    }
                });
        if (btnQuality != null) {
            btnQuality.setOnClickListener(v -> qualityHelper.showQualityDialog());
            // 初始检查：如果右上角直链按钮已显示，隐藏画质按钮
            if (btnCloudMode.getVisibility() == View.VISIBLE) {
                btnQuality.setVisibility(View.GONE);
            }
        }
        btnBack.setOnClickListener(v -> { restoreOrientation(); finish(); });
        btnDanmu.setOnClickListener(v -> settingsPanelManager.toggleDanmuPanel());
        btnLock.setOnClickListener(v -> applyLockState(!isLocked));
        btnBrightness = findViewById(R.id.btnBrightness);
        if (btnBrightness != null) {
            btnBrightness.setOnClickListener(v -> showBrightnessDialog());
            setButtonIcon(btnBrightness, R.drawable.ic_brightness);
        }
        // 跳过设置入口已整合进设置面板（播放 Tab btnSkipEntry）
        // 应用保存的亮度和 HDR 设置
        int savedBright = getSharedPreferences("fntv_prefs", MODE_PRIVATE).getInt("video_brightness", 100);
        if (savedBright != 100) applyBrightness(savedBright);
        applyHdrMode();
        btnEpisodeList.setOnClickListener(v -> episodeManager.showPicker());

        cloudStreamManager = new CloudStreamManager(new CloudStreamManager.Callback() {
            @Override public String getBaseUrl() { return baseUrl; }
            @Override public String getMediaGuid() { return mediaGuid; }
            @Override public FnApiManager getApiManager() { return apiManager; }
            @Override public Context getContext() { return PlayerActivity.this; }
            @Override public SharedPreferences getPrefs() { return getSharedPreferences("fntv_prefs", MODE_PRIVATE); }
            @Override public void onStreamInfoParsed(CloudStreamManager.StreamInfo info) {
                streamBitrate = info.bitrate;
                streamVCodec = info.vCodec;
                streamVProfile = info.vProfile;
                streamVWidth = info.width;
                streamVHeight = info.height;
                streamVBitDepth = info.bitDepth;
                streamVHdr = info.vHdr;
                streamVPixFmt = info.vPixFmt;
                streamVColor = info.vColor;
                streamVFps = info.vFps;
                streamDuration = info.duration;
                streamFileSize = info.fileSize;
                streamContainer = info.container;
                streamResolution = info.resolution != null ? info.resolution : "";
                streamAudioTracks = info.audioTracks;
                streamSubtitleTracks = info.subtitleTracks;
                if (streamVCodec.isEmpty() || streamContainer.isEmpty()) {
                    probeWithMediaExtractor();
                }
            }
            @Override public void onStreamDataFailed() { startPlayback(); }
            @Override public void startPlayback() { PlayerActivity.this.startPlayback(); }
            @Override public void onTrackChanged() {
                // mpv 切轨后 audio-codec-name 会更新，轮询比对以刷新信息面板
                final String oldCodec = playerView != null ? playerView.getAudioCodec() : null;
                final int[] tries = {6};
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (playerView == null || !playerView.isMpvReady()) return;
                        String newCodec = playerView.getAudioCodec();
                        if (newCodec != null && !newCodec.equals(oldCodec)) {
                            updateInfo();
                        } else if (tries[0] > 0) {
                            tries[0]--;
                            handler.postDelayed(this, 500);
                        } else {
                            updateInfo();
                        }
                    }
                });
            }
            @Override public void reloadPlayback() {
                mediaGuid = null;
                seekTs = 0;
                cloudStreamManager.resetForQualitySwitch();
                loadPlayInfo();
            }
            @Override public void probeWithMediaExtractor() { PlayerActivity.this.probeWithMediaExtractor(); }
            @Override public void onCloudBtnVisibilityChanged(boolean vis) {
                // tvDanmuMatch 已锚定在顶部栏跳过按钮左侧，与直链按钮不再重叠
                // 直链/STRM 按钮显示时，隐藏画质按钮
                if (vis && btnQuality != null) btnQuality.setVisibility(View.GONE);
            }
            @Override public void pickExternalSubtitleFile() { PlayerActivity.this.pickExternalSubtitle(); }
            @Override public void showSubtitleStylePanel() { PlayerActivity.this.showSubtitleStyleDialog(); }
            @Override public boolean loadExternalSubtitleBytes(byte[] bytes, String fileName) {
                return PlayerActivity.this.loadExternalSubtitleBytes(bytes, fileName);
            }
            @Override public void runOnUiThread(Runnable r) { PlayerActivity.this.runOnUiThread(r); }
        }, btnCloudMode, getSharedPreferences("fntv_prefs", MODE_PRIVATE));
        cloudStreamManager.initFromPrefs();
        cloudStreamManager.setPlayerView(playerView);

        // 顶部栏焦点链：右端直链按钮向下到锁定按钮，返回键向下进进度条
        // （弹幕入口已移至底部文字选项行，见 activity_player.xml）
        btnCloudMode.setNextFocusDownId(btnLock.getId());
        btnLock.setNextFocusUpId(btnCloudMode.getId());
        btnBack.setNextFocusDownId(seekBar.getId());

        // 设置按钮点击 → 打开底部设置面板
        btnSettings.setOnClickListener(v -> settingsPanelManager.toggleSettingsPanel());

        // 音轨/字幕/超分/画质：已移至底部文字选项行（activity_player.xml），在此接线
        Button btnAudioTrack = findViewById(R.id.btnAudioTrack);
        Button btnSubtitleTrack = findViewById(R.id.btnSubtitleTrack);
        if (btnAudioTrack != null) {
            btnAudioTrack.setOnClickListener(v -> cloudStreamManager.showAudioTrackDialog(PlayerActivity.this));
        }
        if (btnSubtitleTrack != null) {
            btnSubtitleTrack.setOnClickListener(v -> cloudStreamManager.showSubtitleTrackDialog(PlayerActivity.this));
        }
        // Anime4K 超分设置（底部文字选项行「超分」）
        Button btnAnime4K = findViewById(R.id.btnAnime4K);
        if (btnAnime4K != null) {
            btnAnime4K.setOnClickListener(v -> showAnime4KDialog());
        }

        setupFocusAutoHide();

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser && playerView != null) {
                    // 立即更新 UI（时间显示）
                    setTimeText(p, playerView.getDuration());
                    if (tvSeekOverlay.getVisibility() == View.VISIBLE) {
                        tvSeekOverlay.setText(FormatUtils.fmt(p) + " / " + FormatUtils.fmt(playerView.getDuration()));
                    }
                    // 防抖：停止操作 1s 后才真正 seek，避免按住时大量请求
                    if (seekCommitR != null) handler.removeCallbacks(seekCommitR);
                    pendingSeekMs = p;
                    seekCommitR = () -> {
                        if (playerView != null && playerView.isMpvReady()) {
                            playerView.seekTo(p);
                            if (danmuManager != null) danmuManager.onSeekTo(p);
                        }
                        pendingSeekMs = -1;
                    };
                    handler.postDelayed(seekCommitR, 1000);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {
                // 触摸松开时立即执行最后的 seek
                pendingSeekMs = -1;
                if (seekCommitR != null) handler.removeCallbacks(seekCommitR);
                if (playerView != null && playerView.isMpvReady() && sb.getProgress() >= 0) {
                    playerView.seekTo(sb.getProgress());
                    if (danmuManager != null) danmuManager.onSeekTo(sb.getProgress());
                }
            }
        });

        showCtrl(true);
        loadPlayInfo();
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        hideSystemUi();

        // 初始化手势指示浮层（从 view_gesture_overlay 获取子视图引用）
        gestureOverlay = findViewById(R.id.gestureOverlay);
        if (gestureOverlay != null) {
            gestureBrightness = gestureOverlay.findViewById(R.id.gestureBrightness);
            gestureVolume = gestureOverlay.findViewById(R.id.gestureVolume);
            gestureSeek = gestureOverlay.findViewById(R.id.gestureSeek);
            gestureDoubleTap = gestureOverlay.findViewById(R.id.gestureDoubleTap);
        }

        // 初始化加载状态浮层
        loadingOverlay = findViewById(R.id.loadingOverlay);
        loadingText = loadingOverlay != null ? loadingOverlay.findViewById(R.id.loadingText) : null;
        btnRetry = loadingOverlay != null ? loadingOverlay.findViewById(R.id.btnRetry) : null;
        if (btnRetry != null) {
            btnRetry.setOnClickListener(v -> {
                mediaGuid = null;
                seekTs = 0;
                loadingOverlay.setVisibility(View.GONE);
                loadPlayInfo();
            });
        }

        // 初始化手势控制（仅触屏设备）
        gestureHelper = new PlayerGestureHelper(this, new PlayerGestureHelper.GestureCallback() {
            // 系统音量已由 helper 通过 AudioManager 应用，mpv 内部 volume 保持 100 不动
            @Override public void onVolumeChange(int percent) {
                runOnUiThread(() -> showGestureIndicator(PlayerGestureHelper.GestureType.VOLUME, percent, percent + "%"));
            }
            // 屏幕亮度已由 helper 通过 Window 属性应用
            @Override public void onBrightnessChange(int percent) {
                runOnUiThread(() -> showGestureIndicator(PlayerGestureHelper.GestureType.BRIGHTNESS, percent, percent + "%"));
            }
            /**
             * 横滑进度回调：实时更新 Seek 预览浮层位置与目标时间
             * @param fraction 滑动比例（dx / 视图宽度），正=快进，负=快退
             * @param x        手指当前 X 坐标
             * @param y        手指当前 Y 坐标
             */
            @Override public void onProgressChange(float fraction, float x, float y) {
                if (gestureSeekBase < 0 || playerView == null) return;
                long dur = playerView.getDuration();
                long target = gestureSeekBase + (long) (fraction * GESTURE_SEEK_SPAN_MS);
                if (dur > 0) target = Math.min(dur, target);
                gestureSeekTarget = Math.max(0, target);
                showSeekPreview(fraction, gestureSeekTarget, x, y);
            }
            @Override public void onGestureStart() {
                if (ctrlVis) showCtrl(false);
                gestureSeekBase = (playerView != null && playerView.isMpvReady())
                        ? playerView.getCurrentPosition() : -1;
                gestureSeekTarget = -1;
            }
            @Override public void onGestureEnd() {
                if (gestureSeekTarget >= 0 && playerView != null && playerView.isMpvReady()) {
                    playerView.seekTo(gestureSeekTarget);
                    if (danmuManager != null) danmuManager.onSeekTo(gestureSeekTarget);
                    updateTime();
                }
                gestureSeekBase = -1;
                gestureSeekTarget = -1;
                hideSeekPreview();
            }
            @Override public void onSingleTap() {
                runOnUiThread(() -> {
                    // 长按倍速手势窗口内不切 UI（单击确认回调与倍速触发交错会多弹一次 UI）
                    if (speedGestureActive) return;
                    if (isLocked) {
                        showCtrl(true);
                    } else {
                        showCtrl(!ctrlVis);
                    }
                });
            }
            @Override public void onDoubleTap(float xRatio) {
                // 双击任意区域统一为 暂停/继续（快进快退手势已移除）
                runOnUiThread(() -> {
                    togglePlay();
                    showDoubleTapIndicator();
                });
            }
            /**
             * 长按倍速状态变化
             * @param active true=开始长按（临时倍速），false=松开恢复原速
             */
            @Override public void onLongPressSpeed(boolean active) {
                runOnUiThread(() -> {
                    if (active) {
                        float gs = getSharedPreferences("fntv_prefs", MODE_PRIVATE)
                                .getFloat("gesture_speed", 2.0f);
                        speedBeforeLongPress = playerView.getPlaybackSpeed();
                        playerView.setPlaybackSpeed(gs);
                        danmuView.setPlaybackSpeed(gs);
                        // 倍速手势生效：窗口内抑制单击切 UI（含本次手势前残留的单击确认回调）
                        speedGestureActive = true;
                        handler.removeCallbacks(speedGestureUiGuardClear);
                        showCtrl(false);
                        if (tvSpeedHint != null) {
                            tvSpeedHint.setText(formatSpeed(gs) + " 倍速播放中");
                            tvSpeedHint.setVisibility(View.VISIBLE);
                        }
                    } else {
                        playerView.setPlaybackSpeed(speedBeforeLongPress);
                        danmuView.setPlaybackSpeed(speedBeforeLongPress);
                        if (tvSpeedHint != null) tvSpeedHint.setVisibility(View.GONE);
                        // 松开后保留一小段抑制窗口，吞掉与松手交错的单击确认回调（延迟约 300ms）
                        handler.removeCallbacks(speedGestureUiGuardClear);
                        handler.postDelayed(speedGestureUiGuardClear, SPEED_GESTURE_UI_GUARD_MS);
                    }
                });
            }

            private final Runnable speedGestureUiGuardClear = () -> speedGestureActive = false;
            @Override public void onThreeFingerUp() {
                Log.d(TAG, "三指上滑：弹幕设置（待 Phase 2）");
            }
            @Override public void onThreeFingerDown() {
                Log.d(TAG, "三指下滑：字幕设置（待 Phase 2）");
            }
            @Override public void showGestureIndicator(PlayerGestureHelper.GestureType type, int percent, String label) {
                runOnUiThread(() -> {
                    hideAllGestureIndicators();
                    switch (type) {
                        case VOLUME:
                            if (gestureVolume != null) {
                                gestureVolume.setVisibility(View.VISIBLE);
                                GestureIndicatorView ring = gestureVolume.findViewById(R.id.gestureRingVolume);
                                TextView icon = gestureVolume.findViewById(R.id.gestureIconVolume);
                                TextView value = gestureVolume.findViewById(R.id.gestureValueVolume);
                                if (ring != null) ring.setProgress(percent / 100f);
                                if (icon != null) icon.setText("\uD83D\uDD0A");
                                if (value != null) value.setText(label);
                            }
                            break;
                        case BRIGHTNESS:
                            if (gestureBrightness != null) {
                                gestureBrightness.setVisibility(View.VISIBLE);
                                GestureIndicatorView ring = gestureBrightness.findViewById(R.id.gestureRingBrightness);
                                TextView icon = gestureBrightness.findViewById(R.id.gestureIconBrightness);
                                TextView value = gestureBrightness.findViewById(R.id.gestureValueBrightness);
                                if (ring != null) ring.setProgress(percent / 100f);
                                if (icon != null) icon.setText("\u2600\uFE0F");
                                if (value != null) value.setText(label);
                            }
                            break;
                        case PROGRESS:
                            // Seek 预览由 showSeekPreview 处理
                            break;
                    }
                });
            }
            @Override public void hideGestureIndicator() {
                runOnUiThread(() -> {
                    if (gestureVolume != null) gestureVolume.setVisibility(View.GONE);
                    if (gestureBrightness != null) gestureBrightness.setVisibility(View.GONE);
                });
            }
            @Override public void showSeekPreview(float dx, long positionMs, float x, float y) {
                runOnUiThread(() -> {
                    if (gestureSeek == null || gestureOverlay == null) return;
                    gestureSeek.setVisibility(View.VISIBLE);
                    GestureIndicatorView ring = gestureSeek.findViewById(R.id.gestureRingSeek);
                    TextView icon = gestureSeek.findViewById(R.id.gestureIconSeek);
                    TextView value = gestureSeek.findViewById(R.id.gestureValueSeek);
                    if (ring != null) ring.setProgress(0.5f);
                    if (icon != null) icon.setText("\u23F1\uFE0F");
                    if (value != null && gestureSeekBase >= 0) {
                        long deltaSec = (positionMs - gestureSeekBase) / 1000;
                        value.setText(FormatUtils.fmtTime((int)(positionMs / 1000))
                                + (deltaSec >= 0 ? " +" : " -") + Math.abs(deltaSec) + "s");
                    }
                    // 定位到手指位置上方
                    int px = (int) x;
                    int py = Math.max(60, (int) y - 80);
                    gestureSeek.setX(px);
                    gestureSeek.setY(py);
                });
            }
            @Override public void hideSeekPreview() {
                runOnUiThread(() -> {
                    if (gestureSeek != null) gestureSeek.setVisibility(View.GONE);
                });
            }
        });
        if (gestureHelper.isEnabled()) {
            playerView.setOnTouchListener(gestureHelper);
        }

        // 控制栏隐藏时的进度时间浮层
        tvSeekOverlay = new TextView(this);
        tvSeekOverlay.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((FrameLayout.LayoutParams) tvSeekOverlay.getLayoutParams()).gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        tvSeekOverlay.setPadding(32, 16, 32, 16);
        tvSeekOverlay.setTextColor(Color.WHITE);
        tvSeekOverlay.setTextSize(22);
        tvSeekOverlay.setBackgroundColor(0x88000000);
        tvSeekOverlay.setVisibility(View.GONE);
        ((FrameLayout) findViewById(android.R.id.content)).addView(tvSeekOverlay);

        // 初始焦点给视频区域，始终由 playerView 持有焦点
        playerView.setFocusable(true);
        playerView.requestFocus();
    }

    private void initPlayer() {
        // 强制最高刷新率（Android 11+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Window win = getWindow();
            if (win != null) {
                WindowManager.LayoutParams lp = win.getAttributes();
                Display.Mode[] modes = getWindowManager().getDefaultDisplay().getSupportedModes();
                float maxRefresh = 60f;
                for (Display.Mode m : modes) {
                    if (m.getRefreshRate() > maxRefresh) maxRefresh = m.getRefreshRate();
                }
                lp.preferredDisplayModeId = 0;
                for (Display.Mode m : modes) {
                    if (m.getRefreshRate() == maxRefresh) {
                        lp.preferredDisplayModeId = m.getModeId();
                        break;
                    }
                }
                win.setAttributes(lp);
            }
        }
        // mpv 实例初始化（必须在 loadFile 之前）
        isHwDecode = !"software".equals(getSharedPreferences("fntv_prefs", MODE_PRIVATE)
                .getString("decoder_mode", "hardware"));
        playerView.initializeMpv(this, isHwDecode);
        playerView.setKeepScreenOn(true);
        // 应用已保存的字幕样式偏好（字体由 mpv 的 ass-force-font-family 处理）
        applySubtitlePrefs();
        // 恢复已保存的 Anime4K 超分设置
        applyAnime4KPrefs();

        // mpv 事件监听
        eventObserver = new MPVEventObserver();
        eventObserver.setPlaybackListener(new MPVEventObserver.PlaybackListener() {
            @Override public void onPlaybackStart() {
                cancelLoadTimeout();
                loadingOverlay.setVisibility(View.GONE);
                btnRetry.setVisibility(View.GONE);
                tvBuffering.setVisibility(View.GONE);
                actualVideoDecoder = playerView.getCurrentHwdec();
                if (actualVideoDecoder == null) actualVideoDecoder = "";
                startSave();
                updateTime();
                updateInfo();
                if (firstReady) { saveProgress(); showCtrl(true); firstReady = false; }
                // 播放/暂停图标已由 setPlayPauseIcon 更新
                if (danmuManager != null) danmuManager.onPlayerReady();
                checkHdr();
                Log.d(TAG, "音轨: codec=" + playerView.getAudioCodec()
                        + " 视频: " + playerView.getVideoCodec()
                        + " hwdec=" + playerView.getCurrentHwdec());
                autoSelectSubtitle();
                skipIntroIfNeeded();
            }

            @Override public void onPlaybackPause(boolean paused) {
                setPlayPauseIcon(!paused);
                if (paused) {
                    stopSave();
                    if (danmuManager != null) danmuManager.onPlayerPause();
                } else {
                    startSave();
                    updateTime();
                    if (danmuManager != null) danmuManager.onPlayerReady();
                }
            }

            @Override public void onPlaybackEnd() {
                Log.d(TAG, "播放结束 hasNext=" + (episodeManager != null && episodeManager.hasNext()));
                saveProgress();
                reportWatched();
                if (episodeManager != null && episodeManager.hasNext()
                        && getSharedPreferences("fntv_prefs", MODE_PRIVATE).getBoolean("auto_next", true)) {
                    episodeManager.playNext();
                }
            }

            @Override public void onBufferingChange(boolean buffering) {
                tvBuffering.setVisibility(buffering ? View.VISIBLE : View.GONE);
                if (buffering) {
                    if (firstReady) {
                        loadingOverlay.setVisibility(View.VISIBLE);
                        loadingText.setText("加载中...");
                        btnRetry.setVisibility(View.GONE);
                    }
                } else {
                    loadingOverlay.setVisibility(View.GONE);
                }
            }

            @Override public void onVideoParamsChanged(int w, int h, float fps) {
                Log.d(TAG, "视频参数: " + w + "x" + h + "@" + fps);
                updateInfo();
            }
        });
        eventObserver.setProgressListener((positionMs, durationMs) -> {
            if (danmuManager != null) danmuManager.setPlayTime(positionMs / 1000);
        });
        MPVLib.addObserver(eventObserver);
    }

    /** 片头跳过（每次起播只触发一次，片尾在 updateTime 实时监测） */
    private void skipIntroIfNeeded() {
        if (introSkipped || (parentGuid == null && (itemTV == null || itemTV.isEmpty()))) return;
        SharedPreferences sp = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        String skipId = parentGuid != null && !parentGuid.isEmpty() ? parentGuid : itemTV;
        int introSec = sp.getInt("skip_" + skipId + "_intro", 0);
        if (introSec > 0) {
            int pos = (int) (playerView.getCurrentPosition() / 1000);
            if (pos < introSec) {
                playerView.seekTo(introSec * 1000L);
                if (danmuManager != null) danmuManager.showDanmuStatus("跳过片头 " + introSec + "秒");
            }
            introSkipped = true;
        }
    }

    /** 启动加载超时检测：超时未收到 mpv FILE_LOADED 则显示失败提示 */
    private void startLoadTimeout() {
        cancelLoadTimeout();
        loadTimeoutR = () -> {
            loadingOverlay.setVisibility(View.VISIBLE);
            loadingText.setText("加载失败，请检查网络");
            btnRetry.setVisibility(View.VISIBLE);
        };
        handler.postDelayed(loadTimeoutR, 20000);
    }

    /** 取消加载超时检测 */
    private void cancelLoadTimeout() {
        if (loadTimeoutR != null) {
            handler.removeCallbacks(loadTimeoutR);
            loadTimeoutR = null;
        }
    }
    private void loadPlayInfo() {
        hdrNotified = false;
        Map<String, String> b = new HashMap<>(); b.put("item_guid", itemGuid);
        Log.d(TAG, "play/info 请求: " + new com.google.gson.Gson().toJson(b));
        apiManager.getApi().getPlayInfo(b).enqueue(new retrofit2.Callback<ApiResponse<PlayInfoResponse>>() {
            @Override public void onResponse(retrofit2.Call<ApiResponse<PlayInfoResponse>> call,
                                             retrofit2.Response<ApiResponse<PlayInfoResponse>> r) {
                if (r.isSuccessful() && r.body() != null && r.body().code == 0 && r.body().data != null) {
                    PlayInfoResponse info = r.body().data;
                    mediaGuid = info.mediaGuid; videoGuid = info.videoGuid; audioGuid = info.audioGuid;
                    if (info.guid != null && !info.guid.isEmpty()) itemGuid = info.guid;
                    if (info.parentGuid != null && !info.parentGuid.isEmpty()) parentGuid = info.parentGuid;
                    Log.d(TAG, "play/info 返回: type=" + info.getClass().getSimpleName()
                            + " guid=" + info.guid
                            + " mediaGuid=" + info.mediaGuid
                            + " audioGuid='" + info.audioGuid + "'"
                            + " videoGuid=" + info.videoGuid
                            + " subtitleGuid=" + info.subtitleGuid
                            + " raw=" + new com.google.gson.Gson().toJson(info));
                    // 从 intent 的 parent_guid 兜底（详情页传递的）
                    if (parentGuid == null || parentGuid.isEmpty()) {
                        parentGuid = getIntent().getStringExtra("parent_guid");
                    }
                    subtitleGuid = info.subtitleGuid != null ? info.subtitleGuid : "_no_display_";
                    if (info.item != null && info.item.tvTitle != null) itemTV = info.item.tvTitle;
                    if (info.item != null) itemTitle = info.item.title;
                    if (info.item != null && info.item.seasonNumber > 0) seasonNumber = info.item.seasonNumber;
                    if (info.item != null) getIntent().putExtra("episode_number", info.item.episodeNumber);
                    int epNum = info.item != null ? info.item.episodeNumber : 0;
                    String matchName = itemTV != null && !itemTV.isEmpty() ? itemTV : itemTitle;
                    if (matchName != null && !matchName.isEmpty() && epNum > 0) {
                        matchName = matchName + " S" + String.format("%02d", seasonNumber) + "E" + String.format("%02d", epNum);
                    }
                    if (danmuManager != null) danmuManager.loadDanmu(matchName, itemGuid);
                    if (info.item != null && info.item.mediaStream != null
                            && info.item.mediaStream.resolutions != null
                            && !info.item.mediaStream.resolutions.isEmpty())
                        resolution = info.item.mediaStream.resolutions.get(0);

                    // 直播频道：直接从 live_channels 取第一个流地址播放
                    if (info.liveChannels != null && !info.liveChannels.isEmpty()) {
                        String liveUrl = info.liveChannels.get(0).path;
                        Log.d(TAG, "直播频道播放地址: " + liveUrl);
                        playLiveStream(liveUrl);
                        tvTitle.setText(itemTitle != null ? itemTitle : "直播");
                        return;
                    }

                    // 获取直链信息，获取完后开始播放
                    cloudStreamManager.fetchDirectLink(itemGuid, mediaGuid);
                }
            }
            @Override public void onFailure(retrofit2.Call<ApiResponse<PlayInfoResponse>> call, Throwable t) {}
        });
    }

    /** 开始播放（加载到 mpv） */
    private void startPlayback() {
        if (mediaGuid == null || playerView == null) return;
        autoSubTried = false; // 每次起播允许自动选一次字幕
        // 视频按完整屏幕居中；刘海/导航栏安全区仅约束控制 UI，不偏移视频画面
        playerView.setVideoMarginRatio(0, 0, 0, 0);
        // 恢复上次倍速偏好
        restoreSavedSpeed();
        CloudStreamManager.PlaybackConfig cfg = cloudStreamManager.getPlaybackConfig(baseUrl, mediaGuid);
        useHls = cfg.hls;
        savedPlaybackUrl = cfg.url;
        // mpv 原生网络层不走 OkHttp 拦截器，需手动注入签名鉴权头（等效旧 AuthInterceptor）
        playerView.setHttpHeaders(buildStreamHttpHeaders(cfg.url));
        playerView.loadFile(cfg.url, seekTs);
        startLoadTimeout();
        Log.d(TAG, "startPlayback: url=" + cfg.url + " hls=" + useHls + " seekTs=" + seekTs
                + " parentGuid=" + parentGuid
                + " episodeLoaded=" + (episodeManager != null && episodeManager.isLoaded())
                + " loadingEp=" + (episodeManager != null && episodeManager.isLoading()));
        if (parentGuid != null && !parentGuid.isEmpty() && episodeManager != null && !episodeManager.isLoaded() && !episodeManager.isLoading())
            episodeManager.loadList(parentGuid);
    }

    /**
     * 为 mpv 网络层构建请求头。
     * NAS 代理地址（media/range 等）需要 Authx 签名 + mode=relay Cookie + Authorization，
     * 与旧 OkHttp AuthInterceptor 注入的头保持一致；外部直链只带 UA。
     */
    private String buildStreamHttpHeaders(String url) {
        StringBuilder sb = new StringBuilder();
        sb.append("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        if (url != null && baseUrl != null && !baseUrl.isEmpty() && url.startsWith(baseUrl)) {
            String path;
            String query = null;
            try {
                java.net.URI uri = java.net.URI.create(url);
                path = uri.getRawPath();
                query = uri.getRawQuery();
            } catch (Exception e) {
                path = url;
                int q = url.indexOf('?');
                if (q >= 0) { path = url.substring(0, q); query = url.substring(q + 1); }
            }
            sb.append(",Cookie: mode=relay");
            sb.append(",x-trim-client: web");
            sb.append(",x-trim-client-version: 608");
            sb.append(",Authx: ").append(FnAuthUtils.genAuthx(path, query));
            String token = apiManager != null ? apiManager.getToken() : null;
            if (token != null && !token.isEmpty()) {
                sb.append(",Authorization: ").append(token);
            }
        }
        return sb.toString();
    }

    /** 直播频道播放（直接用 live_channels 返回的地址） */
    private void playLiveStream(String url) {
        if (playerView == null || !playerView.isMpvReady()) return;
        savedPlaybackUrl = url;
        useHls = url.contains(".m3u8");
        playerView.setHttpHeaders(buildStreamHttpHeaders(url));
        playerView.loadFile(url, 0);
        startLoadTimeout();
        Log.d(TAG, "直播: " + (useHls ? "HLS " : "") + url);
    }

    /** 硬解失败后切到软解，mpv 只需切 hwdec 并原位重放 */
    private void recreatePlayerWithSwDecoder() {
        if (playerView == null || !playerView.isMpvReady()) return;
        long pos = Math.max(0, playerView.getCurrentPosition());
        isHwDecode = false;
        playerView.setHwDecode(false);
        if (savedPlaybackUrl != null) {
            playerView.setHttpHeaders(buildStreamHttpHeaders(savedPlaybackUrl));
            playerView.loadFile(savedPlaybackUrl, pos);
            startLoadTimeout();
            Log.d(TAG, "已切软解重放: " + savedPlaybackUrl + " pos=" + pos);
        }
    }

    // ========== 剧集移至 EpisodeManager ==========

    // ========== 控制 ==========

    private void togglePlay() {
        if (playerView == null || !playerView.isMpvReady()) return;
        if (playerView.isPlaying()) {
            playerView.setPause(true);
            setPlayPauseIcon(false);
            if (danmuManager != null) danmuManager.onPlayerPause();
        } else {
            playerView.setPause(false);
            setPlayPauseIcon(true);
            updateTime();
            if (danmuManager != null) danmuManager.onPlayerReady();
        }
    }

    /**
     * 给纯图标无文字的 Button 设置复合图标
     * （Button 不是 ImageView，XML 里的 android:src 会被忽略，需在代码中设置）
     */
    private void setButtonIcon(Button btn, int iconRes) {
        if (btn != null) btn.setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0);
    }

    /**
     * 切换播放/暂停按钮图标（使用 VectorDrawable 而非文字）
     * 约定：按钮显示"下一步动作"——播放中显示暂停图标，暂停中显示播放图标
     * @param playing true=正在播放（显示暂停图标），false=已暂停（显示播放图标）
     */
    private void setPlayPauseIcon(boolean playing) {
        if (btnPlayPause == null) return;
        int iconRes = playing ? R.drawable.ic_pause : R.drawable.ic_play;
        btnPlayPause.setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0);
    }

    /**
     * 显示双击暂停手势指示器（屏幕中央图标 + 600ms 后自动消失）
     */
    private void showDoubleTapIndicator() {
        if (gestureDoubleTap == null) return;
        gestureDoubleTap.setVisibility(View.VISIBLE);
        TextView icon = gestureDoubleTap.findViewById(R.id.gestureIconDoubleTap);
        if (icon != null) {
            icon.setText(playerView != null && playerView.isPlaying() ? "⏸" : "▶️");
        }
        handler.postDelayed(() -> gestureDoubleTap.setVisibility(View.GONE), 600);
    }

    /**
     * 显示双击快进/快退手势指示器（⏪/⏩ + 秒数）
     */
    private void showDoubleTapSeekIndicator(boolean forward) {
        if (gestureDoubleTap == null) return;
        gestureDoubleTap.setVisibility(View.VISIBLE);
        TextView icon = gestureDoubleTap.findViewById(R.id.gestureIconDoubleTap);
        if (icon != null) {
            icon.setText((forward ? "⏩ +" : "⏪ -") + (seekStep / 1000) + "s");
        }
        handler.postDelayed(() -> gestureDoubleTap.setVisibility(View.GONE), 600);
    }

    /**
     * 隐藏所有手势指示器子视图
     */
    private void hideAllGestureIndicators() {
        if (gestureBrightness != null) gestureBrightness.setVisibility(View.GONE);
        if (gestureVolume != null) gestureVolume.setVisibility(View.GONE);
        if (gestureSeek != null) gestureSeek.setVisibility(View.GONE);
    }

    private void seekRel(int ms) {
        if (playerView == null || !playerView.isMpvReady()) return;
        long p = Math.max(0, Math.min(playerView.getDuration(), playerView.getCurrentPosition() + ms));
        playerView.seekTo(p);
        if (danmuManager != null) danmuManager.onSeekTo(p);
    }

    // 格式化倍速显示文字 (如 "1X", "1.25X"，大写与参考图样式一致)
    private String formatSpeed(float s) {
        return (s == (int) s) ? String.valueOf((int) s) + "X" : String.valueOf(s) + "X";
    }

    /** 弹出倍速选择抽屉（右侧抽屉样式） */
    private void cycleSpeed() {
        java.util.List<SideDrawerHelper.Item> items = new java.util.ArrayList<>();
        for (float v : SPEED_OPTIONS) {
            items.add(new SideDrawerHelper.Item(formatSpeed(v), "", Math.abs(v - currentSpeed) < 0.01f));
        }
        new SideDrawerHelper(this).show("播放速度", items,
                null, null, null, null,
                which -> applySpeed(SPEED_OPTIONS[which]), null);
    }

    /** 应用倍速并记忆 (按 parentGuid 存储) */
    private void applySpeed(float speed) {
        currentSpeed = speed;
        if (playerView != null && playerView.isMpvReady()) playerView.setPlaybackSpeed(speed);
        if (danmuManager != null) danmuView.setPlaybackSpeed(speed);
        if (btnSpeed != null) btnSpeed.setText(formatSpeed(speed));
        // 按剧集记忆倍速偏好
        String spKey = "speed_" + (parentGuid != null && !parentGuid.isEmpty() ? parentGuid : "default");
        getSharedPreferences("fntv_prefs", MODE_PRIVATE).edit().putFloat(spKey, speed).apply();
    }

    /** 恢复上次倍速 (在 startPlayback 前调用) */
    private void restoreSavedSpeed() {
        String spKey = "speed_" + (parentGuid != null && !parentGuid.isEmpty() ? parentGuid : "default");
        float saved = getSharedPreferences("fntv_prefs", MODE_PRIVATE).getFloat(spKey, 1.0f);
        // 验证是否在有效范围内
        boolean valid = false;
        for (float s : SPEED_OPTIONS) { if (Math.abs(s - saved) < 0.01f) { valid = true; break; } }
        if (valid) {
            currentSpeed = saved;
            if (btnSpeed != null) btnSpeed.setText(formatSpeed(saved));
        } else {
            currentSpeed = 1.0f;
            if (btnSpeed != null) btnSpeed.setText("1.0x");
        }
    }

    /** 循环切换画面比例（mpv 用 keepaspect + panscan 实现） */
    private void cycleRatio() {
        ratioIdx = (ratioIdx + 1) % RATIO_MODES.length;
        btnRatio.setText(RATIO_LABELS[ratioIdx]);
        if (playerView != null) playerView.setAspectRatioMode(RATIO_MODES[ratioIdx]);
    }


    private void checkHdr() {
        handler.postDelayed(() -> {
            if (playerView == null || !playerView.isMpvReady()) return;
            Log.d(TAG, "HDR检查: isHdr=" + isHdrVideo()
                    + " streamVHdr=" + streamVHdr
                    + " color=" + streamVColor);
            // 统一用 applyHdrMode 处理开/关（切剧集、重缓冲时也会正确切换 colorMode）
            applyHdrMode();
        }, 1500);
    }

    private boolean deviceSupportsHdr() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Display.HdrCapabilities caps = getWindowManager()
                    .getDefaultDisplay().getHdrCapabilities();
            if (caps != null) {
                for (int type : caps.getSupportedHdrTypes()) {
                    if (type == Display.HdrCapabilities.HDR_TYPE_HDR10
                            || type == Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) {
                        return true;
                    }
                }
            }
        }
        return false;
    }


    /**
     * 以右侧抽屉窗口打开既有弹窗布局（滑入动画 + 半屏压暗，内容卡片垂直居中）
     * 用于保留原滑条交互的弹窗（跳过设置/亮度/字幕样式）
     */
    private android.app.Dialog showRightDrawerDialog(int layoutRes) {
        final android.app.Dialog dialog = new android.app.Dialog(this, R.style.SideDrawerDialog);
        FrameLayout wrap = new FrameLayout(this);
        getLayoutInflater().inflate(layoutRes, wrap, true);
        dialog.setContentView(wrap);
        Window w = dialog.getWindow();
        if (w != null) {
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int width = Math.max((int) (screenWidth * 0.38f),
                    (int) (320 * getResources().getDisplayMetrics().density));
            w.setLayout(width, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setGravity(Gravity.END);
        }
        View content = wrap.getChildAt(0);
        if (content != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) content.getLayoutParams();
            lp.gravity = Gravity.CENTER_VERTICAL;
            content.setLayoutParams(lp);
        }
        return dialog;
    }

    private void showIntroOutroDialog() {
        SharedPreferences p = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        String skipId = parentGuid != null && !parentGuid.isEmpty() ? parentGuid : itemTV;
        String key = "skip_" + skipId;
        int defIntro = p.getInt(key + "_intro", 0);
        int defOutro = p.getInt(key + "_outro", 0);

        // 防御：先关闭旧抽屉，再以右侧抽屉样式展示（标题由抽屉提供）
        if (skipDrawer != null && skipDrawer.isShowing()) skipDrawer.dismiss();
        View content = getLayoutInflater().inflate(R.layout.dialog_skip, null);

        // 片头滑条
        final TextView introLabel = content.findViewById(R.id.dm_label);
        final SeekBar introSb = content.findViewById(R.id.dm_seekbar);
        // 片尾滑条（第二个 include 的 ID 是 dm_outro，里面的子控件 ID 相同）
        final TextView outroLabel = ((ViewGroup) content.findViewById(R.id.dm_outro)).findViewById(R.id.dm_label);
        final SeekBar outroSb = ((ViewGroup) content.findViewById(R.id.dm_outro)).findViewById(R.id.dm_seekbar);

        if (introLabel != null) introLabel.setText("跳过片头: " + defIntro + "秒");
        if (outroLabel != null) outroLabel.setText("跳过片尾: " + defOutro + "秒");

        if (introSb != null) {
            introSb.setMax(600);
            introSb.setProgress(defIntro);
            introSb.setKeyProgressIncrement(1);
            introSb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int v, boolean u) {
                    if (introLabel != null) introLabel.setText("跳过片头: " + v + "秒");
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
        }
        if (outroSb != null) {
            outroSb.setMax(600);
            outroSb.setProgress(defOutro);
            outroSb.setKeyProgressIncrement(1);
            outroSb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int v, boolean u) {
                    if (outroLabel != null) outroLabel.setText("跳过片尾: " + v + "秒");
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
        }

        Button reset = content.findViewById(R.id.dm_reset);
        Button cancel = content.findViewById(R.id.dm_cancel);
        Button ok = content.findViewById(R.id.dm_ok);

        if (reset != null) reset.setOnClickListener(v -> { if (introSb != null) introSb.setProgress(0); if (outroSb != null) outroSb.setProgress(0); });
        if (cancel != null) cancel.setOnClickListener(v -> skipDrawer.dismiss());
        if (ok != null) ok.setOnClickListener(v -> {
            if (introSb != null) p.edit().putInt(key + "_intro", introSb.getProgress()).apply();
            if (outroSb != null) p.edit().putInt(key + "_outro", outroSb.getProgress()).apply();
            skipDrawer.dismiss();
        });

        skipDrawer = new SideDrawerHelper(this);
        skipDrawer.showCustom((itemTV != null ? itemTV : "当前视频") + " · 跳过设置", content);
    }

    private void showBrightnessDialog() {
        SharedPreferences p = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        int brightness = p.getInt("video_brightness", 100);
        if (brightness > 100) brightness = 100;
        final android.app.Dialog dialog = showRightDrawerDialog(R.layout.dialog_brightness);

        final TextView label = dialog.findViewById(R.id.dm_label);
        final SeekBar sb = dialog.findViewById(R.id.dm_seekbar);
        final Button cancel = dialog.findViewById(R.id.dm_cancel);
        final Button ok = dialog.findViewById(R.id.dm_ok);
        final Button reset = dialog.findViewById(R.id.dm_reset);

        if (label != null) label.setText("亮度: " + (brightness - 100) + "%");
        if (sb != null) {
            sb.setMax(200);
            sb.setProgress(brightness);
            sb.setKeyProgressIncrement(5);
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seek, int val, boolean fromUser) {
                    int adj = val - 100;
                    if (label != null) label.setText("亮度: " + (adj > 0 ? "+" : "") + adj + "%");
                    if (fromUser) applyBrightness(val);
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
        }
        if (reset != null) reset.setOnClickListener(v -> { if (sb != null) { sb.setProgress(100); applyBrightness(100); if (label != null) label.setText("亮度: 0%"); } });
        if (cancel != null) cancel.setOnClickListener(v -> dialog.dismiss());
        if (ok != null) ok.setOnClickListener(v -> {
            if (sb != null) p.edit().putInt("video_brightness", sb.getProgress()).apply();
            dialog.dismiss();
        });
        dialog.show();
    }

    /**
     * 读取 SharedPreferences 中保存的字幕偏好并下发到 mpv
     * 需在 mpv 初始化完成后调用
     */
    private void applySubtitlePrefs() {
        if (subtitleManager == null || playerView == null || !playerView.isMpvReady()) return;
        SharedPreferences p = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        int scalePct = p.getInt("sub_scale", 100);
        int offset = p.getInt("sub_offset", 0);
        int delayMs = p.getInt("sub_delay", 0);
        boolean visible = p.getBoolean("sub_visible", true);
        subtitleManager.adjustStyle(scalePct / 100f, offset);
        subtitleManager.setDelay(delayMs / 1000.0);
        subtitleManager.setVisible(visible);
    }

    /**
     * 外挂字幕加载成功后的收尾：用户主动选择字幕 = 明确要看到它，
     * 覆盖历史"显示字幕"开关（否则开关残留为关时字幕会被 applySubtitlePrefs 重新隐藏）
     */
    private void onExternalSubtitleLoaded() {
        getSharedPreferences("fntv_prefs", MODE_PRIVATE)
                .edit().putBoolean("sub_visible", true).apply();
        subtitleManager.setVisible(true);
    }

    // ========== 字幕自动选择 ==========

    /**
     * 播放就绪（FILE_LOADED）后自动选择可用字幕：
     * 1) 有内封字幕轨 → 选择最佳一条（默认标记 > 中文语言 > 非外挂），mpv 已自动选中则不动；
     * 2) 无内封轨道 → 自动下载加载飞牛媒体库外挂字幕（每次播放会话只尝试一次）
     */
    private void autoSelectSubtitle() {
        if (playerView == null || !playerView.isMpvReady() || subtitleManager == null) return;
        try {
            List<CustomMPVView.TrackInfo> tracks = playerView.getTracks("sub");
            if (!tracks.isEmpty()) {
                Integer sid = MPVLib.getPropertyInt("sid");
                boolean alreadySelected = sid != null && sid > 0;
                if (alreadySelected) return;
                CustomMPVView.TrackInfo best = null;
                int bestScore = -1;
                for (CustomMPVView.TrackInfo t : tracks) {
                    int score = 0;
                    if (t.defaultTrack) score += 8;
                    if (t.forced) score += 2;
                    if (isChineseText(t.lang) || isChineseText(t.title)) score += 6;
                    if (!t.external) score += 1;
                    if (score > bestScore) { bestScore = score; best = t; }
                }
                if (best != null) {
                    playerView.selectSubtitle(best.id);
                    Log.d(TAG, "自动选择内封字幕轨 id=" + best.id
                            + " lang=" + best.lang + " title=" + best.title);
                }
                return;
            }
            // 无内封字幕轨 → 尝试飞牛媒体库外挂字幕
            if (autoSubTried) return;
            autoSubTried = true;
            StreamResponse.SubtitleStreamInfo best = pickBestServerSubtitle();
            if (best != null && mediaGuid != null) {
                Log.d(TAG, "自动加载飞牛外挂字幕 guid=" + best.guid
                        + " title=" + best.title + " lang=" + best.language);
                String label = (best.title != null && !best.title.isEmpty()) ? best.title
                        : (best.language != null && !best.language.isEmpty() ? best.language : "字幕");
                cloudStreamManager.downloadExternalSubtitle(best, label, true);
            }
        } catch (Exception e) {
            Log.e(TAG, "自动选择字幕失败", e);
        }
    }

    /**
     * 服务器字幕优选：默认标记 > 中文 > 外挂文件
     *
     * @return 最佳字幕流信息，无可用时返回 null
     */
    private StreamResponse.SubtitleStreamInfo pickBestServerSubtitle() {
        if (streamSubtitleTracks == null || streamSubtitleTracks.isEmpty()) return null;
        StreamResponse.SubtitleStreamInfo best = null;
        int bestScore = -1;
        for (StreamResponse.SubtitleStreamInfo s : streamSubtitleTracks) {
            if (s.guid == null || s.guid.isEmpty()) continue;
            int score = 0;
            if (s.isDefault != 0) score += 8;
            if (isChineseText(s.language) || isChineseText(s.title)) score += 6;
            if (s.isExternal == 1) score += 4;
            if (score > bestScore) { bestScore = score; best = s; }
        }
        return best;
    }

    /** 判断语言/标题是否指向中文字幕（zh/chi/chs/zho/中文/简/繁） */
    private static boolean isChineseText(String s) {
        if (s == null || s.isEmpty()) return false;
        String l = s.toLowerCase();
        return l.startsWith("zh") || l.equals("chi") || l.equals("chs") || l.equals("zho")
                || s.contains("中文") || s.contains("简") || s.contains("繁");
    }

    /**
     * 字幕设置对话框：字号缩放 / 垂直偏移 / 延迟对齐 / 显示开关 / 外挂字幕
     * 滑动即实时预览，确定时写入 SharedPreferences，取消则回滚到已保存值
     * 经 SideDrawerHelper 以右侧抽屉样式展示
     */
    private void showSubtitleStyleDialog() {
        if (playerView == null || !playerView.isMpvReady()) {
            Toast.makeText(this, "播放器未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        // 从信息抽屉内打开时先关闭它，避免双层抽屉叠加
        if (infoDrawer != null && infoDrawer.isShowing()) {
            infoDrawer.setOnDismissListener(null);
            infoDrawer.dismiss();
            infoDrawer = null;
        }
        if (subtitleDrawer != null && subtitleDrawer.isShowing()) {
            subtitleDrawer.dismiss();
        }
        final SharedPreferences p = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        View content = getLayoutInflater().inflate(R.layout.dialog_subtitle_style, null);

        final TextView scaleLabel = ((ViewGroup) content.findViewById(R.id.dm_scale)).findViewById(R.id.dm_label);
        final SeekBar scaleSb = ((ViewGroup) content.findViewById(R.id.dm_scale)).findViewById(R.id.dm_seekbar);
        final TextView offsetLabel = ((ViewGroup) content.findViewById(R.id.dm_offset)).findViewById(R.id.dm_label);
        final SeekBar offsetSb = ((ViewGroup) content.findViewById(R.id.dm_offset)).findViewById(R.id.dm_seekbar);
        final TextView delayLabel = ((ViewGroup) content.findViewById(R.id.dm_delay)).findViewById(R.id.dm_label);
        final SeekBar delaySb = ((ViewGroup) content.findViewById(R.id.dm_delay)).findViewById(R.id.dm_seekbar);
        final CheckBox cbVisible = content.findViewById(R.id.dm_visible);
        final Button btnLoadExt = content.findViewById(R.id.dm_load_ext);

        // 字号：SeekBar 50~200 直接对应百分比
        scaleSb.setMax(200);
        scaleSb.setProgress(Math.max(50, p.getInt("sub_scale", 100)));
        scaleSb.setKeyProgressIncrement(5);
        scaleLabel.setText("字幕字号: " + scaleSb.getProgress() + "%");
        scaleSb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                int pct = Math.max(50, v);
                scaleLabel.setText("字幕字号: " + pct + "%");
                if (fromUser) subtitleManager.adjustStyle(pct / 100f, offsetSb.getProgress() - 50);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        // 垂直偏移：SeekBar 0~100 映射 -50~+50（正值上移）
        offsetSb.setMax(100);
        offsetSb.setProgress(p.getInt("sub_offset", 0) + 50);
        offsetSb.setKeyProgressIncrement(2);
        offsetLabel.setText("垂直位置: " + (offsetSb.getProgress() - 50));
        offsetSb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                offsetLabel.setText("垂直位置: " + (v - 50));
                if (fromUser) subtitleManager.adjustStyle(Math.max(50, scaleSb.getProgress()) / 100f, v - 50);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        // 延迟：SeekBar 0~200 映射 -10.0s~+10.0s（步进 0.1s）
        delaySb.setMax(200);
        delaySb.setProgress(p.getInt("sub_delay", 0) / 100 + 100);
        delaySb.setKeyProgressIncrement(1);
        delayLabel.setText(formatSubDelay(delaySb.getProgress()));
        delaySb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                delayLabel.setText(formatSubDelay(v));
                if (fromUser) subtitleManager.setDelay((v - 100) / 10.0);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        cbVisible.setChecked(p.getBoolean("sub_visible", true));
        cbVisible.setOnCheckedChangeListener((v, checked) -> subtitleManager.setVisible(checked));

        if (btnLoadExt != null) {
            btnLoadExt.setOnClickListener(v -> { subtitleDrawer.dismiss(); pickExternalSubtitle(); });
        }

        Button reset = content.findViewById(R.id.dm_reset);
        Button cancel = content.findViewById(R.id.dm_cancel);
        Button ok = content.findViewById(R.id.dm_ok);
        if (reset != null) reset.setOnClickListener(v -> {
            scaleSb.setProgress(100);
            offsetSb.setProgress(50);
            delaySb.setProgress(100);
            cbVisible.setChecked(true);
            subtitleManager.adjustStyle(1.0f, 0);
            subtitleManager.setDelay(0);
            subtitleManager.setVisible(true);
        });
        if (cancel != null) cancel.setOnClickListener(v -> {
            applySubtitlePrefs(); // 回滚为已保存的值
            subtitleDrawer.dismiss();
        });
        if (ok != null) ok.setOnClickListener(v -> {
            p.edit()
                    .putInt("sub_scale", Math.max(50, scaleSb.getProgress()))
                    .putInt("sub_offset", offsetSb.getProgress() - 50)
                    .putInt("sub_delay", (delaySb.getProgress() - 100) * 100)
                    .putBoolean("sub_visible", cbVisible.isChecked())
                    .apply();
            subtitleDrawer.dismiss();
        });

        subtitleDrawer = new SideDrawerHelper(this);
        subtitleDrawer.showCustom("字幕调整", content);
    }

    /**
     * 格式化字幕延迟标签
     *
     * @param progress SeekBar 进度（0~200，100 为零延迟）
     */
    private String formatSubDelay(int progress) {
        double sec = (progress - 100) / 10.0;
        return String.format(java.util.Locale.US, "字幕延迟: %+.1fs", sec);
    }

    /** 打开系统文件选择器挑选外挂字幕文件 */
    private void pickExternalSubtitle() {
        try {
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQ_PICK_SUBTITLE);
        } catch (Exception e) {
            Log.e(TAG, "无法打开文件选择器", e);
            Toast.makeText(this, "设备不支持文件选择", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // 文件选择器期间 onStop 暂停了播放，返回后自动续播
        if (requestCode == REQ_PICK_SUBTITLE && playerView != null && playerView.isMpvReady()
                && !playerView.isPlaying()) {
            togglePlay();
        }
        if (requestCode != REQ_PICK_SUBTITLE || resultCode != RESULT_OK || data == null) return;
        android.net.Uri uri = data.getData();
        if (uri != null) loadExternalSubtitle(uri);
    }

    /**
     * 加载外挂字幕：ASS/SSA 先经 BilingualAssProcessor 合并双语行再交给 mpv，其余格式直接加载
     *
     * @param uri 用户选择的字幕文件 URI
     */
    private void loadExternalSubtitle(android.net.Uri uri) {
        if (subtitleManager == null || playerView == null || !playerView.isMpvReady()) {
            Toast.makeText(this, "播放器未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean ok;
        try {
            byte[] bytes = readUriBytes(uri);
            String name = uri.getLastPathSegment() != null ? uri.getLastPathSegment() : "external";
            ok = loadExternalSubtitleBytes(bytes, name);
        } catch (Exception e) {
            Log.e(TAG, "加载外挂字幕失败", e);
            ok = false;
        }
        Toast.makeText(this, ok ? "已加载外挂字幕" : "字幕加载失败", Toast.LENGTH_SHORT).show();
    }

    /**
     * 加载字幕字节数组（飞牛服务器下载 / 本地文件共用入口）
     * ASS/SSA 先合并双语行再写入缓存交给 mpv；其余格式写缓存后加载
     *
     * @param bytes    字幕文件字节
     * @param fileName 原始文件名（用于后缀判断与缓存命名）
     * @return 是否加载成功
     */
    private boolean loadExternalSubtitleBytes(byte[] bytes, String fileName) {
        if (subtitleManager == null || playerView == null || !playerView.isMpvReady() || bytes == null) return false;
        try {
            String text = BilingualAssProcessor.decodeAssBytes(bytes);
            String lower = fileName != null ? fileName.toLowerCase() : "";
            boolean isAss = (text != null && text.contains("[Events]") && text.contains("Dialogue:"))
                    || lower.endsWith(".ass") || lower.endsWith(".ssa");
            if (isAss) {
                if (text == null) text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                BilingualAssProcessor.ProcessedAss processed = BilingualAssProcessor.process(text);
                java.io.File dir = new java.io.File(getFilesDir(), "subtitles");
                if (!dir.exists()) dir.mkdirs();
                java.io.File out = new java.io.File(dir, "ext_" + Math.abs(fileName != null ? fileName.hashCode() : 0) + ".ass");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                try {
                    fos.write(processed.content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } finally {
                    fos.close();
                }
                boolean ok = subtitleManager.addSubtitleFromFile(out.getAbsolutePath());
                if (ok) {
                    applySubtitlePrefs();
                    onExternalSubtitleLoaded();
                    if (processed.mergedCount > 0) {
                        Toast.makeText(this, "已加载字幕（合并 " + processed.mergedCount + " 组双语行）",
                                Toast.LENGTH_SHORT).show();
                    }
                }
                return ok;
            }
            // 非 ASS：写缓存文件后加载（mpv 需要可读路径）。
            // 文本类字幕（SRT/VTT/SMI 等）统一归一为 UTF-8（GBK 编码 SRT 直接投喂会乱码），
            // 二进制格式（PGS .sup、VobSub 等）原样透传
            java.io.File dir = new java.io.File(getFilesDir(), "subtitles");
            if (!dir.exists()) dir.mkdirs();
            String ext = lower.contains(".") && lower.lastIndexOf('.') < lower.length() - 1
                    ? lower.substring(lower.lastIndexOf('.')) : ".srt";
            byte[] toWrite = BilingualAssProcessor.normalizeNonAssTextBytes(bytes, fileName);
            if (toWrite == null) toWrite = bytes;
            java.io.File out = new java.io.File(dir, "ext_" + Math.abs(fileName != null ? fileName.hashCode() : 0) + ext);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
            try {
                fos.write(toWrite);
            } finally {
                fos.close();
            }
            boolean ok = subtitleManager.addSubtitleFromFile(out.getAbsolutePath());
            if (ok) {
                applySubtitlePrefs();
                onExternalSubtitleLoaded();
            }
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "加载外挂字幕失败", e);
            return false;
        }
    }

    /**
     * 读取 URI 全部字节（字幕文件体积小，直接全量读入内存）
     *
     * @param uri 文件 URI
     * @return 文件字节，读取失败返回 null
     */
    private byte[] readUriBytes(android.net.Uri uri) {
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Exception e) {
            Log.w(TAG, "读取字幕文件失败: " + uri, e);
            return null;
        }
    }

    /**
     * Anime4K 超分设置对话框：先选模式，OFF 直接清除着色器，其余进入画质档位选择
     */
    private void showAnime4KDialog() {
        if (playerView == null || !playerView.isMpvReady()) {
            Toast.makeText(this, "播放器未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!anime4kManager.initialize()) {
            Toast.makeText(this, "着色器资源缺失，无法启用超分", Toast.LENGTH_SHORT).show();
            return;
        }
        final Anime4KManager.Mode[] modes = Anime4KManager.Mode.values();
        java.util.List<SideDrawerHelper.Item> items = new java.util.ArrayList<>();
        for (Anime4KManager.Mode m : modes) {
            String desc = anime4kManager.getModeDescription(m);
            items.add(new SideDrawerHelper.Item(desc,
                    m.name(), m == anime4kManager.getCurrentMode()));
        }
        new SideDrawerHelper(this).show("Anime4K 超分", items,
                null, null, null, null,
                which -> {
                    Anime4KManager.Mode mode = modes[which];
                    if (mode == Anime4KManager.Mode.OFF) {
                        anime4kManager.clearShaders();
                        saveAnime4KPrefs(mode, anime4kManager.getCurrentQuality());
                        Toast.makeText(PlayerActivity.this, "已关闭超分", Toast.LENGTH_SHORT).show();
                    } else {
                        showAnime4KQualityDialog(mode);
                    }
                }, null);
    }

    /**
     * 选择 Anime4K 画质档位并下发着色器链
     *
     * @param mode 已选定的 Anime4K 模式
     */
    private void showAnime4KQualityDialog(final Anime4KManager.Mode mode) {
        final Anime4KManager.Quality[] qualities = Anime4KManager.Quality.values();
        final String[] labels = {
                "快速（S）— 开销最低，适合低端设备",
                "平衡（M）— 推荐，画质与性能兼顾",
                "高质量（L）— 需要较强 GPU"
        };
        java.util.List<SideDrawerHelper.Item> items = new java.util.ArrayList<>();
        for (String label : labels) items.add(new SideDrawerHelper.Item(label, "", false));
        new SideDrawerHelper(this).show("超分画质", items,
                null, null, null, null,
                which -> {
                    Anime4KManager.Quality q = qualities[which];
                    anime4kManager.applyShaderChain(mode, q);
                    saveAnime4KPrefs(mode, q);
                    Toast.makeText(PlayerActivity.this,
                            "超分已启用: " + mode.name() + " / " + q.suffix, Toast.LENGTH_SHORT).show();
                }, null);
    }

    /**
     * 持久化 Anime4K 设置
     *
     * @param mode    模式
     * @param quality 画质档位
     */
    private void saveAnime4KPrefs(Anime4KManager.Mode mode, Anime4KManager.Quality quality) {
        getSharedPreferences("fntv_prefs", MODE_PRIVATE).edit()
                .putString("anime4k_mode", mode.name())
                .putString("anime4k_quality", quality.name())
                .apply();
    }

    /**
     * 恢复已保存的 Anime4K 设置
     * 着色器首次复制涉及磁盘 I/O，放后台线程执行以免拖慢首帧
     */
    private void applyAnime4KPrefs() {
        if (anime4kManager == null || playerView == null || !playerView.isMpvReady()) return;
        SharedPreferences p = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        final String modeName = p.getString("anime4k_mode", Anime4KManager.Mode.OFF.name());
        final String qualityName = p.getString("anime4k_quality", Anime4KManager.Quality.BALANCED.name());
        if (Anime4KManager.Mode.OFF.name().equals(modeName)) return;
        new Thread(() -> {
            try {
                Anime4KManager.Mode mode = Anime4KManager.Mode.valueOf(modeName);
                Anime4KManager.Quality quality = Anime4KManager.Quality.valueOf(qualityName);
                if (anime4kManager.initialize()) anime4kManager.applyShaderChain(mode, quality);
            } catch (Exception e) {
                Log.w(TAG, "恢复 Anime4K 设置失败", e);
            }
        }, "anime4k-init").start();
    }

    /** 切换 HDR 开关 */
    private void toggleHdr() {
        SharedPreferences prefs = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        boolean wasEnabled = prefs.getBoolean("hdr_enabled", false);
        prefs.edit().putBoolean("hdr_enabled", !wasEnabled).apply();
        applyHdrMode();
    }

    private void applyHdrMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        boolean enabled = getSharedPreferences("fntv_prefs", MODE_PRIVATE).getBoolean("hdr_enabled", false);
        boolean videoHdr = isHdrVideo();
        Log.d(TAG, "applyHdrMode: enabled=" + enabled + " videoHdr=" + videoHdr);
        if (enabled && videoHdr) {
            getWindow().setColorMode(ActivityInfo.COLOR_MODE_HDR);
            danmuManager.showDanmuStatus("HDR 已开启");
        } else {
            getWindow().setColorMode(0);
            if (videoHdr) danmuManager.showDanmuStatus("HDR 已关闭");
        }
    }

    /** 判断当前视频是否 HDR（mpv 上报的色彩传输函数 + 流信息兜底） */
    private boolean isHdrVideo() {
        if (playerView != null && playerView.isMpvReady() && playerView.isHdrVideo()) return true;
        // streamVHdr（杜比视界）→ 仅在设备支持 Dolby Vision 时算 HDR
        if (streamVHdr) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                android.view.Display.HdrCapabilities caps = getWindowManager()
                        .getDefaultDisplay().getHdrCapabilities();
                if (caps != null) {
                    for (int type : caps.getSupportedHdrTypes()) {
                        if (type == android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) return true;
                    }
                }
            }
            return false;
        }
        return !streamVColor.isEmpty() && (streamVColor.contains("bt2020") || streamVColor.contains("2020"));
    }

    /** 调节屏幕亮度（仅当前 Activity），val 0~200，100=系统默认 */
    private void applyBrightness(int val) {
        android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (val == 100) {
            lp.screenBrightness = -1f; // 恢复系统默认
        } else {
            float f = val / 100f;
            f = Math.max(0.01f, Math.min(1.0f, f));
            lp.screenBrightness = f;
        }
        getWindow().setAttributes(lp);
    }

    /** 打开信息抽屉（⋯ 菜单）：媒体信息双列键值网格 + 字幕设置/HDR 入口 */
    private void toggleInfo() {
        if (infoDrawer != null && infoDrawer.isShowing()) {
            infoDrawer.dismiss();
            infoDrawer = null;
            return;
        }
        if (playerView == null || !playerView.isMpvReady()) {
            Toast.makeText(this, "播放器未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        // 三个分组网格（视频/音频/其他），updateInfo 负责填充行，轨道切换回调也会刷新
        infoText = addInfoGroup(content, "视频", density);
        infoTextAudio = addInfoGroup(content, "音频", density);
        infoTextExtra = addInfoGroup(content, "其他", density);
        updateInfo();

        // 操作行：字幕设置 / HDR 开关
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = (int) (16 * density);
        row.setLayoutParams(rowLp);

        TextView btnStyle = new TextView(this);
        btnStyle.setText("字幕设置");
        btnStyle.setBackgroundResource(R.drawable.bg_side_action);
        btnStyle.setGravity(Gravity.CENTER);
        btnStyle.setPadding((int) (14 * density), 10, (int) (14 * density), 10);
        btnStyle.setTextColor(getColor(R.color.text_primary));
        btnStyle.setTextSize(14);
        btnStyle.setFocusable(true);
        btnStyle.setOnClickListener(v -> showSubtitleStyleDialog());
        row.addView(btnStyle);

        boolean hdrEnabled = getSharedPreferences("fntv_prefs", MODE_PRIVATE).getBoolean("hdr_enabled", false);
        TextView btnHdr = new TextView(this);
        btnHdr.setText(hdrEnabled ? "HDR:开" : "HDR:关");
        btnHdr.setBackgroundResource(R.drawable.bg_side_action);
        btnHdr.setGravity(Gravity.CENTER);
        btnHdr.setPadding((int) (14 * density), 10, (int) (14 * density), 10);
        btnHdr.setTextColor(getColor(hdrEnabled ? R.color.channel_green : R.color.text_secondary));
        btnHdr.setTextSize(14);
        btnHdr.setFocusable(true);
        btnHdr.setOnClickListener(v -> { toggleHdr(); updateInfoHdrLabel(btnHdr); });
        LinearLayout.LayoutParams hdrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hdrLp.leftMargin = (int) (10 * density);
        btnHdr.setLayoutParams(hdrLp);
        row.addView(btnHdr);

        content.addView(row);

        SideDrawerHelper drawer = new SideDrawerHelper(this);
        infoDrawer = drawer.showCustom("媒体信息", content);
        infoDrawer.setOnDismissListener(d -> {
            infoText = null;
            infoTextAudio = null;
            infoTextExtra = null;
            if (infoDrawer == d) infoDrawer = null;
        });
    }

    /** 信息抽屉内新增一个键值分组（标题行 + 空网格容器），返回容器供 updateInfo 填充 */
    private LinearLayout addInfoGroup(LinearLayout content, String title, float density) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(getColor(R.color.colorAccent));
        tv.setTextSize(13);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(0, 0, 0, (int) (6 * density));
        content.addView(tv);

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(grid);
        return grid;
    }

    /** 向分组网格添加一行「标签 + 值」：标签左对齐灰色，值右对齐白色，铺满抽屉宽度 */
    private void addInfoRow(LinearLayout grid, String label, String value, float density) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setPadding(0, 0, 0, (int) (5 * density));

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextColor(getColor(R.color.text_secondary));
        tvLabel.setTextSize(12);
        row.addView(tvLabel);

        TextView tvValue = new TextView(this);
        tvValue.setText(value);
        tvValue.setTextColor(getColor(R.color.text_primary));
        tvValue.setTextSize(13);
        tvValue.setGravity(Gravity.END);
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tvValue.setLayoutParams(valueLp);
        row.addView(tvValue);

        grid.addView(row);
    }

    /** 向分组网格添加一行整段说明文本（音轨列表/字幕轨等长文本），铺满宽度 */
    private void addInfoLine(LinearLayout grid, String text, float density) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(getColor(R.color.text_hint));
        tv.setTextSize(11);
        tv.setLineSpacing(2 * density, 1f);
        tv.setPadding(0, 0, 0, (int) (5 * density));
        grid.addView(tv);
    }

    /** 信息抽屉内 HDR 标签刷新 */
    private void updateInfoHdrLabel(TextView btnHdr) {
        boolean enabled = getSharedPreferences("fntv_prefs", MODE_PRIVATE).getBoolean("hdr_enabled", false);
        btnHdr.setText(enabled ? "HDR:开" : "HDR:关");
        btnHdr.setTextColor(getColor(enabled ? R.color.channel_green : R.color.text_secondary));
    }

    // ========== 底部设置面板 ==========

    /** 面板打开时同步开关状态，避免 setChecked 反向触发监听器 */
    private boolean panelSyncing = false;

    private static final int[] TAB_BTN_IDS = {R.id.tabPlay, R.id.tabQuality, R.id.tabSubtitle, R.id.tabDanmu};

    /** 接线底部设置面板、弹幕面板、字幕面板的全部控件（onCreate 中调用一次） */
    private void initSettingsPanelControls() {
        View panel = findViewById(R.id.settingsPanel);
        if (panel == null) return;
        final SharedPreferences p = getSharedPreferences("fntv_prefs", MODE_PRIVATE);

        // ---- 播放 tab ----
        bindSpeedChip(panel, R.id.btnSpeed1x, 0.5f);
        bindSpeedChip(panel, R.id.btnSpeed10x, 1.0f);
        bindSpeedChip(panel, R.id.btnSpeed15x, 1.5f);
        bindSpeedChip(panel, R.id.btnSpeed20x, 2.0f);

        panel.findViewById(R.id.btnRatioOriginal).setOnClickListener(v -> applyPanelRatio(0));
        panel.findViewById(R.id.btnRatio169).setOnClickListener(v -> applyPanelRatio(3));
        panel.findViewById(R.id.btnRatioFill).setOnClickListener(v -> applyPanelRatio(1));
        panel.findViewById(R.id.btnRatioCrop).setOnClickListener(v -> applyPanelRatio(2));

        Switch swLock = panel.findViewById(R.id.switchLock);
        if (swLock != null) {
            swLock.setOnCheckedChangeListener((v, checked) -> {
                if (!panelSyncing) applyLockState(checked);
            });
        }
        Switch swAutoNext = panel.findViewById(R.id.switchAutoNext);
        if (swAutoNext != null) {
            swAutoNext.setChecked(p.getBoolean("auto_next", true));
            swAutoNext.setOnCheckedChangeListener((v, checked) ->
                    p.edit().putBoolean("auto_next", checked).apply());
        }

        // 跳过设置入口（原右上角 btnSkip 整合至此）
        View btnSkipEntry = panel.findViewById(R.id.btnSkipEntry);
        if (btnSkipEntry != null) {
            btnSkipEntry.setOnClickListener(v -> {
                settingsPanelManager.closeSettingsPanel();
                showIntroOutroDialog();
            });
        }

        // ---- 画质 tab ----
        bindQualityChip(panel, R.id.btnQualityLow, 480);
        bindQualityChip(panel, R.id.btnQualityNormal, 720);
        bindQualityChip(panel, R.id.btnQualityHD, 1080);
        bindQualityChip(panel, R.id.btnQuality4K, 2160);

        Switch swHdr = panel.findViewById(R.id.switchHdr);
        if (swHdr != null) {
            if (!deviceSupportsHdr()) {
                swHdr.setEnabled(false);
                swHdr.setAlpha(0.4f);
            }
            swHdr.setOnCheckedChangeListener((v, checked) -> {
                if (panelSyncing) return;
                boolean enabled = p.getBoolean("hdr_enabled", false);
                if (checked != enabled) toggleHdr();
            });
        }
        Switch swAnime4K = panel.findViewById(R.id.switchAnime4K);
        if (swAnime4K != null) {
            swAnime4K.setOnCheckedChangeListener((v, checked) -> {
                if (panelSyncing) return;
                setAnime4kEnabled(checked, swAnime4K);
            });
        }
        bindAnimeQualityChip(panel, R.id.btnAnimeQualityFast, Anime4KManager.Quality.FAST);
        bindAnimeQualityChip(panel, R.id.btnAnimeQualityBalanced, Anime4KManager.Quality.BALANCED);
        bindAnimeQualityChip(panel, R.id.btnAnimeQualityHigh, Anime4KManager.Quality.HIGH);

        // ---- 字幕 tab ----
        Switch swSubVis = panel.findViewById(R.id.switchSubtitleVisible);
        if (swSubVis != null) {
            swSubVis.setOnCheckedChangeListener((v, checked) -> {
                if (panelSyncing) return;
                subtitleManager.setVisible(checked);
                p.edit().putBoolean("sub_visible", checked).apply();
            });
        }
        View btnSubTrackEntry = panel.findViewById(R.id.btnSubtitleTrackEntry);
        if (btnSubTrackEntry != null) {
            btnSubTrackEntry.setOnClickListener(v -> cloudStreamManager.showSubtitleTrackDialog(PlayerActivity.this));
        }
        View btnSubEntry = panel.findViewById(R.id.btnSubtitleStyleEntry);
        if (btnSubEntry != null) {
            // 字幕调整已迁移为 SideDrawerHelper 右侧抽屉
            btnSubEntry.setOnClickListener(v -> {
                settingsPanelManager.closeSettingsPanel();
                showSubtitleStyleDialog();
            });
        }

        // ---- 弹幕 tab ----
        Switch swDanmu = panel.findViewById(R.id.switchDanmu);
        if (swDanmu != null) {
            swDanmu.setOnCheckedChangeListener((v, checked) -> {
                if (panelSyncing) return;
                danmuManager.setDanmuOn(checked);
            });
        }
        bindDanmuTypeSwitch(panel, R.id.switchDanmuScroll, "danmu_scroll",
                checked -> danmuView.setShowScroll(checked));
        bindDanmuTypeSwitch(panel, R.id.switchDanmuTop, "danmu_top",
                checked -> danmuView.setShowTop(checked));
        bindDanmuTypeSwitch(panel, R.id.switchDanmuBottom, "danmu_bottom",
                checked -> danmuView.setShowBottom(checked));
        View btnDanmuEntry = panel.findViewById(R.id.btnDanmuSettingsEntry);
        if (btnDanmuEntry != null) {
            // 弹幕设置已迁移为 SideDrawerHelper 右侧抽屉
            btnDanmuEntry.setOnClickListener(v -> {
                settingsPanelManager.closeSettingsPanel();
                settingsPanelManager.openDanmuPanel();
            });
        }
    }

    private interface OnDanmuTypeChanged { void onChanged(boolean checked); }

    /** 弹幕类型开关（滚动/顶部/底部）：写 prefs + 应用到弹幕视图 */
    private void bindDanmuTypeSwitch(View panel, int id, String key, OnDanmuTypeChanged applier) {
        Switch sw = panel.findViewById(id);
        if (sw == null) return;
        sw.setOnCheckedChangeListener((v, checked) -> {
            if (panelSyncing) return;
            getSharedPreferences("fntv_prefs", MODE_PRIVATE).edit().putBoolean(key, checked).apply();
            applier.onChanged(checked);
        });
    }

    private void bindSpeedChip(View panel, int id, float speed) {
        View btn = panel.findViewById(id);
        if (btn != null) btn.setOnClickListener(v -> applySpeed(speed));
    }

    private void bindQualityChip(View panel, int id, int targetHeight) {
        View btn = panel.findViewById(id);
        if (btn != null) {
            btn.setOnClickListener(v -> {
                if (qualityHelper != null) qualityHelper.selectQualityByHeight(targetHeight);
            });
        }
    }

    /** 绑定 Anime4K 超分档位 chip：点击即应用对应画质档位并高亮 */
    private void bindAnimeQualityChip(View panel, int id, Anime4KManager.Quality quality) {
        View btn = panel.findViewById(id);
        if (btn != null) {
            btn.setOnClickListener(v -> applyAnimeQuality(quality));
        }
    }

    /** 应用 Anime4K 画质档位：OFF 时默认用 A 模式，选档位即启用超分 */
    private void applyAnimeQuality(Anime4KManager.Quality quality) {
        if (playerView == null || !playerView.isMpvReady()) {
            Toast.makeText(this, "播放器未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        if (anime4kManager == null || !anime4kManager.initialize()) {
            Toast.makeText(this, "着色器资源缺失，无法启用超分", Toast.LENGTH_SHORT).show();
            return;
        }
        Anime4KManager.Mode mode = anime4kManager.getCurrentMode();
        if (mode == Anime4KManager.Mode.OFF) mode = Anime4KManager.Mode.A;
        anime4kManager.applyShaderChain(mode, quality);
        saveAnime4KPrefs(mode, quality);
        View panel = findViewById(R.id.settingsPanel);
        if (panel != null) {
            Switch sw = panel.findViewById(R.id.switchAnime4K);
            if (sw != null && !sw.isChecked()) {
                panelSyncing = true;
                sw.setChecked(true);
                panelSyncing = false;
            }
        }
        highlightAnimeQuality();
    }

    /** 高亮当前 Anime4K 档位 chip */
    private void highlightAnimeQuality() {
        View panel = findViewById(R.id.settingsPanel);
        if (panel == null || anime4kManager == null) return;
        Anime4KManager.Quality cur = anime4kManager.getCurrentQuality();
        highlightAnimeQualityChip(panel, R.id.btnAnimeQualityFast, cur == Anime4KManager.Quality.FAST);
        highlightAnimeQualityChip(panel, R.id.btnAnimeQualityBalanced, cur == Anime4KManager.Quality.BALANCED);
        highlightAnimeQualityChip(panel, R.id.btnAnimeQualityHigh, cur == Anime4KManager.Quality.HIGH);
    }

    private void highlightAnimeQualityChip(View panel, int id, boolean selected) {
        View btn = panel.findViewById(id);
        if (btn instanceof Button) {
            ((Button) btn).setTextColor(getColor(selected ? R.color.text_white : R.color.text_secondary));
        }
    }

    /**
     * 应用画面比例（面板四选一）
     *
     * @param mode 0=原始/适应 1=填充(拉伸) 2=裁剪(缩放) 3=强制16:9
     */
    private void applyPanelRatio(int mode) {
        if (playerView == null) return;
        playerView.setAspectRatioMode(mode);
        // 0~2 与控制栏循环按钮共享状态；16:9 不改变循环按钮的档位
        if (mode >= 0 && mode < RATIO_MODES.length) {
            ratioIdx = mode;
            btnRatio.setText(RATIO_LABELS[mode]);
        }
    }

    /** 提取自 btnLock 点击逻辑，供锁定按钮与设置面板开关共用 */
    private void applyLockState(boolean lock) {
        isLocked = lock;
        btnLock.setImageResource(isLocked ? R.drawable.ic_lock_on : R.drawable.ic_lock_off);
        if (isLocked) {
            // 立即隐藏并同步状态机（ctrlVis 复位），否则解锁时 showCtrl(true) 会因幂等分支提前返回
            handler.removeCallbacks(hideC);
            handler.removeCallbacks(finishHideControls);
            ctrlVis = false;
            controller.clearAnimation();
            topBar.clearAnimation();
            topBar.setVisibility(View.INVISIBLE);
            controller.setVisibility(View.INVISIBLE);
            btnLock.setVisibility(View.INVISIBLE);
            if (lockOverlay != null) lockOverlay.setVisibility(View.VISIBLE);
        } else {
            showCtrl(true);
            if (lockOverlay != null) lockOverlay.setVisibility(View.GONE);
            // 解锁后焦点还给视频区域
            playerView.requestFocus();
        }
    }

    /** 开/关 Anime4K 超分（设置面板开关） */
    private void setAnime4kEnabled(boolean on, Switch swAnime4K) {
        if (on) {
            if (!anime4kManager.initialize()) {
                Toast.makeText(this, "着色器资源缺失，无法启用超分", Toast.LENGTH_SHORT).show();
                if (swAnime4K != null) {
                    panelSyncing = true;
                    swAnime4K.setChecked(false);
                    panelSyncing = false;
                }
                return;
            }
            SharedPreferences p = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
            String modeName = p.getString("anime4k_mode", Anime4KManager.Mode.A.name());
            String qualityName = p.getString("anime4k_quality", Anime4KManager.Quality.BALANCED.name());
            Anime4KManager.Mode mode;
            Anime4KManager.Quality quality;
            try {
                mode = Anime4KManager.Mode.OFF.name().equals(modeName)
                        ? Anime4KManager.Mode.A : Anime4KManager.Mode.valueOf(modeName);
                quality = Anime4KManager.Quality.valueOf(qualityName);
            } catch (Exception e) {
                mode = Anime4KManager.Mode.A;
                quality = Anime4KManager.Quality.BALANCED;
            }
            anime4kManager.applyShaderChain(mode, quality);
            saveAnime4KPrefs(mode, quality);
        } else {
            anime4kManager.clearShaders();
            saveAnime4KPrefs(Anime4KManager.Mode.OFF, anime4kManager.getCurrentQuality());
        }
    }

    /** 设置面板打开时，把各开关/文案同步为当前状态 */
    private void syncSettingsPanelState() {
        View panel = findViewById(R.id.settingsPanel);
        if (panel == null) return;
        final SharedPreferences p = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        panelSyncing = true;

        Switch swLock = panel.findViewById(R.id.switchLock);
        if (swLock != null) swLock.setChecked(isLocked);
        Switch swAutoNext = panel.findViewById(R.id.switchAutoNext);
        if (swAutoNext != null) swAutoNext.setChecked(p.getBoolean("auto_next", true));
        Switch swHdr = panel.findViewById(R.id.switchHdr);
        if (swHdr != null) swHdr.setChecked(p.getBoolean("hdr_enabled", false));
        Switch swAnime4K = panel.findViewById(R.id.switchAnime4K);
        if (swAnime4K != null) {
            swAnime4K.setChecked(anime4kManager != null
                    && anime4kManager.getCurrentMode() != Anime4KManager.Mode.OFF);
        }
        highlightAnimeQuality();
        TextView tvQuality = panel.findViewById(R.id.tvCurrentQuality);
        if (tvQuality != null) {
            tvQuality.setText(qualityHelper != null ? qualityHelper.getCurrentLabel() : "原画");
        }
        Switch swSubVis = panel.findViewById(R.id.switchSubtitleVisible);
        if (swSubVis != null) swSubVis.setChecked(p.getBoolean("sub_visible", true));
        TextView tvSub = panel.findViewById(R.id.tvCurrentSubtitle);
        if (tvSub != null) {
            int count = subtitleManager != null ? subtitleManager.getSubtitleTrackCount() : 0;
            tvSub.setText(count > 0 ? "已加载 " + count + " 条字幕轨" : "关闭");
        }
        Switch swDanmu = panel.findViewById(R.id.switchDanmu);
        if (swDanmu != null) swDanmu.setChecked(danmuManager != null && danmuManager.isEnabled());
        Switch swScroll = panel.findViewById(R.id.switchDanmuScroll);
        if (swScroll != null) swScroll.setChecked(p.getBoolean("danmu_scroll", true));
        Switch swTop = panel.findViewById(R.id.switchDanmuTop);
        if (swTop != null) swTop.setChecked(p.getBoolean("danmu_top", true));
        Switch swBottom = panel.findViewById(R.id.switchDanmuBottom);
        if (swBottom != null) swBottom.setChecked(p.getBoolean("danmu_bottom", true));

        panelSyncing = false;
    }

    /** 面板打开后把焦点放到当前 tab 按钮（TV 遥控器导航） */
    private void focusPanelTab(int tabIndex) {
        View panel = findViewById(R.id.settingsPanel);
        if (panel == null) return;
        int idx = Math.max(0, Math.min(TAB_BTN_IDS.length - 1, tabIndex));
        View tabBtn = panel.findViewById(TAB_BTN_IDS[idx]);
        if (tabBtn != null) tabBtn.requestFocus();
    }


    private void updateTitle() {
        int epNum = getIntent().getIntExtra("episode_number", 0);
        String epName = itemTitle != null ? itemTitle : "";
        StringBuilder sb = new StringBuilder();
        if (itemTV != null && !itemTV.isEmpty()) {
            sb.append(itemTV);
            // 「剧名 · 第 N 集 · 标题」间隔点样式（参考图）
            if (epNum > 0) sb.append(" · 第").append(epNum).append("集");
            if (epName != null && !epName.isEmpty() && !epName.equals(itemTV)) {
                sb.append(" · ").append(epName);
            }
        } else {
            sb.append(epName);
        }
        tvTitle.setText(sb.toString().trim());
    }

    private void showCtrl(boolean show) {
        if (show) handler.removeCallbacks(finishHideControls);
        if (show && isLocked) {
            btnLock.clearAnimation();
            btnLock.setVisibility(View.VISIBLE);
            if (lockOverlay != null) lockOverlay.setVisibility(View.GONE);
            return;
        }

        if (show) {
            if (ctrlVis) {
                resetHideTimer();
                return;
            }
            ctrlVis = true;
            controller.clearAnimation();
            topBar.clearAnimation();
            controller.setVisibility(View.VISIBLE);
            topBar.setVisibility(View.VISIBLE);
            btnLock.setVisibility(View.VISIBLE);
            btnDanmu.setVisibility(View.VISIBLE);
            controller.startAnimation(AnimationUtils.loadAnimation(this, R.anim.controller_slide_in));
            topBar.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_in));
            updateTitle();
            resetHideTimer();
            // 控制栏显示：通知弹幕层底部避让（避免弹幕被控制栏遮挡）
            if (danmuView != null) {
                danmuView.post(() -> danmuView.setControllerBottomAvoid(controller.getHeight()));
            }
            return;
        }

        handler.removeCallbacks(hideC);
        if (!ctrlVis) {
            hideSystemUi();
            return;
        }
        ctrlVis = false;
        controller.clearAnimation();
        topBar.clearAnimation();
        controller.startAnimation(AnimationUtils.loadAnimation(this, R.anim.controller_slide_out));
        topBar.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_out));
        handler.postDelayed(finishHideControls, 300);
        hideSystemUi();
    }

    private void finishHideControls() {
        if (ctrlVis) return;
        controller.clearAnimation();
        topBar.clearAnimation();
        controller.setVisibility(View.INVISIBLE);
        topBar.setVisibility(View.INVISIBLE);
        btnLock.setVisibility(View.INVISIBLE);
        btnDanmu.setVisibility(View.INVISIBLE);
        if (isLocked && lockOverlay != null) lockOverlay.setVisibility(View.VISIBLE);
        if (danmuView != null) danmuView.setControllerBottomAvoid(0);
    }
    private void resetHideTimer() {
        handler.removeCallbacks(hideC);
        handler.postDelayed(hideC, 5000);
    }
    private final Runnable hideC = () -> {
        // 焦点在控制器按钮上时推迟隐藏，infoPanel/顶栏/无焦点时正常隐藏
        if (controller.hasFocus() || btnDanmu.hasFocus() || btnLock.hasFocus()
                || btnCloudMode.hasFocus() || btnBrightness.hasFocus()
                || btnInfo.hasFocus() || btnBack.hasFocus()
                || (btnQuality != null && btnQuality.hasFocus())) {
            resetHideTimer();
            return;
        }
        showCtrl(false);
    };

    private void setupFocusAutoHide() {
        View.OnFocusChangeListener l = (v, hasFocus) -> {
            if (hasFocus) resetHideTimer();
        };
        btnPlayPause.setOnFocusChangeListener(l);
        btnRewind.setOnFocusChangeListener(l);
        btnForward.setOnFocusChangeListener(l);
        btnSpeed.setOnFocusChangeListener(l);
        btnRatio.setOnFocusChangeListener(l);
        btnInfo.setOnFocusChangeListener(l);
        if (btnQuality != null) btnQuality.setOnFocusChangeListener(l);
        btnEpisodeList.setOnFocusChangeListener(l);
        btnBack.setOnFocusChangeListener(l);
        btnDanmu.setOnFocusChangeListener(l);
        btnLock.setOnFocusChangeListener(l);
        btnCloudMode.setOnFocusChangeListener(l);
        if (btnSettings != null) btnSettings.setOnFocusChangeListener(l);
        if (btnBrightness != null) btnBrightness.setOnFocusChangeListener(l);
        View btnAudioTrack = findViewById(R.id.btnAudioTrack);
        View btnSubtitleTrack = findViewById(R.id.btnSubtitleTrack);
        if (btnAudioTrack != null) btnAudioTrack.setOnFocusChangeListener(l);
        if (btnSubtitleTrack != null) btnSubtitleTrack.setOnFocusChangeListener(l);
        // 底部设置面板焦点触发隐藏控制栏
        if (findViewById(R.id.settingsPanel) != null)
            findViewById(R.id.settingsPanel).setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus) resetHideTimer(); });
    };

    /** 时间显示：当前时间白色 / 总时长灰色（参考图双色样式） */
    private void setTimeText(long cur, long dur) {
        if (tvTime == null) return;
        String curStr = FormatUtils.fmt(cur);
        String full = curStr + " / " + FormatUtils.fmt(dur);
        SpannableString ss = new SpannableString(full);
        ss.setSpan(new ForegroundColorSpan(getColor(R.color.text_white)),
                0, curStr.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tvTime.setText(ss);
    }

    private void updateTime() {        if (playerView == null || !playerView.isMpvReady()) return;
        long cur = playerView.getCurrentPosition(), dur = playerView.getDuration();
        seekBar.setMax((int) Math.max(dur, 1));
        seekBar.setKeyProgressIncrement(5000); // 方向键每次 5 秒
        // 防抖期间不覆盖 UI，避免抽搐（tvTime 和 seekBar 进度由 onProgressChanged 控制）
        if (pendingSeekMs < 0) {
            setTimeText(cur, dur);
            seekBar.setProgress((int) cur);
        }
        if (danmuManager != null) danmuManager.setPlayTime(cur);
        // 实时监测片尾位置
        if (!outroSkipped && dur > 0) {
            SharedPreferences sp = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
            String sid = parentGuid != null && !parentGuid.isEmpty() ? parentGuid : (itemTV != null ? itemTV : null);
            if (sid != null) {
                int outroSec = sp.getInt("skip_" + sid + "_outro", 0);
                if (outroSec > 0) Log.d(TAG, "片尾检测: cur=" + (cur/1000) + "s dur=" + (dur/1000) + "s 阈值=" + (dur/1000 - outroSec) + "s");
                if (outroSec > 0 && cur / 1000 > dur / 1000 - outroSec) {
                outroSkipped = true;
                danmuManager.showDanmuStatus("检测到片尾");
                if (episodeManager != null && episodeManager.hasNext()
                        && getSharedPreferences("fntv_prefs", MODE_PRIVATE).getBoolean("auto_next", true))
                    handler.postDelayed(() -> episodeManager.playNext(), 1000);
                }
            }
        }
        handler.postDelayed(timeR, 200);
    }
    private final Runnable timeR = () -> { if (playerView != null && playerView.isPlaying()) updateTime(); };

    private void probeWithMediaExtractor() {
        if (mediaGuid == null || baseUrl == null) return;
        final String url = baseUrl + "/v/api/v1/media/range/" + mediaGuid;
        new Thread(() -> {
            try {
                android.media.MediaExtractor ex = new android.media.MediaExtractor();
                try {
                    ex.setDataSource(url);
                    for (int i = 0; i < ex.getTrackCount(); i++) {
                        android.media.MediaFormat mf = ex.getTrackFormat(i);
                        String mime = mf.getString(android.media.MediaFormat.KEY_MIME);
                        if (mime == null) continue;
                        if (mime.startsWith("video/")) {
                            if (streamVWidth <= 0) streamVWidth = mf.containsKey(android.media.MediaFormat.KEY_WIDTH) ? mf.getInteger(android.media.MediaFormat.KEY_WIDTH) : 0;
                            if (streamVHeight <= 0) streamVHeight = mf.containsKey(android.media.MediaFormat.KEY_HEIGHT) ? mf.getInteger(android.media.MediaFormat.KEY_HEIGHT) : 0;
                            if (streamBitrate <= 0) streamBitrate = mf.containsKey(android.media.MediaFormat.KEY_BIT_RATE) ? mf.getInteger(android.media.MediaFormat.KEY_BIT_RATE) : 0;
                            if (streamVCodec.isEmpty()) streamVCodec = mime.replace("video/", "");
                        }
                    }
                } finally { ex.release(); }
            } catch (Exception e) {
                Log.w(TAG, "MediaExtractor 失败: " + e.getMessage());
            }
        }).start();
    }

    /** 刷新信息面板：键值行填充三个分组网格（数据来源 mpv 属性 + 服务端流信息兜底） */
    private void updateInfo() {
        if (playerView == null || !playerView.isMpvReady()) return;
        String mpvVCodec = playerView.getVideoCodec();
        String mpvACodec = playerView.getAudioCodec();
        float density = getResources().getDisplayMetrics().density;

        // ---- 视频分组 ----
        if (infoText != null) {
            infoText.removeAllViews();
            String codec = FormatUtils.fmtVideoCodec(streamVCodec.isEmpty() ? mpvVCodec : streamVCodec);
            addInfoRow(infoText, "编码", codec, density);
            // 优先用 mpv 实际解码的画面参数（切换画质后自动更新）
            int w = playerView.getVideoWidth() > 0 ? playerView.getVideoWidth() : streamVWidth;
            int h = playerView.getVideoHeight() > 0 ? playerView.getVideoHeight() : streamVHeight;
            if (w > 0 && h > 0) addInfoRow(infoText, "分辨率", w + "×" + h, density);
            float fps = 0;
            if (!streamVFps.isEmpty()) { try { fps = Float.parseFloat(streamVFps.replaceAll("[^0-9.]", "")); } catch (Exception ignored) {} }
            if (fps <= 0) fps = playerView.getVideoFps();
            if (fps > 0) addInfoRow(infoText, "帧率", String.format("%.3f fps", fps), density);
            int vBitrate = playerView.getPropertyIntSafe("video-bitrate", 0);
            String vBr = vBitrate > 0 ? FormatUtils.formatBitrate(vBitrate)
                    : streamBitrate > 0 ? FormatUtils.formatBitrate(streamBitrate) : null;
            if (vBr != null) addInfoRow(infoText, "码率", vBr, density);
            if (streamVBitDepth > 0) addInfoRow(infoText, "色深", streamVBitDepth + "bit", density);
            if (streamVHdr || playerView.isHdrVideo()) addInfoRow(infoText, "动态范围", "HDR10", density);
            addInfoRow(infoText, "解码", actualVideoDecoder.isEmpty() ? (isHwDecode ? "硬解" : "软解") : actualVideoDecoder, density);
        }

        // ---- 音频分组 ----
        if (infoTextAudio != null) {
            infoTextAudio.removeAllViews();
            if (mpvACodec != null && !mpvACodec.isEmpty()) {
                addInfoRow(infoTextAudio, "编码", FormatUtils.fmtAudioCodec(mpvACodec), density);
                int ch = playerView.getPropertyIntSafe("audio-params/channel-count", 0);
                addInfoRow(infoTextAudio, "声道", ch > 0 ? (ch == 8 ? "7.1" : ch == 6 ? "5.1" : ch + "ch") : "?", density);
                int sr = playerView.getPropertyIntSafe("audio-params/samplerate", 0);
                addInfoRow(infoTextAudio, "采样", sr > 0 ? sr + "Hz" : "?", density);
                int aBitrate = playerView.getPropertyIntSafe("audio-bitrate", 0);
                if (aBitrate > 0) addInfoRow(infoTextAudio, "码率", aBitrate / 1000 + "kbps", density);
                addInfoRow(infoTextAudio, "解码", actualAudioDecoder.isEmpty() ? (isHwDecode ? "硬解" : "软解") : actualAudioDecoder, density);
                // 显示用户选择的音轨（如有）
                String selAudio = cloudStreamManager != null ? cloudStreamManager.getLastAudioTrackLabel() : "";
                if (!selAudio.isEmpty() && !selAudio.equals("默认")) {
                    addInfoRow(infoTextAudio, "已选", selAudio, density);
                }
            } else {
                addInfoRow(infoTextAudio, "音轨", "无音轨", density);
            }
        }

        // ---- 其他分组（额外音轨 / 字幕轨 / 时长） ----
        if (infoTextExtra != null) {
            infoTextExtra.removeAllViews();
            // 额外音轨
            if (streamAudioTracks != null && streamAudioTracks.size() > 1) {
                StringBuilder a = new StringBuilder();
                for (int i = 1; i < streamAudioTracks.size(); i++) {
                    StreamResponse.AudioStreamInfo asi = streamAudioTracks.get(i);
                    String an = FormatUtils.fmtAudioCodec(asi.codecName);
                    String al = asi.language != null && !asi.language.isEmpty() ? asi.language : "";
                    String ach = asi.channels > 0 ? (asi.channels == 8 ? "7.1" : asi.channels == 6 ? "5.1" : asi.channels + "ch") : "?";
                    String ab = asi.bps > 0 ? " " + FormatUtils.formatBitrate(asi.bps) : "";
                    if (a.length() > 0) a.append("\n");
                    a.append("音轨").append(i + 1).append("  ").append(an);
                    if (!al.isEmpty()) a.append(" ").append(al);
                    a.append(" ").append(ach).append(ab);
                }
                addInfoLine(infoTextExtra, a.toString(), density);
            }
            // 字幕
            if (streamSubtitleTracks != null && !streamSubtitleTracks.isEmpty()) {
                StringBuilder s = new StringBuilder();
                for (int i = 0; i < streamSubtitleTracks.size(); i++) {
                    StreamResponse.SubtitleStreamInfo sub = streamSubtitleTracks.get(i);
                    if (s.length() > 0) s.append("\n");
                    String sf = sub.codecName != null ? sub.codecName.toUpperCase() : "?";
                    String lang = sub.language != null && !sub.language.isEmpty() ? sub.language : "?";
                    String def = sub.isDefault != 0 ? " [默认]" : "";
                    s.append("字幕").append(i + 1).append("  ").append(sf).append(" ").append(lang).append(def);
                }
                addInfoLine(infoTextExtra, s.toString(), density);
            }
            // 时长
            long durMs = playerView.getDuration();
            if (durMs > 0) {
                addInfoRow(infoTextExtra, "时长", FormatUtils.fmtTime((int) (durMs / 1000)), density);
            }
            if (infoTextExtra.getChildCount() == 0) {
                addInfoLine(infoTextExtra, "（无额外轨道信息）", density);
            }
        }
    }

    // ========== 弹幕全部移至 DanmuManager ==========

    // ========== 进度保存 ==========

    private void startSave() { handler.removeCallbacks(saveR); handler.postDelayed(saveR, 10000); }
    private void stopSave() { handler.removeCallbacks(saveR); }
    private final Runnable saveR = new Runnable() {
        @Override public void run() { saveProgress(); handler.postDelayed(this, 15000); }
    };

    private void saveProgress() {
        if (playerView == null || !playerView.isMpvReady()) return;
        long p = playerView.getCurrentPosition(); if (p <= 0) return;
        long ts = p / 1000;
        Map<String, Object> r = new HashMap<>();
        r.put("item_guid", itemGuid); r.put("media_guid", mediaGuid);
        r.put("video_guid", videoGuid != null ? videoGuid : "");
        r.put("audio_guid", audioGuid != null ? audioGuid : "");
        r.put("subtitle_guid", subtitleGuid != null ? subtitleGuid : "_no_display_");
        // 非原画模式：用切换后的分辨率和码率，并记录 play_link
        if (customQualityBitrate > 0 && !customQualityRes.isEmpty()) {
            r.put("resolution", customQualityRes);
            r.put("bitrate", customQualityBitrate);
            if (!customPlayLink.isEmpty()) r.put("play_link", customPlayLink);
        } else {
            r.put("resolution", !streamResolution.isEmpty() ? streamResolution : (resolution != null ? resolution : ""));
            r.put("bitrate", streamBitrate);
        }
        r.put("ts", ts); r.put("duration", itemDuration > 0 ? itemDuration : playerView.getDuration()/1000);
        apiManager.setReferer(baseUrl + "/v/video/" + itemGuid + "?media_guid=" + mediaGuid);
        Log.d(TAG, "recordPlayStatus 请求: " + (r != null ? new com.google.gson.Gson().toJson(r) : "null"));
        apiManager.getApi().recordPlayStatus(r).enqueue(new retrofit2.Callback<ApiResponse<Object>>() {
            @Override public void onResponse(retrofit2.Call<ApiResponse<Object>> call, retrofit2.Response<ApiResponse<Object>> response) {
                String respBody = response.body() != null
                        ? "code=" + response.body().code + " msg='" + response.body().msg + "' data=" + response.body().data
                        : "nullBody";
                Log.d(TAG, "recordPlayStatus 响应: HTTP " + response.code() + " " + respBody
                        + " (raw: " + (response.body() != null ? new com.google.gson.Gson().toJson(response.body()) : "null") + ")");
            }
            @Override public void onFailure(retrofit2.Call<ApiResponse<Object>> call, Throwable t) {
                Log.e(TAG, "recordPlayStatus 失败: " + t.getMessage());
            }
        });
    }

    /**
     * 视频播放完成后，上报已观看状态至服务器
     * POST api/v1/item/watched { item_guid: String }
     */
    private void reportWatched() {
        if (itemGuid == null || itemGuid.isEmpty()) return;
        Map<String, Object> r = new HashMap<>();
        r.put("item_guid", itemGuid);
        apiManager.setReferer(baseUrl + "/v/video/" + itemGuid + "?media_guid=" + mediaGuid);
        Log.d(TAG, "setWatched 请求: " + new com.google.gson.Gson().toJson(r));
        apiManager.getApi().setWatched(r).enqueue(new retrofit2.Callback<ApiResponse<Object>>() {
            @Override public void onResponse(retrofit2.Call<ApiResponse<Object>> call, retrofit2.Response<ApiResponse<Object>> response) {
                Log.d(TAG, "setWatched 响应: HTTP " + response.code()
                        + " body=" + (response.body() != null ? new com.google.gson.Gson().toJson(response.body()) : "null"));
            }
            @Override public void onFailure(retrofit2.Call<ApiResponse<Object>> call, Throwable t) {
                Log.e(TAG, "setWatched 失败: " + t.getMessage());
            }
        });
    }

    // ========== 按键 ==========

    @Override public boolean onKeyDown(int k, KeyEvent e) {
        if (isLocked) {
            if (k == KeyEvent.KEYCODE_BACK) {
                if (btnLock.hasFocus() || controller.hasFocus()) {
                    controller.clearFocus();
                    btnLock.clearFocus();
                    return true;
                }
                isLocked = false;
                btnLock.setImageResource(R.drawable.ic_lock_off);
                showCtrl(true);
                btnPlayPause.postDelayed(new Runnable() { @Override public void run() { btnPlayPause.requestFocus(); } }, 50);
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
                isLocked = false;
                btnLock.setImageResource(R.drawable.ic_lock_off);
                showCtrl(true);
                btnPlayPause.postDelayed(new Runnable() { @Override public void run() { btnPlayPause.requestFocus(); } }, 50);
                return true;
            }
            return true;
        }

        // 面板优先处理：设置面板/弹幕抽屉打开时，BACK 键关闭对应面板
        // （跳过/字幕/信息抽屉是独立 Dialog，BACK 由系统 Dialog 自行处理）
        if (settingsPanelManager != null) {
            if (settingsPanelManager.isSettingsPanelOpen() && (k == KeyEvent.KEYCODE_BACK)) {
                settingsPanelManager.closeSettingsPanel();
                return true;
            }
            if (settingsPanelManager.isDanmuPanelOpen() && (k == KeyEvent.KEYCODE_BACK)) {
                settingsPanelManager.closeDanmuPanel();
                return true;
            }
        }
        if (ctrlVis) {
            switch (k) {
                case KeyEvent.KEYCODE_BACK:
                    if (infoDrawer != null && infoDrawer.isShowing()) { toggleInfo(); return true; }
                    // 有控件焦点 → 清掉，自动回退到 playerView
                    if (controller.hasFocus() || btnDanmu.hasFocus() || btnLock.hasFocus() || btnCloudMode.hasFocus() || btnBrightness.hasFocus() || topBar.hasFocus() || btnBack.hasFocus()) {
                        topBar.clearFocus();
                        controller.clearFocus();
                        btnDanmu.clearFocus();
                        btnLock.clearFocus();
                        return true;
                    }
                    // 无按钮焦点（playerView 或其它）→ 收起控制栏
                    showCtrl(false);
                    return true;
                // LEFT/RIGHT 由 SeekBar 自身处理（已设 keyProgressIncrement=5000）
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                    if (seekBar.hasFocus() || btnRewind.hasFocus() || btnForward.hasFocus()
                            || btnSpeed.hasFocus() || btnRatio.hasFocus() || btnInfo.hasFocus()
                            || btnEpisodeList.hasFocus() || btnBrightness.hasFocus()) {
                        return true;
                    }
                    togglePlay(); return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    // 顶栏按上→收起，其余情况交给系统焦点导航
                    if (topBar.hasFocus() || btnCloudMode.hasFocus() || (btnInfo != null && btnInfo.hasFocus())) {
                        showCtrl(false);
                        return true;
                    }
                    return super.onKeyDown(k, e);
                case KeyEvent.KEYCODE_INFO: case KeyEvent.KEYCODE_MENU:
                    toggleInfo(); return true;
            }
            return super.onKeyDown(k, e);
        } else {
            switch (k) {
                case KeyEvent.KEYCODE_BACK:
                    if (backPressedTime + 2000 > System.currentTimeMillis()) {
                        restoreOrientation();
                        finish();
                    } else {
                        backPressedTime = System.currentTimeMillis();
                        Toast.makeText(this, "再按一次退出播放", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_DPAD_UP:
                    showCtrl(true);
                    btnPlayPause.postDelayed(new Runnable() { @Override public void run() { btnPlayPause.requestFocus(); } }, 50);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT: {
                    long step = k == KeyEvent.KEYCODE_DPAD_LEFT ? -seekStep : seekStep;
                    long cur = pendingSeekMs >= 0 ? pendingSeekMs : (playerView != null ? playerView.getCurrentPosition() : 0);
                    long dur = playerView != null ? playerView.getDuration() : 0;
                    long target = Math.max(0, Math.min(dur, cur + step));
                    // 立即更新 UI
                    String timeText = FormatUtils.fmt(target) + " / " + FormatUtils.fmt(dur);
                    tvSeekOverlay.setText(timeText);
                    tvSeekOverlay.setVisibility(View.VISIBLE);
                    tvTime.setText(timeText);
                    handler.removeCallbacks(hideSeekOverlayR);
                    handler.postDelayed(hideSeekOverlayR, 2000);
                    // 防抖：真正 seek 延迟到停止操作后
                    pendingSeekMs = target;
                    if (seekCommitR != null) handler.removeCallbacks(seekCommitR);
                    seekCommitR = () -> {
                        if (playerView != null && playerView.isMpvReady()) {
                            playerView.seekTo(target);
                            if (danmuManager != null) danmuManager.onSeekTo(target);
                        }
                        pendingSeekMs = -1;
                    };
                    handler.postDelayed(seekCommitR, 1000);
                    return true;
                }
                case KeyEvent.KEYCODE_INFO: case KeyEvent.KEYCODE_MENU:
                    toggleInfo(); return true;
            }
            return super.onKeyDown(k, e);
        }
    }

    private void hideSystemUi() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return;
        final android.view.View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /** 焦点变化时重新隐藏系统栏，保持沉浸 */
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && playerView != null) hideSystemUi();
    }

    private TextView tvSeekOverlay;
    private final Runnable hideSeekOverlayR = () -> { if (tvSeekOverlay != null) tvSeekOverlay.setVisibility(View.GONE); };


    /** 控制栏隐藏时显示进度时间浮层 */
    private void showSeekOverlay() {
        if (playerView == null || !playerView.isMpvReady()) return;
        updateTime();
        tvSeekOverlay.setText(FormatUtils.fmt(playerView.getCurrentPosition()) + " / " + FormatUtils.fmt(playerView.getDuration()));
        tvSeekOverlay.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideSeekOverlayR);
        handler.postDelayed(hideSeekOverlayR, 2000);
    }

    private boolean isTvDevice() {
        android.app.UiModeManager uiModeManager = (android.app.UiModeManager) getSystemService(UI_MODE_SERVICE);
        return uiModeManager != null
                && uiModeManager.getCurrentModeType() == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION;
    }

    @Override
    public void finish() {
        // 退出时恢复系统亮度
        try {
            android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = -1f; // 恢复系统默认
            getWindow().setAttributes(lp);
        } catch (Exception ignored) {}
        super.finish();
    }

    private void restoreOrientation() {
        // 播放页固定横屏（TV 与手机一致，不再回竖屏）
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    @Override protected void onPause() {
        super.onPause();
        // 仅在真正退出播放页时恢复竖屏；
        // 打开系统文件选择器等临时页面也会触发 onPause，此时保持横屏避免回来后卡在竖屏
        if (isFinishing()) restoreOrientation();
    }

    @Override protected void onResume() {
        super.onResume();
        // 从文件选择器/弹窗返回后重新锁定横屏（修复：二级菜单返回后卡竖屏）
        if (!isFinishing()) {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }
    }
    @Override protected void onStop() {
        // PiP 模式下不停止播放
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode()) return;
        super.onStop();
        saveProgress();
        if (playerView != null && playerView.isMpvReady()) playerView.setPause(true);
    }
    /** 进入画中画模式 */
    private void enterPiPMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || playerView == null) return;
        PictureInPictureParams params = new PictureInPictureParams.Builder()
                .setAspectRatio(new android.util.Rational(16, 9))
                .build();
        enterPictureInPictureMode(params);
    }
    @Override public void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && playerView != null && playerView.isPlaying()) {
            enterPiPMode();
        }
    }
    @Override public void onPictureInPictureModeChanged(boolean isInPiPMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPiPMode, newConfig);
        if (isInPiPMode) {
            // PiP 模式下隐藏所有 UI，仅保留视频画面
            showCtrl(false);
            topBar.setVisibility(View.GONE);
            controller.setVisibility(View.GONE);
            btnDanmu.setVisibility(View.GONE);
            btnLock.setVisibility(View.GONE);
            danmuView.setVisibility(View.GONE);
            gestureOverlay.setVisibility(View.GONE);
            loadingOverlay.setVisibility(View.GONE);
        } else {
            // 退出 PiP，恢复 UI
            danmuView.setVisibility(View.VISIBLE);
            showCtrl(true);
            // PiP 模式下定时器可能未运行，退出时主动保存进度
            saveProgress();
            if (playerView != null && playerView.isPlaying()) startSave();
        }
    }
    @Override protected void onDestroy() {
        saveProgress();
        super.onDestroy(); handler.removeCallbacksAndMessages(null);
        cancelLoadTimeout();
        if (danmuManager != null) { danmuManager.destroy(); }
        if (eventObserver != null) { MPVLib.removeObserver(eventObserver); eventObserver = null; }
        if (playerView != null) playerView.destroyMpv();
    }

    // ========== MPVTimeSource 实现（供 DanmuManager 读取播放时间轴） ==========

    /** 当前播放位置（毫秒） */
    @Override public long getCurrentPositionMs() {
        return playerView != null && playerView.isMpvReady() ? playerView.getCurrentPosition() : 0;
    }

    /** 视频总时长（毫秒） */
    @Override public long getDurationMs() {
        return playerView != null && playerView.isMpvReady() ? playerView.getDuration() : 0;
    }

    /** 是否正在播放 */
    @Override public boolean isPlaying() {
        return playerView != null && playerView.isPlaying();
    }

    /** 当前倍速 */
    @Override public float getPlaybackSpeed() {
        return playerView != null && playerView.isMpvReady() ? playerView.getPlaybackSpeed() : 1.0f;
    }
}


