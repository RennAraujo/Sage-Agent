package io.github.rennaraujo.sage.search;

import io.github.rennaraujo.sage.document.DocumentStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RetrievalProperties.class)
class SearchConfig {

    /** O nome do bean vira o nome do indicador em /actuator/health: "index". */
    @Bean("index")
    IndexReadiness indexReadiness() {
        return new IndexReadiness();
    }

    @Bean
    LuceneSearchIndex searchIndex(RetrievalProperties properties, IndexReadiness readiness) {
        return new LuceneSearchIndex(properties, readiness);
    }

    @Bean
    IndexBootstrapper indexBootstrapper(DocumentStore store, SearchIndex index, IndexReadiness readiness) {
        return new IndexBootstrapper(store, index, readiness);
    }
}
