package io.github.rennaraujo.sage.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Diretório temporário de teste com limpeza que insiste. No Windows, um processo externo (antivírus ou indexador)
 * às vezes cria e apaga arquivos de nome estranho (visto: {@code .SAGE-<n>.TMP.tmp}) dentro do diretório exatamente
 * quando o {@code @TempDir} do JUnit tenta removê-lo, e a limpeza falha com {@code DirectoryNotEmptyException} ou
 * {@code NoSuchFileException}. Aqui a remoção é repetida por alguns segundos; se mesmo assim não der, o teste falha
 * de verdade (nada é silenciado).
 */
public final class ScratchDirs {

    private static final int MAX_ATTEMPTS = 50;
    private static final long BACKOFF_MILLIS = 100;

    private ScratchDirs() {}

    public static Path create(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void delete(Path dir) {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                deleteTree(dir);
                return;
            } catch (IOException e) {
                last = e;
                pause();
            }
        }
        throw new UncheckedIOException("Não foi possível apagar o diretório de teste", last);
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void pause() {
        try {
            Thread.sleep(BACKOFF_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
