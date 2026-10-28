package io.github.rennaraujo.sage.search;

/**
 * Prepara o texto que o reranker lê junto de cada trecho. Palavras de versão e nome de linguagem já
 * viraram filtro de busca; deixadas na pergunta, diluem o score do trecho certo ("sealed classes
 * existe no java 21 e no java 25?" dava 0,08 ao trecho de sealed classes do Java 21). Só o reranker vê
 * o texto limpo; BM25 e vetores usam a pergunta inteira.
 */
@FunctionalInterface
public interface RerankQueryCleaner {

    /** Não limpa nada. */
    RerankQueryCleaner NONE = question -> question;

    /** O texto para o reranker; nunca vazio (se a limpeza apagar tudo, volta a pergunta original). */
    String clean(String question);
}
