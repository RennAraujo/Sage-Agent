package io.github.rennaraujo.sage.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.rennaraujo.sage.document.InvalidDocumentException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.Principal;
import java.util.OptionalInt;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Semeadura do acervo (só EDITOR; a regra fica no {@code SecurityConfig}). O ator é o nome da chave de API. */
@RestController
@RequestMapping("/api/importar")
class ImportController {

    private final ImportService service;
    private final ImportProperties properties;

    ImportController(ImportService service, ImportProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    Started start(@Valid @RequestBody Request request, Principal principal) {
        ImportJob job = service.start(
                parse(request.url()),
                request.language(),
                request.version(),
                request.depthOrEmpty().orElse(properties.maxDepth()),
                principal.getName());
        return new Started(job.id());
    }

    @GetMapping("/{id}")
    Status status(@PathVariable("id") String id) {
        ImportJob job = service.status(id).orElseThrow(ImportJobNotFoundException::new);
        return new Status(job.id(), job.status().name(), job.imported(), job.skipped(), job.collisions(), job.failed());
    }

    /** A mensagem da recusa é fixa: a URL recebida nunca é repetida. */
    private static URI parse(String url) {
        try {
            return new URI(url.strip());
        } catch (URISyntaxException e) {
            throw new InvalidDocumentException(ImportService.INVALID_ROOT);
        }
    }

    record Request(
            @JsonProperty("url") @NotBlank String url,
            @JsonProperty("linguagem") @NotBlank String language,
            @JsonProperty("versao") @NotBlank String version,
            @JsonProperty("profundidade") Integer depth) {

        OptionalInt depthOrEmpty() {
            return depth == null ? OptionalInt.empty() : OptionalInt.of(depth);
        }
    }

    record Started(@JsonProperty("id") String id) {}

    record Status(
            @JsonProperty("id") String id,
            @JsonProperty("status") String status,
            @JsonProperty("importadas") int imported,
            @JsonProperty("ignoradas") int skipped,
            @JsonProperty("colisoes") int collisions,
            @JsonProperty("falhas") int failed) {}
}
