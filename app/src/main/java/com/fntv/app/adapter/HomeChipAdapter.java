package com.fntv.app.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fntv.app.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 首页筛选 chips（全部/电影/电视节目 + 数量），选中态由 bg_chip_card 的 state_selected 控制
 */
public class HomeChipAdapter extends RecyclerView.Adapter<HomeChipAdapter.VH> {

    public interface Listener {
        void onChipSelected(int position);
    }

    private final List<String> labels = new ArrayList<>();
    private final List<Integer> counts = new ArrayList<>();
    private int selected = 0;
    private final Listener listener;

    public HomeChipAdapter(Listener listener) {
        this.listener = listener;
        labels.add("全部");
        labels.add("电影");
        labels.add("电视节目");
        counts.add(null);
        counts.add(null);
        counts.add(null);
    }

    /** 数量来源 mediadb/sum，未知传 null 则不显示 */
    public void setCounts(Integer total, Integer movie, Integer tv) {
        counts.set(0, total);
        counts.set(1, movie);
        counts.set(2, tv);
        notifyDataSetChanged();
    }

    public void setSelected(int position) {
        if (position < 0 || position >= labels.size()) return;
        selected = position;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_home_chip, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        h.label.setText(labels.get(position));
        Integer c = counts.get(position);
        if (c != null && c >= 0) {
            h.count.setText(String.valueOf(c));
            h.count.setVisibility(View.VISIBLE);
        } else {
            h.count.setVisibility(View.GONE);
        }
        h.itemView.setSelected(position == selected);
        h.itemView.setOnClickListener(v -> {
            if (position == selected) return;
            selected = position;
            notifyDataSetChanged();
            if (listener != null) listener.onChipSelected(position);
        });
    }

    @Override
    public int getItemCount() {
        return labels.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView label, count;
        VH(View v) {
            super(v);
            label = v.findViewById(R.id.tvChipLabel);
            count = v.findViewById(R.id.tvChipCount);
        }
    }
}
