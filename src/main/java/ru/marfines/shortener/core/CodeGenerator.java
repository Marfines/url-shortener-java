package ru.marfines.shortener.core;

@FunctionalInterface
public interface CodeGenerator {
  String nextCode();
}
