package com.my.mealse.browser.password;

/**
 * Модель пароля.
 *  - title   — короткое название сайта (например, "ВК", "Госуслуги")
 *  - login   — логин
 *  - password — пароль
 *  - url     — URL (опционально, для перехода)
 */
public class PasswordItem {

    public String id;
    public String title;
    public String login;
    public String password;
    public String url;
    public long createdAt;
    public long updatedAt;

    public PasswordItem() {}

    public PasswordItem(String title, String login, String password) {
        this.id = java.util.UUID.randomUUID().toString();
        this.title = title;
        this.login = login;
        this.password = password;
        this.url = "";
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = createdAt;
    }

    public PasswordItem(String title, String login, String password, String url) {
        this(title, login, password);
        this.url = url != null ? url : "";
    }

    public String getDisplayTitle() {
        if (title != null && !title.isEmpty()) return title;
        if (url != null && !url.isEmpty()) return url;
        return "(без названия)";
    }
}