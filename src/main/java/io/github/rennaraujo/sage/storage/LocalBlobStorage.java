package io.github.rennaraujo.sage.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** Guarda cada blob como um arquivo sob um diretório raiz. Escrita atômica: arquivo temporário + rename. */
public final class LocalBlobStorage implements BlobStorage {

    private static final String TEMP_PREFIX = ".sage-";
    private static final String TEMP_SUFFIX = ".tmp";

    private final Path root;

    public LocalBlobStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, byte[] content) {
        Path target = resolve(key);
        Path temp = null;
        try {
            Files.createDirectories(target.getParent());
            temp = Files.createTempFile(target.getParent(), TEMP_PREFIX, TEMP_SUFFIX);
            Files.write(temp, content);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new UncheckedIOException("Falha ao gravar o blob " + key, e);
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        try {
            return Optional.of(Files.readAllBytes(resolve(key)));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler o blob " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao apagar o blob " + key, e);
        }
    }

    @Override
    public List<String> list(String prefix) {
        BlobKeys.requireValidPrefix(prefix);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .map(file -> root.relativize(file).toString().replace('\\', '/'))
                    .filter(BlobKeys::isValidKey)
                    .filter(key -> key.startsWith(prefix))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao listar blobs", e);
        }
    }

    private Path resolve(String key) {
        BlobKeys.requireValidKey(key);
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IllegalArgumentException("Chave de blob fora do diretório de armazenamento");
        }
        return resolved;
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // melhor esforço: o erro original é o que importa
        }
    }
}
