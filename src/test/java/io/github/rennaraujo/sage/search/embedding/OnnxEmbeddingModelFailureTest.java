package io.github.rennaraujo.sage.search.embedding;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rennaraujo.sage.testsupport.ScratchDirs;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Caminhos de falha do construtor; não precisam dos arquivos reais do modelo. */
class OnnxEmbeddingModelFailureTest {

    private Path dir;

    @BeforeEach
    void setUp() {
        dir = ScratchDirs.create("sage-model");
    }

    @AfterEach
    void tearDown() {
        ScratchDirs.delete(dir);
    }

    @Test
    void corruptModelFileMakesTheConstructorThrow() throws Exception {
        Files.write(dir.resolve("model.onnx"), "isto não é um modelo".getBytes(StandardCharsets.UTF_8));
        Files.writeString(dir.resolve("tokenizer.json"), "{}");

        assertThatThrownBy(() -> new OnnxEmbeddingModel(ModelFiles.inDir(dir)))
                .isInstanceOf(IllegalStateException.class);
    }
}
