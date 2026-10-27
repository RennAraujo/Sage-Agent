package io.github.rennaraujo.sage.crypto;

/** Origem das chaves de dados: uma chave mestra local ou o AWS KMS. */
public interface KeyProvider {

    DataKey generateDataKey();

    byte[] decryptDataKey(byte[] encryptedKey);
}
