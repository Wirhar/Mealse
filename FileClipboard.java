package com.my.mealse.filemanager.util;

import com.my.mealse.filemanager.FileEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Глобальный буфер обмена файлами.
 *
 * Не путать с системным ClipboardManager — там текст, картинки и т.п.
 * Здесь — «файловый» буфер для операций Копировать / Переместить / Вставить.
 *
 * Поведение:
 *   - put(entries, move=false)  → «скопировать» набор записей
 *   - put(entries, move=true)   → «вырезать» набор записей
 *   - peek()                    → текущее содержимое
 *   - clear()                   → сбросить буфер
 *   - isEmpty()                 → пусто ли
 *
 * Если буфер не пуст — файловый менеджер показывает в меню «Вставить».
 *
 * После успешной вставки буфер:
 *   - очищается, если это была «операция перемещения» (move=true)
 *   - остаётся, если это было «копирование» (можно вставить несколько раз)
 *
 * Буфер работает в рамках приложения. При повороте экрана
 * (когда Activity пересоздаётся) буфер сохраняется — он статический.
 */
public final class FileClipboard {

    private FileClipboard() {}

    /** Операция, которая была отложена в буфер. */
    public enum Op { COPY, MOVE }

    /** Одна запись в буфере. */
    public static class Item {
        public final FileEntry entry;
        public Item(FileEntry entry) { this.entry = entry; }
    }

    private static volatile Op pendingOp = null;
    private static final List<Item> items = new ArrayList<>();

    // ============================================================
    //  Запись
    // ============================================================

    /**
     * Кладём набор файлов в буфер.
     *
     * @param entries набор (может быть пустой — тогда буфер очищается)
     * @param op      COPY (копировать) или MOVE (переместить)
     */
    public static synchronized void put(List<FileEntry> entries, Op op) {
        items.clear();
        if (entries != null) {
            for (FileEntry e : entries) {
                if (e != null) items.add(new Item(e));
            }
        }
        if (items.isEmpty()) {
            pendingOp = null;
        } else {
            pendingOp = op != null ? op : Op.COPY;
        }
    }

    /**
     * Удобный метод для одиночного файла.
     */
    public static synchronized void put(FileEntry entry, Op op) {
        List<FileEntry> list = new ArrayList<>(1);
        if (entry != null) list.add(entry);
        put(list, op);
    }

    // ============================================================
    //  Чтение
    // ============================================================

    /** Текущая операция (COPY / MOVE / null). */
    public static synchronized Op getOp() {
        return pendingOp;
    }

    /** Есть ли что-то в буфере. */
    public static synchronized boolean isEmpty() {
        return items.isEmpty() || pendingOp == null;
    }

    /** Количество элементов. */
    public static synchronized int size() {
        return items.size();
    }

    /** Список записей (нельзя менять). */
    public static synchronized List<Item> peek() {
        return Collections.unmodifiableList(new ArrayList<>(items));
    }

    /** Удобная выжимка записей без обёртки. */
    public static synchronized List<FileEntry> peekEntries() {
        List<FileEntry> result = new ArrayList<>(items.size());
        for (Item it : items) {
            if (it.entry != null) result.add(it.entry);
        }
        return result;
    }

    /** Первая (обычно единственная при одиночной вставке) запись. */
    public static synchronized FileEntry peekFirst() {
        if (items.isEmpty()) return null;
        return items.get(0).entry;
    }

    // ============================================================
    //  Очистка
    // ============================================================

    /** Полная очистка буфера. */
    public static synchronized void clear() {
        items.clear();
        pendingOp = null;
    }

    /**
     * Помечаем вставку успешной.
     * Для MOVE — очищаем буфер.
     * Для COPY — оставляем, чтобы можно было вставить ещё.
     */
    public static synchronized void onPasteCompleted() {
        if (pendingOp == Op.MOVE) {
            clear();
        }
    }
}