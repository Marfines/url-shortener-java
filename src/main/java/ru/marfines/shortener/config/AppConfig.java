package ru.marfines.shortener.config;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Duration;
import java.util.Properties;

/** Validated system policy; link owners cannot override TTL. */
public record AppConfig(
    Duration ttl,
    long defaultLimit,
    long maxLimit,
    int codeLength,
    URI baseUrl,
    Duration cleanupInterval,
    Path storageFile) {
  public AppConfig {
    if (ttl == null
        || ttl.isNegative()
        || ttl.isZero()
        || ttl.compareTo(Duration.ofDays(3650)) > 0) {
      throw new IllegalArgumentException(
          "link.ttl: срок должен быть положительным и не более 3650 дней.");
    }
    if (defaultLimit < 1 || maxLimit < defaultLimit) {
      throw new IllegalArgumentException("Лимиты: 1 <= link.default-limit <= link.max-limit.");
    }
    if (codeLength < 6 || codeLength > 32) {
      throw new IllegalArgumentException("link.code-length: допустимо от 6 до 32.");
    }
    if (cleanupInterval == null
        || cleanupInterval.compareTo(Duration.ofMillis(100)) < 0
        || cleanupInterval.compareTo(Duration.ofHours(1)) > 0) {
      throw new IllegalArgumentException("cleanup.interval: допустимо от PT0.1S до PT1H.");
    }
    if (baseUrl == null
        || !("https".equals(baseUrl.getScheme()) || "http".equals(baseUrl.getScheme()))
        || baseUrl.getHost() == null
        || baseUrl.getUserInfo() != null
        || baseUrl.getQuery() != null
        || baseUrl.getFragment() != null
        || !baseUrl.toString().endsWith("/")) {
      throw new IllegalArgumentException(
          "link.base-url: нужен HTTP(S) URL без параметров, заканчивающийся на /.");
    }
    if (storageFile == null) {
      throw new IllegalArgumentException("Не задан storage.file.");
    }
  }

  public static AppConfig load(Path path) throws IOException {
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      properties.load(reader);
    }
    try {
      Path directory = path.toAbsolutePath().normalize().getParent();
      return new AppConfig(
          Duration.parse(required(properties, "link.ttl")),
          Long.parseLong(required(properties, "link.default-limit")),
          Long.parseLong(required(properties, "link.max-limit")),
          Integer.parseInt(required(properties, "link.code-length")),
          URI.create(required(properties, "link.base-url")),
          Duration.parse(required(properties, "cleanup.interval")),
          directory.resolve(required(properties, "storage.file")).normalize());
    } catch (IllegalArgumentException | DateTimeException ex) {
      throw new IllegalArgumentException("Ошибка конфигурации: " + ex.getMessage(), ex);
    }
  }

  private static String required(Properties properties, String key) {
    String value = properties.getProperty(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Не задан параметр " + key);
    }
    return value.trim();
  }
}
