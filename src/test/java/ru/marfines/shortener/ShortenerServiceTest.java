package ru.marfines.shortener;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.marfines.shortener.core.Model.Reason;
import ru.marfines.shortener.core.ShortenerService;
import ru.marfines.shortener.infra.CleanupWorker;
import ru.marfines.shortener.infra.RandomCodeGenerator;

class ShortenerServiceTest {
  private TestSupport.MemoryStore store;
  private TestSupport.MutableClock clock;
  private ShortenerService service;

  @BeforeEach
  void setup() throws IOException {
    store = new TestSupport.MemoryStore();
    clock = new TestSupport.MutableClock();
    service = TestSupport.service(store, clock, Duration.ofDays(1));
  }

  @Test
  void createsUuidOnlyOnFirstSuccessfulCreationAndReusesIt() throws IOException {
    assertNull(service.currentUser());
    assertThrows(IllegalArgumentException.class, () -> service.create("bad", null));
    assertNull(service.currentUser());
    var first = service.create("https://example.org/a", null);
    var second = service.create("https://example.org/b", null);
    assertEquals(first.owner(), second.owner());
    assertEquals(10, first.limit());
    assertEquals(2, service.list().size());
  }

  @Test
  void differentUsersReceiveDifferentCodesForSameUrlAndIsolatedLists() throws IOException {
    var first = service.create("https://example.org", 3L);
    service.newUser();
    var second = service.create("https://example.org", 3L);
    assertNotEquals(first.owner(), second.owner());
    assertNotEquals(first.code(), second.code());
    assertEquals(List.of(second), service.list());
    service.useUser(first.owner());
    assertEquals(List.of(first), service.list());
  }

  @Test
  void exactLimitBlocksAllSubsequentVisitsAndNotifiesOnce() throws IOException {
    var link = service.create("https://example.org", 2L);
    assertEquals(link.target(), service.visit(service.shortUrl(link)));
    assertEquals(link.target(), service.visit(link.code()));
    assertThrows(IllegalArgumentException.class, () -> service.visit(link.code()));
    assertEquals(2, service.info(link.code()).visits());
    assertEquals(Reason.LIMIT_REACHED, service.takeNotices().get(0).reason());
    assertTrue(service.takeNotices().isEmpty());
  }

  @Test
  void visitsDoNotRequireOwnerIdentity() throws IOException {
    var link = service.create("https://example.org", 1L);
    service.newUser();
    assertEquals(link.target(), service.visit(link.code()));
    assertTrue(service.takeNotices().isEmpty());
    service.useUser(link.owner());
    assertEquals(1, service.takeNotices().size());
  }

  @ParameterizedTest
  @ValueSource(longs = {3600, 86400})
  void expiresAtExactBoundaryAndRemovesOriginalUrl(long seconds) throws IOException {
    service = TestSupport.service(store, clock, Duration.ofSeconds(seconds));
    var link = service.create("https://example.org/private", 3L);
    clock.advance(Duration.ofSeconds(seconds).minusNanos(1));
    assertEquals(link.target(), service.visit(link.code()));
    clock.advance(Duration.ofNanos(1));
    var error = assertThrows(IllegalArgumentException.class, () -> service.visit(link.code()));
    assertTrue(error.getMessage().contains("истекло"));
    assertTrue(store.saved.links.isEmpty());
    assertEquals(Reason.EXPIRED, store.saved.retired.get(link.code()));
    assertEquals(1, service.takeNotices().size());
    assertEquals(0, service.cleanup());
    assertTrue(service.takeNotices().isEmpty());
  }

  @Test
  void cannotInspectEditOrDeleteAnotherUsersLinks() throws IOException {
    var first = service.create("https://example.org", 4L);
    service.newUser();
    service.create("https://example.net", 4L);
    assertThrows(IllegalArgumentException.class, () -> service.info(first.code()));
    assertThrows(IllegalArgumentException.class, () -> service.changeLimit(first.code(), 9));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.changeTarget(first.code(), "https://other.org"));
    assertThrows(IllegalArgumentException.class, () -> service.delete(first.code()));
    service.useUser(first.owner());
    assertEquals(first, service.info(first.code()));
  }

  @Test
  void raisingLimitReactivatesLinkWithoutResettingCounterOrTtl() throws IOException {
    var link = service.create("https://example.org", 1L);
    service.visit(link.code());
    service.takeNotices();
    var updated = service.changeLimit(link.code(), 2);
    assertEquals(link.expiresAt(), updated.expiresAt());
    assertEquals(1, updated.visits());
    service.visit(link.code());
    assertEquals(1, service.takeNotices().size());
    assertThrows(IllegalArgumentException.class, () -> service.changeLimit(link.code(), 1));
  }

  @Test
  void reducingLimitToUsageNotifiesOwner() throws IOException {
    var link = service.create("https://example.org", 5L);
    service.visit(link.code());
    service.changeLimit(link.code(), 1);
    assertEquals(Reason.LIMIT_REACHED, service.takeNotices().get(0).reason());
    assertThrows(IllegalArgumentException.class, () -> service.visit(link.code()));
  }

  @Test
  void targetEditPreservesCounterAndExpiry() throws IOException {
    var link = service.create("https://example.org", 3L);
    service.visit(link.code());
    clock.advance(Duration.ofMinutes(10));
    var updated = service.changeTarget(link.code(), "https://example.net/new?q=1#part");
    assertEquals(link.expiresAt(), updated.expiresAt());
    assertEquals(1, updated.visits());
    assertEquals(updated.target(), service.visit(link.code()));
  }

  @Test
  void deletedLinkCannotBeVisitedAndCodeIsNeverReused() throws IOException {
    AtomicInteger calls = new AtomicInteger();
    service =
        new ShortenerService(
            TestSupport.config(Path.of("unused"), Duration.ofDays(1)),
            store,
            clock,
            () -> calls.incrementAndGet() < 3 ? "aaaaaa" : "bbbbbb");
    var first = service.create("https://example.org", null);
    service.delete(first.code());
    assertThrows(IllegalArgumentException.class, () -> service.visit(first.code()));
    assertEquals("bbbbbb", service.create("https://example.org", null).code());
  }

  @Test
  void activeCodeCollisionRetriesWithoutOverwriting() throws IOException {
    AtomicInteger calls = new AtomicInteger();
    service =
        new ShortenerService(
            TestSupport.config(Path.of("unused"), Duration.ofDays(1)),
            store,
            clock,
            () -> calls.incrementAndGet() < 3 ? "aaaaaa" : "bbbbbb");
    var first = service.create("https://example.org/a", null);
    var second = service.create("https://example.org/b", null);
    assertNotEquals(first.code(), second.code());
    assertEquals(first, service.info(first.code()));
  }

  @Test
  void brokenGeneratorStopsAfterBoundedRetries() throws IOException {
    service =
        new ShortenerService(
            TestSupport.config(Path.of("unused"), Duration.ofDays(1)),
            store,
            clock,
            () -> "aaaaaa");
    service.create("https://example.org", null);
    assertThrows(IllegalStateException.class, () -> service.create("https://example.org", null));
    assertEquals(1, service.list().size());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "example.com",
        "javascript:alert(1)",
        "ftp://example.org",
        "http:///path",
        "https://user:password@example.org",
        "https://example.org:70000",
        "https://example.org:0",
        "https://exa mple.org"
      })
  void rejectsInvalidUrls(String url) {
    assertThrows(IllegalArgumentException.class, () -> service.create(url, null));
    assertNull(service.currentUser());
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1, 101, Long.MAX_VALUE})
  void rejectsOutOfRangeLimits(long limit) {
    assertThrows(
        IllegalArgumentException.class, () -> service.create("https://example.org", limit));
  }

  @Test
  void rejectsUnknownUuidAndForeignShortUrl() throws IOException {
    var link = service.create("https://example.org", null);
    assertThrows(IllegalArgumentException.class, () -> service.useUser(UUID.randomUUID()));
    assertThrows(
        IllegalArgumentException.class, () -> service.visit("https://attacker.org/" + link.code()));
    assertEquals(0, service.info(link.code()).visits());
  }

  @Test
  void failedSaveDoesNotConsumeVisitOrChangeInMemoryState() throws IOException {
    var link = service.create("https://example.org", 1L);
    store.fail = true;
    assertThrows(IOException.class, () -> service.visit(link.code()));
    assertEquals(0, service.info(link.code()).visits());
    assertTrue(service.takeNotices().isEmpty());
    assertThrows(IOException.class, () -> service.delete(link.code()));
    assertEquals(link, service.info(link.code()));
  }

  @Test
  void failedCreationDoesNotPersistIdentity() {
    store.fail = true;
    assertThrows(IOException.class, () -> service.create("https://example.org", null));
    assertNull(service.currentUser());
  }

  @Test
  void concurrentVisitorsCannotExceedLimit() throws Exception {
    var link = service.create("https://example.org", 25L);
    var executor = Executors.newFixedThreadPool(8);
    try {
      List<Callable<Boolean>> tasks = new ArrayList<>();
      for (int i = 0; i < 100; i++) {
        tasks.add(
            () -> {
              try {
                service.visit(link.code());
                return true;
              } catch (IllegalArgumentException ex) {
                return false;
              }
            });
      }
      int successful = 0;
      for (var result : executor.invokeAll(tasks)) {
        if (result.get()) {
          successful++;
        }
      }
      assertEquals(25, successful);
      assertEquals(25, service.info(link.code()).visits());
      assertEquals(1, service.takeNotices().size());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  @SuppressWarnings("try")
  void backgroundWorkerDeletesWithoutConsoleCommands() throws Exception {
    service.create("https://example.org", null);
    clock.advance(Duration.ofDays(1));
    CountDownLatch cleaned = new CountDownLatch(1);
    List<Exception> errors = new ArrayList<>();
    try (CleanupWorker worker =
        new CleanupWorker(service, Duration.ofMillis(100), cleaned::countDown, errors::add)) {
      assertTrue(cleaned.await(5, TimeUnit.SECONDS));
      assertTrue(store.saved.links.isEmpty());
      assertTrue(errors.isEmpty());
    }
  }

  @Test
  void generatorProducesConfiguredBase62Length() {
    RandomCodeGenerator generator = new RandomCodeGenerator(8);
    for (int i = 0; i < 100; i++) {
      assertTrue(generator.nextCode().matches("[A-Za-z0-9]{8}"));
    }
  }
}
