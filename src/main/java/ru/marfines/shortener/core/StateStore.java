package ru.marfines.shortener.core;

import java.io.IOException;

/** A save must either replace the whole snapshot or leave the previous one intact. */
public interface StateStore {
  State load() throws IOException;

  void save(State state) throws IOException;
}
