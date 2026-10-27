package io.github.rennaraujo.sage.web;

/** Id de importação que não existe (ou já saiu da memória). Mensagem fixa: nunca cita o id recebido. */
public class ImportJobNotFoundException extends RuntimeException {

    public ImportJobNotFoundException() {
        super("Importação não encontrada");
    }
}
