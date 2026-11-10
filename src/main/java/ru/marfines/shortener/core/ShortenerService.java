package ru.marfines.shortener.core;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import ru.marfines.shortener.config.AppConfig;
import ru.marfines.shortener.core.Model.Link;
import ru.marfines.shortener.core.Model.Notice;
import ru.marfines.shortener.core.Model.Reason;

/** Serializes transactions, including cleanup, and commits only after durable persistence. */
public final class ShortenerService {
  private final AppConfig config;
  private final StateStore store;
  private final Clock clock;
  private final CodeGenerator codes;
  private State state;

  public ShortenerService(AppConfig config, StateStore store, Clock clock, CodeGenerator codes)
      throws IOException {
    this.config = config;
    this.store = store;
    this.clock = clock;
    this.codes = codes;
    state = new State(store.load());
    cleanup();
  }

  public synchronized Link create(String url, Long requestedLimit) throws IOException {
    URI target = validateUrl(url);
    long limit = requestedLimit == null ? config.defaultLimit() : requestedLimit;
    validateLimit(limit);
    cleanup();
    String code = unusedCode();
    State next = new State(state);
    if (next.currentUser == null) {
      do {
        next.currentUser = UUID.randomUUID();
      } while (next.users.contains(next.currentUser));
      next.users.add(next.currentUser);
    }
    Instant now = clock.instant();
    Link link = new Link(code, next.currentUser, target, limit, 0, now, now.plus(config.ttl()));
    next.links.put(code, link);
    commit(next);
    return link;
  }

  /** Counts a granted redirect, persisted before the target is returned to the UI. */
  public synchronized URI visit(String reference) throws IOException {
    cleanup();
    Link link = find(reference);
    if (link.visits() >= link.limit()) {
      throw new IllegalArgumentException("Лимит переходов исчерпан. Ссылка недоступна.");
    }
    State next = new State(state);
    Link visited = link.withVisit();
    next.links.put(link.code(), visited);
    if (visited.visits() == visited.limit()) {
      next.notices.add(
          new Notice(link.owner(), link.code(), Reason.LIMIT_REACHED, clock.instant()));
    }
    commit(next);
    return link.target();
  }

  public synchronized Link changeLimit(String reference, long limit) throws IOException {
    validateLimit(limit);
    cleanup();
    Link link = owned(reference);
    if (limit < link.visits()) {
      throw new IllegalArgumentException(
          "Лимит не может быть меньше уже выполненных переходов: " + link.visits());
    }
    State next = new State(state);
    Link updated = link.withLimit(limit);
    next.links.put(link.code(), updated);
    if (link.visits() < link.limit() && updated.visits() == updated.limit()) {
      next.notices.add(
          new Notice(link.owner(), link.code(), Reason.LIMIT_REACHED, clock.instant()));
    }
    commit(next);
    return updated;
  }

  public synchronized Link changeTarget(String reference, String url) throws IOException {
    URI target = validateUrl(url);
    cleanup();
    Link updated = owned(reference).withTarget(target);
    State next = new State(state);
    next.links.put(updated.code(), updated);
    commit(next);
    return updated;
  }

  public synchronized void delete(String reference) throws IOException {
    cleanup();
    Link link = owned(reference);
    State next = new State(state);
    next.links.remove(link.code());
    next.retired.put(link.code(), Reason.DELETED);
    commit(next);
  }

  public synchronized List<Link> list() throws IOException {
    cleanup();
    UUID owner = requireUser();
    return state.links.values().stream()
        .filter(link -> link.owner().equals(owner))
        .sorted(Comparator.comparing(Link::createdAt).thenComparing(Link::code))
        .toList();
  }

  public synchronized Link info(String reference) throws IOException {
    cleanup();
    return owned(reference);
  }

  public synchronized int cleanup() throws IOException {
    Instant now = clock.instant();
    List<Link> expired =
        state.links.values().stream().filter(link -> !now.isBefore(link.expiresAt())).toList();
    if (expired.isEmpty()) {
      return 0;
    }
    State next = new State(state);
    for (Link link : expired) {
      next.links.remove(link.code());
      next.retired.put(link.code(), Reason.EXPIRED);
      next.notices.add(new Notice(link.owner(), link.code(), Reason.EXPIRED, now));
    }
    commit(next);
    return expired.size();
  }

  public synchronized UUID currentUser() {
    return state.currentUser;
  }

  public synchronized void newUser() throws IOException {
    State next = new State(state);
    next.currentUser = null;
    commit(next);
  }

  public synchronized void useUser(UUID user) throws IOException {
    if (!state.users.contains(user)) {
      throw new IllegalArgumentException("UUID не найден. Новый UUID создаётся командой shorten.");
    }
    State next = new State(state);
    next.currentUser = user;
    commit(next);
  }

  /** Notifications for inactive owners remain in storage until that owner returns. */
  public synchronized List<Notice> takeNotices() throws IOException {
    List<Notice> notices =
        state.notices.stream().filter(notice -> notice.owner().equals(state.currentUser)).toList();
    if (!notices.isEmpty()) {
      State next = new State(state);
      next.notices.removeAll(notices);
      commit(next);
    }
    return notices;
  }

  public String shortUrl(Link link) {
    return config.baseUrl().toString() + link.code();
  }

  private String unusedCode() {
    for (int attempt = 0; attempt < 128; attempt++) {
      String code = codes.nextCode();
      if (code == null || !code.matches("[A-Za-z0-9]{6,32}")) {
        throw new IllegalStateException("Генератор вернул некорректный код.");
      }
      if (!state.links.containsKey(code) && !state.retired.containsKey(code)) {
        return code;
      }
    }
    throw new IllegalStateException(
        "Не удалось выбрать уникальный код. Повторите создание ссылки.");
  }

  private Link owned(String reference) {
    UUID user = requireUser();
    Link link = find(reference);
    if (!link.owner().equals(user)) {
      throw new IllegalArgumentException(
          "Доступ запрещён: управлять ссылкой может только её создатель.");
    }
    return link;
  }

  private UUID requireUser() {
    if (state.currentUser == null) {
      throw new IllegalArgumentException(
          "Сначала создайте ссылку (shorten) или выберите свой UUID (user use).");
    }
    return state.currentUser;
  }

  private Link find(String reference) {
    String code = reference;
    if (reference.startsWith(config.baseUrl().toString())) {
      code = reference.substring(config.baseUrl().toString().length());
    }
    if (!code.matches("[A-Za-z0-9]{6,32}")) {
      throw new IllegalArgumentException(
          "Введите код или полную короткую ссылку из этого сервиса.");
    }
    Link link = state.links.get(code);
    if (link == null) {
      Reason reason = state.retired.get(code);
      if (reason == Reason.EXPIRED) {
        throw new IllegalArgumentException(
            "Время жизни ссылки истекло. Ссылка автоматически удалена.");
      }
      if (reason == Reason.DELETED) {
        throw new IllegalArgumentException("Ссылка удалена владельцем.");
      }
      throw new IllegalArgumentException("Ссылка не найдена.");
    }
    return link;
  }

  public static URI validateUrl(String value) {
    try {
      URI uri = URI.create(value);
      if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getPort() > 65535
          || uri.getPort() == 0
          || value.chars().anyMatch(Character::isISOControl)) {
        throw new IllegalArgumentException();
      }
      return uri;
    } catch (IllegalArgumentException | NullPointerException ex) {
      throw new IllegalArgumentException(
          "Нужен корректный абсолютный HTTP(S) URL без логина и пароля.");
    }
  }

  private void validateLimit(long limit) {
    if (limit < 1 || limit > config.maxLimit()) {
      throw new IllegalArgumentException("Лимит должен быть от 1 до " + config.maxLimit() + ".");
    }
  }

  private void commit(State next) throws IOException {
    store.save(next);
    state = next;
  }
}
