package com.my.mealse.messenger.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.my.mealse.R;
import com.my.mealse.messenger.model.Conversation;
import com.my.mealse.messenger.model.ConversationType;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Адаптер списка диалогов.
 *
 * Показывает: аватар (инициал), название, время последнего сообщения,
 * превью, badge непрочитанных.
 *
 * ★ Работает с ListView (как NodeAdapter, HistoryAdapter) —
 *   консистентно с проектом.
 */
public class ChatsAdapter extends BaseAdapter {

    private final Context context;
    private final List<Conversation> items = new ArrayList<>();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("dd.MM", Locale.getDefault());

    public ChatsAdapter(Context context) {
        this.context = context;
    }

    // ============================================================
    // Данные
    // ============================================================

    public void replaceAll(List<Conversation> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    public void updateConversation(Conversation c) {
        if (c == null) return;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getId().equals(c.getId())) {
                items.set(i, c);
                notifyDataSetChanged();
                return;
            }
        }
    }

    public List<Conversation> getItems() {
        return new ArrayList<>(items);
    }

    // ============================================================
    // BaseAdapter
    // ============================================================

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Conversation getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        Conversation c = items.get(position);
        return c.getId() != null ? c.getId().hashCode() : position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder h;
        if (convertView == null) {
            convertView = LayoutInflater.from(context)
                .inflate(R.layout.item_conversation, parent, false);
            h = new ViewHolder();
            h.avatar = convertView.findViewById(R.id.ic_avatar);
            h.title = convertView.findViewById(R.id.ic_title);
            h.time = convertView.findViewById(R.id.ic_time);
            h.preview = convertView.findViewById(R.id.ic_preview);
            h.badge = convertView.findViewById(R.id.ic_badge);
            convertView.setTag(h);
        } else {
            h = (ViewHolder) convertView.getTag();
        }

        Conversation c = items.get(position);

        // Заголовок
        h.title.setText(c.getDisplayTitle());

        // Инициал
        String initial = extractInitial(c.getDisplayTitle(), c.getType());
        h.avatar.setText(initial);

        // Цвет аватара в зависимости от типа
        int bgColor;
        if (c.getType() == ConversationType.GROUP) {
            bgColor = 0xFF6B46C1;   // фиолетовый
        } else if (c.getType() == ConversationType.CALL) {
            bgColor = 0xFF2B6CB0;   // синий
        } else {
            bgColor = 0xFF38A169;   // зелёный
        }
        h.avatar.setBackgroundColor(bgColor);

        // Превью
        String preview = c.getLastMessagePreview();
        if (preview == null || preview.isEmpty()) {
            h.preview.setText("Нет сообщений");
            h.preview.setTextColor(0xFFA0AEC0);
        } else {
            h.preview.setText(preview);
            h.preview.setTextColor(0xFF718096);
        }

        // Время
        if (c.getLastMessageAt() > 0) {
            h.time.setText(formatTime(c.getLastMessageAt()));
        } else {
            h.time.setText("");
        }

        // Badge
        if (c.getUnreadCount() > 0) {
            h.badge.setVisibility(View.VISIBLE);
            h.badge.setText(c.getUnreadCount() > 99 ? "99+" : String.valueOf(c.getUnreadCount()));
        } else {
            h.badge.setVisibility(View.GONE);
        }

        return convertView;
    }

    // ============================================================
    // Хелперы
    // ============================================================

    private static String extractInitial(String title, ConversationType type) {
        if (title == null || title.isEmpty()) return "?";
        if (type == ConversationType.GROUP) {
            // Для группы — «Г» (группа), чтобы отличать
            return "Г";
        }
        if (type == ConversationType.CALL) {
            return "☎";
        }
        // Первая буква/цифра
        String trimmed = title.trim();
        if (trimmed.isEmpty()) return "?";
        char first = trimmed.charAt(0);
        return String.valueOf(Character.toUpperCase(first));
    }

    private String formatTime(long ts) {
        if (ts <= 0) return "";
        Calendar now = Calendar.getInstance();
        Calendar msg = Calendar.getInstance();
        msg.setTimeInMillis(ts);

        boolean sameDay = now.get(Calendar.YEAR) == msg.get(Calendar.YEAR)
            && now.get(Calendar.DAY_OF_YEAR) == msg.get(Calendar.DAY_OF_YEAR);

        if (sameDay) {
            return timeFmt.format(new Date(ts));
        }

        // Вчера?
        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        boolean isYesterday = yesterday.get(Calendar.YEAR) == msg.get(Calendar.YEAR)
            && yesterday.get(Calendar.DAY_OF_YEAR) == msg.get(Calendar.DAY_OF_YEAR);
        if (isYesterday) return "вчера";

        // В этом году — dd.MM, иначе dd.MM.yy
        if (now.get(Calendar.YEAR) == msg.get(Calendar.YEAR)) {
            return dateFmt.format(new Date(ts));
        }
        return new SimpleDateFormat("dd.MM.yy", Locale.getDefault()).format(new Date(ts));
    }

    private static class ViewHolder {
        TextView avatar;
        TextView title;
        TextView time;
        TextView preview;
        TextView badge;
    }
}