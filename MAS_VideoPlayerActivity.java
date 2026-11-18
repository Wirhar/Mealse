package com.my.mealse.filemanager.viewer;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.my.mealse.BaseActivity;
import com.my.mealse.R;
import com.my.mealse.filemanager.view.AspectRatioSurfaceView;
import com.my.mealse.util.MealseLogger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Видеоплеер Mealse.
 *
 * ★ NEW 2026-11-01:
 *  - Список видео (EXTRA_PATHS + EXTRA_START), свайп влево/вправо переключает.
 *  - Мини-превью при перемотке (SeekBar + ImageView над ним).
 *  - Короткий тап по видео — play/pause (GestureDetector).
 *  - Двойной тап — fullscreen.
 *
 * ★ FIX 2026-11-01: после сворачивания видео не запускалось.
 *  - Убран releasePlayer() из onStop(); MediaPlayer живёт до onDestroy.
 *  - onPause(): pausePlayback() + запоминаем позицию.
 *  - onResume(): если surfaceReady и isPrepared — resumePlayback().
 *  - onDestroy(): releasePlayer().
 *  - surfaceDestroyed() — только при реальном уничтожении surface (поворот),
 *    там releasePlayer() корректен, но pendingPath сохраняет текущую позицию.
 */
public class MAS_VideoPlayerActivity extends BaseActivity {

    private static final String TAG = "VideoPlayer";
    private static final int HIDE_DELAY_MS = 3000;
    private static final int SEEK_PREVIEW_CACHE = 20;

    private static final String EXTRA_PATH = "file_path";
    private static final String EXTRA_PATHS = "video_paths";
    private static final String EXTRA_START = "video_start";

    // UI
    private AspectRatioSurfaceView surface;
    private ProgressBar loading;
    private View controls;
    private ImageButton btnBack, btnPlayPause, btnFullscreen, btnCenterPlay, btnPrev, btnNext;
    private SeekBar seekBar;
    private TextView tvCurrent, tvTotal, tvTitle;
    private ImageView seekPreview;   // ★ мини-превью при перемотке
    private View seekPreviewContainer;

    // Media
    private MediaPlayer player;
    private boolean isPrepared = false;
    private boolean isPlaying = false;
    private boolean isPaused = false;
    private boolean isSeeking = false;
    private boolean isFullscreen = false;
    private boolean surfaceReady = false;
    private String pendingPath;
    private int pendingSeekMs = 0;   // ★ для восстановления позиции после сворачивания

    // ★ Плейлист
    private final ArrayList<String> playlist = new ArrayList<>();
    private int currentIndex = 0;

    // Handlers
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable hideControls;
    private Runnable updateProgress;

    private MealseLogger log;

    // ★ Превью-кадры
    private final ExecutorService thumbExecutor = Executors.newFixedThreadPool(2);
    private final Map<Long, Bitmap> seekPreviewCache =
            new LinkedHashMap<Long, Bitmap>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Bitmap> eldest) {
                    if (size() > SEEK_PREVIEW_CACHE) {
                        Bitmap b = eldest.getValue();
                        if (b != null && !b.isRecycled()) b.recycle();
                        return true;
                    }
                    return false;
                }
            };
    private Future<?> lastPreviewTask;

    private GestureDetector gestureDetector;

    // ============================================================
    //  Lifecycle
    // ============================================================

    public static void start(android.content.Context context, String path) {
        ArrayList<String> list = new ArrayList<>();
        list.add(path);
        start(context, list, 0);
    }

    public static void start(android.content.Context context,
                             ArrayList<String> paths, int startIndex) {
        if (paths == null || paths.isEmpty()) return;
        if (startIndex < 0 || startIndex >= paths.size()) startIndex = 0;
        Intent i = new Intent(context, MAS_VideoPlayerActivity.class);
        i.putStringArrayListExtra(EXTRA_PATHS, paths);
        i.putExtra(EXTRA_START, startIndex);
        i.putExtra(EXTRA_PATH, paths.get(startIndex));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_video_player);

        log = MealseLogger.get(this, TAG);
        log.log("onCreate");

        // ★ Плейлист
        ArrayList<String> input = getIntent().getStringArrayListExtra(EXTRA_PATHS);
        if (input != null && !input.isEmpty()) {
            playlist.addAll(input);
            currentIndex = getIntent().getIntExtra(EXTRA_START, 0);
            if (currentIndex < 0 || currentIndex >= playlist.size()) currentIndex = 0;
        } else {
            String single = getIntent().getStringExtra(EXTRA_PATH);
            if (single != null) {
                playlist.add(single);
                currentIndex = 0;
            }
        }
        if (playlist.isEmpty()) {
            toast("Файл не указан");
            finish();
            return;
        }

        initViews();
        setupListeners();

        pendingPath = playlist.get(currentIndex);
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                surfaceReady = true;
                if (pendingPath != null) {
                    String p = pendingPath;
                    pendingPath = null;
                    playVideo(p);
                }
            }
            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
                if (player != null && isPrepared) {
                    int vw = player.getVideoWidth();
                    int vh = player.getVideoHeight();
                    if (vw > 0 && vh > 0) surface.setAspectRatio(vw, vh);
                }
            }
            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                // ★ Surface разрушается при повороте экрана — сохраняем позицию
                if (isPrepared && player != null) {
                    try { pendingSeekMs = player.getCurrentPosition(); }
                    catch (IllegalStateException ignored) {}
                }
                surfaceReady = false;
                releasePlayer();
                // ★ После поворота перезапустим тот же файл с сохранённой позицией
                if (!isFinishing() && !isDestroyed()) {
                    pendingPath = playlist.get(currentIndex);
                }
            }
        });
    }

    private void initViews() {
        surface = findViewById(R.id.vp_surface);
        loading = findViewById(R.id.vp_loading);
        controls = findViewById(R.id.vp_controls);
        btnBack = findViewById(R.id.vp_btn_back);
        btnPlayPause = findViewById(R.id.vp_btn_play_pause);
        btnFullscreen = findViewById(R.id.vp_btn_fullscreen);
        btnCenterPlay = findViewById(R.id.vp_center_play);
        btnPrev = findViewById(R.id.vp_btn_prev);
        btnNext = findViewById(R.id.vp_btn_next);
        seekBar = findViewById(R.id.vp_seekbar);
        tvCurrent = findViewById(R.id.vp_time_current);
        tvTotal = findViewById(R.id.vp_time_total);
        tvTitle = findViewById(R.id.vp_title);
        seekPreview = findViewById(R.id.vp_seek_preview);
        seekPreviewContainer = findViewById(R.id.vp_seek_preview_container);
    }

    private void setupListeners() {
        btnBack.setOnClickListener(v -> finish());
        btnPlayPause.setOnClickListener(v -> togglePlayPause());
        btnCenterPlay.setOnClickListener(v -> togglePlayPause());
        btnFullscreen.setOnClickListener(v -> toggleFullscreen());
        if (btnPrev != null) btnPrev.setOnClickListener(v -> playPrev());
        if (btnNext != null) btnNext.setOnClickListener(v -> playNext());

        // ★ Тап по видео: короткий — play/pause, свайп влево/вправо — переключение
        gestureDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(MotionEvent e) { return true; }

                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent e) {
                        togglePlayPause();
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        toggleFullscreen();
                        return true;
                    }

                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2,
                                           float vx, float vy) {
                        if (e1 == null || e2 == null) return false;
                        float dx = e2.getX() - e1.getX();
                        float dy = e2.getY() - e1.getY();
                        if (Math.abs(dy) > Math.abs(dx)) return false;
                        if (Math.abs(dx) < 120) return false;
                        if (Math.abs(vx) < 200) return false;
                        if (dx < 0) playNext();
                        else playPrev();
                        return true;
                    }
                });

        surface.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return true;
        });

        // ★ Контролы не перехватывают тап — пропускаем на surface
        if (controls != null) {
            controls.setClickable(false);
            controls.setFocusable(false);
        }

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && player != null && isPrepared) {
                    int dur = player.getDuration();
                    int pos = (int) ((progress / 100.0) * dur);
                    tvCurrent.setText(formatTime(pos));
                    // ★ Показываем превью
                    showSeekPreview(pos);
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                isSeeking = true;
                showControls();
                if (seekPreviewContainer != null)
                    seekPreviewContainer.setVisibility(View.VISIBLE);
            }
            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                isSeeking = false;
                if (player != null && isPrepared) {
                    int dur = player.getDuration();
                    int pos = (int) ((sb.getProgress() / 100.0) * dur);
                    player.seekTo(pos);
                    pendingSeekMs = pos;
                }
                if (seekPreviewContainer != null)
                    seekPreviewContainer.setVisibility(View.GONE);
                startHideTimer();
            }
        });

        hideControls = () -> { if (isPlaying) hideControlsBar(); };
        updateProgress = new Runnable() {
            @Override public void run() {
                updateProgressInternal();
                if (isPlaying && !isSeeking) ui.postDelayed(this, 500);
            }
        };
    }

    // ============================================================
    //  Превью при перемотке
    // ============================================================

    private void showSeekPreview(int positionMs) {
        if (seekPreview == null || player == null) return;
        final String path = playlist.get(currentIndex);
        final int pos = positionMs;

        // Кэш
        Bitmap cached = seekPreviewCache.get((long) pos);
        if (cached != null && !cached.isRecycled()) {
            seekPreview.setImageBitmap(cached);
            return;
        }

        if (lastPreviewTask != null && !lastPreviewTask.isDone())
            lastPreviewTask.cancel(true);

        lastPreviewTask = thumbExecutor.submit(() -> {
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            Bitmap frame = null;
            try {
                r.setDataSource(path);
                frame = r.getFrameAtTime(pos * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            } catch (Throwable t) {
                // ignore
            } finally {
                try { r.release(); } catch (Throwable ignored) {}
            }

            if (frame == null || Thread.currentThread().isInterrupted())
                return;

            // Уменьшаем до 160px по большей стороне
            Bitmap small = frame;
            int maxSide = Math.max(frame.getWidth(), frame.getHeight());
            if (maxSide > 160) {
                float k = 160f / maxSide;
                try {
                    small = Bitmap.createScaledBitmap(frame,
                            Math.max(1, (int) (frame.getWidth() * k)),
                            Math.max(1, (int) (frame.getHeight() * k)), true);
                } catch (Throwable ignored) {}
                if (small != frame && !frame.isRecycled()) frame.recycle();
            }

            final Bitmap finalSmall = small;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    if (finalSmall != null && !finalSmall.isRecycled())
                        finalSmall.recycle();
                    return;
                }
                if (finalSmall != null && !finalSmall.isRecycled()) {
                    seekPreviewCache.put((long) pos, finalSmall);
                    seekPreview.setImageBitmap(finalSmall);
                }
            });
        });
    }

    // ============================================================
    //  MediaPlayer
    // ============================================================

    private void playVideo(String path) {
        if (!surfaceReady) { pendingPath = path; return; }

        releasePlayer();
        loading.setVisibility(View.VISIBLE);

        try {
            player = new MediaPlayer();
            player.setSurface(surface.getHolder().getSurface());
            player.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);

            Uri uri = resolveUri(path);
            if (uri != null) player.setDataSource(this, uri);
            else player.setDataSource(path);

            final int seekTo = pendingSeekMs;
            pendingSeekMs = 0;

            player.setOnPreparedListener(mp -> {
                isPrepared = true;
                loading.setVisibility(View.GONE);
                onPrepared(seekTo);
            });

            player.setOnCompletionListener(mp -> {
                isPlaying = false;
                isPaused = false;
                updatePlayPauseIcon();
                showControls();
                // ★ Автопереход на следующее видео, если есть
                if (currentIndex < playlist.size() - 1) {
                    ui.postDelayed(this::playNext, 300);
                }
            });

            player.setOnErrorListener((mp, what, extra) -> {
                loading.setVisibility(View.GONE);
                log.logError("MediaPlayer error what=" + what + " extra=" + extra, null);
                toast("Ошибка воспроизведения (" + what + ")");
                return true;
            });

            player.setOnInfoListener((mp, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    loading.setVisibility(View.VISIBLE);
                } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END
                        || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    loading.setVisibility(View.GONE);
                }
                return false;
            });

            player.prepareAsync();

            File f = new File(path);
            tvTitle.setText(f.getName());
            updatePrevNextButtons();

        } catch (IOException | IllegalArgumentException e) {
            loading.setVisibility(View.GONE);
            log.logError("prepare failed", e);
            toast("Ошибка: " + e.getMessage());
        }
    }

    private Uri resolveUri(String path) {
        if (path == null) return null;
        if (path.startsWith("content://")) return Uri.parse(path);
        if (path.startsWith("file://")) {
            try {
                return FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", new File(path.substring(7)));
            } catch (Exception e) { return null; }
        }
        try {
            return FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", new File(path));
        } catch (Exception e) { return null; }
    }

    private void onPrepared(int seekToMs) {
        int vw = player.getVideoWidth();
        int vh = player.getVideoHeight();
        if (vw > 0 && vh > 0) surface.setAspectRatio(vw, vh);

        int dur = player.getDuration();
        tvTotal.setText(formatTime(dur));
        seekBar.setProgress(0);
        seekBar.setMax(100);

        if (seekToMs > 0) {
            try { player.seekTo(seekToMs); } catch (IllegalStateException ignored) {}
            seekBar.setProgress((int) ((seekToMs / (float) dur) * 100));
        }

        startPlayback();
    }

    private void startPlayback() {
        if (player == null || !isPrepared) return;
        player.start();
        isPlaying = true;
        isPaused = false;
        updatePlayPauseIcon();
        ui.post(updateProgress);
        startHideTimer();
    }

    private void pausePlayback() {
        if (player == null || !isPlaying) return;
        player.pause();
        isPlaying = false;
        isPaused = true;
        updatePlayPauseIcon();
        showControls();
    }

    private void resumePlayback() {
        if (player == null || !isPrepared || !isPaused) return;
        player.start();
        isPlaying = true;
        isPaused = false;
        updatePlayPauseIcon();
        ui.post(updateProgress);
        startHideTimer();
    }

    private void togglePlayPause() {
        if (isPlaying) pausePlayback();
        else if (isPaused) resumePlayback();
        else startPlayback();
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.release(); } catch (Throwable ignored) {}
            player = null;
        }
        isPrepared = false;
        isPlaying = false;
        isPaused = false;
        ui.removeCallbacks(updateProgress);
        ui.removeCallbacks(hideControls);
        if (loading != null) loading.setVisibility(View.GONE);
    }

    private void updateProgressInternal() {
        if (player == null || !isPrepared || isSeeking) return;
        try {
            int pos = player.getCurrentPosition();
            int dur = player.getDuration();
            if (dur > 0) {
                seekBar.setProgress((int) ((pos / (float) dur) * 100));
                tvCurrent.setText(formatTime(pos));
            }
        } catch (IllegalStateException ignored) {}
    }

    private void updatePlayPauseIcon() {
        int res = isPlaying
                ? android.R.drawable.ic_media_pause
                : android.R.drawable.ic_media_play;
        if (btnPlayPause != null) btnPlayPause.setImageResource(res);
        if (btnCenterPlay != null) {
            btnCenterPlay.setImageResource(res);
            btnCenterPlay.setVisibility(isPlaying ? View.GONE : View.VISIBLE);
        }
    }

    // ============================================================
    //  Плейлист: следующее / предыдущее
    // ============================================================

    private void playNext() {
        if (currentIndex < playlist.size() - 1) {
            currentIndex++;
            pendingSeekMs = 0;
            playVideo(playlist.get(currentIndex));
            toast("Следующее видео (" + (currentIndex + 1) + "/" + playlist.size() + ")");
        } else {
            toast("Последнее видео");
        }
    }

    private void playPrev() {
        if (currentIndex > 0) {
            currentIndex--;
            pendingSeekMs = 0;
            playVideo(playlist.get(currentIndex));
            toast("Предыдущее видео (" + (currentIndex + 1) + "/" + playlist.size() + ")");
        } else {
            toast("Первое видео");
        }
    }

    private void updatePrevNextButtons() {
        if (btnPrev != null) btnPrev.setEnabled(currentIndex > 0);
        if (btnNext != null) btnNext.setEnabled(currentIndex < playlist.size() - 1);
    }

    // ============================================================
    //  Контролы
    // ============================================================

    private void showControls() {
        controls.setVisibility(View.VISIBLE);
        updatePlayPauseIcon();
        startHideTimer();
    }
    private void hideControlsBar() { controls.setVisibility(View.GONE); }
    private void toggleControls() {
        if (controls.getVisibility() == View.VISIBLE) hideControlsBar();
        else showControls();
    }
    private void startHideTimer() {
        ui.removeCallbacks(hideControls);
        if (isPlaying) ui.postDelayed(hideControls, HIDE_DELAY_MS);
    }

    private void toggleFullscreen() {
        isFullscreen = !isFullscreen;
        if (isFullscreen) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        showControls();
    }

    private String formatTime(int ms) {
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ============================================================
    //  Lifecycle — ИСПРАВЛЕНО
    // ============================================================

    @Override
    protected void onPause() {
        super.onPause();
        if (isPlaying) pausePlayback();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // ★ Ключевое исправление: возобновляем, если player готов
        if (isPrepared && isPaused && surfaceReady && player != null) {
            resumePlayback();
        } else if (!surfaceReady && pendingPath != null) {
            // ждём surfaceCreated
        }
    }

    // ★ УДАЛЕНО: onStop() с releasePlayer()
    // Player живёт до onDestroy; pause в onPause достаточно.

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releasePlayer();
        ui.removeCallbacksAndMessages(null);
        try { thumbExecutor.shutdownNow(); } catch (Throwable ignored) {}
        synchronized (seekPreviewCache) {
            Iterator<Map.Entry<Long, Bitmap>> it = seekPreviewCache.entrySet().iterator();
            while (it.hasNext()) {
                Bitmap b = it.next().getValue();
                if (b != null && !b.isRecycled()) b.recycle();
                it.remove();
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
                togglePlayPause(); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (player != null && isPrepared)
                    player.seekTo(Math.max(0, player.getCurrentPosition() - 5000));
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (player != null && isPrepared)
                    player.seekTo(Math.min(player.getDuration(),
                            player.getCurrentPosition() + 5000));
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (isFullscreen) { toggleFullscreen(); return true; }
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        showControls();
    }
}