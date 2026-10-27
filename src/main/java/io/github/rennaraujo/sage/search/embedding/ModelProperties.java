package io.github.rennaraujo.sage.search.embedding;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** {@code sage.models.dir}: diretório com os arquivos dos modelos (embeddings e reranker). */
@Validated
@ConfigurationProperties("sage.models")
public record ModelProperties(@DefaultValue("models") Path dir) {

    public ModelFiles files() {
        return ModelFiles.inDir(dir);
    }

    public ModelFiles rerankerFiles() {
        return ModelFiles.rerankerInDir(dir);
    }
}
