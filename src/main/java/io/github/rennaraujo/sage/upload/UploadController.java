package io.github.rennaraujo.sage.upload;

import io.github.rennaraujo.sage.document.DocumentId;
import io.github.rennaraujo.sage.document.InvalidDocumentException;
import jakarta.validation.Valid;
import java.io.IOException;
import java.security.Principal;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Endpoints de documentos (só EDITOR; a regra fica no {@code SecurityConfig}). O ator é o nome da chave de API. */
@RestController
@RequestMapping("/api/documentos")
class UploadController {

    private final UploadService service;

    UploadController(UploadService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    UploadResponse upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "fonte", required = false) String source,
            @RequestParam(name = "linguagem", required = false) String language,
            @RequestParam(name = "versao", required = false) String version,
            @RequestParam(name = "secao", required = false) String section,
            @RequestParam(name = "url_origem", required = false) String originUrl,
            @RequestParam(name = "substituir", required = false) String replace,
            Principal principal) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new InvalidDocumentException("Não foi possível ler o arquivo enviado");
        }
        UploadRequest request = new UploadRequest(
                file.getOriginalFilename(),
                content,
                Optional.ofNullable(source),
                Optional.ofNullable(language),
                Optional.ofNullable(version),
                Optional.ofNullable(section),
                Optional.ofNullable(originUrl),
                "true".equalsIgnoreCase(replace));
        UploadService.UploadResult result = service.upload(principal.getName(), request);
        return new UploadResponse(
                result.ids().stream().map(DocumentId::value).toList(),
                result.replaced().stream().map(DocumentId::value).toList());
    }

    @GetMapping
    List<DocumentSummary> list() {
        return service.list().stream().map(DocumentSummary::from).toList();
    }

    @PatchMapping
    void review(@RequestParam("id") String id, @Valid @RequestBody ReviewRequest body, Principal principal) {
        service.review(principal.getName(), id, body.reviewed());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@RequestParam("id") String id, Principal principal) {
        service.delete(principal.getName(), id);
    }
}
