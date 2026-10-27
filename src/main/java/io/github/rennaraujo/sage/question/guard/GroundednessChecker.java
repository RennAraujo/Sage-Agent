package io.github.rennaraujo.sage.question.guard;

import java.util.List;

/**
 * Decide se uma frase da resposta é sustentada pelos trechos que ela cita. A frase chega sem os marcadores
 * {@code [n]}; {@code evidence} traz o texto de cada trecho citado.
 */
public interface GroundednessChecker {

    /**
     * {@code true} se algum dos trechos sustenta a frase. Sem trechos, nada sustenta: {@code false}. Frase em branco
     * não afirma nada: {@code true}.
     */
    boolean isSupported(String sentence, List<String> evidence);
}
