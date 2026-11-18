package com.my.mealse.filemanager;

/**
 * Типы сортировки списка файлов.
 *
 * Двойной критерий: сначала папки/файлы, потом выбранный критерий.
 * Это стандартное поведение всех файловых менеджеров.
 */
public enum FileSortType {

    NAME_ASC("По имени (А-Я)", true),
    NAME_DESC("По имени (Я-А)", true),
    DATE_ASC("По дате (старые)", false),
    DATE_DESC("По дате (новые)", false),
    SIZE_ASC("По размеру (мелкие)", false),
    SIZE_DESC("По размеру (крупные)", false);

    private final String displayName;
    private final boolean dirsFirst;

    FileSortType(String displayName, boolean dirsFirst) {
        this.displayName = displayName;
        this.dirsFirst = dirsFirst;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isDirsFirst() {
        return dirsFirst;
    }

    public static FileSortType fromName(String name) {
        if (name == null) return NAME_ASC;
        for (FileSortType t : values()) {
            if (t.name().equals(name)) return t;
        }
        return NAME_ASC;
    }
}