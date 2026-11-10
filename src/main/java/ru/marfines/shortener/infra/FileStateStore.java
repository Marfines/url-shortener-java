package ru.marfines.shortener.infra;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import ru.marfines.shortener.core.Model.Link;
import ru.marfines.shortener.core.Model.Notice;
import ru.marfines.shortener.core.Model.Reason;
import ru.marfines.shortener.core.ShortenerService;
import ru.marfines.shortener.core.State;
import ru.marfines.shortener.core.StateStore;

/** Versioned XML properties, atomic replacement, and an exclusive process lock. */
public final class FileStateStore implements StateStore, AutoCloseable {
  private final Path file;
  private final FileChannel lockChannel;
  private final FileLock lock;

  public FileStateStore(Path file) throws IOException {
    this.file = file.toAbsolutePath().normalize();
    Files.createDirectories(this.file.getParent());
    lockChannel =
        FileChannel.open(
            this.file.resolveSibling(this.file.getFileName() + ".lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE);
    try {
      lock = lockChannel.tryLock();
      if (lock == null) {
        throw new IOException("Хранилище уже используется другим экземпляром приложения.");
      }
    } catch (IOException | OverlappingFileLockException ex) {
      lockChannel.close();
      throw new IOException("Не удалось заблокировать хранилище: закройте другой экземпляр.", ex);
    }
  }

  @Override
  public State load() throws IOException {
    if (!Files.exists(file)) {
      return new State();
    }
    Properties values = new Properties();
    try (InputStream input = Files.newInputStream(file)) {
      values.loadFromXML(input);
    }
    try {
      if (!"1".equals(required(values, "version"))) {
        throw new IllegalArgumentException("Неподдерживаемая версия.");
      }
      State state = new State();
      for (int i = 0; i < count(values, "users"); i++) {
        if (!state.users.add(UUID.fromString(required(values, "users." + i)))) {
          throw new IllegalArgumentException("Повтор UUID.");
        }
      }
      String current = required(values, "currentUser");
      state.currentUser = current.isEmpty() ? null : UUID.fromString(current);
      for (int i = 0; i < count(values, "links"); i++) {
        String prefix = "links." + i + ".";
        Link link =
            new Link(
                required(values, prefix + "code"),
                UUID.fromString(required(values, prefix + "owner")),
                URI.create(required(values, prefix + "target")),
                Long.parseLong(required(values, prefix + "limit")),
                Long.parseLong(required(values, prefix + "visits")),
                Instant.parse(required(values, prefix + "createdAt")),
                Instant.parse(required(values, prefix + "expiresAt")));
        if (state.links.put(link.code(), link) != null) {
          throw new IllegalArgumentException("Повтор кода ссылки.");
        }
      }
      for (int i = 0; i < count(values, "retired"); i++) {
        String prefix = "retired." + i + ".";
        if (state.retired.put(
                required(values, prefix + "code"),
                Reason.valueOf(required(values, prefix + "reason")))
            != null) {
          throw new IllegalArgumentException("Повтор удалённого кода.");
        }
      }
      for (int i = 0; i < count(values, "notices"); i++) {
        String prefix = "notices." + i + ".";
        state.notices.add(
            new Notice(
                UUID.fromString(required(values, prefix + "owner")),
                required(values, prefix + "code"),
                Reason.valueOf(required(values, prefix + "reason")),
                Instant.parse(required(values, prefix + "at"))));
      }
      validate(state);
      return state;
    } catch (RuntimeException ex) {
      throw new IOException("Хранилище повреждено; исходный файл сохранён. " + ex.getMessage(), ex);
    }
  }

  @Override
  public void save(State state) throws IOException {
    validate(state);
    Properties values = new Properties();
    values.setProperty("version", "1");
    values.setProperty(
        "currentUser", state.currentUser == null ? "" : state.currentUser.toString());
    values.setProperty("users.count", Integer.toString(state.users.size()));
    int index = 0;
    for (UUID user : state.users) {
      values.setProperty("users." + index++, user.toString());
    }
    values.setProperty("links.count", Integer.toString(state.links.size()));
    index = 0;
    for (Link link : state.links.values()) {
      String prefix = "links." + index++ + ".";
      values.setProperty(prefix + "code", link.code());
      values.setProperty(prefix + "owner", link.owner().toString());
      values.setProperty(prefix + "target", link.target().toString());
      values.setProperty(prefix + "limit", Long.toString(link.limit()));
      values.setProperty(prefix + "visits", Long.toString(link.visits()));
      values.setProperty(prefix + "createdAt", link.createdAt().toString());
      values.setProperty(prefix + "expiresAt", link.expiresAt().toString());
    }
    values.setProperty("retired.count", Integer.toString(state.retired.size()));
    index = 0;
    for (var entry : state.retired.entrySet()) {
      String prefix = "retired." + index++ + ".";
      values.setProperty(prefix + "code", entry.getKey());
      values.setProperty(prefix + "reason", entry.getValue().name());
    }
    values.setProperty("notices.count", Integer.toString(state.notices.size()));
    index = 0;
    for (Notice notice : state.notices) {
      String prefix = "notices." + index++ + ".";
      values.setProperty(prefix + "owner", notice.owner().toString());
      values.setProperty(prefix + "code", notice.code());
      values.setProperty(prefix + "reason", notice.reason().name());
      values.setProperty(prefix + "at", notice.occurredAt().toString());
    }
    Path temporary = Files.createTempFile(file.getParent(), "state-", ".tmp");
    try {
      try (OutputStream output = Files.newOutputStream(temporary)) {
        values.storeToXML(output, "URL Shortener state - do not edit while running", "UTF-8");
      }
      try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      // No non-atomic fallback: failure must preserve the previous snapshot.
      Files.move(
          temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static void validate(State state) {
    if (state.currentUser != null && !state.users.contains(state.currentUser)) {
      throw new IllegalArgumentException("Неизвестный текущий пользователь.");
    }
    for (var entry : state.links.entrySet()) {
      Link link = entry.getValue();
      validCode(link.code());
      ShortenerService.validateUrl(link.target().toString());
      if (!entry.getKey().equals(link.code())
          || !state.users.contains(link.owner())
          || link.limit() < 1
          || link.visits() < 0
          || link.visits() > link.limit()
          || !link.expiresAt().isAfter(link.createdAt())
          || state.retired.containsKey(link.code())) {
        throw new IllegalArgumentException("Нарушена целостность ссылки.");
      }
    }
    for (var entry : state.retired.entrySet()) {
      validCode(entry.getKey());
      if (entry.getValue() != Reason.EXPIRED && entry.getValue() != Reason.DELETED) {
        throw new IllegalArgumentException("Некорректная причина удаления.");
      }
    }
    for (Notice notice : state.notices) {
      validCode(notice.code());
      if (!state.users.contains(notice.owner())
          || notice.reason() == Reason.DELETED
          || (!state.links.containsKey(notice.code())
              && !state.retired.containsKey(notice.code()))) {
        throw new IllegalArgumentException("Некорректное уведомление.");
      }
    }
  }

  private static void validCode(String code) {
    if (!code.matches("[A-Za-z0-9]{6,32}")) {
      throw new IllegalArgumentException("Некорректный код ссылки.");
    }
  }

  private static String required(Properties values, String key) {
    String value = values.getProperty(key);
    if (value == null) {
      throw new IllegalArgumentException("Отсутствует поле " + key);
    }
    return value;
  }

  private static int count(Properties values, String key) {
    int count = Integer.parseInt(required(values, key + ".count"));
    if (count < 0) {
      throw new IllegalArgumentException("Отрицательное количество записей.");
    }
    return count;
  }

  @Override
  public void close() throws IOException {
    try {
      lock.release();
    } finally {
      lockChannel.close();
    }
  }
}
