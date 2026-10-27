package io.github.rennaraujo.sage.question;

import io.github.rennaraujo.sage.auth.RateLimitedAction;
import io.github.rennaraujo.sage.auth.RateLimiter;
import io.github.rennaraujo.sage.question.llm.ModelProperties;
import io.github.rennaraujo.sage.search.IndexReadiness;
import io.github.rennaraujo.sage.shared.ServiceUnavailableException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.Principal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code POST /api/perguntas}: responde em {@code text/event-stream} com os eventos {@code metadata}, {@code text}
 * (uma frase validada por evento), {@code done} e, se algo falhar no meio, {@code error}. A resposta roda numa
 * virtual thread; o thread da requisição só valida, confere índice, modelo e limite, e devolve o emitter.
 */
@RestController
@RequestMapping("/api/perguntas")
class QuestionController {

    static final int MAX_QUESTION_CHARS = 2000;
    static final String INDEX_LOADING = "O índice ainda está carregando; tente de novo em alguns segundos.";
    static final String MODEL_NOT_CONFIGURED = "modelo não configurado";
    static final String ANSWER_FAILED = "Não foi possível concluir a resposta. Tente de novo.";

    private static final Logger LOG = LoggerFactory.getLogger(QuestionController.class);

    /** Corpo da requisição. {@code conversaId} é opcional e só aceita um id simples, porque vai para a auditoria. */
    record QuestionRequest(
            @NotBlank @Size(max = MAX_QUESTION_CHARS) String pergunta,
            @Pattern(regexp = "[A-Za-z0-9-]{1,64}") String conversaId) {

        @Override
        public String toString() {
            return "QuestionRequest[" + (pergunta == null ? 0 : pergunta.length()) + " chars]";
        }
    }

    private final AnswerService service;
    private final IndexReadiness readiness;
    private final RateLimiter rateLimiter;
    private final QuestionProperties properties;
    private final ModelProperties models;

    QuestionController(
            AnswerService service,
            IndexReadiness readiness,
            RateLimiter rateLimiter,
            QuestionProperties properties,
            ModelProperties models) {
        this.service = service;
        this.readiness = readiness;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.models = models;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter ask(@Valid @RequestBody QuestionRequest body, Principal principal) {
        if (!readiness.isReady()) {
            throw new ServiceUnavailableException(INDEX_LOADING, Optional.of(properties.indexRetryAfter()));
        }
        if (!service.modelAvailable()) {
            throw new ServiceUnavailableException(MODEL_NOT_CONFIGURED, Optional.empty());
        }
        rateLimiter.require(principal.getName(), RateLimitedAction.QUESTION);

        AnswerRequest request =
                new AnswerRequest(body.pergunta(), Optional.ofNullable(body.conversaId()), principal.getName());
        SseEmitter emitter = new SseEmitter(streamTimeout().toMillis());
        EmitterSink sink = new EmitterSink(emitter);
        emitter.onCompletion(sink::closed);
        emitter.onError(error -> sink.closed());
        emitter.onTimeout(() -> {
            // Só chega aqui se a preparação estourar o orçamento: o modelo tem prazo próprio, menor que este.
            sink.closed();
            sendError(emitter);
        });
        Thread.ofVirtual().name("pergunta").start(() -> run(request, emitter, sink));
        return emitter;
    }

    /**
     * O stream fica aberto pelo orçamento de preparação (análise, busca, auditoria) mais o prazo total do modelo. Assim
     * a resposta termina com {@code done} ou {@code error} enviados pelo serviço antes de o emitter expirar sozinho.
     */
    Duration streamTimeout() {
        return properties.preparationTimeout().plus(models.timeout());
    }

    private void run(AnswerRequest request, SseEmitter emitter, EmitterSink sink) {
        try {
            service.answer(request, sink);
            emitter.complete();
        } catch (AnswerAbortedException e) {
            if (e.reason() == AnswerAbortedException.Reason.CLIENT_GONE) {
                LOG.debug("Cliente saiu antes do fim da resposta");
                return;
            }
            LOG.warn("Resposta interrompida: {}", e.reason());
            sendError(emitter);
        } catch (RuntimeException e) {
            // Só a classe: a mensagem pode carregar conteúdo do modelo ou do acervo.
            LOG.warn("Falha ao responder pergunta: {}", e.getClass().getName());
            sendError(emitter);
        }
    }

    private static void sendError(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(Map.of("mensagem", ANSWER_FAILED), MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException | IllegalStateException e) {
            // O cliente já foi embora ou o emitter já fechou: não há a quem avisar.
            LOG.debug(
                    "Não foi possível enviar o evento de erro: {}", e.getClass().getName());
        }
    }

    /**
     * Converte os eventos da resposta em eventos SSE com corpo JSON (quebra de linha vira string escapada). Quando o
     * emitter fecha (fim normal, erro de escrita, cliente que saiu, timeout), avisa o serviço pelo callback registrado
     * em {@link #onClose}, que cancela o stream do modelo.
     */
    private static final class EmitterSink implements AnswerSink {

        private final SseEmitter emitter;
        private final AtomicReference<Runnable> onClose = new AtomicReference<>(() -> {});

        EmitterSink(SseEmitter emitter) {
            this.emitter = emitter;
        }

        void closed() {
            onClose.get().run();
        }

        @Override
        public void onClose(Runnable callback) {
            onClose.set(callback);
        }

        @Override
        public void metadata(AnswerMetadata m) {
            send("metadata", payload(m));
        }

        @Override
        public void text(String sentence) {
            send("text", Map.of("texto", sentence));
        }

        @Override
        public void done(AnswerMetadata finalMetadata) {
            send("done", donePayload(finalMetadata));
        }

        private void send(String name, Object data) {
            try {
                emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    static Map<String, Object> payload(AnswerMetadata m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("conversaId", m.conversationId());
        out.put("rota", m.route().name());
        m.model().ifPresent(tier -> out.put("modelo", tier.name()));
        out.put("scoreMaximo", m.topScore());
        out.put(
                "citacoes",
                m.citations().stream().map(QuestionController::citation).toList());
        m.versionUsed().ifPresent(v -> out.put("versaoUsada", v));
        out.put("fontesNaoRevisadas", m.unreviewedSources());
        out.put("frasesDescartadas", m.droppedSentences());
        out.put("segredosRemovidos", m.redactedSecrets());
        out.put("temposMs", m.timingsMillis());
        return out;
    }

    /**
     * O {@code done} leva tudo do {@code metadata} mais {@code documentacaoConsultada}: as fontes citadas, na ordem em
     * que as linhas "Documentação consultada" apareceram. Trecho público sem versão vem com {@code versao} nulo e
     * {@code versaoNaoInformada} verdadeiro, o equivalente estruturado de "(versão não informada)".
     */
    static Map<String, Object> donePayload(AnswerMetadata m) {
        Map<String, Object> out = payload(m);
        out.put(
                "documentacaoConsultada",
                m.consulted().stream().map(QuestionController::consulted).toList());
        return out;
    }

    private static Map<String, Object> consulted(ConsultedSource source) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("linguagem", source.language());
        out.put("nome", source.name());
        out.put("versao", source.version().orElse(null));
        out.put("fonte", source.source().label());
        out.put("versaoNaoInformada", source.versionUnknown());
        return out;
    }

    private static Map<String, Object> citation(Citation c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("n", c.index());
        out.put("documento", c.documentId().value());
        out.put("secao", c.section());
        out.put("revisado", c.reviewed());
        out.put("linguagem", c.language());
        c.version().ifPresent(v -> out.put("versao", v));
        return out;
    }
}
