package io.github.rennaraujo.sage.eval;

import io.github.rennaraujo.sage.document.DocumentId;
import io.github.rennaraujo.sage.search.Chunk;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;

/** Uma linha de {@code eval/nli-pairs.tsv}: frase, rótulo e o texto do trecho que a frase cita. */
record NliPair(boolean supported, String docId, String heading, String sentence, String evidence) {

    private static final String RESOURCE = "eval/nli-pairs.tsv";
    private static final int COLUMNS = 4;

    static List<NliPair> load(EvalStack stack) {
        List<NliPair> pairs = new ArrayList<>();
        try {
            String raw =
                    new String(new ClassPathResource(RESOURCE).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            for (String line : raw.split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] columns = line.stripTrailing().split("\t");
                if (columns.length != COLUMNS) {
                    throw new IllegalStateException("Linha malformada em " + RESOURCE + ": " + line);
                }
                pairs.add(new NliPair(
                        "S".equals(columns[0]),
                        columns[1],
                        columns[2],
                        columns[3],
                        evidence(stack, columns[1], columns[2])));
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return List.copyOf(pairs);
    }

    /** O mesmo texto que o {@code AnswerService} entrega ao verificador: caminho de seções, quebra de linha, trecho. */
    private static String evidence(EvalStack stack, String docId, String heading) {
        for (Chunk chunk : stack.index().chunksOf(DocumentId.of(docId))) {
            if (chunk.headingPath().equals(heading)) {
                return chunk.headingPath() + "\n" + chunk.text();
            }
        }
        throw new IllegalStateException("Trecho não encontrado no índice: " + docId + " / " + heading);
    }
}
