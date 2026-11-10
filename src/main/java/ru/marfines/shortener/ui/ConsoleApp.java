package ru.marfines.shortener.ui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.util.UUID;
import ru.marfines.shortener.core.Model.Link;
import ru.marfines.shortener.core.Model.Reason;
import ru.marfines.shortener.core.ShortenerService;

public final class ConsoleApp {
  private static final String HELP =
      """
      Команды (параметры разделяются пробелами):
        shorten <URL> [лимит]       создать ссылку; UUID появится при первом создании
        open <ссылка|код>           открыть исходный URL в браузере
        <короткая ссылка>          то же, что open; можно ввести только код
        list                       мои ссылки, счётчики и срок действия
        info <ссылка|код>           подробности моей ссылки
        limit <ссылка|код> <число>  изменить общий лимит (не меньше использованного)
        url <ссылка|код> <URL>      изменить адрес назначения
        delete <ссылка|код>         удалить свою ссылку
        user                       показать текущий UUID
        user new                   начать новый профиль; старый UUID сохраните
        user use <UUID>            вернуться к существующему профилю
        notifications              показать новые уведомления
        help                       помощь
        exit                       выход
      Пример: shorten https://example.org/article 3
      TTL задаёт система. Даты отображаются в UTC (суффикс Z).
      Короткие ссылки работают внутри этой консоли; HTTP-сервер не запускается.
      """;
  private final ShortenerService service;
  private final Browser browser;
  private final BufferedReader input;
  private final PrintWriter output;

  public ConsoleApp(
      ShortenerService service, Browser browser, BufferedReader input, PrintWriter output) {
    this.service = service;
    this.browser = browser;
    this.input = input;
    this.output = output;
  }

  public void run() throws IOException {
    output.println("Сервис сокращения ссылок — введите help для справки.");
    showUser();
    showNotices();
    while (true) {
      output.print("> ");
      output.flush();
      String line = input.readLine();
      if (line == null || !execute(line)) {
        break;
      }
    }
    output.println("Данные сохранены. До встречи!");
    output.flush();
  }

  public boolean execute(String line) {
    String trimmed = line.trim();
    if (trimmed.isEmpty()) {
      return true;
    }
    String[] args = trimmed.split("\\s+");
    try {
      switch (args[0]) {
        case "help" -> {
          arity(args, 1, 1);
          output.println(HELP);
        }
        case "exit", "quit" -> {
          arity(args, 1, 1);
          return false;
        }
        case "shorten" -> {
          arity(args, 2, 3);
          Link link = service.create(args[1], args.length == 3 ? number(args[2]) : null);
          output.println("Создана: " + service.shortUrl(link));
          output.println("Ваш UUID: " + link.owner() + " — сохраните его для возврата к профилю.");
          printLink(link);
        }
        case "open" -> {
          arity(args, 2, 2);
          open(args[1]);
        }
        case "list" -> {
          arity(args, 1, 1);
          var links = service.list();
          if (links.isEmpty()) {
            output.println("У вас нет действующих записей ссылок.");
          }
          links.forEach(this::printLink);
        }
        case "info" -> {
          arity(args, 2, 2);
          printLink(service.info(args[1]));
        }
        case "limit" -> {
          arity(args, 3, 3);
          printLink(service.changeLimit(args[1], number(args[2])));
        }
        case "url" -> {
          arity(args, 3, 3);
          printLink(service.changeTarget(args[1], args[2]));
        }
        case "delete" -> {
          arity(args, 2, 2);
          service.delete(args[1]);
          output.println("Ссылка удалена.");
        }
        case "user" -> user(args);
        case "notifications" -> {
          arity(args, 1, 1);
          if (!showNotices()) {
            output.println("Новых уведомлений нет.");
          }
        }
        default -> {
          if (args.length == 1
              && (args[0].startsWith("http") || args[0].matches("[A-Za-z0-9]{6,32}"))) {
            open(args[0]);
          } else {
            throw new IllegalArgumentException("Неизвестная команда. Введите help.");
          }
        }
      }
    } catch (IllegalArgumentException | IllegalStateException ex) {
      output.println("Ошибка: " + ex.getMessage());
    } catch (IOException ex) {
      output.println("Ошибка хранилища; операция не завершена: " + ex.getMessage());
    }
    showNotices();
    output.flush();
    return true;
  }

  /** Also called by the cleanup worker, so messages appear while waiting for input. */
  public synchronized boolean showNotices() {
    try {
      var notices = service.takeNotices();
      for (var notice : notices) {
        String message =
            notice.reason() == Reason.EXPIRED
                ? "время жизни истекло; ссылка автоматически удалена."
                : "лимит переходов исчерпан; следующие переходы заблокированы.";
        output.println("\nУведомление [" + notice.code() + "]: " + message);
      }
      output.flush();
      return !notices.isEmpty();
    } catch (IOException ex) {
      output.println("Не удалось прочитать уведомления: " + ex.getMessage());
      output.flush();
      return false;
    }
  }

  private void user(String[] args) throws IOException {
    if (args.length == 1) {
      showUser();
    } else if (args.length == 2 && args[1].equals("new")) {
      UUID previous = service.currentUser();
      service.newUser();
      if (previous != null) {
        output.println("Предыдущий UUID: " + previous);
      }
      output.println("Новый профиль: UUID будет создан при первой команде shorten.");
    } else if (args.length == 3 && args[1].equals("use")) {
      UUID user;
      try {
        user = UUID.fromString(args[2]);
        if (!user.toString().equalsIgnoreCase(args[2])) {
          throw new IllegalArgumentException();
        }
      } catch (IllegalArgumentException ex) {
        throw new IllegalArgumentException(
            "Некорректный UUID. Пример формата: 123e4567-e89b-12d3-a456-426614174000");
      }
      service.useUser(user);
      showUser();
    } else {
      throw new IllegalArgumentException("Используйте user, user new или user use <UUID>.");
    }
  }

  private void showUser() {
    UUID user = service.currentUser();
    output.println(
        user == null ? "Профиль ещё не создан. Начните с shorten <URL>." : "Текущий UUID: " + user);
  }

  private void open(String reference) throws IOException {
    URI target = service.visit(reference);
    try {
      browser.open(target);
      output.println("Открываю: " + target);
    } catch (IOException ex) {
      output.println("Браузер не открылся: " + ex.getMessage());
      output.println("Переход уже учтён. Откройте адрес вручную: " + target);
    }
  }

  private void printLink(Link link) {
    output.println(service.shortUrl(link) + " -> " + link.target());
    output.println(
        "  Переходы: "
            + link.visits()
            + "/"
            + link.limit()
            + "; действует до "
            + link.expiresAt()
            + (link.visits() >= link.limit() ? "; ЛИМИТ ИСЧЕРПАН" : ""));
  }

  private static long number(String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("Лимит должен быть целым числом в допустимом диапазоне.");
    }
  }

  private static void arity(String[] args, int min, int max) {
    if (args.length < min || args.length > max) {
      throw new IllegalArgumentException("Неверное число аргументов. Примеры команд: help.");
    }
  }
}
