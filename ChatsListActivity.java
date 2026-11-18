package com.my.mealse.messenger.ui;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.my.mealse.BaseActivity;
import com.my.mealse.R;
import com.my.mealse.messenger.MessengerEvents;
import com.my.mealse.messenger.model.Conversation;
import com.my.mealse.messenger.model.ConversationType;
import com.my.mealse.messenger.model.Participant;
import com.my.mealse.messenger.model.ParticipantRole;
import com.my.mealse.messenger.repo.ConversationRepository;
import com.my.mealse.messenger.repo.ParticipantRepository;
import com.my.mealse.model.TrustedNode;
import com.my.mealse.util.BroadcastUtils;
import com.my.mealse.util.DeviceInfoHelper;
import com.my.mealse.util.MealseLogger;
import com.my.mealse.util.NodeManager;
import com.my.mealse.util.SecureAuthManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Список диалогов.
 *
 * Источник данных — ConversationRepository.getAll() (Room).
 * При onCreate — загрузка в фоне, показ списка.
 * Подписка на MessengerEvents.ACTION_MESSAGE_NEW / ACTION_CONVERSATION_CHANGED
 * — перезагрузка списка (для простоты; можно оптимизировать updateConversation).
 *
 * Клик по диалогу → ChatActivity (по имени класса, чтобы не ломать компиляцию
 * до Шага 8, когда ChatActivity появится).
 *
 * Кнопка «+» → диалог выбора:
 *   • «Новый приватный чат» → список TrustedNode
 *   • «Новая группа» → выбор нескольких узлов + название
 *
 * ★ MVP: групповой чат = ConversationType.GROUP + участники. Отправка
 *   в группу работает через MessageCommands.sendText (создаёт outbox на
 *   каждого участника). Приём групповых — MessageReceiver создаёт диалог,
 *   если его нет.
 */
public class ChatsListActivity extends BaseActivity {

    private static final String TAG = "MessengerChatsList";

    private MealseLogger log;

    // Views
    private ImageButton btnBack, btnNew;
    private ListView listView;
    private TextView emptyView;
    private ProgressBar loading;

    // Данные
    private ChatsAdapter adapter;
    private ConversationRepository convRepo;
    private ParticipantRepository partRepo;
    private NodeManager nodeManager;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ChatsList");
        t.setDaemon(true);
        return t;
    });

    private final Handler ui = new Handler(Looper.getMainLooper());

    // Broadcast
    private boolean receiverRegistered = false;
    private final BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (MessengerEvents.ACTION_MESSAGE_NEW.equals(action)
                || MessengerEvents.ACTION_CONVERSATION_CHANGED.equals(action)
                || MessengerEvents.ACTION_MESSAGE_STATUS_CHANGED.equals(action)) {
                reload();
            }
        }
    };

    // ============================================================
    // Lifecycle
    // ============================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        log = MealseLogger.get(this, TAG);
        log.log("══ ChatsListActivity onCreate ══");

        try {
            setContentView(R.layout.activity_chats_list);

            convRepo = new ConversationRepository(this);
            partRepo = new ParticipantRepository(this);
            nodeManager = new NodeManager(this);

            initViews();
            setupListeners();
            registerReceiver();
            reload();

        } catch (Exception e) {
            log.logError("onCreate", e);
            Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
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
        btnBack = findViewById(R.id.cl_btn_back);
        btnNew = findViewById(R.id.cl_btn_new);
        listView = findViewById(R.id.cl_list);
        emptyView = findViewById(R.id.cl_empty);
        loading = findViewById(R.id.cl_loading);

        if (btnBack == null || btnNew == null || listView == null
            || emptyView == null || loading == null) {
            throw new IllegalStateException("activity_chats_list.xml: не найдены View");
        }

        adapter = new ChatsAdapter(this);
        listView.setAdapter(adapter);
    }

    private void setupListeners() {
        btnBack.setOnClickListener(v -> finish());
        btnNew.setOnClickListener(v -> showNewChatDialog());

        listView.setOnItemClickListener((parent, view, position, id) -> {
            Conversation c = adapter.getItem(position);
            if (c != null) openConversation(c);
        });

        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            Conversation c = adapter.getItem(position);
            if (c != null) showConversationActions(c);
            return true;
        });
    }

    private void registerReceiver() {
        if (receiverRegistered) return;
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(MessengerEvents.ACTION_MESSAGE_NEW);
            f.addAction(MessengerEvents.ACTION_CONVERSATION_CHANGED);
            f.addAction(MessengerEvents.ACTION_MESSAGE_STATUS_CHANGED);
            BroadcastUtils.registerNotExported(this, refreshReceiver, f);
            receiverRegistered = true;
        } catch (Exception e) {
            log.logError("registerReceiver", e);
        }
    }

    private void unregisterReceiver() {
        if (!receiverRegistered) return;
        try {
            BroadcastUtils.unregisterSafely(this, refreshReceiver);
        } catch (Exception ignored) {
        }
        receiverRegistered = false;
    }

    // ============================================================
    // Загрузка списка
    // ============================================================

    private void reload() {
        showLoading(true);
        executor.execute(() -> {
            try {
                List<Conversation> list = convRepo.getAll();
                ui.post(() -> {
                    showLoading(false);
                    adapter.replaceAll(list);
                    updateEmpty(list.isEmpty());
                });
            } catch (Exception e) {
                log.logError("reload", e);
                ui.post(() -> showLoading(false));
            }
        });
    }

    private void updateEmpty(boolean empty) {
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        listView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void showLoading(boolean show) {
        loading.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // ============================================================
    // Открытие диалога
    // ============================================================

    private void openConversation(Conversation c) {
        try {
            Intent i = new Intent();
            i.setClassName(this, "com.my.mealse.messenger.ui.ChatActivity");
            i.putExtra("conversation_id", c.getId());
            startActivity(i);
        } catch (Exception e) {
            log.logError("openConversation", e);
            Toast.makeText(this, "ChatActivity ещё не создана (Шаг 8): "
                + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ============================================================
    // Действия с диалогом (долгий тап)
    // ============================================================

    private void showConversationActions(Conversation c) {
        String[] options = {"🗑 Удалить диалог"};
        new AlertDialog.Builder(this)
            .setTitle(c.getDisplayTitle())
            .setItems(options, (d, w) -> {
                if (w == 0) confirmDelete(c);
            })
            .setNegativeButton("Отмена", null)
            .show();
    }

    private void confirmDelete(Conversation c) {
        new AlertDialog.Builder(this)
            .setTitle("Удалить диалог?")
            .setMessage("«" + c.getDisplayTitle() + "» и все сообщения будут удалены.\n"
                + "Вложения в messenger/attachments/ тоже будут удалены.")
            .setPositiveButton("Удалить", (d, w) -> {
                executor.execute(() -> {
                    try {
                        // Удаляем вложения
                        try {
                            com.my.mealse.messenger.storage.AttachmentStorage
                                .deleteForConversation(this, c.getId());
                        } catch (Exception ignored) {}

                        // Удаляем сообщения, участников, диалог (CASCADE в БД)
                        new com.my.mealse.messenger.repo.MessageRepository(this)
                            .deleteByConversation(c.getId());
                        partRepo.deleteByConversation(c.getId());
                        convRepo.deleteById(c.getId());

                        ui.post(this::reload);
                    } catch (Exception e) {
                        log.logError("deleteConversation", e);
                    }
                });
            })
            .setNegativeButton("Отмена", null)
            .show();
    }

    // ============================================================
    // Создание нового диалога
    // ============================================================

    private void showNewChatDialog() {
        String[] options = {"👤 Приватный чат", "👥 Групповой чат"};
        new AlertDialog.Builder(this)
            .setTitle("Новый чат")
            .setItems(options, (d, w) -> {
                if (w == 0) showPickNodeForPrivate();
                else showCreateGroupFlow();
            })
            .setNegativeButton("Отмена", null)
            .show();
    }

    // ============================================================
    // Приватный чат
    // ============================================================

    private void showPickNodeForPrivate() {
        executor.execute(() -> {
            try {
                List<TrustedNode> nodes = nodeManager.getAllNodes();
                ui.post(() -> {
                    if (nodes.isEmpty()) {
                        Toast.makeText(this, "Нет доверенных узлов. Добавьте узел в настройках.",
                            Toast.LENGTH_LONG).show();
                        return;
                    }
                    String[] names = new String[nodes.size()];
                    for (int i = 0; i < nodes.size(); i++) {
                        TrustedNode n = nodes.get(i);
                        names[i] = n.getName() + "  (" + n.getKey() + ")";
                    }
                    new AlertDialog.Builder(this)
                        .setTitle("Выберите узел")
                        .setItems(names, (d, w) -> createPrivateChat(nodes.get(w)))
                        .setNegativeButton("Отмена", null)
                        .show();
                });
            } catch (Exception e) {
                log.logError("showPickNodeForPrivate", e);
            }
        });
    }

    private void createPrivateChat(TrustedNode node) {
        executor.execute(() -> {
            try {
                String selfFp = getSelfFingerprint();
                String selfDeviceId = getSelfDeviceId();

                String peerFp = node.getFingerprint();
                if (peerFp == null || peerFp.isEmpty()) {
                    // нет fingerprint — используем deviceId как запасной ключ
                    peerFp = "deviceid:" + node.getDeviceId();
                }

                Conversation conv = convRepo.getOrCreatePrivate(peerFp, node.getName());
                if (conv == null) {
                    ui.post(() -> Toast.makeText(this, "Не удалось создать диалог",
                        Toast.LENGTH_SHORT).show());
                    return;
                }

                // Добавляем участников (если ещё нет)
                if (partRepo.countByConversation(conv.getId()) == 0) {
                    // Я — OWNER
                    partRepo.add(conv.getId(), null, selfFp, selfDeviceId,
                        "Я", ParticipantRole.OWNER);
                    // Собеседник — MEMBER
                    partRepo.add(conv.getId(), node.getKey(), peerFp, node.getDeviceId(),
                        node.getName(), ParticipantRole.MEMBER);
                }

                final String convId = conv.getId();
                ui.post(() -> {
                    Intent i = new Intent();
                    i.setClassName(this, "com.my.mealse.messenger.ui.ChatActivity");
                    i.putExtra("conversation_id", convId);
                    try {
                        startActivity(i);
                    } catch (Exception e) {
                        log.logError("startActivity ChatActivity", e);
                        Toast.makeText(this, "ChatActivity ещё не создана (Шаг 8)",
                            Toast.LENGTH_LONG).show();
                        reload();
                    }
                });
            } catch (Exception e) {
                log.logError("createPrivateChat", e);
            }
        });
    }

    // ============================================================
    // Групповой чат
    // ============================================================

    private void showCreateGroupFlow() {
        executor.execute(() -> {
            try {
                List<TrustedNode> nodes = nodeManager.getAllNodes();
                ui.post(() -> {
                    if (nodes.isEmpty()) {
                        Toast.makeText(this, "Нет доверенных узлов для группы",
                            Toast.LENGTH_LONG).show();
                        return;
                    }
                    String[] names = new String[nodes.size()];
                    final boolean[] checked = new boolean[nodes.size()];
                    for (int i = 0; i < nodes.size(); i++) {
                        names[i] = nodes.get(i).getName() + "  (" + nodes.get(i).getKey() + ")";
                    }
                    new AlertDialog.Builder(this)
                        .setTitle("Участники группы")
                        .setMultiChoiceItems(names, checked,
                            (d, which, isChecked) -> checked[which] = isChecked)
                        .setPositiveButton("Далее", (d, w) -> {
                            List<TrustedNode> selected = new ArrayList<>();
                            for (int i = 0; i < nodes.size(); i++) {
                                if (checked[i]) selected.add(nodes.get(i));
                            }
                            if (selected.isEmpty()) {
                                Toast.makeText(this, "Выберите хотя бы одного",
                                    Toast.LENGTH_SHORT).show();
                                return;
                            }
                            askGroupTitle(selected);
                        })
                        .setNegativeButton("Отмена", null)
                        .show();
                });
            } catch (Exception e) {
                log.logError("showCreateGroupFlow", e);
            }
        });
    }

    private void askGroupTitle(List<TrustedNode> selected) {
        EditText input = new EditText(this);
        input.setHint("Название группы");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setTextColor(0xFFFFFFFF);

        new AlertDialog.Builder(this)
            .setTitle("Название группы")
            .setView(input)
            .setPositiveButton("Создать", (d, w) -> {
                String title = input.getText().toString().trim();
                if (TextUtils.isEmpty(title)) title = "Группа";
                createGroupChat(title, selected);
            })
            .setNegativeButton("Отмена", null)
            .show();
    }

    private void createGroupChat(String title, List<TrustedNode> selected) {
        executor.execute(() -> {
            try {
                Conversation conv = convRepo.createGroup(title);
                if (conv == null) {
                    ui.post(() -> Toast.makeText(this, "Не удалось создать группу",
                        Toast.LENGTH_SHORT).show());
                    return;
                }

                String selfFp = getSelfFingerprint();
                String selfDeviceId = getSelfDeviceId();

                // Я — OWNER
                partRepo.add(conv.getId(), null, selfFp, selfDeviceId,
                    "Я", ParticipantRole.OWNER);

                // Остальные — MEMBER
                for (TrustedNode n : selected) {
                    String peerFp = n.getFingerprint();
                    if (peerFp == null || peerFp.isEmpty()) {
                        peerFp = "deviceid:" + n.getDeviceId();
                    }
                    partRepo.add(conv.getId(), n.getKey(), peerFp, n.getDeviceId(),
                        n.getName(), ParticipantRole.MEMBER);
                }

                final String convId = conv.getId();
                ui.post(() -> {
                    Intent i = new Intent();
                    i.setClassName(this, "com.my.mealse.messenger.ui.ChatActivity");
                    i.putExtra("conversation_id", convId);
                    try {
                        startActivity(i);
                    } catch (Exception e) {
                        log.logError("startActivity ChatActivity", e);
                        Toast.makeText(this, "ChatActivity ещё не создана (Шаг 8)",
                            Toast.LENGTH_LONG).show();
                        reload();
                    }
                });
            } catch (Exception e) {
                log.logError("createGroupChat", e);
            }
        });
    }

    // ============================================================
    // Self identity
    // ============================================================

    private String getSelfFingerprint() {
        try {
            String fp = new SecureAuthManager(this).getDeviceFingerprint();
            return fp != null ? fp : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String getSelfDeviceId() {
        try {
            String id = DeviceInfoHelper.getDeviceId(this);
            return id != null ? id : "";
        } catch (Exception e) {
            return "";
        }
    }
}