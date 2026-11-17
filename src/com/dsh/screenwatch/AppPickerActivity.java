package com.dsh.screenwatch;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lists launchable apps so the user can pick the cloud-gaming app for the widget. */
public class AppPickerActivity extends Activity {

    private static final class Entry {
        final String label;
        final String pkg;
        final Drawable icon;

        Entry(String label, String pkg, Drawable icon) {
            this.label = label;
            this.pkg = pkg;
            this.icon = icon;
        }
    }

    private final List<Entry> all = new ArrayList<>();
    private List<Entry> shown = new ArrayList<>();
    private AppAdapter adapter;
    private String query = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        loadApps();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF5F7FB);
        int pad = dp(16);
        root.setPadding(pad, dp(18), pad, pad);

        TextView title = new TextView(this);
        title.setText("选择云游戏应用");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF17202B);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("选中后，桌面卡片点按就会启动这个应用");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setTextColor(0xFF6B7785);
        hint.setPadding(0, dp(4), 0, dp(10));
        root.addView(hint);

        EditText search = new EditText(this);
        search.setHint("搜索应用");
        search.setSingleLine(true);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        search.setPadding(dp(12), dp(8), dp(12), dp(8));
        search.setBackgroundColor(0xFFFFFFFF);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString().trim().toLowerCase();
                filter();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        root.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        ListView list = new ListView(this);
        list.setDividerHeight(0);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < shown.size()) {
                select(shown.get(position));
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(8);
        root.addView(list, lp);
        return root;
    }

    private void loadApps() {
        new Thread(() -> {
            List<Entry> loaded = new ArrayList<>();
            try {
                Map<String, Entry> unique = new LinkedHashMap<>();
                android.content.pm.PackageManager pm = getPackageManager();
                Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                    String pkg = ri.activityInfo.packageName;
                    if (pkg.equals(getPackageName()) || unique.containsKey(pkg)) {
                        continue;
                    }
                    String label = ri.loadLabel(pm).toString();
                    unique.put(pkg, new Entry(label, pkg, ri.loadIcon(pm)));
                }
                loaded.addAll(unique.values());
                Collections.sort(loaded, (a, b) -> a.label.compareToIgnoreCase(b.label));
            } catch (Throwable ignored) {
            }
            List<Entry> finalLoaded = loaded;
            runOnUiThread(() -> {
                all.clear();
                all.addAll(finalLoaded);
                filter();
            });
        }).start();
    }

    private void filter() {
        if (adapter == null) {
            return;
        }
        if (query.isEmpty()) {
            shown = new ArrayList<>(all);
        } else {
            List<Entry> filtered = new ArrayList<>();
            for (Entry e : all) {
                if (e.label.toLowerCase().contains(query) || e.pkg.toLowerCase().contains(query)) {
                    filtered.add(e);
                }
            }
            shown = filtered;
        }
        adapter.setData(shown);
    }

    private void select(Entry e) {
        SharedPreferences p = getSharedPreferences(ScreenWatchService.PREF, MODE_PRIVATE);
        p.edit()
                .putString("cloud_app_package", e.pkg)
                .putString("cloud_app_label", e.label)
                .apply();
        CloudWidgetProvider.updateAll(this);
        Toast.makeText(this, "已选择：" + e.label, Toast.LENGTH_SHORT).show();
        finish();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private final class AppAdapter extends BaseAdapter {
        private List<Entry> data = new ArrayList<>();

        void setData(List<Entry> list) {
            data = list;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public Object getItem(int position) {
            return data.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            ImageView icon;
            TextView label;
            if (convertView == null) {
                row = new LinearLayout(AppPickerActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), dp(10), dp(10), dp(10));
                row.setBackgroundColor(0xFFFFFFFF);

                icon = new ImageView(AppPickerActivity.this);
                row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

                label = new TextView(AppPickerActivity.this);
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                label.setTextColor(0xFF17202B);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.leftMargin = dp(12);
                row.addView(label, lp);
                row.setTag(new Object[]{icon, label});
            } else {
                row = (LinearLayout) convertView;
                Object[] tags = (Object[]) row.getTag();
                icon = (ImageView) tags[0];
                label = (TextView) tags[1];
            }
            Entry e = data.get(position);
            icon.setImageDrawable(e.icon);
            label.setText(e.label);
            return row;
        }
    }
}
