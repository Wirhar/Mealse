package com.my.mealse.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.TextView;

import com.my.mealse.R;
import com.my.mealse.model.TrustedNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ★ РЕВИЗИЯ (галочка = выбор узла):
 *  - Ключ статуса и getItemId — по node.getKey() = "ip:port".
 *  - OnGatewayToggleListener УБРАН.
 *  - Добавлен OnNodeSelectListener — вызывается при клике по галочке.
 *  - Добавлено поле selectedKey — "ip:port" выбранного узла.
 *  - Галочка (ib_select) — ImageButton с ic_check (выбрано) /
 *    ic_radio_unchecked (не выбрано).
 *  - Клик по галочке = выбор узла. Клик по строке = меню действий
 *    (обрабатывается снаружи, в MainActivity).
 */
public class NodeAdapter extends BaseAdapter {

  public enum NodeStatus {
    UNKNOWN, CHECKING, ONLINE, REFUSED, UNREACHABLE
  }

  /**
   * ★ НОВОЕ: callback выбора узла. Вызывается при клике по галочке.
   */
  public interface OnNodeSelectListener {
    void onNodeSelected(TrustedNode node);
  }

  private final Context context;
  private final List<TrustedNode> nodeList;

  /** ★ Ключ — node.getKey() = "ip:port". */
  private final Map<String, NodeStatus> statuses = new HashMap<>();

  /** ★ НОВОЕ: ключ текущего выбранного узла ("ip:port"). null = ничего не выбрано. */
  private String selectedKey = null;

  private OnNodeSelectListener nodeSelectListener;

  public NodeAdapter(Context context, List<TrustedNode> nodeList) {
    this.context = context;
    this.nodeList = new ArrayList<>(nodeList);

    for (TrustedNode node : this.nodeList) {
      statuses.put(node.getKey(), NodeStatus.UNKNOWN);
    }
  }

  public void setOnNodeSelectListener(OnNodeSelectListener listener) {
    this.nodeSelectListener = listener;
  }

  /**
   * ★ НОВОЕ: установить выбранный узел по ключу "ip:port".
   * null — сбросить выбор.
   */
  public void setSelectedKey(String key) {
    if (this.selectedKey == null && key == null) return;
    if (this.selectedKey != null && this.selectedKey.equals(key)) return;

    this.selectedKey = key;
    notifyDataSetChanged();
  }

  public String getSelectedKey() {
    return selectedKey;
  }

  @Override
  public int getCount() {
    return nodeList.size();
  }

  @Override
  public TrustedNode getItem(int position) {
    return nodeList.get(position);
  }

  @Override
  public long getItemId(int position) {
    TrustedNode node = nodeList.get(position);
    return node.getKey().hashCode();
  }

  private static class ViewHolder {
    TextView tvName;
    TextView tvAddress;
    TextView tvStatus;
    TextView tvMode;
    ImageButton ibSelect;
  }

  @Override
  public View getView(int position, View convertView, ViewGroup parent) {
    ViewHolder holder;

    if (convertView == null) {
      convertView = LayoutInflater.from(context).inflate(R.layout.list_item_node, parent, false);
      holder = new ViewHolder();
      holder.tvName = convertView.findViewById(R.id.tv_node_name);
      holder.tvAddress = convertView.findViewById(R.id.tv_node_address);
      holder.tvStatus = convertView.findViewById(R.id.tv_node_status);
      holder.tvMode = convertView.findViewById(R.id.tv_node_mode);
      holder.ibSelect = convertView.findViewById(R.id.ib_select);
      convertView.setTag(holder);
    } else {
      holder = (ViewHolder) convertView.getTag();
    }

    TrustedNode node = getItem(position);
    if (node == null)
      return convertView;

    holder.tvName.setText(node.getName());
    holder.tvAddress.setText(node.getIpAddress() + ":" + node.getPort());

    // ============================================================
    // Статус — по ключу "ip:port"
    // ============================================================
    NodeStatus status = statuses.get(node.getKey());
    if (status == null)
      status = NodeStatus.UNKNOWN;

    String statusText;
    int statusColor;
    switch (status) {
      case ONLINE:
        statusText = "🟢 Доступен";
        statusColor = 0xFF38A169;
        break;
      case REFUSED:
        statusText = "🟡 Сервер не запущен";
        statusColor = 0xFFDD6B20;
        break;
      case UNREACHABLE:
        statusText = "🔴 Недоступен";
        statusColor = 0xFFE53E3E;
        break;
      case CHECKING:
        statusText = "⏳ Проверка...";
        statusColor = 0xFF718096;
        break;
      case UNKNOWN:
      default:
        statusText = "❔ Не проверен";
        statusColor = 0xFFA0AEC0;
        break;
    }

    holder.tvStatus.setText(statusText);
    holder.tvStatus.setTextColor(statusColor);

    // ============================================================
    // Режим
    // ============================================================
    if (holder.tvMode != null) {
      TrustedNode.Mode mode = node.getMode();
      String modeText;
      int modeColor;

      switch (mode) {
        case DIRECT:
          modeText = "Режим: напрямую";
          modeColor = 0xFFA0AEC0;
          break;
        case GATEWAY:
          if (node.hasGateway()) {
            modeText = "Режим: через шлюз (" + node.getGatewayIp() + ":" + node.getGatewayPort()
                + ")";
            modeColor = 0xFF2B6CB0;
          } else {
            modeText = "⚠ Укажите IP шлюза";
            modeColor = 0xFFDD6B20;
          }
          break;
        case WIFI_DIRECT:
          modeText = "Режим: Wi-Fi Direct";
          modeColor = 0xFF6B46C1;
          break;
        case AUTO:
        default:
          modeText = "Режим: авто";
          modeColor = 0xFFA0AEC0;
          break;
      }

      holder.tvMode.setText(modeText);
      holder.tvMode.setTextColor(modeColor);
    }

    // ============================================================
    // ★ НОВОЕ: Галочка выбора узла
    // ============================================================
    boolean isSelected = selectedKey != null && selectedKey.equals(node.getKey());

    holder.ibSelect.setImageResource(
        isSelected ? R.drawable.ic_check : R.drawable.ic_radio_unchecked);

    // Снимаем прежний listener, чтобы не сработал на переиспользованной View.
    holder.ibSelect.setOnClickListener(null);

    final TrustedNode nodeRef = node;
    holder.ibSelect.setOnClickListener(v -> {
      try {
        // Toggle: если этот узел уже выбран — снять выбор.
        // Но по требованию — «при выборе другого — снимает галочку с предыдущего».
        // Простое поведение: клик по галочке ВСЕГДА выбирает этот узел
        // (снимает галочку с предыдущего). Если клик по уже выбранному —
        // ничего не меняется (галочка остаётся).
        if (selectedKey != null && selectedKey.equals(nodeRef.getKey())) {
          // Уже выбран — ничего не делаем.
          return;
        }

        selectedKey = nodeRef.getKey();
        notifyDataSetChanged();

        if (nodeSelectListener != null) {
          nodeSelectListener.onNodeSelected(nodeRef);
        }
      } catch (Exception e) {
        android.util.Log.e("NodeAdapter", "Ошибка выбора узла", e);
      }
    });

    // Клик по строке не перехватываем — обрабатывается
    // ListView.OnItemClickListener в MainActivity.
    convertView.setClickable(false);
    convertView.setFocusable(false);

    return convertView;
  }

  // ============================================================
  // Обновление статусов
  // ============================================================

  public void updateStatus(String nodeKey, NodeStatus status) {
    if (nodeKey == null)
      return;
    NodeStatus current = statuses.get(nodeKey);
    if (current == status)
      return;

    statuses.put(nodeKey, status);
    notifyDataSetChanged();
  }

  public void markAllChecking() {
    for (TrustedNode node : nodeList) {
      statuses.put(node.getKey(), NodeStatus.CHECKING);
    }
    notifyDataSetChanged();
  }

  public void clearStatuses() {
    statuses.clear();
    for (TrustedNode node : nodeList) {
      statuses.put(node.getKey(), NodeStatus.UNKNOWN);
    }
    notifyDataSetChanged();
  }

  public void replaceAll(List<TrustedNode> nodes) {
    nodeList.clear();
    if (nodes != null) {
      nodeList.addAll(nodes);
    }
    statuses.clear();
    for (TrustedNode node : nodeList) {
      statuses.put(node.getKey(), NodeStatus.UNKNOWN);
    }

    // ★ Если выбранный узел исчез из списка — сбрасываем.
    if (selectedKey != null) {
      boolean stillPresent = false;
      for (TrustedNode n : nodeList) {
        if (n.getKey().equals(selectedKey)) {
          stillPresent = true;
          break;
        }
      }
      if (!stillPresent) {
        selectedKey = null;
      }
    }

    notifyDataSetChanged();
  }

  public void clear() {
    nodeList.clear();
    statuses.clear();
    selectedKey = null;
    notifyDataSetChanged();
  }

  public void addAll(List<TrustedNode> nodes) {
    if (nodes == null) return;
    nodeList.addAll(nodes);
    for (TrustedNode node : nodes) {
      if (!statuses.containsKey(node.getKey())) {
        statuses.put(node.getKey(), NodeStatus.UNKNOWN);
      }
    }
    notifyDataSetChanged();
  }

  public void updateNode(TrustedNode updatedNode) {
    if (updatedNode == null) return;
    for (int i = 0; i < nodeList.size(); i++) {
      if (nodeList.get(i).getKey().equals(updatedNode.getKey())) {
        nodeList.set(i, updatedNode);
        notifyDataSetChanged();
        return;
      }
    }
  }
}