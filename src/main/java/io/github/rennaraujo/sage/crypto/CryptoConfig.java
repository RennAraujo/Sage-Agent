package io.github.rennaraujo.sage.crypto;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DescribeKeyRequest;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CryptoProperties.class)
class CryptoConfig {

    @Bean
    @ConditionalOnProperty(prefix = "sage.crypto", name = "provider", havingValue = "local", matchIfMissing = true)
    KeyProvider localKeyProvider(CryptoProperties properties) {
        return LocalKeyProvider.fromFile(properties.localKeyFile());
    }

    @Bean
    @ConditionalOnProperty(prefix = "sage.crypto", name = "provider", havingValue = "kms")
    KmsClient kmsClient() {
        return KmsClient.create();
    }

    @Bean
    @ConditionalOnProperty(prefix = "sage.crypto", name = "provider", havingValue = "kms")
    KeyProvider kmsKeyProvider(KmsClient kmsClient, CryptoProperties properties) {
        verifyKeyIsUsable(kmsClient, properties.kmsKeyId());
        return new KmsKeyProvider(kmsClient, properties.kmsKeyId());
    }

    @Bean
    EnvelopeCipher envelopeCipher(KeyProvider keyProvider) {
        return new EnvelopeCipher(keyProvider);
    }

    /** Falha na subida (e não no primeiro upload) se a chave não existe ou falta permissão. */
    private static void verifyKeyIsUsable(KmsClient kmsClient, String keyId) {
        try {
            kmsClient.describeKey(DescribeKeyRequest.builder().keyId(keyId).build());
        } catch (SdkException e) {
            throw new IllegalStateException(
                    "Não consegui acessar a chave KMS configurada em sage.crypto.kms-key-id (" + keyId
                            + "). Confira o id/alias, a região e a permissão kms:DescribeKey.",
                    e);
        }
    }
}
