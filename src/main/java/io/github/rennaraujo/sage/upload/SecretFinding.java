package io.github.rennaraujo.sage.upload;

/** Um padrão de segredo encontrado: o tipo e a linha (a partir de 1). Nunca carrega o valor encontrado. */
public record SecretFinding(String type, int line) {}
