package ru.marfines.shortener.infra;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import ru.marfines.shortener.core.ShortenerService;

/** Cleans expired links even while the CLI is waiting for input. */
public final class CleanupWorker implements AutoCloseable {
  private final ScheduledExecutorService executor;

  public CleanupWorker(
      ShortenerService service,
      Duration interval,
      Runnable afterCleanup,
      Consumer<Exception> onError) {
    executor =
        Executors.newSingleThreadScheduledExecutor(
            task -> {
              Thread thread = new Thread(task, "link-expiration");
              thread.setDaemon(true);
              return thread;
            });
    executor.scheduleWithFixedDelay(
        () -> {
          try {
            service.cleanup();
            afterCleanup.run();
          } catch (Exception ex) {
            onError.accept(ex);
          }
        },
        interval.toMillis(),
        interval.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  @Override
  public void close() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException ex) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
