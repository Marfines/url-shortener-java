package ru.marfines.shortener.infra;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import ru.marfines.shortener.ui.Browser;

public final class DesktopBrowser implements Browser {
  @Override
  public void open(URI uri) throws IOException {
    if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
      throw new IOException("Открытие браузера не поддерживается в этом окружении.");
    }
    try {
      Desktop.getDesktop().browse(uri);
    } catch (SecurityException | UnsupportedOperationException ex) {
      throw new IOException("Окружение запретило открытие браузера.", ex);
    }
  }
}
