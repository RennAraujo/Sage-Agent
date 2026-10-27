package io.github.rennaraujo.sage.auth;

import java.security.Principal;
import java.util.Map;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Endpoints só de teste. {@code @TestComponent} de propósito: o component scan da aplicação ignora a classe, e cada
 * teste a registra explicitamente com {@code @Import}.
 */
@TestComponent
@Controller
@ResponseBody
class TestOnlyController {

    private final RateLimiter rateLimiter;

    TestOnlyController(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/api/perguntas")
    Map<String, String> ask(Principal principal) {
        rateLimiter.require(principal.getName(), RateLimitedAction.QUESTION);
        return Map.of("ok", "true", "quem", principal.getName());
    }

    @GetMapping("/api/documentos/teste")
    Map<String, String> editorOnly() {
        return Map.of("ok", "true");
    }

    @GetMapping("/api/falha")
    Map<String, String> boom() {
        throw new IllegalStateException("conteudo-secreto-do-documento");
    }
}
