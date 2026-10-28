package io.github.rennaraujo.sage.eval;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Uma pergunta-gabarito de {@code eval/perguntas.yml}. {@code versions} só existe quando o gabarito fixa as
 * versões que o analisador deve extrair (lista vazia quer dizer "nenhuma"); {@code expectedTexts} são
 * textos fixos emitidos pelo código, {@code expectedConsulted} as fontes de {@code documentacaoConsultada},
 * no formato "linguagem versão" ou só "aplicação".
 */
public record EvalCase(
        String question,
        String source,
        List<String> expectedDocs,
        String expectedRoute,
        Optional<List<String>> versions,
        List<String> expectedTexts,
        List<String> forbiddenTexts,
        List<String> expectedConsulted,
        boolean noConsulted) {

    public static final String PUBLIC = "publico";
    public static final String INTERNAL = "interno";
    public static final String OUT_OF_SCOPE = "fora";
    public static final String NOT_DOCUMENTED = "NOT_DOCUMENTED";

    private static final String RESOURCE = "eval/perguntas.yml";

    public boolean outOfScope() {
        return OUT_OF_SCOPE.equals(source);
    }

    public static List<EvalCase> load() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
            List<EvalCase> cases = new ArrayList<>();
            for (Object item : (List<?>) root) {
                cases.add(parse((Map<?, ?>) item));
            }
            return List.copyOf(cases);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static EvalCase parse(Map<?, ?> map) {
        return new EvalCase(
                (String) map.get("pergunta"),
                (String) map.get("fonte"),
                strings(map.get("esperadoDocId")),
                (String) map.get("esperadoRota"),
                map.containsKey("versoes") ? Optional.of(strings(map.get("versoes"))) : Optional.empty(),
                strings(map.get("esperaTextos")),
                strings(map.get("naoEsperaTextos")),
                strings(map.get("esperaConsultadas")),
                Boolean.TRUE.equals(map.get("semConsultadas")));
    }

    private static List<String> strings(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of(String.valueOf(value));
    }
}
