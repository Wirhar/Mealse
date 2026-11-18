package com.my.mealse.messenger.ui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.my.mealse.R;
import com.my.mealse.messenger.model.AttachmentInfo;
import com.my.mealse.messenger.model.Message;
import com.my.mealse.messenger.model.MessageStatus;
import com.my.mealse.messenger.model.MessageType;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Адаптер сообщений.
 *
 * ★ reverse-layout: getItem(0) — САМОЕ НОВОЕ (внизу экрана).
 *   ListView.stackFromBottom = true, adapter.getItem(0) отображается внизу.
 *
 * Два типа view:
 *   VIEW_TYPE_SENT     — свои (senderFingerprint == selfFp)
 *   VIEW_TYPE_RECEIVED — чужие
 *
 * SYSTEM-сообщения рендерятся как RECEIVED с серым цветом.
 *
 * ★ Шаг 16.7: отображение FILE-сообщений (иконка + имя + размер + клик).
 *
 * ★ Улучшение №2 (Glide): превью картинок.
 *   Если mime.startsWith("image/") и файл существует — ImageView
 *   с миниатюрой 60x60 (centerCrop) через Glide. Иконка-эмодзи скрыта.
 *   Для не-картинок — иконка-эмодзи (как раньше).
 *   Glide.clear(thumb) при переиспользовании convertView — иначе
 *   старая картинка останется на новом элементе.
 *
 * ★ Улучшение №3 (прогресс): при status == PENDING и 0 < progress < 100
 *   показываем ProgressBar (горизонтальный, 4dp) + текст «45%» вместо
 *   размера файла. Прогресс читается из SharedPreferences
 *   (mealse_messenger_progress, ключ progress_<messageId>), куда его
 *   пишет DeliveryScheduler из ProgressCallback FileMessageSender.
 *   Обновление в реальном времени — через broadcast
 *   MessengerEvents.ACTION_MESSAGE_PROGRESS (ChatActivity ловит и
 *   вызывает adapter.notifyDataSetChanged()).
 */
public class MessageAdapter extends BaseAdapter {

    private static final int VIEW_TYPE_SENT = 0;
    private static final int VIEW_TYPE_RECEIVED = 1;

    /** Синхронизирован с DeliveryScheduler.PREF_PROGRESS. */
    private static final String PREF_PROGRESS = "mealse_messenger_progress";
    private static final String KEY_PROGRESS_PREFIX = "progress_";

    private final Context context;
    private final String selfFingerprint;
    private final List<Message> items = new ArrayList<>();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());

    public MessageAdapter(Context context, String selfFingerprint) {
        this.context = context;
        this.selfFingerprint = selfFingerprint != null ? selfFingerprint : "";
    }

    // ============================================================
    // Данные
    // ============================================================

    public void replaceAll(List<Message> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    public void addFirst(Message m) {
        if (m == null) return;
        for (Message x : items) {
            if (x.getId().equals(m.getId())) return;
        }
        items.add(0, m);
        notifyDataSetChanged();
    }

    public void updateStatus(String messageId, MessageStatus newStatus) {
        if (messageId == null || newStatus == null) return;
        boolean changed = false;
        for (Message m : items) {
            if (messageId.equals(m.getId())) {
                m.setStatus(newStatus);
                changed = true;
                break;
            }
        }
        if (changed) notifyDataSetChanged();
    }

    public List<Message> getItems() {
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
    public Message getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        Message m = items.get(position);
        return m.getId() != null ? m.getId().hashCode() : position;
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        Message m = items.get(position);
        return isMine(m) ? VIEW_TYPE_SENT : VIEW_TYPE_RECEIVED;
    }

    private boolean isMine(Message m) {
        if (m == null) return false;
        if (m.getType() == MessageType.SYSTEM) return false;
        return selfFingerprint.equals(m.getSenderFingerprint());
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Message m = items.get(position);
        if (isMine(m)) {
            return getSentView(position, convertView, parent, m);
        } else {
            return getReceivedView(position, convertView, parent, m);
        }
    }

    // ============================================================
    // SENT view
    // ============================================================

    private View getSentView(int position, View convertView, ViewGroup parent, Message m) {
        SentHolder h;
        if (convertView == null) {
            convertView = LayoutInflater.from(context)
                .inflate(R.layout.item_message_sent, parent, false);
            h = new SentHolder();
            h.body = convertView.findViewById(R.id.ms_body);
            h.time = convertView.findViewById(R.id.ms_time);
            h.status = convertView.findViewById(R.id.ms_status);
            h.attachment = convertView.findViewById(R.id.ms_attachment);
            h.attachIcon = convertView.findViewById(R.id.ms_attach_icon);
            h.attachThumb = convertView.findViewById(R.id.ms_attach_thumb);
            h.attachName = convertView.findViewById(R.id.ms_attach_name);
            h.attachSize = convertView.findViewById(R.id.ms_attach_size);
            h.attachProgress = convertView.findViewById(R.id.ms_attach_progress);
            convertView.setTag(h);
        } else {
            h = (SentHolder) convertView.getTag();
        }

        bindBody(h.body, h.attachment, h.attachIcon, h.attachThumb,
            h.attachName, h.attachSize, h.attachProgress, m);

        h.time.setText(timeFmt.format(new Date(m.getCreatedAt())));
        h.status.setText(statusIcon(m.getStatus()));
        h.status.setTextColor(statusColor(m.getStatus()));

        return convertView;
    }

    // ============================================================
    // RECEIVED view
    // ============================================================

    private View getReceivedView(int position, View convertView, ViewGroup parent, Message m) {
        ReceivedHolder h;
        if (convertView == null) {
            convertView = LayoutInflater.from(context)
                .inflate(R.layout.item_message_received, parent, false);
            h = new ReceivedHolder();
            h.sender = convertView.findViewById(R.id.mr_sender);
            h.body = convertView.findViewById(R.id.mr_body);
            h.time = convertView.findViewById(R.id.mr_time);
            h.attachment = convertView.findViewById(R.id.mr_attachment);
            h.attachIcon = convertView.findViewById(R.id.mr_attach_icon);
            h.attachThumb = convertView.findViewById(R.id.mr_attach_thumb);
            h.attachName = convertView.findViewById(R.id.mr_attach_name);
            h.attachSize = convertView.findViewById(R.id.mr_attach_size);
            h.attachProgress = convertView.findViewById(R.id.mr_attach_progress);
            convertView.setTag(h);
        } else {
            h = (ReceivedHolder) convertView.getTag();
        }

        boolean isSystem = (m.getType() == MessageType.SYSTEM);
        if (isSystem) {
            h.sender.setVisibility(View.GONE);
            h.body.setText(m.getBody() != null ? m.getBody() : "");
            h.body.setTextColor(0xFF718096);
            h.body.setTextSize(13f);
            h.body.setVisibility(View.VISIBLE);
            h.attachment.setVisibility(View.GONE);
            h.attachment.setOnClickListener(null);
            if (h.attachThumb != null) {
                Glide.with(context).clear(h.attachThumb);
            }
            h.time.setText("");
        } else {
            h.sender.setVisibility(View.GONE);
            h.body.setTextColor(0xFF2D3748);
            h.body.setTextSize(15f);
            bindBody(h.body, h.attachment, h.attachIcon, h.attachThumb,
                h.attachName, h.attachSize, h.attachProgress, m);
            h.time.setText(timeFmt.format(new Date(m.getCreatedAt())));
        }

        return convertView;
    }

    // ============================================================
    // Привязка body / attachment
    // ============================================================

    /**
     * Универсальная привязка: FILE → контейнер вложения, иначе — body.
     *
     * ★ Улучшение №2: для image/* — превью через Glide.
     * ★ Улучшение №3: для status == PENDING && 0 < progress < 100 —
     *   ProgressBar + «NN%» вместо размера.
     */
    private void bindBody(TextView body, LinearLayout attachment,
                           TextView attachIcon, ImageView attachThumb,
                           TextView attachName, TextView attachSize,
                           ProgressBar attachProgress,
                           Message m) {
        if (m.getType() == MessageType.FILE) {
            body.setVisibility(View.GONE);
            attachment.setVisibility(View.VISIBLE);

            String name = m.getAttachmentName();
            if (name == null || name.isEmpty()) {
                name = m.getBody() != null && !m.getBody().isEmpty() ? m.getBody() : "файл";
            }
            attachName.setText(name);

            String mime = m.getAttachmentMime();
            if (mime == null || mime.isEmpty()) {
                mime = AttachmentInfo.guessMimeByName(name);
            }

            long size = m.getAttachmentSize();

            // ★ Улучшение №3: прогресс для PENDING
            int progress = readProgress(m.getId());
            boolean showProgress = (m.getStatus() == MessageStatus.PENDING
                && progress > 0 && progress < 100);

            if (showProgress) {
                attachSize.setText(progress + "%");
                if (attachProgress != null) {
                    attachProgress.setVisibility(View.VISIBLE);
                    attachProgress.setProgress(progress);
                }
            } else {
                attachSize.setText(size > 0 ? AttachmentInfo.formatSize(size) : "");
                if (attachProgress != null) {
                    attachProgress.setVisibility(View.GONE);
                }
            }

            // ★ Улучшение №2: превью для image/*
            boolean isImage = mime != null && mime.startsWith("image/");
            File file = (m.getAttachmentUri() != null && !m.getAttachmentUri().isEmpty())
                ? new File(m.getAttachmentUri()) : null;
            boolean fileExists = file != null && file.exists();

            if (isImage && fileExists && attachThumb != null) {
                attachThumb.setVisibility(View.VISIBLE);
                attachIcon.setVisibility(View.GONE);
                Glide.with(context)
                    .load(file)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .skipMemoryCache(true)
                    .centerCrop()
                    .into(attachThumb);
            } else {
                if (attachThumb != null) {
                    attachThumb.setVisibility(View.GONE);
                    Glide.with(context).clear(attachThumb);
                }
                attachIcon.setVisibility(View.VISIBLE);
                attachIcon.setText(iconForMime(mime, name));
            }

            attachment.setOnClickListener(v -> openAttachment(m));
        } else {
            body.setVisibility(View.VISIBLE);
            body.setText(m.getBody() != null ? m.getBody() : "");
            attachment.setVisibility(View.GONE);
            attachment.setOnClickListener(null);
            if (attachThumb != null) {
                Glide.with(context).clear(attachThumb);
            }
        }
    }

    // ============================================================
    // Прогресс из SharedPreferences
    // ============================================================

    /**
     * Читает progress_<messageId> из SharedPreferences.
     * Возвращает 0, если нет или ошибка.
     */
    private int readProgress(String messageId) {
        if (messageId == null) return 0;
        try {
            SharedPreferences prefs = context.getSharedPreferences(
                PREF_PROGRESS, Context.MODE_PRIVATE);
            return prefs.getInt(KEY_PROGRESS_PREFIX + messageId, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    // ============================================================
    // Открытие вложения
    // ============================================================

    private void openAttachment(Message m) {
        if (m == null) return;
        String path = m.getAttachmentUri();
        if (path == null || path.isEmpty()) {
            toast("Файл недоступен");
            return;
        }
        File f = new File(path);
        if (!f.exists() || !f.isFile()) {
            toast("Файл недоступен: " + f.getName());
            return;
        }

        String mime = m.getAttachmentMime();
        if (mime == null || mime.isEmpty()) {
            mime = AttachmentInfo.guessMimeByName(f.getName());
        }

        try {
            Uri uri = FileProvider.getUriForFile(context,
                context.getPackageName() + ".fileprovider", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast("Нет приложения для открытия " + mime);
        } catch (IllegalArgumentException e) {
            toast("Ошибка FileProvider: " + e.getMessage());
        } catch (Exception e) {
            toast("Не удалось открыть: " + e.getMessage());
        }
    }

    // ============================================================
    // Хелперы
    // ============================================================

    private static String iconForMime(String mime, String fileName) {
        String m = mime;
        if (m == null || m.isEmpty()) {
            m = AttachmentInfo.guessMimeByName(fileName);
        }
        if (m == null) return "📎";
        if (m.startsWith("image/")) return "🖼";
        if (m.startsWith("video/")) return "🎬";
        if (m.startsWith("audio/")) return "🎵";
        if (m.equals("application/pdf")) return "📄";
        if (m.startsWith("text/")) return "📝";
        if (m.contains("zip") || m.contains("rar") || m.contains("7z")
            || m.contains("tar") || m.contains("gzip")) return "📦";
        if (m.startsWith("application/vnd.android.package")) return "📱";
        return "📎";
    }

    private String statusIcon(MessageStatus status) {
        if (status == null) return "";
        switch (status) {
            case PENDING:   return "⏳";
            case SENT:      return "✓";
            case DELIVERED: return "✓✓";
            case READ:      return "✓✓";
            case FAILED:    return "✗";
            default:        return "";
        }
    }

    private int statusColor(MessageStatus status) {
        if (status == null) return 0xFF718096;
        switch (status) {
            case READ:      return 0xFF2B6CB0;
            case FAILED:    return 0xFFE53E3E;
            case PENDING:   return 0xFFDD6B20;
            default:        return 0xFF718096;
        }
    }

    private void toast(String msg) {
        try {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }

    private static class SentHolder {
        TextView body;
        TextView time;
        TextView status;
        LinearLayout attachment;
        TextView attachIcon;
        ImageView attachThumb;
        TextView attachName;
        TextView attachSize;
        ProgressBar attachProgress;
    }

    private static class ReceivedHolder {
        TextView sender;
        TextView body;
        TextView time;
        LinearLayout attachment;
        TextView attachIcon;
        ImageView attachThumb;
        TextView attachName;
        TextView attachSize;
        ProgressBar attachProgress;
    }
}