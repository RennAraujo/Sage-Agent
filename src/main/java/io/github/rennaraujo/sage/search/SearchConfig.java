package io.github.rennaraujo.sage.search;

import io.github.rennaraujo.sage.document.DocumentStore;
import io.github.rennaraujo.sage.search.embedding.EmbeddingConfig;
import io.github.rennaraujo.sage.search.embedding.EmbeddingModel;
import io.github.rennaraujo.sage.search.rerank.RerankConfig;
import io.github.rennaraujo.sage.search.rerank.Reranker;
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
@Import({EmbeddingConfig.class, RerankConfig.class})
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
    Retriever retriever(
            SearchIndex index,
            ObjectProvider<Reranker> rerankers,
            RetrievalProperties properties,
            ObjectProvider<RerankQueryCleaner> cleaners) {
        Optional<Reranker> reranker = Optional.ofNullable(rerankers.getIfAvailable());
        if (reranker.isEmpty()) {
            LOG.warn("Modelo de reranking ausente (sage.models.dir): o score passa a ser a cobertura lexical dos "
                    + "termos da pergunta, não uma nota do reranker. Rode scripts/download-models para baixá-lo.");
        } else {
            LOG.info(
                    "Reranker ligado: {} candidatos (busca com profundidade {}), {} no contexto",
                    properties.candidates(),
                    properties.searchDepth(),
                    properties.contextChunks());
        }
        return new Retriever(
                index,
                reranker,
                properties,
                Optional.ofNullable(cleaners.getIfAvailable()).orElse(RerankQueryCleaner.NONE));
    }

    @Bean
    IndexBootstrapper indexBootstrapper(DocumentStore store, SearchIndex index, IndexReadiness readiness) {
        return new IndexBootstrapper(store, index, readiness);
    }
}
