package io.github.rennaraujo.sage.question.llm;

/**
 * O que vai para o modelo. Contém conteúdo do acervo e a pergunta, então nunca é logado: o {@code toString} só diz
 * os tamanhos.
 */
public record Prompt(String system, String user, boolean allowEscalation) {

    @Override
    public String toString() {
        return "Prompt[system=" + system.length() + " chars, user=" + user.length() + " chars]";
    }
}
