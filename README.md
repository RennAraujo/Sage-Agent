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

As avaliações pesadas (perguntas-gabarito, latência e calibração) ficam fora do `verify` padrão. `.\mvnw.cmd verify -Peval` roda tudo, cobra os orçamentos de latência e gera `target/eval-report.md`; precisa dos modelos em `models/` (`scripts/download-models`). Os testes de segurança (injeção, segredo, texto em claro, tokenizers) rodam nos dois. Os números e as decisões estão em [docs/decisoes.md](docs/decisoes.md#calibração-com-as-perguntas-gabarito).

## Modelo de embeddings

A busca híbrida usa o `multilingual-e5-small` (470 MB), que não vai para o git. Baixe-o antes de rodar os testes que o usam e antes de subir a aplicação com busca por significado:

```
.\scripts\download-models.ps1     # ou scripts/download-models.sh
```

Os arquivos (embeddings, reranker e verificação de fundamentação, uns 2,1 GB no total) vão para `models/` (ou para `sage.models.dir`) e são conferidos contra o sha256 de `models.lock`. Sem eles tudo continua funcionando, só que a busca é apenas BM25, o score de relevância vira uma cobertura lexical aproximada em vez da nota do reranker, a verificação de fundamentação das respostas fica desligada (o guard valida só citações e segredos), e o log avisa na subida. Detalhes em [docs/arquitetura.md](docs/arquitetura.md#busca-híbrida) e [sobre o reranker](docs/arquitetura.md#reranker-e-o-score-que-decide-o-caminho).

Para a imagem Docker (ela baixa e confere o modelo no build):

```
docker build -t sage-agent .
```

O `docker-compose.yml` lê um arquivo `.env` (copie o `.env.example`) e monta `./data` em `/app/data`.

## Testando pelo mini chat

Com a aplicação no ar (`docker compose up`, ou `.\mvnw.cmd spring-boot:run`), abra `http://localhost:8080/chat/`. É uma página estática, sem framework e sem nada carregado de fora, que serve só para experimentar a API. Ela não faz parte do produto: o contrato continua sendo o de [docs/api.md](docs/api.md).

Antes de tudo, gere uma chave com `scripts/new-api-key.ps1` (ou `.sh`), ponha o hash dela em `sage.auth.keys` e digite a chave no campo do topo da página. Para usar as abas de documentos, lacunas e importação a chave precisa ser `EDITOR`; para só perguntar, `READER` basta. A chave fica no `sessionStorage` da aba: some quando a aba fecha e nunca vai para um cookie.

As abas:

- **Perguntar** manda a pergunta e mostra a resposta chegando por SSE. No topo de cada resposta aparece, em destaque, a linha "Documentação consultada: Java 21" (ela é emitida pelo servidor, não pelo modelo), e cada fonte vem como `[1] Java 21 · Stream > Terminal operations`, com um selo "não revisado" quando ninguém aprovou o documento. Abaixo fica o painel de diagnóstico: rota, modelo, score do melhor trecho, versão usada, frases descartadas pelo guard e o tempo de cada etapa.
- **Documentos** envia arquivos (md, txt, pdf, docx, html ou zip), lista os metadados do acervo e permite aprovar, desaprovar e excluir. Se o id já existe, a página mostra o 409 e oferece reenviar com "substituir".
- **Lacunas** e **Importar** falam com `/api/lacunas` e `/api/importar`. Em servidores que ainda não têm esses endpoints, a página avisa que não estão disponíveis em vez de mostrar um erro cru.

Um caminho rápido para ver tudo funcionando: na aba Documentos, envie um `.md` com `fonte: interno` no frontmatter, aprove-o na lista e pergunte sobre o conteúdo na aba Perguntar. Sem Bedrock configurado (`SAGE_MODEL_FAST_ID` vazio) a pergunta volta com "Serviço indisponível (503): modelo não configurado"; o upload e a lista funcionam do mesmo jeito.

O texto que vem do modelo e dos documentos entra na página sempre como texto puro, nunca como HTML, e a política de segurança da aplicação (CSP) não deixa scripts nem estilos inline. Por isso um documento com `<script>` dentro aparece literalmente como `<script>`, sem executar.

## Desenvolvimento

O repositório traz um hook de pre-commit que roda o gitleaks nos arquivos em stage e bloqueia o commit se achar segredo. Para ativar:

```
git config core.hooksPath .githooks
```

O hook usa o binário `gitleaks` local; se ele não estiver instalado, só avisa e deixa o commit passar. A varredura de histórico completo roda de qualquer forma no CI (`.github/workflows/ci.yml`), junto com o build e o Trivy. A regra extra `sage-api-key` fica em `.gitleaks.toml`.
