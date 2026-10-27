package io.github.rennaraujo.sage.question.guard;

import java.nio.file.Files;
import java.nio.file.Path;

/** Arquivos do modelo de NLI: ONNX, tokenizer e o {@code config.json} que diz qual saída é cada rótulo. */
public record NliFiles(Path model, Path tokenizer, Path config) {

    static final String MODEL_FILE = "nli.onnx";
    static final String TOKENIZER_FILE = "nli-tokenizer.json";
    static final String CONFIG_FILE = "nli-config.json";

    /** Os arquivos com os nomes de {@code models.lock} dentro de {@code dir}. */
    public static NliFiles inDir(Path dir) {
        return new NliFiles(dir.resolve(MODEL_FILE), dir.resolve(TOKENIZER_FILE), dir.resolve(CONFIG_FILE));
    }

    /** {@code true} quando os três arquivos existem. */
    public boolean available() {
        return Files.isRegularFile(model) && Files.isRegularFile(tokenizer) && Files.isRegularFile(config);
    }
}
