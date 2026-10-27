package io.github.rennaraujo.sage.search.embedding;

import java.nio.file.Files;
import java.nio.file.Path;

/** Onde ficam os arquivos de um modelo ONNX (baixados por {@code scripts/download-models}). */
public record ModelFiles(Path model, Path tokenizer) {

    static final String MODEL_FILE = "model.onnx";
    static final String TOKENIZER_FILE = "tokenizer.json";
    static final String RERANKER_MODEL_FILE = "reranker.onnx";
    static final String RERANKER_TOKENIZER_FILE = "reranker-tokenizer.json";

    /** Os dois arquivos com os nomes padrão dentro de {@code dir}. */
    public static ModelFiles inDir(Path dir) {
        return new ModelFiles(dir.resolve(MODEL_FILE), dir.resolve(TOKENIZER_FILE));
    }

    /** Os arquivos do reranker (nomes próprios, para conviver com os de embeddings no mesmo diretório). */
    public static ModelFiles rerankerInDir(Path dir) {
        return new ModelFiles(dir.resolve(RERANKER_MODEL_FILE), dir.resolve(RERANKER_TOKENIZER_FILE));
    }

    /** {@code true} quando o modelo e o tokenizer existem. */
    public boolean available() {
        return Files.isRegularFile(model) && Files.isRegularFile(tokenizer);
    }
}
