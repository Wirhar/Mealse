package com.my.mealse.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.my.mealse.R;
import com.my.mealse.util.WifiDirectManager.Peer;

import java.util.ArrayList;
import java.util.List;

/**
 * Адаптер для списка Wi-Fi Direct-пиров.
 * Ключ строки — deviceAddress (MAC). Дублей быть не должно —
 * WifiP2pManager отдаёт уникальные адреса.
 */
public class WifiDirectPeerAdapter extends BaseAdapter {

  private final Context context;
  private final List<Peer> peers = new ArrayList<>();

  public WifiDirectPeerAdapter(Context context) {
    this.context = context;
  }

  @Override
  public int getCount() {
    return peers.size();
  }

  @Override
  public Peer getItem(int position) {
    return peers.get(position);
  }

  @Override
  public long getItemId(int position) {
    Peer p = peers.get(position);
    return p != null && p.deviceAddress != null
        ? p.deviceAddress.hashCode()
        : position;
  }

  private static class ViewHolder {
    TextView tvName;
    TextView tvAddress;
    TextView tvStatus;
  }

  @Override
  public View getView(int position, View convertView, ViewGroup parent) {
    ViewHolder holder;
    if (convertView == null) {
      convertView = LayoutInflater.from(context)
          .inflate(R.layout.list_item_wifi_peer, parent, false);
      holder = new ViewHolder();
      holder.tvName = convertView.findViewById(R.id.tv_peer_name);
      holder.tvAddress = convertView.findViewById(R.id.tv_peer_address);
      holder.tvStatus = convertView.findViewById(R.id.tv_peer_status);
      convertView.setTag(holder);
    } else {
      holder = (ViewHolder) convertView.getTag();
    }

    Peer peer = getItem(position);
    if (peer == null) return convertView;

    holder.tvName.setText(peer.deviceName != null ? peer.deviceName : "Неизвестно");
    holder.tvAddress.setText(peer.deviceAddress != null ? peer.deviceAddress : "—");

    String statusText;
    int statusColor;
    if (peer.status == null) {
      statusText = "неизвестно";
      statusColor = 0xFFA0AEC0;
    } else {
      switch (peer.status) {
        case "connected":
          statusText = "🟢 подключён";
          statusColor = 0xFF38A169;
          break;
        case "invited":
          statusText = "🟡 приглашён";
          statusColor = 0xFFDD6B20;
          break;
        case "available":
          statusText = "⚪ доступен";
          statusColor = 0xFF718096;
          break;
        case "failed":
          statusText = "🔴 ошибка";
          statusColor = 0xFFE53E3E;
          break;
        case "unavailable":
          statusText = "🚫 недоступен";
          statusColor = 0xFF718096;
          break;
        default:
          statusText = peer.status;
          statusColor = 0xFFA0AEC0;
          break;
      }
    }
    holder.tvStatus.setText(statusText);
    holder.tvStatus.setTextColor(statusColor);

    return convertView;
  }

  public void replaceAll(List<Peer> newPeers) {
    peers.clear();
    if (newPeers != null) peers.addAll(newPeers);
    notifyDataSetChanged();
  }
}