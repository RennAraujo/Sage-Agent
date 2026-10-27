package io.github.rennaraujo.sage.search.embedding;

import java.nio.file.Path;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Verdadeira quando {@code model.onnx} e {@code tokenizer.json} existem em {@code sage.models.dir}. */
final class ModelFilesPresent implements Condition {

    private static final String DEFAULT_DIR = "models";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String dir = context.getEnvironment().getProperty("sage.models.dir", DEFAULT_DIR);
        return ModelFiles.inDir(Path.of(dir)).available();
    }
}
