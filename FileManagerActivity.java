package com.my.mealse.filemanager.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Toolbar;

import java.util.concurrent.atomic.AtomicInteger;
import androidx.core.content.FileProvider;

import com.my.mealse.BaseActivity;
import com.my.mealse.MealseApp;
import com.my.mealse.R;
import com.my.mealse.filemanager.FileEntry;
import com.my.mealse.filemanager.FileListAdapter;
import com.my.mealse.filemanager.FileSortType;
import com.my.mealse.filemanager.FileTransport;
import com.my.mealse.filemanager.FileTransportException;
import com.my.mealse.filemanager.FileTransportFactory;
import com.my.mealse.filemanager.LocalFileEntry;
import com.my.mealse.filemanager.RemoteFsProtocol;
import com.my.mealse.filemanager.util.FileClipboard;
import com.my.mealse.filemanager.util.FileTypeHelper;
import com.my.mealse.filemanager.util.TrashManager;
import com.my.mealse.filemanager.viewer.ImagePreviewActivity;
import com.my.mealse.filemanager.viewer.MAS_TextEditor;
import com.my.mealse.filemanager.viewer.MAS_VideoPlayerActivity;
import com.my.mealse.messenger.model.AttachmentInfo;
import com.my.mealse.messenger.model.Conversation;
import com.my.mealse.messenger.repo.ConversationRepository;
import com.my.mealse.messenger.transport.MessageCommands;
import com.my.mealse.model.TrustedNode;
import com.my.mealse.util.MealseLogger;
import com.my.mealse.util.NodeManager;
import com.my.mealse.util.WifiDirectManager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Файловый менеджер Mealse.
 *
 * Источники: 1. 📱 Это устройство — внешняя память. 2. 📦 Приложение — getFilesDir(). 3. Удалённые узлы — через RemoteFsService.
 *
 * ★ Шаг 16.6: «💬 Отправить в чат» — выбор диалога → MessageCommands.sendFile.
 *
 * ★ НОВОЕ: pick-mode. Запускается с EXTRA_PICK_MODE=true из ChatActivity/CallActivity. Показывает только локальные источники (📱 Это устройство, 📦 Приложение). При тапе по файлу — setResult(RESULT_OK, Intent с extras) и finish(). Папки — обычная навигация. Долгий тап/меню действий — отключены.
 */
public class FileManagerActivity extends BaseActivity {

  private static final String TAG = "FileManager";
  private static final int REQ_PICK_LOCAL_FILE = 4101;

  private static final String APP_SUBDIR_DEFAULT = "downloads";
  private static final String EXTERNAL_SUBDIR = "Mealse";

  private static final String STATE_SOURCE_KEY = "state_source_key";
  private static final String STATE_REMOTE_PATH = "state_remote_path";
  private static final String STATE_LOCAL_PATH = "state_local_path";

  // ★ Pick-mode extras
  public static final String EXTRA_PICK_MODE = "pick_mode";
  public static final String EXTRA_PICKED_PATH = "picked_path";
  public static final String EXTRA_PICKED_NAME = "picked_name";
  public static final String EXTRA_PICKED_MIME = "picked_mime";
  public static final String EXTRA_PICKED_SIZE = "picked_size";
  public static final String EXTRA_PICKED_SOURCE = "picked_source";

  public static final String EXTRA_PICK_MODE_FOLDER = "pick_mode_folder";
  public static final String EXTRA_PICKED_IS_FOLDER = "picked_is_folder";

  private boolean pickModeFolder = false;

  private volatile boolean destroyed = false;
  private boolean pickMode = false;

  // ===== Views =====
  private Toolbar toolbar;
  private Spinner sourceSpinner;
  private TextView currentPathView;
  private ListView listView;
  private TextView emptyView;
  private ProgressBar loadingView;
  private ImageButton btnUp;

  // ===== Источники =====
  private final List<FileManagerSource> sources = new ArrayList<>();
  private FileManagerSource currentSource;
  private FileManagerSourceAdapter sourceAdapter;

  // ===== Список =====
  private FileListAdapter listAdapter;
  private FileSortType sortType = FileSortType.NAME_ASC;
  private boolean showHidden = false;

  // ===== Режимы =====
  private File currentLocalDir;
  private String currentRemotePath;
  private FileTransport currentTransport;

  // ===== ActionMode =====
  private ActionMode actionMode;

  // ===== Менеджеры =====
  private MealseLogger log;
  private NodeManager nodeManager;
  private FileTransportFactory transportFactory;
  private TrashManager trashManager;

  // ===== Защита от повторной загрузки тяжёлой папки =====
  private final AtomicInteger loadGeneration = new AtomicInteger(0);
  private long lastFolderClickTime = 0L;
  private static final long FOLDER_CLICK_DEBOUNCE_MS = 500;

  // ===== Paste state =====
  private int pasteTotal = 0;
  private int pasteCompleted = 0;
  private int pasteSuccess = 0;
  private int pasteFailed = 0;
  private Runnable pasteDoneCallback;

  // ===== Прогресс-диалог =====
  private AlertDialog progressDialog;
  private ProgressBar dialogProgressBar;
  private TextView dialogMessage, dialogPercent;

  private boolean storageDialogShown = false;

  // ★ Шаг 16.6: executor для sendFileToChat
  private final ExecutorService chatExecutor = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "FmSendToChat");
    t.setDaemon(true);
    return t;
  });

  // ============================================================
  // Lifecycle
  // ============================================================

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    log = MealseLogger.get(this, TAG);
    log.log("══ onCreate START ══");
    log.log("SDK=" + Build.VERSION.SDK_INT);

    // ★ pick-mode читаем ДО initToolbar
    pickMode = getIntent().getBooleanExtra(EXTRA_PICK_MODE, false);
    log.log("pickMode=" + pickMode);

    pickMode = getIntent().getBooleanExtra(EXTRA_PICK_MODE, false);
    pickModeFolder = getIntent().getBooleanExtra(EXTRA_PICK_MODE_FOLDER, false);
    log.log("pickMode=" + pickMode + ", pickModeFolder=" + pickModeFolder);

    setContentView(R.layout.activity_file_manager);

    nodeManager = new NodeManager(this);
    transportFactory = FileTransportFactory.get(this);
    trashManager = TrashManager.get(this);
    try {
      trashManager.cleanupOldFiles();
    } catch (Exception ignored) {
    }

    initViews();
    initToolbar();

    buildSources();
    if (pickMode) {
      buildSourcesForPick();
    }
    sourceAdapter.replaceAll(sources);

    FileManagerSource initial = null;
    File initialLocalDir = null;
    String initialRemotePath = null;

    if (savedInstanceState != null) {
      String sourceKey = savedInstanceState.getString(STATE_SOURCE_KEY, null);
      if (sourceKey != null) {
        for (FileManagerSource s : sources) {
          if (s.key().equals(sourceKey)) {
            initial = s;
            break;
          }
        }
      }
      initialRemotePath = savedInstanceState.getString(STATE_REMOTE_PATH, null);
      String localPath = savedInstanceState.getString(STATE_LOCAL_PATH, null);
      if (localPath != null)
        initialLocalDir = new File(localPath);
    }

    if (initial == null) {
      initial = sources.isEmpty()
          ? FileManagerSource.local(getInitialExternalRoot().getAbsolutePath())
          : sources.get(0);
    }
    if (initialLocalDir == null) {
      initialLocalDir = new File(initial.rootPath);
    }

    currentSource = initial;
    currentLocalDir = initialLocalDir;
    currentRemotePath = initialRemotePath;

    int idx = sourceAdapter.indexOf(initial);
    if (idx >= 0)
      sourceSpinner.setSelection(idx);

    if (initial.isRemote() && currentRemotePath != null) {
      try {
        currentTransport = transportFactory.forNode(initial.node);
      } catch (FileTransportException ignored) {
        currentTransport = null;
      }
      loadRemote(currentRemotePath);
    } else {
      setSource(initial);
    }

    checkStoragePermission(false);

    log.log("══ onCreate END ══");
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    if (currentSource != null) {
      out.putString(STATE_SOURCE_KEY, currentSource.key());
    }
    if (currentRemotePath != null) {
      out.putString(STATE_REMOTE_PATH, currentRemotePath);
    }
    if (currentLocalDir != null) {
      out.putString(STATE_LOCAL_PATH, currentLocalDir.getAbsolutePath());
    }
  }

  @Override
  protected void onResume() {
    super.onResume();

    destroyed = false;

    checkStoragePermission(true);

    FileManagerSource keep = currentSource;
    buildSources();
    if (pickMode) {
      buildSourcesForPick();
    }
    sourceAdapter.replaceAll(sources);

    int idx = keep != null ? sourceAdapter.indexOf(keep) : -1;
    if (idx >= 0) {
      sourceSpinner.setSelection(idx);
    } else if (!sources.isEmpty()) {
      currentSource = sources.get(0);
      sourceSpinner.setSelection(0);
      setSource(currentSource);
    }
  }

  @Override
  protected void onDestroy() {
    destroyed = true;
    loadGeneration.incrementAndGet();
    if (actionMode != null) {
      actionMode.finish();
      actionMode = null;
    }
    if (listView != null) {
      try {
        listView.setAdapter(null);
      } catch (Throwable ignored) {
      }
    }
    if (listAdapter != null) {
      listAdapter.release();
      listAdapter = null;
    }
    if (progressDialog != null && progressDialog.isShowing())
      progressDialog.dismiss();
    try {
      chatExecutor.shutdownNow();
    } catch (Exception ignored) {
    }
    super.onDestroy();
  }

  // ============================================================
  // Разрешение «Все файлы»
  // ============================================================

  private void checkStoragePermission(boolean fromResume) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      return;
    }

    boolean hasAccess;
    try {
      hasAccess = Environment.isExternalStorageManager();
    } catch (Throwable t) {
      log.warn("isExternalStorageManager: " + t.getMessage());
      hasAccess = false;
    }

    log.log("checkStoragePermission: hasAccess=" + hasAccess + ", fromResume=" + fromResume);

    if (hasAccess) {
      if (currentSource != null && currentSource.isLocal()
          && !currentSource.rootPath.startsWith(getFilesDir().getAbsolutePath())) {
        log.log("Разрешение выдано — перезагружаем локальный источник");
        reloadCurrent();
      }
      return;
    }

    if (!fromResume && storageDialogShown) {
      return;
    }
    storageDialogShown = true;
    showStoragePermissionDialog();
  }

  private void showStoragePermissionDialog() {
    if (isFinishing() || isUiAlive() == false && destroyed)
      return;

    try {
      new AlertDialog.Builder(this).setTitle("Доступ к файлам")
          .setMessage("Для отображения файлов на устройстве "
              + "требуется разрешение «Доступ ко всем файлам».\n\n"
              + "Нажмите «Открыть настройки», найдите Mealse в списке "
              + "и включите «Разрешить управление всеми файлами».\n\n"
              + "Без этого разрешения будет доступна только папка «📦 Приложение».")
          .setPositiveButton("Открыть настройки", (d, w) -> openStorageSettings())
          .setNegativeButton("Только «Приложение»", (d, w) -> d.dismiss()).setCancelable(true)
          .show();
    } catch (Exception e) {
      log.logError("showStoragePermissionDialog", e);
    }
  }

  private void openStorageSettings() {
    try {
      Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
      intent.setData(Uri.parse("package:" + getPackageName()));
      startActivity(intent);
      log.log("Открыты настройки MANAGE_APP_ALL_FILES_ACCESS_PERMISSION");
    } catch (Exception e) {
      log.warn("ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION: " + e.getMessage());
      try {
        Intent fallback = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
        startActivity(fallback);
        log.log("Открыт общий экран MANAGE_ALL_FILES_ACCESS_PERMISSION");
      } catch (Exception e2) {
        log.logError("Не удалось открыть настройки", e2);
        toast("Откройте вручную: Настройки → Приложения → Mealse → Разрешения");
      }
    }
  }

  private boolean isUiAlive() {
    if (destroyed)
      return false;
    if (isFinishing())
      return false;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
      if (isDestroyed())
        return false;
    }
    return listAdapter != null;
  }

  // ============================================================
  // Init
  // ============================================================

  private void initViews() {
    toolbar = findViewById(R.id.fm_toolbar);
    sourceSpinner = findViewById(R.id.fm_source_spinner);
    currentPathView = findViewById(R.id.fm_current_path);
    listView = findViewById(R.id.fm_list);
    emptyView = findViewById(R.id.fm_empty);
    loadingView = findViewById(R.id.fm_loading);
    btnUp = findViewById(R.id.fm_btn_up);

    listAdapter = new FileListAdapter(this);
    listView.setAdapter(listAdapter);

    listView.setOnItemClickListener((parent, view, position, id) -> {
      if (!isUiAlive())
        return;
      if (listAdapter.isMultiSelectMode()) {
        FileEntry e = listAdapter.getItem(position);
        listAdapter.toggleSelection(e.getPath());
        updateActionModeTitle();
        return;
      }
      onFileClicked(listAdapter.getItem(position));
    });

    listView.setOnItemLongClickListener((parent, view, position, id) -> {
      if (!isUiAlive())
        return false;
      if (pickMode)
        return false;
      if (!listAdapter.isMultiSelectMode()) {
        startMultiSelect(position);
        return true;
      }
      return false;
    });

    btnUp.setOnClickListener(v -> {
      if (!isUiAlive())
        return;
      goUp();
    });

    sourceAdapter = new FileManagerSourceAdapter(this);
    sourceSpinner.setAdapter(sourceAdapter);
    sourceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      @Override
      public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if (!isUiAlive())
          return;
        FileManagerSource s = sourceAdapter.getItem(position);
        if (currentSource == null || !currentSource.equals(s)) {
          setSource(s);
        }
      }

      @Override
      public void onNothingSelected(AdapterView<?> parent) {
      }
    });
  }

  private void initToolbar() {
    setSupportActionBar(toolbar);
    if (getSupportActionBar() != null) {
      getSupportActionBar().setDisplayHomeAsUpEnabled(true);
      if (pickModeFolder) {
        getSupportActionBar().setTitle("Выберите папку");
      } else if (pickMode) {
        getSupportActionBar().setTitle("Выберите файл");
      }
    }
    setHomeClickListener(this::finish);
  }

  private File getInitialExternalRoot() {
    File ext = Environment.getExternalStorageDirectory();
    if (ext != null && ext.exists() && ext.canRead())
      return ext;
    return getFilesDir();
  }

  /**
   * ★ ИЗМЕНЕНО (правка G): подключение menu_file_manager с сохранением всей исходной логики pick-mode.
   *
   * Что было: if (pickMode && !pickModeFolder) return false; // файл-pick — без меню getMenuInflater().inflate(R.menu.menu_file_manager, menu); return true;
   *
   * Что стало: - логика pick-mode файла сохранена (return false — меню не нужно); - добавлена диагностика: если menu_file_manager пуст/битый — логируем, чтобы понять, почему «меню не отображается»; - всё остальное — как было.
   *
   * ВАЖНО: если FileManagerActivity использует android.widget.Toolbar (без AppCompat) — toolbar должен быть установлен через setActionBar(toolbar) (НЕ setSupportActionBar), иначе onCreateOptionsMenu не вызовется и меню не появится вообще.
   */
  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    if (pickMode && !pickModeFolder) {
      // Только pick-mode файла — без меню.
      // (Выбор одного файла не требует refresh/new_folder/paste/trash.)
      return false;
    }
    getMenuInflater().inflate(R.menu.menu_file_manager, menu);
    if (menu.size() == 0) {
      // ★ Диагностика: меню инфлейтнулось, но пустое — R.menu.menu_file_manager
      // пуст или все пункты android:visible="false" без onPrepare.
      android.util.Log.w("FileManagerActivity",
          "onCreateOptionsMenu: menu_file_manager пуст после inflate — "
              + "проверьте res/menu/menu_file_manager.xml");
    }
    return true;
  }

  /**
   * ★ ИЗМЕНЕНО (правка G): onPrepareOptionsMenu с сохранением всей исходной логики (pickMode/pickModeFolder, FileClipboard COPY/MOVE, new_text только для local, showHidden) и добавлением видимости пунктов, которые в pick-mode не имеют смысла.
   *
   * Ключевое исправление: раньше в ветке `if (pickMode) { ... return ... }` обновлялся ТОЛЬКО fm_menu_pick_this_folder, а paste/new_text/showHidden не обновлялись — в pick-режиме панель вставки могла показывать устаревшее состояние. Теперь обновление paste вынесено ДО ветвления.
   *
   * Также добавлено: в pick-mode скрываем refresh/new_folder/new_text/ search/trash/sort — они не имеют смысла при выборе файла/папки (пользователь не должен создавать папки/корзину в чужом дереве).
   */
  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    // ---- 1. Paste: обновляем ВСЕГДА (и в pick-mode тоже), т.к. в
    // pick-modeFolder пользователь может вставить в выбранную папку.
    MenuItem pasteItem = menu.findItem(R.id.fm_menu_paste);
    if (pasteItem != null) {
      if (FileClipboard.isEmpty()) {
        pasteItem.setVisible(false);
      } else {
        pasteItem.setVisible(true);
        int count = FileClipboard.size();
        FileClipboard.Op op = FileClipboard.getOp();
        String verb = (op == FileClipboard.Op.COPY) ? "Вставить" : "Переместить сюда";
        String icon = (op == FileClipboard.Op.COPY) ? "📋" : "✂";
        pasteItem.setTitle(icon + " " + verb + " (" + count + ")");
      }
    }

    // ---- 2. Pick-mode: показываем «Выбрать эту папку» только если
    // выбран режим папки. Остальные пункты скрываем.
    if (pickMode) {
      MenuItem pickItem = menu.findItem(R.id.fm_menu_pick_this_folder);
      if (pickItem != null) {
        pickItem.setVisible(pickModeFolder);
      }
      // ★ НОВОЕ: в pick-режиме скрываем операции, не имеющие смысла.
      setVisible(menu, R.id.fm_menu_refresh, false);
      setVisible(menu, R.id.fm_menu_new_folder, false);
      setVisible(menu, R.id.fm_menu_new_text, false);
      setVisible(menu, R.id.fm_menu_search, false);
      setVisible(menu, R.id.fm_menu_trash, false);
      setVisible(menu, R.id.fm_menu_sort, false);
      // show_hidden оставляем — пользователю полезно видеть скрытые
      // файлы при выборе. Если не нужно — раскомментируйте:
      // setVisible(menu, R.id.fm_menu_show_hidden, false);
      return super.onPrepareOptionsMenu(menu);
    }

    // ---- 3. Обычный режим (не pick).
    // fm_menu_pick_this_folder всегда скрыт.
    MenuItem pickItem = menu.findItem(R.id.fm_menu_pick_this_folder);
    if (pickItem != null) {
      pickItem.setVisible(false);
    }

    MenuItem hiddenItem = menu.findItem(R.id.fm_menu_show_hidden);
    if (hiddenItem != null)
      hiddenItem.setChecked(showHidden);

    // new_text — только для локального источника (не для удалённого).
    MenuItem newTextItem = menu.findItem(R.id.fm_menu_new_text);
    if (newTextItem != null) {
      boolean isLocal = currentSource != null && currentSource.isLocal();
      newTextItem.setVisible(isLocal);
    }

    // refresh/new_folder/search/trash/sort — видимы по умолчанию
    // (их visible в XML = true). Явно включаем на случай, если
    // предыдущий onPrepare их скрыл (например, при выходе из pick-mode).
    setVisible(menu, R.id.fm_menu_refresh, true);
    setVisible(menu, R.id.fm_menu_new_folder, true);
    setVisible(menu, R.id.fm_menu_search, true);
    setVisible(menu, R.id.fm_menu_trash, true);
    setVisible(menu, R.id.fm_menu_sort, true);

    return super.onPrepareOptionsMenu(menu);
  }

  /**
   * ★ НОВОЕ (правка G): безопасная установка видимости пункта меню. Если пункт не найден (например, его нет в menu_file_manager.xml) — молча пропускаем, не роняя onPrepareOptionsMenu.
   */
  private void setVisible(Menu menu, int itemId, boolean visible) {
    MenuItem item = menu.findItem(itemId);
    if (item != null)
      item.setVisible(visible);
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    int id = item.getItemId();
    if (id == android.R.id.home) {
      finish();
      return true;
    }
    if (pickMode) {
      return super.onOptionsItemSelected(item);
    }
    if (id == R.id.fm_menu_refresh) {
      reloadCurrent();
      return true;
    }
    if (id == R.id.fm_menu_new_folder) {
      askCreateFolder();
      return true;
    }
    if (id == R.id.fm_menu_new_text) {
      askCreateTextFile();
      return true;
    }
    if (id == R.id.fm_menu_search) {
      askSearch();
      return true;
    }
    if (id == R.id.fm_menu_paste) {
      doPasteFromMenu();
      return true;
    }
    if (id == R.id.fm_menu_trash) {
      startActivity(new Intent(this, TrashActivity.class));
      return true;
    }
    if (id == R.id.fm_menu_show_hidden) {
      showHidden = !showHidden;
      item.setChecked(showHidden);
      reloadCurrent();
      return true;
    }
    if (id == R.id.fm_sort_name_asc) {
      setSort(FileSortType.NAME_ASC);
      return true;
    }
    if (id == R.id.fm_sort_name_desc) {
      setSort(FileSortType.NAME_DESC);
      return true;
    }
    if (id == R.id.fm_sort_date_desc) {
      setSort(FileSortType.DATE_DESC);
      return true;
    }
    if (id == R.id.fm_sort_date_asc) {
      setSort(FileSortType.DATE_ASC);
      return true;
    }
    if (id == R.id.fm_sort_size_desc) {
      setSort(FileSortType.SIZE_DESC);
      return true;
    }
    if (id == R.id.fm_sort_size_asc) {
      setSort(FileSortType.SIZE_ASC);
      return true;
    }
    if (id == R.id.fm_menu_pick_this_folder) {
      pickCurrentFolderAndFinish();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  private void setSort(FileSortType t) {
    sortType = t;
    reloadCurrent();
  }

  /** ★ НОВОЕ: возвращает текущую папку как результат pick-mode-folder. */
  private void pickCurrentFolderAndFinish() {
    try {
      if (currentSource == null || !currentSource.isLocal()) {
        toast("Можно выбрать только локальную папку");
        return;
      }
      if (currentLocalDir == null || !currentLocalDir.isDirectory()) {
        toast("Текущая папка недоступна");
        return;
      }
      Intent result = new Intent();
      result.putExtra(EXTRA_PICKED_PATH, currentLocalDir.getAbsolutePath());
      result.putExtra(EXTRA_PICKED_NAME, currentLocalDir.getName());
      result.putExtra(EXTRA_PICKED_IS_FOLDER, true);
      setResult(RESULT_OK, result);
      finish();
    } catch (Exception e) {
      log.logError("pickCurrentFolderAndFinish", e);
      toast("Ошибка: " + e.getMessage());
    }
  }

  // ============================================================
  // Источники
  // ============================================================

  private void buildSources() {
    sources.clear();

    File ext = getInitialExternalRoot();
    sources.add(FileManagerSource.local(ext.getAbsolutePath()));

    File appInternal = getFilesDir();
    if (appInternal != null && appInternal.exists()) {
      sources.add(FileManagerSource.localApp(appInternal.getAbsolutePath()));
    }

    if (pickMode) {
      return;
    }

    try {
      List<TrustedNode> nodes = nodeManager.getAllNodes();
      for (TrustedNode n : nodes) {
        if (n.getMode() == TrustedNode.Mode.WIFI_DIRECT) {
          WifiDirectManager wdm = ((MealseApp) getApplication()).getWifiDirectManager();
          if (wdm == null || !wdm.isConnected())
            continue;
        }
        sources.add(FileManagerSource.remote(n, "/"));
      }
    } catch (Exception e) {
      log.logError("buildSources", e);
    }
  }

  private void buildSourcesForPick() {
    sources.clear();
    File ext = getInitialExternalRoot();
    sources.add(FileManagerSource.local(ext.getAbsolutePath()));
    File appInternal = getFilesDir();
    if (appInternal != null && appInternal.exists()) {
      sources.add(FileManagerSource.localApp(appInternal.getAbsolutePath()));
    }
    if (sourceAdapter != null)
      sourceAdapter.replaceAll(sources);
  }

  private void setSource(FileManagerSource source) {
    log.log("setSource: " + source.displayName);
    currentSource = source;

    if (source.isLocal()) {
      currentLocalDir = new File(source.rootPath);
      currentRemotePath = null;
      currentTransport = null;
      reloadCurrent();
      return;
    }

    try {
      currentTransport = transportFactory.forNode(source.node);
    } catch (FileTransportException e) {
      toast(humanizeError(e));
      return;
    }

    showLoading(true);
    updatePathView("Подключение к " + source.displayName + "…");
    if (listAdapter != null)
      listAdapter.clear();
    updateEmptyView(false);

    final FileManagerSource requestSource = source;

    currentTransport.info(new FileTransport.InfoCallback() {
      @Override
      public void onResult(RemoteFsProtocol.RemoteRootInfo info) {
        if (!isUiAlive())
          return;
        if (currentSource != requestSource)
          return;
        showLoading(false);
        log.log("info() OK: " + info);
        currentRemotePath = info.rootPath != null ? info.rootPath : "/";
        loadRemote(currentRemotePath);
      }

      @Override
      public void onError(FileTransportException e) {
        if (!isUiAlive())
          return;
        if (currentSource != requestSource)
          return;
        showLoading(false);
        log.logError("info() FAILED", e);
        if (listAdapter != null)
          listAdapter.clear();
        updatePathView("(нет соединения)");
        updateEmptyView(false);
        showConnectErrorDialog(e, source);
      }
    });
  }

  private void showConnectErrorDialog(FileTransportException e, FileManagerSource src) {
    if (!isUiAlive())
      return;

    String title;
    String message;
    if (e.getCode() == FileTransportException.CODE_ACCESS_DENIED) {
      title = "Доступ не разрешён";
      message = "Узел «" + src.displayName + "» не дал доступ к своим файлам.\n\n"
          + "Попросите владельца узла:\n" + "• открыть Mealse на своём устройстве,\n"
          + "• тапнуть по этому узлу в списке,\n" + "• выбрать «🔐 Разрешить доступ к файлам».";
    } else if (e.getCode() == FileTransportException.CODE_NETWORK) {
      title = "Узел недоступен";
      message = "Не удалось связаться с узлом «" + src.displayName + "».\n\n" + "Проверьте:\n"
          + "• узел включён и в той же сети (или подключён по Wi-Fi Direct),\n"
          + "• на узле запущен сервер файлов (Mealse → «Файлы»).";
    } else {
      title = "Ошибка";
      message = humanizeError(e);
    }

    new AlertDialog.Builder(this).setTitle(title).setMessage(message)
        .setPositiveButton("Повторить", (d, w) -> setSource(src))
        .setNegativeButton("Закрыть", (d, w) -> {
          if (!sources.isEmpty()) {
            currentSource = sources.get(0);
            sourceSpinner.setSelection(0);
            setSource(currentSource);
          }
        }).setCancelable(false).show();
  }

  // ============================================================
  // Загрузка содержимого
  // ============================================================

  private void reloadCurrent() {
    if (currentSource == null)
      return;
    if (currentSource.isLocal()) {
      loadLocal(currentLocalDir);
    } else if (currentRemotePath != null) {
      loadRemote(currentRemotePath);
    } else {
      setSource(currentSource);
    }
  }

  private void loadLocal(File dir) {
    if (dir == null || !isUiAlive())
      return;
    if (!dir.exists() || !dir.isDirectory()) {
      toast("Папка недоступна");
      return;
    }

    final int generation = loadGeneration.incrementAndGet();
    final File requestDir = dir;
    final boolean requestShowHidden = showHidden;
    final FileSortType requestSort = sortType;

    showLoading(true);
    updatePathView(dir.getAbsolutePath());

    new Thread(() -> {
      List<FileEntry> entries = new ArrayList<>();
      File[] children = null;
      try {
        children = requestDir.listFiles();
      } catch (Throwable t) {
        log.logError("listFiles", t);
      }

      if (children == null) {
        final boolean hasPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.R
            || Environment.isExternalStorageManager();
        runOnUiThread(() -> {
          if (!isUiAlive() || generation != loadGeneration.get())
            return;
          showLoading(false);
          log.warn("loadLocal: listFiles() == null для " + requestDir.getAbsolutePath());
          if (!hasPerm) {
            checkStoragePermission(false);
          }
          toast("Нет доступа к папке");
          listAdapter.replaceAll(new ArrayList<>());
          updateEmptyView(true);
        });
        return;
      }

      for (File f : children) {
        if (generation != loadGeneration.get()) {
          return;
        }
        if (!requestShowHidden && f.isHidden())
          continue;
        entries.add(new LocalFileEntry(f));
      }

      Collections.sort(entries, buildComparator(requestSort));

      final List<FileEntry> finalEntries = entries;

      runOnUiThread(() -> {
        if (!isUiAlive())
          return;
        if (generation != loadGeneration.get()) {
          return;
        }
        showLoading(false);
        listAdapter.replaceAll(finalEntries);
        updatePathView(requestDir.getAbsolutePath());
        updateEmptyView(finalEntries.isEmpty());
        currentLocalDir = requestDir;
      });
    }, "LoadLocal-" + requestDir.getName()).start();
  }

  private void loadRemote(String path) {
    if (currentTransport == null || !isUiAlive())
      return;

    showLoading(true);

    final String requestPath = path;
    final FileTransport requestTransport = currentTransport;
    final FileManagerSource requestSource = currentSource;

    currentTransport.list(path, new FileTransport.ListCallback() {
      @Override
      public void onResult(String resultPath, List<FileEntry> entries) {
        if (!isUiAlive())
          return;
        if (requestTransport != currentTransport || requestSource != currentSource)
          return;
        showLoading(false);
        if (entries == null)
          entries = new ArrayList<>();
        List<FileEntry> filtered = new ArrayList<>();
        for (FileEntry e : entries) {
          if (!showHidden && e.isHidden())
            continue;
          filtered.add(e);
        }
        sortEntries(filtered);
        listAdapter.replaceAll(filtered);
        updatePathView(resultPath);
        updateEmptyView(filtered.isEmpty());
        currentRemotePath = resultPath;
      }

      @Override
      public void onError(FileTransportException e) {
        if (!isUiAlive())
          return;
        if (requestTransport != currentTransport || requestSource != currentSource)
          return;
        showLoading(false);
        log.logError("list remote", e);
        toast(humanizeError(e));
      }
    });
  }

  private void sortEntries(List<FileEntry> entries) {
    Collections.sort(entries, buildComparator(sortType));
  }

  private Comparator<FileEntry> buildComparator(FileSortType type) {
    final boolean dirsFirst = type.isDirsFirst();
    Comparator<FileEntry> base;
    switch (type) {
      case NAME_DESC:
        base = (a, b) -> b.getName().compareToIgnoreCase(a.getName());
        break;
      case DATE_ASC:
        base = (a, b) -> Long.compare(a.getLastModified(), b.getLastModified());
        break;
      case DATE_DESC:
        base = (a, b) -> Long.compare(b.getLastModified(), a.getLastModified());
        break;
      case SIZE_ASC:
        base = (a, b) -> Long.compare(a.getSize(), b.getSize());
        break;
      case SIZE_DESC:
        base = (a, b) -> Long.compare(b.getSize(), a.getSize());
        break;
      case NAME_ASC:
      default:
        base = (a, b) -> a.getName().compareToIgnoreCase(b.getName());
        break;
    }
    if (!dirsFirst)
      return base;
    return (a, b) -> {
      if (a.isDirectory() != b.isDirectory())
        return a.isDirectory() ? -1 : 1;
      return base.compare(a, b);
    };
  }

  // ============================================================
  // Навигация
  // ============================================================

  private void onFileClicked(FileEntry entry) {
    if (pickMode) {
      if (entry == null)
        return;
      if (entry.isDirectory()) {
        // Навигация (как обычно)
        long now = System.currentTimeMillis();
        if (now - lastFolderClickTime < FOLDER_CLICK_DEBOUNCE_MS)
          return;
        lastFolderClickTime = now;
        if (entry.isRemote()) {
          loadRemote(entry.getPath());
        } else if (entry instanceof LocalFileEntry) {
          loadLocal(((LocalFileEntry) entry).getFile());
        }
        return;
      }
      if (pickModeFolder) {
        // Тап по файлу в pick-mode-folder — игнор (выбирается папка).
        toast("Нажмите «✓ Выбрать» вверху, чтобы выбрать текущую папку");
        return;
      }
      pickFileAndFinish(entry);
      return;
    }

    if (entry.isDirectory()) {
      long now = System.currentTimeMillis();
      if (now - lastFolderClickTime < FOLDER_CLICK_DEBOUNCE_MS) {
        return;
      }
      lastFolderClickTime = now;

      if (entry.isRemote())
        loadRemote(entry.getPath());
      else if (entry instanceof LocalFileEntry)
        loadLocal(((LocalFileEntry) entry).getFile());
    } else {
      showFileActions(entry);
    }
  }

  private void pickFileAndFinish(FileEntry entry) {
    try {
      Intent result = new Intent();
      result.putExtra(EXTRA_PICKED_PATH, entry.getPath());
      result.putExtra(EXTRA_PICKED_NAME, entry.getName());
      result.putExtra(EXTRA_PICKED_MIME, entry.getMimeType());
      result.putExtra(EXTRA_PICKED_SIZE, entry.getSize());
      result.putExtra(EXTRA_PICKED_SOURCE, entry.isRemote() ? "remote" : "local");
      setResult(RESULT_OK, result);
      finish();
    } catch (Exception e) {
      log.logError("pickFileAndFinish", e);
      Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  private void goUp() {
    if (currentSource == null)
      return;
    long now = System.currentTimeMillis();
    if (now - lastFolderClickTime < FOLDER_CLICK_DEBOUNCE_MS)
      return;
    lastFolderClickTime = now;

    if (currentSource.isLocal()) {
      File parent = currentLocalDir != null ? currentLocalDir.getParentFile() : null;
      if (parent != null)
        loadLocal(parent);
    } else {
      String parent = parentPath(currentRemotePath);
      if (parent != null && !parent.equals(currentRemotePath))
        loadRemote(parent);
    }
  }

  private static String parentPath(String path) {
    if (path == null)
      return null;
    if (path.endsWith("/"))
      path = path.substring(0, path.length() - 1);
    int slash = path.lastIndexOf('/');
    if (slash <= 0)
      return "/";
    return path.substring(0, slash);
  }

  private void updatePathView(String path) {
    if (!isUiAlive())
      return;
    if (currentPathView != null)
      currentPathView.setText(path);
  }

  private void updateEmptyView(boolean empty) {
    if (!isUiAlive())
      return;
    if (emptyView == null)
      return;
    if (empty) {
      emptyView.setVisibility(View.VISIBLE);
      emptyView
          .setText(currentSource != null && currentSource.isRemote() ? "📂 Удалённая папка пуста"
              : "📂 Папка пуста");
    } else {
      emptyView.setVisibility(View.GONE);
    }
  }

  private void showLoading(boolean show) {
    if (!isUiAlive())
      return;
    if (loadingView != null) {
      loadingView.setVisibility(show ? View.VISIBLE : View.GONE);
    }
  }

  // ============================================================
  // Меню действий с файлом
  // ============================================================

  private void showFileActions(FileEntry entry) {
    if (!isUiAlive())
      return;
    if (pickMode) {
      pickFileAndFinish(entry);
      return;
    }

    List<String> actions = new ArrayList<>();
    List<Runnable> runnables = new ArrayList<>();

    final boolean canPaste = !FileClipboard.isEmpty();

    if (entry.isRemote()) {
      if (!entry.isDirectory()) {
        actions.add("📂 Открыть");
        runnables.add(() -> openRemote(entry));
        actions.add("⬇ Скачать в загрузки");
        runnables.add(() -> askDownloadRemote(entry));
        actions.add("📥 Скачать в «Приложение»");
        runnables.add(() -> askDownloadRemoteToApp(entry));
      }
      actions.add("📋 Копировать в буфер");
      runnables.add(() -> doClipboardPut(entry, FileClipboard.Op.COPY));
      actions.add("✂ Переместить в буфер");
      runnables.add(() -> doClipboardPut(entry, FileClipboard.Op.MOVE));
      if (canPaste) {
        actions.add("📥 Вставить сюда");
        runnables.add(() -> doPaste(entry.getPath()));
      }
      actions.add("✏ Переименовать");
      runnables.add(() -> askRename(entry));
      if (entry.isDirectory()) {
        actions.add("⬆ Загрузить сюда");
        runnables.add(() -> askUploadToDir(entry));
      }
      actions.add("🗑 Удалить");
      runnables.add(() -> confirmDelete(entry));
    } else {
      if (!entry.isDirectory()) {
        actions.add("📂 Открыть");
        runnables.add(() -> openLocal(entry));
        actions.add("📤 Поделиться");
        runnables.add(() -> shareLocal(entry));
        actions.add("💬 Отправить в чат");
        runnables.add(() -> sendFileToChat(entry));
        actions.add("⬆ Загрузить на узел");
        runnables.add(() -> askUploadToNode(entry));
      }
      actions.add("📋 Копировать в буфер");
      runnables.add(() -> doClipboardPut(entry, FileClipboard.Op.COPY));
      actions.add("✂ Переместить в буфер");
      runnables.add(() -> doClipboardPut(entry, FileClipboard.Op.MOVE));
      if (canPaste) {
        actions.add("📥 Вставить сюда");
        runnables.add(() -> doPaste(
            currentLocalDir != null ? currentLocalDir.getAbsolutePath() : entry.getParentPath()));
      }
      actions.add("✏ Переименовать");
      runnables.add(() -> askRename(entry));
      actions.add("🗑 Удалить");
      runnables.add(() -> confirmDelete(entry));
    }

    new AlertDialog.Builder(this).setTitle(entry.getName())
        .setItems(actions.toArray(new String[0]), (d, w) -> runnables.get(w).run())
        .setNegativeButton("Отмена", null).show();
  }

  // ============================================================
  // Отправить файл в чат
  // ============================================================

  private void sendFileToChat(final FileEntry entry) {
    if (!isUiAlive() || entry == null || entry.isDirectory())
      return;

    final File srcFile;
    if (entry instanceof LocalFileEntry) {
      srcFile = ((LocalFileEntry) entry).getFile();
    } else {
      srcFile = new File(entry.getPath());
    }

    if (!srcFile.exists() || !srcFile.isFile()) {
      toast("Файл недоступен: " + entry.getName());
      return;
    }

    if (srcFile.length() > MessageCommands.MAX_ATTACHMENT_BYTES) {
      toast("Файл слишком большой (" + AttachmentInfo.formatSize(srcFile.length()) + " > "
          + AttachmentInfo.formatSize(MessageCommands.MAX_ATTACHMENT_BYTES) + ")");
      return;
    }

    toast("Загрузка списка чатов...");
    chatExecutor.execute(() -> {
      List<Conversation> conversations;
      try {
        ConversationRepository convRepo = new ConversationRepository(this);
        conversations = convRepo.getAll();
      } catch (Exception e) {
        log.logError("sendFileToChat: getAll", e);
        runOnUiThread(() -> toast("Ошибка загрузки чатов: " + e.getMessage()));
        return;
      }

      final List<Conversation> finalConvs = conversations;
      runOnUiThread(() -> {
        if (!isUiAlive())
          return;

        if (finalConvs.isEmpty()) {
          new AlertDialog.Builder(this).setTitle("Нет чатов")
              .setMessage(
                  "Сначала создайте чат в мессенджере:\n" + "главный экран → 💬 Сообщения → «+».")
              .setPositiveButton("OK", null).show();
          return;
        }

        String[] names = new String[finalConvs.size()];
        for (int i = 0; i < finalConvs.size(); i++) {
          Conversation c = finalConvs.get(i);
          String title = c.getDisplayTitle();
          String typeLabel;
          switch (c.getType()) {
            case GROUP:
              typeLabel = "👥";
              break;
            case CALL:
              typeLabel = "☎";
              break;
            default:
              typeLabel = "👤";
              break;
          }
          names[i] = typeLabel + " " + title;
        }

        new AlertDialog.Builder(this).setTitle("Отправить «" + entry.getName() + "» в чат")
            .setItems(names, (d, w) -> {
              Conversation chosen = finalConvs.get(w);
              doSendFileToConversation(srcFile, chosen);
            }).setNegativeButton("Отмена", null).show();
      });
    });
  }

  private void doSendFileToConversation(File srcFile, Conversation conversation) {
    // ★ ИСПРАВЛЕНО (шаг 17.2): MessageCommands.sendFile синхронно копирует
    // файл в AttachmentStorage.saveFromFile (до ~200 мс для 10 МБ).
    // Вызываем из chatExecutor, а не из UI-потока, чтобы не блокировать UI.
    final String conversationId = conversation.getId();
    final String conversationTitle = conversation.getDisplayTitle();

    chatExecutor.execute(() -> {
      try {
        AttachmentInfo attachment = AttachmentInfo.fromFile(srcFile);
        String messageId = MessageCommands.sendFile(this, conversationId, attachment);

        if (messageId == null) {
          runOnUiThread(() -> toast("Не удалось отправить (файл слишком большой или пустой?)"));
          log.warn("sendFile вернул null для " + srcFile + " → " + conversationId);
          return;
        }

        final String msgId = messageId;
        runOnUiThread(() -> {
          toast("💬 Отправлено в «" + conversationTitle + "»");
          log.log("sendFileToChat: msg=" + shortId(msgId) + ", conv=" + shortId(conversationId)
              + ", file=" + srcFile.getName() + ", size="
              + AttachmentInfo.formatSize(srcFile.length()));
        });

      } catch (Exception e) {
        log.logError("doSendFileToConversation", e);
        runOnUiThread(() -> toast("Ошибка отправки: " + e.getMessage()));
      }
    });
  }

  private static String shortId(String id) {
    if (id == null)
      return "?";
    return id.length() > 8 ? id.substring(0, 8) : id;
  }

  // ============================================================
  // Буфер обмена
  // ============================================================

  private void doClipboardPut(FileEntry entry, FileClipboard.Op op) {
    FileClipboard.put(entry, op);
    String word = (op == FileClipboard.Op.COPY) ? "скопирован" : "вырезан";
    toast("📋 Файл " + word + ": " + entry.getName());
    if (listAdapter != null)
      listAdapter.notifyDataSetChanged();
    invalidateOptionsMenu();
  }

  private void doPaste(String targetDirPath) {
    if (!isUiAlive())
      return;
    if (FileClipboard.isEmpty()) {
      toast("Буфер обмена пуст");
      return;
    }
    List<FileEntry> srcEntries = FileClipboard.peekEntries();
    FileClipboard.Op op = FileClipboard.getOp();
    if (srcEntries.isEmpty()) {
      toast("Буфер обмена пуст");
      return;
    }

    if (targetDirPath == null) {
      toast("Не удалось определить целевую папку");
      return;
    }

    final String fTarget = targetDirPath;
    new AlertDialog.Builder(this)
        .setTitle(op == FileClipboard.Op.COPY ? "📋 Копировать сюда?" : "✂ Переместить сюда?")
        .setMessage("Файлов: " + srcEntries.size() + "\nЦелевая папка: " + fTarget)
        .setPositiveButton(op == FileClipboard.Op.COPY ? "Копировать" : "Переместить",
            (d, w) -> performPaste(srcEntries, fTarget, op))
        .setNegativeButton("Отмена", null).show();
  }

  private void doPasteFromMenu() {
    if (!isUiAlive())
      return;
    if (FileClipboard.isEmpty()) {
      toast("Буфер обмена пуст");
      return;
    }
    if (currentSource == null) {
      toast("Источник не выбран");
      return;
    }

    String targetDir;
    if (currentSource.isLocal()) {
      if (currentLocalDir == null) {
        toast("Папка недоступна");
        return;
      }
      targetDir = currentLocalDir.getAbsolutePath();
    } else {
      if (currentRemotePath == null) {
        toast("Удалённая папка недоступна");
        return;
      }
      targetDir = currentRemotePath;
    }

    doPaste(targetDir);
  }

  private void performPaste(List<FileEntry> srcEntries, String targetDirPath, FileClipboard.Op op) {
    int total = srcEntries.size();
    if (total == 0) {
      toast("Буфер пуст");
      return;
    }

    synchronized (this) {
      pasteTotal = total;
      pasteCompleted = 0;
      pasteSuccess = 0;
      pasteFailed = 0;
    }

    showProgressDialog(op == FileClipboard.Op.COPY ? "Копирование" : "Перемещение", "0 / " + total,
        null);

    pasteDoneCallback = () -> {
      if (!isUiAlive())
        return;
      dismissProgressDialog();
      FileClipboard.onPasteCompleted();
      reloadCurrent();
      toast("Готово: успешно " + pasteSuccess + ", ошибок " + pasteFailed);
      if (listAdapter != null)
        listAdapter.notifyDataSetChanged();
      invalidateOptionsMenu();
    };

    for (FileEntry src : srcEntries) {
      if (src.isRemote()) {
        if (currentSource != null && currentSource.isRemote() && sameSource(src, currentSource)) {
          if (op == FileClipboard.Op.COPY) {
            currentTransport.copy(src.getPath(), targetDirPath, new FileTransport.OpCallback() {
              @Override
              public void onSuccess() {
                onPasteOneDone(true);
              }

              @Override
              public void onError(FileTransportException e) {
                onPasteOneDone(false);
                log.logError("copy", e);
              }
            });
          } else {
            currentTransport.move(src.getPath(), targetDirPath, new FileTransport.OpCallback() {
              @Override
              public void onSuccess() {
                onPasteOneDone(true);
              }

              @Override
              public void onError(FileTransportException e) {
                onPasteOneDone(false);
                log.logError("move", e);
              }
            });
          }
        } else {
          toast("Между узлами не поддерживается");
          onPasteOneDone(false);
        }
      } else {
        File srcFile = new File(src.getPath());
        if (currentSource != null && currentSource.isLocal()) {
          File dstDir = new File(targetDirPath);
          File dstFile = new File(dstDir, srcFile.getName());
          boolean ok = pasteLocalToLocal(srcFile, dstFile, op == FileClipboard.Op.MOVE);
          onPasteOneDone(ok);
        } else {
          if (currentTransport != null) {
            final File fSrc = srcFile;
            final FileClipboard.Op fOp = op;
            currentTransport.upload(srcFile, targetDirPath, srcFile.getName(),
                FileTransport.ConflictPolicy.RENAME, new FileTransport.TransferCallback() {
                  @Override
                  public void onStart(long total) {
                  }

                  @Override
                  public void onProgress(long d, long t) {
                  }

                  @Override
                  public void onSuccess(File destFile) {
                    if (fOp == FileClipboard.Op.MOVE) {
                      deleteRecursive(fSrc);
                    }
                    onPasteOneDone(true);
                  }

                  @Override
                  public void onError(FileTransportException e) {
                    onPasteOneDone(false);
                    log.logError("upload during paste", e);
                  }

                  @Override
                  public void onCancelled() {
                    onPasteOneDone(false);
                  }
                });
          } else {
            onPasteOneDone(false);
          }
        }
      }
    }
  }

  private void onPasteOneDone(boolean success) {
    synchronized (this) {
      pasteCompleted++;
      if (success)
        pasteSuccess++;
      else
        pasteFailed++;

      if (dialogMessage != null) {
        dialogMessage.setText(pasteCompleted + " / " + pasteTotal);
      }

      if (pasteCompleted >= pasteTotal) {
        if (pasteDoneCallback != null) {
          Runnable cb = pasteDoneCallback;
          pasteDoneCallback = null;
          runOnUiThread(() -> {
            if (isUiAlive())
              cb.run();
          });
        }
      }
    }
  }

  private boolean pasteLocalToLocal(File src, File dst, boolean move) {
    try {
      if (dst.exists()) {
        String name = dst.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int i = 1;
        do {
          dst = new File(dst.getParentFile(), base + " (" + i + ")" + ext);
          i++;
        } while (dst.exists() && i < 1000);
      }
      if (src.isDirectory()) {
        if (!copyRecursiveLocal(src, dst))
          return false;
        if (move)
          deleteRecursive(src);
        return true;
      }
      try (InputStream in = new BufferedInputStream(new FileInputStream(src));
          OutputStream out = new BufferedOutputStream(new FileOutputStream(dst))) {
        byte[] buf = new byte[64 * 1024];
        int r;
        while ((r = in.read(buf)) != -1)
          out.write(buf, 0, r);
        out.flush();
      }
      if (move) {
        src.delete();
      }
      return true;
    } catch (IOException e) {
      log.logError("pasteLocalToLocal", e);
      return false;
    }
  }

  private static boolean copyRecursiveLocal(File src, File dst) {
    if (src.isDirectory()) {
      if (!dst.exists() && !dst.mkdirs())
        return false;
      File[] kids = src.listFiles();
      if (kids != null) {
        for (File k : kids) {
          if (!copyRecursiveLocal(k, new File(dst, k.getName())))
            return false;
        }
      }
      return true;
    }
    try (InputStream in = new BufferedInputStream(new FileInputStream(src));
        OutputStream out = new BufferedOutputStream(new FileOutputStream(dst))) {
      byte[] buf = new byte[64 * 1024];
      int r;
      while ((r = in.read(buf)) != -1)
        out.write(buf, 0, r);
      out.flush();
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  private static boolean sameSource(FileEntry a, FileManagerSource s) {
    if (a == null || s == null)
      return false;
    if (!s.isRemote())
      return false;
    String aSrc = a.getSourceId();
    if (aSrc == null)
      return false;
    if (s.node != null) {
      return aSrc.equals(s.node.getKey());
    }
    return false;
  }

  private boolean isAppInternalSource() {
    if (currentSource == null)
      return false;
    if (!currentSource.isLocal())
      return false;
    String root = getFilesDir().getAbsolutePath();
    String cur = currentSource.rootPath;
    return cur != null && (cur.equals(root) || cur.startsWith(root + File.separator));
  }

  // ============================================================
  // Создание папок / файлов
  // ============================================================

  private void askCreateFolder() {
    if (!isUiAlive())
      return;
    EditText input = new EditText(this);
    input.setHint("Имя папки");
    input.setInputType(InputType.TYPE_CLASS_TEXT);
    input.setTextColor(0xFFFFFFFF);

    new AlertDialog.Builder(this).setTitle("Создать папку").setView(input)
        .setPositiveButton("Создать", (d, w) -> {
          String n = input.getText().toString().trim();
          if (TextUtils.isEmpty(n)) {
            toast("Введите имя");
            return;
          }
          doCreateFolder(n);
        }).setNegativeButton("Отмена", null).show();
  }

  private void doCreateFolder(String name) {
    if (!isUiAlive())
      return;
    if (currentSource.isLocal()) {
      File dir = new File(currentLocalDir, name);
      if (dir.exists()) {
        toast("Уже существует");
        return;
      }
      if (dir.mkdirs()) {
        toast("✅ Создано");
        reloadCurrent();
      } else
        toast("❌ Ошибка создания");
    } else {
      currentTransport.mkdir(currentRemotePath, name, new FileTransport.OpCallback() {
        @Override
        public void onSuccess() {
          if (!isUiAlive())
            return;
          toast("✅ Создано");
          reloadCurrent();
        }

        @Override
        public void onError(FileTransportException e) {
          if (!isUiAlive())
            return;
          toast(humanizeError(e));
        }
      });
    }
  }

  private void askCreateTextFile() {
    if (!isUiAlive())
      return;
    if (currentSource == null || !currentSource.isLocal()) {
      toast("Создание документа доступно только для локальных папок");
      return;
    }
    if (currentLocalDir == null || !currentLocalDir.isDirectory()) {
      toast("Папка недоступна");
      return;
    }

    EditText input = new EditText(this);
    input.setHint("Имя файла, например: заметка.txt");
    input.setInputType(InputType.TYPE_CLASS_TEXT);
    input.setText("новый документ.txt");
    input.setSelection(0, input.getText().length());
    input.setTextColor(0xFFFFFFFF);

    new AlertDialog.Builder(this).setTitle("Создать текстовый документ").setView(input)
        .setPositiveButton("Создать и открыть", (d, w) -> {
          String n = input.getText().toString().trim();
          if (TextUtils.isEmpty(n)) {
            toast("Введите имя");
            return;
          }
          doCreateTextFile(n);
        }).setNegativeButton("Отмена", null).show();
  }

  private void doCreateTextFile(String rawName) {
    if (!isUiAlive())
      return;

    String name = rawName;

    if (name.contains("/") || name.contains("\\")) {
      toast("Имя не должно содержать / или \\");
      return;
    }

    if (!name.contains(".")) {
      name = name + ".txt";
    }

    File target = new File(currentLocalDir, name);
    if (target.exists()) {
      String base = name;
      String ext = "";
      int dot = name.lastIndexOf('.');
      if (dot > 0) {
        base = name.substring(0, dot);
        ext = name.substring(dot);
      }
      int i = 1;
      File candidate;
      do {
        candidate = new File(currentLocalDir, base + " (" + i + ")" + ext);
        i++;
      } while (candidate.exists() && i < 1000);
      target = candidate;
      name = target.getName();
    }

    try {
      boolean created = target.createNewFile();
      if (!created && !target.exists()) {
        toast("❌ Не удалось создать файл");
        return;
      }
      log.log("Создан текстовый документ: " + target.getAbsolutePath());

      reloadCurrent();
      MAS_TextEditor.createNew(this, target);

    } catch (IOException e) {
      log.logError("doCreateTextFile", e);
      toast("❌ Ошибка: " + e.getMessage());
    }
  }

  // ============================================================
  // Переименование / удаление
  // ============================================================

  private void askRename(FileEntry entry) {
    if (!isUiAlive())
      return;
    EditText input = new EditText(this);
    input.setText(entry.getName());
    int dot = entry.getName().lastIndexOf('.');
    input.setSelection(0, dot > 0 ? dot : entry.getName().length());
    input.setTextColor(0xFFFFFFFF);

    new AlertDialog.Builder(this).setTitle("Переименовать").setView(input)
        .setPositiveButton("ОК", (d, w) -> {
          String n = input.getText().toString().trim();
          if (TextUtils.isEmpty(n) || n.equals(entry.getName()))
            return;
          doRename(entry, n);
        }).setNegativeButton("Отмена", null).show();
  }

  private void doRename(FileEntry entry, String newName) {
    if (!isUiAlive())
      return;
    if (entry.isRemote()) {
      currentTransport.rename(entry.getPath(), newName, new FileTransport.OpCallback() {
        @Override
        public void onSuccess() {
          if (!isUiAlive())
            return;
          toast("✅ Переименовано");
          reloadCurrent();
        }

        @Override
        public void onError(FileTransportException e) {
          if (!isUiAlive())
            return;
          toast(humanizeError(e));
        }
      });
    } else {
      File src = new File(entry.getPath());
      File dst = new File(src.getParentFile(), newName);
      if (dst.exists()) {
        toast("Уже существует");
        return;
      }
      if (src.renameTo(dst)) {
        toast("✅ Переименовано");
        reloadCurrent();
      } else
        toast("❌ Ошибка");
    }
  }

  private void confirmDelete(FileEntry entry) {
    if (!isUiAlive())
      return;
    if (entry.isRemote()) {
      new AlertDialog.Builder(this).setTitle("Удалить на узле?")
          .setMessage(entry.getName() + "\n\nКорзины на узле нет — файл будет удалён безвозвратно.")
          .setPositiveButton("Удалить", (d, w) -> doDeleteRemote(entry))
          .setNegativeButton("Отмена", null).show();
      return;
    }
    new AlertDialog.Builder(this).setTitle("Удалить?").setMessage(entry.getName())
        .setPositiveButton("🗑 В корзину", (d, w) -> doTrash(entry))
        .setNeutralButton("Удалить навсегда", (d, w) -> doDeleteLocal(entry))
        .setNegativeButton("Отмена", null).show();
  }

  private void doTrash(FileEntry entry) {
    if (!(entry instanceof LocalFileEntry))
      return;
    File f = ((LocalFileEntry) entry).getFile();
    if (trashManager.moveToTrash(f)) {
      toast("🗑 В корзине: " + entry.getName());
      reloadCurrent();
    } else {
      toast("❌ Не удалось");
    }
  }

  private void doDeleteLocal(FileEntry entry) {
    if (deleteRecursive(new File(entry.getPath()))) {
      toast("✅ Удалено");
      reloadCurrent();
    } else
      toast("❌ Ошибка");
  }

  private void doDeleteRemote(FileEntry entry) {
    if (currentTransport == null || !isUiAlive())
      return;
    currentTransport.delete(entry.getPath(), entry.isDirectory(), new FileTransport.OpCallback() {
      @Override
      public void onSuccess() {
        if (!isUiAlive())
          return;
        toast("✅ Удалено");
        reloadCurrent();
      }

      @Override
      public void onError(FileTransportException e) {
        if (!isUiAlive())
          return;
        toast(humanizeError(e));
      }
    });
  }

  private void doDelete(FileEntry entry) {
    if (entry.isRemote())
      doDeleteRemote(entry);
    else
      doTrash(entry);
  }

  private static boolean deleteRecursive(File f) {
    if (f == null)
      return false;
    if (f.isDirectory()) {
      File[] kids = f.listFiles();
      if (kids != null)
        for (File k : kids)
          deleteRecursive(k);
    }
    return f.delete();
  }

  // ============================================================
  // Перемещение
  // ============================================================

  private void askMove(FileEntry entry) {
    if (!isUiAlive())
      return;
    EditText input = new EditText(this);
    input.setHint("Путь папки назначения");
    input.setTextColor(0xFFFFFFFF);

    new AlertDialog.Builder(this).setTitle("Переместить в…").setView(input)
        .setPositiveButton("ОК", (d, w) -> {
          String dst = input.getText().toString().trim();
          if (TextUtils.isEmpty(dst))
            return;
          doMove(entry, dst);
        }).setNegativeButton("Отмена", null).show();
  }

  private void doMove(FileEntry entry, String dstDirPath) {
    if (!isUiAlive())
      return;
    if (entry.isRemote()) {
      currentTransport.move(entry.getPath(), dstDirPath, new FileTransport.OpCallback() {
        @Override
        public void onSuccess() {
          if (!isUiAlive())
            return;
          toast("✅ Перемещено");
          reloadCurrent();
        }

        @Override
        public void onError(FileTransportException e) {
          if (!isUiAlive())
            return;
          toast(humanizeError(e));
        }
      });
    } else {
      File src = new File(entry.getPath());
      File dst = new File(new File(dstDirPath), src.getName());
      if (dst.exists()) {
        toast("Уже существует");
        return;
      }
      if (src.renameTo(dst)) {
        toast("✅ Перемещено");
        reloadCurrent();
      } else
        toast("❌ Ошибка");
    }
  }

  // ============================================================
  // Скачивание / копирование
  // ============================================================

  private void askDownloadRemote(FileEntry entry) {
    if (!isUiAlive())
      return;
    File downloads = getExternalMealseDir();
    if (downloads == null) {
      toast("Внешнее хранилище недоступно");
      return;
    }
    File dest = new File(downloads, entry.getName());
    if (dest.exists()) {
      new AlertDialog.Builder(this).setTitle("Файл существует").setMessage("Перезаписать?")
          .setPositiveButton("Перезаписать", (d, w) -> doDownload(entry, dest))
          .setNegativeButton("Отмена", null).show();
    } else
      doDownload(entry, dest);
  }

  private void askDownloadRemoteToApp(FileEntry entry) {
    if (!isUiAlive())
      return;
    FileTypeHelper.Category cat = FileTypeHelper.getCategoryByFileName(entry.getName());
    String folder = FileTypeHelper.getCategoryFolder(cat);
    File dir = new File(getFilesDir(), folder);
    if (!dir.exists())
      dir.mkdirs();
    File dest = new File(dir, entry.getName());

    if (dest.exists()) {
      new AlertDialog.Builder(this).setTitle("Файл существует")
          .setMessage(
              "Категория: " + FileTypeHelper.getCategoryDisplayName(cat) + "\nПерезаписать?")
          .setPositiveButton("Перезаписать", (d, w) -> doDownload(entry, dest))
          .setNegativeButton("Отмена", null).show();
    } else
      doDownload(entry, dest);
  }

  private void doDownload(FileEntry entry, File dest) {
    if (!isUiAlive())
      return;
    showProgressDialog("Скачивание", entry.getName(), new Runnable() {
      @Override
      public void run() {
        if (currentTransport != null)
          currentTransport.cancel();
      }
    });

    currentTransport.download(entry.getPath(), dest, new FileTransport.TransferCallback() {
      @Override
      public void onStart(long total) {
      }

      @Override
      public void onProgress(long done, long total) {
        if (!isUiAlive())
          return;
        updateProgressDialog(done, total);
      }

      @Override
      public void onSuccess(File destFile) {
        if (!isUiAlive())
          return;
        dismissProgressDialog();
        toast("✅ Скачано: " + destFile.getName());
        if (isAppInternalSource())
          reloadCurrent();
      }

      @Override
      public void onError(FileTransportException e) {
        if (!isUiAlive())
          return;
        dismissProgressDialog();
        toast(humanizeError(e));
      }

      @Override
      public void onCancelled() {
        if (!isUiAlive())
          return;
        dismissProgressDialog();
        toast("Отменено");
      }
    });
  }

  private void askCopyToApp(FileEntry entry) {
    if (!isUiAlive())
      return;
    File src = new File(entry.getPath());
    if (!src.exists() || !src.isFile()) {
      toast("Не файл");
      return;
    }
    FileTypeHelper.Category cat = FileTypeHelper.getCategoryByFileName(src.getName());
    File dir = new File(getFilesDir(), FileTypeHelper.getCategoryFolder(cat));
    if (!dir.exists())
      dir.mkdirs();
    File dst = new File(dir, src.getName());
    if (dst.exists()) {
      new AlertDialog.Builder(this).setTitle("Файл уже есть").setMessage("Перезаписать?")
          .setPositiveButton("Перезаписать", (d, w) -> copyLocalFile(src, dst, true))
          .setNegativeButton("Отмена", null).show();
    } else
      copyLocalFile(src, dst, false);
  }

  private void askCopyToExternal(FileEntry entry) {
    if (!isUiAlive())
      return;
    File src = new File(entry.getPath());
    if (!src.exists() || !src.isFile()) {
      toast("Не файл");
      return;
    }
    File downloads = getExternalMealseDir();
    if (downloads == null) {
      toast("Внешнее хранилище недоступно");
      return;
    }
    File dst = new File(downloads, src.getName());
    if (dst.exists()) {
      new AlertDialog.Builder(this).setTitle("Файл уже есть").setMessage("Перезаписать?")
          .setPositiveButton("Перезаписать", (d, w) -> copyLocalFile(src, dst, true))
          .setNegativeButton("Отмена", null).show();
    } else
      copyLocalFile(src, dst, false);
  }

  private void copyLocalFile(File src, File dst, boolean overwrite) {
    if (!isUiAlive())
      return;
    showProgressDialog("Копирование", src.getName(), null);

    new Thread(() -> {
      InputStream in = null;
      OutputStream out = null;
      try {
        if (overwrite && dst.exists())
          dst.delete();
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists())
          parent.mkdirs();

        long total = src.length();
        long done = 0;
        in = new BufferedInputStream(new FileInputStream(src), 64 * 1024);
        out = new BufferedOutputStream(new FileOutputStream(dst), 64 * 1024);

        byte[] buf = new byte[64 * 1024];
        int r;
        long lastReport = 0;
        while ((r = in.read(buf)) != -1) {
          out.write(buf, 0, r);
          done += r;
          long now = System.currentTimeMillis();
          if (now - lastReport > 100 && total > 0) {
            final long fdone = done, ftotal = total;
            runOnUiThread(() -> {
              if (isUiAlive())
                updateProgressDialog(fdone, ftotal);
            });
            lastReport = now;
          }
        }
        out.flush();

        final File fdst = dst;
        runOnUiThread(() -> {
          if (!isUiAlive())
            return;
          dismissProgressDialog();
          toast("✅ Скопировано: " + fdst.getName());
          reloadCurrent();
        });
      } catch (IOException e) {
        log.logError("copyLocalFile", e);
        runOnUiThread(() -> {
          if (!isUiAlive())
            return;
          dismissProgressDialog();
          toast("❌ Ошибка: " + e.getMessage());
        });
      } finally {
        closeQuietly(in);
        closeQuietly(out);
      }
    }, "FileManagerCopy").start();
  }

  private File getExternalMealseDir() {
    File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
    if (downloads == null)
      return null;
    File mealse = new File(downloads, EXTERNAL_SUBDIR);
    if (!mealse.exists())
      mealse.mkdirs();
    return mealse;
  }

  // ============================================================
  // Загрузка на узел
  // ============================================================

  private void askUploadToNode(FileEntry entry) {
    if (!isUiAlive())
      return;
    List<FileManagerSource> remotes = new ArrayList<>();
    for (FileManagerSource s : sources)
      if (s.isRemote())
        remotes.add(s);
    if (remotes.isEmpty()) {
      toast("Нет подключённых узлов");
      return;
    }

    String[] names = new String[remotes.size()];
    for (int i = 0; i < remotes.size(); i++)
      names[i] = remotes.get(i).displayName;

    new AlertDialog.Builder(this).setTitle("Загрузить на узел").setItems(names, (d, w) -> {
      FileManagerSource target = remotes.get(w);
      doUploadToRemote(entry, target, "/" + APP_SUBDIR_DEFAULT);
    }).setNegativeButton("Отмена", null).show();
  }

  private void askUploadToDir(FileEntry remoteDir) {
    if (!isUiAlive())
      return;
    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
    intent.setType("*/*");
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    try {
      startActivityForResult(Intent.createChooser(intent, "Выберите файл"), REQ_PICK_LOCAL_FILE);
    } catch (Exception e) {
      toast("Не удалось открыть проводник");
    }
  }

  private void doUploadToRemote(FileEntry localFile, FileManagerSource target, String remoteDir) {
    if (!isUiAlive())
      return;
    try {
      FileTransport t = transportFactory.forNode(target.node);
      File src = new File(localFile.getPath());

      showProgressDialog("Загрузка", src.getName() + " → " + target.displayName, null);

      t.upload(src, remoteDir, src.getName(), FileTransport.ConflictPolicy.RENAME,
          new FileTransport.TransferCallback() {
            @Override
            public void onStart(long total) {
            }

            @Override
            public void onProgress(long done, long total) {
              if (!isUiAlive())
                return;
              updateProgressDialog(done, total);
            }

            @Override
            public void onSuccess(File destFile) {
              if (!isUiAlive())
                return;
              dismissProgressDialog();
              toast("✅ Загружено на " + target.displayName);
              if (currentSource != null && currentSource.equals(target))
                reloadCurrent();
            }

            @Override
            public void onError(FileTransportException e) {
              if (!isUiAlive())
                return;
              dismissProgressDialog();
              toast(humanizeError(e));
            }

            @Override
            public void onCancelled() {
              if (!isUiAlive())
                return;
              dismissProgressDialog();
            }
          });
    } catch (FileTransportException e) {
      if (!isUiAlive())
        return;
      toast(humanizeError(e));
    }
  }

  // ============================================================
  // Открытие / шаринг
  // ============================================================

  private void openLocal(FileEntry entry) {
    if (!isUiAlive())
      return;
    File f = new File(entry.getPath());
    if (openWithInternalViewer(f, entry.getMimeType()))
      return;
    try {
      Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
      Intent i = new Intent(Intent.ACTION_VIEW);
      i.setDataAndType(uri, entry.getMimeType());
      i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      startActivity(i);
    } catch (Exception e) {
      toast("Не удалось открыть: " + e.getMessage());
    }
  }

  private void openRemote(FileEntry entry) {
    if (!isUiAlive())
      return;
    File cacheDir = new File(getCacheDir(), "remote_open");
    if (!cacheDir.exists())
      cacheDir.mkdirs();
    File dest = new File(cacheDir, entry.getName());

    showProgressDialog("Подготовка", entry.getName(), null);

    currentTransport.download(entry.getPath(), dest, new FileTransport.TransferCallback() {
      @Override
      public void onStart(long total) {
      }

      @Override
      public void onProgress(long done, long total) {
        if (!isUiAlive())
          return;
        updateProgressDialog(done, total);
      }

      @Override
      public void onSuccess(File destFile) {
        if (!isUiAlive())
          return;
        dismissProgressDialog();
        if (openWithInternalViewer(destFile, entry.getMimeType()))
          return;
        try {
          Uri uri = FileProvider.getUriForFile(FileManagerActivity.this,
              getPackageName() + ".fileprovider", destFile);
          Intent i = new Intent(Intent.ACTION_VIEW);
          i.setDataAndType(uri, entry.getMimeType());
          i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
          startActivity(i);
        } catch (Exception e) {
          toast("Не удалось открыть: " + e.getMessage());
        }
      }

      @Override
      public void onError(FileTransportException e) {
        if (!isUiAlive())
          return;
        dismissProgressDialog();
        toast(humanizeError(e));
      }

      @Override
      public void onCancelled() {
        if (!isUiAlive())
          return;
        dismissProgressDialog();
      }
    });
  }

  private boolean openWithInternalViewer(File file, String mime) {
    if (file == null || !file.exists())
      return false;
    String lower = file.getName().toLowerCase();

    if ((mime != null && mime.startsWith("image/")) || lower.endsWith(".jpg")
        || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".gif")
        || lower.endsWith(".webp") || lower.endsWith(".bmp")) {
      ArrayList<String> images = new ArrayList<>();
      int startPos = 0;
      File parent = file.getParentFile();
      if (parent != null && parent.isDirectory()) {
        File[] children = parent.listFiles();
        if (children != null) {
          for (File c : children) {
            if (!c.isFile())
              continue;
            String l = c.getName().toLowerCase();
            if (l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png")
                || l.endsWith(".gif") || l.endsWith(".webp") || l.endsWith(".bmp")) {
              if (c.getAbsolutePath().equals(file.getAbsolutePath()))
                startPos = images.size();
              images.add(c.getAbsolutePath());
            }
          }
        }
      }
      if (images.isEmpty())
        images.add(file.getAbsolutePath());
      ImagePreviewActivity.start(this, images, startPos);
      return true;
    }

    if ((mime != null && mime.startsWith("video/")) || lower.endsWith(".mp4")
        || lower.endsWith(".mkv") || lower.endsWith(".avi") || lower.endsWith(".mov")
        || lower.endsWith(".webm") || lower.endsWith(".3gp") || lower.endsWith(".m4v")) {
      ArrayList<String> videos = new ArrayList<>();
      int startPos = 0;
      File parent = file.getParentFile();
      if (parent != null && parent.isDirectory()) {
        File[] children = parent.listFiles();
        if (children != null) {
          for (File c : children) {
            if (!c.isFile())
              continue;
            String l = c.getName().toLowerCase();
            if (l.endsWith(".mp4") || l.endsWith(".mkv") || l.endsWith(".avi") || l.endsWith(".mov")
                || l.endsWith(".webm") || l.endsWith(".3gp") || l.endsWith(".m4v")) {
              if (c.getAbsolutePath().equals(file.getAbsolutePath()))
                startPos = videos.size();
              videos.add(c.getAbsolutePath());
            }
          }
        }
      }
      if (videos.isEmpty())
        videos.add(file.getAbsolutePath());
      MAS_VideoPlayerActivity.start(this, videos, startPos);
      return true;
    }

    if ((mime != null && (mime.startsWith("text/") || mime.equals("application/json")
        || mime.equals("application/xml"))) || lower.endsWith(".txt") || lower.endsWith(".json")
        || lower.endsWith(".xml") || lower.endsWith(".md") || lower.endsWith(".log")
        || lower.endsWith(".csv")) {
      MAS_TextEditor.open(this, file);
      return true;
    }
    return false;
  }

  private void shareLocal(FileEntry entry) {
    if (!isUiAlive())
      return;
    try {
      File f = new File(entry.getPath());
      Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
      Intent i = new Intent(Intent.ACTION_SEND);
      i.setType(entry.getMimeType());
      i.putExtra(Intent.EXTRA_STREAM, uri);
      i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      startActivity(Intent.createChooser(i, "Поделиться"));
    } catch (Exception e) {
      toast("Ошибка: " + e.getMessage());
    }
  }

  // ============================================================
  // Multi-select
  // ============================================================

  private void startMultiSelect(int position) {
    if (!isUiAlive())
      return;
    FileEntry first = listAdapter.getItem(position);
    listAdapter.setMultiSelectMode(true);
    listAdapter.setSelection(first.getPath(), true);
    if (actionMode == null)
      actionMode = startActionMode(new MultiActionMode());
    updateActionModeTitle();
  }

  private void updateActionModeTitle() {
    if (!isUiAlive())
      return;
    if (actionMode != null && listAdapter != null)
      actionMode.setTitle("Выбрано: " + listAdapter.getSelectedCount());
  }

  private class MultiActionMode implements ActionMode.Callback {
    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
      mode.getMenuInflater().inflate(R.menu.menu_file_manager_multi, menu);
      return true;
    }

    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
      MenuItem paste = menu.findItem(R.id.fm_multi_paste);
      if (paste != null)
        paste.setVisible(!FileClipboard.isEmpty());

      MenuItem download = menu.findItem(R.id.fm_multi_download);
      if (download != null) {
        boolean isRemote = currentSource != null && currentSource.isRemote();
        download.setVisible(isRemote);
      }
      return true;
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
      if (!isUiAlive())
        return false;
      int id = item.getItemId();

      if (id == R.id.fm_multi_select_all) {
        listAdapter.selectAll();
        updateActionModeTitle();
        return true;
      }
      if (id == R.id.fm_multi_download) {
        askMultiDownload();
        return true;
      }
      if (id == R.id.fm_multi_copy) {
        List<FileEntry> selected = listAdapter.getSelectedEntries();
        if (selected.isEmpty()) {
          toast("Не выбрано");
          return true;
        }
        FileClipboard.put(selected, FileClipboard.Op.COPY);
        toast("📋 Скопировано в буфер: " + selected.size());
        invalidateOptionsMenu();
        mode.finish();
        return true;
      }
      if (id == R.id.fm_multi_move) {
        List<FileEntry> selected = listAdapter.getSelectedEntries();
        if (selected.isEmpty()) {
          toast("Не выбрано");
          return true;
        }
        FileClipboard.put(selected, FileClipboard.Op.MOVE);
        toast("✂ Вырезано в буфер: " + selected.size());
        invalidateOptionsMenu();
        mode.finish();
        return true;
      }
      if (id == R.id.fm_multi_paste) {
        String target = null;
        if (currentSource != null) {
          if (currentSource.isLocal()) {
            target = currentLocalDir != null ? currentLocalDir.getAbsolutePath() : null;
          } else {
            target = currentRemotePath;
          }
        }
        if (target == null) {
          toast("Не удалось определить целевую папку");
          return true;
        }
        doPaste(target);
        mode.finish();
        return true;
      }
      if (id == R.id.fm_multi_delete) {
        askMultiDelete();
        return true;
      }
      return false;
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
      if (listAdapter != null) {
        listAdapter.setMultiSelectMode(false);
        listAdapter.clearSelection();
      }
      actionMode = null;
    }
  }

  private void askMultiDelete() {
    if (!isUiAlive())
      return;
    List<FileEntry> selected = listAdapter.getSelectedEntries();
    if (selected.isEmpty()) {
      toast("Не выбрано");
      return;
    }
    boolean hasRemote = false, hasLocal = false;
    for (FileEntry e : selected) {
      if (e.isRemote())
        hasRemote = true;
      else
        hasLocal = true;
    }
    StringBuilder msg = new StringBuilder();
    msg.append("Файлов/папок: ").append(selected.size()).append("\n\n");
    if (hasLocal)
      msg.append("• Локальные → в корзину\n");
    if (hasRemote)
      msg.append("• Удалённые → удалить навсегда");

    new AlertDialog.Builder(this).setTitle("Удалить выбранные?").setMessage(msg.toString())
        .setPositiveButton("Удалить", (d, w) -> {
          for (FileEntry e : selected)
            doDelete(e);
          if (actionMode != null)
            actionMode.finish();
        }).setNegativeButton("Отмена", null).show();
  }

  private void askMultiDownload() {
    if (!isUiAlive())
      return;
    List<FileEntry> selected = listAdapter.getSelectedEntries();
    if (selected.isEmpty()) {
      toast("Не выбрано");
      return;
    }
    if (currentSource == null || currentSource.isLocal()) {
      toast("Скачивание только для удалённых файлов");
      return;
    }
    File downloads = getExternalMealseDir();
    if (downloads == null) {
      toast("Внешнее хранилище недоступно");
      return;
    }
    int n = 0;
    for (FileEntry e : selected) {
      if (e.isDirectory())
        continue;
      doDownload(e, new File(downloads, e.getName()));
      n++;
    }
    if (actionMode != null)
      actionMode.finish();
    if (n > 0)
      toast("Запущено скачивание: " + n);
  }

  // ============================================================
  // Поиск
  // ============================================================

  private void askSearch() {
    if (!isUiAlive())
      return;
    EditText input = new EditText(this);
    input.setHint("Что искать?");
    input.setTextColor(0xFFFFFFFF);

    new AlertDialog.Builder(this).setTitle("Поиск").setView(input)
        .setPositiveButton("Найти", (d, w) -> {
          String q = input.getText().toString().trim();
          if (!TextUtils.isEmpty(q))
            doSearch(q);
        }).setNegativeButton("Отмена", null).show();
  }

  private void doSearch(String query) {
    if (!isUiAlive())
      return;
    if (currentSource.isLocal()) {
      List<FileEntry> result = new ArrayList<>();
      searchRecursive(currentLocalDir, query.toLowerCase(), result, 500);
      sortEntries(result);
      listAdapter.replaceAll(result);
      updatePathView("🔍 " + query + " (" + result.size() + ")");
      updateEmptyView(result.isEmpty());
    } else {
      showLoading(true);
      final FileTransport requestTransport = currentTransport;
      final FileManagerSource requestSource = currentSource;
      currentTransport.search(currentRemotePath, query, new FileTransport.ListCallback() {
        @Override
        public void onResult(String path, List<FileEntry> entries) {
          if (!isUiAlive())
            return;
          if (requestTransport != currentTransport || requestSource != currentSource)
            return;
          showLoading(false);
          if (entries == null)
            entries = new ArrayList<>();
          sortEntries(entries);
          listAdapter.replaceAll(entries);
          updatePathView("🔍 " + query + " (" + entries.size() + ")");
          updateEmptyView(entries.isEmpty());
        }

        @Override
        public void onError(FileTransportException e) {
          if (!isUiAlive())
            return;
          if (requestTransport != currentTransport || requestSource != currentSource)
            return;
          showLoading(false);
          toast(humanizeError(e));
        }
      });
    }
  }

  private static void searchRecursive(File root, String q, List<FileEntry> out, int limit) {
    if (out.size() >= limit || root == null)
      return;
    File[] kids = root.listFiles();
    if (kids == null)
      return;
    for (File f : kids) {
      if (out.size() >= limit)
        return;
      if (f.getName().toLowerCase().contains(q))
        out.add(new LocalFileEntry(f));
      if (f.isDirectory())
        searchRecursive(f, q, out, limit);
    }
  }

  // ============================================================
  // onActivityResult
  // ============================================================

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == REQ_PICK_LOCAL_FILE && resultCode == RESULT_OK && data != null) {
      Uri uri = data.getData();
      if (uri == null)
        return;
      try {
        String name = queryDisplayName(uri);
        File tmp = new File(getCacheDir(), name);
        try (InputStream in = getContentResolver().openInputStream(uri);
            OutputStream out = new FileOutputStream(tmp)) {
          if (in == null)
            throw new IOException("null input");
          byte[] buf = new byte[64 * 1024];
          int r;
          while ((r = in.read(buf)) != -1)
            out.write(buf, 0, r);
        }
        FileEntry tmpEntry = new LocalFileEntry(tmp);
        String remoteDir = currentRemotePath != null ? currentRemotePath
            : ("/" + APP_SUBDIR_DEFAULT);
        if (currentSource != null && currentSource.isRemote()) {
          doUploadToRemote(tmpEntry, currentSource, remoteDir);
        }
      } catch (Exception e) {
        toast("Ошибка чтения файла: " + e.getMessage());
      }
    }
  }

  private String queryDisplayName(Uri uri) {
    String fallback = "upload_" + System.currentTimeMillis();
    try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
      if (c != null && c.moveToFirst()) {
        int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
        if (idx >= 0) {
          String name = c.getString(idx);
          if (!TextUtils.isEmpty(name))
            return name;
        }
      }
    } catch (Throwable ignored) {
    }
    return fallback;
  }

  // ============================================================
  // Прогресс-диалог
  // ============================================================

  private void showProgressDialog(String title, String message, Runnable onCancel) {
    if (!isUiAlive())
      return;
    if (progressDialog != null && progressDialog.isShowing())
      progressDialog.dismiss();

    View v = LayoutInflater.from(this).inflate(R.layout.dialog_progress, null);
    TextView tvTitle = v.findViewById(R.id.dp_title);
    TextView tvMsg = v.findViewById(R.id.dp_message);
    ProgressBar pb = v.findViewById(R.id.dp_progress);
    TextView tvPct = v.findViewById(R.id.dp_percent);

    tvTitle.setText(title != null ? title : "");
    tvMsg.setText(message != null ? message : "");
    pb.setProgress(0);
    tvPct.setText("0%");

    dialogProgressBar = pb;
    dialogMessage = tvMsg;
    dialogPercent = tvPct;

    progressDialog = new AlertDialog.Builder(this).setView(v).setCancelable(onCancel != null)
        .setNegativeButton(onCancel != null ? "Отмена" : null, (d, w) -> {
          if (onCancel != null)
            onCancel.run();
        }).create();
    progressDialog.show();
  }

  private void updateProgressDialog(long done, long total) {
    if (!isUiAlive())
      return;
    if (progressDialog == null || !progressDialog.isShowing())
      return;
    if (dialogProgressBar == null)
      return;
    if (total <= 0)
      return;
    int p = (int) Math.min(100, (done * 100) / total);
    dialogProgressBar.setProgress(p);
    if (dialogPercent != null)
      dialogPercent.setText(p + "%");
  }

  private void dismissProgressDialog() {
    if (progressDialog != null && progressDialog.isShowing()) {
      progressDialog.dismiss();
    }
    progressDialog = null;
    dialogProgressBar = null;
    dialogMessage = null;
    dialogPercent = null;
  }

  // ============================================================
  // Хелперы
  // ============================================================

  private void toast(String msg) {
    if (!isUiAlive())
      return;
    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
  }

  private String humanizeError(FileTransportException e) {
    if (e == null)
      return "Неизвестная ошибка";
    switch (e.getCode()) {
      case FileTransportException.CODE_NETWORK:
        return "Нет связи с узлом";
      case FileTransportException.CODE_NOT_FOUND:
        return "Файл или папка не найдены";
      case FileTransportException.CODE_ACCESS_DENIED:
        return "Узел не дал доступ к файлам";
      case FileTransportException.CODE_PATH_FORBIDDEN:
        return "Путь вне разрешённого хранилища";
      case FileTransportException.CODE_ALREADY_EXISTS:
        return "Уже существует";
      case FileTransportException.CODE_INVALID_REQUEST:
        return "Некорректный запрос";
      case FileTransportException.CODE_SERVER_ERROR:
        return "Ошибка на удалённом узле";
      case FileTransportException.CODE_CANCELLED:
        return "Отменено";
      case FileTransportException.CODE_NOT_CONNECTED:
        return "Wi-Fi Direct не подключён";
      case FileTransportException.CODE_PROTOCOL:
        return "Нарушение протокола";
      case FileTransportException.CODE_IO:
        return "Ошибка ввода-вывода";
      default:
        String m = e.getMessage();
        return m != null && !m.isEmpty() ? m : "Неизвестная ошибка";
    }
  }

  private static void closeQuietly(java.io.Closeable c) {
    if (c == null)
      return;
    try {
      c.close();
    } catch (Throwable ignored) {
    }
  }
}