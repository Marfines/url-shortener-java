package ru.marfines.shortener.core;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/** Immutable domain values shared by the service and persistence adapter. */
public final class Model {
  private Model() {}

  public enum Reason {
    LIMIT_REACHED,
    EXPIRED,
    DELETED
  }

  public record Link(
      String code,
      UUID owner,
      URI target,
      long limit,
      long visits,
      Instant createdAt,
      Instant expiresAt) {
    public Link withVisit() {
      return new Link(code, owner, target, limit, visits + 1, createdAt, expiresAt);
    }

    public Link withLimit(long value) {
      return new Link(code, owner, target, value, visits, createdAt, expiresAt);
    }

    public Link withTarget(URI value) {
      return new Link(code, owner, value, limit, visits, createdAt, expiresAt);
    }
  }

  public record Notice(UUID owner, String code, Reason reason, Instant occurredAt) {}
}
