package ru.marfines.shortener;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.marfines.shortener.config.AppConfig;

class AppConfigTest {
  @TempDir Path directory;

  private String validConfig() throws IOException {
    return Files.readString(Path.of("config/application.properties"));
  }

  @Test
  void loadsAllSettingsAndResolvesRelativeStoragePath() throws IOException {
    Path file = directory.resolve("application.properties");
    Files.writeString(file, validConfig());
    var config = AppConfig.load(file);
    assertEquals(Duration.ofDays(1), config.ttl());
    assertEquals(10, config.defaultLimit());
    assertEquals(1000000, config.maxLimit());
    assertEquals(8, config.codeLength());
    assertEquals(Duration.ofSeconds(5), config.cleanupInterval());
    assertEquals(directory.resolve("../data/state.xml").normalize(), config.storageFile());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "link.ttl=PT0S",
        "link.ttl=PT-1H",
        "link.ttl=not-a-duration",
        "cleanup.interval=not-a-duration",
        "link.default-limit=0",
        "link.max-limit=2",
        "link.code-length=1",
        "cleanup.interval=PT0S",
        "link.base-url=javascript:bad"
      })
  void rejectsInvalidSystemSettings(String replacement) throws IOException {
    String key = replacement.substring(0, replacement.indexOf('='));
    String changed =
        validConfig()
            .replaceAll("(?m)^" + java.util.regex.Pattern.quote(key) + "=.*$", replacement);
    Path file = directory.resolve("application.properties");
    Files.writeString(file, changed);
    assertThrows(IllegalArgumentException.class, () -> AppConfig.load(file));
  }

  @Test
  void missingSettingsAreNotSilentlyDefaulted() throws IOException {
    Path file = directory.resolve("application.properties");
    Files.writeString(file, "link.ttl=PT1H\n");
    assertThrows(IllegalArgumentException.class, () -> AppConfig.load(file));
  }
}
