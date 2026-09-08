package com.fntv.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 检查更新、下载源测速、可取消下载与安装。 */
public class UpdateManager {
    public static final String PREF_AUTO_CHECK_UPDATE = "auto_check_update";
    private static final String PREF_DOWNLOAD_SOURCE = "update_download_source";
    private static final String SOURCE_AUTO = "auto";
    private static final String TAG = "Update";
    private static final int SAMPLE_BYTES = 2 * 1024 * 1024;
    private static final long SAMPLE_TIME_MS = 2000;
    private static boolean autoCheckedInProcess;

    private static final String[] UPDATE_URLS = {
            "https://jsd.onmicrosoft.cn/gh/shikaiwe/fnos_tv_danmu_self@master/update.json",
            "https://cdn.jsdelivr.net/gh/shikaiwe/fnos_tv_danmu_self@master/update.json",
            "https://fastly.jsdelivr.net/gh/shikaiwe/fnos_tv_danmu_self@master/update.json",
            "https://raw.githubusercontent.com/shikaiwe/fnos_tv_danmu_self/master/update.json"
    };
    private static final String[] SOURCE_IDS = {"ghproxy", "gh-proxy", "ghfast", "github"};
    private static final String[] SOURCE_NAMES = {"ghproxy.net", "gh-proxy.com", "ghfast.top", "GitHub 直连"};
    private static final String[] SOURCE_DESCRIPTIONS = {"国内镜像加速", "备用镜像加速", "高速代理节点", "官方发布地址"};
    private static final String[] SOURCE_PREFIXES = {
            "https://ghproxy.net/", "https://gh-proxy.com/", "https://ghfast.top/", ""
    };

    private final Activity activity;
    private final Button btnCheckUpdate;
    private final int currentVersionCode;
    private final SharedPreferences prefs;
    private final Object transferLock = new Object();
    private final AtomicBoolean downloadCancelled = new AtomicBoolean();
    private volatile boolean checking;
    private volatile boolean released;
    private volatile int benchmarkSession;
    private volatile Thread benchmarkThread;
    private volatile ExecutorService benchmarkExecutor;
    private final Set<HttpURLConnection> benchmarkConnections =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private volatile Thread downloadThread;
    private HttpURLConnection activeConnection;
    private InputStream activeInput;
    private OutputStream activeOutput;
    private File activePartFile;
    private final double[] sourceSpeeds = {-1, -1, -1, -1};
    private long sourceSpeedsAt;

    private Dialog sourceDialog;
    private Dialog updateDialog;
    private Dialog progressDialog;
    private TextView progressTitle;
    private TextView progressVersion;
    private TextView progressPercent;
    private TextView progressStage;
    private TextView progressSource;
    private TextView progressSize;
    private TextView progressSpeed;
    private TextView progressEta;
    private ProgressBar progressBar;
    private Button progressAction;
    private boolean progressActionCloses;

    public UpdateManager(Activity activity, Button btnCheckUpdate, int currentVersionCode) {
        this.activity = activity;
        this.btnCheckUpdate = btnCheckUpdate;
        this.currentVersionCode = currentVersionCode;
        this.prefs = activity.getSharedPreferences("fntv_prefs", Context.MODE_PRIVATE);
    }

    public void setup() {
        btnCheckUpdate.setOnClickListener(v -> checkUpdate(false));
    }

    public void checkUpdateAutomatically() {
        synchronized (UpdateManager.class) {
            if (autoCheckedInProcess || !prefs.getBoolean(PREF_AUTO_CHECK_UPDATE, true)) return;
            autoCheckedInProcess = true;
        }
        checkUpdate(true);
    }

    public String getSelectedSourceName() {
        int index = sourceIndex(prefs.getString(PREF_DOWNLOAD_SOURCE, SOURCE_AUTO));
        return index < 0 ? "自动选择" : SOURCE_NAMES[index];
    }

    public void release() {
        released = true;
        cancelBenchmark();
        cancelDownload(false);
        dismissDialog(sourceDialog);
        dismissDialog(updateDialog);
        dismissDialog(progressDialog);
    }

    public void showDownloadSourceDialog(Runnable onSelectionChanged) {
        if (!canUseActivity()) return;
        benchmarkSession++;
        final int session = benchmarkSession;
        int selectedIndex = sourceIndex(prefs.getString(PREF_DOWNLOAD_SOURCE, SOURCE_AUTO)) + 1;
        final int[] pendingSelection = {selectedIndex};

        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_update_source, null);
        LinearLayout group = content.findViewById(R.id.sourceGroup);
        TextView hint = content.findViewById(R.id.tvSourceHint);
        Button cancel = content.findViewById(R.id.btnSourceCancel);
        Button save = content.findViewById(R.id.btnSourceSave);
        List<SourceRow> rows = new ArrayList<>();

        rows.add(createSourceRow(group, "自动选择", "优先使用实测最快来源，失败时自动切换", -1,
                pendingSelection, rows));
        for (int i = 0; i < SOURCE_NAMES.length; i++) {
            rows.add(createSourceRow(group, SOURCE_NAMES[i], SOURCE_DESCRIPTIONS[i], i,
                    pendingSelection, rows));
        }
        updateRowSelection(rows, pendingSelection[0]);

        sourceDialog = createDialog(content, true);
        sourceDialog.setOnDismissListener(ignored -> {
            cancelBenchmark();
            sourceDialog = null;
        });
        cancel.setOnClickListener(v -> sourceDialog.dismiss());
        save.setOnClickListener(v -> {
            String sourceId = pendingSelection[0] <= 0 ? SOURCE_AUTO : SOURCE_IDS[pendingSelection[0] - 1];
            prefs.edit().putString(PREF_DOWNLOAD_SOURCE, sourceId).apply();
            if (onSelectionChanged != null) onSelectionChanged.run();
            sourceDialog.dismiss();
        });
        sourceDialog.show();
        applyDialogWidth(sourceDialog);
        rows.get(Math.max(0, pendingSelection[0])).root.requestFocus();
        startSourceBenchmark(session, rows, hint);
    }

    private SourceRow createSourceRow(LinearLayout parent, String name, String description, int sourceIndex,
                                      int[] selection, List<SourceRow> rows) {
        View root = LayoutInflater.from(activity).inflate(R.layout.item_update_source, parent, false);
        SourceRow row = new SourceRow(root, sourceIndex);
        row.name.setText(name);
        row.description.setText(description);
        row.speed.setText(sourceIndex < 0 ? "自动推荐" : "等待测速");
        root.setOnClickListener(v -> {
            selection[0] = sourceIndex + 1;
            updateRowSelection(rows, selection[0]);
        });
        parent.addView(root);
        return row;
    }

    private void updateRowSelection(List<SourceRow> rows, int selectedPosition) {
        for (int i = 0; i < rows.size(); i++) {
            boolean selected = i == selectedPosition;
            rows.get(i).root.setActivated(selected);
            rows.get(i).mark.setText(selected ? "●" : "○");
        }
    }

    private void startSourceBenchmark(int session, List<SourceRow> rows, TextView hint) {
        Arrays.fill(sourceSpeeds, -1);
        benchmarkThread = new Thread(() -> {
            UpdateInfo info = fetchUpdateInfo(null);
            if (!benchmarkActive(session) || info == null || !isValidUpdateInfo(info)) {
                if (benchmarkActive(session)) runOnUiThread(() -> {
                    hint.setText("无法获取 APK 地址，请稍后再试");
                    hint.setTextColor(activity.getColor(R.color.error));
                });
                return;
            }

            runOnUiThread(() -> {
                if (!benchmarkActive(session)) return;
                hint.setText("正在同时测试全部来源，结果会实时更新");
                for (int i = 0; i < SOURCE_NAMES.length; i++) {
                    rows.get(i + 1).speed.setText("测速中…");
                    rows.get(i + 1).speed.setTextColor(activity.getColor(R.color.accent_light));
                }
            });

            ExecutorService executor = Executors.newFixedThreadPool(SOURCE_NAMES.length);
            benchmarkExecutor = executor;
            CountDownLatch completed = new CountDownLatch(SOURCE_NAMES.length);
            for (int i = 0; i < SOURCE_NAMES.length; i++) {
                final int index = i;
                executor.execute(() -> {
                    try {
                        double speed = benchmarkSource(SOURCE_PREFIXES[index] + info.apkUrl, session);
                        sourceSpeeds[index] = speed;
                        runOnUiThread(() -> updateBenchmarkRow(session, rows.get(index + 1), speed));
                    } finally {
                        completed.countDown();
                    }
                });
            }

            try {
                completed.await(12, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                executor.shutdownNow();
                if (benchmarkExecutor == executor) benchmarkExecutor = null;
            }
            if (!benchmarkActive(session)) return;
            sourceSpeedsAt = System.currentTimeMillis();
            int fastest = fastestSourceIndex();
            runOnUiThread(() -> {
                if (!benchmarkActive(session)) return;
                String result = fastest >= 0 ? "测速完成 · 自动模式将优先使用 " + SOURCE_NAMES[fastest]
                        : "测速完成 · 暂无可用来源";
                hint.setText(result);
                hint.setTextColor(activity.getColor(fastest >= 0 ? R.color.text_secondary : R.color.error));
                rows.get(0).speed.setText(fastest >= 0 ? formatSpeed(sourceSpeeds[fastest]) : "暂无推荐");
            });
        }, "update-benchmark-coordinator");
        benchmarkThread.start();
    }

    private void updateBenchmarkRow(int session, SourceRow row, double speed) {
        if (!benchmarkActive(session)) return;
        if (speed >= 0) {
            row.speed.setText(formatSpeed(speed));
            row.speed.setTextColor(activity.getColor(R.color.success));
        } else {
            row.speed.setText("不可用");
            row.speed.setTextColor(activity.getColor(R.color.error));
        }
    }

    private double benchmarkSource(String url, int session) {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(url, 7000, 7000);
            benchmarkConnections.add(connection);
            connection.setRequestProperty("Range", "bytes=0-" + (SAMPLE_BYTES - 1));
            connection.setRequestProperty("Accept-Encoding", "identity");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) return -1;
            long started = System.nanoTime();
            int total = 0;
            byte[] buffer = new byte[32768];
            try (InputStream input = connection.getInputStream()) {
                while (total < SAMPLE_BYTES && benchmarkActive(session)) {
                    int count = input.read(buffer, 0, Math.min(buffer.length, SAMPLE_BYTES - total));
                    if (count < 0) break;
                    total += count;
                    if ((System.nanoTime() - started) / 1_000_000L >= SAMPLE_TIME_MS) break;
                }
            }
            double seconds = (System.nanoTime() - started) / 1_000_000_000d;
            return total >= 64 * 1024 && seconds > 0 ? total / 1024d / 1024d / seconds : -1;
        } catch (Exception e) {
            Log.d(TAG, "测速失败: " + url + " " + e.getClass().getSimpleName());
            return -1;
        } finally {
            if (connection != null) {
                benchmarkConnections.remove(connection);
                connection.disconnect();
            }
        }
    }

    private void cancelBenchmark() {
        benchmarkSession++;
        Thread coordinator = benchmarkThread;
        if (coordinator != null) coordinator.interrupt();
        ExecutorService executor = benchmarkExecutor;
        if (executor != null) executor.shutdownNow();
        for (HttpURLConnection connection : benchmarkConnections) connection.disconnect();
        benchmarkConnections.clear();
        benchmarkThread = null;
        benchmarkExecutor = null;
    }

    private boolean benchmarkActive(int session) {
        return !released && !Thread.currentThread().isInterrupted() && session == benchmarkSession
                && sourceDialog != null && sourceDialog.isShowing();
    }

    private void checkUpdate(boolean automatic) {
        if (checking) {
            if (!automatic) Toast.makeText(activity, "正在检查更新", Toast.LENGTH_SHORT).show();
            return;
        }
        checking = true;
        setButtonState(false, "检查中...");
        if (!automatic) showProgressDialog("检查更新", "当前版本 v" + BuildConfig.VERSION_NAME, false);
        updateProgressUi("正在连接更新服务器", "获取最新版本信息…", -1, 0, 0, 0, false);

        new Thread(() -> {
            UpdateInfo info = fetchUpdateInfo(null);
            if (!canUseActivity()) {
                checking = false;
                return;
            }
            checking = false;
            setButtonState(true, "检查更新");
            if (info == null || !isValidUpdateInfo(info)) {
                if (!automatic) showProgressResult("检查更新失败", "无法连接更新服务器，请稍后重试", true);
                return;
            }
            boolean testInstall = BuildConfig.DEBUG && info.versionCode <= currentVersionCode;
            if (info.versionCode <= currentVersionCode && !testInstall) {
                if (!automatic) showProgressResult("已是最新版本", "当前版本 v" + BuildConfig.VERSION_NAME, false);
                return;
            }
            dismissDialog(progressDialog);
            runOnUiThread(() -> showUpdateAvailableDialog(info, testInstall));
        }, "update-check").start();
    }

    private UpdateInfo fetchUpdateInfo(StatusListener unused) {
        String timestamp = new java.text.SimpleDateFormat("yyyyMMddHHmm", Locale.CHINA)
                .format(new java.util.Date());
        for (String baseUrl : UPDATE_URLS) {
            HttpURLConnection connection = null;
            try {
                String requestUrl = baseUrl;
                if (!baseUrl.contains("raw.githubusercontent.com")) {
                    requestUrl += (requestUrl.contains("?") ? "&" : "?") + "t=" + timestamp;
                }
                connection = openConnection(requestUrl, 8000, 8000);
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) continue;
                StringBuilder json = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) json.append(line);
                }
                JSONObject object = new JSONObject(json.toString());
                UpdateInfo info = new UpdateInfo();
                info.versionCode = object.optInt("versionCode", 0);
                info.versionName = object.optString("versionName", "");
                info.apkUrl = object.optString("apkUrl", "");
                info.changelog = object.optString("changelog", "暂无更新说明");
                info.forceUpdate = object.optBoolean("forceUpdate", false);
                return info;
            } catch (Exception e) {
                Log.w(TAG, "更新源不可用: " + baseUrl, e);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        return null;
    }

    private boolean isValidUpdateInfo(UpdateInfo info) {
        return info.versionCode > 0 && !TextUtils.isEmpty(info.versionName)
                && !TextUtils.isEmpty(info.apkUrl) && info.apkUrl.startsWith("https://");
    }

    private void showUpdateAvailableDialog(UpdateInfo info, boolean testInstall) {
        if (!canUseActivity()) return;
        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_update_available, null);
        TextView title = content.findViewById(R.id.tvUpdateAvailableTitle);
        TextView subtitle = content.findViewById(R.id.tvUpdateAvailableSubtitle);
        TextView changelog = content.findViewById(R.id.tvUpdateChangelog);
        Button later = content.findViewById(R.id.btnUpdateLater);
        Button now = content.findViewById(R.id.btnUpdateNow);
        title.setText(testInstall ? "测试安装器" : "发现新版本 v" + info.versionName);
        subtitle.setText(testInstall ? "Debug 模式可测试当前版本安装流程" : "新版本已准备好，建议保持网络连接稳定");
        changelog.setText(info.changelog);
        now.setText(testInstall ? "测试安装" : "立即更新");
        later.setText(info.forceUpdate ? "退出应用" : "稍后再说");

        updateDialog = createDialog(content, !info.forceUpdate);
        later.setOnClickListener(v -> {
            if (info.forceUpdate) activity.finishAffinity();
            else updateDialog.dismiss();
        });
        now.setOnClickListener(v -> {
            updateDialog.dismiss();
            downloadAndInstall(info, testInstall);
        });
        updateDialog.show();
        applyDialogWidth(updateDialog);
        now.requestFocus();
    }

    private void downloadAndInstall(UpdateInfo info, boolean testInstall) {
        downloadCancelled.set(false);
        setButtonState(false, "下载中...");
        showProgressDialog("正在准备更新", "目标版本 v" + info.versionName, true);
        updateProgressUi("正在选择下载源", "根据设置和测速结果连接…", -1, 0, 0, 0, true);

        downloadThread = new Thread(() -> {
            File partFile = null;
            File finalFile = null;
            try {
                File directory = new File(activity.getExternalFilesDir(null), "download");
                if (!directory.exists() && !directory.mkdirs()) throw new Exception("无法创建下载目录");
                partFile = new File(directory, "FNTV_v" + info.versionCode + ".apk.part");
                finalFile = new File(directory, "FNTV_v" + info.versionCode + ".apk");
                deleteQuietly(partFile);
                deleteQuietly(finalFile);
                synchronized (transferLock) { activePartFile = partFile; }

                Exception lastError = null;
                boolean downloaded = false;
                for (int sourceIndex : selectedSourceOrder()) {
                    checkCancelled();
                    try {
                        downloadFromSource(info.apkUrl, sourceIndex, partFile);
                        downloaded = true;
                        break;
                    } catch (DownloadCancelledException e) {
                        throw e;
                    } catch (Exception e) {
                        lastError = e;
                        deleteQuietly(partFile);
                        Log.w(TAG, "下载源不可用: " + SOURCE_NAMES[sourceIndex], e);
                    }
                }
                if (!downloaded) throw lastError != null ? lastError : new Exception("所有下载源均不可用");
                checkCancelled();
                updateProgressUi("正在校验安装包", "检查文件格式、包名和签名…", 100,
                        partFile.length(), partFile.length(), 0, true);
                if (!hasZipHeader(partFile)) throw new Exception("下载文件不是有效的 APK");
                if (!verifyApkSignature(partFile)) throw new Exception("APK 包名或签名不匹配，已阻止安装");
                checkCancelled();
                if (!partFile.renameTo(finalFile)) throw new Exception("无法保存已下载的安装包");
                synchronized (transferLock) { activePartFile = null; }
                checkCancelled();
                File installFile = finalFile;
                updateProgressUi("下载完成", "即将打开系统安装器", 100,
                        finalFile.length(), finalFile.length(), 0, false);
                runOnUiThread(() -> installApk(installFile, testInstall));
            } catch (DownloadCancelledException e) {
                deleteQuietly(partFile);
                showCancelledResult();
            } catch (Exception e) {
                Log.e(TAG, "下载失败", e);
                deleteQuietly(partFile);
                showProgressResult("下载失败", friendlyError(e), true);
                setButtonState(true, "检查更新");
            } finally {
                clearActiveTransfer();
            }
        }, "update-download");
        downloadThread.start();
    }

    private void downloadFromSource(String apkUrl, int sourceIndex, File target) throws Exception {
        checkCancelled();
        updateProgressUi("正在连接下载源", SOURCE_NAMES[sourceIndex], -1, 0, 0, 0, true);
        HttpURLConnection connection = openConnection(SOURCE_PREFIXES[sourceIndex] + apkUrl, 10000, 30000);
        synchronized (transferLock) { activeConnection = connection; }
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) throw new Exception("服务器返回 HTTP " + responseCode);
            long total = connection.getContentLengthLong();
            InputStream input = connection.getInputStream();
            OutputStream output = new FileOutputStream(target);
            synchronized (transferLock) {
                activeInput = input;
                activeOutput = output;
            }
            byte[] buffer = new byte[32768];
            long downloaded = 0;
            long sampleBytes = 0;
            long sampleStarted = System.nanoTime();
            long lastUi = 0;
            double smoothSpeed = 0;
            try {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkCancelled();
                    output.write(buffer, 0, count);
                    downloaded += count;
                    sampleBytes += count;
                    long now = System.nanoTime();
                    if ((now - lastUi) >= 300_000_000L) {
                        double seconds = (now - sampleStarted) / 1_000_000_000d;
                        double instant = seconds > 0 ? sampleBytes / 1024d / 1024d / seconds : 0;
                        smoothSpeed = smoothSpeed == 0 ? instant : smoothSpeed * 0.7 + instant * 0.3;
                        int progress = total > 0 ? (int) Math.min(99, downloaded * 100 / total) : -1;
                        updateProgressUi("正在下载更新", SOURCE_NAMES[sourceIndex], progress,
                                downloaded, total, smoothSpeed, true);
                        sampleBytes = 0;
                        sampleStarted = now;
                        lastUi = now;
                    }
                }
                output.flush();
            } finally {
                closeQuietly(input);
                closeQuietly(output);
                synchronized (transferLock) {
                    activeInput = null;
                    activeOutput = null;
                }
            }
            if (downloaded == 0) throw new Exception("下载内容为空");
        } finally {
            connection.disconnect();
            synchronized (transferLock) {
                if (activeConnection == connection) activeConnection = null;
            }
        }
    }

    private void showProgressDialog(String title, String version, boolean cancellableDownload) {
        runOnUiThread(() -> {
            if (!canUseActivity()) return;
            dismissDialog(progressDialog);
            View content = LayoutInflater.from(activity).inflate(R.layout.dialog_update_progress, null);
            progressTitle = content.findViewById(R.id.tvProgressTitle);
            progressVersion = content.findViewById(R.id.tvProgressVersion);
            progressPercent = content.findViewById(R.id.tvProgressPercent);
            progressStage = content.findViewById(R.id.tvProgressStage);
            progressSource = content.findViewById(R.id.tvProgressSource);
            progressSize = content.findViewById(R.id.tvProgressSize);
            progressSpeed = content.findViewById(R.id.tvProgressSpeed);
            progressEta = content.findViewById(R.id.tvProgressEta);
            progressBar = content.findViewById(R.id.updateProgressBar);
            progressAction = content.findViewById(R.id.btnProgressAction);
            progressTitle.setText(title);
            progressVersion.setText(version);
            progressActionCloses = !cancellableDownload;
            progressAction.setText(cancellableDownload ? "取消下载" : "关闭");
            progressAction.setOnClickListener(v -> {
                if (progressActionCloses) progressDialog.dismiss();
                else cancelDownload(true);
            });
            progressDialog = createDialog(content, true);
            progressDialog.setOnKeyListener((dialog, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    if (!progressActionCloses) cancelDownload(true);
                    else dialog.dismiss();
                    return true;
                }
                return false;
            });
            progressDialog.show();
            applyDialogWidth(progressDialog);
            progressAction.requestFocus();
        });
    }

    private void updateProgressUi(String stage, String source, int percent, long downloaded,
                                  long total, double speedMb, boolean working) {
        runOnUiThread(() -> {
            if (progressDialog == null || !progressDialog.isShowing()) return;
            progressStage.setText(stage);
            progressSource.setText(source);
            if (percent >= 0) {
                progressBar.setIndeterminate(false);
                progressBar.setProgress(percent);
                progressPercent.setText(percent + "%");
            } else {
                progressBar.setIndeterminate(working);
                progressPercent.setText("--");
            }
            progressSize.setText(downloaded > 0
                    ? "已下载 " + formatBytes(downloaded) + (total > 0 ? " / " + formatBytes(total) : "")
                    : "已下载 --");
            progressSpeed.setText(speedMb > 0 ? "速度 " + formatSpeed(speedMb) : "速度 -- MB/s");
            if (total > downloaded && speedMb > 0) {
                long seconds = Math.max(1, (long) ((total - downloaded) / 1024d / 1024d / speedMb));
                progressEta.setText("剩余 " + formatDuration(seconds));
            } else {
                progressEta.setText("剩余 --");
            }
        });
    }

    private void showProgressResult(String title, String detail, boolean error) {
        runOnUiThread(() -> {
            if (progressDialog == null || !progressDialog.isShowing()) {
                showProgressDialog(title, "", false);
                activity.getWindow().getDecorView().postDelayed(() -> showProgressResult(title, detail, error), 50);
                return;
            }
            progressTitle.setText(title);
            progressTitle.setTextColor(activity.getColor(error ? R.color.error : R.color.success));
            progressStage.setText(detail);
            progressSource.setText("");
            progressPercent.setText(error ? "!" : "✓");
            progressBar.setIndeterminate(false);
            progressBar.setProgress(error ? 0 : 100);
            progressSize.setText("");
            progressSpeed.setText("");
            progressEta.setText("");
            progressActionCloses = true;
            progressAction.setEnabled(true);
            progressAction.setText("关闭");
            progressAction.requestFocus();
        });
    }

    private void showCancelledResult() {
        runOnUiThread(() -> {
            setButtonState(true, "检查更新");
            showProgressResult("下载已取消", "临时文件已清理，可随时重新检查更新", false);
        });
    }

    private void cancelDownload(boolean showFeedback) {
        if (downloadThread == null || !downloadThread.isAlive()) {
            if (showFeedback && progressDialog != null) progressDialog.dismiss();
            return;
        }
        downloadCancelled.set(true);
        runOnUiThread(() -> {
            if (progressAction != null) {
                progressAction.setEnabled(false);
                progressAction.setText("正在取消…");
            }
            if (progressStage != null) progressStage.setText("正在终止下载并清理临时文件…");
        });
        synchronized (transferLock) {
            closeQuietly(activeInput);
            closeQuietly(activeOutput);
            if (activeConnection != null) activeConnection.disconnect();
        }
        downloadThread.interrupt();
    }

    private void checkCancelled() throws DownloadCancelledException {
        if (downloadCancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new DownloadCancelledException();
        }
    }

    private void clearActiveTransfer() {
        synchronized (transferLock) {
            closeQuietly(activeInput);
            closeQuietly(activeOutput);
            if (activeConnection != null) activeConnection.disconnect();
            activeInput = null;
            activeOutput = null;
            activeConnection = null;
            if (downloadCancelled.get()) deleteQuietly(activePartFile);
            activePartFile = null;
        }
        downloadThread = null;
    }

    private void installApk(File apkFile, boolean testOnly) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && !activity.getPackageManager().canRequestPackageInstalls()) {
                Intent permission = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(permission);
                showProgressResult("需要安装权限", "请允许安装未知来源应用后，再次点击检查更新", true);
                setButtonState(true, "检查更新");
                return;
            }
            Uri apkUri = androidx.core.content.FileProvider.getUriForFile(
                    activity, activity.getPackageName() + ".fileprovider", apkFile);
            Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                    .setDataAndType(apkUri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                activity.startActivity(install);
            } catch (android.content.ActivityNotFoundException e) {
                activity.startActivity(new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(apkUri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            dismissDialog(progressDialog);
            if (testOnly) Toast.makeText(activity, "测试成功：系统安装器可用", Toast.LENGTH_LONG).show();
            setButtonState(true, "检查更新");
        } catch (Exception e) {
            Log.e(TAG, "启动安装器失败", e);
            showProgressResult("无法打开安装器", friendlyError(e) + "\n文件位置：" + apkFile.getAbsolutePath(), true);
            setButtonState(true, "检查更新");
        }
    }

    private boolean verifyApkSignature(File apkFile) {
        try {
            PackageManager pm = activity.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo current = pm.getPackageInfo(activity.getPackageName(), flags);
            PackageInfo archive = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), flags);
            if (archive == null || !activity.getPackageName().equals(archive.packageName)) return false;
            if (BuildConfig.DEBUG) return true;
            Signature[] currentSignatures = getSignatures(current);
            Signature[] archiveSignatures = getSignatures(archive);
            return currentSignatures != null && archiveSignatures != null
                    && currentSignatures.length > 0 && Arrays.equals(currentSignatures, archiveSignatures);
        } catch (Exception e) {
            Log.e(TAG, "签名校验异常", e);
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private Signature[] getSignatures(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
        }
        return info.signatures;
    }

    private boolean hasZipHeader(File file) {
        if (file == null || file.length() < 2) return false;
        try (FileInputStream input = new FileInputStream(file)) {
            return input.read() == 'P' && input.read() == 'K';
        } catch (Exception e) {
            return false;
        }
    }

    private List<Integer> selectedSourceOrder() {
        int selected = sourceIndex(prefs.getString(PREF_DOWNLOAD_SOURCE, SOURCE_AUTO));
        List<Integer> order = new ArrayList<>();
        if (selected >= 0) {
            order.add(selected);
            return order;
        }
        for (int i = 0; i < SOURCE_NAMES.length; i++) order.add(i);
        if (System.currentTimeMillis() - sourceSpeedsAt < 5 * 60 * 1000L) {
            Collections.sort(order, (left, right) -> Double.compare(sourceSpeeds[right], sourceSpeeds[left]));
        }
        return order;
    }

    private int fastestSourceIndex() {
        int fastest = -1;
        for (int i = 0; i < sourceSpeeds.length; i++) {
            if (sourceSpeeds[i] >= 0 && (fastest < 0 || sourceSpeeds[i] > sourceSpeeds[fastest])) fastest = i;
        }
        return fastest;
    }

    private int sourceIndex(String id) {
        if (id == null || SOURCE_AUTO.equals(id)) return -1;
        for (int i = 0; i < SOURCE_IDS.length; i++) if (SOURCE_IDS[i].equals(id)) return i;
        return -1;
    }

    private Dialog createDialog(View content, boolean cancelable) {
        Dialog dialog = new Dialog(activity, R.style.UpdateDialog);
        dialog.setContentView(content);
        dialog.setCancelable(cancelable);
        dialog.setCanceledOnTouchOutside(false);
        Window window = dialog.getWindow();
        if (window != null) window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        return dialog;
    }

    private void applyDialogWidth(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        int maxWidth = dp(620);
        window.setLayout(Math.min((int) (screenWidth * 0.92f), maxWidth), WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private HttpURLConnection openConnection(String url, int connectTimeout, int readTimeout) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(connectTimeout);
        connection.setReadTimeout(readTimeout);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "FNTV/" + BuildConfig.VERSION_NAME);
        return connection;
    }

    private void setButtonState(boolean enabled, String text) {
        runOnUiThread(() -> {
            btnCheckUpdate.setEnabled(enabled);
            btnCheckUpdate.setText(text);
        });
    }

    private String friendlyError(Exception error) {
        if (error instanceof java.net.SocketTimeoutException) return "连接超时，请更换下载源后重试";
        if (error instanceof java.net.UnknownHostException) return "无法解析服务器地址";
        String message = error.getMessage();
        return TextUtils.isEmpty(message) ? error.getClass().getSimpleName() : message;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.CHINA, "%.1f KB", bytes / 1024d);
        return String.format(Locale.CHINA, "%.1f MB", bytes / 1024d / 1024d);
    }

    private String formatSpeed(double megabytesPerSecond) {
        return String.format(Locale.CHINA, "%.2f MB/s", megabytesPerSecond);
    }

    private String formatDuration(long seconds) {
        if (seconds < 60) return seconds + " 秒";
        return (seconds / 60) + " 分 " + (seconds % 60) + " 秒";
    }

    private void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try { closeable.close(); } catch (Exception ignored) {}
    }

    private void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) Log.w(TAG, "无法删除文件: " + file);
    }

    private void dismissDialog(Dialog dialog) {
        runOnUiThread(() -> {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        });
    }

    private boolean canUseActivity() {
        return !released && !activity.isFinishing() && !activity.isDestroyed();
    }

    private void runOnUiThread(Runnable runnable) {
        if (canUseActivity()) activity.runOnUiThread(runnable);
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density);
    }

    private interface StatusListener {
        void onStatus(String title, String detail);
    }

    private static class UpdateInfo {
        int versionCode;
        String versionName;
        String apkUrl;
        String changelog;
        boolean forceUpdate;
    }

    private static class SourceRow {
        final View root;
        final int sourceIndex;
        final TextView mark;
        final TextView name;
        final TextView description;
        final TextView speed;

        SourceRow(View root, int sourceIndex) {
            this.root = root;
            this.sourceIndex = sourceIndex;
            this.mark = root.findViewById(R.id.tvSourceMark);
            this.name = root.findViewById(R.id.tvSourceName);
            this.description = root.findViewById(R.id.tvSourceDescription);
            this.speed = root.findViewById(R.id.tvSourceSpeed);
        }
    }

    private static class DownloadCancelledException extends Exception {}
}
