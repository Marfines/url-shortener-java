package ru.marfines.shortener;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.marfines.shortener.ui.Browser;
import ru.marfines.shortener.ui.ConsoleApp;

class ConsoleIntegrationTest {
  @Test
  void fullConsoleFlowCreatesOpensBlocksAndEdits() throws IOException {
    var service =
        TestSupport.service(
            new TestSupport.MemoryStore(), new TestSupport.MutableClock(), Duration.ofDays(1));
    List<URI> opened = new ArrayList<>();
    StringWriter output = new StringWriter();
    String commands =
        """
        help
        shorten https://example.org/a 1
        https://short.local/code0001
        open code0001
        limit code0001 2
        url code0001 https://example.net/new
        open code0001
        list
        delete code0001
        exit
        """;
    new ConsoleApp(
            service,
            opened::add,
            new BufferedReader(new StringReader(commands)),
            new PrintWriter(output))
        .run();
    assertEquals(
        List.of(URI.create("https://example.org/a"), URI.create("https://example.net/new")),
        opened);
    assertTrue(output.toString().contains("лимит переходов исчерпан"));
    assertTrue(output.toString().contains("Ссылка удалена"));
    assertTrue(service.list().isEmpty());
  }

  @Test
  void invalidCommandsDoNotTerminateSession() throws IOException {
    var service =
        TestSupport.service(
            new TestSupport.MemoryStore(), new TestSupport.MutableClock(), Duration.ofDays(1));
    StringWriter output = new StringWriter();
    String commands =
        "shorten\nshorten https://example.org abc\nuser use bad\nunknown command\nshorten https://example.org 2\nexit\n";
    new ConsoleApp(
            service,
            uri -> fail("Must not open"),
            new BufferedReader(new StringReader(commands)),
            new PrintWriter(output))
        .run();
    assertEquals(1, service.list().size());
    assertEquals(4, output.toString().lines().filter(line -> line.contains("Ошибка:")).count());
  }

  @Test
  void browserFailurePrintsTargetAndExplainsCountedRedirect() throws IOException {
    var service =
        TestSupport.service(
            new TestSupport.MemoryStore(), new TestSupport.MutableClock(), Duration.ofDays(1));
    var link = service.create("https://example.org", 1L);
    StringWriter output = new StringWriter();
    Browser browser =
        uri -> {
          throw new IOException("headless");
        };
    ConsoleApp app =
        new ConsoleApp(
            service, browser, new BufferedReader(new StringReader("")), new PrintWriter(output));
    assertTrue(app.execute("open " + link.code()));
    assertTrue(output.toString().contains("Откройте адрес вручную: https://example.org"));
    assertTrue(output.toString().contains("Переход уже учтён"));
    assertEquals(1, service.info(link.code()).visits());
  }

  @Test
  void eofExitsCleanly() throws IOException {
    var service =
        TestSupport.service(
            new TestSupport.MemoryStore(), new TestSupport.MutableClock(), Duration.ofDays(1));
    StringWriter output = new StringWriter();
    new ConsoleApp(
            service, uri -> {}, new BufferedReader(new StringReader("")), new PrintWriter(output))
        .run();
    assertTrue(output.toString().contains("До встречи"));
  }
}
