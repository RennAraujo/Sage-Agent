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
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/** Blobs num bucket S3. O conteúdo já chega cifrado pelo {@code EnvelopeCipher}; o bucket só vê bytes opacos. */
public final class S3BlobStorage implements BlobStorage {

    private final S3Client s3;
    private final String bucket;
    private final Optional<String> kmsKeyId;

    /** Com a chave KMS padrão do bucket. */
    public S3BlobStorage(S3Client s3, String bucket) {
        this(s3, bucket, Optional.empty());
    }

    /**
     * @param kmsKeyId a chave KMS (id, ARN ou alias) que o S3 usa no SSE-KMS de cada objeto; vazio usa a chave padrão
     *     do bucket
     */
    public S3BlobStorage(S3Client s3, String bucket, Optional<String> kmsKeyId) {
        this.s3 = s3;
        this.bucket = bucket;
        this.kmsKeyId = kmsKeyId.filter(k -> !k.isBlank());
    }

    @Override
    public void put(String key, byte[] content) {
        BlobKeys.requireValidKey(key);
        // Pede SSE-KMS de forma explícita: assim uma política de bucket que nega escrita sem esse cabeçalho não
        // bloqueia
        // o Sage, e o conteúdo, que já chega cifrado, ganha a segunda camada. Com sage.storage.s3-kms-key-id, a chave
        // é a gerenciada pelo cliente; sem ele, o S3 usa a padrão do bucket (ou a aws/s3).
        PutObjectRequest.Builder request =
                PutObjectRequest.builder().bucket(bucket).key(key).serverSideEncryption(ServerSideEncryption.AWS_KMS);
        kmsKeyId.ifPresent(request::ssekmsKeyId);
        s3.putObject(request.build(), RequestBody.fromBytes(content));
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
