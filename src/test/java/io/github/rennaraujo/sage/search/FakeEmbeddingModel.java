package io.github.rennaraujo.sage.search;

import io.github.rennaraujo.sage.search.embedding.EmbeddingModel;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Embedding de mentira para testes: cada "conceito" é uma dimensão, e palavras de línguas diferentes que significam
 * a mesma coisa caem na mesma dimensão. Assim "como ordenar lista" fica perto de "How to sort a List" sem modelo real.
 * Texto sem nenhum conceito conhecido vira um vetor sem direção própria (só a dimensão residual).
 */
public final class FakeEmbeddingModel implements EmbeddingModel {

    private static final List<List<String>> CONCEPTS = List.of(
            List.of("ordenar", "ordenacao", "sort", "sorting"),
            List.of("lista", "list", "colecao", "collection"),
            List.of("tls", "ssl", "certificado", "certificate"),
            List.of("configurar", "configure", "configuracao", "configuration"));

    private static final float RESIDUAL = 0.01f;

    @Override
    public float[] embedQuery(String text) {
        return embed(text);
    }

    @Override
    public float[] embedPassage(String text) {
        return embed(text);
    }

    @Override
    public int dimensions() {
        return CONCEPTS.size() + 1;
    }

    private static float[] embed(String text) {
        float[] v = new float[CONCEPTS.size() + 1];
        String folded = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        for (String word : folded.split("[^a-z0-9]+")) {
            for (int i = 0; i < CONCEPTS.size(); i++) {
                if (CONCEPTS.get(i).contains(word)) {
                    v[i] += 1f;
                }
            }
        }
        v[CONCEPTS.size()] = RESIDUAL;
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        float inv = (float) (1 / Math.sqrt(norm));
        for (int i = 0; i < v.length; i++) {
            v[i] *= inv;
        }
        return v;
    }
}
