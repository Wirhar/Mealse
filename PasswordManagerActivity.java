package com.my.mealse.browser;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.my.mealse.BaseActivity;
import com.my.mealse.R;
import com.my.mealse.browser.password.PasswordItem;
import com.my.mealse.browser.password.PasswordsHelper;
import com.my.mealse.util.MealseLogger;

import java.util.ArrayList;
import java.util.List;

/**
 * Менеджер паролей.
 *  - Короткий тап: диалог с деталями (копировать логин/пароль, открыть URL).
 *  - Длинный тап: меню (Просмотр / Редактировать / Удалить).
 */
public class PasswordManagerActivity extends BaseActivity {

    private static final String TAG = "PasswordManagerActivity";

    private MealseLogger log;
    private PasswordsHelper passwordsHelper;

    private ListView listView;
    private TextView emptyView;
    private ProgressBar loading;
    private ImageButton btnBack, btnAdd;

    private final List<PasswordItem> items = new ArrayList<>();
    private PasswordAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_passwords);

        log = MealseLogger.get(this, TAG);
        log.log("══ PasswordManagerActivity onCreate ══");

        passwordsHelper = new PasswordsHelper(this);

        initViews();
        reload();
    }

    private void initViews() {
        listView = findViewById(R.id.pw_list);
        emptyView = findViewById(R.id.pw_empty);
        loading = findViewById(R.id.pw_loading);
        btnBack = findViewById(R.id.pw_btn_back);
        btnAdd = findViewById(R.id.pw_btn_add);

        adapter = new PasswordAdapter();
        listView.setAdapter(adapter);

        btnBack.setOnClickListener(v -> finish());
        btnAdd.setOnClickListener(v -> showAddDialog());

        listView.setOnItemClickListener((parent, view, position, id) -> {
            showItemDetails(items.get(position));
        });

        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            showItemMenu(items.get(position));
            return true;
        });
    }

    // ============ Data ============

    private void reload() {
        loading.setVisibility(View.VISIBLE);
        passwordsHelper.loadAll(new PasswordsHelper.LoadCallback() {
            @Override
            public void onLoaded(List<PasswordItem> list) {
                loading.setVisibility(View.GONE);
                items.clear();
                if (list != null) items.addAll(list);
                adapter.notifyDataSetChanged();
                updateEmpty();
                log.log("Loaded " + items.size() + " passwords");
            }

            @Override
            public void onError(String error) {
                loading.setVisibility(View.GONE);
                Toast.makeText(PasswordManagerActivity.this,
                        "Ошибка: " + error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void updateEmpty() {
        emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ============ Actions ============

    private void showItemDetails(PasswordItem e) {
        if (e == null) return;

        StringBuilder sb = new StringBuilder();
        sb.append("🏷 Название: ").append(e.getDisplayTitle()).append("\n\n");
        sb.append("👤 Логин: ").append(e.login != null ? e.login : "").append("\n\n");
        sb.append("🔑 Пароль: ").append(e.password != null ? e.password : "").append("\n\n");
        if (e.url != null && !e.url.isEmpty()) {
            sb.append("🌐 URL: ").append(e.url).append("\n");
        }

        new AlertDialog.Builder(this)
                .setTitle("🔐 " + e.getDisplayTitle())
                .setMessage(sb.toString())
                .setPositiveButton("👤 Логин", (d, w) -> copyToClipboard(e.login, "Логин"))
                .setNeutralButton("🔑 Пароль", (d, w) -> copyToClipboard(e.password, "Пароль"))
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void showItemMenu(PasswordItem e) {
        if (e == null) return;

        String[] options = { "👤 Копировать логин", "🔑 Копировать пароль",
                "✏ Редактировать", "🗑 Удалить" };

        new AlertDialog.Builder(this)
                .setTitle(e.getDisplayTitle())
                .setItems(options, (d, w) -> {
                    switch (w) {
                        case 0: copyToClipboard(e.login, "Логин"); break;
                        case 1: copyToClipboard(e.password, "Пароль"); break;
                        case 2: showEditDialog(e); break;
                        case 3: confirmDelete(e); break;
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void copyToClipboard(String text, String label) {
        if (text == null || text.isEmpty()) {
            Toast.makeText(this, label + " пуст", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(label, text));
                Toast.makeText(this, "📋 " + label + " скопирован", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ex) {
            Toast.makeText(this, "Ошибка: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDelete(PasswordItem e) {
        new AlertDialog.Builder(this)
                .setTitle("Удалить пароль?")
                .setMessage(e.getDisplayTitle())
                .setPositiveButton("Удалить", (d, w) -> {
                    passwordsHelper.delete(e.id, new PasswordsHelper.DeleteCallback() {
                        @Override public void onDeleted(boolean success) {
                            Toast.makeText(PasswordManagerActivity.this,
                                    success ? "Удалено" : "Не найдено", Toast.LENGTH_SHORT).show();
                            reload();
                        }
                        @Override public void onError(String error) {
                            Toast.makeText(PasswordManagerActivity.this,
                                    "Ошибка: " + error, Toast.LENGTH_SHORT).show();
                        }
                    });
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    // ============ Add / Edit ============

    private void showAddDialog() {
        View v = getLayoutInflater().inflate(R.layout.dialog_password, null);
        EditText etTitle = v.findViewById(R.id.pw_edit_title);
        EditText etLogin = v.findViewById(R.id.pw_edit_login);
        EditText etPassword = v.findViewById(R.id.pw_edit_password);
        EditText etUrl = v.findViewById(R.id.pw_edit_url);

        if (etTitle != null) etTitle.setText("");
        if (etLogin != null) etLogin.setText("");
        if (etPassword != null) etPassword.setText("");
        if (etUrl != null) etUrl.setText("");

        new AlertDialog.Builder(this)
                .setTitle("🔐 Новая запись")
                .setView(v)
                .setPositiveButton("Сохранить", (d, w) -> {
                    PasswordItem item = buildFromInputs(etTitle, etLogin, etPassword, etUrl, null);
                    if (item == null) return;
                    passwordsHelper.save(item, saveCb());
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showEditDialog(PasswordItem e) {
        View v = getLayoutInflater().inflate(R.layout.dialog_password, null);
        EditText etTitle = v.findViewById(R.id.pw_edit_title);
        EditText etLogin = v.findViewById(R.id.pw_edit_login);
        EditText etPassword = v.findViewById(R.id.pw_edit_password);
        EditText etUrl = v.findViewById(R.id.pw_edit_url);

        if (etTitle != null) etTitle.setText(e.title != null ? e.title : "");
        if (etLogin != null) etLogin.setText(e.login != null ? e.login : "");
        if (etPassword != null) etPassword.setText(e.password != null ? e.password : "");
        if (etUrl != null) etUrl.setText(e.url != null ? e.url : "");

        new AlertDialog.Builder(this)
                .setTitle("✏ Редактировать")
                .setView(v)
                .setPositiveButton("Сохранить", (d, w) -> {
                    PasswordItem item = buildFromInputs(etTitle, etLogin, etPassword, etUrl, e);
                    if (item == null) return;
                    passwordsHelper.save(item, saveCb());
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private PasswordItem buildFromInputs(EditText etTitle, EditText etLogin,
                                         EditText etPassword, EditText etUrl,
                                         PasswordItem existing) {
        String title = etTitle != null ? etTitle.getText().toString().trim() : "";
        String login = etLogin != null ? etLogin.getText().toString().trim() : "";
        String password = etPassword != null ? etPassword.getText().toString().trim() : "";
        String url = etUrl != null ? etUrl.getText().toString().trim() : "";

        if (login.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Логин и пароль обязательны", Toast.LENGTH_SHORT).show();
            return null;
        }

        PasswordItem item = new PasswordItem();
        if (existing != null) {
            item.id = existing.id;
            item.createdAt = existing.createdAt;
        }
        item.title = title;
        item.login = login;
        item.password = password;
        item.url = url;
        item.updatedAt = System.currentTimeMillis();
        return item;
    }

    private PasswordsHelper.SaveCallback saveCb() {
        return new PasswordsHelper.SaveCallback() {
            @Override public void onSaved(PasswordItem item) {
                Toast.makeText(PasswordManagerActivity.this,
                        "✅ Сохранено", Toast.LENGTH_SHORT).show();
                reload();
            }
            @Override public void onError(String error) {
                Toast.makeText(PasswordManagerActivity.this,
                        "Ошибка: " + error, Toast.LENGTH_SHORT).show();
            }
        };
    }

    // ============ Adapter ============

    private class PasswordAdapter extends BaseAdapter {

        @Override public int getCount() { return items.size(); }
        @Override public PasswordItem getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) {
            PasswordItem p = items.get(position);
            return p.id != null ? p.id.hashCode() : position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder h;
            if (convertView == null) {
                convertView = getLayoutInflater()
                        .inflate(R.layout.list_item_password, parent, false);
                h = new ViewHolder();
                h.title = convertView.findViewById(R.id.pw_title);
                h.login = convertView.findViewById(R.id.pw_login);
                h.passwordMasked = convertView.findViewById(R.id.pw_password_masked);
                convertView.setTag(h);
            } else {
                h = (ViewHolder) convertView.getTag();
            }
            PasswordItem e = items.get(position);
            h.title.setText(e.getDisplayTitle());
            h.login.setText(e.login != null ? e.login : "");
            int len = e.password != null ? e.password.length() : 0;
            StringBuilder mask = new StringBuilder();
            for (int i = 0; i < Math.min(len, 12); i++) mask.append("•");
            h.passwordMasked.setText(mask.toString());

            return convertView;
        }
    }

    private static class ViewHolder {
        TextView title;
        TextView login;
        TextView passwordMasked;
    }
}