package io.github.rennaraujo.sage.question;

import java.util.List;

/**
 * Uma resposta completa guardada para reuso: todos os eventos {@code text} na ordem em que saíram (avisos de versão,
 * linhas "Documentação consultada", frases aprovadas) e o {@code metadata} final, com citações e
 * {@code documentacaoConsultada}. Contém texto derivado de documentos, então vive só na memória e o
 * {@code toString} não o revela.
 */
public record CachedAnswer(List<String> sentences, AnswerMetadata metadata) {

    public CachedAnswer {
        sentences = List.copyOf(sentences);
    }

    @Override
    public String toString() {
        return "CachedAnswer[" + sentences.size() + " eventos]";
    }
}
