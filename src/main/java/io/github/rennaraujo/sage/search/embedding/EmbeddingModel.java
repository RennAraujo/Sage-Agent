package io.github.rennaraujo.sage.search.embedding;

/**
 * Porta de embeddings. Consulta e trecho têm métodos separados porque modelos como o e5 tratam os dois de forma
 * assimétrica (prefixos diferentes). Os vetores saem normalizados (norma L2 = 1). As implementações precisam ser
 * seguras para uso concorrente.
 */
public interface EmbeddingModel {

    float[] embedQuery(String text);

    float[] embedPassage(String text);

    int dimensions();
}
