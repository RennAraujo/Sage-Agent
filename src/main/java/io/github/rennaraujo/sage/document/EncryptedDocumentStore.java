package io.github.rennaraujo.sage.document;

import io.github.rennaraujo.sage.crypto.EncryptedBlob;
import io.github.rennaraujo.sage.crypto.EnvelopeCipher;
import io.github.rennaraujo.sage.storage.BlobStorage;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Guarda cada documento como um blob cifrado em {@code docs/<uuid>}. Frontmatter e corpo seguem juntos dentro do
 * payload cifrado, então nem o id, nem o caminho, nem o nome do app aparecem na chave do blob ou em bytes em claro. O
 * catálogo (id para chave do blob, mais os metadados) fica em memória e é montado na subida decifrando todos os blobs.
 */
public final class EncryptedDocumentStore implements DocumentStore {

    private static final Logger LOG = LoggerFactory.getLogger(EncryptedDocumentStore.class);
    private static final String BLOB_PREFIX = "docs/";

    private final EnvelopeCipher cipher;
    private final BlobStorage blobs;
    private final FrontmatterParser parser;
    private final ApplicationEventPublisher events;
    private final Map<DocumentId, Entry> catalog = new ConcurrentHashMap<>();
    private final Map<DocumentId, Object> locks = new ConcurrentHashMap<>();

    public EncryptedDocumentStore(
            EnvelopeCipher cipher, BlobStorage blobs, FrontmatterParser parser, ApplicationEventPublisher events) {
        this.cipher = cipher;
        this.blobs = blobs;
        this.parser = parser;
        this.events = events;
        loadCatalog();
    }

    @Override
    public void save(Document doc) {
        write(doc, false);
    }

    @Override
    public boolean saveIfAbsent(Document doc) {
        return write(doc, true);
    }

    /** Com {@code onlyIfAbsent}, a checagem e a gravação acontecem sob o mesmo lock do id: um único vencedor. */
    private boolean write(Document doc, boolean onlyIfAbsent) {
        DocumentId id = doc.metadata().id();
        byte[] payload = parser.render(doc).getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = cipher.encrypt(payload).toBytes();
        synchronized (lockFor(id)) {
            Entry existing = catalog.get(id);
            if (onlyIfAbsent && existing != null) {
                return false;
            }
            // Mesmo id reaproveita a chave do blob: a troca é um único put atômico e não sobra blob órfão.
            String blobKey = existing != null ? existing.blobKey() : BLOB_PREFIX + UUID.randomUUID();
            blobs.put(blobKey, encrypted);
            catalog.put(id, new Entry(blobKey, doc.metadata()));
            LOG.info("Documento salvo: {}", id.logRef());
            // Dentro do lock: a ordem dos eventos de um id é a ordem das operações no armazém.
            publish(new DocumentSavedEvent(doc));
            return true;
        }
    }

    /**
     * Publica o evento da mudança e, num {@code finally}, o {@link DocumentsChangedEvent}: se um ouvinte (o índice)
     * lançar exceção, a exceção sobe para quem gravou, mas o cache de respostas ainda é esvaziado.
     */
    private void publish(Object event) {
        try {
            events.publishEvent(event);
        } catch (RuntimeException e) {
            // A mudança já está no acervo: quem gravou precisa saber que não foi a gravação que falhou.
            throw new DocumentChangeNotPropagatedException(e);
        } finally {
            events.publishEvent(new DocumentsChangedEvent());
        }
    }

    @Override
    public Optional<Document> find(DocumentId id) {
        Entry entry = catalog.get(id);
        return entry == null ? Optional.empty() : read(entry.blobKey());
    }

    @Override
    public List<DocumentMetadata> listMetadata() {
        return catalog.values().stream()
                .map(Entry::metadata)
                .sorted(Comparator.comparing(m -> m.id().value()))
                .toList();
    }

    @Override
    public void delete(DocumentId id) {
        synchronized (lockFor(id)) {
            Entry entry = catalog.get(id);
            if (entry != null) {
                blobs.delete(entry.blobKey());
                catalog.remove(id);
                LOG.info("Documento apagado: {}", id.logRef());
                publish(new DocumentDeletedEvent(id));
            }
        }
    }

    @Override
    public Stream<Document> loadAll() {
        return catalog.values().stream().map(Entry::blobKey).sorted().toList().stream()
                .map(this::read)
                .flatMap(Optional::stream);
    }

    private Object lockFor(DocumentId id) {
        return locks.computeIfAbsent(id, key -> new Object());
    }

    private Optional<Document> read(String blobKey) {
        return blobs.get(blobKey).map(this::decode);
    }

    private Document decode(byte[] stored) {
        byte[] payload = cipher.decrypt(EncryptedBlob.fromBytes(stored));
        return parser.parse(new String(payload, StandardCharsets.UTF_8), DocumentDefaults.empty());
    }

    private void loadCatalog() {
        List<String> keys = blobs.list(BLOB_PREFIX);
        for (String key : keys) {
            Optional<Document> doc = read(key);
            if (doc.isEmpty()) {
                continue;
            }
            DocumentMetadata meta = doc.get().metadata();
            Entry previous = catalog.putIfAbsent(meta.id(), new Entry(key, meta));
            if (previous != null) {
                LOG.warn(
                        "Dois blobs para o mesmo documento {}; mantendo {} e ignorando {}",
                        meta.id().logRef(),
                        previous.blobKey(),
                        key);
            }
        }
        LOG.info("Catálogo do acervo carregado: {} documentos", catalog.size());
    }

    private record Entry(String blobKey, DocumentMetadata metadata) {}
}
