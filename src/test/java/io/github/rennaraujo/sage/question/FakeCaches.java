package io.github.rennaraujo.sage.question;

import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;

/** Cache de respostas e armazém de conversas com os valores padrão, para os testes que não os exercitam. */
final class FakeCaches {

    private FakeCaches() {}

    static AnswerCache cache() {
        return new AnswerCache(new CacheProperties(Duration.ofHours(6), 1000), Ticker.systemTicker());
    }

    static ConversationStore conversations() {
        return new ConversationStore(
                new ConversationProperties(Duration.ofMinutes(30), 5, 10_000), Ticker.systemTicker());
    }
}
