package com.my.mealse.browser.history;

/**
 * Модель закладки (сохранённого сайта).
 */
public class BookmarkItem {

    public String id;
    public String title;
    public String url;
    public long createdAt;

    public BookmarkItem() {}

    public BookmarkItem(String title, String url) {
        this.id = java.util.UUID.randomUUID().toString();
        this.title = title;
        this.url = url;
        this.createdAt = System.currentTimeMillis();
    }

    public String getShortTitle() {
        if (title != null && !title.isEmpty() && !title.equals(url)) {
            if (title.length() > 60) return title.substring(0, 57) + "...";
            return title;
        }
        String d = extractDomain(url);
        return d.isEmpty() ? url : d;
    }

    public String getShortUrl() {
        if (url == null) return "";
        if (url.length() > 60) return url.substring(0, 57) + "...";
        return url;
    }

    private static String extractDomain(String u) {
        try {
            android.net.Uri uri = android.net.Uri.parse(u);
            String host = uri.getHost();
            if (host == null) return "";
            return host.replaceFirst("^www\\.", "");
        } catch (Throwable t) {
            return "";
        }
    }
}