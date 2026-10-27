package io.github.rennaraujo.sage.gap;

import java.time.Instant;

/** Uma pergunta sem resposta na documentação interna, agrupada pela forma normalizada, com quantas vezes veio. */
public record GapSummary(String question, Instant lastAskedAt, int times) {}
