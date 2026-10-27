package io.github.rennaraujo.sage.document;

import io.github.rennaraujo.sage.crypto.EnvelopeCipher;
import io.github.rennaraujo.sage.storage.BlobStorage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class DocumentConfig {

    @Bean
    FrontmatterParser frontmatterParser() {
        return new FrontmatterParser();
    }

    @Bean
    DocumentStore documentStore(
            EnvelopeCipher cipher, BlobStorage blobs, FrontmatterParser parser, ApplicationEventPublisher events) {
        return new EncryptedDocumentStore(cipher, blobs, parser, events);
    }
}
