package io.github.rennaraujo.sage.crypto;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DataKeySpec;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;

/** Chaves de dados geradas e abertas pelo AWS KMS; a chave mestra nunca sai do KMS. */
public final class KmsKeyProvider implements KeyProvider {

    private final KmsClient kms;
    private final String keyId;

    public KmsKeyProvider(KmsClient kms, String keyId) {
        this.kms = kms;
        this.keyId = keyId;
    }

    @Override
    public DataKey generateDataKey() {
        try {
            GenerateDataKeyResponse response = kms.generateDataKey(GenerateDataKeyRequest.builder()
                    .keyId(keyId)
                    .keySpec(DataKeySpec.AES_256)
                    .build());
            return new DataKey(
                    response.plaintext().asByteArray(),
                    response.ciphertextBlob().asByteArray());
        } catch (SdkException e) {
            throw new CryptoException("O KMS recusou GenerateDataKey", e);
        }
    }

    @Override
    public byte[] decryptDataKey(byte[] encryptedKey) {
        try {
            return kms.decrypt(DecryptRequest.builder()
                            .keyId(keyId)
                            .ciphertextBlob(SdkBytes.fromByteArray(encryptedKey))
                            .build())
                    .plaintext()
                    .asByteArray();
        } catch (SdkException e) {
            throw new CryptoException("O KMS recusou Decrypt", e);
        }
    }
}
