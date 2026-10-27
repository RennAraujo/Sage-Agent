# Sage-Agent

Agente de documentação em Java 25 e Spring Boot 4: você sobe seus documentos, faz perguntas em linguagem natural e recebe respostas ancoradas nas fontes, com a indicação de onde cada trecho veio. O desenho completo está em [docs/design.md](docs/design.md).

## Status

Em desenvolvimento. Por enquanto existe só o esqueleto do projeto: a aplicação sobe e responde em `/actuator/health`. Upload, busca e perguntas entram nas próximas etapas.

## Como compilar

Precisa de JDK 25. O Maven Wrapper já vem no repositório, então não é preciso instalar o Maven.

```
.\mvnw.cmd verify
```

O `verify` roda o Spotless (formatação), o Checkstyle, o SpotBugs, os testes e o JaCoCo, que exige 80% de cobertura de linhas. Se a formatação reclamar, `.\mvnw.cmd spotless:apply` corrige.

## Modelo de embeddings

A busca híbrida usa o `multilingual-e5-small` (470 MB), que não vai para o git. Baixe-o antes de rodar os testes que o usam e antes de subir a aplicação com busca por significado:

```
.\scripts\download-models.ps1     # ou scripts/download-models.sh
```

Os arquivos (embeddings e reranker, uns 960 MB no total) vão para `models/` (ou para `sage.models.dir`) e são conferidos contra o sha256 de `models.lock`. Sem eles tudo continua funcionando, só que a busca é apenas BM25, o score de relevância vira uma cobertura lexical aproximada em vez da nota do reranker, e o log avisa na subida. Detalhes em [docs/arquitetura.md](docs/arquitetura.md#busca-híbrida) e [sobre o reranker](docs/arquitetura.md#reranker-e-o-score-que-decide-o-caminho).

Para a imagem Docker (ela baixa e confere o modelo no build):

```
docker build -t sage-agent .
```

O `docker-compose.yml` lê um arquivo `.env` (copie o `.env.example`) e monta `./data` em `/app/data`.

## Desenvolvimento

O repositório traz um hook de pre-commit que roda o gitleaks nos arquivos em stage e bloqueia o commit se achar segredo. Para ativar:

```
git config core.hooksPath .githooks
```

O hook usa o binário `gitleaks` local; se ele não estiver instalado, só avisa e deixa o commit passar. A varredura de histórico completo roda de qualquer forma no CI (`.github/workflows/ci.yml`), junto com o build e o Trivy. A regra extra `sage-api-key` fica em `.gitleaks.toml`.
