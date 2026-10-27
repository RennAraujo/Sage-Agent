package io.github.rennaraujo.sage.search.rerank;

import io.github.rennaraujo.sage.search.embedding.ModelProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * O {@link Reranker} só existe se os arquivos do modelo estão no disco. Sem eles o bean some e o {@code Retriever}
 * usa o fallback (ver {@code docs/arquitetura.md}). O {@code close} do bean fecha a sessão ONNX no desligamento.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ModelProperties.class, RerankProperties.class})
public class RerankConfig {

    @Bean
    @Conditional(RerankerFilesPresent.class)
    OnnxReranker reranker(ModelProperties models, RerankProperties properties) {
        return new OnnxReranker(models.rerankerFiles(), properties);
    }
}
