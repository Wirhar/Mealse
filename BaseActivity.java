package com.my.mealse;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toolbar;

import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Базовая Activity с собственной реализацией AppCompat-методов
 * (setSupportActionBar / getSupportActionBar / getMenuInflater)
 * БЕЗ подключения androidx.appcompat.
 *
 * Использует:
 *   - android.widget.Toolbar (системный, API 21+).
 *   - android.app.Activity (НЕ AppCompatActivity).
 *   - androidx.core — только WindowCompat/ViewCompat/Insets.
 *
 * Ключевое:
 *   setSupportActionBar() вызывает setActionBar(toolbar), поэтому
 *   система САМА вызывает onCreateOptionsMenu / onPrepareOptionsMenu /
 *   onOptionsItemSelected и САМА управляет navigation-иконкой «назад»
 *   (клик по ней приходит в onOptionsItemSelected как android.R.id.home).
 *
 * ★ НОВОЕ (правка BackInvoked):
 *   В манифесте стоит android:enableOnBackInvokedCallback="true".
 *   На API 33+ это значит, что система НЕ вызывает onBackPressed()
 *   у Activity, если Activity не зарегистрировала OnBackInvokedCallback.
 *   BaseActivity регистрирует колбэк в onCreate, который сводит всё
 *   в onBackPressed() — наследники продолжают переопределять
 *   onBackPressed как раньше, и это работает на всех API.
 *
 * ★ НОВОЕ (диагностика):
 *   requireSupportActionBar() бросает IllegalStateException с понятным
 *   сообщением, если setSupportActionBar() не был вызван. Экономит
 *   часы отладки «почему меню не появляется».
 *
 * ★ НОВОЕ (утилиты):
 *   hideKeyboard(), keepScreenOn() — мелкие, но реально нужные
 *   в редакторе, плеере, звонке.
 */
public abstract class BaseActivity extends Activity {

  // ============================================================
  // Поля
  // ============================================================

  private Toolbar supportToolbar;
  private SupportActionBar supportActionBar;
  private boolean homeAsUpEnabled = false;

  /** ★ НОВОЕ: обработчик клика по «назад» (home). */
  private Runnable homeClickListener;

  // ============================================================
  // Lifecycle
  // ============================================================

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    // Android 15+ принудительно включает edge-to-edge — выключаем.
    // (На API 29–34 — no-op.)
    try {
      WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
    } catch (Throwable ignored) {
    }

    // ★ НОВОЕ: регистрируем OnBackInvokedCallback на API 33+.
    // Без этого onBackPressed() в наследниках НЕ вызывается, потому что
    // в манифесте enableOnBackInvokedCallback="true".
    registerBackInvoked();
  }

  /**
   * ★ НОВОЕ: регистрация OnBackInvokedCallback на API 33+.
   *
   * Сводит системный back в onBackPressed() — единая точка входа для
   * всех Activity. Наследники продолжают переопределять onBackPressed
   * как раньше, и это работает на всех API.
   *
   * ВАЖНО: PRIORITY_DEFAULT — стандартный приоритет. Если Activity
   * показывает диалог/фрагмент, который хочет перехватить back — он
   * может зарегистрировать свой колбэк с PRIORITY_OVERLAY.
   *
   * Раньше: CallActivity регистрировал колбэк вручную в onCreate.
   * Теперь это делает BaseActivity — ручная регистрация в наследниках
   * больше не нужна (и приведёт к двойному вызову onBackPressed,
   * если её не убрать).
   */
  private void registerBackInvoked() {
    if (Build.VERSION.SDK_INT < 33) {
      return;
    }
    try {
      getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
          android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
          () -> {
            // Проверки на всякий случай: если Activity уже финиширует
            // или уничтожена — не дёргаем onBackPressed.
            if (!isFinishing() && !isDestroyed()) {
              onBackPressed();
            }
          });
    } catch (Throwable ignored) {
      // На некоторых прошивках (MIUI/HyperOS) OnBackInvokedDispatcher
      // может бросить — молча пропускаем, onBackPressed будет
      // вызываться по-старому (если прошивка игнорирует флаг манифеста).
    }
  }

  // ============================================================
  // setContentView + WindowInsets
  // ============================================================

  @Override
  public void setContentView(int layoutResID) {
    super.setContentView(layoutResID);
    applyWindowInsets();
  }

  @Override
  public void setContentView(View view) {
    super.setContentView(view);
    applyWindowInsets();
  }

  /**
   * Применяет WindowInsets к корневому View. Работает на API 29+.
   * Требует androidx.core; если его нет — тихо пропускаем.
   */
  private void applyWindowInsets() {
    try {
      View root = findViewById(android.R.id.content);
      if (root == null)
        return;
      ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
        Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
        return WindowInsetsCompat.CONSUMED;
      });
      ViewCompat.requestApplyInsets(root);
    } catch (Throwable ignored) {
    }
  }

  // ============================================================
  // setSupportActionBar
  // ============================================================

  /**
   * Привязывает Toolbar как action bar.
   *
   * ★ КЛЮЧЕВАЯ СТРОКА: setActionBar(toolbar) — без неё onCreateOptionsMenu
   * не вызывается никогда, и меню не появляется.
   *
   * @param toolbar системный android.widget.Toolbar (НЕ AppCompat).
   *                Если null — action bar отключается.
   */
  public void setSupportActionBar(Toolbar toolbar) {
    this.supportToolbar = toolbar;
    if (toolbar != null) {
      // Регистрируем toolbar как системный action bar.
      // После этого система сама:
      //   - вызовет onCreateOptionsMenu / onPrepareOptionsMenu;
      //   - вызовет onOptionsItemSelected при клике по пунктам;
      //   - нарисует navigation-иконку «назад» (если setDisplayHomeAsUpEnabled).
      setActionBar(toolbar);
      this.supportActionBar = new SupportActionBar(toolbar, this);
      if (homeAsUpEnabled) {
        supportActionBar.setDisplayHomeAsUpEnabled(true);
      }
    } else {
      setActionBar(null);
      this.supportActionBar = null;
    }
  }

  // ============================================================
  // getSupportActionBar
  // ============================================================

  /**
   * Возвращает обёртку над Toolbar. Аналог
   * androidx.appcompat.app.AppCompatActivity.getSupportActionBar().
   *
   * @return SupportActionBar или null, если setSupportActionBar не вызывался.
   */
  public SupportActionBar getSupportActionBar() {
    return supportActionBar;
  }

  /**
   * ★ НОВОЕ: диагностическая версия getSupportActionBar().
   *
   * Если setSupportActionBar() не был вызван (или вызван позже первого
   * обращения) — бросает IllegalStateException с именем класса.
   *
   * Пример использования:
   *   requireSupportActionBar().setTitle("Файлы");
   *   requireSupportActionBar().setDisplayHomeAsUpEnabled(true);
   *
   * Экономит часы отладки «почему меню не появляется»: сразу видно,
   * что проблема в порядке вызовов, а не в layout или теме.
   */
  public SupportActionBar requireSupportActionBar() {
    SupportActionBar ab = supportActionBar;
    if (ab == null) {
      throw new IllegalStateException(getClass().getSimpleName()
          + ": setSupportActionBar() не вызван (или вызван позже первого обращения)");
    }
    return ab;
  }

  // ============================================================
  // getMenuInflater
  // ============================================================

  /**
   * Системный MenuInflater. Уже есть в android.app.Activity, но
   * переопределяем для совместимости с AppCompat-кодом.
   *
   * Работает с android.view.Menu (НЕ AppCompat-меню).
   */
  @Override
  public MenuInflater getMenuInflater() {
    return super.getMenuInflater();
  }

  // ============================================================
  // ★ SupportActionBar — ОДИН класс-обёртка
  // ============================================================

  /**
   * Обёртка над android.widget.Toolbar, имитирующая API
   * androidx.appcompat.app.ActionBar.
   *
   * После setActionBar(toolbar) системный ActionBar сам управляет
   * navigation-иконкой и title тулбара. Поэтому методы этой обёртки
   * сначала идут в activity.getActionBar(), а если его нет —
   * в toolbar напрямую (fallback для активити без action bar).
   */
  public static class SupportActionBar {

    private final Toolbar toolbar;
    private final Activity activity;

    public SupportActionBar(Toolbar toolbar, Activity activity) {
      this.toolbar = toolbar;
      this.activity = activity;
    }

    /** Системный ActionBar (есть после setActionBar()). */
    private android.app.ActionBar fw() {
      return activity != null ? activity.getActionBar() : null;
    }

    // --------------------------------------------------------
    // Title / Subtitle
    // --------------------------------------------------------

    public void setTitle(CharSequence title) {
      android.app.ActionBar ab = fw();
      if (ab != null)
        ab.setTitle(title);
      else if (toolbar != null)
        toolbar.setTitle(title);
    }

    public void setTitle(int resId) {
      android.app.ActionBar ab = fw();
      if (ab != null)
        ab.setTitle(resId);
      else if (toolbar != null) {
        try {
          toolbar.setTitle(resId);
        } catch (Throwable ignored) {
        }
      }
    }

    public CharSequence getTitle() {
      android.app.ActionBar ab = fw();
      return ab != null ? ab.getTitle() : (toolbar != null ? toolbar.getTitle() : null);
    }

    public void setSubtitle(CharSequence subtitle) {
      android.app.ActionBar ab = fw();
      if (ab != null)
        ab.setSubtitle(subtitle);
      else if (toolbar != null)
        toolbar.setSubtitle(subtitle);
    }

    public void setSubtitle(int resId) {
      android.app.ActionBar ab = fw();
      if (ab != null)
        ab.setSubtitle(resId);
      else if (toolbar != null) {
        try {
          toolbar.setSubtitle(resId);
        } catch (Throwable ignored) {
        }
      }
    }

    public CharSequence getSubtitle() {
      android.app.ActionBar ab = fw();
      return ab != null ? ab.getSubtitle() : (toolbar != null ? toolbar.getSubtitle() : null);
    }

    // --------------------------------------------------------
    // Home as up (back arrow)
    // --------------------------------------------------------

    /**
     * После setActionBar() стрелкой управляет системный ActionBar,
     * а клик по ней сам приходит в onOptionsItemSelected как
     * android.R.id.home. Прямые toolbar.setNavigationIcon/OnClickListener
     * здесь не нужны — фреймворк их перетрёт.
     *
     * Fallback (без action bar) — старая иконка ic_media_previous.
     */
    public void setDisplayHomeAsUpEnabled(boolean enabled) {
      android.app.ActionBar ab = fw();
      if (ab != null) {
        ab.setDisplayHomeAsUpEnabled(enabled);
      } else if (toolbar != null) {
        if (enabled) {
          toolbar.setNavigationIcon(android.R.drawable.ic_media_previous);
        } else {
          toolbar.setNavigationIcon(null);
        }
      }
    }

    // --------------------------------------------------------
    // Display options
    // --------------------------------------------------------

    public void setDisplayShowTitleEnabled(boolean show) {
      android.app.ActionBar ab = fw();
      if (ab != null)
        ab.setDisplayShowTitleEnabled(show);
      else if (toolbar != null)
        toolbar.setTitle(show ? "" : null);
    }

    public void setDisplayShowHomeEnabled(boolean show) {
      if (!show) {
        android.app.ActionBar ab = fw();
        if (ab != null) {
          ab.setDisplayHomeAsUpEnabled(false);
        } else if (toolbar != null) {
          toolbar.setNavigationIcon(null);
        }
      }
    }

    // --------------------------------------------------------
    // Hide / show
    // --------------------------------------------------------

    public void hide() {
      if (toolbar != null)
        toolbar.setVisibility(View.GONE);
    }

    public void show() {
      if (toolbar != null)
        toolbar.setVisibility(View.VISIBLE);
    }

    // --------------------------------------------------------
    // Доступ к Toolbar
    // --------------------------------------------------------

    public Toolbar getToolbar() {
      return toolbar;
    }
  }

  // ============================================================
  // home-клик (запасной механизм для активити без action bar)
  // ============================================================

  /**
   * После setActionBar() клик по «назад» сам попадает в
   * onOptionsItemSelected как android.R.id.home — этот обработчик
   * становится запасным для активити без action bar.
   *
   * ВАЖНО: навешиваем setNavigationOnClickListener напрямую ТОЛЬКО
   * если action bar НЕ создан. Иначе перетрёт системную навигацию,
   * и клик по стрелке перестанет доходить до onOptionsItemSelected.
   */
  public void setHomeClickListener(Runnable listener) {
    this.homeClickListener = listener;
    if (supportToolbar != null && homeAsUpEnabled && getSupportActionBar() == null) {
      supportToolbar.setNavigationOnClickListener(v -> {
        if (homeClickListener != null)
          homeClickListener.run();
      });
    }
  }

  public Runnable getHomeClickListener() {
    return homeClickListener;
  }

  // ============================================================
  // onOptionsItemSelected
  // ============================================================

  /**
   * Переопределён, чтобы обработать android.R.id.home, если Activity
   * его не переопределила. Если переопределила — вызывается её версия
   * (через super.onOptionsItemSelected).
   */
  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    if (item != null && item.getItemId() == android.R.id.home) {
      if (homeClickListener != null) {
        homeClickListener.run();
        return true;
      }
    }
    return super.onOptionsItemSelected(item);
  }

  // ============================================================
  // ★ НОВОЕ: утилиты
  // ============================================================

  /**
   * Скрывает мягкую клавиатуру. Полезно в редакторе, поиске,
   * при закрытии диалога.
   *
   * Безопасно вызывать из любого места: если IME нет или фокуса нет —
   * no-op.
   */
  protected void hideKeyboard() {
    try {
      InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
      View focus = getCurrentFocus();
      if (imm != null && focus != null) {
        imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
      }
    } catch (Throwable ignored) {
    }
  }

  /**
   * Держит экран включённым. Полезно в плеере, звонке, редакторе.
   * Не забудьте вызвать keepScreenOn(false) в onPause/onDestroy.
   */
  protected void keepScreenOn(boolean on) {
    try {
      if (on) {
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      } else {
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      }
    } catch (Throwable ignored) {
    }
  }
}