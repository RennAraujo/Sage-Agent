package io.github.rennaraujo.sage.storage;

import java.util.List;
import java.util.Optional;

/**
 * Armazém de blobs opacos. Quem chama já entrega os bytes cifrados; a implementação não interpreta o conteúdo. As
 * chaves usam só {@code [a-z0-9/-]} e não carregam nenhuma informação sobre o documento.
 */
public interface BlobStorage {

    void put(String key, byte[] content);

    Optional<byte[]> get(String key);

    void delete(String key);

    /** Chaves que começam com o prefixo, em ordem alfabética. */
    List<String> list(String prefix);
}
