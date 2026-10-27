package io.github.rennaraujo.sage.storage;

import java.util.regex.Pattern;

/** Regras de chave compartilhadas pelas implementações de {@link BlobStorage}. */
final class BlobKeys {

    private static final Pattern ALLOWED = Pattern.compile("[a-z0-9/-]+");

    private BlobKeys() {}

    static boolean isValidKey(String key) {
        try {
            requireValidKey(key);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static String requireValidKey(String key) {
        if (key == null
                || !ALLOWED.matcher(key).matches()
                || key.startsWith("/")
                || key.endsWith("/")
                || key.contains("//")) {
            throw new IllegalArgumentException(
                    "Chave de blob inválida: use apenas [a-z0-9/-], sem barra no início, no fim ou repetida");
        }
        return key;
    }

    /** Prefixo pode ser vazio ou terminar em barra, mas segue o mesmo alfabeto das chaves. */
    static String requireValidPrefix(String prefix) {
        if (prefix == null
                || (!prefix.isEmpty() && !ALLOWED.matcher(prefix).matches())
                || prefix.startsWith("/")
                || prefix.contains("//")) {
            throw new IllegalArgumentException("Prefixo de blob inválido: use apenas [a-z0-9/-], sem barra no início");
        }
        return prefix;
    }
}
