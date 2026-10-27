package io.github.rennaraujo.sage.search;

import io.github.rennaraujo.sage.document.DocumentStore;
import io.github.rennaraujo.sage.search.embedding.EmbeddingConfig;
import io.github.rennaraujo.sage.search.embedding.EmbeddingModel;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RetrievalProperties.class)
@Import(EmbeddingConfig.class)
class SearchConfig {

    private static final Logger LOG = LoggerFactory.getLogger(SearchConfig.class);

    /** O nome do bean vira o nome do indicador em /actuator/health: "index". */
    @Bean("index")
    IndexReadiness indexReadiness() {
        return new IndexReadiness();
    }

    @Bean
    LuceneSearchIndex searchIndex(
            RetrievalProperties properties, IndexReadiness readiness, ObjectProvider<EmbeddingModel> embeddings) {
        Optional<EmbeddingModel> model = Optional.ofNullable(embeddings.getIfAvailable());
        if (model.isEmpty()) {
            LOG.warn("Modelo de embeddings ausente (sage.models.dir): busca híbrida desligada, só BM25. "
                    + "Rode scripts/download-models para baixá-lo.");
        } else {
            LOG.info("Busca híbrida ligada: {} dimensões", model.get().dimensions());
        }
        return new LuceneSearchIndex(properties, readiness, model);
    }

    @Bean
    IndexBootstrapper indexBootstrapper(DocumentStore store, SearchIndex index, IndexReadiness readiness) {
        return new IndexBootstrapper(store, index, readiness);
    }
}
