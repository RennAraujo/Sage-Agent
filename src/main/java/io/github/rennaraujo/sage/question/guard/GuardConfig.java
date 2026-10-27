package io.github.rennaraujo.sage.question.guard;

import io.github.rennaraujo.sage.search.embedding.ModelProperties;
import io.github.rennaraujo.sage.upload.SecretScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Com os arquivos do modelo no disco o {@link GroundednessChecker} é o {@link OnnxNliChecker}. Sem eles entra o
 * {@link PassThroughGroundednessChecker}, com um aviso na partida (sem nenhum conteúdo): o guard passa a validar só
 * citações e segredos. O {@code close} do bean fecha a sessão ONNX no desligamento (o Spring infere o método de
 * {@link AutoCloseable} a partir da instância).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ModelProperties.class, GuardProperties.class})
public class GuardConfig {

    private static final Logger LOG = LoggerFactory.getLogger(GuardConfig.class);

    @Bean
    GroundednessChecker groundednessChecker(ModelProperties models, GuardProperties properties) {
        NliFiles files = NliFiles.inDir(models.dir());
        if (files.available()) {
            return new OnnxNliChecker(files, properties);
        }
        if (properties.requireNli()) {
            throw new IllegalStateException(
                    "Modelo de NLI ausente e sage.guard.require-nli está ligado. Rode scripts/download-models.");
        }
        LOG.warn("Modelo de NLI ausente: a verificação de fundamentação está desligada e o guard valida só citações"
                + " e segredos. Rode scripts/download-models para ativá-la.");
        return new PassThroughGroundednessChecker();
    }

    /** Fábrica dos guards de resposta: o verificador (com prazo por frase) e o scanner de segredos do upload. */
    @Bean
    AnswerGuardFactory answerGuardFactory(
            GroundednessChecker checker, SecretScanner scanner, GuardProperties properties) {
        return new AnswerGuardFactory(checker, scanner, properties);
    }

    /** Indicador de saúde "guard": mostra se a verificação de fundamentação está ativa. */
    @Bean
    GuardHealthIndicator guardHealthIndicator(GroundednessChecker checker, AnswerGuardFactory factory) {
        return new GuardHealthIndicator(checker, factory);
    }
}
