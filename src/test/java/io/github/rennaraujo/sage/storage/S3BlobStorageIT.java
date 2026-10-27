package io.github.rennaraujo.sage.storage;

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
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

@Testcontainers(disabledWithoutDocker = true)
class S3BlobStorageIT {

    private static final String BUCKET = "sage-test";

    @Container
    static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.9"));

    @Test
    void putGetListDelete() {
        try (S3Client s3 = S3Client.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build()) {
            s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
            S3BlobStorage storage = new S3BlobStorage(s3, BUCKET);
            byte[] content = "conteudo".getBytes(StandardCharsets.UTF_8);

            storage.put("docs/aaa-111", content);
            storage.put("docs/bbb-222", content);
            storage.put("outros/ccc", content);

            assertThat(storage.get("docs/aaa-111")).hasValue(content);
            assertThat(storage.get("docs/nada")).isEmpty();
            assertThat(storage.list("docs/")).containsExactly("docs/aaa-111", "docs/bbb-222");

            storage.delete("docs/aaa-111");

            assertThat(storage.get("docs/aaa-111")).isEmpty();
            assertThat(storage.list("docs/")).containsExactly("docs/bbb-222");
        }
    }
}
