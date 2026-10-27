package io.github.rennaraujo.sage.question.llm;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Modelos de linguagem no Bedrock ({@code sage.models.*}). {@code fastId} e {@code strongId} são model ids ou inference
 * profiles e não têm padrão: sem eles a aplicação sobe, mas {@code /api/perguntas} responde 503. {@code timeout} é o
 * prazo total de uma resposta do modelo, do pedido ao último token (não é por token: um modelo que pinga devagar também
 * é cortado). O SSE fica aberto por esse prazo mais {@code sage.questions.preparation-timeout}, então o corte chega ao
 * cliente como evento {@code error} antes de o stream expirar.
 */
@Validated
@ConfigurationProperties("sage.models")
public record ModelProperties(
        String fastId,
        String strongId,
        @Min(1) @DefaultValue("1500") int maxTokens,

        @DecimalMin("0.0") @DecimalMax("1.0") @DefaultValue("0")
        double temperature,

        @DefaultValue("50s") Duration timeout) {

    /** Model id da faixa, vazio se não configurado. */
    public Optional<String> idFor(ModelTier tier) {
        String id = tier == ModelTier.FAST ? fastId : strongId;
        return Optional.ofNullable(id).filter(s -> !s.isBlank());
    }

    /** Os dois ids estão preenchidos. */
    public boolean configured() {
        return idFor(ModelTier.FAST).isPresent() && idFor(ModelTier.STRONG).isPresent();
    }
}
