package com.my.mealse.filemanager.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Toolbar;

import androidx.appcompat.app.AppCompatActivity;
// import androidx.appcompat.widget.Toolbar;

import com.my.mealse.R;
import com.my.mealse.filemanager.util.FileTypeHelper;
import com.my.mealse.filemanager.util.TrashManager;
import com.my.mealse.util.MealseLogger;
import com.my.mealse.BaseActivity;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * TrashActivity — экран корзины файлового менеджера.
 *
 * Возможности: - Список файлов/папок в корзине с датой удаления и оставшимся сроком. - Тап по элементу → диалог: «Восстановить» / «Удалить навсегда». - Меню: «Очистить корзину». - При входе автоматически вызывается cleanupOldFiles() — удаляет элементы старше 10 дней.
 */
public class TrashActivity extends BaseActivity {

  private static final String TAG = "TrashActivity";

  private Toolbar toolbar;
  private ListView listView;
  private TextView emptyView;

  private TrashManager trashManager;
  private MealseLogger log;

  private final List<TrashManager.TrashItem> items = new ArrayList<>();
  private TrashAdapter adapter;

  private final SimpleDateFormat dateFmt = new SimpleDateFormat("dd.MM.yyyy HH:mm",
      Locale.getDefault());

  // ============================================================
  // Lifecycle
  // ============================================================

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_trash);

    log = MealseLogger.get(this, TAG);
    log.log("TrashActivity onCreate");

    trashManager = TrashManager.get(this);

    // Автоочистка старых файлов
    try {
      trashManager.cleanupOldFiles();
    } catch (Exception e) {
      log.logError("cleanupOldFiles", e);
    }

    initViews();
    reload();
  }

  private void initViews() {
    toolbar = findViewById(R.id.trash_toolbar);
    listView = findViewById(R.id.trash_list);
    emptyView = findViewById(R.id.trash_empty);

    setSupportActionBar(toolbar);
    if (getSupportActionBar() != null) {
      getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    adapter = new TrashAdapter();
    listView.setAdapter(adapter);

    listView.setOnItemClickListener((parent, view, position, id) -> {
      TrashManager.TrashItem it = items.get(position);
      showItemDialog(it);
    });
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.menu_trash, menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    int id = item.getItemId();
    if (id == android.R.id.home) {
      finish();
      return true;
    }
    if (id == R.id.trash_menu_empty) {
      confirmEmptyTrash();
      return true;
    }
    if (id == R.id.trash_menu_refresh) {
      reload();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  // ============================================================
  // Данные
  // ============================================================

  private void reload() {
    items.clear();
    try {
      items.addAll(trashManager.getTrashContents());
    } catch (Exception e) {
      log.logError("reload", e);
    }
    adapter.notifyDataSetChanged();
    updateEmpty();
  }

  private void updateEmpty() {
    if (emptyView != null) {
      emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }
  }

  // ============================================================
  // Действия
  // ============================================================

  private void showItemDialog(TrashManager.TrashItem it) {
    String date = dateFmt.format(new Date(it.deletionTime));
    long daysLeft = calcDaysLeft(it.deletionTime);

    String title = it.originalName != null ? it.originalName : it.trashFileName;
    String msg = "Удалён: " + date + "\n" + "Тип: " + (it.isDirectory ? "папка" : "файл") + "\n"
        + "Будет удалён через: " + Math.max(0, daysLeft) + " дн.\n\n" + "Оригинальный путь:\n"
        + it.originalPath;

    new AlertDialog.Builder(this).setTitle(title).setMessage(msg)
        .setPositiveButton("♻ Восстановить", (d, w) -> restore(it))
        .setNeutralButton("🗑 Удалить навсегда", (d, w) -> deletePermanently(it))
        .setNegativeButton("Отмена", null).show();
  }

  private void restore(TrashManager.TrashItem it) {
    boolean ok = trashManager.restoreFromTrash(it.trashFileName);
    if (ok) {
      Toast.makeText(this, "✅ Восстановлено: " + it.originalName, Toast.LENGTH_SHORT).show();
      reload();
    } else {
      Toast.makeText(this, "❌ Не удалось восстановить", Toast.LENGTH_SHORT).show();
    }
  }

  private void deletePermanently(TrashManager.TrashItem it) {
    new AlertDialog.Builder(this).setTitle("Удалить навсегда?")
        .setMessage("Файл \"" + it.originalName + "\" будет удалён безвозвратно.")
        .setPositiveButton("Удалить", (d, w) -> {
          boolean ok = trashManager.deletePermanently(it.trashFileName);
          if (ok) {
            Toast.makeText(this, "Удалено", Toast.LENGTH_SHORT).show();
            reload();
          } else {
            Toast.makeText(this, "❌ Ошибка удаления", Toast.LENGTH_SHORT).show();
          }
        }).setNegativeButton("Отмена", null).show();
  }

  private void confirmEmptyTrash() {
    if (items.isEmpty()) {
      Toast.makeText(this, "Корзина уже пуста", Toast.LENGTH_SHORT).show();
      return;
    }
    new AlertDialog.Builder(this).setTitle("Очистить корзину?")
        .setMessage("Все файлы (" + items.size() + ") будут удалены безвозвратно.")
        .setPositiveButton("Очистить", (d, w) -> {
          trashManager.emptyTrash();
          Toast.makeText(this, "Корзина очищена", Toast.LENGTH_SHORT).show();
          reload();
        }).setNegativeButton("Отмена", null).show();
  }

  private static long calcDaysLeft(long deletionTime) {
    long age = System.currentTimeMillis() - deletionTime;
    long ageDays = TimeUnit.MILLISECONDS.toDays(age);
    return 10 - ageDays;
  }

  // ============================================================
  // Адаптер
  // ============================================================

  private class TrashAdapter extends BaseAdapter {

    @Override
    public int getCount() {
      return items.size();
    }

    @Override
    public TrashManager.TrashItem getItem(int pos) {
      return items.get(pos);
    }

    @Override
    public long getItemId(int pos) {
      return items.get(pos).trashFileName.hashCode();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
      ViewHolder h;
      if (convertView == null) {
        convertView = getLayoutInflater().inflate(R.layout.list_item_trash, parent, false);
        h = new ViewHolder();
        h.icon = convertView.findViewById(R.id.trash_icon);
        h.name = convertView.findViewById(R.id.trash_name);
        h.info = convertView.findViewById(R.id.trash_info);
        convertView.setTag(h);
      } else {
        h = (ViewHolder) convertView.getTag();
      }

      TrashManager.TrashItem it = items.get(position);

      h.name.setText(it.originalName != null ? it.originalName : it.trashFileName);

      String date = dateFmt.format(new Date(it.deletionTime));
      long daysLeft = calcDaysLeft(it.deletionTime);
      String info = (it.isDirectory ? "📁 папка" : "📄 файл") + " · удалён " + date + " · "
          + Math.max(0, daysLeft) + " дн.";
      h.info.setText(info);

      // Иконка по категории
      if (it.isDirectory) {
        h.icon.setImageResource(R.drawable.ic_folder);
        h.icon.setColorFilter(null);
      } else {
        FileTypeHelper.Category cat = FileTypeHelper.getCategoryByFileName(it.originalName);
        switch (cat) {
          case PICTURE:
            h.icon.setImageResource(R.drawable.ic_file_image);
            break;
          case VIDEO:
            h.icon.setImageResource(R.drawable.ic_file_video);
            break;
          case AUDIO:
            h.icon.setImageResource(R.drawable.ic_file_audio);
            break;
          case ARCHIVE:
            h.icon.setImageResource(R.drawable.ic_file_archive);
            break;
          case APK:
            h.icon.setImageResource(R.drawable.ic_file_generic);
            break;
          case DOCUMENT:
          default:
            h.icon.setImageResource(R.drawable.ic_file_document);
            break;
        }
        h.icon.setColorFilter(null);
      }

      return convertView;
    }

    class ViewHolder {
      ImageView icon;
      TextView name;
      TextView info;
    }
  }
}ginalName);
                switch (cat) {
                    case PICTURE:
                        h.icon.setImageResource(android.R.drawable.ic_menu_gallery);
                        break;
                    case VIDEO:
                        h.icon.setImageResource(android.R.drawable.ic_media_play);
                        break;
                    case AUDIO:
                        h.icon.setImageResource(android.R.drawable.ic_lock_silent_mode_off);
                        break;
                    case ARCHIVE:
                        h.icon.setImageResource(android.R.drawable.ic_menu_save);
                        break;
                    case APK:
                        h.icon.setImageResource(android.R.drawable.ic_menu_manage);
                        break;
                    case DOCUMENT:
                    default:
                        h.icon.setImageResource(android.R.drawable.ic_menu_agenda);
                        break;
                }
                h.icon.setColorFilter(0xFFFF6B6B);
            }

            return convertView;
        }

        class ViewHolder {
            ImageView icon;
            TextView name;
            TextView info;
        }
    }
}