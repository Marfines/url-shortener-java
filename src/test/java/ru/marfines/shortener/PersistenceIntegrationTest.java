package ru.marfines.shortener;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.marfines.shortener.core.Model.Link;
import ru.marfines.shortener.core.Model.Reason;
import ru.marfines.shortener.infra.FileStateStore;

class PersistenceIntegrationTest {
  @TempDir Path directory;

  @Test
  void restartPreservesUuidCounterLimitAndQueuedOwnerNotification() throws IOException {
    Path file = directory.resolve("state.xml");
    var clock = new TestSupport.MutableClock();
    Link link;
    try (var store = new FileStateStore(file)) {
      var service = TestSupport.service(store, clock, Duration.ofDays(1));
      link = service.create("https://example.org/путь?q=1&b=2", 1L);
      service.newUser();
      service.visit(link.code());
    }
    try (var store = new FileStateStore(file)) {
      var service = TestSupport.service(store, clock, Duration.ofDays(1));
      assertNull(service.currentUser());
      service.useUser(link.owner());
      assertEquals(link.target(), service.info(link.code()).target());
      assertEquals(1, service.info(link.code()).visits());
      assertThrows(IllegalArgumentException.class, () -> service.visit(link.code()));
      assertEquals(Reason.LIMIT_REACHED, service.takeNotices().get(0).reason());
    }
    try (var store = new FileStateStore(file)) {
      var service = TestSupport.service(store, clock, Duration.ofDays(1));
      assertEquals(link.owner(), service.currentUser());
      assertTrue(service.takeNotices().isEmpty());
    }
  }

  @Test
  void restartRemovesExpiredLinksAndRetainsReasonAndNotification() throws IOException {
    Path file = directory.resolve("state.xml");
    var clock = new TestSupport.MutableClock();
    Link link;
    try (var store = new FileStateStore(file)) {
      var service = TestSupport.service(store, clock, Duration.ofHours(1));
      link = service.create("https://example.org/secret-target", null);
    }
    clock.advance(Duration.ofHours(1));
    try (var store = new FileStateStore(file)) {
      var service = TestSupport.service(store, clock, Duration.ofDays(1));
      assertTrue(service.list().isEmpty());
      assertEquals(Reason.EXPIRED, service.takeNotices().get(0).reason());
      assertTrue(
          assertThrows(IllegalArgumentException.class, () -> service.visit(link.code()))
              .getMessage()
              .contains("истекло"));
      assertFalse(Files.readString(file).contains("secret-target"));
    }
  }

  @Test
  void secondProcessCannotUseSameStorage() throws IOException {
    Path file = directory.resolve("state.xml");
    try (var first = new FileStateStore(file)) {
      assertNotNull(first.load());
      assertThrows(IOException.class, () -> new FileStateStore(file));
    }
    try (var reopened = new FileStateStore(file)) {
      assertNotNull(reopened.load());
    }
  }

  @Test
  void corruptStorageFailsWithoutReplacingUserData() throws IOException {
    Path file = directory.resolve("state.xml");
    Files.writeString(file, "broken data");
    try (var store = new FileStateStore(file)) {
      assertThrows(IOException.class, store::load);
      assertEquals("broken data", Files.readString(file));
    }
  }

  @Test
  void corruptCounterIsRejected() throws IOException {
    Path file = directory.resolve("state.xml");
    try (var store = new FileStateStore(file)) {
      TestSupport.service(store, new TestSupport.MutableClock(), Duration.ofDays(1))
          .create("https://example.org", 1L);
    }
    Files.writeString(
        file,
        Files.readString(file)
            .replace(
                "<entry key=\"links.0.visits\">0</entry>",
                "<entry key=\"links.0.visits\">2</entry>"));
    try (var store = new FileStateStore(file)) {
      assertThrows(IOException.class, store::load);
    }
  }
}
