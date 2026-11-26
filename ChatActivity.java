package com.my.mealse.messenger.ui;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.FileProvider;

import com.my.mealse.BaseActivity;
import com.my.mealse.R;
import com.my.mealse.messenger.MessengerEvents;
import com.my.mealse.messenger.model.AttachmentInfo;
import com.my.mealse.messenger.model.Conversation;
import com.my.mealse.messenger.model.ConversationType;
import com.my.mealse.messenger.model.Message;
import com.my.mealse.messenger.model.Participant;
import com.my.mealse.messenger.repo.ConversationRepository;
import com.my.mealse.messenger.repo.MessageRepository;
import com.my.mealse.messenger.repo.ParticipantRepository;
import com.my.mealse.messenger.storage.AttachmentStorage;
import com.my.mealse.messenger.transport.MessageCommands;
import com.my.mealse.util.BroadcastUtils;
import com.my.mealse.util.MealseLogger;
import com.my.mealse.util.SecureAuthManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.my.mealse.filemanager.ui.FileManagerActivity;

/**
 * Экран переписки.
 *
 * ★ reverse-layout: ListView.stackFromBottom = true, adapter хранит сообщения в порядке «новые сначала» (getItem(0) — новый, отображается внизу экрана).
 *
 * Жизненный цикл: 1. onCreate — загрузка Conversation, Participant, первая страница (50). 2. onResume — MessageCommands.markRead (сброс unread). 3. Broadcast MESSAGE_NEW / STATUS_CHANGED / PROGRESS — обновление. 4. Кнопка «Отправить» — MessageCommands.sendText → reload через 150мс.
 *
 * ★ Улучшение №3: ACTION_MESSAGE_PROGRESS → adapter.notifyDataSetChanged() (без перезапроса Room). MessageAdapter перечитывает progress_<msgId> из SharedPreferences и обновляет ProgressBar / «NN%». MESSAGE_NEW / STATUS_CHANGED → reloadFirstPage() (Room + адаптер).
 *
 * ★ Вложения: 📎 → «Файл / Приложение / Галерея / Фото с камеры / Видео с камеры». Все выбранные/снятые файлы → MessageCommands.sendFile → AttachmentStorage.saveFromFile → messenger/attachments/<convId>/ → outbox → DeliveryScheduler.
 */
public class ChatActivity extends BaseActivity {

  private static final String TAG = "MessengerChat";

  private static final String EXTRA_CONVERSATION_ID = "conversation_id";

  private static final int PAGE_SIZE = 50;

  private static final int REQ_PICK_APP_MANAGER = 5010;
  private static final int REQ_PICK_MEDIA = 5002;
  private static final int REQ_CAPTURE_PHOTO = 5003;
  private static final int REQ_CAPTURE_VIDEO = 5004;

  private MealseLogger log;

  private ImageButton btnAttach;
  private Uri pendingCameraUri;
  private File pendingCameraFile;

  // Views
  private ImageButton btnBack, btnInfo, btnSend;
  private TextView tvTitle, tvSubtitle, tvEmpty;
  private ListView listView;
  private ProgressBar loading;
  private EditText input;

  // Данные
  private String conversationId;
  private String selfFingerprint;
  private Conversation conversation;
  private MessageAdapter adapter;
  private ConversationRepository convRepo;
  private MessageRepository msgRepo;
  private ParticipantRepository partRepo;

  // Пагинация
  private int loadedOffset = 0;
  private boolean hasMore = true;
  private boolean loadingPage = false;

  private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "ChatActivity");
    t.setDaemon(true);
    return t;
  });
  private final Handler ui = new Handler(Looper.getMainLooper());

  // Broadcast
  private boolean receiverRegistered = false;

  /**
   * Приёмник событий мессенджера.
   *
   * ★ Улучшение №3: ACTION_MESSAGE_PROGRESS → adapter.notifyDataSetChanged() (дёшево, без перезапроса Room). Прогресс читается MessageAdapter'ом из SharedPreferences для каждого видимого FILE. MESSAGE_NEW / STATUS_CHANGED → reloadFirstPage() (полная перезагрузка).
   */
  private final BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      if (intent == null)
        return;
      String action = intent.getAction();
      if (action == null)
        return;

      String convId = intent.getStringExtra(MessengerEvents.EXTRA_CONVERSATION_ID);
      if (convId == null || !convId.equals(conversationId))
        return;

      // ★ Улучшение №3: прогресс — дёшево, notifyDataSetChanged
      if (MessengerEvents.ACTION_MESSAGE_PROGRESS.equals(action)) {
        ui.post(() -> {
          if (adapter != null) {
            adapter.notifyDataSetChanged();
          }
        });
        return;
      }

      // MESSAGE_NEW / MESSAGE_STATUS_CHANGED — полная перезагрузка
      if (MessengerEvents.ACTION_MESSAGE_NEW.equals(action)
          || MessengerEvents.ACTION_MESSAGE_STATUS_CHANGED.equals(action)) {
        reloadFirstPage();
      }
    }
  };

  // ============================================================
  // onActivityResult — все источники вложений
  // ============================================================

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);

    if (resultCode != RESULT_OK) {
      if (requestCode == REQ_CAPTURE_PHOTO || requestCode == REQ_CAPTURE_VIDEO) {
        // ★ MIUI/HyperOS иногда возвращают RESULT_CANCELED, хотя файл снят.
        // Если pendingCameraFile существует и непуст — обрабатываем как успех.
        if (pendingCameraFile != null && pendingCameraFile.exists()
            && pendingCameraFile.length() > 0) {
          log.log("onActivityResult: RESULT_CANCELED, но файл есть — обрабатываю");
          handleCapturedFile();
        } else {
          if (pendingCameraFile != null && pendingCameraFile.exists()) {
            pendingCameraFile.delete();
          }
          pendingCameraFile = null;
          pendingCameraUri = null;
        }
      }
      return;
    }

    // ★ Внутренний менеджер: мультивыбор (файлы + папки), fallback на одиночный путь.
    if (requestCode == REQ_PICK_APP_MANAGER && data != null) {
      ArrayList<String> paths = data
          .getStringArrayListExtra(FileManagerActivity.EXTRA_PICKED_PATHS);
      if (paths == null || paths.isEmpty()) {
        String p = data.getStringExtra(FileManagerActivity.EXTRA_PICKED_PATH);
        if (p != null && !p.isEmpty()) {
          paths = new ArrayList<>();
          paths.add(p);
        }
      }
      if (paths == null || paths.isEmpty()) {
        toast("Ничего не выбрано");
        return;
      }
      handlePickedPaths(paths);
      return;
    }

    switch (requestCode) {
      case REQ_PICK_MEDIA:
        if (data != null && data.getData() != null) {
          handlePickedUri(data.getData());
        }
        break;
      case REQ_CAPTURE_PHOTO:
      case REQ_CAPTURE_VIDEO:
        handleCapturedFile();
        break;
    }
  }

  /**
   * ★ НОВОЕ: обработка множественного выбора из внутреннего менеджера. Папки пакуются существующим AttachmentStorage.zipDirectory; файлы шлются как есть. Батч: «тихая» отправка каждого элемента, в конце — один тост и один reload.
   */
  private void handlePickedPaths(List<String> paths) {
    if (conversationId == null) {
      toast("Чат ещё не готов");
      return;
    }
    executor.execute(() -> {
      int sent = 0, failed = 0;
      for (String p : paths) {
        File f = new File(p);
        if (!f.exists()) {
          failed++;
          continue;
        }
        try {
          if (f.isDirectory()) {
            File zip = AttachmentStorage.zipDirectory(this, f);
            if (zip == null || zip.length() > MessageCommands.MAX_ATTACHMENT_BYTES) {
              if (zip != null)
                zip.delete();
              failed++;
              continue;
            }
            AttachmentInfo info = new AttachmentInfo(zip.getAbsolutePath(), f.getName() + ".zip",
                "application/zip", zip.length());
            // ★ deleteSrc=false: владелец очистки — этот код (см. ниже),
            // а не sendAttachmentCore. sendFile копирует СИНХРОННО, поэтому
            // удалять zip можно сразу после возврата.
            boolean ok = sendAttachmentCore(info, zip, false);
            if (ok)
              sent++;
            else
              failed++;
            // ★ Безусловное удаление: cache-guard внутри core мог не сработать,
            // если AttachmentStorage.zipDirectory кладёт zip вне getCacheDir().
            // Иначе временный архив остаётся навсегда → утечка места.
            if (zip.exists())
              zip.delete();
          } else {
            long size = f.length();
            if (size <= 0 || size > MessageCommands.MAX_ATTACHMENT_BYTES) {
              failed++;
              continue;
            }
            AttachmentInfo info = new AttachmentInfo(f.getAbsolutePath(), f.getName(),
                AttachmentInfo.guessMimeByName(f.getName()), size);
            if (sendAttachmentCore(info, f, false))
              sent++;
            else
              failed++;
          }
        } catch (Exception e) {
          log.logError("handlePickedPaths", e);
          failed++;
        }
      }
      final int fs = sent, ff = failed;
      ui.post(() -> {
        toast("📎 Отправлено: " + fs + (ff > 0 ? ", ошибок: " + ff : ""));
        reloadFirstPage();
      });
    });
  }

  /** «Тихая» отправка без тоста/reload — для батча. Возвращает успех. */
  /**
   * «Тихая» отправка без тоста/reload — для батча. Возвращает успех.
   *
   * ★ Guard: conversationId == null → ранний выход с логом (симметрия с CallActivity.sendAttachmentCore). MessageCommands.sendFile сам возвращает null при пустом conversationId, так что это не NPE-фикс, а явный лог причины и отсутствие лишней работы. ★ deleteSrc: удаляем src, только если он в getCacheDir() (для tmp галереи/камеры из одиночных путей). Для zip, собранных в батче, вызывающий код (handlePickedPaths) удаляет файл сам, безусловно.
   */
  private boolean sendAttachmentCore(AttachmentInfo info, File src, boolean deleteSrc) {
    if (conversationId == null) {
      log.warn("sendAttachmentCore: conversationId == null — пропуск");
      return false;
    }
    try {
      String id = MessageCommands.sendFile(this, conversationId, info);
      if (id == null)
        return false;
      if (deleteSrc && src != null && src.exists()
          && src.getAbsolutePath().startsWith(getCacheDir().getAbsolutePath())) {
        src.delete();
      }
      return true;
    } catch (Exception e) {
      log.logError("sendAttachmentCore", e);
      return false;
    }
  }

  // ============================================================
  // Обработчики выбранных/снятых файлов
  // ============================================================

  private void handlePickedUri(Uri uri) {
    executor.execute(() -> {
      try {
        String name = queryDisplayName(uri);
        String mime = getContentResolver().getType(uri);
        File tmp = new File(getCacheDir(),
            "attach_" + System.currentTimeMillis() + "_" + sanitize(name));
        try (InputStream in = getContentResolver().openInputStream(uri);
            OutputStream out = new FileOutputStream(tmp)) {
          if (in == null)
            throw new IOException("null input");
          byte[] buf = new byte[64 * 1024];
          int r;
          while ((r = in.read(buf)) != -1)
            out.write(buf, 0, r);
        }
        long size = tmp.length();
        if (size <= 0) {
          // noinspection ResultOfMethodCallIgnored
          tmp.delete();
          ui.post(() -> toast("Пустой файл"));
          return;
        }
        if (size > MessageCommands.MAX_ATTACHMENT_BYTES) {
          // noinspection ResultOfMethodCallIgnored
          tmp.delete();
          ui.post(() -> toast("Файл слишком большой (" + AttachmentInfo.formatSize(size) + " > "
              + AttachmentInfo.formatSize(MessageCommands.MAX_ATTACHMENT_BYTES) + ")"));
          return;
        }
        AttachmentInfo info = new AttachmentInfo(tmp.getAbsolutePath(), name, mime, size);
        sendAttachment(info, tmp, true);
      } catch (Exception e) {
        log.logError("handlePickedUri", e);
        ui.post(() -> toast("Ошибка: " + e.getMessage()));
      }
    });
  }

  private void handleCapturedFile() {
    File f = pendingCameraFile;
    pendingCameraFile = null;
    pendingCameraUri = null;
    if (f == null || !f.exists()) {
      toast("Файл не создан");
      return;
    }
    long size = f.length();
    if (size <= 0) {
      // noinspection ResultOfMethodCallIgnored
      f.delete();
      toast("Пустой файл");
      return;
    }
    if (size > MessageCommands.MAX_ATTACHMENT_BYTES) {
      // noinspection ResultOfMethodCallIgnored
      f.delete();
      toast("Файл слишком большой (" + AttachmentInfo.formatSize(size) + " > "
          + AttachmentInfo.formatSize(MessageCommands.MAX_ATTACHMENT_BYTES) + ")");
      return;
    }
    String mime = AttachmentInfo.guessMimeByName(f.getName());
    AttachmentInfo info = new AttachmentInfo(f.getAbsolutePath(), f.getName(), mime, size);
    // Файл уже в messenger/attachments/<convId>/ — не удалять после отправки
    sendAttachment(info, f, false);
  }

  /**
   * Отправка вложения через MessageCommands.sendFile.
   *
   * @param deleteSrcAfter удалять ли srcFile после отправки (true для tmp из cache).
   */
  private void sendAttachment(AttachmentInfo info, File srcFile, boolean deleteSrcAfter) {
    try {
      String messageId = MessageCommands.sendFile(this, conversationId, info);
      if (messageId == null) {
        ui.post(() -> toast("Не удалось отправить файл"));
        if (deleteSrcAfter && srcFile != null && srcFile.exists()) {
          // noinspection ResultOfMethodCallIgnored
          srcFile.delete();
        }
        return;
      }
      ui.post(() -> {
        toast("📎 Отправлено: " + info.getFileName());
        ui.postDelayed(this::reloadFirstPage, 150);
      });
      if (deleteSrcAfter && srcFile != null && srcFile.exists()
          && srcFile.getAbsolutePath().startsWith(getCacheDir().getAbsolutePath())) {
        // noinspection ResultOfMethodCallIgnored
        srcFile.delete();
      }
    } catch (Exception e) {
      log.logError("sendAttachment", e);
      ui.post(() -> toast("Ошибка: " + e.getMessage()));
    }
  }

  // ============================================================
  // Lifecycle
  // ============================================================

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    log = MealseLogger.get(this, TAG);

    try {
      conversationId = getIntent().getStringExtra(EXTRA_CONVERSATION_ID);
      if (conversationId == null || conversationId.isEmpty()) {
        log.warn("onCreate: нет conversation_id");
        Toast.makeText(this, "Диалог не указан", Toast.LENGTH_LONG).show();
        finish();
        return;
      }

      log.log("══ ChatActivity onCreate: conv=" + shortId(conversationId) + " ══");

      setContentView(R.layout.activity_chat);

      convRepo = new ConversationRepository(this);
      msgRepo = new MessageRepository(this);
      partRepo = new ParticipantRepository(this);

      try {
        selfFingerprint = new SecureAuthManager(this).getDeviceFingerprint();
        if (selfFingerprint == null)
          selfFingerprint = "";
      } catch (Exception e) {
        selfFingerprint = "";
      }

      initViews();
      setupListeners();
      registerReceiver();
      loadConversationAndFirstPage();

    } catch (Exception e) {
      log.logError("onCreate", e);
      Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
      finish();
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    try {
      MessageCommands.markRead(this, conversationId);
    } catch (Exception e) {
      log.logError("markRead", e);
    }
  }

  @Override
  protected void onDestroy() {
    unregisterReceiver();
    executor.shutdownNow();
    ui.removeCallbacksAndMessages(null);
    super.onDestroy();
  }

  private void initViews() {
    btnBack = findViewById(R.id.ch_btn_back);
    btnInfo = findViewById(R.id.ch_btn_info);
    btnSend = findViewById(R.id.ch_btn_send);
    tvTitle = findViewById(R.id.ch_title);
    tvSubtitle = findViewById(R.id.ch_subtitle);
    tvEmpty = findViewById(R.id.ch_empty);
    listView = findViewById(R.id.ch_list);
    loading = findViewById(R.id.ch_loading);
    input = findViewById(R.id.ch_input);
    btnAttach = findViewById(R.id.ch_btn_attach);

    checkViewNotNull(btnAttach, "ch_btn_attach");

    if (btnBack == null || btnSend == null || listView == null || tvTitle == null
        || input == null) {
      throw new IllegalStateException("activity_chat.xml: не найдены View");
    }

    adapter = new MessageAdapter(this, selfFingerprint);
    listView.setAdapter(adapter);
  }

  private void setupListeners() {
    btnBack.setOnClickListener(v -> finish());

    btnInfo.setOnClickListener(v -> showConversationInfo());

    btnSend.setOnClickListener(v -> onSendClicked());

    btnAttach.setOnClickListener(v -> showAttachDialog());

    input.setOnEditorActionListener((v, actionId, event) -> {
      if (actionId == EditorInfo.IME_ACTION_SEND) {
        onSendClicked();
        return true;
      }
      return false;
    });

    listView.setOnItemLongClickListener((parent, view, position, id) -> {
      Message m = adapter.getItem(position);
      if (m != null)
        showMessageActions(m);
      return true;
    });

    listView.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
      @Override
      public void onScrollStateChanged(android.widget.AbsListView view, int scrollState) {
      }

      @Override
      public void onScroll(android.widget.AbsListView view, int firstVisibleItem,
          int visibleItemCount, int totalItemCount) {
        if (firstVisibleItem == 0 && hasMore && !loadingPage) {
          loadNextPage();
        }
      }
    });
  }

  private void registerReceiver() {
    if (receiverRegistered)
      return;
    try {
      IntentFilter f = new IntentFilter();
      f.addAction(MessengerEvents.ACTION_MESSAGE_NEW);
      f.addAction(MessengerEvents.ACTION_MESSAGE_STATUS_CHANGED);
      f.addAction(MessengerEvents.ACTION_MESSAGE_PROGRESS); // ★ Улучшение №3
      BroadcastUtils.registerNotExported(this, refreshReceiver, f);
      receiverRegistered = true;
      log.log("registerReceiver: OK (NEW + STATUS + PROGRESS)");
    } catch (Exception e) {
      log.logError("registerReceiver", e);
    }
  }

  private void unregisterReceiver() {
    if (!receiverRegistered)
      return;
    try {
      BroadcastUtils.unregisterSafely(this, refreshReceiver);
    } catch (Exception ignored) {
    }
    receiverRegistered = false;
  }

  // ============================================================
  // Загрузка
  // ============================================================

  private void loadConversationAndFirstPage() {
    showLoading(true);
    executor.execute(() -> {
      try {
        Conversation conv = convRepo.getById(conversationId);
        List<Participant> participants = partRepo.getByConversation(conversationId);

        ui.post(() -> {
          showLoading(false);
          if (conv == null) {
            Toast.makeText(this, "Диалог не найден", Toast.LENGTH_LONG).show();
            finish();
            return;
          }
          conversation = conv;
          updateHeader(conv, participants);
        });

        reloadFirstPageSync();
      } catch (Exception e) {
        log.logError("loadConversationAndFirstPage", e);
        ui.post(() -> showLoading(false));
      }
    });
  }

  private void reloadFirstPage() {
    executor.execute(this::reloadFirstPageSync);
  }

  private void reloadFirstPageSync() {
    try {
      List<Message> list = msgRepo.getByConversation(conversationId, PAGE_SIZE, 0);
      // ★ Переворачиваем: старые сверху, новые снизу
      java.util.Collections.reverse(list);
      ui.post(() -> {
        loadedOffset = list.size();
        hasMore = list.size() >= PAGE_SIZE;
        adapter.replaceAll(list);
        updateEmpty(list.isEmpty());
        scrollToBottom();
      });
    } catch (Exception e) {
      log.logError("reloadFirstPageSync", e);
    }
  }

  private void loadNextPage() {
    loadingPage = true;
    final int offset = loadedOffset;
    executor.execute(() -> {
      try {
        List<Message> list = msgRepo.getByConversation(conversationId, PAGE_SIZE, offset);
        // ★ Переворачиваем страницу (она приходит новые-сначала)
        java.util.Collections.reverse(list);
        ui.post(() -> {
          loadingPage = false;
          if (list.isEmpty()) {
            hasMore = false;
            return;
          }
          loadedOffset += list.size();
          if (list.size() < PAGE_SIZE)
            hasMore = false;
          // ★ Добавляем старые В НАЧАЛО
          List<Message> current = adapter.getItems();
          current.addAll(0, list);
          adapter.replaceAll(current);
        });
      } catch (Exception e) {
        loadingPage = false;
        log.logError("loadNextPage", e);
      }
    });
  }

  private void updateHeader(Conversation conv, List<Participant> participants) {
    String title = conv.getDisplayTitle();
    tvTitle.setText(title);

    if (conv.getType() == ConversationType.GROUP && participants != null) {
      int count = participants.size();
      tvSubtitle.setText("Группа · " + count + " участник(ов)");
    } else if (conv.getType() == ConversationType.CALL) {
      tvSubtitle.setText("Чат звонка");
    } else {
      tvSubtitle.setText("");
    }
  }

  private void updateEmpty(boolean empty) {
    tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
    listView.setVisibility(empty ? View.GONE : View.VISIBLE);
  }

  private void showLoading(boolean show) {
    loading.setVisibility(show ? View.VISIBLE : View.GONE);
  }

  private void scrollToBottom() {
    if (listView == null || adapter == null)
      return;
    listView.post(() -> {
      try {
        int count = adapter.getCount();
        if (count > 0) {
          listView.setSelection(count - 1);
        }
      } catch (Exception ignored) {
      }
    });
  }

  // ============================================================
  // Отправка текста
  // ============================================================

  private void onSendClicked() {
    String text = input.getText().toString().trim();
    if (TextUtils.isEmpty(text))
      return;
    try {
      String messageId = MessageCommands.sendText(this, conversationId, text);
      if (messageId == null) {
        Toast.makeText(this, "Не удалось отправить", Toast.LENGTH_SHORT).show();
        return;
      }
      input.setText("");
      ui.postDelayed(this::reloadFirstPage, 150);
    } catch (Exception e) {
      log.logError("onSendClicked", e);
      Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  // ============================================================
  // Действия с сообщением
  // ============================================================

  private void showMessageActions(Message m) {
    if (m == null)
      return;

    String[] options = { "📋 Копировать текст", "🗑 Удалить" };

    new AlertDialog.Builder(this).setTitle("Сообщение").setItems(options, (d, w) -> {
      if (w == 0) {
        copyToClipboard(m.getBody());
      } else if (w == 1) {
        confirmDelete(m);
      }
    }).setNegativeButton("Отмена", null).show();
  }

  private void copyToClipboard(String text) {
    if (text == null)
      return;
    try {
      ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
      if (cm != null) {
        cm.setPrimaryClip(ClipData.newPlainText("message", text));
        Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show();
      }
    } catch (Exception ignored) {
    }
  }

  private void confirmDelete(Message m) {
    new AlertDialog.Builder(this).setTitle("Удалить сообщение?").setMessage(m.getPreview())
        .setPositiveButton("Удалить", (d, w) -> {
          executor.execute(() -> {
            try {
              msgRepo.deleteById(m.getId());
              ui.post(() -> {
                reloadFirstPage();
                MessengerEvents.sendConversationChanged(ChatActivity.this, conversationId);
              });
            } catch (Exception e) {
              log.logError("deleteMessage", e);
            }
          });
        }).setNegativeButton("Отмена", null).show();
  }

  // ============================================================
  // Информация о диалоге
  // ============================================================

  private void showConversationInfo() {
    if (conversation == null)
      return;
    executor.execute(() -> {
      try {
        List<Participant> participants = partRepo.getByConversation(conversationId);
        StringBuilder sb = new StringBuilder();
        sb.append("Тип: ").append(conversation.getType()).append("\n");
        sb.append("Создан: ")
            .append(
                new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
                    .format(new java.util.Date(conversation.getCreatedAt())))
            .append("\n\n");
        sb.append("Участники (").append(participants.size()).append("):\n");
        for (Participant p : participants) {
          sb.append("• ").append(p.getDisplayName() != null ? p.getDisplayName() : "?").append(" [")
              .append(p.getRole()).append("]")
              .append(p.getNodeKey() != null ? " — " + p.getNodeKey() : "").append("\n");
        }

        final String info = sb.toString();
        ui.post(() -> new AlertDialog.Builder(this).setTitle("Информация о диалоге")
            .setMessage(info).setPositiveButton("OK", null).show());
      } catch (Exception e) {
        log.logError("showConversationInfo", e);
      }
    });
  }

  // ============================================================
  // Меню «Прикрепить»
  // ============================================================

  private void showAttachDialog() {
    String[] options = { "📷 Фото с камеры", "🎥 Видео с камеры", "🖼 Галерея (фото/видео)",
        "📦 Внутренний менеджер" };
    new AlertDialog.Builder(this).setTitle("Прикрепить").setItems(options, (d, w) -> {
      switch (w) {
        case 0:
          capturePhoto();
          break;
        case 1:
          captureVideo();
          break;
        case 2:
          pickMedia();
          break;
        case 3:
          pickFromAppManager();
          break;
      }
    }).setNegativeButton("Отмена", null).show();
  }
  
  private void pickFromAppManager() {
    try {
      Intent i = new Intent(this, com.my.mealse.filemanager.ui.FileManagerActivity.class);
      i.putExtra(com.my.mealse.filemanager.ui.FileManagerActivity.EXTRA_PICK_MODE, true);
      startActivityForResult(i, REQ_PICK_APP_MANAGER);
    } catch (Exception e) {
      toast("Не удалось открыть файловый менеджер: " + e.getMessage());
    }
  }

  private void pickMedia() {
    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
    i.addCategory(Intent.CATEGORY_OPENABLE);
    i.setType("*/*");
    i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] { "image/*", "video/*" });
    try {
      startActivityForResult(Intent.createChooser(i, "Выбрать медиа"), REQ_PICK_MEDIA);
    } catch (Exception e) {
      toast("Не удалось открыть галерею: " + e.getMessage());
    }
  }

  private void capturePhoto() {
    try {
      File dir = AttachmentStorage.getConversationDir(this, conversationId);
      String name = "photo_" + System.currentTimeMillis() + ".jpg";
      pendingCameraFile = new File(dir, name);
      pendingCameraUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider",
          pendingCameraFile);

      Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
      i.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri);
      i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
      startActivityForResult(i, REQ_CAPTURE_PHOTO);
    } catch (Exception e) {
      toast("Не удалось открыть камеру: " + e.getMessage());
    }
  }

  private void captureVideo() {
    try {
      File dir = AttachmentStorage.getConversationDir(this, conversationId);
      String name = "video_" + System.currentTimeMillis() + ".mp4";
      pendingCameraFile = new File(dir, name);
      pendingCameraUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider",
          pendingCameraFile);

      Intent i = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
      i.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri);
      i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
      startActivityForResult(i, REQ_CAPTURE_VIDEO);
    } catch (Exception e) {
      toast("Не удалось открыть камеру: " + e.getMessage());
    }
  }

  // ============================================================
  // Хелперы
  // ============================================================

  private static String shortId(String id) {
    if (id == null)
      return "?";
    return id.length() > 8 ? id.substring(0, 8) : id;
  }

  private String queryDisplayName(Uri uri) {
    String fallback = "file_" + System.currentTimeMillis();
    try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
      if (c != null && c.moveToFirst()) {
        int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
        if (idx >= 0) {
          String name = c.getString(idx);
          if (name != null && !name.isEmpty())
            return name;
        }
      }
    } catch (Throwable ignored) {
    }
    return fallback;
  }

  private static String sanitize(String s) {
    if (s == null || s.isEmpty())
      return "file";
    return s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
  }

  private void toast(String message) {
    try {
      Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    } catch (Throwable ignored) {
    }
  }

  private void checkViewNotNull(Object view, String id) {
    if (view == null) {
      String msg = "View не найден: " + id + " — проверь activity_chat.xml";
      if (log != null)
        log.error(msg);
      throw new IllegalStateException(msg);
    }
  }
}