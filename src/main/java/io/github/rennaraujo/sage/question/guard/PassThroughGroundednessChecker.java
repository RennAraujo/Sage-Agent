package io.github.rennaraujo.sage.question.guard;

import java.util.List;

/**
 * Usado quando o modelo de NLI não está no disco: aprova toda frase que cita algum trecho (sem trechos, como no
 * contrato da porta, não há o que sustente). O guard degrada para validar só as citações (marcador aponta para um
 * trecho que existe) e o filtro de segredos; a checagem de fundamentação fica desligada.
 */
public final class PassThroughGroundednessChecker implements GroundednessChecker {

    @Override
    public boolean isSupported(String sentence, List<String> evidence) {
        return !evidence.isEmpty();
    }
}
