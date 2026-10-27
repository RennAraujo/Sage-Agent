package io.github.rennaraujo.sage.gap;

import io.github.rennaraujo.sage.crypto.EnvelopeCipher;
import io.github.rennaraujo.sage.storage.BlobStorage;
import java.time.Clock;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GapProperties.class)
class GapConfig {

    /** Aquece o registro em segundo plano na subida, para a primeira lacuna não esperar a leitura de todas. */
    @Bean
    ApplicationListener<ApplicationReadyEvent> gapWarmup(GapRegistry registry) {
        return event -> Thread.ofVirtual().name("gap-warmup").start(registry::warm);
    }

    @Bean
    GapRegistry gapRegistry(EnvelopeCipher cipher, BlobStorage blobs, GapProperties properties) {
        return new GapRegistry(cipher, blobs, properties, Clock.systemUTC());
    }
}
