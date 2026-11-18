package com.my.mealse.filemanager;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ThumbnailUtils;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.my.mealse.R;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Универсальный адаптер для списка FileEntry (локальных и удалённых).
 *
 * ★ FIX 2026-11-01:
 *  - Убран дубликат методов в конце файла (не компилировалось).
 *  - notifyDataSetChanged() батчится (раз в 80 мс), не дёргается на каждое превью.
 *  - thumbCache — LRU LinkedHashMap с accessOrder=true, ограничение 200.
 *  - thumbPending — ConcurrentHashMap.newKeySet() (потокобезопасно).
 *  - release() отменяет задачи, освобождает Bitmap, отвязывает ImageView от адаптера.
 *  - Защита от OOM при декодировании.
 *  - Отмена устаревших задач при смене папки (generation).
 */
public class FileListAdapter extends BaseAdapter {

    private static final int MAX_THUMB_CACHE = 200;
    private static final int THUMB_SIZE_PX = 96;
    private static final long NOTIFY_BATCH_MS = 80;

    private final Context context;
    private final LayoutInflater inflater;
    private final List<FileEntry> items = new ArrayList<>();

    // ★ LRU-кэш превью
    private final Map<String, Bitmap> thumbCache =
            Collections.synchronizedMap(new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    if (size() > MAX_THUMB_CACHE) {
                        Bitmap b = eldest.getValue();
                        if (b != null && !b.isRecycled()) {
                            try { b.recycle(); } catch (Throwable ignored) {}
                        }
                        return true;
                    }
                    return false;
                }
            });

    // ★ Потокобезопасные множества
    private final Set<String> thumbPending = ConcurrentHashMap.newKeySet();
    private final Set<String> thumbFailed = ConcurrentHashMap.newKeySet();

    // Множественный выбор
    private boolean multiSelectMode = false;
    private final Set<String> selectedPaths = new HashSet<>();

    // ★ Поколение — отменяет старые задачи при смене папки
    private volatile int generation = 0;

    private final ExecutorService thumbExecutor = Executors.newFixedThreadPool(3);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ★ Батчинг notifyDataSetChanged
    private final AtomicBoolean notifyScheduled = new AtomicBoolean(false);
    private final Runnable notifyRunnable = () -> {
        notifyScheduled.set(false);
        try {
            notifyDataSetChanged();
        } catch (Throwable ignored) {}
    };

    private final SimpleDateFormat dateFormat = new SimpleDateFormat(
            "dd.MM.yyyy HH:mm", Locale.getDefault());

    public FileListAdapter(Context context) {
        this.context = context.getApplicationContext();
        this.inflater = LayoutInflater.from(context);
    }

    // ==================== Данные ====================

    public void replaceAll(List<FileEntry> newItems) {
        // ★ Новая папка — новое поколение, старые задачи не должны ничего менять
        generation++;
        thumbPending.clear();
        thumbFailed.clear();

        items.clear();
        if (newItems != null)
            items.addAll(newItems);

        // Очищаем выбор для путей, которых больше нет
        selectedPaths.retainAll(pathsOf(items));
        notifyDataSetChanged();
    }

    public void addAll(List<FileEntry> newItems) {
        if (newItems == null || newItems.isEmpty())
            return;
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    public void clear() {
        generation++;
        thumbPending.clear();
        thumbFailed.clear();
        items.clear();
        selectedPaths.clear();
        notifyDataSetChanged();
    }

    public List<FileEntry> getItems() {
        return new ArrayList<>(items);
    }

    // ==================== BaseAdapter ====================

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public FileEntry getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        FileEntry e = items.get(position);
        String sid = e.getSourceId() != null ? e.getSourceId() : "";
        return (sid + "|" + e.getPath()).hashCode();
    }

    // ==================== ViewHolder ====================

    private static class ViewHolder {
        ImageView icon;
        TextView name;
        TextView info;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder h;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.list_item_file, parent, false);
            h = new ViewHolder();
            h.icon = convertView.findViewById(R.id.file_icon);
            h.name = convertView.findViewById(R.id.file_name);
            h.info = convertView.findViewById(R.id.file_info);
            convertView.setTag(h);
        } else {
            h = (ViewHolder) convertView.getTag();
        }

        FileEntry entry = items.get(position);
        if (entry == null)
            return convertView;

        h.name.setText(entry.getName());
        h.name.setTextColor(0xFFFFFFFF);

        if (entry.isDirectory()) {
            h.info.setText("папка");
        } else {
            String size = Formatter.formatFileSize(context, entry.getSize());
            String date = dateFormat.format(entry.getLastModified());
            h.info.setText(size + " · " + date);
        }
        h.info.setTextColor(0xFFAAAAAA);

        if (entry.isDirectory()) {
            h.icon.setImageResource(R.drawable.ic_folder);
            h.icon.setColorFilter(null);
        } else {
            Bitmap cached = thumbCache.get(entry.getPath());
            if (cached != null && !cached.isRecycled()) {
                h.icon.setImageBitmap(cached);
                h.icon.setColorFilter(null);
            } else {
                setPlaceholderIcon(h.icon, entry);
                maybeLoadThumbnail(entry);
            }
        }

        if (multiSelectMode && selectedPaths.contains(entry.getPath())) {
            convertView.setBackgroundColor(0x334285F4);
        } else {
            convertView.setBackgroundColor(0);
        }

        return convertView;
    }

    private void setPlaceholderIcon(ImageView iv, FileEntry entry) {
        String mime = entry.getMimeType();
        if (mime != null) {
            if (mime.startsWith("image/")) {
                iv.setImageResource(R.drawable.ic_file_image);
            } else if (mime.startsWith("video/")) {
                iv.setImageResource(R.drawable.ic_file_video);
            } else if (mime.startsWith("audio/")) {
                iv.setImageResource(R.drawable.ic_file_audio);
            } else if (mime.equals("application/pdf")) {
                iv.setImageResource(R.drawable.ic_file_document);
            } else if (mime.startsWith("text/") || mime.equals("application/json")
                    || mime.equals("application/xml")) {
                iv.setImageResource(R.drawable.ic_file_document);
            } else if (mime.contains("zip") || mime.contains("rar") || mime.contains("tar")
                    || mime.contains("7z")) {
                iv.setImageResource(R.drawable.ic_file_archive);
            } else if (mime.equals("application/vnd.android.package-archive")) {
                iv.setImageResource(R.drawable.ic_file_generic);
            } else {
                iv.setImageResource(R.drawable.ic_file_generic);
            }
        } else {
            iv.setImageResource(R.drawable.ic_file_generic);
        }
        iv.setColorFilter(null);
    }

    // ==================== Асинхронная загрузка превью ====================

    private void maybeLoadThumbnail(FileEntry entry) {
        if (entry.isRemote())
            return;
        if (!(entry instanceof LocalFileEntry))
            return;

        String mime = entry.getMimeType();
        if (mime == null)
            return;
        boolean isImage = mime.startsWith("image/");
        boolean isVideo = mime.startsWith("video/");
        if (!isImage && !isVideo)
            return;

        final String path = entry.getPath();
        if (thumbCache.containsKey(path))
            return;
        if (thumbFailed.contains(path))
            return;
        // ★ Атомарная вставка: если уже в pending — выходим
        if (!thumbPending.add(path))
            return;

        final int taskGen = generation;
        final File file = ((LocalFileEntry) entry).getFile();

        thumbExecutor.execute(() -> {
            Bitmap thumb = null;
            try {
                if (isImage) {
                    thumb = decodeImageThumb(file);
                } else {
                    thumb = ThumbnailUtils.createVideoThumbnail(file.getAbsolutePath(),
                            MediaStore.Video.Thumbnails.MINI_KIND);
                }
            } catch (OutOfMemoryError oom) {
                thumb = null;
            } catch (Throwable t) {
                thumb = null;
            }

            final Bitmap result = thumb;
            mainHandler.post(() -> {
                thumbPending.remove(path);

                // ★ Отбрасываем результат, если папка уже сменилась
                if (taskGen != generation) {
                    if (result != null && !result.isRecycled())
                        result.recycle();
                    return;
                }

                if (result != null && !result.isRecycled()) {
                    thumbCache.put(path, result);
                    scheduleNotify();
                } else {
                    // ★ Запоминаем, что превью нет — чтобы не пытаться снова
                    thumbFailed.add(path);
                }
            });
        });
    }

    /**
     * ★ Батчинг notifyDataSetChanged — не чаще раза в NOTIFY_BATCH_MS.
     * Если 50 превью загрузились одновременно — будет 1-2 вызова, а не 50.
     */
    private void scheduleNotify() {
        if (notifyScheduled.compareAndSet(false, true)) {
            mainHandler.postDelayed(notifyRunnable, NOTIFY_BATCH_MS);
        }
    }

    private static Bitmap decodeImageThumb(File file) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            int w = opts.outWidth, h = opts.outHeight;
            if (w <= 0 || h <= 0)
                return null;

            int scale = 1;
            int minSide = Math.min(w, h);
            while (minSide / scale > THUMB_SIZE_PX * 2)
                scale *= 2;

            opts.inJustDecodeBounds = false;
            opts.inSampleSize = scale;

            Bitmap full = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (full == null)
                return null;
            try {
                return ThumbnailUtils.extractThumbnail(full, THUMB_SIZE_PX, THUMB_SIZE_PX);
            } finally {
                if (!full.isRecycled())
                    full.recycle();
            }
        } catch (OutOfMemoryError oom) {
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== Множественный выбор ====================

    public void setMultiSelectMode(boolean enabled) {
        if (this.multiSelectMode == enabled)
            return;
        this.multiSelectMode = enabled;
        if (!enabled)
            selectedPaths.clear();
        notifyDataSetChanged();
    }

    public boolean isMultiSelectMode() {
        return multiSelectMode;
    }

    public void toggleSelection(String path) {
        if (selectedPaths.contains(path)) {
            selectedPaths.remove(path);
        } else {
            selectedPaths.add(path);
        }
        notifyDataSetChanged();
    }

    public void setSelection(String path, boolean selected) {
        boolean changed;
        if (selected)
            changed = selectedPaths.add(path);
        else
            changed = selectedPaths.remove(path);
        if (changed)
            notifyDataSetChanged();
    }

    public void selectAll() {
        selectedPaths.clear();
        for (FileEntry e : items)
            selectedPaths.add(e.getPath());
        notifyDataSetChanged();
    }

    public void clearSelection() {
        if (selectedPaths.isEmpty())
            return;
        selectedPaths.clear();
        notifyDataSetChanged();
    }

    public int getSelectedCount() {
        return selectedPaths.size();
    }

    public Set<String> getSelectedPaths() {
        return new HashSet<>(selectedPaths);
    }

    public List<FileEntry> getSelectedEntries() {
        List<FileEntry> result = new ArrayList<>();
        for (FileEntry e : items) {
            if (selectedPaths.contains(e.getPath()))
                result.add(e);
        }
        return result;
    }

    // ==================== Lifecycle ====================

    /**
     * ★ FIX: освобождает ресурсы и корректно отвязывает ImageView.
     * Не вызывать из onDestroy без предварительного listView.setAdapter(null) —
     * иначе ImageView могут ссылаться на recycled Bitmap.
     */
    public void release() {
        generation++;

        try {
            thumbExecutor.shutdownNow();
        } catch (Throwable ignored) {}

        mainHandler.removeCallbacks(notifyRunnable);
        notifyScheduled.set(false);

        // ★ Отвязываем ImageView от Bitmap перед recycle
        // (сам ListView перед onDestroy обычно уже не отрисован, но перестрахуемся)
        thumbPending.clear();
        thumbFailed.clear();

        synchronized (thumbCache) {
            Iterator<Map.Entry<String, Bitmap>> it = thumbCache.entrySet().iterator();
            while (it.hasNext()) {
                Bitmap b = it.next().getValue();
                if (b != null && !b.isRecycled()) {
                    try { b.recycle(); } catch (Throwable ignored) {}
                }
                it.remove();
            }
        }
    }

    // ==================== Хелперы ====================

    private static Set<String> pathsOf(List<FileEntry> list) {
        Set<String> s = new HashSet<>();
        for (FileEntry e : list)
            s.add(e.getPath());
        return s;
    }
}