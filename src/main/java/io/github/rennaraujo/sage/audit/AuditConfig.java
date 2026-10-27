package io.github.rennaraujo.sage.audit;

import io.github.rennaraujo.sage.crypto.EnvelopeCipher;
import io.github.rennaraujo.sage.storage.BlobStorage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AuditConfig {

    @Bean
    AuditLog auditLog(EnvelopeCipher cipher, BlobStorage blobs) {
        return new EncryptedAuditLog(cipher, blobs);
    }
}
