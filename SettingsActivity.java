package com.my.mealse;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;

import com.my.mealse.adapter.NodeAdapter;
import com.my.mealse.model.TrustedNode;
import com.my.mealse.util.DeviceInfoHelper;
import com.my.mealse.util.IceConfigManager;
import com.my.mealse.util.MealseLogger;
import com.my.mealse.util.NodeManager;
import com.my.mealse.util.SecureAuthManager;

import java.util.List;

/**
 * ★ РЕВИЗИЯ: SettingsActivity — только настройки + управление узлами.
 *
 * Убрано:
 *  - Кнопка «Проверить доступность узла» (btn_check_node) — теперь в MainActivity.
 *  - Кнопка «VPN (клиент/сервер)» (btn_test_gateway) — теперь MainActivity.
 *  - Кнопка «Остановить передачу файлов» (btn_stop_file_transfer) — теперь MainActivity.
 *  - Методы: onCheckNodeClicked, performNodeCheck, stopFileTransferService,
 *    openTunnelActivity, initiateConnection.
 *  - Поля: btnCheckNode, btnStopFileTransfer, btnTestGateway,
 *    selectedNodeForCheck, pingInProgress.
 *
 * Оставлено:
 *  - «Показать данные устройства».
 *  - «Добавить устройство вручную».
 *  - Список узлов (действия: Настроить режим / Редактировать / Удалить).
 *  - Поведение (автозапуск, фон).
 *  - TURN и центральный сервер.
 *  - «Сохранить настройки».
 */
public class SettingsActivity extends BaseActivity {

  private static final String TAG = "SettingsActivity";

  // Managers
  private NodeManager nodeManager;
  private SecureAuthManager secureAuthManager;
  private IceConfigManager iceConfigManager;

  // Логгер
  private MealseLogger log;

  // Views
  private ListView listSavedNodes;
  private NodeAdapter nodeAdapter;
  private Button btnShowMyDevice, btnAddNode, btnSaveSettings, btnResetTurn;
  private CheckBox cbAutoStart, cbBackground, cbUseTurn;
  private EditText editTurnUrl, editTurnUsername, editTurnCredential;

  // Центральный сервер
  private EditText etCentralUrl, etCentralUsername, etCentralCredential;
  private Button btnCentralClear;

  // ============================================================
  // Lifecycle
  // ============================================================

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    log = MealseLogger.get(this, TAG);
    log.log("══ onCreate START ══");

    try {
      log.log("Шаг 1: setContentView");
      setContentView(R.layout.activity_settings);
      log.log("Шаг 1: OK");

      log.log("Шаг 2: создание managers");
      nodeManager = new NodeManager(this);
      secureAuthManager = new SecureAuthManager(this);
      iceConfigManager = new IceConfigManager(this);
      log.log("Шаг 2: OK");

      log.log("Шаг 3: initViews");
      initViews();
      log.log("Шаг 3: OK");

      log.log("Шаг 4: setupClickListeners");
      setupClickListeners();
      log.log("Шаг 4: OK");

      log.log("Шаг 5: loadNodeList");
      loadNodeList();
      log.log("Шаг 5: OK");

      log.log("Шаг 6: loadSettings");
      loadSettings();
      log.log("Шаг 6: OK");

      log.log("══ onCreate END ══");

    } catch (Exception e) {
      logError("КРИТИЧЕСКАЯ ОШИБКА в onCreate", e);
      Toast.makeText(this, "Ошибка запуска настроек: " + e.getMessage(), Toast.LENGTH_LONG).show();
      finish();
    }
  }

  private void initViews() {
    log.log("  initViews START");

    listSavedNodes = findViewById(R.id.list_saved_nodes);
    btnShowMyDevice = findViewById(R.id.btn_show_my_device);
    btnAddNode = findViewById(R.id.btn_add_node);
    btnSaveSettings = findViewById(R.id.btn_save_settings);
    btnResetTurn = findViewById(R.id.btn_reset_turn);

    cbAutoStart = findViewById(R.id.cb_auto_start);
    cbBackground = findViewById(R.id.cb_run_background);
    cbUseTurn = findViewById(R.id.cb_use_turn);

    editTurnUrl = findViewById(R.id.edit_turn_url);
    editTurnUsername = findViewById(R.id.edit_turn_username);
    editTurnCredential = findViewById(R.id.edit_turn_credential);

    etCentralUrl = findViewById(R.id.edit_central_url);
    etCentralUsername = findViewById(R.id.edit_central_username);
    etCentralCredential = findViewById(R.id.edit_central_credential);
    btnCentralClear = findViewById(R.id.btn_central_clear);

    log.log("  Найдено: listSavedNodes=" + (listSavedNodes != null)
        + ", btnShowMyDevice=" + (btnShowMyDevice != null)
        + ", btnAddNode=" + (btnAddNode != null)
        + ", btnSaveSettings=" + (btnSaveSettings != null)
        + ", btnResetTurn=" + (btnResetTurn != null)
        + ", cbAutoStart=" + (cbAutoStart != null)
        + ", cbBackground=" + (cbBackground != null)
        + ", cbUseTurn=" + (cbUseTurn != null)
        + ", editTurnUrl=" + (editTurnUrl != null)
        + ", editTurnUsername=" + (editTurnUsername != null)
        + ", editTurnCredential=" + (editTurnCredential != null)
        + ", etCentralUrl=" + (etCentralUrl != null)
        + ", etCentralUsername=" + (etCentralUsername != null)
        + ", etCentralCredential=" + (etCentralCredential != null)
        + ", btnCentralClear=" + (btnCentralClear != null));

    checkViewNotNull(listSavedNodes, "list_saved_nodes");
    checkViewNotNull(btnShowMyDevice, "btn_show_my_device");
    checkViewNotNull(btnAddNode, "btn_add_node");
    checkViewNotNull(btnSaveSettings, "btn_save_settings");

    listSavedNodes.setOnTouchListener((v, event) -> {
      if (event.getAction() == MotionEvent.ACTION_DOWN
          || event.getAction() == MotionEvent.ACTION_MOVE) {
        v.getParent().requestDisallowInterceptTouchEvent(true);
      }
      return false;
    });
    log.log("  listSavedNodes: requestDisallowInterceptTouchEvent включён");

    log.log("  initViews END");
  }

  private void checkViewNotNull(Object view, String id) {
    if (view == null) {
      String msg = "View не найден: " + id + " — проверь activity_settings.xml";
      log.error(msg);
      throw new IllegalStateException(msg);
    }
  }

  private void setupClickListeners() {
    log.log("  setupClickListeners START");

    btnShowMyDevice.setOnClickListener(v -> {
      log.log("Клик: btnShowMyDevice");
      showMyDeviceInfo();
    });

    btnAddNode.setOnClickListener(v -> {
      log.log("Клик: btnAddNode");
      showAddNodeDialog(null);
    });

    btnSaveSettings.setOnClickListener(v -> {
      log.log("Клик: btnSaveSettings");
      saveAll();
    });

    if (btnResetTurn != null) {
      btnResetTurn.setOnClickListener(v -> {
        log.log("Клик: btnResetTurn");
        resetTurnConfig();
      });
    }

    if (btnCentralClear != null) {
      btnCentralClear.setOnClickListener(v -> {
        log.log("Клик: btnCentralClear");
        onCentralClearClicked();
      });
    }

    listSavedNodes.setOnItemClickListener((parent, view, position, id) -> {
      try {
        TrustedNode node = nodeAdapter.getItem(position);
        log.log("Выбран узел: " + node.getName() + " (id=" + node.getId() + ") "
            + node.getKey());
        showNodeActionsDialog(node);
      } catch (Exception e) {
        logError("Ошибка выбора узла", e);
      }
    });

    log.log("  setupClickListeners END");
  }

  // ============================================================
  // Показ данных устройства
  // ============================================================

  private void showMyDeviceInfo() {
    log.log("showMyDeviceInfo START");
    try {
      String ip = DeviceInfoHelper.getLocalIpAddress(this);
      String deviceId = DeviceInfoHelper.getDeviceId(this);
      String fingerprint = secureAuthManager.getDeviceFingerprint();
      String name = android.os.Build.MODEL;

      log.log("Данные устройства: name=" + name + ", ip=" + ip + ", deviceId=" + deviceId
          + ", fingerprint=" + fingerprint);

      String networkSummary = DeviceInfoHelper.getNetworkInfoSummary(this);
      log.log("Сводка сети:\n" + networkSummary);

      String info = buildDeviceInfoText(name, ip, deviceId, fingerprint, networkSummary);

      AlertDialog.Builder builder = new AlertDialog.Builder(this);
      builder.setTitle("Данные этого устройства");

      EditText editText = new EditText(this);
      editText.setText(info);
      editText.setSingleLine(false);
      editText.setMinLines(10);
      editText.setGravity(android.view.Gravity.TOP);
      editText.setPadding(16, 16, 16, 16);
      editText.setFocusable(true);
      editText.setFocusableInTouchMode(true);
      editText.setClickable(true);
      editText.setLongClickable(true);
      editText.setTextIsSelectable(true);

      builder.setView(editText);
      builder.setPositiveButton("Копировать", (d, w) -> {
        try {
          android.content.ClipboardManager clipboard =
              (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
          android.content.ClipData clip =
              android.content.ClipData.newPlainText("Mealse данные", info);
          clipboard.setPrimaryClip(clip);
          Toast.makeText(this, "Скопировано в буфер", Toast.LENGTH_SHORT).show();
          log.log("Данные устройства скопированы в буфер");
        } catch (Exception e) {
          logError("Ошибка копирования", e);
        }
        d.dismiss();
      });
      builder.setNeutralButton("Обновить", (d, w) -> {
        log.log("Обновление данных устройства");
        d.dismiss();
        showMyDeviceInfo();
      });
      builder.setNegativeButton("Закрыть", (d, w) -> d.dismiss());
      builder.show();
    } catch (Exception e) {
      logError("Ошибка showMyDeviceInfo", e);
    }
  }

  private String buildDeviceInfoText(String name, String ip, String deviceId, String fingerprint,
      String networkSummary) {
    StringBuilder sb = new StringBuilder();
    sb.append("Имя: ").append(name).append("\n");
    sb.append("IP-адрес: ").append(ip != null ? ip : "—").append("\n");
    sb.append("ID устройства: ").append(deviceId != null ? deviceId : "—").append("\n");
    sb.append("Fingerprint: ").append(fingerprint != null ? fingerprint : "недоступен")
        .append("\n");
    sb.append("\n── Сеть ──\n");
    sb.append(networkSummary != null ? networkSummary : "(нет данных)");
    return sb.toString();
  }

  // ============================================================
  // Добавление / редактирование узла
  // ============================================================

  private void showAddNodeDialog(TrustedNode existingNode) {
    log.log("showAddNodeDialog: existing=" + (existingNode != null));
    try {
      View dialogView = getLayoutInflater().inflate(R.layout.dialog_connect, null);
      EditText editName = dialogView.findViewById(R.id.edit_node_name);
      EditText editIp = dialogView.findViewById(R.id.edit_node_ip);
      EditText editPort = dialogView.findViewById(R.id.edit_node_port);
      EditText editDeviceId = dialogView.findViewById(R.id.edit_device_id);

      if (existingNode != null) {
        editName.setText(existingNode.getName());
        editIp.setText(existingNode.getIpAddress());
        editPort.setText(String.valueOf(existingNode.getPort()));
        editDeviceId.setText(existingNode.getDeviceId());
      } else {
        editPort.setText("8081");
      }

      AlertDialog.Builder builder = new AlertDialog.Builder(this);
      builder.setView(dialogView);
      builder.setTitle(existingNode == null ? "Добавить устройство" : "Редактировать узел");

      builder.setPositiveButton(existingNode == null ? "Добавить" : "Сохранить", (d, w) -> {
        saveNodeFromDialog(existingNode, editName, editIp, editPort, editDeviceId);
      });

      builder.setNegativeButton("Отмена", (d, w) -> d.dismiss());
      builder.show();
    } catch (Exception e) {
      logError("Ошибка showAddNodeDialog", e);
    }
  }

  private void saveNodeFromDialog(TrustedNode existingNode, EditText editName, EditText editIp,
      EditText editPort, EditText editDeviceId) {
    try {
      String name = editName.getText().toString().trim();
      String ip = editIp.getText().toString().trim();
      String deviceIdStr = editDeviceId.getText().toString().trim();
      int port;

      log.log("Ввод узла: name='" + name + "', ip='" + ip + "', port='" + editPort.getText()
          + "', deviceId='" + deviceIdStr + "'");

      try {
        port = Integer.parseInt(editPort.getText().toString().trim());
        if (port < 1024 || port > 65535) {
          throw new NumberFormatException("Порт вне диапазона");
        }
      } catch (NumberFormatException e) {
        log.warn("Неверный порт");
        Toast.makeText(this, "Порт должен быть от 1024 до 65535", Toast.LENGTH_LONG).show();
        return;
      }

      if (name.isEmpty() || ip.isEmpty() || deviceIdStr.isEmpty()) {
        log.warn("Не все поля заполнены");
        Toast.makeText(this, "Заполните все поля", Toast.LENGTH_LONG).show();
        return;
      }

      String selfIp = DeviceInfoHelper.getSelfIp(this);
      String selfFp = secureAuthManager.getDeviceFingerprint();
      String selfDeviceId = DeviceInfoHelper.getDeviceId(this);

      log.log("Self данные: ip=" + selfIp + ", fp=" + selfFp + ", deviceId=" + selfDeviceId);

      if (selfIp != null && selfIp.equals(ip)) {
        log.warn("Отклонено: введён self IP (" + ip + ")");
        Toast.makeText(this, "Это IP вашего устройства — нельзя добавить как узел",
            Toast.LENGTH_LONG).show();
        return;
      }

      if (selfDeviceId != null && selfDeviceId.equals(deviceIdStr)) {
        log.warn("Отклонено: введён self deviceId (" + deviceIdStr + ")");
        Toast.makeText(this, "Это ID вашего устройства — нельзя добавить как узел",
            Toast.LENGTH_LONG).show();
        return;
      }

      // ========== РЕДАКТИРОВАНИЕ СУЩЕСТВУЮЩЕГО ==========
      if (existingNode != null) {
        log.log("Ветка: редактирование существующего узла id=" + existingNode.getId()
            + ", key=" + existingNode.getKey());

        String preservedFp = existingNode.getFingerprint();
        boolean sameOwner = existingNode.getDeviceId() != null
            && existingNode.getDeviceId().equals(deviceIdStr)
            && existingNode.getIpAddress() != null
            && existingNode.getIpAddress().equals(ip);
        if (!sameOwner) {
          log.log("Редактирование: владелец узла изменился — сбрасываю fingerprint");
          preservedFp = null;
        }

        TrustedNode node = new TrustedNode(existingNode.getId(), name, ip, port, deviceIdStr,
            preservedFp);
        node.setMode(existingNode.getMode());
        node.setGatewayIp(existingNode.getGatewayIp());
        node.setGatewayPort(existingNode.getGatewayPort());

        if (selfFp != null && selfFp.equals(node.getFingerprint())) {
          log.warn("Отклонено: fingerprint узла совпадает с self");
          Toast.makeText(this, "Fingerprint совпадает с вашим устройством — нельзя добавить",
              Toast.LENGTH_LONG).show();
          return;
        }

        boolean saved = nodeManager.saveNode(node, selfFp, selfDeviceId);
        if (!saved) {
          log.warn("saveNode вернул false — узел не сохранён (self?)");
          Toast.makeText(this, "Узел не сохранён: похоже, это ваше устройство",
              Toast.LENGTH_LONG).show();
          return;
        }

        log.log("Узел обновлён: id=" + node.getId() + ", name=" + node.getName()
            + " (" + node.getKey() + ")");
        loadNodeList();
        Toast.makeText(this, "Устройство обновлено", Toast.LENGTH_SHORT).show();
        return;
      }

      // ========== ДОБАВЛЕНИЕ НОВОГО ==========
      log.log("Ветка: добавление нового узла, deviceId=" + deviceIdStr);

      List<TrustedNode> existingByDeviceId = nodeManager.findAllByDeviceId(deviceIdStr);
      log.log("Найдено узлов с deviceId=" + deviceIdStr + ": " + existingByDeviceId.size());

      if (!existingByDeviceId.isEmpty()) {
        showDeviceIdConflictDialog(existingByDeviceId, name, ip, port, deviceIdStr,
            selfFp, selfDeviceId);
        return;
      }

      String newId = generateNodeId();
      log.log("Новый deviceId — генерирую id=" + newId);

      TrustedNode node = new TrustedNode(newId, name, ip, port, deviceIdStr, null);

      if (selfFp != null && selfFp.equals(node.getFingerprint())) {
        log.warn("Отклонено: fingerprint узла совпадает с self");
        Toast.makeText(this, "Fingerprint совпадает с вашим устройством — нельзя добавить",
            Toast.LENGTH_LONG).show();
        return;
      }

      boolean saved = nodeManager.saveNode(node, selfFp, selfDeviceId);
      if (!saved) {
        log.warn("saveNode вернул false — узел не сохранён (self?)");
        Toast.makeText(this, "Узел не сохранён: похоже, это ваше устройство", Toast.LENGTH_LONG)
            .show();
        return;
      }

      log.log("Новый узел добавлен: id=" + node.getId() + ", name=" + node.getName()
          + " (" + node.getKey() + ")");
      loadNodeList();
      Toast.makeText(this, "Устройство добавлено", Toast.LENGTH_SHORT).show();

    } catch (Exception e) {
      logError("Ошибка сохранения узла", e);
    }
  }

  private void showDeviceIdConflictDialog(List<TrustedNode> existingNodes, String name,
      String ip, int port, String deviceIdStr, String selfFp, String selfDeviceId) {
    try {
      final String suggestedName = name + " (" + (existingNodes.size() + 1) + ")";
      final String sharedId = existingNodes.get(0).getId();

      StringBuilder msg = new StringBuilder();
      msg.append("Устройство с таким ID уже сохранено:\n\n");
      for (TrustedNode n : existingNodes) {
        msg.append("• ").append(n.getName()).append(" — ").append(n.getKey()).append("\n");
      }
      msg.append("\nНовая запись будет создана с именем:\n");
      msg.append("• ").append(suggestedName).append(" — ").append(ip).append(":")
          .append(port).append("\n\n");
      msg.append("Старые узлы останутся без изменений.");

      log.log("showDeviceIdConflictDialog: sharedId=" + sharedId
          + ", suggestedName=" + suggestedName + ", existingNodes=" + existingNodes.size());

      new AlertDialog.Builder(this).setTitle("Устройство с таким ID уже есть")
          .setMessage(msg.toString())
          .setPositiveButton("Создать новый узел", (d, w) -> {
            TrustedNode node = new TrustedNode(sharedId, suggestedName, ip, port, deviceIdStr,
                null);

            if (selfFp != null && selfFp.equals(node.getFingerprint())) {
              Toast.makeText(this, "Fingerprint совпадает с вашим устройством",
                  Toast.LENGTH_LONG).show();
              return;
            }

            boolean saved = nodeManager.saveNode(node, selfFp, selfDeviceId);
            if (saved) {
              loadNodeList();
              Toast.makeText(this, "Создано: " + suggestedName + " (" + ip + ":" + port + ")",
                  Toast.LENGTH_SHORT).show();
            } else {
              Toast.makeText(this, "Не удалось сохранить узел", Toast.LENGTH_LONG).show();
            }
          })
          .setNegativeButton("Отмена", (d, w) -> d.dismiss())
          .show();
    } catch (Exception e) {
      logError("Ошибка showDeviceIdConflictDialog", e);
    }
  }

  // ============================================================
  // Действия с узлом (упрощено — без «Проверить» и «Подключиться»)
  // ============================================================

  private void showNodeActionsDialog(TrustedNode node) {
    log.log("showNodeActionsDialog: " + node.getName() + " (key=" + node.getKey() + ")");
    try {
      AlertDialog.Builder builder = new AlertDialog.Builder(this);
      builder.setTitle("Действия с узлом: " + node.getName());

      String[] options = { "Настроить режим", "Редактировать", "Удалить" };

      builder.setItems(options, (d, w) -> {
        if (w == 0) {
          log.log("Действие: Настроить режим");
          showNodeModeDialog(node);
        } else if (w == 1) {
          log.log("Действие: Редактировать");
          showAddNodeDialog(node);
        } else if (w == 2) {
          log.log("Действие: Удалить");
          showDeleteConfirmation(node);
        }
      });
      builder.setNegativeButton("Отмена", (d, w) -> d.dismiss());
      builder.show();
    } catch (Exception e) {
      logError("Ошибка showNodeActionsDialog", e);
    }
  }

  private void showNodeModeDialog(TrustedNode node) {
    log.log("showNodeModeDialog START: " + node.getName() + " (key=" + node.getKey() + ")"
        + ", текущий режим=" + node.getMode());

    try {
      View dialogView = getLayoutInflater().inflate(R.layout.dialog_gateway, null);
      RadioGroup rgMode = dialogView.findViewById(R.id.rg_node_mode);
      RadioButton rbAuto = dialogView.findViewById(R.id.rb_mode_auto);
      RadioButton rbDirect = dialogView.findViewById(R.id.rb_mode_direct);
      RadioButton rbGateway = dialogView.findViewById(R.id.rb_mode_gateway);
      RadioButton rbWifiDirect = dialogView.findViewById(R.id.rb_mode_wifi_direct);
      LinearLayout gatewayFields = dialogView.findViewById(R.id.gateway_fields);
      EditText editGwIp = dialogView.findViewById(R.id.edit_gateway_ip);
      EditText editGwPort = dialogView.findViewById(R.id.edit_gateway_port);

      checkViewNotNull(rgMode, "rg_node_mode");
      checkViewNotNull(rbAuto, "rb_mode_auto");
      checkViewNotNull(rbDirect, "rb_mode_direct");
      checkViewNotNull(rbGateway, "rb_mode_gateway");
      checkViewNotNull(rbWifiDirect, "rb_mode_wifi_direct");
      checkViewNotNull(gatewayFields, "gateway_fields");
      checkViewNotNull(editGwIp, "edit_gateway_ip");
      checkViewNotNull(editGwPort, "edit_gateway_port");

      TrustedNode.Mode currentMode = node.getMode();
      if (currentMode == TrustedNode.Mode.DIRECT) {
        rbDirect.setChecked(true);
      } else if (currentMode == TrustedNode.Mode.GATEWAY) {
        rbGateway.setChecked(true);
      } else if (currentMode == TrustedNode.Mode.WIFI_DIRECT) {
        rbWifiDirect.setChecked(true);
      } else {
        rbAuto.setChecked(true);
      }

      editGwIp.setText(node.getGatewayIp() != null ? node.getGatewayIp() : "");
      editGwPort.setText(String.valueOf(node.getGatewayPort() > 0 ? node.getGatewayPort() : 8083));

      updateGatewayFieldsVisibility(gatewayFields, currentMode);

      rgMode.setOnCheckedChangeListener((group, checkedId) -> {
        TrustedNode.Mode m;
        if (checkedId == R.id.rb_mode_direct) {
          m = TrustedNode.Mode.DIRECT;
        } else if (checkedId == R.id.rb_mode_gateway) {
          m = TrustedNode.Mode.GATEWAY;
        } else if (checkedId == R.id.rb_mode_wifi_direct) {
          m = TrustedNode.Mode.WIFI_DIRECT;
        } else {
          m = TrustedNode.Mode.AUTO;
        }
        log.log("Выбор режима: " + m);
        updateGatewayFieldsVisibility(gatewayFields, m);
      });

      AlertDialog.Builder builder = new AlertDialog.Builder(this);
      builder.setView(dialogView);
      builder.setTitle("Режим подключения: " + node.getName());

      builder.setPositiveButton("Сохранить", (d, w) -> {
        try {
          TrustedNode.Mode newMode;
          if (rbGateway.isChecked()) {
            newMode = TrustedNode.Mode.GATEWAY;
          } else if (rbDirect.isChecked()) {
            newMode = TrustedNode.Mode.DIRECT;
          } else if (rbWifiDirect.isChecked()) {
            newMode = TrustedNode.Mode.WIFI_DIRECT;
          } else {
            newMode = TrustedNode.Mode.AUTO;
          }

          String gwIp = editGwIp.getText().toString().trim();
          String gwPortStr = editGwPort.getText().toString().trim();
          int gwPort = 8083;
          try {
            gwPort = TextUtils.isEmpty(gwPortStr) ? 8083 : Integer.parseInt(gwPortStr);
          } catch (NumberFormatException e) {
            Toast.makeText(this, "Неверный порт шлюза", Toast.LENGTH_LONG).show();
            return;
          }

          if (newMode == TrustedNode.Mode.GATEWAY && (gwIp == null || gwIp.isEmpty())) {
            Toast.makeText(this, "Укажите IP шлюза", Toast.LENGTH_LONG).show();
            return;
          }

          boolean ok = nodeManager.updateNodeMode(node.getId(), node.getIpAddress(),
              node.getPort(), newMode, gwIp, gwPort);

          if (ok) {
            log.log("Режим узла обновлён: key=" + node.getKey() + ", mode=" + newMode);
            loadNodeList();
            Toast.makeText(this, "Режим сохранён: " + newMode, Toast.LENGTH_SHORT).show();
          } else {
            Toast.makeText(this, "Не удалось сохранить режим", Toast.LENGTH_LONG).show();
          }
          d.dismiss();
        } catch (Exception e) {
          logError("Ошибка сохранения режима узла", e);
        }
      });

      builder.setNegativeButton("Отмена", (d, w) -> d.dismiss());
      builder.show();

    } catch (Exception e) {
      logError("Ошибка showNodeModeDialog", e);
    }
  }

  private void updateGatewayFieldsVisibility(LinearLayout gatewayFields, TrustedNode.Mode mode) {
    if (gatewayFields == null)
      return;
    if (mode == TrustedNode.Mode.GATEWAY) {
      gatewayFields.setVisibility(View.VISIBLE);
    } else {
      gatewayFields.setVisibility(View.GONE);
    }
  }

  private void showDeleteConfirmation(TrustedNode node) {
    try {
      new AlertDialog.Builder(this).setTitle("Удалить устройство?")
          .setMessage("Вы уверены, что хотите удалить «" + node.getName() + "» ("
              + node.getKey() + ")?")
          .setPositiveButton("Удалить", (d, w) -> {
            try {
              log.log("Удаляем узел: key=" + node.getKey() + ", name=" + node.getName());
              nodeManager.deleteNode(node.getIpAddress(), node.getPort());
              loadNodeList();
              Toast.makeText(this, "Устройство удалено", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
              logError("Ошибка удаления узла", e);
            }
            d.dismiss();
          })
          .setNegativeButton("Отмена", (d, w) -> d.dismiss())
          .show();
    } catch (Exception e) {
      logError("Ошибка showDeleteConfirmation", e);
    }
  }

  // ============================================================
  // TURN
  // ============================================================

  private void resetTurnConfig() {
    log.log("resetTurnConfig START");
    try {
      new AlertDialog.Builder(this).setTitle("Сбросить TURN?")
          .setMessage("Настройки TURN будут сброшены на публичный openrelay, "
              + "центральный сервер будет очищен.")
          .setPositiveButton("Сбросить", (d, w) -> {
            try {
              iceConfigManager.resetToDefaults();
              loadTurnIntoViews();
              loadCentralIntoViews();
              Toast.makeText(this, "Настройки сброшены", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
              logError("Ошибка сброса TURN", e);
            }
            d.dismiss();
          })
          .setNegativeButton("Отмена", (d, w) -> d.dismiss())
          .show();
    } catch (Exception e) {
      logError("Ошибка resetTurnConfig", e);
    }
  }

  // ============================================================
  // Центральный сервер
  // ============================================================

  private void onCentralClearClicked() {
    log.log("onCentralClearClicked START");
    try {
      if (etCentralUrl != null) etCentralUrl.setText("");
      if (etCentralUsername != null) etCentralUsername.setText("");
      if (etCentralCredential != null) etCentralCredential.setText("");
      iceConfigManager.clearCentralServer();
      Toast.makeText(this, "Центральный сервер очищен", Toast.LENGTH_SHORT).show();
    } catch (Exception e) {
      logError("Ошибка onCentralClearClicked", e);
    }
  }

  // ============================================================
  // Список узлов
  // ============================================================

  private void loadNodeList() {
    try {
      List<TrustedNode> nodes = nodeManager.getAllNodes();
      log.log("loadNodeList: узлов=" + nodes.size());

      nodeAdapter = new NodeAdapter(this, nodes);
      // ★ OnGatewayToggleListener больше не существует.
      // Если нужен выбор узла — OnNodeSelectListener, но в SettingsActivity
      // выбор не требуется: действия делаются через клик по строке.
      listSavedNodes.setAdapter(nodeAdapter);

      log.log("loadNodeList: adapter установлен");
    } catch (Exception e) {
      logError("Ошибка loadNodeList", e);
    }
  }

  // ============================================================
  // Настройки
  // ============================================================

  private void loadSettings() {
    log.log("loadSettings START");
    try {
      boolean autoStart = getSharedPreferences("mealse_settings", MODE_PRIVATE)
          .getBoolean("auto_start", true);
      boolean runBackground = getSharedPreferences("mealse_settings", MODE_PRIVATE)
          .getBoolean("run_background", true);

      if (cbAutoStart != null) cbAutoStart.setChecked(autoStart);
      if (cbBackground != null) cbBackground.setChecked(runBackground);

      log.log("Настройки загружены: autoStart=" + autoStart
          + ", runBackground=" + runBackground);

      loadTurnIntoViews();
      loadCentralIntoViews();
    } catch (Exception e) {
      logError("Ошибка loadSettings", e);
    }
  }

  private void loadTurnIntoViews() {
    try {
      if (cbUseTurn != null) cbUseTurn.setChecked(iceConfigManager.isTurnEnabled());
      if (editTurnUrl != null) editTurnUrl.setText(iceConfigManager.getTurnUrl());
      if (editTurnUsername != null)
        editTurnUsername.setText(iceConfigManager.getTurnUsername());
      if (editTurnCredential != null)
        editTurnCredential.setText(iceConfigManager.getTurnCredential());
      log.log("TURN поля заполнены");
    } catch (Exception e) {
      logError("Ошибка loadTurnIntoViews", e);
    }
  }

  private void loadCentralIntoViews() {
    try {
      if (etCentralUrl != null) {
        String url = iceConfigManager.getCentralServerUrl();
        etCentralUrl.setText(url != null ? url : "");
      }
      if (etCentralUsername != null)
        etCentralUsername.setText(iceConfigManager.getCentralServerUsername());
      if (etCentralCredential != null)
        etCentralCredential.setText(iceConfigManager.getCentralServerCredential());
    } catch (Exception e) {
      logError("Ошибка loadCentralIntoViews", e);
    }
  }

  private void saveAll() {
    log.log("saveAll START");
    try {
      getSharedPreferences("mealse_settings", MODE_PRIVATE).edit()
          .putBoolean("auto_start", cbAutoStart != null && cbAutoStart.isChecked())
          .putBoolean("run_background", cbBackground != null && cbBackground.isChecked())
          .apply();

      if (cbUseTurn != null) {
        iceConfigManager.setTurnEnabled(cbUseTurn.isChecked());
      }
      String url = editTurnUrl != null ? editTurnUrl.getText().toString().trim() : null;
      String username = editTurnUsername != null
          ? editTurnUsername.getText().toString().trim() : null;
      String credential = editTurnCredential != null
          ? editTurnCredential.getText().toString().trim() : null;
      iceConfigManager.setTurnConfig(url, username, credential);

      String centralUrl = etCentralUrl != null
          ? etCentralUrl.getText().toString().trim() : null;
      String centralUsername = etCentralUsername != null
          ? etCentralUsername.getText().toString().trim() : null;
      String centralCredential = etCentralCredential != null
          ? etCentralCredential.getText().toString().trim() : null;

      if (centralUrl != null && !centralUrl.isEmpty()) {
        iceConfigManager.setCentralServerConfig(centralUrl, centralUsername, centralCredential);
      } else {
        iceConfigManager.clearCentralServer();
      }

      Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show();
      finish();
    } catch (Exception e) {
      logError("Ошибка saveAll", e);
    }
  }

  // ============================================================
  // Хелперы
  // ============================================================

  private String generateNodeId() {
    return "node_" + Long.toHexString(System.currentTimeMillis());
  }

  private void logError(String message, Throwable e) {
    if (log != null) {
      log.logError(message, e);
    } else {
      Log.e(TAG, message, e);
    }
  }
}�злов пуст");
        Toast.makeText(this, "Список узлов пуст. Добавьте узел сначала.", Toast.LENGTH_SHORT)
            .show();
        return;
      }

      log.log("Узлов для выбора: " + nodes.size());

      String[] names = new String[nodes.size()];
      for (int i = 0; i < nodes.size(); i++) {
        TrustedNode n = nodes.get(i);
        names[i] = n.getName() + " (" + n.getKey() + ")";
      }

      new AlertDialog.Builder(this).setTitle("Выберите узел для проверки")
          .setItems(names, (d, which) -> {
            selectedNodeForCheck = nodes.get(which);
            performNodeCheck(selectedNodeForCheck);
          }).setNegativeButton("Отмена", null).show();
    } catch (Exception e) {
      logError("Ошибка onCheckNodeClicked", e);
    }
  }

  private void performNodeCheck(TrustedNode node) {
    if (node == null) {
      log.warn("performNodeCheck: node == null");
      return;
    }

    if (pingInProgress) {
      log.warn("performNodeCheck: проверка уже идёт");
      Toast.makeText(this, "Проверка уже идёт", Toast.LENGTH_SHORT).show();
      return;
    }

    final String ip = node.getIpAddress();
    final int port = node.getPort();
    final String name = node.getName();
    final String key = node.getKey();

    log.log("performNodeCheck START: " + name + " (" + key + ")");
    pingInProgress = true;

    AlertDialog progressDialog;
    try {
      progressDialog = new AlertDialog.Builder(this).setTitle("Проверка узла")
          .setMessage("Проверяю:\n" + name + "\n" + key + "...").setCancelable(false).create();
      progressDialog.show();
    } catch (Exception e) {
      logError("Ошибка создания progressDialog", e);
      pingInProgress = false;
      return;
    }

    new Thread(() -> {
      long startTime = System.currentTimeMillis();
      String result;
      boolean success = false;

      try {
        log.log("ping: создаём Socket");
        Socket socket = new Socket();

        log.log("ping: connect(" + ip + ":" + port + ", timeout=3000)");
        socket.connect(new InetSocketAddress(ip, port), 3000);
        socket.close();

        long elapsed = System.currentTimeMillis() - startTime;
        log.log("ping OK: " + key + " за " + elapsed + " мс");

        success = true;
        result = "✅ Узел доступен\n\n" + "Имя: " + name + "\n" + "Адрес: " + key + "\n" + "Время: "
            + elapsed + " мс";

        // ★ Обновляем lastSeenTimestamp — узел жив. IP не меняем.
        try {
          TrustedNode fresh = nodeManager.findByIpPort(ip, port);
          if (fresh != null) {
            fresh.touchLastSeen(fresh.getLastSeenNetwork());
            nodeManager.saveNode(fresh);
            log.log("performNodeCheck: touchLastSeen для key=" + key);
          }
        } catch (Exception e) {
          log.logError("performNodeCheck: ошибка touchLastSeen", e);
        }

      } catch (SocketTimeoutException e) {
        log.logError("ping TIMEOUT: " + key, e);
        result = "⏱ Таймаут подключения (3 сек)\n\n" + "Узел: " + name + "\n" + "Адрес: " + key
            + "\n\n" + "Возможные причины:\n" + "• Устройство выключено / не в сети\n"
            + "• AP Isolation на роутере\n" + "• Устройства в разных подсетях\n"
            + "• Брандмауэр блокирует порт\n" + "• Устройство сменило IP (Wi-Fi → 4G)";

      } catch (ConnectException e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        log.logError("ping ConnectException: " + msg, e);

        if (msg.contains("ECONNREFUSED") || msg.contains("Connection refused")) {
          result = "🔴 Узел в сети, но сервер не запущен\n\n" + "Узел: " + name + "\n" + "Адрес: "
              + key + "\n\n" + "→ Запустите «Сервер» на устройстве " + name;
        } else if (msg.contains("EHOSTUNREACH") || msg.contains("No route to host")) {
          result = "🔴 Узел НЕДОСТУПЕН в сети\n\n" + "Узел: " + name + "\n" + "Адрес: " + key
              + "\n\n" + "→ Проверьте:\n" + "• Оба устройства в одной Wi-Fi сети?\n"
              + "• AP Isolation выключен на роутере?\n" + "• IP-адрес " + ip + " актуален?";
        } else if (msg.contains("ENETUNREACH") || msg.contains("Network is unreachable")) {
          result = "🔴 Нет доступа к сети\n\n" + "→ Проверьте, что у обоих устройств есть Wi-Fi";
        } else {
          result = "🔴 Ошибка подключения\n\n" + "Узел: " + name + "\n" + "Адрес: " + key + "\n"
              + "Ошибка: " + msg;
        }

      } catch (UnknownHostException e) {
        log.logError("ping UnknownHost: " + ip, e);
        result = "🔴 Неизвестный хост\n\n" + "Адрес: " + ip + "\n" + "→ Проверьте формат IP-адреса";

      } catch (Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        log.logError("ping Exception: " + msg, e);
        result = "🔴 Неожиданная ошибка\n\n" + "Узел: " + name + "\n" + "Адрес: " + key + "\n"
            + "Ошибка: " + msg;
      }

      final String finalResult = result;
      final boolean finalSuccess = success;

      runOnUiThread(() -> {
        try {
          if (progressDialog.isShowing()) {
            progressDialog.dismiss();
          }

          AlertDialog.Builder builder = new AlertDialog.Builder(SettingsActivity.this)
              .setTitle(finalSuccess ? "✅ Узел доступен" : "❌ Узел недоступен")
              .setMessage(finalResult).setPositiveButton("OK", null);

          if (!finalSuccess) {
            builder.setNeutralButton("Путь логов", (d, w) -> {
              String logPath = MealseLogger.getLogDirPath(SettingsActivity.this);
              Toast.makeText(SettingsActivity.this, "Логи: " + logPath, Toast.LENGTH_LONG).show();
            });
          }

          builder.show();
        } catch (Exception e) {
          logError("Ошибка показа результата ping", e);
        } finally {
          pingInProgress = false;
        }
      });

    }, "NodeCheckThread").start();
  }

  // ============================================================
  // Остановка FileTransferService
  // ============================================================

  private void stopFileTransferService() {
    log.log("stopFileTransferService START");
    try {
      new AlertDialog.Builder(this).setTitle("Остановить передачу файлов?")
          .setMessage("Сервер передачи файлов будет остановлен. " + "Активные передачи прервутся.")
          .setPositiveButton("Остановить", (d, w) -> {
            try {
              Intent intent = new Intent(this, FileTransferService.class);
              intent.setAction(FileTransferService.ACTION_STOP_SERVER);
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
              } else {
                startService(intent);
              }
              log.log("FileTransferService: отправлен ACTION_STOP_SERVER");
              Toast.makeText(this, "Сервис передачи файлов остановлен", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
              logError("Ошибка остановки FileTransferService", e);
            }
            d.dismiss();
          }).setNegativeButton("Отмена", (d, w) -> d.dismiss()).show();
    } catch (Exception e) {
      logError("Ошибка stopFileTransferService", e);
    }
  }

  // ============================================================
  // TURN
  // ============================================================

  private void resetTurnConfig() {
    log.log("resetTurnConfig START");
    try {
      new AlertDialog.Builder(this).setTitle("Сбросить TURN?")
          .setMessage("Настройки TURN будут сброшены на публичный openrelay, "
              + "центральный сервер будет очищен.")
          .setPositiveButton("Сбросить", (d, w) -> {
            try {
              iceConfigManager.resetToDefaults();
              loadTurnIntoViews();
              loadCentralIntoViews();
              log.log("TURN и центральный сервер сброшены");
              Toast.makeText(this, "Настройки сброшены", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
              logError("Ошибка сброса TURN", e);
            }
            d.dismiss();
          }).setNegativeButton("Отмена", (d, w) -> d.dismiss()).show();
    } catch (Exception e) {
      logError("Ошибка resetTurnConfig", e);
    }
  }

  // ============================================================
  // Центральный сервер
  // ============================================================

  private void onCentralClearClicked() {
    log.log("onCentralClearClicked START");
    try {
      if (etCentralUrl != null)
        etCentralUrl.setText("");
      if (etCentralUsername != null)
        etCentralUsername.setText("");
      if (etCentralCredential != null)
        etCentralCredential.setText("");
      iceConfigManager.clearCentralServer();
      Toast.makeText(this, "Центральный сервер очищен", Toast.LENGTH_SHORT).show();
      log.log("Центральный сервер очищен");
    } catch (Exception e) {
      logError("Ошибка onCentralClearClicked", e);
    }
  }

  // ============================================================
  // Список узлов
  // ============================================================

  private void loadNodeList() {
    try {
      List<TrustedNode> nodes = nodeManager.getAllNodes();
      log.log("loadNodeList: узлов=" + nodes.size());

      nodeAdapter = new NodeAdapter(this, nodes);
      nodeAdapter.setOnGatewayToggleListener((node, enabled) -> {
        log.log("Gateway toggle: " + node.getName() + " (key=" + node.getKey() + ") → " + enabled);
        try {
          nodeManager.saveNode(node);
          log.log("Gateway state сохранён в NodeManager");
        } catch (Exception e) {
          logError("Ошибка сохранения gateway state", e);
        }
      });
      listSavedNodes.setAdapter(nodeAdapter);

      log.log("loadNodeList: adapter установлен");
    } catch (Exception e) {
      logError("Ошибка loadNodeList", e);
    }
  }

  // ============================================================
  // Настройки
  // ============================================================

  private void loadSettings() {
    log.log("loadSettings START");
    try {
      boolean autoStart = getSharedPreferences("mealse_settings", MODE_PRIVATE)
          .getBoolean("auto_start", true);
      boolean runBackground = getSharedPreferences("mealse_settings", MODE_PRIVATE)
          .getBoolean("run_background", true);

      if (cbAutoStart != null)
        cbAutoStart.setChecked(autoStart);
      if (cbBackground != null)
        cbBackground.setChecked(runBackground);

      log.log("Настройки загружены: autoStart=" + autoStart + ", runBackground=" + runBackground);

      loadTurnIntoViews();
      loadCentralIntoViews();
    } catch (Exception e) {
      logError("Ошибка loadSettings", e);
    }
  }

  private void loadTurnIntoViews() {
    try {
      if (cbUseTurn != null)
        cbUseTurn.setChecked(iceConfigManager.isTurnEnabled());
      if (editTurnUrl != null)
        editTurnUrl.setText(iceConfigManager.getTurnUrl());
      if (editTurnUsername != null) {
        editTurnUsername.setText(iceConfigManager.getTurnUsername());
      }
      if (editTurnCredential != null) {
        editTurnCredential.setText(iceConfigManager.getTurnCredential());
      }
      log.log("TURN поля заполнены");
    } catch (Exception e) {
      logError("Ошибка loadTurnIntoViews", e);
    }
  }

  private void loadCentralIntoViews() {
    try {
      if (etCentralUrl != null) {
        String url = iceConfigManager.getCentralServerUrl();
        etCentralUrl.setText(url != null ? url : "");
      }
      if (etCentralUsername != null) {
        etCentralUsername.setText(iceConfigManager.getCentralServerUsername());
      }
      if (etCentralCredential != null) {
        etCentralCredential.setText(iceConfigManager.getCentralServerCredential());
      }
      log.log("Central server поля заполнены: url=" + iceConfigManager.getCentralServerUrl()
          + ", enabled=" + iceConfigManager.isCentralServerEnabled());
    } catch (Exception e) {
      logError("Ошибка loadCentralIntoViews", e);
    }
  }

  private void saveAll() {
    log.log("saveAll START");
    try {
      getSharedPreferences("mealse_settings", MODE_PRIVATE).edit()
          .putBoolean("auto_start", cbAutoStart != null && cbAutoStart.isChecked())
          .putBoolean("run_background", cbBackground != null && cbBackground.isChecked()).apply();

      if (cbUseTurn != null) {
        iceConfigManager.setTurnEnabled(cbUseTurn.isChecked());
      }
      String url = editTurnUrl != null ? editTurnUrl.getText().toString().trim() : null;
      String username = editTurnUsername != null ? editTurnUsername.getText().toString().trim()
          : null;
      String credential = editTurnCredential != null
          ? editTurnCredential.getText().toString().trim()
          : null;
      iceConfigManager.setTurnConfig(url, username, credential);

      String centralUrl = etCentralUrl != null ? etCentralUrl.getText().toString().trim() : null;
      String centralUsername = etCentralUsername != null
          ? etCentralUsername.getText().toString().trim()
          : null;
      String centralCredential = etCentralCredential != null
          ? etCentralCredential.getText().toString().trim()
          : null;

      if (centralUrl != null && !centralUrl.isEmpty()) {
        iceConfigManager.setCentralServerConfig(centralUrl, centralUsername, centralCredential);
        log.log("Центральный сервер сохранён: " + centralUrl);
      } else {
        iceConfigManager.clearCentralServer();
        log.log("Центральный сервер пуст — очищен");
      }

      log.log("Настройки сохранены: autoStart=" + (cbAutoStart != null && cbAutoStart.isChecked())
          + ", runBackground=" + (cbBackground != null && cbBackground.isChecked())
          + ", turnEnabled=" + iceConfigManager.isTurnEnabled() + ", turnUrl=" + url
          + ", centralUrl=" + centralUrl);

      Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show();
      finish();
    } catch (Exception e) {
      logError("Ошибка saveAll", e);
    }
  }

  // ============================================================
  // Хелперы
  // ============================================================

  private String generateNodeId() {
    return "node_" + Long.toHexString(System.currentTimeMillis());
  }

  private String generateOneTimeCode() {
    return String.format("%04d", new Random().nextInt(10000));
  }

  private void logError(String message, Throwable e) {
    if (log != null) {
      log.logError(message, e);
    } else {
      Log.e(TAG, message, e);
    }
  }
}=============================================

  private String generateNodeId() {
    return "node_" + Long.toHexString(System.currentTimeMillis());
  }

  private String generateOneTimeCode() {
    return String.format("%04d", new Random().nextInt(10000));
  }

  private void logError(String message, Throwable e) {
    if (log != null) {
      log.logError(message, e);
    } else {
      Log.e(TAG, message, e);
    }
  }
}tBoolean("run_background", cbBackground != null && cbBackground.isChecked())
          .apply();

      if (cbUseTurn != null) {
        iceConfigManager.setTurnEnabled(cbUseTurn.isChecked());
      }
      String url = editTurnUrl != null ? editTurnUrl.getText().toString().trim() : null;
      String username = editTurnUsername != null
          ? editTurnUsername.getText().toString().trim() : null;
      String credential = editTurnCredential != null
          ? editTurnCredential.getText().toString().trim() : null;
      iceConfigManager.setTurnConfig(url, username, credential);

      String centralUrl = etCentralUrl != null
          ? etCentralUrl.getText().toString().trim() : null;
      String centralUsername = etCentralUsername != null
          ? etCentralUsername.getText().toString().trim() : null;
      String centralCredential = etCentralCredential != null
          ? etCentralCredential.getText().toString().trim() : null;

      if (centralUrl != null && !centralUrl.isEmpty()) {
        iceConfigManager.setCentralServerConfig(centralUrl, centralUsername, centralCredential);
        log.log("Центральный сервер сохранён: " + centralUrl);
      } else {
        iceConfigManager.clearCentralServer();
        log.log("Центральный сервер пуст — очищен");
      }

      log.log("Настройки сохранены: autoStart="
          + (cbAutoStart != null && cbAutoStart.isChecked())
          + ", runBackground=" + (cbBackground != null && cbBackground.isChecked())
          + ", turnEnabled=" + iceConfigManager.isTurnEnabled()
          + ", turnUrl=" + url
          + ", centralUrl=" + centralUrl);

      Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show();
      finish();
    } catch (Exception e) {
      logError("Ошибка saveAll", e);
    }
  }

  // ============================================================
  // Хелперы
  // ============================================================

  private String generateNodeId() {
    return "node_" + Long.toHexString(System.currentTimeMillis());
  }

  private String generateOneTimeCode() {
    return String.format("%04d", new Random().nextInt(10000));
  }

  private void logError(String message, Throwable e) {
    if (log != null) {
      log.logError(message, e);
    } else {
      Log.e(TAG, message, e);
    }
  }
}