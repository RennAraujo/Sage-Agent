package io.github.rennaraujo.sage.question;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Conversas ({@code sage.conversation.*}), só em memória. {@code ttl} conta desde a última pergunta da conversa,
 * {@code maxTurns} é quantas análises recentes ficam por conversa e {@code maxConversations} limita quantas conversas
 * existem ao mesmo tempo.
 */
@Validated
@ConfigurationProperties("sage.conversation")
public record ConversationProperties(
        @DefaultValue("30m") Duration ttl,
        @Min(1) @DefaultValue("5") int maxTurns,
        @Min(1) @DefaultValue("10000") int maxConversations) {

    public ConversationProperties {
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("sage.conversation.ttl precisa ser positivo");
        }
    }
}
