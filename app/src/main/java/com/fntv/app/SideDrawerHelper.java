package com.fntv.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 右侧抽屉式二级菜单（参考图样式）：
 * 半透明深色面板从右侧滑入，占屏宽约 38%，带标题、可选"调整"入口、
 * 可选操作按钮区（如"+ 字幕"）和圆角卡片列表行（选中行蓝色描边 + 勾选图标）。
 * 统一替换播放器内所有 AlertDialog 列表型二级菜单。
 */
public class SideDrawerHelper {

    /** 列表行描述 */
    public static class Item {
        public final String title;
        /** 副标题（如编码格式），可空 */
        public final String subtitle;
        /** 是否选中（蓝色描边 + 勾） */
        public final boolean selected;
        /** 可删除行（外挂字幕）显示垃圾桶图标 */
        public final boolean deletable;

        public Item(String title, String subtitle, boolean selected, boolean deletable) {
            this.title = title;
            this.subtitle = subtitle;
            this.selected = selected;
            this.deletable = deletable;
        }

        public Item(String title, String subtitle, boolean selected) {
            this(title, subtitle, selected, false);
        }
    }

    public interface OnItemClickListener {
        void onClick(int position);
    }

    public interface OnDeleteClickListener {
        void onClick(int position);
    }

    private final Activity activity;
    private Dialog dialog;
    private TextView sideTitle;
    private TextView sideAdjust;
    private LinearLayout sideActions;
    private LinearLayout sideList;

    /** 当前渲染的行视图，用于刷新选中态 */
    private final java.util.List<View> rowViews = new java.util.ArrayList<>();
    private List<Item> items;
    private OnItemClickListener itemClick;
    private OnDeleteClickListener deleteClick;

    public SideDrawerHelper(Activity activity) {
        this.activity = activity;
    }

    /**
     * 显示抽屉
     *
     * @param title       标题（如"字幕""音轨""选集"）
     * @param customContent 自定义内容视图（如媒体信息文本，null 隐藏）
     * @param items       列表数据
     * @param adjustLabel "调整"入口文字（null 隐藏入口）
     * @param adjustAction "调整"入口回调
     * @param actionLabel  操作按钮文字（如"+ 字幕"，null 隐藏按钮区）
     * @param actionClick  操作按钮回调
     */
    public void show(String title, View customContent, List<Item> items,
                     String adjustLabel, Runnable adjustAction,
                     String actionLabel, Runnable actionClick,
                     OnItemClickListener itemClick, OnDeleteClickListener deleteClick) {
        this.items = items;
        this.itemClick = itemClick;
        this.deleteClick = deleteClick;

        dismiss();
        dialog = new Dialog(activity, R.style.SideDrawerDialog);
        dialog.setContentView(R.layout.panel_side_drawer);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            // 靠右、全高，宽度约 38%（最小 320dp）
            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            int width = Math.max((int) (screenWidth * 0.38f),
                    (int) (320 * activity.getResources().getDisplayMetrics().density));
            w.setLayout(width, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setGravity(Gravity.END);
        }

        sideTitle = dialog.findViewById(R.id.sideTitle);
        sideAdjust = dialog.findViewById(R.id.sideAdjust);
        sideActions = dialog.findViewById(R.id.sideActions);
        sideList = dialog.findViewById(R.id.sideList);
        LinearLayout sideCustom = dialog.findViewById(R.id.sideCustom);
        View sideCustomScroll = dialog.findViewById(R.id.sideCustomScroll);
        View sideListScroll = dialog.findViewById(R.id.sideListScroll);

        sideTitle.setText(title);

        if (adjustLabel != null && adjustAction != null) {
            sideAdjust.setVisibility(View.VISIBLE);
            sideAdjust.setText(adjustLabel);
            sideAdjust.setOnClickListener(v -> adjustAction.run());
        } else {
            sideAdjust.setVisibility(View.GONE);
        }

        sideActions.removeAllViews();
        if (actionLabel != null && actionClick != null) {
            sideActions.setVisibility(View.VISIBLE);
            TextView action = new TextView(activity);
            action.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (int) (48 * activity.getResources().getDisplayMetrics().density)));
            action.setBackgroundResource(R.drawable.bg_side_action);
            action.setGravity(Gravity.CENTER);
            action.setText(actionLabel);
            action.setTextColor(activity.getColor(R.color.text_white));
            action.setTextSize(16);
            action.setFocusable(true);
            action.setOnClickListener(v -> actionClick.run());
            sideActions.addView(action);
        } else {
            sideActions.setVisibility(View.GONE);
        }

        sideCustom.removeAllViews();
        if (customContent != null) {
            sideCustom.setVisibility(View.VISIBLE);
            sideCustom.addView(customContent);
            if (sideCustomScroll != null) sideCustomScroll.setVisibility(View.VISIBLE);
        } else {
            sideCustom.setVisibility(View.GONE);
            if (sideCustomScroll != null) sideCustomScroll.setVisibility(View.GONE);
        }
        // 纯自定义内容抽屉（items 为 null）隐藏列表滚动区，让内容独占抽屉；
        // items 非空列表（含空列表的"暂无可选项"空态）保持可见
        if (sideListScroll != null) {
            sideListScroll.setVisibility(items == null && customContent != null ? View.GONE : View.VISIBLE);
        }

        renderRows();

        dialog.show();
    }

    /** 简化重载（无自定义内容区） */
    public void show(String title, List<Item> items,
                     String adjustLabel, Runnable adjustAction,
                     String actionLabel, Runnable actionClick,
                     OnItemClickListener itemClick, OnDeleteClickListener deleteClick) {
        show(title, null, items, adjustLabel, adjustAction, actionLabel, actionClick, itemClick, deleteClick);
    }

    /**
     * 显示纯自定义内容抽屉（无列表，如"媒体信息"）。
     * 调用方持有返回的 Dialog 以控制关闭（setOnDismissListener 等）。
     *
     * @param title 标题
     * @param content 自定义内容视图
     * @return 已 show 的 Dialog
     */
    public Dialog showCustom(String title, View content) {
        show(title, content, (List<Item>) null,
                (String) null, (Runnable) null, (String) null, (Runnable) null,
                (OnItemClickListener) null, (OnDeleteClickListener) null);
        return dialog;
    }

    /** 渲染全部列表行 */
    private void renderRows() {
        sideList.removeAllViews();
        rowViews.clear();
        if (items == null) return;
        float density = activity.getResources().getDisplayMetrics().density;
        for (int i = 0; i < items.size(); i++) {
            sideList.addView(buildRow(items.get(i), i, density));
        }
        if (items.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("暂无可选项");
            empty.setTextColor(activity.getColor(R.color.text_hint));
            empty.setTextSize(14);
            empty.setPadding(0, (int) (24 * density), 0, (int) (24 * density));
            empty.setGravity(Gravity.CENTER);
            sideList.addView(empty);
        }
    }

    /** 构建单行：勾选图标 + 标题/副标题 + 可选删除按钮 */
    private View buildRow(final Item item, final int position, float density) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (10 * density);
        row.setLayoutParams(lp);
        int pad = (int) (14 * density);
        row.setPadding(pad, (int) (10 * density), pad, (int) (10 * density));
        row.setBackgroundResource(item.selected ? R.drawable.bg_side_row_active : R.drawable.bg_side_row);
        row.setFocusable(true);
        // 焦点高亮：沿用文字选项的柔和高亮描边
        row.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) v.setBackgroundResource(R.drawable.bg_side_row_active);
            else v.setBackgroundResource(item.selected ? R.drawable.bg_side_row_active : R.drawable.bg_side_row);
        });

        if (item.selected) {
            ImageView check = new ImageView(activity);
            check.setImageResource(R.drawable.ic_side_check);
            check.setLayoutParams(new LinearLayout.LayoutParams(
                    (int) (22 * density), (int) (22 * density)));
            row.addView(check);
            ((LinearLayout.LayoutParams) check.getLayoutParams()).rightMargin = pad;
        } else {
            // 占位，保持文字对齐
            View spacer = new View(activity);
            spacer.setLayoutParams(new LinearLayout.LayoutParams(
                    (int) (22 * density + pad), 1));
            row.addView(spacer);
        }

        LinearLayout textCol = new LinearLayout(activity);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(activity);
        title.setText(item.title);
        title.setTextColor(item.selected ? activity.getColor(R.color.text_white) : activity.getColor(R.color.text_primary));
        title.setTextSize(16);
        textCol.addView(title);

        if (item.subtitle != null && !item.subtitle.isEmpty()) {
            TextView sub = new TextView(activity);
            sub.setText(item.subtitle);
            sub.setTextColor(activity.getColor(R.color.text_secondary));
            sub.setTextSize(13);
            textCol.addView(sub);
        }
        row.addView(textCol);

        if (item.deletable && deleteClick != null) {
            ImageView del = new ImageView(activity);
            del.setImageResource(R.drawable.ic_delete);
            del.setLayoutParams(new LinearLayout.LayoutParams(
                    (int) (22 * density), (int) (22 * density)));
            del.setFocusable(true);
            del.setOnClickListener(v -> deleteClick.onClick(position));
            row.addView(del);
            ((LinearLayout.LayoutParams) del.getLayoutParams()).leftMargin = pad;
        }

        row.setOnClickListener(v -> {
            dismiss();
            if (itemClick != null) itemClick.onClick(position);
        });
        rowViews.add(row);
        return row;
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    /** 获取当前 Dialog（供调用方设置 OnDismissListener 等），未显示时返回 null */
    public Dialog getDialog() {
        return dialog;
    }

    public void dismiss() {
        if (dialog != null) {
            try { dialog.dismiss(); } catch (Exception ignored) {}
            dialog = null;
        }
    }
}
