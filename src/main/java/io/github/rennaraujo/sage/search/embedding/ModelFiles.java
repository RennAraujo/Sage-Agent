package io.github.rennaraujo.sage.search.embedding;

import java.nio.file.Files;
import java.nio.file.Path;

/** Onde ficam os arquivos do modelo de embeddings (baixados por {@code scripts/download-models}). */
public record ModelFiles(Path model, Path tokenizer) {

    static final String MODEL_FILE = "model.onnx";
    static final String TOKENIZER_FILE = "tokenizer.json";

    /** Os dois arquivos com os nomes padrão dentro de {@code dir}. */
    public static ModelFiles inDir(Path dir) {
        return new ModelFiles(dir.resolve(MODEL_FILE), dir.resolve(TOKENIZER_FILE));
    }

    /** {@code true} quando o modelo e o tokenizer existem. */
    public boolean available() {
        return Files.isRegularFile(model) && Files.isRegularFile(tokenizer);
    }
}
