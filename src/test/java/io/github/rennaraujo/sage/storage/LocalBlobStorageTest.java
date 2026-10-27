package io.github.rennaraujo.sage.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rennaraujo.sage.testsupport.ScratchDirs;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LocalBlobStorageTest {

    private Path root;

    private LocalBlobStorage storage;

    @BeforeEach
    void setUp() {
        root = ScratchDirs.create("sage-blobs");
        storage = new LocalBlobStorage(root);
    }

    @AfterEach
    void tearDown() {
        ScratchDirs.delete(root);
    }

    @Test
    void putGetListDelete() {
        byte[] content = "conteudo".getBytes(StandardCharsets.UTF_8);

        storage.put("docs/aaa-111", content);
        storage.put("docs/bbb-222", content);
        storage.put("outros/ccc", content);

        assertThat(storage.get("docs/aaa-111")).hasValue(content);
        assertThat(storage.list("docs/")).containsExactly("docs/aaa-111", "docs/bbb-222");
        assertThat(storage.list("")).containsExactly("docs/aaa-111", "docs/bbb-222", "outros/ccc");

        storage.delete("docs/aaa-111");

        assertThat(storage.get("docs/aaa-111")).isEmpty();
        assertThat(storage.list("docs/")).containsExactly("docs/bbb-222");
    }

    @Test
    void putReplacesExistingAndLeavesNoTempFiles() throws Exception {
        storage.put("docs/a", new byte[] {1});
        storage.put("docs/a", new byte[] {2, 3});

        assertThat(storage.get("docs/a")).hasValue(new byte[] {2, 3});
        try (Stream<Path> files = Files.walk(root)) {
            assertThat(files.filter(Files::isRegularFile)).hasSize(1);
        }
    }

    @Test
    void missingAndEmptyCases() {
        assertThat(storage.get("docs/nada")).isEmpty();
        storage.delete("docs/nada");
        assertThat(new LocalBlobStorage(root.resolve("nao-existe")).list("docs/"))
                .isEqualTo(List.of());
    }

    @Test
    void rejectsTraversalKey() {
        assertThatThrownBy(() -> storage.put("../x", new byte[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.get("../x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete("../x")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/abs", "a//b", "a/", "A/b", "a b", "a/../b", "a\\b", "docs/x.md", ""})
    void rejectsOtherInvalidKeys(String key) {
        assertThatThrownBy(() -> storage.get(key)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listIgnoresFilesThatAreNotValidKeys() throws Exception {
        storage.put("docs/a", new byte[] {1});
        Files.write(root.resolve("docs/.sage-123.tmp"), new byte[] {2});
        Files.write(root.resolve("docs/Estranho.TXT"), new byte[] {3});

        assertThat(storage.list("")).containsExactly("docs/a");
    }

    @Test
    void rejectsInvalidPrefix() {
        assertThatThrownBy(() -> storage.list("../")).isInstanceOf(IllegalArgumentException.class);
    }
}
