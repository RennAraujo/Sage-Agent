package io.github.rennaraujo.sage.question;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.github.rennaraujo.sage.document.DocumentsChangedEvent;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.context.event.EventListener;

/**
 * Cache de respostas completas, em memória (Caffeine), com validade e tamanho máximo. Nunca é serializado nem gravado
 * em disco: guarda texto derivado de documentos. Qualquer documento salvo ou apagado esvazia o cache inteiro; o
 * ouvinte roda dentro do lock do armazém de documentos, então só faz {@code invalidateAll}.
 *
 * <p>O cache não ouve {@code DocumentSavedEvent} nem {@code DocumentDeletedEvent}, e sim o
 * {@link DocumentsChangedEvent}, que o armazém publica num {@code finally} depois deles. Duas garantias saem disso:
 * o cache esvazia depois de o índice e o catálogo de aliases atualizarem (uma resposta que leu o índice antigo tem a
 * época velha e não entra; uma que leu o novo não é apagada à toa), e esvazia mesmo quando um desses ouvintes lança
 * exceção. Antes, com o cache como último ouvinte do mesmo evento, uma falha no índice impedia a limpeza e a resposta
 * velha continuava sendo servida até a validade.
 *
 * <p>{@link #epoch()} existe para uma resposta que começou antes de um documento mudar não entrar no cache depois da
 * limpeza: quem vai guardar lê a época antes de buscar e a passa na hora do {@code put}.
 */
public final class AnswerCache {

    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[?!]+$");

    private final Cache<CacheKey, CachedAnswer> entries;
    private long epoch;

    public AnswerCache(CacheProperties properties, Ticker ticker) {
        this.entries = Caffeine.newBuilder()
                .maximumSize(properties.maxEntries())
                .expireAfterWrite(properties.ttl())
                .ticker(ticker)
                .build();
    }

    /** Minúsculas, sem espaços nas pontas, espaços colapsados e sem {@code ?}/{@code !} no fim. */
    public static String normalize(String question) {
        String collapsed =
                SPACES.matcher(question.strip().toLowerCase(Locale.ROOT)).replaceAll(" ");
        // Pontuação final pode vir separada por espaço ("java 21 ?!"); tira os dois até estabilizar.
        String text = collapsed;
        String previous;
        do {
            previous = text;
            text = TRAILING_PUNCTUATION.matcher(text.stripTrailing()).replaceAll("");
        } while (!text.equals(previous));
        return text.stripTrailing();
    }

    public Optional<CachedAnswer> get(CacheKey key) {
        return Optional.ofNullable(entries.getIfPresent(key));
    }

    public synchronized void put(CacheKey key, CachedAnswer answer) {
        entries.put(key, answer);
    }

    /** Guarda só se nada foi invalidado desde que {@code epoch} foi lido. */
    public synchronized void put(CacheKey key, CachedAnswer answer, long epoch) {
        if (epoch == this.epoch) {
            entries.put(key, answer);
        }
    }

    public synchronized long epoch() {
        return epoch;
    }

    public synchronized void clear() {
        epoch++;
        entries.invalidateAll();
    }

    /** O acervo mudou: chega depois de todos os ouvintes do salvamento ou da remoção, mesmo que algum tenha falhado. */
    @EventListener
    public void onChanged(DocumentsChangedEvent event) {
        clear();
    }

    void cleanUp() {
        entries.cleanUp();
    }

    long size() {
        return entries.estimatedSize();
    }
}
