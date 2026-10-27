package io.github.rennaraujo.sage.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;

@Testcontainers(disabledWithoutDocker = true)
class KmsKeyProviderIT {

    @Container
    static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.9"));

    @Test
    void generateAndDecryptWithKms() {
        try (KmsClient kms = KmsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build()) {
            String keyId = kms.createKey().keyMetadata().keyId();
            EnvelopeCipher cipher = new EnvelopeCipher(new KmsKeyProvider(kms, keyId));
            byte[] original = "SEGREDO-CANARIO-123".getBytes(StandardCharsets.UTF_8);

            EncryptedBlob blob =
                    EncryptedBlob.fromBytes(cipher.encrypt(original).toBytes());

            assertThat(cipher.decrypt(blob)).isEqualTo(original);
        }
    }
}
