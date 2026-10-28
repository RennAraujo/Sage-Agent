package io.github.rennaraujo.sage.storage;

import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
class StorageConfig {

    @Bean
    @ConditionalOnProperty(prefix = "sage.storage", name = "type", havingValue = "local", matchIfMissing = true)
    BlobStorage localBlobStorage(StorageProperties properties) {
        return new LocalBlobStorage(properties.localDir());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "sage.storage", name = "type", havingValue = "s3")
    S3Client s3Client() {
        return S3Client.create();
    }

    @Bean
    @ConditionalOnProperty(prefix = "sage.storage", name = "type", havingValue = "s3")
    BlobStorage s3BlobStorage(S3Client s3Client, StorageProperties properties) {
        return new S3BlobStorage(s3Client, properties.s3Bucket(), Optional.ofNullable(properties.s3KmsKeyId()));
    }
}
