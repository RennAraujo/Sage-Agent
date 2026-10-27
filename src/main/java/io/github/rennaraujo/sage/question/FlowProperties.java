package io.github.rennaraujo.sage.question;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code sage.flow.*}: limites da expansão de contexto das perguntas de fluxo. {@code maxDocChars} é o tamanho até o
 * qual um documento entra inteiro (e o teto de cada documento ligado); {@code maxLinkedDocs} é quantos documentos
 * apontados por wiki-link entram; {@code maxContextChars} é o teto do texto somado de todo o contexto.
 */
@Validated
@ConfigurationProperties("sage.flow")
public record FlowProperties(
        @Min(500) @DefaultValue("12000") int maxDocChars,
        @Min(0) @DefaultValue("3") int maxLinkedDocs,
        @Min(500) @DefaultValue("24000") int maxContextChars) {}
