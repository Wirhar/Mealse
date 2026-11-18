package com.my.mealse.browser;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
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
import com.my.mealse.browser.history.BookmarkItem;
import com.my.mealse.browser.history.BookmarksHelper;
import com.my.mealse.util.MealseLogger;

import java.util.ArrayList;
import java.util.List;

/**
 * Сохранённые сайты (закладки). - Короткий тап: открыть в BrowserActivity. - Длинный тап: меню (Открыть / Редактировать / Удалить).
 */
public class SavedSitesActivity extends BaseActivity {

  private static final String TAG = "SavedSitesActivity";

  private MealseLogger log;
  private BookmarksHelper bookmarksHelper;

  private ListView listView;
  private TextView emptyView;
  private ProgressBar loading;
  private ImageButton btnBack, btnAdd;

  private final List<BookmarkItem> items = new ArrayList<>();
  private BookmarkAdapter adapter;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_saved_sites);

    log = MealseLogger.get(this, TAG);
    log.log("══ SavedSitesActivity onCreate ══");

    bookmarksHelper = new BookmarksHelper(this);

    initViews();
    reload();
  }

  private void initViews() {
    listView = findViewById(R.id.ss_list);
    emptyView = findViewById(R.id.ss_empty);
    loading = findViewById(R.id.ss_loading);
    btnBack = findViewById(R.id.ss_btn_back);
    btnAdd = findViewById(R.id.ss_btn_add);

    adapter = new BookmarkAdapter();
    listView.setAdapter(adapter);

    btnBack.setOnClickListener(v -> finish());
    btnAdd.setOnClickListener(v -> showAddDialog());

    listView.setOnItemClickListener((parent, view, position, id) -> {
      openEntry(items.get(position));
    });

    listView.setOnItemLongClickListener((parent, view, position, id) -> {
      BookmarkItem item = items.get(position);
      showItemMenu(item);
      return true;
    });
  }

  // ============ Data ============

  private void reload() {
    loading.setVisibility(View.VISIBLE);
    bookmarksHelper.loadAll(new BookmarksHelper.LoadCallback() {
      @Override
      public void onLoaded(List<BookmarkItem> list) {
        loading.setVisibility(View.GONE);
        items.clear();
        if (list != null)
          items.addAll(list);
        adapter.notifyDataSetChanged();
        updateEmpty();
        log.log("Loaded " + items.size() + " bookmarks");
      }

      @Override
      public void onError(String error) {
        loading.setVisibility(View.GONE);
        Toast.makeText(SavedSitesActivity.this, "Ошибка: " + error, Toast.LENGTH_LONG).show();
      }
    });
  }

  private void updateEmpty() {
    emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
  }

  // ============ Actions ============

  private void openEntry(BookmarkItem e) {
    if (e == null || e.url == null || e.url.isEmpty())
      return;
    try {
      Intent i = new Intent(this, BrowserActivity.class);
      i.putExtra("start_url", e.url);
      startActivity(i);
    } catch (Exception ex) {
      Toast.makeText(this, "Ошибка: " + ex.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  private void showItemMenu(BookmarkItem e) {
    if (e == null)
      return;

    String[] options = { "🌐 Открыть", "✏ Редактировать", "🔗 Скопировать ссылку", "🗑 Удалить" };

    new AlertDialog.Builder(this).setTitle(e.getShortTitle()).setItems(options, (d, w) -> {
      switch (w) {
        case 0:
          openEntry(e);
          break;
        case 1:
          showEditDialog(e);
          break;
        case 2:
          copyUrl(e);
          break;
        case 3:
          confirmDelete(e);
          break;
      }
    }).setNegativeButton("Отмена", null).show();
  }

  private void copyUrl(BookmarkItem e) {
    try {
      android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(
          android.content.Context.CLIPBOARD_SERVICE);
      if (cm != null) {
        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", e.url));
        Toast.makeText(this, "🔗 Скопировано", Toast.LENGTH_SHORT).show();
      }
    } catch (Exception ex) {
      Toast.makeText(this, "Ошибка: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
    }
  }

  private void confirmDelete(BookmarkItem e) {
    new AlertDialog.Builder(this).setTitle("Удалить закладку?").setMessage(e.getShortTitle())
        .setPositiveButton("Удалить", (d, w) -> {
          bookmarksHelper.delete(e.id, new BookmarksHelper.SaveCallback() {
            @Override
            public void onSaved(int total) {
              Toast.makeText(SavedSitesActivity.this, "Удалено", Toast.LENGTH_SHORT).show();
              reload();
            }

            @Override
            public void onError(String error) {
              Toast.makeText(SavedSitesActivity.this, "Ошибка: " + error, Toast.LENGTH_SHORT)
                  .show();
            }
          });
        }).setNegativeButton("Отмена", null).show();
  }

  // ============ Add / Edit ============

  private void showAddDialog() {
    View v = getLayoutInflater().inflate(R.layout.dialog_bookmark, null);
    EditText etTitle = v.findViewById(R.id.bm_edit_title);
    EditText etUrl = v.findViewById(R.id.bm_edit_url);
    if (etTitle != null)
      etTitle.setText("");
    if (etUrl != null)
      etUrl.setText("");

    new AlertDialog.Builder(this).setTitle("⭐ Новая закладка").setView(v)
        .setPositiveButton("Сохранить", (d, w) -> {
          // Шаг 1: читаем «сырые» значения из EditText в локальные переменные
          String rawTitle = etTitle != null ? etTitle.getText().toString().trim() : "";
          String rawUrl = etUrl != null ? etUrl.getText().toString().trim() : "";

          if (rawUrl.isEmpty()) {
            Toast.makeText(this, "URL не может быть пустым", Toast.LENGTH_SHORT).show();
            return;
          }

          // Шаг 2: финальный URL — final, вычисляется один раз
          final String finalUrl;
          if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            finalUrl = rawUrl;
          } else {
            finalUrl = "https://" + rawUrl;
          }

          // Шаг 3: финальный заголовок — тоже final
          final String finalTitle = rawTitle;

          // Шаг 4: передаём final-переменные в анонимный класс
          bookmarksHelper.add(finalTitle, finalUrl, new BookmarksHelper.SaveCallback() {
            @Override
            public void onSaved(int total) {
              Toast.makeText(SavedSitesActivity.this, "⭐ Сохранено (" + total + ")",
                  Toast.LENGTH_SHORT).show();
              reload();
            }

            @Override
            public void onError(String error) {
              Toast.makeText(SavedSitesActivity.this, "Ошибка: " + error, Toast.LENGTH_SHORT)
                  .show();
            }
          });
        }).setNegativeButton("Отмена", null).show();
  }

  private void showEditDialog(BookmarkItem e) {
    View v = getLayoutInflater().inflate(R.layout.dialog_bookmark, null);
    EditText etTitle = v.findViewById(R.id.bm_edit_title);
    EditText etUrl = v.findViewById(R.id.bm_edit_url);
    if (etTitle != null)
      etTitle.setText(e.title != null ? e.title : "");
    if (etUrl != null)
      etUrl.setText(e.url != null ? e.url : "");

    new AlertDialog.Builder(this).setTitle("✏ Редактировать закладку").setView(v)
        .setPositiveButton("Сохранить", (d, w) -> {
          // Шаг 1: читаем «сырые» значения
          String rawTitle = etTitle != null ? etTitle.getText().toString().trim() : "";
          String rawUrl = etUrl != null ? etUrl.getText().toString().trim() : "";

          if (rawUrl.isEmpty()) {
            Toast.makeText(this, "URL не может быть пустым", Toast.LENGTH_SHORT).show();
            return;
          }

          // Шаг 2: финальные переменные (final, вычисляются один раз)
          final String finalUrl;
          if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            finalUrl = rawUrl;
          } else {
            finalUrl = "https://" + rawUrl;
          }
          final String finalTitle = rawTitle;
          final String oldId = e.id;

          // Шаг 3: передаём final-переменные во вложенные анонимные классы
          bookmarksHelper.delete(oldId, new BookmarksHelper.SaveCallback() {
            @Override
            public void onSaved(int total) {
              bookmarksHelper.add(finalTitle, finalUrl, new BookmarksHelper.SaveCallback() {
                @Override
                public void onSaved(int t) {
                  Toast.makeText(SavedSitesActivity.this, "Обновлено", Toast.LENGTH_SHORT).show();
                  reload();
                }

                @Override
                public void onError(String error) {
                  Toast.makeText(SavedSitesActivity.this, "Ошибка: " + error, Toast.LENGTH_SHORT)
                      .show();
                }
              });
            }

            @Override
            public void onError(String error) {
              Toast.makeText(SavedSitesActivity.this, "Ошибка: " + error, Toast.LENGTH_SHORT)
                  .show();
            }
          });
        }).setNegativeButton("Отмена", null).show();
  }

  // ============ Adapter ============

  private class BookmarkAdapter extends BaseAdapter {

    @Override
    public int getCount() {
      return items.size();
    }

    @Override
    public BookmarkItem getItem(int position) {
      return items.get(position);
    }

    @Override
    public long getItemId(int position) {
      BookmarkItem b = items.get(position);
      return b.id != null ? b.id.hashCode() : position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
      ViewHolder h;
      if (convertView == null) {
        convertView = getLayoutInflater().inflate(R.layout.list_item_bookmark, parent, false);
        h = new ViewHolder();
        h.title = convertView.findViewById(R.id.bm_title);
        h.url = convertView.findViewById(R.id.bm_url);
        convertView.setTag(h);
      } else {
        h = (ViewHolder) convertView.getTag();
      }
      BookmarkItem e = items.get(position);
      h.title.setText(e.getShortTitle());
      h.url.setText(e.getShortUrl());
      return convertView;
    }
  }

  private static class ViewHolder {
    TextView title;
    TextView url;
  }
}