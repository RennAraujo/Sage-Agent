package io.github.rennaraujo.sage.question;

/** Destino dos eventos da resposta, na ordem: um {@code metadata}, zero ou mais {@code text} e um {@code done}. */
public interface AnswerSink {

    void metadata(AnswerMetadata m);

    /** Uma frase (ou um bloco de código) já validada pelo guard, ou um texto fixo emitido pelo código. */
    void text(String sentence);

    void done(AnswerMetadata finalMetadata);

    /** Registra o que fazer quando o cliente for embora ou o stream fechar (cancelar o modelo, por exemplo). */
    default void onClose(Runnable callback) {}
}
