FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package
# Bibliotecas nativas do ONNX Runtime e do tokenizer (DJL) extraídas dos jars já neste build, para a imagem final
# carregá-las de um caminho somente leitura e nada nativo ser extraído (nem executado) em /tmp em tempo de execução.
RUN set -eu; \
    case "$(uname -m)" in \
      x86_64) ort=linux-x64; djl=linux-x86_64 ;; \
      aarch64) ort=linux-aarch64; djl=linux-aarch64 ;; \
      *) echo "arquitetura sem biblioteca nativa: $(uname -m)" >&2; exit 1 ;; \
    esac; \
    mkdir -p /native /work && cd /work; \
    jar xf "$(ls /root/.m2/repository/com/microsoft/onnxruntime/onnxruntime/*/onnxruntime-*.jar)" "ai/onnxruntime/native/$ort"; \
    jar xf "$(ls /root/.m2/repository/ai/djl/huggingface/tokenizers/*/tokenizers-*.jar)" "native/lib/$djl/cpu"; \
    cp ai/onnxruntime/native/$ort/*.so /native/; \
    cp native/lib/$djl/cpu/libtokenizers.so /native/; \
    chmod 755 /native && chmod 644 /native/*

# Modelos (embeddings, reranker e NLI): baixados de uma revisão fixa e conferidos contra o sha256 de models.lock. Se o hash divergir,
# o build falha aqui. Fica em estágio próprio para o cache do Docker não rebaixar 2 GB a cada mudança no código.
FROM maven:3.9-eclipse-temurin-25 AS models
WORKDIR /models-build
COPY models.lock ./
COPY scripts/download-models.sh scripts/
RUN bash scripts/download-models.sh /models

FROM eclipse-temurin:25-jre
RUN groupadd --gid 10001 sage && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin sage
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/target/sage-agent-*.jar /app/sage-agent.jar
COPY --from=models --chown=10001:10001 /models /app/models
RUN mkdir -p /app/data && chown 10001:10001 /app/data
# Bibliotecas nativas pré-extraídas, de root e somente leitura: o ONNX Runtime as lê de onnxruntime.native.path e o
# tokenizer de RUST_LIBRARY_PATH. Assim /tmp continua noexec.
COPY --from=build /native /app/native
ENV SAGE_MODELS_DIR=/app/models
# A imagem sempre traz o modelo de NLI; se ele sumir, melhor não subir do que responder sem verificar fundamentação.
ENV SAGE_GUARD_REQUIRE_NLI=true
ENV RUST_LIBRARY_PATH=/app/native/libtokenizers.so
ENV DJL_CACHE_DIR=/tmp/djl
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-Donnxruntime.native.path=/app/native", "-jar", "/app/sage-agent.jar"]
