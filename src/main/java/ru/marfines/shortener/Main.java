package ru.marfines.shortener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import ru.marfines.shortener.config.AppConfig;
import ru.marfines.shortener.core.ShortenerService;
import ru.marfines.shortener.infra.CleanupWorker;
import ru.marfines.shortener.infra.DesktopBrowser;
import ru.marfines.shortener.infra.FileStateStore;
import ru.marfines.shortener.infra.RandomCodeGenerator;
import ru.marfines.shortener.ui.ConsoleApp;

public final class Main {
  private Main() {}

  @SuppressWarnings("try") // Worker lifetime follows the interactive session.
  public static void main(String[] args) {
    if (args.length > 1) {
      System.err.println(
          "Запуск: java -jar target/url-shortener.jar [путь/к/application.properties]");
      System.exit(1);
    }
    var console = System.console();
    PrintWriter output =
        console == null
            ? new PrintWriter(System.out, true, StandardCharsets.UTF_8)
            : console.writer();
    BufferedReader input =
        new BufferedReader(
            console == null
                ? new InputStreamReader(System.in, StandardCharsets.UTF_8)
                : console.reader());
    try {
      AppConfig config =
          AppConfig.load(Path.of(args.length == 0 ? "config/application.properties" : args[0]));
      try (FileStateStore store = new FileStateStore(config.storageFile())) {
        ShortenerService service =
            new ShortenerService(
                config, store, Clock.systemUTC(), new RandomCodeGenerator(config.codeLength()));
        ConsoleApp app = new ConsoleApp(service, new DesktopBrowser(), input, output);
        try (CleanupWorker worker =
            new CleanupWorker(
                service,
                config.cleanupInterval(),
                app::showNotices,
                ex -> output.println("Ошибка автоматической очистки: " + ex.getMessage()))) {
          app.run();
        }
      }
    } catch (IOException | IllegalArgumentException ex) {
      output.println("Не удалось запустить сервис: " + ex.getMessage());
      output.flush();
      System.exit(1);
    }
  }
}
