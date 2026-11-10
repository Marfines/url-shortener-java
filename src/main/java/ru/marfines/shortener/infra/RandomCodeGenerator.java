package ru.marfines.shortener.infra;

import java.security.SecureRandom;
import ru.marfines.shortener.core.CodeGenerator;

public final class RandomCodeGenerator implements CodeGenerator {
  private static final String ALPHABET =
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
  private final SecureRandom random = new SecureRandom();
  private final int length;

  public RandomCodeGenerator(int length) {
    if (length < 6 || length > 32) {
      throw new IllegalArgumentException("Длина кода должна быть от 6 до 32.");
    }
    this.length = length;
  }

  @Override
  public String nextCode() {
    StringBuilder code = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return code.toString();
  }
}
