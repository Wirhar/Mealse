package com.my.mealse.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Валидатор YAML-конфига для hev-socks5-tunnel.
 *
 * ★ РАСШИРЕНО:
 *  - repair(): перезаписывает невалидный YAML эталонным содержимым,
 *    сохраняя старую версию в .bak.
 *  - ensureValid(): комбинирует validate + repair.
 *  - isCanonical(): проверяет, что файл совпадает с эталоном.
 */
public final class YamlValidator {

  private static final String TAG = "YamlValidator";
  private static final String BACKUP_SUFFIX = ".bak";

  public static class Result {
    public final boolean valid;
    public final String message;
    public final Map<String, String> values;
    public final boolean wasRepaired;

    public Result(boolean valid, String message,
                  Map<String, String> values, boolean wasRepaired) {
      this.valid = valid;
      this.message = message;
      this.values = values;
      this.wasRepaired = wasRepaired;
    }
  }

  private YamlValidator() {}

  // ============================================================
  //  Валидация (базовая — без ремонта)
  // ============================================================

  public static Result validate(File yamlFile) {
    return validate(yamlFile, null);
  }

  public static Result validate(File yamlFile, MealseLogger log) {
    Result r = doValidate(yamlFile, log);
    return new Result(r.valid, r.message, r.values, false);
  }

  // ============================================================
  //  Валидация + ремонт
  // ============================================================

  /**
   * Проверяет YAML. Если невалиден — перезаписывает эталонным.
   * Старая версия сохраняется в файл с суффиксом .bak.
   *
   * @param yamlFile       файл конфига
   * @param canonicalYaml  эталонное содержимое (используется для ремонта)
   * @param log            логгер (может быть null)
   * @return результат с флагом wasRepaired
   */
  public static Result ensureValid(File yamlFile, String canonicalYaml, MealseLogger log) {
    log(log, "════════ YamlValidator.ensureValid START ════════");

    // 1. Базовая валидация
    Result base = doValidate(yamlFile, log);

    if (base.valid) {
      log(log, "✓ YAML валиден, ремонт не требуется");
      log(log, "════════ YamlValidator.ensureValid END (valid) ════════");
      return new Result(true, "OK", base.values, false);
    }

    // 2. Невалиден — пытаемся починить
    log(log, "❌ YAML невалиден: " + base.message);
    log(log, "→ Попытка восстановления эталонным содержимым...");

    if (canonicalYaml == null || canonicalYaml.isEmpty()) {
      log(log, "❌ Эталонный YAML пустой — ремонт невозможен");
      log(log, "════════ YamlValidator.ensureValid END (no repair) ════════");
      return new Result(false, base.message + " (canonical empty)", base.values, false);
    }

    boolean repaired = repair(yamlFile, canonicalYaml, log);
    if (!repaired) {
      log(log, "❌ Ремонт не удался");
      log(log, "════════ YamlValidator.ensureValid END (repair failed) ════════");
      return new Result(false, base.message + " (repair failed)", base.values, false);
    }

    // 3. Повторная валидация после ремонта
    log(log, "→ Повторная валидация после ремонта...");
    Result after = doValidate(yamlFile, log);

    if (after.valid) {
      log(log, "✓ YAML восстановлен и валиден");
      log(log, "════════ YamlValidator.ensureValid END (repaired) ════════");
      return new Result(true, "repaired", after.values, true);
    }

    log(log, "❌ После ремонта YAML всё ещё невалиден: " + after.message);
    log(log, "════════ YamlValidator.ensureValid END (still invalid) ════════");
    return new Result(false, after.message, after.values, true);
  }

  // ============================================================
  //  Ремонт
  // ============================================================

  /**
   * Перезаписывает файл эталонным содержимым.
   * Перед перезаписью создаёт .bak-копию.
   */
  public static boolean repair(File yamlFile, String canonicalYaml, MealseLogger log) {
    try {
      // 1. Создать .bak, если файл существует
      if (yamlFile.exists() && yamlFile.length() > 0) {
        File backup = new File(yamlFile.getAbsolutePath() + BACKUP_SUFFIX);
        try {
          Files.copy(yamlFile.toPath(), backup.toPath(),
              StandardCopyOption.REPLACE_EXISTING);
          log(log, "  ✓ Резервная копия: " + backup.getAbsolutePath());
        } catch (IOException e) {
          log(log, "  ⚠ Не удалось создать резервную копию: " + e.getMessage());
          // Продолжаем — это не критично
        }
      }

      // 2. Записать эталонное содержимое
      try (FileWriter w = new FileWriter(yamlFile, false)) {
        w.write(canonicalYaml);
        w.flush();
      }

      log(log, "  ✓ YAML перезаписан (" + canonicalYaml.length() + " симв.)");
      return true;

    } catch (Exception e) {
      log(log, "  ❌ Ошибка ремонта: " + e.getMessage());
      return false;
    }
  }

  // ============================================================
  //  Канонизация
  // ============================================================

  /**
   * Проверяет, что файл побайтово совпадает с эталоном.
   * Если нет — возвращает false (можно вызвать repair).
   */
  public static boolean isCanonical(File yamlFile, String canonicalYaml) {
    if (yamlFile == null || !yamlFile.exists() || canonicalYaml == null) {
      return false;
    }
    try {
      String content = new String(Files.readAllBytes(yamlFile.toPath()), "UTF-8");
      return content.equals(canonicalYaml);
    } catch (IOException e) {
      return false;
    }
  }

  // ============================================================
  //  Основная логика валидации
  // ============================================================

  private static Result doValidate(File yamlFile, MealseLogger log) {
    Map<String, String> values = new HashMap<>();

    log(log, "════════ YamlValidator START ════════");

    if (yamlFile == null) {
      log(log, "❌ yamlFile == null");
      return new Result(false, "YAML file is null", values, false);
    }
    log(log, "  file=" + yamlFile.getAbsolutePath());
    log(log, "  exists=" + yamlFile.exists());
    log(log, "  canRead=" + yamlFile.canRead());
    log(log, "  length=" + (yamlFile.exists() ? yamlFile.length() : -1) + " байт");

    if (!yamlFile.exists()) {
      log(log, "❌ файл не существует");
      return new Result(false, "YAML file does not exist: " + yamlFile.getAbsolutePath(), values, false);
    }
    if (!yamlFile.canRead()) {
      log(log, "❌ файл не читается");
      return new Result(false, "YAML file is not readable: " + yamlFile.getAbsolutePath(), values, false);
    }
    if (yamlFile.length() == 0) {
      log(log, "❌ файл пустой");
      return new Result(false, "YAML file is empty", values, false);
    }

    StringBuilder errors = new StringBuilder();
    String currentSection = null;
    int lineNumber = 0;
    int parsedKeys = 0;

    try (BufferedReader reader = new BufferedReader(new FileReader(yamlFile))) {
      String line;
      while ((line = reader.readLine()) != null) {
        lineNumber++;

        if (line.trim().isEmpty() || line.trim().startsWith("#")) {
          continue;
        }

        if (line.contains("\t")) {
          errors.append("Line ").append(lineNumber)
              .append(": contains TAB (use spaces for indentation)\n");
          log(log, "  ⚠ line " + lineNumber + ": TAB");
          continue;
        }

        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') {
          indent++;
        }
        if (indent % 2 != 0) {
          errors.append("Line ").append(lineNumber)
              .append(": indent is not a multiple of 2 (found ").append(indent).append(")\n");
          log(log, "  ⚠ line " + lineNumber + ": indent=" + indent + " (не кратно 2)");
        }

        String trimmed = line.trim();

        if (indent == 0 && trimmed.endsWith(":")) {
          currentSection = trimmed.substring(0, trimmed.length() - 1);
          log(log, "  → секция: " + currentSection);
          continue;
        }

        int colonIdx = trimmed.indexOf(':');
        if (colonIdx <= 0) {
          errors.append("Line ").append(lineNumber)
              .append(": not a valid 'key: value' line: ").append(trimmed).append("\n");
          log(log, "  ⚠ line " + lineNumber + ": не key:value — '" + trimmed + "'");
          continue;
        }

        String key = trimmed.substring(0, colonIdx).trim();
        String value = trimmed.substring(colonIdx + 1).trim();

        if (value.length() >= 2) {
          if ((value.startsWith("'") && value.endsWith("'"))
              || (value.startsWith("\"") && value.endsWith("\""))) {
            value = value.substring(1, value.length() - 1);
          }
        }

        String fullKey = (currentSection != null ? currentSection + "." : "") + key;

        if (values.containsKey(fullKey)) {
          errors.append("Line ").append(lineNumber)
              .append(": duplicate key '").append(fullKey).append("'\n");
          log(log, "  ⚠ line " + lineNumber + ": дубликат ключа " + fullKey);
        }

        values.put(fullKey, value);
        parsedKeys++;
        log(log, "  parsed: " + fullKey + " = '" + value + "'");
      }
    } catch (Exception e) {
      log(log, "❌ read error: " + e.getMessage());
      return new Result(false, "Read error: " + e.getMessage(), values, false);
    }

    log(log, "  Всего строк: " + lineNumber + ", распознано ключей: " + parsedKeys);

    log(log, "── Проверка значений ──");

    if (!values.containsKey("tunnel.mtu")) {
      errors.append("Missing required key: tunnel.mtu\n");
      log(log, "  ❌ tunnel.mtu отсутствует");
    } else {
      try {
        int mtu = Integer.parseInt(values.get("tunnel.mtu"));
        if (mtu < 576 || mtu > 65535) {
          errors.append("tunnel.mtu out of range (576..65535): ").append(mtu).append("\n");
          log(log, "  ❌ tunnel.mtu вне диапазона: " + mtu);
        } else {
          log(log, "  ✓ tunnel.mtu=" + mtu);
        }
      } catch (NumberFormatException e) {
        errors.append("tunnel.mtu is not a number: '")
            .append(values.get("tunnel.mtu")).append("'\n");
        log(log, "  ❌ tunnel.mtu не число: " + values.get("tunnel.mtu"));
      }
    }

    if (!values.containsKey("tunnel.ipv4")) {
      errors.append("Missing required key: tunnel.ipv4\n");
      log(log, "  ❌ tunnel.ipv4 отсутствует");
    } else {
      String ipv4 = values.get("tunnel.ipv4");
      if (!isValidIpv4(ipv4)) {
        errors.append("tunnel.ipv4 is not a valid IPv4: '").append(ipv4).append("'\n");
        log(log, "  ❌ tunnel.ipv4 невалиден: " + ipv4);
      } else {
        log(log, "  ✓ tunnel.ipv4=" + ipv4);
      }
    }

    if (values.containsKey("tunnel.ipv6")) {
      String ipv6 = values.get("tunnel.ipv6");
      if (ipv6 != null && !ipv6.isEmpty() && !isValidIpv6(ipv6)) {
        errors.append("tunnel.ipv6 is not a valid IPv6: '").append(ipv6).append("'\n");
        log(log, "  ❌ tunnel.ipv6 невалиден: " + ipv6);
      } else {
        log(log, "  ✓ tunnel.ipv6=" + ipv6);
      }
    } else {
      log(log, "  • tunnel.ipv6 отсутствует (опционально)");
    }

    if (!values.containsKey("socks5.port")) {
      errors.append("Missing required key: socks5.port\n");
      log(log, "  ❌ socks5.port отсутствует");
    } else {
      try {
        int port = Integer.parseInt(values.get("socks5.port"));
        if (port < 1 || port > 65535) {
          errors.append("socks5.port out of range (1..65535): ").append(port).append("\n");
          log(log, "  ❌ socks5.port вне диапазона: " + port);
        } else {
          log(log, "  ✓ socks5.port=" + port);
        }
      } catch (NumberFormatException e) {
        errors.append("socks5.port is not a number: '")
            .append(values.get("socks5.port")).append("'\n");
        log(log, "  ❌ socks5.port не число: " + values.get("socks5.port"));
      }
    }

    if (!values.containsKey("socks5.address")) {
      errors.append("Missing required key: socks5.address\n");
      log(log, "  ❌ socks5.address отсутствует");
    } else {
      String addr = values.get("socks5.address");
      if (addr == null || addr.isEmpty()) {
        errors.append("socks5.address is empty\n");
        log(log, "  ❌ socks5.address пустой");
      } else if (!isValidIpv4(addr) && !isValidIpv6(addr)) {
        errors.append("socks5.address is not a valid IPv4/IPv6: '").append(addr).append("'\n");
        log(log, "  ❌ socks5.address невалиден: " + addr);
      } else {
        log(log, "  ✓ socks5.address=" + addr);
      }
    }

    if (errors.length() > 0) {
      log(log, "❌ YAML невалиден. Ошибок: " + errors.toString().split("\n").length);
      log(log, "  errors:\n" + errors);
      log(log, "════════ YamlValidator END (invalid) ════════");
      return new Result(false, errors.toString().trim(), values, false);
    }

    log(log, "✓ YAML валиден");
    log(log, "  values=" + values);
    log(log, "════════ YamlValidator END (valid) ════════");
    return new Result(true, "OK", values, false);
  }

  // ============================================================
  //  Валидаторы
  // ============================================================

  public static boolean isValidIpv4(String s) {
    if (s == null || s.isEmpty()) return false;
    String[] parts = s.split("\\.");
    if (parts.length != 4) return false;
    for (String p : parts) {
      try {
        int n = Integer.parseInt(p);
        if (n < 0 || n > 255) return false;
      } catch (NumberFormatException e) {
        return false;
      }
    }
    return true;
  }

  public static boolean isValidIpv6(String s) {
    if (s == null || s.isEmpty()) return false;
    if (s.length() > 45) return false;
    if (!s.contains(":")) return false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      boolean ok = (c >= '0' && c <= '9')
          || (c >= 'a' && c <= 'f')
          || (c >= 'A' && c <= 'F')
          || c == ':'
          || c == '.';
      if (!ok) return false;
    }
    return true;
  }

  private static void log(MealseLogger log, String message) {
    if (log != null) {
      log.log(message);
    }
  }
}