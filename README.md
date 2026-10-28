# Sage-Agent

Um agente de documentação que responde a partir de um acervo próprio, em vez de pesquisar na internet a cada pergunta. Você envia a documentação (de uma linguagem, de um framework, da aplicação da sua empresa), ela fica guardada cifrada, e o Sage responde perguntas em português dizendo de qual versão da documentação cada resposta saiu e em qual trecho cada frase se apoia. Feito em Java 25 e Spring Boot 4.

## O problema

Um assistente comum que responde sobre documentação pesquisa na web a cada pergunta, e isso tem três defeitos. É lento e caro, porque cada pergunta paga uma busca e uma leitura de páginas. Mistura versões sem avisar: a resposta sobre `Stream.toList()` pode vir da documentação do Java 8 quando você usa o 21. E quando não sabe, inventa com a mesma confiança com que acerta.

O Sage inverte a ordem. A documentação entra uma vez, por upload ou por importação de um site oficial, e vira um acervo cifrado que só o processo do Sage consegue ler. A pergunta é respondida a partir dele: busca por palavra e por significado, um reranker que decide se existe resposta, e só então um modelo de linguagem (Claude, pelo Amazon Bedrock) escreve a partir de trechos citados. O texto que o modelo produz passa por um filtro frase a frase: sem citação válida, ou sem sustentação no trecho citado, a frase não chega a quem perguntou. Quando a documentação não cobre o assunto, a resposta é um texto fixo dizendo isso, sem chamar o modelo.

Toda resposta informa a versão exata da documentação usada ("Documentação consultada: Java 21"), e uma pergunta como "isso existe no Java 25 ou é exclusivo do Java 8?" é respondida versão a versão. Se a versão que você pediu não está no acervo, o Sage diz que não a tem, em vez de responder com outra.

## Como rodar com Docker

Precisa de Docker com pelo menos 4 GB de memória para o contêiner (os três modelos locais ocupam uns 3,7 GB residentes), de uns 3 GB de disco para a imagem e, para perguntas serem respondidas por um modelo de linguagem, de uma conta AWS com acesso a modelos no Bedrock. Sem a conta, o resto funciona: upload, busca e a página de testes. O que não funciona é responder.

Primeiro a chave mestra local, que cifra tudo o que o Sage grava. Ela vai para `secrets/`, que o git ignora:

```
scripts/generate-local-key.sh        # ou .\scripts\generate-local-key.ps1 no Windows
```

Depois uma chave de API. O script imprime a chave (que você guarda) e o hash dela (que vai para a configuração):

```
scripts/new-api-key.sh               # ou .\scripts\new-api-key.ps1
```

Copie `.env.example` para `.env` e preencha. No mínimo o hash da chave de API; os model ids do Bedrock são necessários só para responder perguntas:

```
SAGE_AUTH_KEYS_0_NAME=ana
SAGE_AUTH_KEYS_0_ROLE=EDITOR
SAGE_AUTH_KEYS_0_SHA256=<o hash impresso pelo script>

SAGE_MODEL_FAST_ID=<model id ou inference profile do modelo rápido>
SAGE_MODEL_STRONG_ID=<model id ou inference profile do modelo forte>
AWS_REGION=us-east-1
```

As credenciais AWS vêm da cadeia padrão (variáveis de ambiente do contêiner, perfil). O `docker-compose.yml` já monta `./data` e `./secrets` (esta última somente leitura).

Em Linux, o contêiner roda como o usuário 10001 e precisa gravar em `./data` e ler `./secrets`: `mkdir -p data && sudo chown 10001:10001 data`, e deixe a chave legível por esse usuário. No Docker Desktop (Windows, macOS) isso não é necessário.

```
docker compose up --build
```

O primeiro build é lento. Ele baixa os três modelos ONNX (uns 2,1 GB) de uma revisão fixa e confere o sha256 de cada um contra o `models.lock`; se um hash divergir, o build falha. Depois disso o cache do Docker evita baixar de novo. Quando a aplicação sobe, `/actuator/health` responde 503 até o índice terminar de carregar o acervo.

Para rodar sem Docker, com JDK 25, baixe os modelos e use o Maven Wrapper, com as mesmas variáveis no ambiente. No Windows (PowerShell): `.\scripts\download-models.ps1` e `.\mvnw.cmd spring-boot:run`. No Linux ou macOS: `scripts/download-models.sh` e `./mvnw spring-boot:run`. Sem os arquivos de modelo a aplicação sobe só com busca BM25 e sem a verificação de fundamentação, com um aviso no log; o Docker nunca sobe assim.

## O mini chat

Com a aplicação no ar, abra `http://localhost:8080/chat/`. É uma página estática, sem framework e sem nada carregado de fora, para experimentar a API. Cole a chave de API no campo do topo (ela fica no `sessionStorage` da aba e some quando a aba fecha). Na aba Documentos, envie arquivos (md, txt, pdf, docx, html ou um zip), aprove os que você revisou e apague os que não servem. Na aba Perguntar, a resposta chega por SSE, com a linha "Documentação consultada" em destaque, as fontes numeradas e um painel de diagnóstico (rota, modelo, score do melhor trecho, tempo de cada etapa). As abas de lacunas e de importação cobrem os outros dois endpoints. O contrato real é o de [docs/api.md](docs/api.md); a página não faz parte do produto.

O texto do modelo e dos documentos entra na página sempre como texto puro, e a política de segurança (CSP) proíbe scripts e estilos inline, então um documento com `<script>` dentro aparece como texto.

## Um exemplo

O que segue foi executado de verdade, numa máquina de desenvolvimento, com a aplicação empacotada deste repositório, os modelos de embeddings e reranker locais e o acervo de avaliação do projeto (36 documentos de Java 21 e 25, Spring Boot 3.5 e 4.1 e de uma aplicação fictícia, escritos para os testes). Não havia Bedrock acessível, e isso aparece nos resultados.

Subindo com a configuração padrão, sem model ids, a pergunta é recusada com 503 antes de qualquer outra coisa:

```
$ curl -i -X POST localhost:8080/api/perguntas \
    -H "Authorization: Bearer $SAGE_KEY" -H "Content-Type: application/json" \
    -d '{"pergunta":"Como usar toList em um Stream no Java 21?"}'

HTTP/1.1 503
Content-Type: application/problem+json

{"detail":"modelo não configurado","instance":"/api/perguntas","status":503,
 "title":"Serviço indisponível","type":"urn:sage:problem:unavailable"}
```

Com model ids preenchidos (de teste, sem credenciais), as perguntas que o Sage resolve sozinho, sem chamar o modelo, já funcionam. Pedindo uma versão que o acervo não tem:

```
$ curl -N -X POST localhost:8080/api/perguntas ... \
    -d '{"pergunta":"Como usar toList em um Stream no Java 8?"}'

event:metadata
data:{"rota":"NOT_DOCUMENTED","scoreMaximo":0.0,"citacoes":[],"versaoUsada":"8",
      "temposMs":{"analise":10,"busca":1}, ...}

event:text
data:{"texto":"Não tenho a documentação do Java 8 no acervo."}

event:done
data:{"rota":"NOT_DOCUMENTED", ..., "temposMs":{"analise":10,"busca":1,"total":21},
      "documentacaoConsultada":[]}
```

Vinte e um milissegundos, sem modelo e sem inventar uma resposta sobre o Java 21 com cara de resposta sobre o 8.

A resposta de uma pergunta que chega ao Claude não pôde ser gerada aqui, então o que segue é uma ilustração, escrita a partir do trecho `Streams > Collecting results` do acervo de avaliação, e não saída de um modelo:

```
Documentação consultada: Java 21

Stream.toList() é uma operação terminal que devolve uma lista não modificável
com os elementos do stream, na ordem de encontro [1].
Chamar add ou remove nessa lista lança UnsupportedOperationException [1].
Para ter uma lista mutável com certeza, use Collectors.toCollection(ArrayList::new),
porque Collectors.toList() não garante o tipo nem a mutabilidade [1].

[1] Java 21 · Streams > Collecting results
```

A linha "Documentação consultada" é emitida pelo código, antes da primeira frase que cita a fonte, e não pelo modelo. Cada frase termina numa citação que aponta para um trecho que foi de fato enviado ao modelo.

## Precificação aproximada

Os números abaixo são estimativas feitas a partir do tamanho dos prompts que o Sage monta, não medições: nenhuma conta foi conferida contra o Bedrock real. Servem para ter ordem de grandeza. Para o valor de verdade, rode umas 50 perguntas reais e some o `usage` que o Bedrock devolve em cada resposta.

### Preço dos modelos

Tabela da Anthropic, em dólares por milhão de tokens. No Amazon Bedrock a AWS publica o próprio preço, que pode variar um pouco; confira na [página de preços do Bedrock](https://aws.amazon.com/bedrock/pricing/) antes de fechar orçamento.

| Modelo | Entrada | Saída |
|---|---|---|
| Claude Haiku 5.5 | US$ 0,10 | US$ 0,50 |
| Claude Sonnet 5.5 | US$ 2,00 | US$ 10,00 |

O raciocínio interno do modelo (thinking) é cobrado como saída, por isso entra na conta da saída nas estimativas.

### Custo por pergunta

| Caminho | Entrada | Saída | Custo estimado |
|---|---|---|---|
| Haiku: consulta comum com até 5 trechos | ~3 mil tokens | ~1 mil tokens | ~US$ 0,0008 |
| Sonnet: comparação entre versões ou explicação de fluxo | ~5 a 8 mil tokens | ~1,5 a 2 mil tokens | ~US$ 0,02 a 0,04 |
| Resposta do cache ou "não encontrei" | 0 | 0 | US$ 0 |

A entrada é quase toda feita dos trechos de documentação enviados junto com a pergunta (cada trecho tem até 1500 caracteres). O Sonnet custa mais porque recebe mais contexto e escreve respostas mais longas, versão por versão ou passo a passo.

### Exemplo: equipe de 10 pessoas

Suponha 10 pessoas fazendo 20 perguntas por dia, 22 dias úteis no mês: 4.400 perguntas. Distribuindo como costuma acontecer, 75% vão para o Haiku, 20% para o Sonnet e 5% saem do cache ou terminam em "não encontrei" sem chamar modelo nenhum.

| Caminho | Perguntas | Custo unitário | Total |
|---|---|---|---|
| Haiku | 3.300 | US$ 0,0008 | ~US$ 2,60 |
| Sonnet | 880 | ~US$ 0,03 | ~US$ 26,40 |
| Cache / sem modelo | 220 | US$ 0 | US$ 0 |
| **Tokens no mês** | | | **~US$ 30** |

Isso dá uma média de cerca de US$ 0,007 por pergunta, e quase todo o valor vem do Sonnet. Com 100 pessoas no mesmo ritmo, a conta de tokens vai para cerca de US$ 300 por mês.

### Infraestrutura AWS por mês

Preços de us-east-1. Em São Paulo (`sa-east-1`) a mesma configuração fica uns 40 a 50% mais cara.

| Item | Custo mensal | Observação |
|---|---|---|
| ECS Fargate, 2 vCPU e 4 GB, ligado 24 horas | ~US$ 70 | os 4 GB são necessários para carregar os três modelos ONNX |
| NAT Gateway | ~US$ 35 + tráfego | só existe para a saída ao Brave no fallback web |
| 3 VPC endpoints (S3, KMS, Bedrock) | ~US$ 22 | mantêm o tráfego para a AWS fora da internet |
| KMS, S3 e logs | poucos dólares | o KMS cobra por requisição, cerca de duas por pergunta |
| **Total** | **~US$ 130 a 180** | custo fixo, independe do número de perguntas |

O Brave Search só gera custo quando a versão pedida não está no acervo e o Sage vai buscar na web. Ele tem plano gratuito com limite mensal e plano pago por mil consultas; confira os valores atuais no site do Brave.

### Juntando tudo

Para uma equipe pequena, são cerca de US$ 30 de tokens mais US$ 150 de infraestrutura: **uns US$ 180 por mês, perto de R$ 1.000**. Nessa escala quem pesa é a infraestrutura. Conforme o número de pessoas cresce, os tokens passam a dominar a conta.

### Onde dá para economizar

- **Sonnet.** É a maior parte do custo de tokens. Reduzir quantas perguntas viram comparação ou fluxo, ou baixar o `effort` do Sonnet, é a alavanca que mais mexe no valor.
- **Fargate em horário comercial.** Desligar fora do expediente corta perto de 60% do custo de computação. O preço é esperar o índice carregar de novo a cada subida.
- **Sem fallback web.** Se o acervo cobre o que a equipe usa, dá para desligar o fallback e eliminar o NAT Gateway.
- **Cache de prompt da Anthropic.** Ajuda pouco aqui: só o prompt de sistema se repete, e os trechos mudam a cada pergunta.

## Documentação

Cada arquivo responde a uma pergunta diferente, e a ordem abaixo é uma boa ordem de leitura.

- [docs/arquitetura.md](docs/arquitetura.md): as peças, o índice em memória, a busca híbrida e o reranker.
- [docs/fluxo-de-uma-pergunta.md](docs/fluxo-de-uma-pergunta.md): o caminho de uma pergunta, com exemplos e tempos.
- [docs/acervo-e-upload.md](docs/acervo-e-upload.md): frontmatter, upload, lacunas, importação e o template do Obsidian.
- [docs/seguranca.md](docs/seguranca.md): modelo de ameaças, criptografia, defesas contra vazamento e delírio, e o que não está coberto.
- [docs/fallback-web.md](docs/fallback-web.md): quando o Sage vai à internet e como se protege.
- [docs/api.md](docs/api.md): endpoints, eventos SSE e erros.
- [docs/configuracao.md](docs/configuracao.md): cada propriedade `sage.*`, variável de ambiente, padrão e efeito.
- [docs/deploy-aws.md](docs/deploy-aws.md): ECS Fargate, S3, KMS, VPC endpoints e IAM.
- [docs/decisoes.md](docs/decisoes.md): por que cada escolha importante foi feita, com os números da calibração.
- [docs/design.md](docs/design.md): o desenho original e o que mudou na implementação.

## Desenvolvimento

Precisa de JDK 25; o Maven Wrapper já vem no repositório.

```
.\mvnw.cmd verify
```

O `verify` roda o Spotless (formatação), o Checkstyle, o SpotBugs, os testes e o JaCoCo, que exige 80% de cobertura de linhas. Se a formatação reclamar, `.\mvnw.cmd spotless:apply` corrige. Os testes que precisam dos modelos ONNX (`@Tag("models")`) só rodam se os arquivos estiverem em `models/`; os que chamam o Bedrock real só rodam com `SAGE_BEDROCK_IT=true`, e os que usam LocalStack só com Docker. As avaliações pesadas (perguntas-gabarito, latência, calibração) ficam fora do padrão: `.\mvnw.cmd verify -Peval` roda tudo, cobra os orçamentos de latência e gera `target/eval-report.md`; precisa dos três modelos.

O repositório traz um hook de pre-commit que roda o gitleaks nos arquivos em stage e bloqueia o commit se achar segredo. Para ativar: `git config core.hooksPath .githooks`. O hook usa o binário `gitleaks` local; se ele não estiver instalado, só avisa. A varredura de histórico completo roda de qualquer forma no CI (`.github/workflows/ci.yml`), junto com o build e o Trivy.
