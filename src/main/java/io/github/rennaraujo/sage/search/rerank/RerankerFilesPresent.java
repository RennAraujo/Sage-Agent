package io.github.rennaraujo.sage.search.rerank;

import io.github.rennaraujo.sage.search.embedding.ModelFiles;
import java.nio.file.Path;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Verdadeira quando os arquivos do reranker existem em {@code sage.models.dir}. */
final class RerankerFilesPresent implements Condition {

    private static final String DEFAULT_DIR = "models";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String dir = context.getEnvironment().getProperty("sage.models.dir", DEFAULT_DIR);
        return ModelFiles.rerankerInDir(Path.of(dir)).available();
    }
}
