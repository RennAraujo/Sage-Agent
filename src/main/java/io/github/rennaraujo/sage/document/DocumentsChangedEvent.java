package io.github.rennaraujo.sage.document;

/**
 * Publicado pelo armazém depois de {@link DocumentSavedEvent} ou {@link DocumentDeletedEvent}, num {@code finally}: sai
 * mesmo que um ouvinte do evento anterior (o índice, por exemplo) tenha lançado exceção. É o sinal para quem só precisa
 * saber que o acervo mudou, como o cache de respostas, que não pode ficar servindo resposta velha por causa da falha
 * de outro ouvinte.
 */
public record DocumentsChangedEvent() {}
