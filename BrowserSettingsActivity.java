package com.my.mealse.browser;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.my.mealse.BaseActivity;
import com.my.mealse.R;
import com.my.mealse.browser.history.BookmarksHelper;
import com.my.mealse.browser.history.HistoryHelper;
import com.my.mealse.browser.password.PasswordsHelper;
import com.my.mealse.browser.proxy.AdblockStats;
import com.my.mealse.browser.proxy.GatewayDetector;
import com.my.mealse.browser.proxy.ProxyCache;
import com.my.mealse.model.TrustedNode;
import com.my.mealse.util.MealseLogger;
import com.my.mealse.util.NodeManager;

import java.util.List;
import java.util.Locale;

/**
 * Настройки браузера.
 *
 * Позволяет:
 *  - Изменить домашнюю страницу
 *  - Выбрать поисковик (Yandex / Google / DuckDuckGo)
 *  - Включить/выключить блокировку рекламы
 *  - Выбрать шлюз вручную (если автодетект не сработал)
 *  - Очистить кэш
 *  - Очистить историю, закладки, пароли
 */
public class BrowserSettingsActivity extends BaseActivity {

  private static final String TAG = "BrowserSettings";
  private static final String PREF_BROWSER = "mealse_browser_prefs";
  private static final String KEY_MANUAL_GATEWAY_KEY = "manual_gateway_key";

  private MealseLogger log;
  private BrowserSettings settings;
  private NodeManager nodeManager;

  private EditText etHomeUrl;
  private TextView tvSearchEngine;
  private TextView tvGatewayStatus;
  private TextView tvCacheSize;
  private TextView tvAdblockStats;
  private Switch swAdblock;
  private ImageButton btnBack;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_browser_settings);

    log = MealseLogger.get(this, TAG);
    settings = new BrowserSettings(this);
    nodeManager = new NodeManager(this);

    initViews();
  }

  private void initViews() {
    btnBack = findViewById(R.id.bs_btn_back);
    etHomeUrl = findViewById(R.id.bs_edit_home);
    tvSearchEngine = findViewById(R.id.bs_tv_search_engine);
    tvGatewayStatus = findViewById(R.id.bs_tv_gateway);
    tvCacheSize = findViewById(R.id.bs_tv_cache_size);
    tvAdblockStats = findViewById(R.id.bs_tv_adblock_stats);
    swAdblock = findViewById(R.id.bs_switch_adblock);

    if (btnBack != null)
      btnBack.setOnClickListener(v -> finish());

    // Загрузка текущих значений
    if (etHomeUrl != null)
      etHomeUrl.setText(settings.getHomeUrl());
    updateSearchEngineLabel();
    updateGatewayLabel();
    updateCacheLabel();
    updateAdblockUi();

    // Домашняя страница — сохраняется при вводе
    if (etHomeUrl != null) {
      etHomeUrl.setOnFocusChangeListener((v, hasFocus) -> {
        if (!hasFocus) {
          settings.setHomeUrl(etHomeUrl.getText().toString().trim());
          toast("Домашняя страница сохранена");
        }
      });
    }

    // Поисковик
    View rowSearch = findViewById(R.id.bs_row_search_engine);
    if (rowSearch != null)
      rowSearch.setOnClickListener(v -> chooseSearchEngine());

    // ★ Блокировка рекламы
    if (swAdblock != null) {
      swAdblock.setChecked(settings.isAdblockEnabled());
      swAdblock.setOnCheckedChangeListener((v, checked) -> {
        settings.setAdblockEnabled(checked);
        toast(checked ? "Блокировка рекламы: ВКЛ" : "Блокировка рекламы: ВЫКЛ");
        // ★ Инвалидировать кэш при смене
        new ProxyCache(this).clear();
        updateAdblockUi();
      });
    }

    // Шлюз
    View rowGateway = findViewById(R.id.bs_row_gateway);
    if (rowGateway != null)
      rowGateway.setOnClickListener(v -> chooseGateway());

    // Кэш
    View btnClearCache = findViewById(R.id.bs_btn_clear_cache);
    if (btnClearCache != null)
      btnClearCache.setOnClickListener(v -> confirmClearCache());

    // История
    View btnClearHistory = findViewById(R.id.bs_btn_clear_history);
    if (btnClearHistory != null)
      btnClearHistory.setOnClickListener(v -> confirmClearHistory());

    // Закладки
    View btnClearBookmarks = findViewById(R.id.bs_btn_clear_bookmarks);
    if (btnClearBookmarks != null)
      btnClearBookmarks.setOnClickListener(v -> confirmClearBookmarks());

    // Пароли
    View btnClearPasswords = findViewById(R.id.bs_btn_clear_passwords);
    if (btnClearPasswords != null)
      btnClearPasswords.setOnClickListener(v -> confirmClearPasswords());

    View rowUa = findViewById(R.id.bs_row_user_agent);
    if (rowUa != null)
      rowUa.setOnClickListener(v -> chooseUserAgent());

    View btnSetDefault = findViewById(R.id.bs_btn_set_default_browser);
    if (btnSetDefault != null) {
      btnSetDefault.setOnClickListener(v -> showSetDefaultBrowserDialog());
    }

    updateUserAgentLabel();
  }

  // ============ Блокировка рекламы ============

  private void updateAdblockUi() {
    if (swAdblock != null) {
      swAdblock.setChecked(settings.isAdblockEnabled());
    }
    updateAdblockStats();
  }

  private void updateAdblockStats() {
    if (tvAdblockStats == null) return;
    AdblockStats s = new AdblockStats(this);
    long blocked = s.getBlockedCount();
    long saved = s.getSavedBytes();
    String fmt;
    if (saved < 1024) {
      fmt = saved + " B";
    } else if (saved < 1024 * 1024) {
      fmt = String.format(Locale.US, "%.1f KB", saved / 1024.0);
    } else {
      fmt = String.format(Locale.US, "%.2f MB", saved / (1024.0 * 1024.0));
    }
    tvAdblockStats.setText("Заблокировано: " + blocked + " · Экономия: " + fmt);
  }

  // ============ Поисковик ============

  private void chooseSearchEngine() {
    String[] engines = { "Яндекс", "Google", "DuckDuckGo" };
    String[] keys = { "yandex", "google", "duckduckgo" };
    int checked = 0;
    String cur = settings.getSearchEngine();
    for (int i = 0; i < keys.length; i++) {
      if (keys[i].equals(cur)) {
        checked = i;
        break;
      }
    }

    new AlertDialog.Builder(this).setTitle("Поисковик")
        .setSingleChoiceItems(engines, checked, (d, which) -> {
          settings.setSearchEngine(keys[which]);
          updateSearchEngineLabel();
          d.dismiss();
        }).setNegativeButton("Отмена", null).show();
  }

  private void updateSearchEngineLabel() {
    if (tvSearchEngine == null)
      return;
    String cur = settings.getSearchEngine();
    switch (cur) {
      case "google":
        tvSearchEngine.setText("Google");
        break;
      case "duckduckgo":
        tvSearchEngine.setText("DuckDuckGo");
        break;
      default:
        tvSearchEngine.setText("Яндекс");
        break;
    }
  }

  // ============ Шлюз ============

  private void chooseGateway() {
    try {
      // Автодетект
      GatewayDetector detector = new GatewayDetector(this);
      GatewayDetector.GatewayInfo auto = detector.getActiveGateway();

      // Ручной из prefs
      SharedPreferences prefs = getSharedPreferences(PREF_BROWSER, MODE_PRIVATE);
      String manualKey = prefs.getString(KEY_MANUAL_GATEWAY_KEY, null);

      // Список узлов с mode == GATEWAY
      List<TrustedNode> all = nodeManager.getAllNodes();
      List<TrustedNode> candidates = new java.util.ArrayList<>();
      for (TrustedNode n : all) {
        if (n.getMode() == TrustedNode.Mode.GATEWAY) {
          candidates.add(n);
        }
      }

      String[] options = new String[candidates.size() + 1];
      options[0] = "🔁 Авто"
          + (auto != null ? " (сейчас: " + auto.gatewayIp + ")" : " (не активен)");
      for (int i = 0; i < candidates.size(); i++) {
        TrustedNode n = candidates.get(i);
        options[i + 1] = "🛡 " + n.getName() + " (" + n.getGatewayIp() + ":" + n.getGatewayPort()
            + ")";
      }

      new AlertDialog.Builder(this).setTitle("Выбор шлюза").setItems(options, (d, which) -> {
        if (which == 0) {
          prefs.edit().remove(KEY_MANUAL_GATEWAY_KEY).apply();
          toast("Шлюз: авто");
        } else {
          TrustedNode n = candidates.get(which - 1);
          prefs.edit().putString(KEY_MANUAL_GATEWAY_KEY, n.getKey()).apply();
          toast("Шлюз: " + n.getName());
        }
        updateGatewayLabel();
      }).setNegativeButton("Отмена", null).show();
    } catch (Throwable t) {
      toast("Ошибка: " + t.getMessage());
    }
  }

  private void updateGatewayLabel() {
    if (tvGatewayStatus == null)
      return;
    GatewayDetector detector = new GatewayDetector(this);
    GatewayDetector.GatewayInfo info = detector.getActiveGateway();
    if (info != null) {
      tvGatewayStatus.setText("🛡 Активен: " + info.gatewayIp + ":" + info.proxyPort);
    } else {
      tvGatewayStatus.setText("🌐 Напрямую (VPN не активен)");
    }
  }

  // ============ Кэш ============

  private void updateCacheLabel() {
    if (tvCacheSize == null)
      return;
    ProxyCache cache = new ProxyCache(this);
    long bytes = cache.getTotalBytes();
    String s;
    if (bytes < 1024)
      s = bytes + " B";
    else if (bytes < 1024 * 1024)
      s = String.format(Locale.US, "%.1f KB", bytes / 1024.0);
    else
      s = String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0));
    tvCacheSize.setText(s);
  }

  private void confirmClearCache() {
    new AlertDialog.Builder(this).setTitle("Очистить кэш?")
        .setMessage("Все сохранённые ответы прокси будут удалены.")
        .setPositiveButton("Очистить", (d, w) -> {
          new ProxyCache(this).clear();
          updateCacheLabel();
          toast("Кэш очищен");
        }).setNegativeButton("Отмена", null).show();
  }

  private void confirmClearHistory() {
    new AlertDialog.Builder(this).setTitle("Очистить историю?")
        .setMessage("Активная история будет удалена.\nАрхив сохранится в browser/history_archive/.")
        .setPositiveButton("Очистить", (d, w) -> {
          new HistoryHelper(this).clearAll(new HistoryHelper.SaveCallback() {
            @Override
            public void onSaved(int total) {
              toast("История очищена");
            }

            @Override
            public void onError(String error) {
              toast("Ошибка: " + error);
            }
          });
        }).setNegativeButton("Отмена", null).show();
  }

  private void confirmClearBookmarks() {
    new AlertDialog.Builder(this).setTitle("Очистить закладки?")
        .setMessage("Все сохранённые сайты будут удалены.")
        .setPositiveButton("Очистить", (d, w) -> {
          BookmarksHelper bh = new BookmarksHelper(this);
          bh.loadAll(new BookmarksHelper.LoadCallback() {
            @Override
            public void onLoaded(List<com.my.mealse.browser.history.BookmarkItem> items) {
              int count = items != null ? items.size() : 0;
              if (count == 0) {
                toast("Уже пусто");
                return;
              }
              final int[] done = { 0 };
              for (com.my.mealse.browser.history.BookmarkItem b : items) {
                bh.delete(b.id, new BookmarksHelper.SaveCallback() {
                  @Override
                  public void onSaved(int total) {
                    done[0]++;
                    if (done[0] >= count)
                      toast("Закладки очищены");
                  }

                  @Override
                  public void onError(String error) {
                  }
                });
              }
            }

            @Override
            public void onError(String error) {
              toast("Ошибка: " + error);
            }
          });
        }).setNegativeButton("Отмена", null).show();
  }

  private void confirmClearPasswords() {
    new AlertDialog.Builder(this).setTitle("Очистить пароли?")
        .setMessage("Все сохранённые пароли будут удалены.")
        .setPositiveButton("Очистить", (d, w) -> {
          PasswordsHelper ph = new PasswordsHelper(this);
          ph.loadAll(new PasswordsHelper.LoadCallback() {
            @Override
            public void onLoaded(List<com.my.mealse.browser.password.PasswordItem> items) {
              int count = items != null ? items.size() : 0;
              if (count == 0) {
                toast("Уже пусто");
                return;
              }
              final int[] done = { 0 };
              for (com.my.mealse.browser.password.PasswordItem p : items) {
                ph.delete(p.id, new PasswordsHelper.DeleteCallback() {
                  @Override
                  public void onDeleted(boolean success) {
                    done[0]++;
                    if (done[0] >= count)
                      toast("Пароли очищены");
                  }

                  @Override
                  public void onError(String error) {
                  }
                });
              }
            }

            @Override
            public void onError(String error) {
              toast("Ошибка: " + error);
            }
          });
        }).setNegativeButton("Отмена", null).show();
  }

  private void showSetDefaultBrowserDialog() {
    new AlertDialog.Builder(this).setTitle("Сделать Mealse браузером по умолчанию?")
        .setMessage("Все ссылки из других приложений будут открываться в Mealse.\n\n"
            + "⚠ ВАЖНО: если шлюз (VPN-клиент) активен, все ссылки будут идти "
            + "через шлюз. Шлюз увидит, какие сайты вы открываете.")
        .setPositiveButton("Перейти в настройки", (d, w) -> {
          try {
            Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS);
            startActivity(i);
          } catch (Throwable t) {
            toast("Откройте вручную: Настройки → Приложения → Браузер по умолчанию");
          }
        }).setNegativeButton("Отмена", null).show();
  }

  private void chooseUserAgent() {
    String[] options = { "📱 Мобильный", "🖥 Десктоп" };
    String[] keys = { "mobile", "desktop" };
    int checked = 0;
    String cur = settings.getUserAgentMode();
    for (int i = 0; i < keys.length; i++) {
      if (keys[i].equals(cur)) {
        checked = i;
        break;
      }
    }
    new AlertDialog.Builder(this).setTitle("User-Agent")
        .setSingleChoiceItems(options, checked, (d, which) -> {
          settings.setUserAgentMode(keys[which]);
          updateUserAgentLabel();
          toast("Изменения вступят в силу после перезагрузки браузера");
          d.dismiss();
        }).setNegativeButton("Отмена", null).show();
  }

  private void updateUserAgentLabel() {
    TextView tv = findViewById(R.id.bs_tv_user_agent);
    if (tv == null)
      return;
    tv.setText("desktop".equals(settings.getUserAgentMode()) ? "🖥 Десктоп" : "📱 Мобильный");
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    // Чтобы изменения UA вступили в силу — перезагрузить браузер (пользователь откроет заново).
    // Можно сделать broadcast, если хочется автоматически.
  }

  private void toast(String msg) {
    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
  }
}