package io.github.rennaraujo.sage.document;

/**
 * O documento foi gravado (ou apagado) no acervo, mas um ouvinte da mudança, como o índice de busca, lançou exceção.
 * Quem gravou não deve registrar a gravação como falha: o conteúdo está no acervo. A mensagem é fixa e não leva o id.
 */
public final class DocumentChangeNotPropagatedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DocumentChangeNotPropagatedException(RuntimeException cause) {
        super("Documento gravado, mas um ouvinte da mudança falhou", cause);
    }
}
