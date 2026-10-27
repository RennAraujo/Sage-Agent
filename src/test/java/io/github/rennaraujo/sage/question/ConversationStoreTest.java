package io.github.rennaraujo.sage.question;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Ticker;
import io.github.rennaraujo.sage.document.Source;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ConversationStoreTest {

    private final AtomicLong nanos = new AtomicLong();
    private final ConversationStore store =
            new ConversationStore(new ConversationProperties(Duration.ofMinutes(30), 5, 100), nanos::get);

    @Test
    void newConversationsGetDistinctIds() {
        assertThat(store.newConversation()).isNotBlank().isNotEqualTo(store.newConversation());
    }

    @Test
    void lastReturnsTheMostRecentAnalysis() {
        String id = store.newConversation();
        assertThat(store.last(id, "ana")).isEmpty();

        store.append(id, "ana", analysis("1"));
        store.append(id, "ana", analysis("2"));

        assertThat(store.last(id, "ana")).map(a -> a.versions().get(0)).contains("2");
    }

    @Test
    void keepsOnlyTheLastFiveTurns() {
        String id = store.newConversation();
        for (int i = 1; i <= 8; i++) {
            store.append(id, "ana", analysis(Integer.toString(i)));
        }

        assertThat(store.turns(id)).extracting(a -> a.versions().get(0)).containsExactly("4", "5", "6", "7", "8");
    }

    @Test
    void conversationExpiresAfterTtl() {
        String id = store.newConversation();
        store.append(id, "ana", analysis("1"));
        nanos.addAndGet(Duration.ofMinutes(29).toNanos());
        assertThat(store.last(id, "ana")).isPresent();

        nanos.addAndGet(Duration.ofMinutes(2).toNanos());
        assertThat(store.last(id, "ana")).isEmpty();
    }

    @Test
    void appendRenewsTheTtl() {
        String id = store.newConversation();
        store.append(id, "ana", analysis("1"));
        nanos.addAndGet(Duration.ofMinutes(20).toNanos());
        store.append(id, "ana", analysis("2"));
        nanos.addAndGet(Duration.ofMinutes(20).toNanos());

        assertThat(store.last(id, "ana")).isPresent();
    }

    @Test
    void conversationBelongsToItsActor() {
        String id = store.newConversation();
        store.append(id, "ana", analysis("1"));

        assertThat(store.last(id, "bia")).isEmpty();
        assertThat(store.last(id, "ana")).isPresent();
    }

    @Test
    void unknownIdIsEmpty() {
        assertThat(store.last("nao-existe", "ana")).isEmpty();
    }

    @Test
    void numberOfConversationsIsBounded() {
        ConversationStore small =
                new ConversationStore(new ConversationProperties(Duration.ofMinutes(30), 5, 3), Ticker.systemTicker());
        for (int i = 0; i < 30; i++) {
            small.append(small.newConversation(), "ana", analysis("1"));
        }
        small.cleanUp();

        assertThat(small.size()).isLessThanOrEqualTo(3);
    }

    private static QuestionAnalysis analysis(String version) {
        return new QuestionAnalysis(
                EnumSet.of(Source.PUBLIC), Optional.of("java"), List.of(version), QuestionType.LOOKUP, false, true);
    }
}
