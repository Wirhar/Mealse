package com.my.mealse.filemanager.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.widget.Toolbar;
import android.widget.ViewFlipper;

import androidx.core.content.FileProvider;

import com.my.mealse.BaseActivity;
import com.my.mealse.R;
import com.my.mealse.util.MealseLogger;

import java.io.File;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Просмотр изображений свайпом — БЕЗ ViewPager2 и FragmentStateAdapter.
 *
 * ★ FIX 2026-11-01:
 *  - Быстрое перелистывание → нет OOM (RGB_565, inSampleSize до 1280, отмена задач,
 *    освобождение Bitmap у ушедших страниц, debounce 250 мс).
 *
 * ★ NEW 2026-11-01: pinch-to-zoom.
 *  - ScaleGestureDetector обрабатывает onScale (два пальца).
 *  - Один палец: если scale > 1 — панорамирование; если scale == 1 — свайп.
 *  - Двойной тап — сброс/установка масштаба 2.0.
 *  - При смене страницы масштаб сбрасывается.
 */
public class ImagePreviewActivity extends BaseActivity {

    private static final String TAG = "ImagePreview";
    private static final String EXTRA_PATHS = "image_paths";
    private static final String EXTRA_START = "start_position";

    private static final int MAX_BITMAP_SIDE = 1280;
    private static final long SWIPE_DEBOUNCE_MS = 250;
    private static final float MIN_SCALE = 1.0f;
    private static final float MAX_SCALE = 5.0f;

    private MealseLogger log;
    private Toolbar toolbar;
    private ViewFlipper viewFlipper;
    private ProgressBar loading;

    private final ArrayList<String> paths = new ArrayList<>();
    private int currentPosition;

    private GestureDetector gestureDetector;
    private ScaleGestureDetector scaleDetector;

    private final ExecutorService loader = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());

    private long lastSwipeTime = 0L;
    private Future<?> currentLoadTask;

    // ★ NEW: состояние масштабирования текущей страницы
    private float currentScale = 1.0f;
    private float currentTransX = 0f;
    private float currentTransY = 0f;
    private float baseTransX = 0f;   // translation в момент начала pan
    private float baseTransY = 0f;
    private float lastTouchX = 0f;
    private float lastTouchY = 0f;
    private boolean isScaling = false;

    // ============================================================
    // Фабричный метод запуска
    // ============================================================

    public static void start(Activity from, ArrayList<String> imagePaths, int startPos) {
        Intent intent = new Intent(from, ImagePreviewActivity.class);
        intent.putStringArrayListExtra(EXTRA_PATHS, imagePaths);
        intent.putExtra(EXTRA_START, startPos);
        from.startActivity(intent);
    }

    // ============================================================
    // Lifecycle
    // ============================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_preview);

        log = MealseLogger.get(this, TAG);
        log.log("onCreate");

        ArrayList<String> input = getIntent().getStringArrayListExtra(EXTRA_PATHS);
        if (input != null) paths.addAll(input);
        currentPosition = getIntent().getIntExtra(EXTRA_START, 0);
        if (currentPosition < 0 || currentPosition >= paths.size()) currentPosition = 0;

        if (paths.isEmpty()) {
            finish();
            return;
        }

        toolbar = findViewById(R.id.ip_toolbar);
        viewFlipper = findViewById(R.id.ip_flipper);
        loading = findViewById(R.id.ip_loading);

        toolbar.setTitle(buildTitle(currentPosition));
        toolbar.setNavigationIcon(android.R.drawable.ic_media_previous);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_image_preview);
        toolbar.setOnMenuItemClickListener(this::onToolbarMenuItem);

        // ★ ScaleGestureDetector — два пальца
        scaleDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector detector) {
                        isScaling = true;
                        return true;
                    }

                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        ImageView iv = getCurrentImageView();
                        if (iv == null) return false;

                        float factor = detector.getScaleFactor();
                        float newScale = currentScale * factor;
                        if (newScale < MIN_SCALE) newScale = MIN_SCALE;
                        if (newScale > MAX_SCALE) newScale = MAX_SCALE;

                        currentScale = newScale;
                        iv.setScaleX(currentScale);
                        iv.setScaleY(currentScale);

                        // корректируем translation, чтобы зум был вокруг точки фокуса
                        float focusX = detector.getFocusX();
                        float focusY = detector.getFocusY();
                        // насколько изображение «уехало» из-за зума вокруг центра ImageView
                        float dx = (iv.getWidth() / 2f - focusX) * (currentScale - 1f);
                        float dy = (iv.getHeight() / 2f - focusY) * (currentScale - 1f);
                        currentTransX = baseTransX + dx;
                        currentTransY = baseTransY + dy;
                        clampTranslation(iv);
                        iv.setTranslationX(currentTransX);
                        iv.setTranslationY(currentTransY);
                        return true;
                    }

                    @Override
                    public void onScaleEnd(ScaleGestureDetector detector) {
                        isScaling = false;
                        baseTransX = currentTransX;
                        baseTransY = currentTransY;

                        // если масштаб вернулся к 1 — сбрасываем translation
                        if (currentScale <= MIN_SCALE + 0.01f) {
                            resetZoom();
                        }
                    }
                });

        // ★ GestureDetector — свайпы, двойной тап
        gestureDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent e) {
                        lastTouchX = e.getX();
                        lastTouchY = e.getY();
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        ImageView iv = getCurrentImageView();
                        if (iv == null) return false;
                        if (currentScale > 1.05f) {
                            resetZoom();
                        } else {
                            currentScale = 2.0f;
                            iv.setScaleX(currentScale);
                            iv.setScaleY(currentScale);
                            baseTransX = 0f;
                            baseTransY = 0f;
                            currentTransX = 0f;
                            currentTransY = 0f;
                            iv.setTranslationX(0f);
                            iv.setTranslationY(0f);
                        }
                        return true;
                    }

                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2,
                                           float velocityX, float velocityY) {
                        // ★ FIX: не листаем, если идёт масштабирование или зум > 1
                        if (isScaling || currentScale > 1.05f) return false;
                        if (e1 == null || e2 == null) return false;
                        float dx = e2.getX() - e1.getX();
                        float dy = e2.getY() - e1.getY();
                        if (Math.abs(dy) > Math.abs(dx)) return false;
                        if (Math.abs(dx) < 50) return false;
                        if (Math.abs(velocityX) < 100) return false;

                        if (dx < 0) showNext();
                        else showPrevious();
                        return true;
                    }
                });

        buildPages();
        viewFlipper.setDisplayedChild(currentPosition);
        updateTitle();

        log.log("onCreate END: paths=" + paths.size() + ", start=" + currentPosition);
    }

    @Override
    protected void onDestroy() {
        cancelCurrentLoad();
        try {
            loader.shutdownNow();
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    // ============================================================
    // ★ Утилиты масштабирования
    // ============================================================

    private ImageView getCurrentImageView() {
        try {
            View page = viewFlipper.getChildAt(currentPosition);
            if (page == null) return null;
            return page.findViewWithTag("image");
        } catch (Throwable t) {
            return null;
        }
    }

    private void resetZoom() {
        currentScale = 1.0f;
        currentTransX = 0f;
        currentTransY = 0f;
        baseTransX = 0f;
        baseTransY = 0f;
        ImageView iv = getCurrentImageView();
        if (iv != null) {
            iv.setScaleX(1f);
            iv.setScaleY(1f);
            iv.setTranslationX(0f);
            iv.setTranslationY(0f);
        }
    }

    /**
     * Не даём изображению «уехать» за границы экрана.
     */
    private void clampTranslation(ImageView iv) {
        if (iv == null) return;
        int w = iv.getWidth();
        int h = iv.getHeight();
        if (w == 0 || h == 0) return;

        float maxX = Math.max(0f, (w * (currentScale - 1f)) / 2f);
        float maxY = Math.max(0f, (h * (currentScale - 1f)) / 2f);

        if (currentTransX > maxX) currentTransX = maxX;
        if (currentTransX < -maxX) currentTransX = -maxX;
        if (currentTransY > maxY) currentTransY = maxY;
        if (currentTransY < -maxY) currentTransY = -maxY;
    }

    // ============================================================
    // ViewFlipper: страницы + загрузка
    // ============================================================

    private void buildPages() {
        viewFlipper.removeAllViews();
        for (int i = 0; i < paths.size(); i++) {
            View page = createPage(paths.get(i), i);
            viewFlipper.addView(page, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    private View createPage(String path, int index) {
        FrameLayout page = new FrameLayout(this);
        page.setClickable(true);
        page.setFocusable(true);
        page.setFocusableInTouchMode(true);

        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        iv.setTag("image");
        iv.setClickable(true);
        iv.setFocusable(true);

        ProgressBar pb = new ProgressBar(this);
        FrameLayout.LayoutParams pbLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER);
        pb.setLayoutParams(pbLp);
        pb.setTag("progress");

        page.addView(iv);
        page.addView(pb);

        // ★ onTouch: сначала scale, потом gesture
        View.OnTouchListener touchListener = (v, event) -> {
            // Запоминаем точку начала для панорамирования
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                baseTransX = currentTransX;
                baseTransY = currentTransY;
            }

            // Если идёт масштабирование — не отдаём событие в gestureDetector
            if (scaleDetector != null) {
                scaleDetector.onTouchEvent(event);
                if (scaleDetector.isInProgress()) {
                    return true;
                }
            }

            // Панорамирование при scale > 1 (один палец)
            if (!isScaling && currentScale > 1.05f && event.getPointerCount() == 1) {
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    float dx = event.getX() - lastTouchX;
                    float dy = event.getY() - lastTouchY;
                    currentTransX = baseTransX + dx;
                    currentTransY = baseTransY + dy;
                    ImageView iv2 = getCurrentImageView();
                    clampTranslation(iv2);
                    if (iv2 != null) {
                        iv2.setTranslationX(currentTransX);
                        iv2.setTranslationY(currentTransY);
                    }
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    baseTransX = currentTransX;
                    baseTransY = currentTransY;
                    return true;
                }
            }

            // Свайпы / двойной тап
            if (gestureDetector != null) {
                gestureDetector.onTouchEvent(event);
            }
            return true;
        };

        page.setOnTouchListener(touchListener);
        iv.setOnTouchListener(touchListener);

        // Грузим только видимую страницу, остальные — по требованию
        if (index == currentPosition) {
            loadImageAsync(path, iv, pb);
        } else {
            pb.setVisibility(View.GONE);
        }

        return page;
    }

    private void loadImageAsync(String path, ImageView iv, ProgressBar pb) {
        if (iv == null) return;
        if (pb != null) pb.setVisibility(View.VISIBLE);

        cancelCurrentLoad();

        final WeakReference<ImageView> ivRef = new WeakReference<>(iv);
        final WeakReference<ProgressBar> pbRef = new WeakReference<>(pb);
        final AtomicBoolean cancelled = new AtomicBoolean(false);

        currentLoadTask = loader.submit(() -> {
            Bitmap bmp;
            try {
                bmp = decodeSampled(path);
            } catch (OutOfMemoryError oom) {
                log.logError("OOM при декодировании " + path, oom);
                bmp = null;
            } catch (Throwable t) {
                log.logError("decodeSampled", t);
                bmp = null;
            }

            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                if (bmp != null && !bmp.isRecycled()) bmp.recycle();
                return;
            }

            final Bitmap finalBmp = bmp;
            ui.post(() -> {
                if (cancelled.get()) {
                    if (finalBmp != null && !finalBmp.isRecycled()) finalBmp.recycle();
                    return;
                }
                ImageView view = ivRef.get();
                ProgressBar pbView = pbRef.get();
                if (view == null) {
                    if (finalBmp != null && !finalBmp.isRecycled()) finalBmp.recycle();
                    return;
                }
                try {
                    view.setImageDrawable(null);
                } catch (Throwable ignored) {}

                if (finalBmp != null && !finalBmp.isRecycled()) {
                    try {
                        view.setImageBitmap(finalBmp);
                    } catch (Throwable t) {
                        log.logError("setImageBitmap", t);
                    }
                } else {
                    view.setImageResource(android.R.drawable.ic_menu_gallery);
                }
                // ★ Сброс масштаба для только что загруженной страницы
                view.setScaleX(1f);
                view.setScaleY(1f);
                view.setTranslationX(0f);
                view.setTranslationY(0f);
                if (pbView != null) pbView.setVisibility(View.GONE);
            });
        });
    }

    private void cancelCurrentLoad() {
        if (currentLoadTask != null && !currentLoadTask.isDone()) {
            currentLoadTask.cancel(true);
        }
        currentLoadTask = null;
    }

    private void releasePageBitmap(int index) {
        try {
            View page = viewFlipper.getChildAt(index);
            if (page == null) return;
            ImageView iv = page.findViewWithTag("image");
            if (iv != null) {
                iv.setImageDrawable(null);
                iv.setScaleX(1f);
                iv.setScaleY(1f);
                iv.setTranslationX(0f);
                iv.setTranslationY(0f);
            }
        } catch (Throwable ignored) {}
    }

    private void ensurePageLoaded(int index) {
        try {
            View page = viewFlipper.getChildAt(index);
            if (page == null) return;
            ImageView iv = page.findViewWithTag("image");
            ProgressBar pb = page.findViewWithTag("progress");
            if (iv != null && iv.getDrawable() == null) {
                loadImageAsync(paths.get(index), iv, pb);
            }
        } catch (Throwable t) {
            log.logError("ensurePageLoaded", t);
        }
    }

    private static Bitmap decodeSampled(String path) {
        if (path == null) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        try {
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, opts);
            int w = opts.outWidth, h = opts.outHeight;
            if (w <= 0 || h <= 0) return null;

            int maxSide = Math.max(w, h);
            int sample = 1;
            while (maxSide / sample > MAX_BITMAP_SIDE) sample *= 2;

            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(path, opts);
        } catch (OutOfMemoryError oom) {
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ============================================================
    // Навигация между страницами
    // ============================================================

    private void showNext() {
        if (paths.size() <= 1) return;
        if (currentPosition >= paths.size() - 1) {
            toast("Последнее изображение");
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSwipeTime < SWIPE_DEBOUNCE_MS) return;
        lastSwipeTime = now;

        int oldPos = currentPosition;
        currentPosition++;
        viewFlipper.showNext();
        updateTitle();

        releasePageBitmap(oldPos);
        ensurePageLoaded(currentPosition);
        resetZoom();   // ★ сброс масштаба на новой странице

        log.log("showNext → " + currentPosition);
    }

    private void showPrevious() {
        if (paths.size() <= 1) return;
        if (currentPosition <= 0) {
            toast("Первое изображение");
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSwipeTime < SWIPE_DEBOUNCE_MS) return;
        lastSwipeTime = now;

        int oldPos = currentPosition;
        currentPosition--;
        viewFlipper.showPrevious();
        updateTitle();

        releasePageBitmap(oldPos);
        ensurePageLoaded(currentPosition);
        resetZoom();

        log.log("showPrevious → " + currentPosition);
    }

    private void updateTitle() {
        if (toolbar != null) toolbar.setTitle(buildTitle(currentPosition));
    }

    private String buildTitle(int pos) {
        return (pos + 1) + " / " + paths.size();
    }

    // ============================================================
    // Меню Toolbar
    // ============================================================

    private boolean onToolbarMenuItem(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.ip_action_delete) { confirmDelete(); return true; }
        if (id == R.id.ip_action_share) { share(); return true; }
        if (id == R.id.ip_action_info) { showInfo(); return true; }
        if (id == R.id.ip_action_copy_path) { copyPath(); return true; }
        if (id == R.id.ip_action_open_with) { openWith(); return true; }
        return false;
    }

    // ============================================================
    // Действия
    // ============================================================

    private void confirmDelete() {
        if (currentPosition < 0 || currentPosition >= paths.size()) return;
        File f = new File(paths.get(currentPosition));

        new AlertDialog.Builder(this).setTitle("Удалить?")
                .setMessage(f.getName() + "\n\nФайл будет удалён безвозвратно.")
                .setPositiveButton("Удалить", (d, w) -> doDelete(f))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void doDelete(File f) {
        if (f == null || !f.delete()) {
            toast("❌ Не удалось удалить");
            return;
        }
        cancelCurrentLoad();
        viewFlipper.removeViewAt(currentPosition);
        paths.remove(currentPosition);

        if (paths.isEmpty()) {
            setResultAndFinish();
            return;
        }

        if (currentPosition >= paths.size()) {
            currentPosition = paths.size() - 1;
        }

        viewFlipper.setDisplayedChild(currentPosition);
        updateTitle();
        ensurePageLoaded(currentPosition);
        resetZoom();
        toast("✅ Удалено");
    }

    private void share() {
        try {
            File f = new File(paths.get(currentPosition));
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("image/*");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Поделиться"));
        } catch (Exception e) {
            toast("Ошибка: " + e.getMessage());
        }
    }

    private void showInfo() {
        File f = new File(paths.get(currentPosition));
        long size = f.length();
        String sizeStr;
        if (size < 1024) sizeStr = size + " B";
        else if (size < 1024 * 1024) sizeStr = (size / 1024) + " KB";
        else sizeStr = String.format(Locale.US, "%.2f MB", size / (1024.0 * 1024.0));

        String info = "📄 Имя: " + f.getName() + "\n" +
                "📁 Путь: " + f.getAbsolutePath() + "\n" +
                "💾 Размер: " + sizeStr + "\n" +
                "📅 Изменён: " + new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
                        .format(new Date(f.lastModified()));

        new AlertDialog.Builder(this).setTitle("Информация")
                .setMessage(info).setPositiveButton("OK", null).show();
    }

    private void copyPath() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("path", paths.get(currentPosition)));
            toast("📋 Путь скопирован");
        }
    }

    private void openWith() {
        try {
            File f = new File(paths.get(currentPosition));
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "image/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Открыть с помощью"));
        } catch (Exception e) {
            toast("Нет приложения для открытия");
        }
    }

    // ============================================================
    // Возврат RESULT_OK
    // ============================================================

    private void setResultAndFinish() {
        Intent result = new Intent();
        result.putStringArrayListExtra(EXTRA_PATHS, new ArrayList<>(paths));
        setResult(RESULT_OK, result);
        finish();
    }

    @Override
    public void finish() {
        Intent result = new Intent();
        result.putStringArrayListExtra(EXTRA_PATHS, new ArrayList<>(paths));
        setResult(RESULT_OK, result);
        super.finish();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item != null && item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}