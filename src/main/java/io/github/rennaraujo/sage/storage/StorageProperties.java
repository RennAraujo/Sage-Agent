package io.github.rennaraujo.sage.storage;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("sage.storage")
public record StorageProperties(
        @NotNull @DefaultValue("local") Type type,
        @NotNull @DefaultValue("data") Path localDir,
        @DefaultValue("") String s3Bucket,
        @DefaultValue("") String s3KmsKeyId) {

    public enum Type {
        LOCAL,
        S3
    }

    @AssertTrue(message = "sage.storage.s3-bucket é obrigatório quando sage.storage.type=s3")
    public boolean isS3BucketPresentWhenS3() {
        return type != Type.S3 || !s3Bucket.isBlank();
    }
}
