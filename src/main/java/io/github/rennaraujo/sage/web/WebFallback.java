package io.github.rennaraujo.sage.web;

import io.github.rennaraujo.sage.document.Document;
import io.github.rennaraujo.sage.question.QuestionAnalysis;
import java.time.Duration;
import java.util.List;

/**
 * O que o {@code AnswerService} precisa do fallback web. {@link #NONE} é o fallback desligado, usado por quem monta o
 * serviço sem web: não busca nada e não afirma que alguma linguagem não é suportada.
 */
public interface WebFallback {

    WebFallback NONE = new WebFallback() {
        @Override
        public boolean active() {
            return false;
        }

        @Override
        public boolean supports(String language) {
            return true;
        }

        @Override
        public int maxPages() {
            return 0;
        }

        @Override
        public Duration timeout() {
            return Duration.ZERO;
        }

        @Override
        public List<Document> fetchAndStore(
                String question,
                QuestionAnalysis analysis,
                String version,
                String actor,
                int maxPages,
                Duration budget) {
            return List.of();
        }
    };

    /** Há buscador configurado (chave do Brave). Sem ele a rota WEB_FALLBACK nunca é tentada. */
    boolean active();

    /** A linguagem tem fontes oficiais na allowlist. */
    boolean supports(String language);

    /** Teto de páginas por pergunta ({@code sage.web.max-pages-per-question}). */
    int maxPages();

    /** Prazo para o fallback inteiro de uma pergunta, todas as versões somadas ({@code sage.web.fallback-timeout}). */
    Duration timeout();

    /**
     * Busca, baixa e grava até {@code maxPages} páginas oficiais da {@code version} da linguagem da análise, como
     * documentos {@code revisado: false}, dentro do tempo restante {@code budget} do prazo da pergunta. Devolve só os
     * documentos realmente gravados; qualquer falha da web resulta em lista vazia, menos a falha de auditoria, que sobe
     * (fail-closed). {@code actor} é a chave que fez a pergunta (vai para a auditoria).
     */
    List<Document> fetchAndStore(
            String question, QuestionAnalysis analysis, String version, String actor, int maxPages, Duration budget);
}
