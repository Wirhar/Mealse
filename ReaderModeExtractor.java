package com.my.mealse.browser;

import android.webkit.WebView;

/**
 * Reader Mode — извлечение основного контента через JS.
 *
 * Логика:
 *  1. Ищем в DOM <article>, <main>, [role="main"], .article, .post.
 *  2. Если нет — берём <body> и удаляем всё «шумное»: nav, header, footer,
 *     aside, .ad, .banner, .popup, iframe, script (кроме разрешённых).
 *  3. Возвращаем HTML через @JavascriptInterface.
 */
public class ReaderModeExtractor {

    public interface Callback {
        void onExtracted(String title, String html);
        void onError(String error);
    }

    private final WebView webView;
    private Callback callback;

    public ReaderModeExtractor(WebView webView) {
        this.webView = webView;
    }

    public void extract(Callback cb) {
        if (webView == null) {
            if (cb != null) cb.onError("WebView == null");
            return;
        }
        this.callback = cb;

        webView.addJavascriptInterface(new Bridge(), "MealseReaderBridge");

        String js =
                "(function(){" +
                        "  try {" +
                        "    var article = document.querySelector('article')" +
                        "      || document.querySelector('main')" +
                        "      || document.querySelector('[role=\"main\"]')" +
                        "      || document.querySelector('.article')" +
                        "      || document.querySelector('.post')" +
                        "      || document.body;" +
                        "    if (!article) { MealseReaderBridge.onError('Контент не найден'); return; }" +
                        "    var clone = article.cloneNode(true);" +
                        "    // Удаляем шум" +
                        "    var noisy = ['nav','header','footer','aside','iframe','script'," +
                        "      'noscript','style','form'];" +
                        "    for (var i = 0; i < noisy.length; i++) {" +
                        "      var els = clone.querySelectorAll(noisy[i]);" +
                        "      for (var j = els.length - 1; j >= 0; j--) els[j].remove();" +
                        "    }" +
                        "    var adSelectors = ['.ad','.ads','.banner','.popup','.share'," +
                        "      '.comment','.comments','[class*=advert]','[id*=advert]'," +
                        "      '[class*=banner]','[id*=banner]'];" +
                        "    for (var k = 0; k < adSelectors.length; k++) {" +
                        "      var ads = clone.querySelectorAll(adSelectors[k]);" +
                        "      for (var m = ads.length - 1; m >= 0; m--) ads[m].remove();" +
                        "    }" +
                        "    var title = document.title || '';" +
                        "    var html = clone.innerHTML || '';" +
                        "    MealseReaderBridge.onExtracted(title, html);" +
                        "  } catch(e) {" +
                        "    MealseReaderBridge.onError(String(e));" +
                        "  }" +
                        "})();";

        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    public class Bridge {
        @android.webkit.JavascriptInterface
        public void onExtracted(String title, String html) {
            if (callback != null) callback.onExtracted(title, html);
        }
        @android.webkit.JavascriptInterface
        public void onError(String error) {
            if (callback != null) callback.onError(error);
        }
    }
}