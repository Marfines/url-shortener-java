package ru.marfines.shortener;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import ru.marfines.shortener.config.AppConfig;
import ru.marfines.shortener.core.ShortenerService;
import ru.marfines.shortener.core.State;
import ru.marfines.shortener.core.StateStore;

final class TestSupport {
  private TestSupport() {}

  static AppConfig config(Path file, Duration ttl) {
    return new AppConfig(
        ttl, 10, 100, 8, URI.create("https://short.local/"), Duration.ofMillis(100), file);
  }

  static ShortenerService service(StateStore store, Clock clock, Duration ttl) throws IOException {
    AtomicInteger counter = new AtomicInteger();
    return new ShortenerService(
        config(Path.of("unused.xml"), ttl),
        store,
        clock,
        () -> String.format("code%04d", counter.incrementAndGet()));
  }

  static final class MemoryStore implements StateStore {
    State saved = new State();
    boolean fail;

    @Override
    public State load() {
      return new State(saved);
    }

    @Override
    public void save(State state) throws IOException {
      if (fail) {
        throw new IOException("Simulated disk failure");
      }
      saved = new State(state);
    }
  }

  static final class MutableClock extends Clock {
    private volatile Instant now = Instant.parse("2026-01-01T00:00:00Z");

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
