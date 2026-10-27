package io.github.rennaraujo.sage.search.embedding;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * O {@link EmbeddingModel} só existe se os arquivos do modelo estão no disco. Sem eles o bean some, o
 * {@code SearchConfig} avisa no log e o índice funciona só com BM25. O {@code close} do bean fecha a sessão ONNX no
 * desligamento (o Spring infere o método de {@link AutoCloseable}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ModelProperties.class)
public class EmbeddingConfig {

    @Bean
    @Conditional(ModelFilesPresent.class)
    OnnxEmbeddingModel embeddingModel(ModelProperties properties) {
        return new OnnxEmbeddingModel(properties.files());
    }
}
