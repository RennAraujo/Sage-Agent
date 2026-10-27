package io.github.rennaraujo.sage.question;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Últimas análises de cada conversa, em memória do processo (Caffeine), para as perguntas de continuação herdarem
 * linguagem e versão. Cada conversa guarda no máximo {@code maxTurns} análises e expira {@code ttl} depois da última
 * pergunta; o número de conversas também tem teto. O estado é por processo: com mais de uma instância, uma
 * continuação que cai em outra instância é tratada como conversa nova.
 */
public final class ConversationStore {

    private final Cache<String, Conversation> conversations;

    private record Conversation(String actor, List<QuestionAnalysis> turns) {}

    private final int maxTurns;

    public ConversationStore(ConversationProperties properties, Ticker ticker) {
        this.maxTurns = properties.maxTurns();
        this.conversations = Caffeine.newBuilder()
                .maximumSize(properties.maxConversations())
                .expireAfterWrite(properties.ttl())
                .ticker(ticker)
                .build();
    }

    public String newConversation() {
        return UUID.randomUUID().toString();
    }

    /**
     * A análise da pergunta mais recente, ou vazio se a conversa é desconhecida, expirou ou pertence a outro ator (uma
     * conversa só continua para quem a começou; para os outros o id é como se não existisse).
     */
    public Optional<QuestionAnalysis> last(String conversationId, String actor) {
        List<QuestionAnalysis> turns = ownedTurns(conversationId, actor);
        return turns.isEmpty() ? Optional.empty() : Optional.of(turns.get(turns.size() - 1));
    }

    /** Registra a análise e renova a validade da conversa. Conversa de outro ator é substituída. */
    public void append(String conversationId, String actor, QuestionAnalysis analysis) {
        conversations.asMap().compute(conversationId, (id, conversation) -> {
            List<QuestionAnalysis> next = new ArrayList<>(
                    conversation == null || !conversation.actor().equals(actor) ? List.of() : conversation.turns());
            next.add(analysis);
            return new Conversation(actor, List.copyOf(next.subList(Math.max(0, next.size() - maxTurns), next.size())));
        });
    }

    List<QuestionAnalysis> turns(String conversationId) {
        Conversation conversation = conversations.getIfPresent(conversationId);
        return conversation == null ? List.of() : conversation.turns();
    }

    private List<QuestionAnalysis> ownedTurns(String conversationId, String actor) {
        Conversation conversation = conversations.getIfPresent(conversationId);
        return conversation == null || !conversation.actor().equals(actor) ? List.of() : conversation.turns();
    }

    void cleanUp() {
        conversations.cleanUp();
    }

    long size() {
        return conversations.estimatedSize();
    }
}
