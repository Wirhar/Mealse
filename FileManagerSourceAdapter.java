package com.my.mealse.filemanager.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Адаптер Spinner'а для выбора источника (локально / удалённые узлы).
 * Стиль — системный simple_spinner_item (text1).
 */
public class FileManagerSourceAdapter extends BaseAdapter {

    private final Context context;
    private final List<FileManagerSource> items = new ArrayList<>();
    private final LayoutInflater inflater;

    public FileManagerSourceAdapter(Context context) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
    }

    public void replaceAll(List<FileManagerSource> sources) {
        items.clear();
        if (sources != null) items.addAll(sources);
        notifyDataSetChanged();
    }

    public int indexOf(FileManagerSource s) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).equals(s)) return i;
        }
        return -1;
    }

    @Override public int getCount() { return items.size(); }
    @Override public FileManagerSource getItem(int position) { return items.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        TextView tv;
        if (convertView == null) {
            tv = (TextView) inflater.inflate(
                    android.R.layout.simple_spinner_item, parent, false);
            tv.setTextColor(0xFFFFFFFF);
            tv.setTextSize(14f);
        } else {
            tv = (TextView) convertView;
        }
        tv.setText(getItem(position).displayName);
        return tv;
    }

    @Override
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        TextView tv;
        if (convertView == null) {
            tv = (TextView) inflater.inflate(
                    android.R.layout.simple_spinner_dropdown_item, parent, false);
        } else {
            tv = (TextView) convertView;
        }
        tv.setText(getItem(position).displayName);
        tv.setTextColor(0xFF000000);
        tv.setPadding(24, 24, 24, 24);
        return tv;
    }
}