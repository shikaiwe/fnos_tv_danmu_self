package com.fntv.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.fntv.app.adapter.HomeChipAdapter;
import com.fntv.app.adapter.HomeContinueAdapter;
import com.fntv.app.adapter.HomeLibrariesAdapter;
import com.fntv.app.adapter.HomePosterAdapter;
import com.fntv.app.api.FnApiManager;
import com.fntv.app.api.model.*;
import com.fntv.app.util.SimpleImageLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class HomeActivity extends AppCompatActivity {

    private View panelMovies, panelLibrary, panelSettings;
    private LinearLayout moviesContainer, libraryContainer;
    private LinearLayout pageOverview, sectionsContainer, sectionContinue, searchPanel;
    private NestedScrollView homeScroll;
    private RecyclerView rvLibraries, rvContinue, rvChips, rvSearchResults;
    private EditText etSearch, etHomeSearch;
    private TextView tvSearchEmpty;
    private TextView tvMoviesLoading, tvMoviesEmpty, tvLibraryLoading, tvLibraryEmpty;
    private boolean isSearching = false;
    private boolean libSearchActive = false;
    private boolean libBrowseMode = false;
    private int browseCols = 3;
    private int mergeGen = 0;
    private String lastBrowseTitle = "";
    private final Map<String, TextView> libRowCountViews = new HashMap<>();
    private final Map<String, TextView> catCountViews = new HashMap<>();
    private HomePosterAdapter libSearchAdapter, libBrowseAdapter;
    private RecyclerView rvLibrarySearchResults;
    private TextView tvLibrarySearchEmpty;
    private ImageView btnLibBack, btnLibSearch;
    private TextView tvLibTitle, tvLibSortLabel, tvLibBrowseCount, tvLibBrowseEmpty;
    private View libraryListPage, libraryBrowsePage, librarySearchPanel, btnLibSort,
            btnLibGridMode, btnLibFilter, rvLibBrowse;
    private boolean homeSearchActive = false;
    private String currentFilter = HomePosterAdapter.FILTER_ALL;
    private final Map<String, HomePosterAdapter> sectionAdapters = new LinkedHashMap<>();
    private final Map<String, View> sectionViews = new LinkedHashMap<>();
    private HomeLibrariesAdapter librariesAdapter;
    private HomeContinueAdapter continueAdapter;
    private HomeChipAdapter chipsAdapter;
    private HomePosterAdapter searchResultsAdapter;
    private ImageView btnBack, btnSearch;
    private View tabMovies, tabLibrary, tabSettings;
    private ImageView iconTabMovies, iconTabLibrary, iconTabSettings;
    private TextView textTabMovies, textTabLibrary, textTabSettings;
    private TextView tvSettingUsername, tvSettingServer, tvDecoderValue, tvDanmuUrl;
    private Button btnLogout;
    private UpdateManager updateManager;
    private RelativeLayout rlDecoderSetting, rlDanmuSetting, rlSeekStep, rlGestureSpeed;
    private TextView tvSeekStepValue, tvGestureSpeedValue;

    private int currentTab = 0;
    private final List<MediaDbItem> mediaLibraries = new ArrayList<>();
    private boolean showingOverview = true;
    private boolean showingEpisodes = false;
    private boolean loadingPreviews = false;

    // 媒体库浏览排序状态
    private String currentBrowseGuid;
    private String currentBrowseTitle;
    private LinearLayout currentBrowseContainer;
    private TextView currentBrowseLoading;
    private int libSortColumnIndex = 0; // 0=添加日期, 1=发行日期
    private int libSortOrderIndex = 1;  // 0=升序, 1=降序

    private FnApiManager apiManager;
    private String baseUrl = "";
    private SharedPreferences prefs;
    private static final String PREF_DECODER = "decoder_mode";

    private long t0;
    private boolean overviewBuilt = false;
    private long backPressedTime = 0;

    // 横竖屏切换时保存的页面状态
    private String savedBrowseGuid, savedBrowseTitle;
    private List<PlayListItem> savedBrowseList;
    private PlayListItem savedDetailItem;
    private PlayInfoResponse savedDetailInfo;
    private boolean browseFromLibrary;
    private String savedLiveChannelTitle;

    @Override

    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (savedDetailItem != null) {
            pageOverview.setVisibility(View.GONE);
            buildDetailPage(savedDetailItem, savedDetailInfo);
        } else if (savedBrowseList != null) {
            if (browseFromLibrary) { renderBrowseGrid(savedBrowseList, savedBrowseTitle); } else { renderGridInContainer(savedBrowseList, savedBrowseTitle, moviesContainer); }
        } else if (currentTab == 1 && libBrowseMode) {
            if (savedBrowseGuid != null) {
                browseItemsInContainer(savedBrowseGuid, savedBrowseTitle,
                        libraryContainer, tvLibraryLoading);
            } else if (savedBrowseList != null) {
                renderLibBrowseGrid(savedBrowseList);
            }
        } else if (currentTab == 0) {
            loadingPreviews = false;
            overviewBuilt = false;
            loadOverview();
        }
    }

    @Override

    protected void onCreate(Bundle savedInstanceState) {
        t0 = System.currentTimeMillis();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(getColor(R.color.bg_dark));
        }

        prefs = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        apiManager = FnApiManager.getInstance();
        baseUrl = prefs.getString("host", "").replaceAll("/+$", "");
        if (apiManager.getApi() == null && !baseUrl.isEmpty()) {
            apiManager.updateBaseUrl(baseUrl);
        }
        // 切换服务器时清空观看记录
        String lastHost = prefs.getString("last_host", "");
        if (!lastHost.equals(baseUrl) && !lastHost.isEmpty()) {
            prefs.edit().remove("watch_history").putString("last_host", baseUrl).apply();
        } else if (lastHost.isEmpty() && !baseUrl.isEmpty()) {
            prefs.edit().putString("last_host", baseUrl).apply();
        }
        initViews();
        setupTabs();
        setupSettings();
        setupLogout();
        setupSearch();
        setupLibBrowseBar();
        setupLibSearchResults();

        switchTab(0);
        tvMoviesLoading.setVisibility(View.VISIBLE);
        tvMoviesLoading.setText("正在加载媒体库...");
        loadOverview();
    }

    /** 媒体库搜索结果网格 */
    private void setupLibSearchResults() {
        libSearchAdapter = new HomePosterAdapter(baseUrl, apiManager.getClient(), this::showDetail, 0);
        rvLibrarySearchResults.setLayoutManager(new GridLayoutManager(this, 3));
        rvLibrarySearchResults.setAdapter(libSearchAdapter);
        btnLibBack.setOnClickListener(v -> onKeyDown(KeyEvent.KEYCODE_BACK,
                new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)));
    }


    private void initViews() {
        tabMovies = findViewById(R.id.tabMovies);
        tabLibrary = findViewById(R.id.tabLibrary);
        tabSettings = findViewById(R.id.tabSettings);
        iconTabMovies = findViewById(R.id.iconTabMovies);
        iconTabLibrary = findViewById(R.id.iconTabLibrary);
        iconTabSettings = findViewById(R.id.iconTabSettings);
        textTabMovies = findViewById(R.id.textTabMovies);
        textTabLibrary = findViewById(R.id.textTabLibrary);
        textTabSettings = findViewById(R.id.textTabSettings);
        panelMovies = findViewById(R.id.panelMovies);
        panelLibrary = findViewById(R.id.panelLibrary);
        panelSettings = findViewById(R.id.panelSettings);
        moviesContainer = findViewById(R.id.pageContent);
        libraryContainer = findViewById(R.id.libraryGridContainer);
        pageOverview = findViewById(R.id.pageOverview);
        sectionsContainer = findViewById(R.id.sectionsContainer);
        sectionContinue = findViewById(R.id.sectionContinue);
        homeScroll = findViewById(R.id.homeScroll);
        searchPanel = findViewById(R.id.searchPanel);
        rvLibraries = findViewById(R.id.rvLibraries);
        rvContinue = findViewById(R.id.rvContinue);
        rvChips = findViewById(R.id.rvChips);
        rvSearchResults = findViewById(R.id.rvSearchResults);
        btnBack = findViewById(R.id.btnBack);
        btnSearch = findViewById(R.id.btnSearch);
        etSearch = findViewById(R.id.etSearch);
        etHomeSearch = findViewById(R.id.etHomeSearch);
        tvSearchEmpty = findViewById(R.id.tvSearchEmpty);
        tvLibrarySearchEmpty = findViewById(R.id.tvLibrarySearchEmpty);
        rvLibrarySearchResults = findViewById(R.id.rvLibrarySearchResults);
        btnLibBack = findViewById(R.id.btnLibraryBack);
        btnLibSearch = findViewById(R.id.btnLibrarySearch);
        tvLibTitle = findViewById(R.id.tvLibraryTitle);
        libraryListPage = findViewById(R.id.libraryListPage);
        libraryBrowsePage = findViewById(R.id.libraryBrowsePage);
        librarySearchPanel = findViewById(R.id.librarySearchPanel);
        btnLibSort = findViewById(R.id.btnLibSort);
        tvLibSortLabel = findViewById(R.id.tvLibSortLabel);
        tvLibBrowseCount = findViewById(R.id.tvLibBrowseCount);
        btnLibGridMode = findViewById(R.id.btnLibGridMode);
        btnLibFilter = findViewById(R.id.btnLibFilter);
        rvLibBrowse = findViewById(R.id.rvLibBrowse);
        tvLibBrowseEmpty = findViewById(R.id.tvLibBrowseEmpty);
        tvMoviesLoading = findViewById(R.id.tvMoviesLoading);
        tvMoviesEmpty = findViewById(R.id.tvMoviesEmpty);
        tvLibraryLoading = findViewById(R.id.tvLibraryLoading);
        tvLibraryEmpty = findViewById(R.id.tvLibraryEmpty);
        tvSettingUsername = findViewById(R.id.tvSettingUsername);
        tvSettingServer = findViewById(R.id.tvSettingServer);
        tvDecoderValue = findViewById(R.id.tvDecoderValue);
        btnLogout = findViewById(R.id.btnLogout);
        rlDecoderSetting = findViewById(R.id.rlDecoderSetting);
        rlDanmuSetting = findViewById(R.id.rlDanmuSetting);
        rlSeekStep = findViewById(R.id.rlSeekStep);
        tvSeekStepValue = findViewById(R.id.tvSeekStepValue);
        rlGestureSpeed = findViewById(R.id.rlGestureSpeed);
        tvGestureSpeedValue = findViewById(R.id.tvGestureSpeedValue);
        tvDanmuUrl = findViewById(R.id.tvDanmuUrl);
        tvSettingServer.setText(prefs.getString("host", ""));

        TextView tvVersion = findViewById(R.id.tvVersionName);
        try {
            tvVersion.setText("FN TV v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception ignored) {}

        // 检查更新
        Button btnCheckUpdate = findViewById(R.id.btnCheckUpdate);
        updateManager = new UpdateManager(this, btnCheckUpdate, BuildConfig.VERSION_CODE);
        updateManager.setup();
    }


    // ======================== Tab ========================

    @Override

    protected void onResume() {
        super.onResume();
        if (savedDetailItem != null) {
            pageOverview.setVisibility(View.GONE);
            buildDetailPage(savedDetailItem, savedDetailInfo);
        } else if (currentTab == 1 && libBrowseMode) {
            if (savedBrowseGuid != null) {
                browseItemsInContainer(savedBrowseGuid, savedBrowseTitle,
                        libraryContainer, tvLibraryLoading);
            } else if (savedBrowseList != null) {
                renderLibBrowseGrid(savedBrowseList);
            }
        } else if (currentTab == 0 && !mediaLibraries.isEmpty() && overviewBuilt && showingOverview) {
            loadingPreviews = false;
            showOverview();
            loadAllPreviews();
            loadContinueWatching();
            loadLiveChannels();
        } else if (currentTab == 0 && !overviewBuilt && showingOverview) {
            loadOverview();
        }
    }


    private void setupTabs() {
        tabMovies.setOnClickListener(v -> switchTab(0));
        tabLibrary.setOnClickListener(v -> { switchTab(1); loadMediaLibraries(); });
        tabSettings.setOnClickListener(v -> switchTab(2));

        // 顶部栏：返回 = 模拟系统返回键；搜索 = 进入首页搜索模式
        btnBack.setOnClickListener(v -> onKeyDown(KeyEvent.KEYCODE_BACK,
                new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)));
        btnSearch.setOnClickListener(v -> enterHomeSearch());

        // 媒体库文件夹卡片行
        librariesAdapter = new HomeLibrariesAdapter(baseUrl, apiManager.getClient(),
                lib -> browseItems(lib.guid, lib.title));
        rvLibraries.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvLibraries.setAdapter(librariesAdapter);

        // 继续观看
        continueAdapter = new HomeContinueAdapter(baseUrl, apiManager.getClient(), item ->
                launchPlayer(item.guid, item.title, item.tvTitle != null ? item.tvTitle : "",
                        item.episodeNumber, item.poster, item.getCategoryLabel(),
                        item.ts, item.duration, item.parentGuid));
        rvContinue.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvContinue.setAdapter(continueAdapter);

        // 筛选 chips
        chipsAdapter = new HomeChipAdapter(this::onChipSelected);
        rvChips.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvChips.setAdapter(chipsAdapter);

        // 首页搜索结果（3 列网格）
        searchResultsAdapter = new HomePosterAdapter(baseUrl, apiManager.getClient(), item -> {
            exitHomeSearch();
            showDetail(item);
        }, 0);
        rvSearchResults.setLayoutManager(new GridLayoutManager(this, 3));
        rvSearchResults.setAdapter(searchResultsAdapter);

        setupHomeSearchInput();
    }

    /** chips 点击 → 客户端过滤各媒体库区块 */
    private void onChipSelected(int position) {
        currentFilter = position == 1 ? HomePosterAdapter.FILTER_MOVIE
                : position == 2 ? HomePosterAdapter.FILTER_TV : HomePosterAdapter.FILTER_ALL;
        applyChipFilter();
    }

    private void applyChipFilter() {
        for (Map.Entry<String, HomePosterAdapter> e : sectionAdapters.entrySet()) {
            boolean has = e.getValue().applyFilter(currentFilter);
            View sec = sectionViews.get(e.getKey());
            if (sec != null) sec.setVisibility(has ? View.VISIBLE : View.GONE);
        }
    }

    /** 媒体库内容计数（mediadb/sum），key 结构未知做防御性解析，失败则只显示标题 */
    private void loadMediaDbSum() {
        apiManager.getApi().getMediaDbSum().enqueue(new Callback<ApiResponse<Map<String, Integer>>>() {
            @Override
            public void onResponse(Call<ApiResponse<Map<String, Integer>>> call,
                                   Response<ApiResponse<Map<String, Integer>>> response) {
                if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                        || response.body().data == null) return;
                Log.d("Home", "mediadb/sum: " + new com.google.gson.Gson().toJson(response.body().data));
                Integer total = null, movie = null, tv = null;
                int otherSum = 0;
                for (Map.Entry<String, Integer> e : response.body().data.entrySet()) {
                    String k = e.getKey() == null ? "" : e.getKey().toLowerCase();
                    int v = e.getValue() == null ? 0 : e.getValue();
                    if (k.contains("movie") || k.contains("film") || k.contains("电影")) movie = v;
                    else if (k.equals("tv") || k.contains("tvshow") || k.contains("tv_show")
                            || k.contains("series") || k.contains("电视")) tv = v;
                    else if (k.contains("total") || k.contains("all") || k.contains("sum")) total = v;
                    else otherSum += v;
                }
                if (total == null && movie != null && tv != null) total = movie + tv;
                if (total == null && otherSum > 0) total = otherSum;
                chipsAdapter.setCounts(total, movie, tv);
            }
            @Override public void onFailure(Call<ApiResponse<Map<String, Integer>>> call, Throwable t) {}
        });
    }

    /** 在概览中添加一个媒体库区块（标题行 + 横向海报行） */
    private void addLibrarySection(MediaDbItem lib) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setTag("section_" + lib.guid);

        TextView header = new TextView(this);
        header.setText((lib.title != null ? lib.title : "") + "  ›");
        header.setTextColor(getColor(R.color.text_primary));
        header.setTextSize(17);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(dp(16), dp(10), dp(16), dp(2));
        header.setOnClickListener(v -> browseItems(lib.guid, lib.title));
        section.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rv.setClipToPadding(false);
        rv.setPadding(dp(12), 0, dp(12), 0);
        rv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        int cardW = dp(108);
        HomePosterAdapter ad = new HomePosterAdapter(baseUrl, apiManager.getClient(),
                this::showDetail, cardW);
        rv.setAdapter(ad);
        section.addView(rv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sectionAdapters.put(lib.guid, ad);
        sectionViews.put(lib.guid, section);
        sectionsContainer.addView(section, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ==================== 首页搜索 ====================

    private void setupHomeSearchInput() {
        etHomeSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                performHomeSearch(v.getText().toString().trim());
                hideKeyboard();
                return true;
            }
            return false;
        });
    }

    private void enterHomeSearch() {
        homeSearchActive = true;
        homeScroll.setVisibility(View.GONE);
        searchPanel.setVisibility(View.VISIBLE);
        etHomeSearch.setText("");
        rvSearchResults.setVisibility(View.GONE);
        tvSearchEmpty.setVisibility(View.GONE);
        etHomeSearch.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(etHomeSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
    }

    private void exitHomeSearch() {
        if (!homeSearchActive) return;
        homeSearchActive = false;
        searchPanel.setVisibility(View.GONE);
        homeScroll.setVisibility(View.VISIBLE);
        hideKeyboard();
    }

    private void performHomeSearch(String query) {
        if (query.isEmpty()) return;
        rvSearchResults.setVisibility(View.GONE);
        tvSearchEmpty.setText("搜索中...");
        tvSearchEmpty.setVisibility(View.VISIBLE);
        SearchHelper.search(apiManager, query, new SearchHelper.SearchCallback() {
            @Override
            public void onResults(List<PlayListItem> results) {
                tvSearchEmpty.setVisibility(View.GONE);
                rvSearchResults.setVisibility(View.VISIBLE);
                searchResultsAdapter.setItems(results);
            }
            @Override
            public void onEmpty() {
                rvSearchResults.setVisibility(View.GONE);
                tvSearchEmpty.setText("搜索无结果");
                tvSearchEmpty.setVisibility(View.VISIBLE);
            }
            @Override
            public void onError(String msg) {
                rvSearchResults.setVisibility(View.GONE);
                tvSearchEmpty.setText(msg);
                tvSearchEmpty.setVisibility(View.VISIBLE);
            }
        });
    }

    /** 底部导航选中态：图标+文字 选中白色/未选灰色 */
    private void setNavSelected(int index) {
        int active = getColor(R.color.text_white);
        int inactive = getColor(R.color.nav_inactive);
        iconTabMovies.setColorFilter(index == 0 ? active : inactive);
        iconTabLibrary.setColorFilter(index == 1 ? active : inactive);
        iconTabSettings.setColorFilter(index == 2 ? active : inactive);
        textTabMovies.setTextColor(index == 0 ? active : inactive);
        textTabLibrary.setTextColor(index == 1 ? active : inactive);
        textTabSettings.setTextColor(index == 2 ? active : inactive);
    }

    private void switchTab(int index) {
        currentTab = index;
        // 从媒体库切换到其他标签时清除搜索状态
        if (index != 1 && isSearching) clearSearch();
        panelMovies.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        panelLibrary.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        panelSettings.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        setNavSelected(index);
    }


    // ==================== 影视概览 ====================


    private void loadOverview() {
        Log.d("Overview", "loadOverview start  t=" + (System.currentTimeMillis() - t0) + "ms");
        showingOverview = true;
        overviewBuilt = false;
        loadingPreviews = false;
        tvMoviesLoading.setVisibility(View.VISIBLE);

        final int[] retryCount = {1};
        apiManager.getApi().getMediaDbList().enqueue(new Callback<ApiResponse<List<MediaDbItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<MediaDbItem>>> call,
                                   Response<ApiResponse<List<MediaDbItem>>> response) {
                String bodyStr = response.body() != null ? "code=" + response.body().code + " msg=" + response.body().msg + " data=" + (response.body().data != null ? response.body().data.size() + "条" : "null") : "nullBody";
                Log.d("Overview", "getMediaDbList resp code=" + response.code() + " " + bodyStr + " t=" + (System.currentTimeMillis() - t0) + "ms");
                if (response.body() != null && response.body().code != 0) {
                    try { Log.w("Overview", "错误响应: " + new com.google.gson.Gson().toJson(response.body())); } catch (Exception ignored) {}
                }
                // Auth Failed 时重试一次
                if (response.body() != null && response.body().code == -2 && retryCount[0] > 0) {
                    retryCount[0]--;
                    Log.d("Overview", "Auth Failed，重试中...");
                    call.clone().enqueue(this);
                    return;
                }
                if (response.isSuccessful() && response.body() != null && response.body().code == 0
                        && response.body().data != null && !response.body().data.isEmpty()) {
                    mediaLibraries.clear();
                    for (MediaDbItem lib : response.body().data) {
                        if (!lib.refreshDisabled) mediaLibraries.add(lib);
                    }
                    Log.d("Overview", "loaded " + mediaLibraries.size() + " libraries");
                    showOverview();
                    loadAllPreviews();
                    loadContinueWatching();
                    loadLiveChannels();
                    loadMediaDbSum();
                } else {
                    tvMoviesLoading.setVisibility(View.GONE);
                    tvMoviesEmpty.setVisibility(View.VISIBLE);
                }
            }
            @Override
            public void onFailure(Call<ApiResponse<List<MediaDbItem>>> call, Throwable t) {
                tvMoviesLoading.setVisibility(View.GONE);
                Log.e("Overview", "getMediaDbList onFailure: " + t.getMessage() + " t=" + (System.currentTimeMillis() - t0) + "ms");
            }
        });
    }

    /** 构建概览：重置文件夹行、chips、各媒体库区块 */

    private void showOverview() {
        tvMoviesLoading.setVisibility(View.GONE);
        savedDetailItem = null; savedDetailInfo = null; savedBrowseList = null; savedBrowseGuid = null;
        savedLiveChannelTitle = null;
        showingEpisodes = false;
        showingOverview = true;
        overviewBuilt = true;

        // 概览页 / 内容页切换
        pageOverview.setVisibility(View.VISIBLE);
        moviesContainer.setVisibility(View.GONE);
        moviesContainer.removeAllViews();

        // 媒体库文件夹卡片行
        librariesAdapter.setItems(mediaLibraries);
        rvLibraries.setVisibility(mediaLibraries.isEmpty() ? View.GONE : View.VISIBLE);

        // 继续观看（等 loadContinueWatching 回调填充）
        sectionContinue.setVisibility(View.GONE);
        continueAdapter.setItems(null);

        // 筛选 chips 重置为"全部"
        currentFilter = HomePosterAdapter.FILTER_ALL;
        chipsAdapter.setSelected(0);

        // 各媒体库区块（空区块占位，loadAllPreviews 后填充数据）
        sectionAdapters.clear();
        sectionViews.clear();
        sectionsContainer.removeAllViews();
        for (MediaDbItem lib : mediaLibraries) {
            addLibrarySection(lib);
        }

        tvMoviesEmpty.setVisibility(mediaLibraries.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** 加载各媒体库预览 */

    private void loadAllPreviews() {
        if (loadingPreviews) return;
        loadingPreviews = true;
        for (final MediaDbItem lib : mediaLibraries) {
            final String guid = lib.guid;
            apiManager.getApi().getItemList(ItemListRequest.browseLibrary(guid))
                .enqueue(new Callback<ApiResponse<ItemListResponse>>() {
                @Override
                public void onResponse(Call<ApiResponse<ItemListResponse>> call,
                                       Response<ApiResponse<ItemListResponse>> response) {
                    if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                            || response.body().data == null || response.body().data.list == null
                            || response.body().data.list.isEmpty()) return;
                    // 取前6个填到预览容器
                    List<PlayListItem> items = response.body().data.list;
                    if (items.size() > 20) items = items.subList(0, 20);
                    fillPreview(guid, items);
                }
                @Override public void onFailure(Call<ApiResponse<ItemListResponse>> call, Throwable t) {}
            });
        }
    }

    /** 填充媒体库区块（先清空再填充，防止重复） */

    private void fillPreview(String libGuid, List<PlayListItem> items) {
        HomePosterAdapter ad = sectionAdapters.get(libGuid);
        if (ad == null) return;
        ad.setItems(items);
        // 过滤后为空的区块整体隐藏
        boolean has = ad.applyFilter(currentFilter);
        View sec = sectionViews.get(libGuid);
        if (sec != null) sec.setVisibility(has ? View.VISIBLE : View.GONE);
    }


    private String makePosterUrl(String path) {
        if (path == null || path.isEmpty()) {
            Log.d("PosterUrl", "path is null/empty");
            return null;
        }
        String p = path.startsWith("/") ? path : "/" + path;
        String fullUrl = baseUrl + "/v/api/v1/sys/img" + p + "?w=400";
        Log.d("PosterUrl", "poster=" + path + " -> " + fullUrl);
        return fullUrl;
    }


    // ==================== 继续观看 ====================


    private void loadContinueWatching() {
        apiManager.getApi().getPlayList().enqueue(new Callback<ApiResponse<List<PlayListItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<PlayListItem>>> call,
                                   Response<ApiResponse<List<PlayListItem>>> response) {
                if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                        || response.body().data == null || response.body().data.isEmpty()) {
                    sectionContinue.setVisibility(View.GONE);
                    return;
                }
                sectionContinue.setVisibility(View.VISIBLE);
                continueAdapter.setItems(response.body().data);
            }
            @Override
            public void onFailure(Call<ApiResponse<List<PlayListItem>>> call, Throwable t) {}
        });
    }


    // ==================== 横向滚动卡片 ====================


    private void onItemClick(PlayListItem item) {
        showDetail(item);  // 全部走 getPlayInfo
    }


    // ==================== 查看全部 ====================


    private void browseItems(String ancestorGuid, String title) {
        browseItemsInContainer(ancestorGuid, title, moviesContainer, tvMoviesLoading);
    }

    /** 浏览请求代数：每次新的浏览/排序请求 +1，使仍在途的旧分页链失效 */
    private int browseGen = 0;

    private void browseItemsInContainer(String ancestorGuid, String title,
                                        LinearLayout container, TextView loadingView) {
        Log.d("Overview", "browseItems: guid=" + ancestorGuid + " title=" + title);
        savedDetailItem = null; savedDetailInfo = null;
        showingEpisodes = false;
        savedBrowseGuid = ancestorGuid; savedBrowseTitle = title;
        browseFromLibrary = (container == libraryContainer);
        // 首页：概览行 → 内容页（pageContent 挂载浏览网格）
        if (container == moviesContainer) {
            showingOverview = false;
            pageOverview.setVisibility(View.GONE);
            homeSearchActive = false;
            searchPanel.setVisibility(View.GONE);
            homeScroll.setVisibility(View.VISIBLE);
        }
        // 媒体库：列表页 → 浏览页
        if (browseFromLibrary) {
            libBrowseMode = true;
            libraryListPage.setVisibility(View.GONE);
            librarySearchPanel.setVisibility(View.GONE);
            libraryBrowsePage.setVisibility(View.VISIBLE);
            btnLibSearch.setVisibility(View.VISIBLE);
            tvLibTitle.setText(title);
            tvLibBrowseEmpty.setVisibility(View.GONE);
            rvLibBrowse.setVisibility(View.VISIBLE);
        }

        // 存储当前浏览上下文，排序变化时用于重新加载
        currentBrowseGuid = ancestorGuid;
        currentBrowseTitle = title;
        currentBrowseContainer = container;
        currentBrowseLoading = loadingView;

        container.removeAllViews();
        loadingView.setVisibility(View.VISIBLE);

        String sortColumn = libSortColumnIndex == 0 ? "create_time" : "release_date";
        String sortType = libSortOrderIndex == 0 ? "DESC" : "ASC";
        // 浏览代数：新一轮浏览/排序使旧的分页链全部失效，避免旧链渲染出重复网格
        final int gen = ++browseGen;
        fetchBrowsePages(gen, ancestorGuid, sortColumn, sortType,
                new java.util.ArrayList<>(), new java.util.HashSet<>(),
                container, loadingView, title, 1);
    }

    /** 每页请求条数与分页链的最大页数（防御 total 异常时的死循环） */
    private static final int BROWSE_PAGE_SIZE = 200;
    private static final int BROWSE_MAX_PAGES = 50;

    /** 逐页抓取媒体库内容直到取满 total，全部到齐后一次性渲染 */
    private void fetchBrowsePages(final int gen, final String ancestorGuid,
                                  final String sortColumn, final String sortType,
                                  final List<PlayListItem> acc, final java.util.Set<String> seen,
                                  final LinearLayout container, final TextView loadingView,
                                  final String title, final int page) {
        ItemListRequest request = new ItemListRequest(ancestorGuid,
                Arrays.asList("Movie", "TV", "Directory", "Video"),
                true, sortColumn, sortType, BROWSE_PAGE_SIZE);
        request.page = page;
        apiManager.getApi().getItemList(request)
                .enqueue(new Callback<ApiResponse<ItemListResponse>>() {
            @Override
            public void onResponse(Call<ApiResponse<ItemListResponse>> call,
                                   Response<ApiResponse<ItemListResponse>> response) {
                if (gen != browseGen) return; // 已有新的浏览/排序请求接管，丢弃本链
                Log.d("Overview", "browseItems page=" + page + " code=" + response.code());

                List<PlayListItem> list = null;
                int total = -1;
                if (response.isSuccessful() && response.body() != null && response.body().code == 0
                        && response.body().data != null && response.body().data.list != null) {
                    list = response.body().data.list;
                    total = response.body().data.total;
                    int added = 0;
                    for (PlayListItem it : list) {
                        String key = it.guid != null ? it.guid
                                : (it.title != null ? it.title : "") + "#" + it.type;
                        if (seen.add(key)) { acc.add(it); added++; }
                    }
                    Log.d("Overview", "page " + page + ": +" + added
                            + " 累计=" + acc.size() + " total=" + total);
                    // 服务器忽略 page 参数时会返回重复内容，新增数为 0 时终止分页
                    boolean hasMore = !list.isEmpty() && added > 0
                            && total > acc.size() && page < BROWSE_MAX_PAGES;
                    if (hasMore) {
                        fetchBrowsePages(gen, ancestorGuid, sortColumn, sortType,
                                acc, seen, container, loadingView, title, page + 1);
                        return;
                    }
                }
                finishBrowse(loadingView, container, title, acc);
            }
            @Override
            public void onFailure(Call<ApiResponse<ItemListResponse>> call, Throwable t) {
                if (gen != browseGen) return;
                // 网络失败：已取到的页照样展示（部分结果好于空白）
                Log.e("Overview", "browseItems page " + page + " 失败: " + t.getMessage());
                finishBrowse(loadingView, container, title, acc);
            }
        });
    }

    /** 分页抓取完成，渲染完整网格 */
    private void finishBrowse(TextView loadingView, LinearLayout container,
                              String title, List<PlayListItem> list) {
        if (loadingView != null) loadingView.setVisibility(View.GONE);

        // 媒体库浏览页：网格渲染到 rvLibBrowse
        if (browseFromLibrary) {
            savedBrowseList = new ArrayList<>(list);
            renderLibBrowseGrid(list);
            return;
        }

        if (!list.isEmpty()) {
            savedBrowseList = new java.util.ArrayList<>(list);

            TextView h = new TextView(HomeActivity.this);
            h.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            h.setPadding(6, 8, 6, 4);
            h.setText(title + "  (" + list.size() + "项)");
            h.setTextColor(getColor(R.color.text_primary));
            container.addView(h);

            // 排序筛选栏
            LinearLayout sortFilterBar = makeLibSortFilterBar();
            sortFilterBar.setTag("lib_sort_bar");
            container.addView(sortFilterBar);
            container.addView(makeSpacer(6));

            // 自适应列数网格（最小卡片宽200dp）
            float density = getResources().getDisplayMetrics().density;
            int cols = Math.max(3, (int) (getResources().getDisplayMetrics().widthPixels / (130 * density)));
            for (int idx = 0; idx < list.size(); idx += cols) {
                LinearLayout row = new LinearLayout(HomeActivity.this);
                row.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                row.setOrientation(LinearLayout.HORIZONTAL);
                int inRow = Math.min(cols, list.size() - idx);
                for (int c = 0; c < cols && idx + c < list.size(); c++) {
                    PlayListItem pli = list.get(idx + c);
                    View card = HomePosterAdapter.inflatePosterCard(HomeActivity.this);
                    HomePosterAdapter.bindPoster(card, pli, baseUrl, apiManager.getClient(),
                            v -> showDetail(pli));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                    lp.rightMargin = 6;
                    lp.leftMargin = 6;
                    card.setLayoutParams(lp);
                    row.addView(card);
                }
                // 补齐空位
                for (int e = inRow; e < cols; e++) {
                    View spacer = new View(HomeActivity.this);
                    spacer.setLayoutParams(new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
                    row.addView(spacer);
                }
                container.addView(row);
                container.addView(makeSpacer(12));
            }
            new Handler(Looper.getMainLooper()).post(() -> loadImagesLazily(container, 0));
        } else {
            TextView e = new TextView(HomeActivity.this);
            e.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 120));
            e.setGravity(Gravity.CENTER);
            e.setText("暂无内容");
            e.setTextColor(getResources().getColor(R.color.text_secondary));
            e.setTextSize(14);
            container.addView(e);
        }
    }

    /** 从缓存数据重绘浏览网格（横竖屏切换时调用） */

    private void renderBrowseGrid(List<PlayListItem> list, String title) {
        libBrowseMode = true;
        libraryListPage.setVisibility(View.GONE);
        libraryBrowsePage.setVisibility(View.VISIBLE);
        btnLibSearch.setVisibility(View.VISIBLE);
        tvLibTitle.setText(title);
        renderLibBrowseGrid(list);
    }

    /** 在指定容器中绘制缓存网格 */

    private void renderGridInContainer(List<PlayListItem> list, String title, LinearLayout container) {
        container.removeAllViews();
        int total = list.size();

        TextView h = new TextView(this);
        h.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        h.setPadding(6, 8, 6, 4);
        h.setText(title + "  (" + total + "项)");
        h.setTextColor(getColor(R.color.text_primary));
        h.setTextSize(14);
        container.addView(h);

        float density = getResources().getDisplayMetrics().density;
        int cols = Math.max(3, (int) (getResources().getDisplayMetrics().widthPixels / (130 * density)));
        for (int idx = 0; idx < list.size(); idx += cols) {
            LinearLayout row = new LinearLayout(this);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setOrientation(LinearLayout.HORIZONTAL);
            int inRow = Math.min(cols, list.size() - idx);
            for (int c = 0; c < cols && idx + c < list.size(); c++) {
                PlayListItem pli = list.get(idx + c);
                View card = HomePosterAdapter.inflatePosterCard(this);
                HomePosterAdapter.bindPoster(card, pli, baseUrl, apiManager.getClient(),
                        v -> showDetail(pli));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                lp.rightMargin = 6; lp.leftMargin = 6;
                card.setLayoutParams(lp);
                row.addView(card);
            }
            for (int e = inRow; e < cols; e++) {
                View spacer = new View(this);
                spacer.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
                row.addView(spacer);
            }
            container.addView(row);
            container.addView(makeSpacer(12));
        }
        new Handler(Looper.getMainLooper()).post(() -> loadImagesLazily(container, 0));
    }

    // ==================== 媒体库排序筛选 ====================

    /** 使用当前排序重新加载媒体库内容 */
    private void reFetchLibraryItems() {
        if (currentBrowseGuid == null || currentBrowseContainer == null) return;
        browseItemsInContainer(currentBrowseGuid, currentBrowseTitle,
                currentBrowseContainer, currentBrowseLoading);
    }

    /** 构建排序筛选栏 */
    private LinearLayout makeLibSortFilterBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundResource(R.drawable.bg_input);
        bar.setPadding(16, 18, 16, 18);

        // 排序方式标签
        TextView sortLabel = new TextView(this);
        sortLabel.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sortLabel.setGravity(Gravity.CENTER_VERTICAL);
        sortLabel.setText("排序方式: ");
        sortLabel.setTextColor(getColor(R.color.text_secondary));
        sortLabel.setTextSize(13);
        bar.addView(sortLabel);

        // 排序列选择按钮
        Button sortColumnBtn = new Button(this);
        sortColumnBtn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 78));
        sortColumnBtn.setBackgroundResource(R.drawable.bg_btn_primary);
        String[] colLabels = {"添加日期", "发行日期"};
        sortColumnBtn.setText(colLabels[libSortColumnIndex] + " ▾");
        sortColumnBtn.setTextColor(getResources().getColor(R.color.text_primary));
        sortColumnBtn.setTextSize(12);
        sortColumnBtn.setFocusable(true);
        sortColumnBtn.setPadding(14, 0, 14, 0);
        sortColumnBtn.setOnFocusChangeListener((v, hasFocus) -> {
            v.setScaleX(hasFocus ? 1.08f : 1.0f);
            v.setScaleY(hasFocus ? 1.08f : 1.0f);
        });
        final Button colBtnRef = sortColumnBtn;
        sortColumnBtn.setOnClickListener(v ->
                new android.app.AlertDialog.Builder(this)
                        .setTitle("排序方式")
                        .setSingleChoiceItems(colLabels, libSortColumnIndex,
                                (dialog, which) -> {
                                    libSortColumnIndex = which;
                                    colBtnRef.setText(colLabels[which] + " ▾");
                                    dialog.dismiss();
                                    reFetchLibraryItems();
                                })
                        .setNegativeButton("取消", null)
                        .show()
        );
        bar.addView(sortColumnBtn);

        // 弹性间隔
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        bar.addView(spacer);

        // 顺序标签
        TextView orderLabel = new TextView(this);
        orderLabel.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        orderLabel.setGravity(Gravity.CENTER_VERTICAL);
        orderLabel.setText("顺序: ");
        orderLabel.setTextColor(getColor(R.color.text_secondary));
        orderLabel.setTextSize(13);
        bar.addView(orderLabel);

        // 排序顺序选择按钮
        Button sortOrderBtn = new Button(this);
        sortOrderBtn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 78));
        sortOrderBtn.setBackgroundResource(R.drawable.bg_btn_primary);
        String[] orderLabels = {"升序", "降序"};
        sortOrderBtn.setText(orderLabels[libSortOrderIndex] + " ▾");
        sortOrderBtn.setTextColor(getResources().getColor(R.color.text_primary));
        sortOrderBtn.setTextSize(12);
        sortOrderBtn.setFocusable(true);
        sortOrderBtn.setPadding(14, 0, 14, 0);
        sortOrderBtn.setOnFocusChangeListener((v, hasFocus) -> {
            v.setScaleX(hasFocus ? 1.08f : 1.0f);
            v.setScaleY(hasFocus ? 1.08f : 1.0f);
        });
        final Button orderBtnRef = sortOrderBtn;
        sortOrderBtn.setOnClickListener(v ->
                new android.app.AlertDialog.Builder(this)
                        .setTitle("排序顺序")
                        .setSingleChoiceItems(orderLabels, libSortOrderIndex,
                                (dialog, which) -> {
                                    libSortOrderIndex = which;
                                    orderBtnRef.setText(orderLabels[which] + " ▾");
                                    dialog.dismiss();
                                    reFetchLibraryItems();
                                })
                        .setNegativeButton("取消", null)
                        .show()
        );
        bar.addView(sortOrderBtn);

        return bar;
    }

    /** 启动播放器 */
    private void launchPlayer(String guid, String title, String tvTitle, int epNum,
                              String poster, String cat, long ts, long dur) {
        launchPlayer(guid, title, tvTitle, epNum, poster, cat, ts, dur, null);
    }

    private void launchPlayer(String guid, String title, String tvTitle, int epNum,
                              String poster, String cat, long ts, long dur, String parentGuid) {
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra("guid", guid);
        intent.putExtra("title", title);
        intent.putExtra("tv_title", tvTitle);
        intent.putExtra("episode_number", epNum);
        intent.putExtra("poster", poster);
        intent.putExtra("category", cat);
        intent.putExtra("ts", ts);
        intent.putExtra("duration", dur);
        if (parentGuid != null) intent.putExtra("parent_guid", parentGuid);
        startActivity(intent);
    }


    // ==================== 详情页（getPlayInfo → 按类型展示） ====================


    private void showDetail(PlayListItem item) {
        switchTab(0);
        savedBrowseList = null; savedBrowseGuid = null;
        showingOverview = false;
        showingEpisodes = false;
        exitHomeSearch();
        pageOverview.setVisibility(View.GONE);
        moviesContainer.setVisibility(View.VISIBLE);
        moviesContainer.removeAllViews();
        tvMoviesLoading.setVisibility(View.VISIBLE);
        tvMoviesLoading.setText("加载中...");

        Map<String, String> body = new HashMap<>();
        body.put("item_guid", item.guid);
        apiManager.getApi().getPlayInfo(body).enqueue(new Callback<ApiResponse<PlayInfoResponse>>() {
            @Override
            public void onResponse(Call<ApiResponse<PlayInfoResponse>> call,
                                   Response<ApiResponse<PlayInfoResponse>> response) {
                tvMoviesLoading.setVisibility(View.GONE);
                if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                        || response.body().data == null) {
                    Toast.makeText(HomeActivity.this, "获取详情失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                buildDetailPage(item, response.body().data);
            }
            @Override
            public void onFailure(Call<ApiResponse<PlayInfoResponse>> call, Throwable t) {
                tvMoviesLoading.setVisibility(View.GONE);
                Toast.makeText(HomeActivity.this, "网络错误: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 构建详情页：Hero 背景图 + 元信息 + 播放行 + 简介 + 分季 + 链接 */

    private void buildDetailPage(PlayListItem item, PlayInfoResponse info) {
        pageOverview.setVisibility(View.GONE);
        moviesContainer.setVisibility(View.VISIBLE);
        moviesContainer.removeAllViews();
        savedDetailItem = item; savedDetailInfo = info;
        showingEpisodes = false;

        String backdropUrl = makePosterUrl(info.getBackdropPath());
        if (backdropUrl == null) backdropUrl = makePosterUrl(item.poster);
        final String heroUrl = backdropUrl;

        androidx.core.widget.NestedScrollView scroll = new androidx.core.widget.NestedScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // ── Hero 背景图 + 返回按钮 ──
        int heroH = (int) (getResources().getDisplayMetrics().widthPixels * 0.56f);
        FrameLayout hero = new FrameLayout(this);
        hero.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, heroH));

        RoundedImageView backdrop = new RoundedImageView(this);
        backdrop.setCornerRadius(0);
        backdrop.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setBackgroundColor(getColor(R.color.bg_card));
        if (heroUrl != null) {
            backdrop.setTag(heroUrl);
            new Handler(Looper.getMainLooper()).post(() ->
                    SimpleImageLoader.load(heroUrl, backdrop, apiManager.getClient()));
        }
        hero.addView(backdrop);

        View shade = new View(this);
        shade.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, heroH / 2, Gravity.BOTTOM));
        shade.setBackgroundResource(R.drawable.bg_gradient_bottom);
        hero.addView(shade);

        ImageView btnDetailBack = new ImageView(this);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(40), dp(40));
        blp.topMargin = dp(8);
        blp.leftMargin = dp(4);
        btnDetailBack.setLayoutParams(blp);
        btnDetailBack.setImageResource(R.drawable.ic_arrow_back);
        btnDetailBack.setColorFilter(getColor(R.color.text_white));
        btnDetailBack.setBackground(null);
        btnDetailBack.setOnClickListener(v -> onKeyDown(KeyEvent.KEYCODE_BACK,
                new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)));
        hero.addView(btnDetailBack);
        root.addView(hero);

        // ── 内容区 ──
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(14), dp(16), dp(20));

        // 元信息行：国家 / 分类 / 年份 / 清晰度
        StringBuilder meta = new StringBuilder();
        if (info.item != null && info.item.productionCountries != null
                && !info.item.productionCountries.isEmpty()) {
            meta.append(info.item.productionCountries.get(0)).append(" / ");
        }
        meta.append(item.getCategoryLabel());
        String year = firstYear(info.item != null && info.item.airDate != null
                ? info.item.airDate : item.airDate);
        if (!year.isEmpty()) meta.append(" / ").append(year);
        if (info.item != null && info.item.mediaStream != null
                && info.item.mediaStream.resolutions != null
                && !info.item.mediaStream.resolutions.isEmpty()) {
            meta.append(" / ").append(info.item.mediaStream.resolutions.get(0));
        }
        TextView metaLine = new TextView(this);
        metaLine.setText(meta.toString());
        metaLine.setTextColor(getColor(R.color.text_secondary));
        metaLine.setTextSize(13);
        body.addView(metaLine);
        body.addView(makeSpacer(dp(12)));

        // 播放行：播放 + 收藏 + 已看
        String typeStr = info.type != null ? info.type : item.type;
        final long pTs = info.ts > 0 ? info.ts : (item.ts > 0 ? item.ts : 0);
        String playLabel = pTs > 0 ? "▶  继续播放" : "▶  播放";
        body.addView(makePlayRow(playLabel, v -> playFromDetail(item, info)));

        // 简介（点击展开/收起）
        String overview = info.item != null && info.item.overview != null
                && !info.item.overview.isEmpty() ? info.item.overview : item.overview;
        if (overview != null && !overview.isEmpty()) {
            body.addView(makeSpacer(dp(14)));
            TextView ov = new TextView(this);
            ov.setText(overview);
            ov.setTextColor(getColor(R.color.text_secondary));
            ov.setTextSize(14);
            ov.setLineSpacing(dp(4), 1);
            ov.setMaxLines(3);
            ov.setEllipsize(TextUtils.TruncateAt.END);
            body.addView(ov);
            TextView toggle = new TextView(this);
            toggle.setText("更多");
            toggle.setTextColor(getColor(R.color.text_secondary));
            toggle.setTextSize(13);
            toggle.setPadding(0, dp(6), 0, 0);
            toggle.setGravity(Gravity.END);
            toggle.setOnClickListener(v -> {
                boolean expanded = ov.getMaxLines() > 3;
                ov.setMaxLines(expanded ? 3 : Integer.MAX_VALUE);
                toggle.setText(expanded ? "更多" : "收起");
            });
            body.addView(toggle);
        }

        // ── 分季卡片行 ──
        if ("TV".equals(typeStr) || item.isFolder()) {
            body.addView(makeSpacer(dp(10)));
            LinearLayout seasonsBox = new LinearLayout(this);
            seasonsBox.setOrientation(LinearLayout.VERTICAL);
            body.addView(seasonsBox);
            loadSeasonsInto(seasonsBox, item);
        }

        // ── 链接 ──
        LinearLayout links = new LinearLayout(this);
        links.setOrientation(LinearLayout.HORIZONTAL);
        if (item.imdbId != null && !item.imdbId.isEmpty()) {
            links.addView(makeLinkChip("IMDB",
                    "https://www.imdb.com/title/" + item.imdbId + "/"));
        }
        if (item.doubanId > 0) {
            links.addView(makeLinkChip("豆瓣",
                    "https://movie.douban.com/subject/" + item.doubanId + "/"));
        }
        if (links.getChildCount() > 0) {
            body.addView(makeSpacer(dp(16)));
            TextView linkLabel = new TextView(this);
            linkLabel.setText("链接");
            linkLabel.setTextColor(getColor(R.color.text_secondary));
            linkLabel.setTextSize(15);
            linkLabel.setTypeface(Typeface.DEFAULT_BOLD);
            body.addView(linkLabel);
            body.addView(makeSpacer(dp(8)));
            body.addView(links);
        }

        root.addView(body);
        scroll.addView(root);
        moviesContainer.addView(scroll);
    }

    /** 详情页播放：优先服务端返回的进度续播 */
    private void playFromDetail(PlayListItem item, PlayInfoResponse info) {
        final String pTV = info.item != null && info.item.tvTitle != null ? info.item.tvTitle : "";
        final String pParentGuid = info.parentGuid != null && !info.parentGuid.isEmpty()
                ? info.parentGuid : item.parentGuid;
        long rawDur = info.item != null && info.item.duration > 0 ? info.item.duration : 0;
        if (rawDur <= 0 && info.item != null && info.item.runtime > 0) rawDur = info.item.runtime * 60L;
        if (rawDur <= 0) rawDur = item.duration;
        final long pTs = info.ts > 0 ? info.ts : (item.ts > 0 ? item.ts : 0);
        launchPlayer(item.guid, item.title, pTV, item.episodeNumber, item.poster,
                item.getCategoryLabel(), pTs, rawDur, pParentGuid);
    }

    /** 播放行：蓝色播放按钮 + 收藏/已看圆形按钮 */
    private View makePlayRow(String playLabel, View.OnClickListener onPlay) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView playBtn = new TextView(this);
        playBtn.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1));
        playBtn.setBackgroundResource(R.drawable.bg_btn_play_blue);
        playBtn.setGravity(Gravity.CENTER);
        playBtn.setText(playLabel);
        playBtn.setTextColor(getColor(R.color.text_white));
        playBtn.setTextSize(16);
        playBtn.setTypeface(Typeface.DEFAULT_BOLD);
        playBtn.setOnClickListener(onPlay);
        row.addView(playBtn);

        ImageView fav = makeCircleIcon(R.drawable.ic_fav);
        fav.setOnClickListener(v -> Toast.makeText(this, "收藏功能开发中", Toast.LENGTH_SHORT).show());
        row.addView(fav);

        ImageView watched = makeCircleIcon(R.drawable.ic_eye);
        watched.setOnClickListener(v -> Toast.makeText(this, "已看功能开发中", Toast.LENGTH_SHORT).show());
        row.addView(watched);
        return row;
    }

    private ImageView makeCircleIcon(int iconRes) {
        ImageView iv = new ImageView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(48));
        lp.leftMargin = dp(12);
        iv.setLayoutParams(lp);
        iv.setBackgroundResource(R.drawable.bg_circle_dark);
        iv.setImageResource(iconRes);
        iv.setColorFilter(getColor(R.color.text_primary));
        iv.setPadding(dp(13), dp(13), dp(13), dp(13));
        return iv;
    }

    private String firstYear(String date) {
        return date != null && date.length() >= 4 ? date.substring(0, 4) : "";
    }

    private View makeLinkChip(String text, String url) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextColor(getColor(R.color.text_primary));
        chip.setTextSize(13);
        chip.setBackgroundResource(R.drawable.bg_resolution_badge);
        chip.setPadding(dp(16), 0, dp(16), 0);
        chip.setGravity(Gravity.CENTER);
        chip.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception e) {
                Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(chip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)));
        ((LinearLayout.LayoutParams) chip.getLayoutParams()).rightMargin = dp(10);
        return row;
    }

    /** 加载分季卡片行（横向海报卡，点击进入选集页） */
    private void loadSeasonsInto(LinearLayout box, PlayListItem item) {
        apiManager.getApi().getSeasonList(item.guid).enqueue(new Callback<ApiResponse<List<PlayListItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<PlayListItem>>> call,
                                   Response<ApiResponse<List<PlayListItem>>> response) {
                if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                        || response.body().data == null || response.body().data.isEmpty()) return;
                HorizontalScrollView hsv = new HorizontalScrollView(HomeActivity.this);
                hsv.setHorizontalScrollBarEnabled(false);
                hsv.setOverScrollMode(View.OVER_SCROLL_NEVER);
                LinearLayout row = new LinearLayout(HomeActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, dp(4), 0, 0);
                for (PlayListItem season : response.body().data) {
                    View card = HomePosterAdapter.inflatePosterCard(HomeActivity.this);
                    HomePosterAdapter.bindPoster(card, season, baseUrl, apiManager.getClient(),
                            v -> openSeasonPage(season));
                    TextView titleView = card.findViewById(R.id.tvPosterTitle);
                    titleView.setText("第" + (season.seasonNumber > 0 ? season.seasonNumber : 1) + "季");
                    titleView.setGravity(Gravity.CENTER);
                    TextView subView = card.findViewById(R.id.tvPosterSub);
                    subView.setGravity(Gravity.CENTER);
                    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                            dp(108), ViewGroup.LayoutParams.WRAP_CONTENT);
                    clp.rightMargin = dp(10);
                    row.addView(card, clp);
                }
                hsv.addView(row);
                box.addView(hsv, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            @Override public void onFailure(Call<ApiResponse<List<PlayListItem>>> call, Throwable t) {}
        });
    }

    /** 选集页：季海报头部 + 播放行 + 选集横滑列表 */
    private void openSeasonPage(PlayListItem season) {
        showingEpisodes = true;
        moviesContainer.removeAllViews();

        final String seriesTitle = season.tvTitle != null && !season.tvTitle.isEmpty()
                ? season.tvTitle : (season.title != null ? season.title : "");
        final int seasonNo = season.seasonNumber > 0 ? season.seasonNumber : 1;

        androidx.core.widget.NestedScrollView scroll = new androidx.core.widget.NestedScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(20));

        // 头部：海报 + 信息列
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        View poster = HomePosterAdapter.inflatePosterCard(this);
        HomePosterAdapter.bindPoster(poster, season, baseUrl, apiManager.getClient(), v -> {});
        head.addView(poster, new LinearLayout.LayoutParams(
                dp(108), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout infoCol = new LinearLayout(this);
        infoCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        ilp.leftMargin = dp(14);
        infoCol.setLayoutParams(ilp);

        TextView title = new TextView(this);
        title.setText(seriesTitle);
        title.setTextColor(getColor(R.color.text_white));
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        infoCol.addView(title);

        TextView seasonLabel = new TextView(this);
        seasonLabel.setText("第 " + seasonNo + " 季");
        seasonLabel.setTextColor(getColor(R.color.text_secondary));
        seasonLabel.setTextSize(14);
        seasonLabel.setPadding(0, dp(6), 0, 0);
        infoCol.addView(seasonLabel);

        String vote = season.voteAverage;
        if (vote != null && !vote.isEmpty() && !"0".equals(vote) && !"0.0".equals(vote)) {
            TextView voteView = new TextView(this);
            voteView.setText(vote + " 分");
            voteView.setTextColor(getColor(R.color.rating_badge_bg));
            voteView.setTextSize(14);
            voteView.setTypeface(Typeface.DEFAULT_BOLD);
            voteView.setPadding(0, dp(6), 0, 0);
            infoCol.addView(voteView);
        }

        String sub = HomePosterAdapter.buildSubTitle(season);
        if (!sub.isEmpty()) {
            TextView subView = new TextView(this);
            subView.setText(sub);
            subView.setTextColor(getColor(R.color.text_secondary));
            subView.setTextSize(13);
            subView.setPadding(0, dp(6), 0, 0);
            infoCol.addView(subView);
        }
        head.addView(infoCol);
        root.addView(head);

        // 播放行（剧集加载后更新按钮文案与点击目标）
        root.addView(makeSpacer(dp(14)));
        TextView playBtn = new TextView(this);
        playBtn.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1));
        playBtn.setBackgroundResource(R.drawable.bg_btn_play_blue);
        playBtn.setGravity(Gravity.CENTER);
        playBtn.setText("▶  播放");
        playBtn.setTextColor(getColor(R.color.text_white));
        playBtn.setTextSize(16);
        playBtn.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout playRow = new LinearLayout(this);
        playRow.setOrientation(LinearLayout.HORIZONTAL);
        playRow.setGravity(Gravity.CENTER_VERTICAL);
        playRow.addView(playBtn);
        ImageView fav = makeCircleIcon(R.drawable.ic_fav);
        fav.setOnClickListener(v -> Toast.makeText(this, "收藏功能开发中", Toast.LENGTH_SHORT).show());
        playRow.addView(fav);
        ImageView watched = makeCircleIcon(R.drawable.ic_eye);
        watched.setOnClickListener(v -> Toast.makeText(this, "已看功能开发中", Toast.LENGTH_SHORT).show());
        playRow.addView(watched);
        root.addView(playRow);

        // 选集标题行
        root.addView(makeSpacer(dp(18)));
        LinearLayout epHeader = new LinearLayout(this);
        epHeader.setOrientation(LinearLayout.HORIZONTAL);
        epHeader.setGravity(Gravity.BOTTOM);
        TextView epLabel = new TextView(this);
        epLabel.setText("选集");
        epLabel.setTextColor(getColor(R.color.text_primary));
        epLabel.setTextSize(17);
        epLabel.setTypeface(Typeface.DEFAULT_BOLD);
        epHeader.addView(epLabel);
        TextView epCount = new TextView(this);
        epCount.setText("加载中...");
        epCount.setTextColor(getColor(R.color.text_secondary));
        epCount.setTextSize(13);
        epCount.setPadding(dp(8), 0, 0, dp(2));
        epHeader.addView(epCount);
        root.addView(epHeader);

        // 选集横滑容器
        HorizontalScrollView epScroll = new HorizontalScrollView(this);
        epScroll.setHorizontalScrollBarEnabled(false);
        epScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout epRow = new LinearLayout(this);
        epRow.setOrientation(LinearLayout.HORIZONTAL);
        epRow.setPadding(0, dp(10), 0, 0);
        epScroll.addView(epRow);
        root.addView(epScroll);

        scroll.addView(root);
        moviesContainer.addView(scroll);

        // 加载剧集列表
        apiManager.getApi().getEpisodeList(season.guid).enqueue(new Callback<ApiResponse<List<PlayListItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<PlayListItem>>> call,
                                   Response<ApiResponse<List<PlayListItem>>> response) {
                if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                        || response.body().data == null || response.body().data.isEmpty()) {
                    epCount.setText("暂无剧集");
                    return;
                }
                List<PlayListItem> episodes = response.body().data;
                epCount.setText("共" + episodes.size());
                for (PlayListItem ep : episodes) {
                    epRow.addView(makeEpisodeCard(ep));
                }
                final PlayListItem target = pickFirstPlayable(episodes);
                if (target != null) {
                    String label = target.ts > 0 ? "▶  继续播放 第" + target.episodeNumber + "集"
                            : "▶  第" + target.episodeNumber + "集";
                    playBtn.setText(label);
                    playBtn.setOnClickListener(v -> playEpisode(target));
                }
            }
            @Override public void onFailure(Call<ApiResponse<List<PlayListItem>>> call, Throwable t) {
                epCount.setText("加载失败");
            }
        });
    }

    /** 选集页播放目标：优先有进度未看完的集，其次第一集未看，兜底第 1 集 */
    private PlayListItem pickFirstPlayable(List<PlayListItem> eps) {
        for (PlayListItem ep : eps) {
            if (ep.watched != 1 && ep.ts > 0) return ep;
        }
        for (PlayListItem ep : eps) {
            if (ep.watched != 1) return ep;
        }
        return eps.isEmpty() ? null : eps.get(0);
    }

    private void playEpisode(PlayListItem ep) {
        launchPlayer(ep.guid, ep.title, ep.tvTitle != null ? ep.tvTitle : "",
                ep.episodeNumber, ep.poster, ep.getCategoryLabel(), ep.ts, ep.duration, ep.parentGuid);
    }

    /** 选集横滑卡片：缩略图(清晰度角标) + 标题 + 简介(详情) + 时长 */
    private View makeEpisodeCard(PlayListItem ep) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                dp(210), ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.rightMargin = dp(12);
        card.setLayoutParams(clp);

        FrameLayout thumb = new FrameLayout(this);
        RoundedImageView iv = new RoundedImageView(this);
        iv.setCornerRadius(8);
        iv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(118)));
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundColor(getColor(R.color.img_placeholder_dark));
        String url = HomePosterAdapter.buildPosterUrl(baseUrl, ep.poster);
        iv.setTag(url);
        SimpleImageLoader.load(url, iv, apiManager.getClient());
        thumb.addView(iv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (ep.mediaStream != null && ep.mediaStream.resolutions != null
                && !ep.mediaStream.resolutions.isEmpty()) {
            TextView res = new TextView(this);
            res.setText(ep.mediaStream.resolutions.get(0));
            res.setTextColor(getColor(R.color.text_white));
            res.setTextSize(10);
            res.setBackgroundResource(R.drawable.bg_resolution_badge);
            res.setPadding(dp(5), 0, dp(5), 0);
            res.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(18));
            rlp.gravity = Gravity.BOTTOM | Gravity.END;
            rlp.rightMargin = dp(6);
            rlp.bottomMargin = dp(6);
            thumb.addView(res, rlp);
        }
        card.addView(thumb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(118)));

        TextView title = new TextView(this);
        title.setText((ep.episodeNumber > 0 ? ep.episodeNumber + ". " : "")
                + (ep.title != null ? ep.title : ""));
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(13);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, dp(8), 0, 0);
        card.addView(title);

        if (ep.overview != null && !ep.overview.isEmpty()) {
            TextView ov = new TextView(this);
            ov.setText(ep.overview);
            ov.setTextColor(getColor(R.color.text_secondary));
            ov.setTextSize(12);
            ov.setMaxLines(2);
            ov.setEllipsize(TextUtils.TruncateAt.END);
            ov.setPadding(0, dp(4), 0, 0);
            card.addView(ov);
            TextView more = new TextView(this);
            more.setText("详情");
            more.setTextColor(getColor(R.color.text_secondary));
            more.setTextSize(12);
            more.setPadding(0, dp(2), 0, 0);
            more.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                    .setTitle(title.getText())
                    .setMessage(ep.overview)
                    .setPositiveButton("关闭", null)
                    .show());
            card.addView(more);
        }

        if (ep.duration > 0) {
            TextView dur = new TextView(this);
            dur.setText(formatDuration(ep.duration));
            dur.setTextColor(getColor(R.color.text_hint));
            dur.setTextSize(12);
            dur.setPadding(0, dp(4), 0, 0);
            card.addView(dur);
        }

        card.setOnClickListener(v -> playEpisode(ep));
        return card;
    }

    // ==================== 媒体库 Tab ====================


    private void loadMediaLibraries() {
        isSearching = false;
        libSearchActive = false;
        librarySearchPanel.setVisibility(View.GONE);
        libBrowseMode = false;
        libraryBrowsePage.setVisibility(View.GONE);
        libraryListPage.setVisibility(View.VISIBLE);
        btnLibSearch.setVisibility(View.GONE);
        tvLibTitle.setText("媒体库");
        clearContainer(libraryContainer, tvLibraryLoading, tvLibraryEmpty);
        tvLibraryLoading.setVisibility(View.VISIBLE);

        final int[] retryCount = {1};
        apiManager.getApi().getMediaDbList().enqueue(new Callback<ApiResponse<List<MediaDbItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<MediaDbItem>>> call,
                                   Response<ApiResponse<List<MediaDbItem>>> response) {
                // Auth Failed 时重试一次
                if (response.body() != null && response.body().code == -2 && retryCount[0] > 0) {
                    retryCount[0]--;
                    Log.d("Home", "Auth Failed，重试中...");
                    call.clone().enqueue(this);
                    return;
                }
                if (response.isSuccessful() && response.body() != null && response.body().code == 0
                        && response.body().data != null && !response.body().data.isEmpty()) {
                    mediaLibraries.clear();
                    List<MediaDbItem> filteredLibs = new ArrayList<>();
                    for (MediaDbItem lib : response.body().data) {
                        if (!lib.refreshDisabled) filteredLibs.add(lib);
                    }
                    mediaLibraries.addAll(filteredLibs);
                    tvLibraryLoading.setVisibility(View.GONE);
                    renderLibListPage(filteredLibs);
                    fetchLibCounts();
                    loadLibSum();
                    return;
                }
                tvLibraryLoading.setVisibility(View.GONE);
                tvLibraryEmpty.setVisibility(View.VISIBLE);
            }
            @Override
            public void onFailure(Call<ApiResponse<List<MediaDbItem>>> call, Throwable t) {
                tvLibraryLoading.setVisibility(View.GONE);
                tvLibraryEmpty.setText("加载失败: " + t.getMessage());
                tvLibraryEmpty.setVisibility(View.VISIBLE);
            }
        });
    }

    // ==================== 媒体库浏览页（单库 + 合并分类） ====================

    /** 进入单个媒体库浏览页 */
    private void enterLibBrowse(String guid, String title) {
        libBrowseMode = true;
        libraryListPage.setVisibility(View.GONE);
        librarySearchPanel.setVisibility(View.GONE);
        libraryBrowsePage.setVisibility(View.VISIBLE);
        btnLibSearch.setVisibility(View.VISIBLE);
        tvLibTitle.setText(title);
        tvLibBrowseEmpty.setVisibility(View.GONE);
        browseItemsInContainer(guid, title, libraryContainer, tvLibraryLoading);
    }

    /** 初始化浏览页排序/视图/筛选栏交互 */
    private void setupLibBrowseBar() {
        btnLibSort.setOnClickListener(v -> showLibSortColumnDialog());
        btnLibGridMode.setOnClickListener(v -> showLibGridColsDialog());
        btnLibFilter.setOnClickListener(v -> showLibSortOrderDialog());
    }

    private void showLibSortColumnDialog() {
        final String[] options = {"按添加日期", "按发行日期"};
        new android.app.AlertDialog.Builder(this)
                .setTitle("排序方式")
                .setSingleChoiceItems(options, libSortColumnIndex, (dialog, which) -> {
                    libSortColumnIndex = which;
                    tvLibSortLabel.setText(options[which]);
                    dialog.dismiss();
                    reFetchOrRerender();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showLibSortOrderDialog() {
        final String[] options = {"降序（新→旧）", "升序（旧→新）"};
        new android.app.AlertDialog.Builder(this)
                .setTitle("排序顺序")
                .setSingleChoiceItems(options, libSortOrderIndex, (dialog, which) -> {
                    libSortOrderIndex = which;
                    dialog.dismiss();
                    reFetchOrRerender();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showLibGridColsDialog() {
        final String[] options = {"2 列", "3 列", "4 列"};
        new android.app.AlertDialog.Builder(this)
                .setTitle("视图列数")
                .setSingleChoiceItems(options, Math.max(0, Math.min(2, browseCols - 2)), (dialog, which) -> {
                    browseCols = which + 2;
                    dialog.dismiss();
                    if (savedBrowseList != null && libBrowseMode) {
                        renderLibBrowseGrid(savedBrowseList);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 排序变化后：单库浏览重新请求；合并浏览本地重排 */
    private void reFetchOrRerender() {
        if (currentBrowseGuid != null && libBrowseMode) {
            enterLibBrowse(currentBrowseGuid, currentBrowseTitle);
        } else if (savedBrowseList != null && libBrowseMode) {
            // 合并浏览：按当前排序设置本地重排
            List<PlayListItem> sorted = sortEpisodeList(savedBrowseList);
            savedBrowseList = sorted;
            renderLibBrowseGrid(sorted);
        }
    }

    /** 按 libSortOrderIndex 对本地列表排序（合并浏览无 create_time，用 airDate 近似） */
    private List<PlayListItem> sortEpisodeList(List<PlayListItem> src) {
        List<PlayListItem> out = new ArrayList<>(src);
        java.util.Collections.sort(out, (a, b) -> {
            String da = a.airDate != null ? a.airDate : "";
            String db = b.airDate != null ? b.airDate : "";
            return libSortOrderIndex == 0 ? db.compareTo(da) : da.compareTo(db);
        });
        return out;
    }

    /** 渲染浏览页网格（单库与合并分类共用） */
    private void renderLibBrowseGrid(List<PlayListItem> list) {
        tvLibraryLoading.setVisibility(View.GONE);
        rvLibBrowse.setVisibility(View.VISIBLE);
        tvLibBrowseCount.setText(String.valueOf(list.size()));
        if (list.isEmpty()) {
            tvLibBrowseEmpty.setText("暂无内容");
            tvLibBrowseEmpty.setVisibility(View.VISIBLE);
            rvLibBrowse.setVisibility(View.GONE);
        } else {
            tvLibBrowseEmpty.setVisibility(View.GONE);
        }
        androidx.recyclerview.widget.RecyclerView rv = (androidx.recyclerview.widget.RecyclerView) rvLibBrowse;
        rv.setLayoutManager(new GridLayoutManager(this, browseCols));
        libBrowseAdapter = new HomePosterAdapter(baseUrl, apiManager.getClient(), this::showDetail, 0);
        rv.setAdapter(libBrowseAdapter);
        libBrowseAdapter.setItems(list);
    }

    /** 分类合并浏览结果渲染 */
    private void showBrowseResult(int gen, String label, List<PlayListItem> items) {
        if (gen != mergeGen) return;
        List<PlayListItem> sorted = sortEpisodeList(items);
        savedBrowseList = sorted;
        savedBrowseTitle = label;
        renderLibBrowseGrid(sorted);
    }

    // ==================== 媒体库搜索（媒体库 Tab 搜索面板） ====================

    private void setupSearch() {
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_UNSPECIFIED
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                String q = v.getText().toString().trim();
                performSearch(q);
                hideKeyboard();
                return true;
            }
            return false;
        });
        btnLibSearch.setOnClickListener(v -> enterLibSearch());
    }

    /** 进入媒体库搜索模式（列表页/浏览页 → 搜索面板，顶部栏出现搜索图标用于退出） */
    private void enterLibSearch() {
        libSearchActive = true;
        libraryListPage.setVisibility(View.GONE);
        libraryBrowsePage.setVisibility(View.GONE);
        librarySearchPanel.setVisibility(View.VISIBLE);
        btnLibSearch.setVisibility(View.GONE);
        etSearch.setText("");
        rvLibrarySearchResults.setVisibility(View.GONE);
        tvLibrarySearchEmpty.setVisibility(View.GONE);
        etSearch.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(etSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
    }

    /** 退出媒体库搜索模式，回到进入前的页面（列表页或浏览页） */
    private void exitLibSearch() {
        if (!libSearchActive) return;
        libSearchActive = false;
        librarySearchPanel.setVisibility(View.GONE);
        if (libBrowseMode) {
            libraryBrowsePage.setVisibility(View.VISIBLE);
            btnLibSearch.setVisibility(View.VISIBLE);
        } else {
            libraryListPage.setVisibility(View.VISIBLE);
        }
        hideKeyboard();
    }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
            imm.hideSoftInputFromWindow(etHomeSearch.getWindowToken(), 0);
        }
    }

    private void performSearch(String query) {
        if (query.isEmpty()) {
            exitLibSearch();
            return;
        }
        isSearching = true;
        rvLibrarySearchResults.setVisibility(View.GONE);
        tvLibrarySearchEmpty.setText("搜索中...");
        tvLibrarySearchEmpty.setVisibility(View.VISIBLE);

        SearchHelper.search(apiManager, query, new SearchHelper.SearchCallback() {
            @Override
            public void onResults(List<PlayListItem> results) {
                tvLibrarySearchEmpty.setVisibility(View.GONE);
                rvLibrarySearchResults.setVisibility(View.VISIBLE);
                libSearchAdapter.setItems(results);
            }

            @Override
            public void onEmpty() {
                rvLibrarySearchResults.setVisibility(View.GONE);
                tvLibrarySearchEmpty.setText("搜索无结果");
                tvLibrarySearchEmpty.setVisibility(View.VISIBLE);
            }

            @Override
            public void onError(String msg) {
                rvLibrarySearchResults.setVisibility(View.GONE);
                tvLibrarySearchEmpty.setText(msg);
                tvLibrarySearchEmpty.setVisibility(View.VISIBLE);
            }
        });
    }

    private void clearSearch() {
        if (isSearching) {
            isSearching = false;
            etSearch.setText("");
        }
        exitLibSearch();
    }

    // ==================== 媒体库列表页（收藏/已下载 + 媒体库 + 分类） ====================

    /** 分类定义：label + 类型过滤 + 图标 */
    private static final String[][] CATEGORIES = {
            {"全部", "ALL", "ic_dashboard"},
            {"电影", "Movie", "ic_movie"},
            {"电视节目", "TV", "ic_tv"},
            {"电视直播", "LIVE", "ic_live_tv"},
            {"其他", "OTHER", "ic_nav_library"},
    };

    /** 渲染列表页：收藏/已下载卡片组 + 媒体库组 + 分类组 */
    private void renderLibListPage(List<MediaDbItem> libs) {
        libraryContainer.removeAllViews();
        libRowCountViews.clear();
        catCountViews.clear();

        // ── 收藏 / 已下载 卡片组 ──
        LinearLayout group1 = makeGroupCard();
        group1.addView(makeActionRow("收藏", "ic_fav", "0", v -> Toast.makeText(this,
                "收藏功能开发中", Toast.LENGTH_SHORT).show()));
        group1.addView(makeDivider());
        group1.addView(makeActionRow("已下载", "ic_download", "0", v -> Toast.makeText(this,
                "下载功能开发中", Toast.LENGTH_SHORT).show()));
        libraryContainer.addView(group1);

        // ── 媒体库 标题 + 卡片组 ──
        libraryContainer.addView(makeSectionLabel("媒体库"));
        LinearLayout group2 = makeGroupCard();
        for (int i = 0; i < libs.size(); i++) {
            MediaDbItem lib = libs.get(i);
            TextView countView = makeCountView();
            libRowCountViews.put(lib.guid, countView);
            group2.addView(makeRow(lib.title, iconForLibrary(lib), countView,
                    v -> enterLibBrowse(lib.guid, lib.title)));
            if (i < libs.size() - 1) group2.addView(makeDivider());
        }
        libraryContainer.addView(group2);

        // ── 分类 标题 + 卡片组 ──
        libraryContainer.addView(makeSectionLabel("分类"));
        LinearLayout group3 = makeGroupCard();
        for (int i = 0; i < CATEGORIES.length; i++) {
            final int idx = i;
            TextView countView = makeCountView();
            String key = CATEGORIES[i][1];
            catCountViews.put(key, countView);
            group3.addView(makeRow(CATEGORIES[i][0], CATEGORIES[i][2], countView,
                    v -> enterCategoryBrowse(idx)));
            if (i < CATEGORIES.length - 1) group3.addView(makeDivider());
        }
        libraryContainer.addView(group3);
    }

    /** 媒体库类型 → 图标 */
    private String iconForLibrary(MediaDbItem lib) {
        return "TV".equals(lib.category) ? "ic_tv" : "ic_nav_library";
    }

    private LinearLayout makeGroupCard() {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackgroundResource(R.drawable.bg_group_card);
        group.setPadding(0, dp(6), 0, dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        group.setLayoutParams(lp);
        return group;
    }

    private TextView makeSectionLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(getColor(R.color.text_primary));
        label.setTextSize(17);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setPadding(dp(4), dp(20), dp(4), dp(2));
        return label;
    }

    /** 列表页行：图标 + 标题 + 计数 + 箭头 */
    private View makeRow(String title, String iconName, TextView countView, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_settings_item);
        row.setPadding(dp(16), 0, dp(16), 0);

        ImageView icon = new ImageView(this);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
        icon.setColorFilter(getColor(R.color.text_primary));
        int resId = getResources().getIdentifier(iconName, "drawable", getPackageName());
        icon.setImageResource(resId > 0 ? resId : R.drawable.ic_nav_library);
        row.addView(icon);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(getColor(R.color.text_primary));
        titleView.setTextSize(15);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tp.leftMargin = dp(14);
        titleView.setLayoutParams(tp);
        row.addView(titleView);

        if (countView != null) {
            row.addView(countView);
        }

        ImageView chevron = new ImageView(this);
        chevron.setLayoutParams(new LinearLayout.LayoutParams(dp(20), dp(20)));
        chevron.setColorFilter(getColor(R.color.text_hint));
        chevron.setImageResource(R.drawable.ic_chevron_right);
        LinearLayout.LayoutParams cp = (LinearLayout.LayoutParams) chevron.getLayoutParams();
        cp.leftMargin = dp(6);
        chevron.setLayoutParams(cp);
        row.addView(chevron);

        row.setOnClickListener(click);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        return row;
    }

    private TextView makeCountView() {
        TextView count = new TextView(this);
        count.setTextColor(getColor(R.color.text_secondary));
        count.setTextSize(14);
        count.setVisibility(View.GONE);
        return count;
    }

    private View makeDivider() {
        View v = new View(this);
        v.setBackgroundColor(getColor(R.color.divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.leftMargin = dp(16);
        lp.rightMargin = dp(16);
        v.setLayoutParams(lp);
        return v;
    }

    /** 收藏/已下载行动作（占位） */
    private View makeActionRow(String title, String iconName, String countText, View.OnClickListener click) {
        TextView count = makeCountView();
        count.setText(countText);
        count.setVisibility(View.VISIBLE);
        return makeRow(title, iconName, count, click);
    }

    /** 拉取媒体库/分类计数（mediadb/sum），失败则不显示 */
    private void loadLibSum() {
        apiManager.getApi().getMediaDbSum().enqueue(new Callback<ApiResponse<Map<String, Integer>>>() {
            @Override
            public void onResponse(Call<ApiResponse<Map<String, Integer>>> call,
                                   Response<ApiResponse<Map<String, Integer>>> response) {
                if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                        || response.body().data == null) return;
                Log.d("Home", "mediadb/sum: " + new com.google.gson.Gson().toJson(response.body().data));
                Integer total = null, movie = null, tv = null, live = null, other = null;
                for (Map.Entry<String, Integer> e : response.body().data.entrySet()) {
                    String k = e.getKey() == null ? "" : e.getKey().toLowerCase();
                    int v = e.getValue() == null ? 0 : e.getValue();
                    if (k.contains("movie") || k.contains("film") || k.contains("电影")) movie = v;
                    else if (k.contains("live") || k.contains("直播")) live = v;
                    else if (k.contains("tv") || k.contains("series") || k.contains("电视")) tv = v;
                    else if (k.contains("other") || k.contains("其他") || k.contains("video")) other = v;
                    else if (k.contains("total") || k.contains("all") || k.contains("sum")) total = v;
                }
                setCount(catCountViews.get("ALL"), total);
                setCount(catCountViews.get("Movie"), movie);
                setCount(catCountViews.get("TV"), tv);
                setCount(catCountViews.get("LIVE"), live);
                setCount(catCountViews.get("OTHER"), other);
            }
            @Override public void onFailure(Call<ApiResponse<Map<String, Integer>>> call, Throwable t) {}
        });
    }

    /** 各媒体库条目计数（逐库请求 total，全部到齐前先到达的先显示） */
    private void fetchLibCounts() {
        for (MediaDbItem lib : mediaLibraries) {
            final String guid = lib.guid;
            apiManager.getApi().getItemList(ItemListRequest.browseLibrary(guid))
                    .enqueue(new Callback<ApiResponse<ItemListResponse>>() {
                        @Override
                        public void onResponse(Call<ApiResponse<ItemListResponse>> call,
                                               Response<ApiResponse<ItemListResponse>> response) {
                            if (!response.isSuccessful() || response.body() == null
                                    || response.body().code != 0 || response.body().data == null) return;
                            TextView tv = libRowCountViews.get(guid);
                            if (tv != null && response.body().data.total > 0) {
                                tv.setText(String.valueOf(response.body().data.total));
                                tv.setVisibility(View.VISIBLE);
                            }
                        }
                        @Override public void onFailure(Call<ApiResponse<ItemListResponse>> call, Throwable t) {}
                    });
        }
    }

    private void setCount(TextView tv, Integer val) {
        if (tv == null) return;
        if (val != null && val >= 0) {
            tv.setText(String.valueOf(val));
            tv.setVisibility(View.VISIBLE);
        } else {
            tv.setVisibility(View.GONE);
        }
    }

    // ==================== 媒体库分类合并浏览 ====================

    /** 进入分类浏览：全部=跨库合并；电影/电视节目=类型过滤合并；直播=直播频道 */
    private void enterCategoryBrowse(int categoryIndex) {
        String type = CATEGORIES[categoryIndex][1];
        String label = CATEGORIES[categoryIndex][0];
        switch (type) {
            case "LIVE":
                browseLiveChannels();
                return;
            case "ALL":
                browseMerged(mediaLibraries, label, null);
                return;
            case "Movie":
                browseMerged(mediaLibraries, label, it -> "Movie".equals(it.type));
                return;
            case "TV":
                browseMerged(mediaLibraries, label, it -> "TV".equals(it.type) || "Episode".equals(it.type));
                return;
            default:
                browseMerged(mediaLibraries, label,
                        it -> !"Movie".equals(it.type) && !"TV".equals(it.type) && !"Episode".equals(it.type)
                                && !"LiveChannel".equals(it.type));
        }
    }

    /** 跨库拉取（每库取全部分页），按类型过滤后合并渲染 */
    private void browseMerged(List<MediaDbItem> libs, String label,
                              java.util.function.Predicate<PlayListItem> typeFilter) {
        isSearching = false;
        libSearchActive = false;
        librarySearchPanel.setVisibility(View.GONE);
        libBrowseMode = true;
        libraryListPage.setVisibility(View.GONE);
        libraryBrowsePage.setVisibility(View.VISIBLE);
        btnLibSearch.setVisibility(View.VISIBLE);
        tvLibTitle.setText(label);
        savedDetailItem = null;
        savedDetailInfo = null;
        showingEpisodes = false;
        showingOverview = false;
        savedBrowseList = null;
        savedBrowseGuid = null;
        browseFromLibrary = true;
        currentBrowseGuid = null;
        currentBrowseTitle = label;

        tvLibBrowseEmpty.setVisibility(View.GONE);
        rvLibBrowse.setVisibility(View.VISIBLE);
        libBrowseAdapter.setItems(new ArrayList<>());

        final int gen = ++mergeGen;
        final List<PlayListItem> merged = java.util.Collections.synchronizedList(new ArrayList<>());
        final java.util.Set<String> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final int libCount = libs.size();
        if (libCount == 0) {
            showBrowseResult(gen, label, merged);
            return;
        }
        final int[] done = {0};

        for (MediaDbItem lib : libs) {
            fetchAllPages(lib.guid, 1, new ArrayList<>(), new java.util.HashSet<>(), new ArrayList<>(),
                    (items) -> {
                        if (gen != mergeGen) return;
                        synchronized (merged) {
                            for (PlayListItem it : items) {
                                String key = it.guid != null ? it.guid
                                        : (it.title != null ? it.title : "") + "#" + it.type;
                                if (seen.add(key) && (typeFilter == null || typeFilter.test(it))) {
                                    merged.add(it);
                                }
                            }
                        }
                        done[0]++;
                        if (done[0] >= libCount) showBrowseResult(gen, label, merged);
                    });
        }
    }

    /** 递归取完某库所有分页 */
    private void fetchAllPages(String guid, int page, List<PlayListItem> acc,
                               java.util.Set<String> seen, List<String> pagesFetched,
                               java.util.function.Consumer<List<PlayListItem>> doneCb) {
        ItemListRequest request = new ItemListRequest(guid,
                Arrays.asList("Movie", "TV", "Directory", "Video"),
                true, "create_time", "DESC", BROWSE_PAGE_SIZE);
        request.page = page;
        apiManager.getApi().getItemList(request).enqueue(new Callback<ApiResponse<ItemListResponse>>() {
            @Override
            public void onResponse(Call<ApiResponse<ItemListResponse>> call,
                                   Response<ApiResponse<ItemListResponse>> response) {
                List<PlayListItem> list = null;
                int total = -1;
                int added = 0;
                if (response.isSuccessful() && response.body() != null && response.body().code == 0
                        && response.body().data != null && response.body().data.list != null) {
                    list = response.body().data.list;
                    total = response.body().data.total;
                    for (PlayListItem it : list) {
                        String key = it.guid != null ? it.guid
                                : (it.title != null ? it.title : "") + "#" + it.type;
                        if (seen.add(key)) { acc.add(it); added++; }
                    }
                }
                boolean hasMore = list != null && !list.isEmpty() && added > 0
                        && total > acc.size() && page < BROWSE_MAX_PAGES;
                if (hasMore) {
                    fetchAllPages(guid, page + 1, acc, seen, pagesFetched, doneCb);
                } else {
                    doneCb.accept(acc);
                }
            }
            @Override
            public void onFailure(Call<ApiResponse<ItemListResponse>> call, Throwable t) {
                doneCb.accept(acc);
            }
        });
    }


    // ==================== 设置 ====================


    private void setupSettings() {
        tvSettingUsername.setText("用户名: " + prefs.getString("user", ""));
        String d = prefs.getString(PREF_DECODER, "hardware");
        tvDecoderValue.setText("hardware".equals(d) ? "硬解" : "软解");
        rlDecoderSetting.setOnClickListener(v -> toggleDecoder());

        // 弹幕服务器
        String danmuUrl = prefs.getString("danmu_url", "");
        if (danmuUrl.isEmpty()) {
            String host = prefs.getString("host", "");
            host = host.replaceAll("^https?://", "").replaceAll("/.*$", "").replaceAll(":\\d+$", "");
            danmuUrl = "http://" + host + ":9321";
        }
        tvDanmuUrl.setText(danmuUrl);
        rlDanmuSetting.setOnClickListener(v -> {
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
            b.setTitle("弹幕服务器地址");
            final android.widget.EditText input = new android.widget.EditText(this);
            input.setText(tvDanmuUrl.getText());
            input.setSelection(input.getText().length());
            b.setView(input);
            b.setPositiveButton("保存", (dialog, which) -> {
                String val = input.getText().toString().trim();
                if (!val.isEmpty()) {
                    prefs.edit().putString("danmu_url", val).apply();
                    tvDanmuUrl.setText(val);
                }
            });
            b.setNegativeButton("重置", (dialog, which) -> {
                prefs.edit().remove("danmu_url").apply();
                String host = prefs.getString("host", "");
                host = host.replaceAll("^https?://", "").replaceAll("/.*$", "").replaceAll(":\\d+$", "");
                tvDanmuUrl.setText("http://" + host + ":9321");
            });
            b.show();
        });

        // 快进退步长
        final int[] savedStep = {prefs.getInt("seek_step", 10)};
        tvSeekStepValue.setText(savedStep[0] + "s");
        rlSeekStep.setOnClickListener(v -> {
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
            b.setTitle("快进退步长（秒）");
            final android.widget.EditText input = new android.widget.EditText(this);
            input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            input.setText(String.valueOf(savedStep[0]));
            input.setSelection(input.getText().length());
            b.setView(input);
            b.setPositiveButton("保存", (dialog, which) -> {
                try {
                    int val = Integer.parseInt(input.getText().toString().trim());
                    if (val < 1) val = 1;
                    if (val > 300) val = 300;
                    prefs.edit().putInt("seek_step", val).apply();
                    tvSeekStepValue.setText(val + "s");
                    savedStep[0] = val;
                } catch (Exception ignored) {}
            });
            b.setNegativeButton("取消", null);
            b.show();
        });

        // 长按倍速
        final float[] speedOptions = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f, 4.0f};
        final float[] savedSpeed = {prefs.getFloat("gesture_speed", 2.0f)};
        tvGestureSpeedValue.setText(formatSpeedText(savedSpeed[0]));
        rlGestureSpeed.setOnClickListener(v -> {
            final String[] labels = new String[speedOptions.length];
            for (int i = 0; i < speedOptions.length; i++) labels[i] = formatSpeedText(speedOptions[i]);
            new android.app.AlertDialog.Builder(this)
                    .setTitle("长按倍速")
                    .setItems(labels, (dialog, which) -> {
                        if (which < 0 || which >= speedOptions.length) return;
                        savedSpeed[0] = speedOptions[which];
                        prefs.edit().putFloat("gesture_speed", savedSpeed[0]).apply();
                        tvGestureSpeedValue.setText(formatSpeedText(savedSpeed[0]));
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });

        apiManager.getApi().getUserInfo().enqueue(new Callback<ApiResponse<UserInfoResponse>>() {
            @Override
            public void onResponse(Call<ApiResponse<UserInfoResponse>> call,
                                   Response<ApiResponse<UserInfoResponse>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().code == 0
                        && response.body().data != null) {
                    tvSettingUsername.setText("用户名: " + response.body().data.getDisplayName());
                }
            }
            @Override public void onFailure(Call<ApiResponse<UserInfoResponse>> call, Throwable t) {}
        });
    }


    private void toggleDecoder() {
        String cur = prefs.getString(PREF_DECODER, "hardware");
        if ("hardware".equals(cur)) {
            prefs.edit().putString(PREF_DECODER, "software").apply();
            tvDecoderValue.setText("软解");
            Toast.makeText(this, "解码: 软解 (CPU)", Toast.LENGTH_SHORT).show();
        } else {
            prefs.edit().putString(PREF_DECODER, "hardware").apply();
            tvDecoderValue.setText("硬解");
            Toast.makeText(this, "解码: 硬解 (GPU)", Toast.LENGTH_SHORT).show();
        }
    }

    /** 倍速显示文案（如 1.0x / 1.25x） */
    private String formatSpeedText(float s) {
        return (s == (int) s) ? ((int) s) + "x" : s + "x";
    }


    // ==================== 登出 ====================


    private void setupLogout() {
        btnLogout.setOnClickListener(v -> logout());
    }


    private void logout() {
        apiManager.setToken(null);
        Toast.makeText(this, "已退出", Toast.LENGTH_SHORT).show();
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("skip_auto_login", true);
        startActivity(intent);
        finish();
    }


    // ==================== 工具 ====================


    private void clearContainer(LinearLayout c, TextView l, TextView e) {
        l.setVisibility(View.GONE);
        e.setVisibility(View.GONE);
        for (int i = c.getChildCount() - 1; i >= 0; i--) {
            View v = c.getChildAt(i);
            if (v != l && v != e) c.removeView(v);
        }
    }


    private View makeSpacer(int h) {
        View v = new View(HomeActivity.this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h));
        return v;
    }


    private String formatDuration(long sec) {
        if (sec <= 0) return "";
        long s = sec % 60;
        long m = (sec / 60) % 60;
        long h = sec / 3600;
        if (h > 0) return h + "h" + m + "m" + s + "s";
        return m + "分" + s + "秒";
    }

    /** 逐张加载图片 */

    private void loadImagesLazily(ViewGroup container, int index) {
        List<ImageView> targets = new ArrayList<>();
        collectImageViews(container, targets);
        if (targets.isEmpty() || index >= targets.size()) return;

        ImageView iv = targets.get(index);
        Object tag = iv.getTag();
        if (tag instanceof String) {
            String url = (String) tag;
            if (url.startsWith("http")) {
                SimpleImageLoader.load(url, iv, apiManager.getClient());
            }
        }
        final int next = index + 1;
        if (next < targets.size()) {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() { loadImagesLazily(container, next); }
            }, 100);
        }
    }


    private void collectImageViews(ViewGroup parent, List<ImageView> out) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof ImageView) {
                out.add((ImageView) child);
            } else if (child instanceof ViewGroup) {
                collectImageViews((ViewGroup) child, out);
            }
        }
    }


    // ==================== 直播频道 ====================


    /** 加载直播频道（total>0 则显示预览区） */
    private void loadLiveChannels() {
        try {
            ItemListRequest liveReq = ItemListRequest.browseLiveChannels();
            Log.d("LiveChannel", "请求体: " + new com.google.gson.Gson().toJson(liveReq));
            Log.d("LiveChannel", "loadLiveChannels 开始请求...");
            apiManager.getApi().getItemList(liveReq).enqueue(new Callback<ApiResponse<ItemListResponse>>() {
                @Override
                public void onResponse(Call<ApiResponse<ItemListResponse>> call,
                                       Response<ApiResponse<ItemListResponse>> response) {
                    try {
                        Log.d("LiveChannel", "响应 code=" + response.code()
                                + " isSuccessful=" + response.isSuccessful());
                        // 打印请求信息
                        okhttp3.Request req = call.request();
                        Log.d("LiveChannel", "请求URL: " + req.url());
                        Log.d("LiveChannel", "请求Method: " + req.method());
                        Log.d("LiveChannel", "请求Headers:");
                        for (int i = 0; i < req.headers().size(); i++) {
                            Log.d("LiveChannel", "  " + req.headers().name(i) + ": " + req.headers().value(i));
                        }
                        if (response.body() != null) {
                            Log.d("LiveChannel", "body code=" + response.body().code
                                    + " msg=" + response.body().msg
                                    + " data=" + new com.google.gson.Gson().toJson(response.body()));
                        } else {
                            Log.w("LiveChannel", "body=null");
                        }
                        if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                                || response.body().data == null || response.body().data.list == null
                                || response.body().data.list.isEmpty()) return;
                        List<PlayListItem> items = response.body().data.list;
                        int total = response.body().data.total;
                        Log.d("LiveChannel", "直播频道: total=" + total + " items=" + items.size());
                        if (total > 0) {
                            List<PlayListItem> preview = items.size() > 20 ? items.subList(0, 20) : items;
                            fillLiveChannelPreview(preview, total);
                        }
                    } catch (Exception e) {
                        Log.e("LiveChannel", "onResponse 异常", e);
                    }
                }
                @Override
                public void onFailure(Call<ApiResponse<ItemListResponse>> call, Throwable t) {
                    Log.e("LiveChannel", "请求失败: " + t.getMessage(), t);
                }
            });
        } catch (Exception e) {
            Log.e("LiveChannel", "loadLiveChannels 异常", e);
        }
    }

    /** 填充直播频道预览区（先移除旧的再添加） */
    private void fillLiveChannelPreview(List<PlayListItem> items, int total) {
        // 移除已有的直播频道区域
        for (int i = sectionsContainer.getChildCount() - 1; i >= 0; i--) {
            View v = sectionsContainer.getChildAt(i);
            if (v instanceof LinearLayout && "live_channel".equals(v.getTag())) {
                sectionsContainer.removeView(v);
            }
        }

        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setTag("live_channel");
        section.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── 标题行（与其他媒体库区块一致，点击 = 查看全部）──
        TextView header = new TextView(this);
        header.setText("直播频道  ›");
        header.setTextColor(getColor(R.color.text_primary));
        header.setTextSize(17);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(dp(16), dp(10), dp(16), dp(2));
        header.setOnClickListener(v -> browseLiveChannels());
        section.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── 横向卡片预览（复用 makeLiveChannelCard，逐项重建视图） ──
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rv.setClipToPadding(false);
        rv.setPadding(dp(12), dp(4), dp(12), 0);
        rv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        List<PlayListItem> liveItems = new ArrayList<>(items);
        rv.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                FrameLayout holder = new FrameLayout(HomeActivity.this);
                holder.setLayoutParams(new FrameLayout.LayoutParams(dp(110), dp(190)));
                return new RecyclerView.ViewHolder(holder) {};
            }
            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                FrameLayout holderFrame = (FrameLayout) holder.itemView;
                holderFrame.removeAllViews();
                View card = makeLiveChannelCard(liveItems.get(position));
                card.setLayoutParams(new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                holderFrame.addView(card);
            }
            @Override
            public int getItemCount() { return liveItems.size(); }
        });
        section.addView(rv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        section.addView(makeSpacer(16));

        // 插入到概览区块容器
        sectionsContainer.addView(section);
    }

    /** 直播频道卡片（彩色角标 + 标题，用于概览横滑行与浏览网格） */
    private View makeLiveChannelCard(PlayListItem item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(false);

        // 频道全名 + 彩色背景
        String shortName = item.title != null && !item.title.isEmpty() ? item.title : "?";
        int[] colors = {getResources().getColor(R.color.semantic_red), getResources().getColor(R.color.semantic_blue), getResources().getColor(R.color.semantic_green), getResources().getColor(R.color.semantic_orange),
                        getResources().getColor(R.color.semantic_purple), getResources().getColor(R.color.semantic_teal), getResources().getColor(R.color.semantic_brown), getResources().getColor(R.color.semantic_gray)};
        int colorIdx = item.guid != null ? Math.abs(item.guid.hashCode() % colors.length) : 0;
        float r = 10 * getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable badgeBg = new android.graphics.drawable.GradientDrawable();
        badgeBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        badgeBg.setCornerRadius(r);
        badgeBg.setColor(colors[colorIdx]);
        TextView channelBadge = new TextView(this);
        channelBadge.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        channelBadge.setGravity(Gravity.CENTER);
        channelBadge.setText(shortName);
        channelBadge.setTextColor(getColor(R.color.text_white));
        int len = shortName.length();
        channelBadge.setTextSize(len <= 2 ? 28 : len <= 4 ? 22 : len <= 6 ? 16 : 13);
        channelBadge.setTypeface(Typeface.DEFAULT_BOLD);
        channelBadge.setBackground(badgeBg);
        card.addView(channelBadge);

        // 底部文字条：类型 + 标题
        LinearLayout textBar = new LinearLayout(this);
        textBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));
        textBar.setOrientation(LinearLayout.VERTICAL);
        textBar.setGravity(Gravity.CENTER_VERTICAL);

        final TextView title = new TextView(this);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextSize(12);
        title.setTextColor(getResources().getColor(R.color.text_primary));
        title.setText(item.title != null ? item.title : "未知");
        textBar.addView(title);

        card.addView(textBar);

        card.setTag(item);
        card.setOnClickListener(v -> {
            PlayListItem it = (PlayListItem) card.getTag();
            launchPlayer(it.guid, it.title, "", 0, null, "LiveChannel", 0, 0, it.parentGuid);
        });
        return card;
    }

    /** 直播频道查看全部 */
    private void browseLiveChannels() {
        savedDetailItem = null;
        savedDetailInfo = null;
        showingEpisodes = false;
        showingOverview = false;
        savedLiveChannelTitle = "直播频道";
        pageOverview.setVisibility(View.GONE);
        moviesContainer.setVisibility(View.VISIBLE);
        moviesContainer.removeAllViews();
        tvMoviesLoading.setVisibility(View.VISIBLE);
        tvMoviesLoading.setText("加载直播频道...");

        apiManager.getApi().getItemList(ItemListRequest.browseLiveChannels()).enqueue(new Callback<ApiResponse<ItemListResponse>>() {
            @Override
            public void onResponse(Call<ApiResponse<ItemListResponse>> call,
                                   Response<ApiResponse<ItemListResponse>> response) {
                tvMoviesLoading.setVisibility(View.GONE);
                if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                        || response.body().data == null || response.body().data.list == null
                        || response.body().data.list.isEmpty()) {
                    TextView e = new TextView(HomeActivity.this);
                    e.setLayoutParams(new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 120));
                    e.setGravity(Gravity.CENTER);
                    e.setText("暂无直播频道");
                    e.setTextColor(getResources().getColor(R.color.text_secondary));
                    e.setTextSize(14);
                    moviesContainer.addView(e);
                    return;
                }
                List<PlayListItem> list = response.body().data.list;
                int total = response.body().data.total;
                Log.d("LiveChannel", "直播频道查看全部: total=" + total + " items=" + list.size()
                        + " resp=" + new com.google.gson.Gson().toJson(response.body()));
                renderLiveChannelGrid(list, total);
            }
            @Override
            public void onFailure(Call<ApiResponse<ItemListResponse>> call, Throwable t) {
                tvMoviesLoading.setVisibility(View.GONE);
                Toast.makeText(HomeActivity.this, "加载直播频道失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 渲染直播频道网格（自适应列数） */
    private void renderLiveChannelGrid(List<PlayListItem> list, int total) {
        // 标题
        TextView h = new TextView(this);
        h.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        h.setPadding(6, 8, 6, 4);
        h.setText("直播频道  (" + total + "项)");
        h.setTextColor(getResources().getColor(R.color.text_primary));
        h.setTextSize(14);
        moviesContainer.addView(h);

        // 自适应列数
        float density = getResources().getDisplayMetrics().density;
        int cols = Math.max(3, (int) (getResources().getDisplayMetrics().widthPixels / (130 * density)));

        for (int idx = 0; idx < list.size(); idx += cols) {
            LinearLayout row = new LinearLayout(this);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setOrientation(LinearLayout.HORIZONTAL);
            int inRow = Math.min(cols, list.size() - idx);
            for (int c = 0; c < cols && idx + c < list.size(); c++) {
                PlayListItem pli = list.get(idx + c);
                View card = makeLiveChannelCard(pli);
                // 图片占位区域高度与其他卡片一致：基于列数自适应
                if (card instanceof ViewGroup) {
                    View ch = ((ViewGroup) card).getChildAt(0);
                    int posterH = Math.min(550, (getResources().getDisplayMetrics().widthPixels / cols) * 3 / 2);
                    ch.setLayoutParams(new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, posterH));
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                lp.rightMargin = 6;
                lp.leftMargin = 6;
                card.setLayoutParams(lp);
                row.addView(card);
            }
            // 补齐空位
            for (int e = inRow; e < cols; e++) {
                View spacer = new View(this);
                spacer.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
                row.addView(spacer);
            }
            moviesContainer.addView(row);
            moviesContainer.addView(makeSpacer(8));
        }
    }


    // ==================== 按键 ====================

    @Override

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            // 媒体库搜索模式 → 退出搜索
            if (libSearchActive) {
                exitLibSearch();
                return true;
            }
            // 首页搜索模式 → 退出搜索回概览
            if (homeSearchActive) {
                exitHomeSearch();
                return true;
            }
            // 媒体库浏览页 → 返回媒体库列表页
            if (currentTab == 1 && libBrowseMode) {
                libBrowseMode = false;
                savedBrowseGuid = null; savedBrowseList = null;
                currentBrowseGuid = null;
                mergeGen++; // 使在途合并请求失效
                tvLibTitle.setText("媒体库");
                libraryBrowsePage.setVisibility(View.GONE);
                loadMediaLibraries();
                return true;
            }
            // 剧集选择页 → 返回详情页
            if (showingEpisodes && savedDetailItem != null && savedDetailInfo != null) {
                showingEpisodes = false;
                pageOverview.setVisibility(View.GONE);
                buildDetailPage(savedDetailItem, savedDetailInfo);
                return true;
            }
            // 详情页/浏览网格 → 回到概览
            if (!showingOverview) {
                loadOverview();
                return true;
            }
            if (currentTab != 0) {
                switchTab(0);
                return true;
            }
            if (backPressedTime + 2000 > System.currentTimeMillis()) {
                finish();
            } else {
                backPressedTime = System.currentTimeMillis();
                Toast.makeText(this, "再按一次返回桌面", Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
