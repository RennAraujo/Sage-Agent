package io.github.rennaraujo.sage.storage;

import java.util.List;
import java.util.Optional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

/** Blobs num bucket S3. O conteúdo já chega cifrado pelo {@code EnvelopeCipher}; o bucket só vê bytes opacos. */
public final class S3BlobStorage implements BlobStorage {

    private final S3Client s3;
    private final String bucket;

    public S3BlobStorage(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public void put(String key, byte[] content) {
        BlobKeys.requireValidKey(key);
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromBytes(content));
    }

    @Override
    public Optional<byte[]> get(String key) {
        BlobKeys.requireValidKey(key);
        try {
            return Optional.of(s3.getObjectAsBytes(
                            GetObjectRequest.builder().bucket(bucket).key(key).build())
                    .asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public void delete(String key) {
        BlobKeys.requireValidKey(key);
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public List<String> list(String prefix) {
        BlobKeys.requireValidPrefix(prefix);
        return s3
                .listObjectsV2Paginator(ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .prefix(prefix)
                        .build())
                .contents()
                .stream()
                .map(S3Object::key)
                .sorted()
                .toList();
    }
}
