package com.my.mealse.filemanager.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.AbsoluteSizeSpan;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Toolbar;

import androidx.appcompat.app.AppCompatActivity;
// import androidx.appcompat.widget.Toolbar;
import com.my.mealse.BaseActivity;

import com.my.mealse.R;
import com.my.mealse.util.MealseLogger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Простой текстовый редактор для файлового менеджера Mealse.
 *
 * Функции:
 *   - Просмотр / редактирование .txt / .json / .xml / .md / .log.
 *   - Номера строк (тумблер в меню, персист в SharedPreferences).
 *   - Размер шрифта: глобальный (меню → персист) и per-selection
 *     (только отображение в сессии; в .txt не сохраняется).
 *   - Сохранение / Сохранить как / Переименовать / Поиск.
 *   - Информация о файле.
 *   - Read-only режим через EXTRA_READ_ONLY.
 *
 * Запуск:
 *   MAS_TextEditor.open(context, file)          — редактирование
 *   MAS_TextEditor.view(context, file)          — только чтение
 *   MAS_TextEditor.createNew(context, file)     — создать новый и открыть на редактирование
 */
public class MAS_TextEditor extends BaseActivity {

    private static final String TAG = "TextEditor";
    private static final String EXTRA_PATH = "file_path";
    private static final String EXTRA_READ_ONLY = "read_only";
    /** ★ Дополнительный флаг: «открыть для немедленного ввода». */
    private static final String EXTRA_FOCUS_INPUT = "focus_input";

    private static final int MAX_FILE_SIZE_MB = 5;
    private static final int MAX_CHARS_FOR_LINE_NUMBERS = 200_000;

    // ★ НОВОЕ: SharedPreferences для персиста настроек редактора
    private static final String PREFS = "mealse_editor_prefs";
    private static final String KEY_FONT_SIZE = "font_size_sp";
    private static final String KEY_LINE_NUMBERS = "show_line_numbers";

    private Toolbar toolbar;
    private TextView lineNumbers;
    private EditText content;
    private ScrollView scroll;

    private File currentFile;
    private String originalContent;
    private boolean readOnly;
    private boolean isModified;
    private MealseLogger log;

    // ★ НОВОЕ: состояние редактора
    private SharedPreferences prefs;
    private int currentFontSize = 14;
    private boolean showLineNumbers = true;
    private boolean spanWarnShown = false;

    // ============================================================
    //  Статические методы запуска
    // ============================================================

    public static void open(Context context, File file) {
        Intent i = new Intent(context, MAS_TextEditor.class);
        i.putExtra(EXTRA_PATH, file.getAbsolutePath());
        i.putExtra(EXTRA_READ_ONLY, false);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
    }

    public static void view(Context context, File file) {
        Intent i = new Intent(context, MAS_TextEditor.class);
        i.putExtra(EXTRA_PATH, file.getAbsolutePath());
        i.putExtra(EXTRA_READ_ONLY, true);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
    }

    /**
     * ★ НОВОЕ: открыть только что созданный пустой файл на редактирование
     * и сразу поставить фокус в поле ввода, чтобы пользователь мог печатать.
     * Используется из FileManagerActivity → «Создать текстовый документ».
     */
    public static void createNew(Context context, File file) {
        Intent i = new Intent(context, MAS_TextEditor.class);
        i.putExtra(EXTRA_PATH, file.getAbsolutePath());
        i.putExtra(EXTRA_READ_ONLY, false);
        i.putExtra(EXTRA_FOCUS_INPUT, true);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
    }

    // ============================================================
    //  Lifecycle
    // ============================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_text_editor);

        log = MealseLogger.get(this, TAG);
        log.log("onCreate");

        toolbar = findViewById(R.id.te_toolbar);
        lineNumbers = findViewById(R.id.te_line_numbers);
        content = findViewById(R.id.te_content);
        scroll = findViewById(R.id.te_scroll);

        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        String path = getIntent().getStringExtra(EXTRA_PATH);
        readOnly = getIntent().getBooleanExtra(EXTRA_READ_ONLY, false);
        boolean focusInput = getIntent().getBooleanExtra(EXTRA_FOCUS_INPUT, false);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(readOnly ? "Просмотр" : "Редактор");
        }

        if (path == null || path.isEmpty()) {
            toast("Файл не указан");
            finish();
            return;
        }
        currentFile = new File(path);

        if (readOnly) {
            content.setFocusable(false);
            content.setClickable(false);
            content.setCursorVisible(false);
        } else {
            content.setFocusable(true);
            content.setClickable(true);
            content.setFocusableInTouchMode(true);
        }

        // ★ НОВОЕ: настройки из prefs + синхронизация размеров поля и колонки
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        currentFontSize = prefs.getInt(KEY_FONT_SIZE, 14);
        showLineNumbers = prefs.getBoolean(KEY_LINE_NUMBERS, true);

        content.setTextSize(TypedValue.COMPLEX_UNIT_SP, currentFontSize);
        lineNumbers.setTextSize(TypedValue.COMPLEX_UNIT_SP, currentFontSize);
        applyLineNumbersVisibility();

        content.setHorizontallyScrolling(false);

        loadFile();
        setupWatcher();

        if (getSupportActionBar() != null) {
            getSupportActionBar().setSubtitle(currentFile.getName());
        }

        // ★ Если просили сфокусировать ввод — ставим курсор и показываем клавиатуру
        if (focusInput && !readOnly) {
            content.requestFocus();
            // откладываем, чтобы успел отрисоваться layout
            content.post(() -> {
                try {
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager)
                                    getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.showSoftInput(content,
                                android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                    }
                } catch (Exception e) {
                    log.logError("showSoftInput", e);
                }
            });
            // Мягкий режим: состояние клавиатуры "adjustResize"
            getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                            | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
    }

    private void loadFile() {
        if (currentFile == null || !currentFile.exists()) {
            toast("Файл не найден");
            finish();
            return;
        }
        long size = currentFile.length();
        if (size > MAX_FILE_SIZE_MB * 1024L * 1024L) {
            toast("Файл слишком большой (" + (size / 1024 / 1024) + " МБ)");
            finish();
            return;
        }

        try (BufferedReader r = new BufferedReader(new FileReader(currentFile))) {
            StringBuilder sb = new StringBuilder((int) Math.min(size, 1_000_000));
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            originalContent = sb.toString();
            content.setText(originalContent);
            updateLineNumbers();
            log.log("Загружено " + originalContent.length() + " симв.");
        } catch (IOException e) {
            log.logError("loadFile", e);
            toast("Ошибка чтения: " + e.getMessage());
            finish();
        }
    }

    private void setupWatcher() {
        content.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                if (readOnly) return;
                boolean modified = originalContent != null
                        && !s.toString().equals(originalContent);
                if (modified != isModified) {
                    isModified = modified;
                    updateToolbarSubtitle();
                    invalidateOptionsMenu();
                }
                updateLineNumbers();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void updateLineNumbers() {
        if (lineNumbers == null || content == null) return;
        if (!showLineNumbers) return; // ★ скрыта — не строим строку на каждый keystroke
        String text = content.getText().toString();
        if (text.length() > MAX_CHARS_FOR_LINE_NUMBERS) {
            lineNumbers.setText("...");
            return;
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') lines++;
        }
        StringBuilder sb = new StringBuilder(lines * 3);
        for (int i = 1; i <= lines; i++) sb.append(i).append('\n');
        lineNumbers.setText(sb);
    }

    private void updateToolbarSubtitle() {
        if (getSupportActionBar() == null || currentFile == null) return;
        String name = currentFile.getName() + (isModified ? " •" : "");
        getSupportActionBar().setSubtitle(name);
    }

    // ============================================================
    //  Меню
    // ============================================================

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_text_editor, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem save = menu.findItem(R.id.te_action_save);
        MenuItem saveAs = menu.findItem(R.id.te_action_save_as);
        MenuItem rename = menu.findItem(R.id.te_action_rename);
        if (save != null) save.setVisible(!readOnly);
        if (saveAs != null) saveAs.setVisible(true);
        if (rename != null) rename.setVisible(!readOnly);

        // ★ нумерация: галочка по состоянию
        MenuItem ln = menu.findItem(R.id.te_action_line_numbers);
        if (ln != null) ln.setChecked(showLineNumbers);

        // ★ размер: явно ставим/снимаем галочки ВСЕХ пресетов —
        //   иначе при кастомном размере висит stale-галочка последнего пресета.
        MenuItem fs = menu.findItem(R.id.te_action_font_size);
        if (fs != null && fs.hasSubMenu()) {
            Menu sub = fs.getSubMenu();
            int curId = idForFontSize(currentFontSize);
            for (int i = 0; i < sub.size(); i++) {
                MenuItem mi = sub.getItem(i);
                if (mi.getItemId() == R.id.te_font_custom) continue;
                mi.setChecked(curId != 0 && mi.getItemId() == curId);
            }
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) { tryExit(); return true; }
        if (id == R.id.te_action_save) { saveFile(); return true; }
        if (id == R.id.te_action_search) { askSearch(); return true; }
        if (id == R.id.te_action_info) { showInfo(); return true; }
        if (id == R.id.te_action_save_as) { askSaveAs(); return true; }
        if (id == R.id.te_action_rename) { askRename(); return true; }

        // ★ НОВОЕ — до return super
        if (id == R.id.te_action_line_numbers) { toggleLineNumbers(); return true; }
        if (id == R.id.te_font_custom) { askCustomFontSize(); return true; }
        int sp = fontSizeForId(id);
        if (sp > 0) { applyChosenSize(sp); return true; }

        return super.onOptionsItemSelected(item);
    }

    // ================= Нумерация =================

    private void toggleLineNumbers() {
        showLineNumbers = !showLineNumbers;
        prefs.edit().putBoolean(KEY_LINE_NUMBERS, showLineNumbers).apply();
        applyLineNumbersVisibility();
        invalidateOptionsMenu();
    }

    private void applyLineNumbersVisibility() {
        lineNumbers.setVisibility(showLineNumbers ? View.VISIBLE : View.GONE);
        if (showLineNumbers) updateLineNumbers();
    }

    // ================= Размер шрифта =================

    /** Диспетчер: есть выделение → span на выделение; иначе → глобальный размер. */
    private void applyChosenSize(int sp) {
        if (sp < 8 || sp > 72) { toast("Размер 8–72 sp"); return; }
        if (content.hasSelection()
                && content.getSelectionStart() != content.getSelectionEnd()) {
            applySizeToSelection(sp);
        } else {
            applyGlobalFontSize(sp);
        }
        invalidateOptionsMenu();
    }

    private void applyGlobalFontSize(int sp) {
        currentFontSize = sp;
        content.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        lineNumbers.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        prefs.edit().putInt(KEY_FONT_SIZE, sp).apply();

        // ★ Сброс всех per-selection spans: смена глобального размера
        //   возвращает весь текст к единому кеглю (иначе часть останется старого).
        Editable ed = content.getText();
        AbsoluteSizeSpan[] spans = ed.getSpans(0, ed.length(), AbsoluteSizeSpan.class);
        for (AbsoluteSizeSpan s : spans) ed.removeSpan(s);

        updateLineNumbers();
    }

    /**
     * ★ Размер выделенного текста. Plain-text сохранение spans не пишет —
     * только отображение в сессии (тост один раз).
     */
    private void applySizeToSelection(int sp) {
        int a = content.getSelectionStart(), b = content.getSelectionEnd();
        if (a < 0 || b < 0 || a == b) return;
        if (a > b) { int t = a; a = b; b = t; }
        Editable ed = content.getText();
        // прежние размерные spans в диапазоне убираем (упрощённо — целиком,
        // вложенная тонкая разметка схлопывается)
        AbsoluteSizeSpan[] old = ed.getSpans(a, b, AbsoluteSizeSpan.class);
        for (AbsoluteSizeSpan s : old) ed.removeSpan(s);

        // ★ ФИКС fontScale: AbsoluteSizeSpan(sp, true) — это dp БЕЗ fontScale.
        //   Конвертируем sp→px тем же путём, что setTextSize(SP) —
        //   выделение совпадает с окружающим текстом при любом fontScale.
        int px = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics()));
        ed.setSpan(new AbsoluteSizeSpan(px, false), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        if (!spanWarnShown) {
            spanWarnShown = true;
            toast("Размер выделенного — только на экране; в .txt не сохраняется");
        }
    }

    private void askCustomFontSize() {
        EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(2)});
        input.setText(String.valueOf(currentFontSize));
        input.setSelection(0, input.getText().length());
        new AlertDialog.Builder(this).setTitle("Размер шрифта (8–72)")
                .setView(input)
                .setPositiveButton("OK", (d, w) -> {
                    try {
                        applyChosenSize(Integer.parseInt(input.getText().toString().trim()));
                    } catch (Exception e) { toast("Введите число"); }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private static int fontSizeForId(int id) {
        if (id == R.id.te_font_10) return 10;
        if (id == R.id.te_font_12) return 12;
        if (id == R.id.te_font_14) return 14;
        if (id == R.id.te_font_16) return 16;
        if (id == R.id.te_font_18) return 18;
        if (id == R.id.te_font_20) return 20;
        if (id == R.id.te_font_24) return 24;
        if (id == R.id.te_font_28) return 28;
        return -1;
    }

    private static int idForFontSize(int sp) {
        switch (sp) {
            case 10: return R.id.te_font_10;
            case 12: return R.id.te_font_12;
            case 14: return R.id.te_font_14;
            case 16: return R.id.te_font_16;
            case 18: return R.id.te_font_18;
            case 20: return R.id.te_font_20;
            case 24: return R.id.te_font_24;
            case 28: return R.id.te_font_28;
            default: return 0;
        }
    }

    // ============================================================
    //  Сохранение / переименование
    // ============================================================

    private void saveFile() {
        if (readOnly || currentFile == null) return;
        try (FileWriter w = new FileWriter(currentFile)) {
            w.write(content.getText().toString());
            originalContent = content.getText().toString();
            isModified = false;
            updateToolbarSubtitle();
            toast("✅ Сохранено");
        } catch (IOException e) {
            log.logError("saveFile", e);
            toast("❌ Ошибка: " + e.getMessage());
        }
    }

    private void askSaveAs() {
        EditText input = new EditText(this);
        input.setText(currentFile != null ? currentFile.getName() : "new.txt");
        input.setSelection(0, input.getText().length());

        new AlertDialog.Builder(this)
                .setTitle("Сохранить как")
                .setView(input)
                .setPositiveButton("Сохранить", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) { toast("Введите имя"); return; }
                    if (!name.contains(".")) name += ".txt";
                    performSaveAs(name);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void performSaveAs(String newName) {
        File parent = currentFile != null && currentFile.getParentFile() != null
                ? currentFile.getParentFile()
                : getFilesDir();
        File dst = new File(parent, newName);
        if (dst.exists()) {
            toast("Файл уже существует");
            return;
        }
        try (FileWriter w = new FileWriter(dst)) {
            w.write(content.getText().toString());
            currentFile = dst;
            originalContent = content.getText().toString();
            isModified = false;
            updateToolbarSubtitle();
            toast("✅ Сохранено: " + newName);
        } catch (IOException e) {
            toast("❌ Ошибка: " + e.getMessage());
        }
    }

    private void askRename() {
        if (readOnly || currentFile == null) return;
        EditText input = new EditText(this);
        input.setText(currentFile.getName());

        new AlertDialog.Builder(this)
                .setTitle("Переименовать")
                .setView(input)
                .setPositiveButton("ОК", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    File dst = new File(currentFile.getParentFile(), name);
                    if (dst.exists()) { toast("Уже существует"); return; }
                    if (currentFile.renameTo(dst)) {
                        currentFile = dst;
                        updateToolbarSubtitle();
                        toast("✅ Переименовано");
                    } else {
                        toast("❌ Не удалось");
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    // ============================================================
    //  Поиск / инфо
    // ============================================================

    private void askSearch() {
        EditText input = new EditText(this);
        input.setHint("Что искать?");

        new AlertDialog.Builder(this)
                .setTitle("Поиск")
                .setView(input)
                .setPositiveButton("Найти", (d, w) -> {
                    String q = input.getText().toString();
                    if (q.isEmpty()) return;
                    findInText(q);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void findInText(String q) {
        String text = content.getText().toString();
        int idx = text.toLowerCase().indexOf(q.toLowerCase());
        if (idx < 0) { toast("Не найдено"); return; }
        content.requestFocus();
        content.setSelection(idx, idx + q.length());
    }

    private void showInfo() {
        if (currentFile == null) return;
        long size = currentFile.length();
        String sizeStr = size < 1024 ? size + " B"
                : size < 1024 * 1024 ? (size / 1024) + " KB"
                : String.format(Locale.US, "%.2f MB", size / (1024.0 * 1024.0));

        String info = "📄 " + currentFile.getName() + "\n"
                + "📁 " + currentFile.getAbsolutePath() + "\n"
                + "💾 " + sizeStr + "\n"
                + "📅 " + new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
                        .format(new Date(currentFile.lastModified())) + "\n"
                + "🔧 " + (readOnly ? "Просмотр" : "Редактирование")
                + (isModified ? "\n⚠ Есть несохранённые изменения" : "");

        new AlertDialog.Builder(this)
                .setTitle("Информация")
                .setMessage(info)
                .setPositiveButton("OK", null)
                .show();
    }

    // ============================================================
    //  Выход
    // ============================================================

    private void tryExit() {
        if (!readOnly && isModified) {
            new AlertDialog.Builder(this)
                    .setTitle("Несохранённые изменения")
                    .setMessage("Сохранить перед выходом?")
                    .setPositiveButton("Сохранить", (d, w) -> {
                        saveFile();
                        finish();
                    })
                    .setNegativeButton("Не сохранять", (d, w) -> finish())
                    .setNeutralButton("Отмена", null)
                    .show();
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        tryExit();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}