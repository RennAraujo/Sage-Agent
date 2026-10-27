package io.github.rennaraujo.sage.question;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.github.rennaraujo.sage.document.DocumentDeletedEvent;
import io.github.rennaraujo.sage.document.DocumentSavedEvent;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Cache de respostas completas, em memória (Caffeine), com validade e tamanho máximo. Nunca é serializado nem gravado
 * em disco: guarda texto derivado de documentos. Qualquer documento salvo ou apagado esvazia o cache inteiro; os
 * ouvintes rodam dentro do lock do armazém de documentos, então só fazem {@code invalidateAll}.
 *
 * <p>Ordem dos ouvintes: {@code @EventListener} sem {@code @Order} já vale {@code LOWEST_PRECEDENCE}, então só o
 * {@code @Order(0)} explícito de {@code IndexBootstrapper} e {@code AliasCatalog} faz o cache (que fica em
 * {@code LOWEST_PRECEDENCE}) rodar depois deles; sem isso o empate cai na ordem de registro dos beans. Só então a
 * época avança e o cache esvazia. Assim uma resposta que lê o índice já atualizado não é apagada à toa, e uma que
 * leu o índice antigo tem a época velha e não entra.
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

    @EventListener
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onSaved(DocumentSavedEvent event) {
        clear();
    }

    @EventListener
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onDeleted(DocumentDeletedEvent event) {
        clear();
    }

    void cleanUp() {
        entries.cleanUp();
    }

    long size() {
        return entries.estimatedSize();
    }
}
