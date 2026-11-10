package ru.marfines.shortener.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import ru.marfines.shortener.core.Model.Link;
import ru.marfines.shortener.core.Model.Notice;
import ru.marfines.shortener.core.Model.Reason;

/** Transaction workspace. Never exposed by the service; record values are immutable. */
public final class State {
  public final Set<UUID> users = new HashSet<>();
  public final Map<String, Link> links = new HashMap<>();
  // Tombstones contain no original URL or owner. Codes are never reused.
  public final Map<String, Reason> retired = new HashMap<>();
  public final List<Notice> notices = new ArrayList<>();
  public UUID currentUser;

  public State() {}

  public State(State source) {
    users.addAll(source.users);
    links.putAll(source.links);
    retired.putAll(source.retired);
    notices.addAll(source.notices);
    currentUser = source.currentUser;
  }
}
