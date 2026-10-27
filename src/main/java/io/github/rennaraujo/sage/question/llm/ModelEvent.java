package io.github.rennaraujo.sage.question.llm;

/** O que o modelo devolve durante o streaming. */
public sealed interface ModelEvent permits ModelEvent.TextDelta, ModelEvent.EscalationRequested, ModelEvent.Completed {

    /** Pedaço de texto, sem garantia de fronteira de palavra ou frase. */
    record TextDelta(String text) implements ModelEvent {}

    /** O modelo pediu um modelo mais forte (só quando o prompt permite; a ferramenta chega na Task 12). */
    record EscalationRequested(String reason) implements ModelEvent {}

    /** Fim normal da resposta. */
    record Completed() implements ModelEvent {}
}
