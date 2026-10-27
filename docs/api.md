# API

Este documento descreve o contrato HTTP do Sage: autenticação, perguntas, documentos e lacunas. O endpoint de importação entra nas próximas tarefas e ganha seção aqui quando chegar.

## Autenticação e papéis

Todo endpoint sob `/api/**` exige uma chave de API no cabeçalho `Authorization`:

```
Authorization: Bearer sage_<40 caracteres base62>
```

A chave tem o formato `sage_` seguido de 40 caracteres base62 (`[0-9A-Za-z]`), ou seja, cerca de 238 bits de entropia. Não há usuário, senha, sessão nem cookie: cada requisição se autentica sozinha. Foi uma escolha deliberada para uma API consumida por scripts e por uma página de chat que guarda a chave em memória. Em troca, quem tem a chave tem o acesso, e não existe "logout"; revogar é tirar a chave da configuração e reiniciar.

### Papéis

Cada chave tem um papel fixo na configuração:

| Papel | Pode |
| --- | --- |
| `READER` | `POST /api/perguntas` |
| `EDITOR` | tudo do `READER`, mais `/api/documentos/**`, `/api/lacunas/**` e `/api/importar/**` |

Sem chave (ou com chave inválida) a resposta é 401. Com chave válida, mas sem o papel necessário, é 403. Qualquer outro caminho de `/api/**` só exige uma chave válida, e fora de `/api/**` tudo é negado, exceto os caminhos públicos abaixo.

Caminhos públicos, sem chave: `/actuator/health` e `/chat/**` (a página estática do chat; a chave é digitada nela e enviada nas chamadas à API).

### Gerar e configurar uma chave

```
scripts/new-api-key.sh          # Linux, macOS, Git Bash
scripts/new-api-key.ps1         # Windows PowerShell
```

O script sorteia a chave com o gerador criptográfico do sistema e imprime a chave e o hash SHA-256 dela. A chave é mostrada uma vez e não fica guardada em lugar nenhum; só o hash vai para a configuração:

```yaml
sage:
  auth:
    keys:
      - name: ana
        role: EDITOR
        sha256: <hash impresso pelo script>
      - name: painel-suporte
        role: READER
        sha256: <outro hash>
```

Também dá para passar por variáveis de ambiente (`SAGE_AUTH_KEYS_0_NAME`, `SAGE_AUTH_KEYS_0_ROLE`, `SAGE_AUTH_KEYS_0_SHA256`, e assim por diante), o que evita deixar os hashes num arquivo versionado. O `name` é o que aparece na auditoria e no limite de requisições: use algo que identifique o cliente (`ana`, `painel-suporte`), nunca a própria chave. Nomes não podem se repetir.

A aplicação não sobe se um `sha256` não tiver 64 caracteres hexadecimais ou se faltar o papel. A mensagem de erro não repete o valor recebido, para que uma chave colada por engano no campo do hash não vá parar no log. Sem nenhuma chave configurada a aplicação sobe, mas toda chamada a `/api/**` responde 401.

### Limite de requisições

Cada chave tem um balde por ação, recarregado a cada minuto: `sage.rate-limit.questions-per-minute` (padrão 20) e `sage.rate-limit.uploads-per-minute` (padrão 10). Passou do limite, a resposta é 429 com o cabeçalho `Retry-After` em segundos inteiros (no mínimo 1):

```
HTTP/1.1 429
Retry-After: 37
Content-Type: application/problem+json

{"type":"urn:sage:problem:rate-limited","title":"Muitas requisições","status":429}
```

O limite vale por instância da aplicação, porque os baldes ficam em memória. Se o Sage rodar com várias instâncias, o limite efetivo é o configurado multiplicado pelo número delas.

### Formato dos erros

Todo erro da API é `application/problem+json` (RFC 9457) com `type`, `title` e `status`. Sem detalhe da exceção, sem nome de classe, sem stack trace e sem trecho de documento. A exceção são os erros de documento (415, 413, 422 e 404 do upload), que acrescentam um `detail` com uma frase fixa em português ("Campo obrigatório ausente no frontmatter: secao"); essas frases citam o nome do campo e nunca o valor enviado. O `type` é um URN estável (`urn:sage:problem:unauthorized`, `forbidden`, `rate-limited`, `bad-request`, `not-found`, `unavailable`, `internal-error`...) que o cliente pode comparar; o `title` é o texto para mostrar a uma pessoa. O 401 inclui `WWW-Authenticate: Bearer realm="sage"`.

```
HTTP/1.1 401
WWW-Authenticate: Bearer realm="sage"
Content-Type: application/problem+json

{"type":"urn:sage:problem:unauthorized","title":"Autenticação necessária","status":401}
```

Os mesmos cabeçalhos de segurança acompanham todas as respostas; estão descritos em [seguranca.md](seguranca.md).

## Perguntas

### POST /api/perguntas

Papel `READER` ou `EDITOR`. O corpo é JSON:

```json
{ "pergunta": "como usar toList no java 21?", "conversaId": "3f2a9c1e-7d4b-4e8a-9f10-2b6c5d8e1a47" }
```

`pergunta` é obrigatória, não pode ser só espaço e tem no máximo 2000 caracteres. O teto existe por dois motivos: limita o custo do reranker e do tokenizer (que são lineares no tamanho da entrada) e mantém as regex do analisador longe de entradas patológicas. `conversaId` é opcional; se vier, precisa casar `[A-Za-z0-9-]{1,64}`, porque vai para a auditoria. Sem ele o Sage gera um UUID e o devolve no `metadata`. Com ele, a pergunta é tratada como continuação: herda linguagem e versão da anterior (ver [fluxo-de-uma-pergunta.md](fluxo-de-uma-pergunta.md#cache-e-conversa)). Um `conversaId` que o servidor não conhece (expirou, ou a requisição caiu em outra instância) não dá erro: a pergunta vira o começo de uma conversa nova e o `metadata` devolve o id novo, que o cliente deve usar daí em diante.

A resposta é `text/event-stream`. Cada evento tem um nome e um corpo JSON numa linha `data:`. São quatro tipos:

| Evento | Quando | Corpo |
| --- | --- | --- |
| `metadata` | Uma vez, antes de qualquer texto. No modelo rápido, só quando sai a primeira frase aprovada (ver abaixo). | Rota, modelo, score do melhor trecho, citações, versão usada, se há fonte não revisada, tempos até ali. |
| `text` | Zero ou mais vezes. | `{"texto": "..."}`: uma frase já validada, um bloco de código inteiro, ou um texto fixo emitido pelo código (aviso de versão, "Documentação consultada: ...", "Não encontrei..."). |
| `done` | Uma vez, no fim. | O mesmo formato do `metadata`, com `frasesDescartadas` e `temposMs` finais e mais o campo `documentacaoConsultada`. |
| `error` | Se algo falhar depois que o stream começou. | `{"mensagem": "Não foi possível concluir a resposta. Tente de novo."}`, sempre a mesma frase. |

Cada `text` é uma unidade completa. A interface pode concatenar com espaço (ou quebra de linha, para bloco de código) sem se preocupar com frase cortada no meio, porque o servidor só solta uma frase depois que ela terminou e passou pela validação de citação descrita em [seguranca.md](seguranca.md#contra-delírio). O preço é que a primeira frase aparece inteira de uma vez, não letra por letra.

Exemplo de uma resposta (quebrado em linhas para leitura; cada `data:` vem numa linha só):

```
curl -N -X POST http://localhost:8080/api/perguntas \
  -H "Authorization: Bearer $SAGE_KEY" -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"pergunta":"como usar toList no java?"}'
```

```
event:metadata
data:{"conversaId":"b1c0e5d2-6a43-4f7e-9d2a-0c5e8f1b3a77","rota":"FAST_MODEL","modelo":"FAST",
      "scoreMaximo":0.92,
      "citacoes":[{"n":1,"documento":"publico/java/25/stream-api.md","secao":"Stream > Terminal operations",
                   "revisado":true,"linguagem":"java","versao":"25"},
                  {"n":2,"documento":"publico/java/25/colecoes.md","secao":"List","revisado":false,
                   "linguagem":"java","versao":"25"}],
      "versaoUsada":"25","fontesNaoRevisadas":true,"frasesDescartadas":0,"temposMs":{"analise":0,"busca":41}}

event:text
data:{"texto":"Considerei a versão 25, a mais recente disponível."}

event:text
data:{"texto":"Documentação consultada: Java 25"}

event:text
data:{"texto":"Stream.toList() devolve uma lista não modificável com os elementos do stream [1]."}

event:text
data:{"texto":"```java\nList<String> nomes = pessoas.stream().map(Pessoa::nome).toList();\n```"}

event:done
data:{"conversaId":"b1c0e5d2-6a43-4f7e-9d2a-0c5e8f1b3a77","rota":"FAST_MODEL","modelo":"FAST",...,
      "frasesDescartadas":1,"temposMs":{"analise":0,"busca":41,"modelo":1830,"total":1872},
      "documentacaoConsultada":[{"linguagem":"java","nome":"Java","versao":"25","fonte":"publico",
                                 "versaoNaoInformada":false}]}
```

O que cada campo quer dizer:

- `rota`: `FAST_MODEL` (respondeu com o modelo rápido), `STRONG_MODEL` (respondeu com o modelo forte: toda comparação, ou uma pergunta que o rápido escalou), `NOT_DOCUMENTED` (respondeu com texto fixo, sem chamar modelo) ou `CACHE` (repetiu uma resposta recente da mesma pergunta, sem busca nem modelo; `modelo` não aparece, e os `text` e o `documentacaoConsultada` são os da resposta original). Os outros valores do enum (`FLOW`, `WEB_FALLBACK`) ainda não são emitidos.
- `modelo`: `FAST` ou `STRONG`; ausente quando nenhum modelo foi chamado.
- `scoreMaximo`: score do melhor trecho, de 0 a 1. Com o reranker ligado é a nota dele; sem reranker é a cobertura lexical, outra escala (ver [fluxo-de-uma-pergunta.md](fluxo-de-uma-pergunta.md)).
- `citacoes`: os trechos enviados ao modelo, na ordem em que o texto os cita (`[1]`, `[2]`...). `versao` só aparece em documento público versionado; em documento interno, `linguagem` é o nome da aplicação. Com isso a interface mostra "[1] Java 25 · Stream > Terminal operations".
- `versaoUsada`: a versão pública que filtrou a busca, quando foi uma só.
- `fontesNaoRevisadas`: algum trecho enviado vem de documento que ninguém marcou como revisado.
- `frasesDescartadas`: quantas frases (e blocos de código) o guard barrou. Depois de uma escalada, soma o que o guard do modelo rápido barrou antes de escalar com o que barrou no modelo forte.
- `temposMs`: milissegundos por etapa (`analise`, `busca`, `modelo`, `total`).
- `documentacaoConsultada` (só no `done`): as fontes que as frases aprovadas citaram, na ordem em que apareceram, uma por linguagem e versão. É a versão estruturada das linhas "Documentação consultada: ..." do texto e segue a mesma regra: trecho que foi ao modelo mas não sustentou nenhuma frase não entra, e resposta sem frase aprovada traz a lista vazia. Cada item tem `linguagem` (id da linguagem pública, ou o nome da aplicação interna), `nome` (o nome legível, "Java", "Spring Boot"), `versao`, `fonte` (`publico` ou `interno`) e `versaoNaoInformada`. Documento interno vem com `versao` nulo e `versaoNaoInformada` falso. Documento público sem versão no frontmatter vem com `versao` nulo e `versaoNaoInformada` verdadeiro; no texto ele aparece como "Documentação consultada: Java (versão não informada)".

As linhas "Documentação consultada: ..." são eventos `text` comuns, emitidos pelo código e não pelo modelo. Cada uma sai imediatamente antes da primeira frase aprovada que cita um trecho daquela fonte, então numa comparação a sequência fica "Documentação consultada: Java 17", frases do 17, "Documentação consultada: Java 21", frases do 21. Uma fonte aparece uma vez só, mesmo que seja citada de novo depois. Quem quiser montar o rodapé de fontes sem ler o texto usa `documentacaoConsultada` do `done`.

No caminho do modelo rápido, o `metadata` e os avisos fixos que vêm logo depois dele ("Considerei a versão 25...", "Não tenho a documentação...") só saem com a primeira frase (ou bloco) que o guard aprovar, ou no fim da resposta se nada for aprovado. Até ali o modelo ainda pode pedir escalada para o modelo forte, e o `metadata` já sai com a rota e o modelo que de fato responderam. Na prática o atraso é o tempo até a primeira frase completa do Haiku. Se o modelo falhar antes disso, o `metadata` sai mesmo assim (com o `conversaId`), seguido do `error`.

Quando a rota é `NOT_DOCUMENTED`, o `metadata` vem com `citacoes` vazio e os `text` são textos fixos: "Não encontrei isso na documentação disponível." (nenhum trecho passou do limiar) ou "Não tenho a documentação do Java 8 no acervo." (a pergunta é só sobre documentação pública e pediu uma versão que não está indexada). Numa pergunta mista (pública e interna) que pede uma versão pública ausente, o aviso "Não tenho a documentação do Java 8 no acervo." sai logo depois do `metadata` e a resposta continua só com a documentação interna. Numa comparação entre versões ("isso existe no java 25 ou é exclusivo do java 8?"), a versão ausente recebe o mesmo aviso e a resposta segue com as que existem, pela rota `STRONG_MODEL`; só quando nenhuma das versões pedidas existe a rota é `NOT_DOCUMENTED`. Versão que existe mas não trouxe nenhum trecho acima do limiar de relevância recebe do código "A documentação do Java 17 disponível não menciona isso.". Essa linha sai na posição da versão na resposta: logo antes da primeira frase que cita uma versão posterior a ela, ou no fim, antes do `done`, se nenhuma posterior for citada. Numa comparação mista ("isso mudou do java 17 para o 21 na nossa app X?"), a documentação interna entra como mais um grupo, depois das versões. São buscados no máximo 4 grupos (`sage.retrieval.comparison-max-chunks` / 2, contando o interno); com mais versões pedidas, logo depois do `metadata` sai "Comparei só as versões Java 8, Java 11, Java 17 e Java 21; peça as demais em outra pergunta.", e as versões de fora não recebem "não menciona".

Perguntas de fluxo ("como funciona o fluxo de faturamento no Portal X?") vão pelo `STRONG_MODEL` e levam, no prompt, documentos internos inteiros e os ligados por wiki-link (`sage.flow.*`); a resposta vem em passos numerados, cada um com citação, e pode terminar num bloco ```mermaid. Quando nenhum trecho passa do limiar, o `text` fixo é "A documentação não detalha esse fluxo.". Detalhes em [fluxo-de-uma-pergunta.md](fluxo-de-uma-pergunta.md#perguntas-de-fluxo).

Respostas de erro, todas `application/problem+json` e antes de o stream abrir:

| Status | Quando |
| --- | --- |
| 400 | Corpo não é JSON, `pergunta` vazia ou com mais de 2000 caracteres, `conversaId` fora do formato. A resposta não repete a pergunta. |
| 401, 403 | Sem chave ou chave inválida. |
| 429 | Mais de `sage.rate-limit.questions-per-minute` perguntas por minuto na mesma chave. |
| 503 + `Retry-After: 5` | O índice ainda está carregando o acervo (logo depois de subir). `detail`: "O índice ainda está carregando; tente de novo em alguns segundos." |
| 503 | Modelo não configurado: faltam `SAGE_MODEL_FAST_ID`/`SAGE_MODEL_STRONG_ID` ou o Bedrock está desligado (`SAGE_CHAT_MODEL=none`). `detail`: "modelo não configurado". |

A ordem das verificações é índice, modelo e limite: um 503 não gasta a cota da chave.

Cada pergunta gera dois eventos na auditoria cifrada. `QUESTION_ASKED` é gravado antes de qualquer evento SSE, com o id da conversa, a rota e os ids dos documentos enviados ao modelo; se essa gravação falhar, o cliente recebe só um `error` e nada da resposta é transmitido. `QUESTION_ANSWERED` é gravado no fim com o desfecho (`completed`, `interrupted` ou `failed`), a rota e o modelo finais (depois de uma escalada, `STRONG_MODEL` e `STRONG`, com `escalated=true`), os ids dos documentos efetivamente citados até ali e o número de frases descartadas; numa resposta completa ele é gravado antes do `done`, e se falhar o cliente recebe `error` no lugar do `done`. Nenhum dos dois leva o texto da pergunta ou da resposta. Um acerto de cache grava os mesmos dois eventos, com rota `CACHE`; ver [fluxo-de-uma-pergunta.md](fluxo-de-uma-pergunta.md#cache-e-conversa).

Toda resposta termina com `done` ou com `error`. O modelo tem um prazo total, `sage.models.timeout` (50 s), contado do pedido ao último token: um modelo que manda um pedaço a cada segundo também é cortado. Quando o modelo rápido escala, o prazo é o mesmo para as duas chamadas somadas. Se ele falhar ou estourar o prazo depois que o stream começou, o cliente recebe um `error` e o stream fecha; as frases já enviadas continuam válidas. O SSE fica aberto por `sage.questions.preparation-timeout` (20 s, o orçamento de análise, busca, fallback web e auditoria) mais o prazo do modelo, então o `error` do serviço chega antes de o stream expirar sozinho. Se o cliente fechar a conexão, o Sage cancela a chamada ao modelo na hora, para o Bedrock parar de gerar (e de cobrar) tokens que ninguém vai ler.

## Documentos

Todos os endpoints desta seção exigem papel `EDITOR`. O ator registrado na auditoria é o nome da chave de API. O que o upload aceita e recusa, e por quê, está em [acervo-e-upload.md](acervo-e-upload.md).

### POST /api/documentos

Envia um arquivo (ou um zip) como `multipart/form-data`. Só `file` é obrigatório; os outros campos preenchem o que o documento não traz no frontmatter e, quando não há `id` no frontmatter, formam o id:

| Campo | Para que serve |
| --- | --- |
| `file` | O arquivo: Markdown, texto, PDF, DOCX, HTML ou zip de `.md`/`.txt`. |
| `fonte` | `publico` ou `interno`. |
| `linguagem` | Linguagem ou framework (público) ou nome da aplicação (interno). |
| `versao` | Versão, obrigatória para `fonte=publico`. |
| `secao` | Seção; sem ela vale o nome do arquivo sem extensão. |
| `url_origem` | URL `http(s)` da documentação, obrigatória para `fonte=publico` quando o arquivo não tem frontmatter. |
| `substituir` | `true` para sobrescrever documentos que já existem; sem ele, id repetido dá 409. |

`fonte`, `linguagem` e `versao` também podem vir só no frontmatter (sem `id`): o id é montado com eles. Se o formulário e o frontmatter divergirem, 422. Os campos do formulário passam pela mesma varredura de segredos do documento.

```
curl -X POST http://localhost:8080/api/documentos \
  -H "Authorization: Bearer $SAGE_KEY" \
  -F file=@faturamento.pdf -F fonte=interno -F linguagem=portal-x -F secao=Faturamento
```

```
HTTP/1.1 201
{"ids":["interno/portal-x/faturamento.md"],"substituidos":[]}
```

Num zip a resposta lista um id por entrada, na ordem do arquivo. Todas as entradas são validadas antes de gravar a primeira, então um segredo na última entrada não deixa metade do zip gravada.

Respostas de erro:

| Status | Quando |
| --- | --- |
| 400 | Falta a parte `file` ou o multipart está malformado. |
| 401, 403 | Sem chave, ou chave `READER`. |
| 413 | Arquivo ou Markdown convertido acima de `sage.upload.max-file-bytes`; zip acima de `max-zip-bytes`, com mais de `max-zip-entries` entradas ou mais de `max-zip-uncompressed-bytes` depois de descompactado; requisição acima do limite do multipart. |
| 415 | Tipo de arquivo fora da lista (o tipo vem do conteúdo, não do nome), ou entrada de zip que não é `.md`/`.txt`. |
| 409 | Algum id do envio já existe e `substituir=true` não foi enviado. Nada é gravado e a resposta não repete o id. Com `substituir=true`, os ids trocados voltam em `substituidos`. |
| 422 | Frontmatter inválido, id impossível de montar, campo do formulário inválido, zip com caminho inseguro, zip corrompido, PDF sem texto, id repetido dentro do zip, ou segredo detectado. |
| 429 | Mais de `sage.rate-limit.uploads-per-minute` envios por minuto na mesma chave. |

Quando o motivo é segredo, a resposta lista só tipo e linha, nunca o valor:

```
HTTP/1.1 422
Content-Type: application/problem+json

{"type":"urn:sage:problem:secret-detected","title":"Segredo detectado no documento","status":422,
 "detail":"O documento parece conter credenciais; remova-as e envie de novo",
 "segredos":[{"tipo":"aws-access-key","linha":3}]}
```

Se o envio era um zip, a resposta traz também `"arquivo"` com o nome da entrada que falhou, só o último trecho do caminho e no máximo 100 caracteres (a varredura para na primeira entrada com segredo). Se o segredo estava num campo do formulário, `arquivo` vale `campo <nome>`.

### GET /api/documentos

Lista os metadados de todos os documentos, sem o texto:

```
[{"id":"publico/java/25/stream-api.md","fonte":"publico","linguagem":"java","versao":"25",
  "secao":"Streams","revisado":true,"coletado_em":"2026-10-01"},
 {"id":"interno/portal-x/faturamento.md","fonte":"interno","linguagem":"java",
  "secao":"Faturamento","revisado":false,"coletado_em":"2026-10-27"}]
```

`versao` só aparece nos documentos que têm versão.

### PATCH /api/documentos?id=...

Marca ou desmarca um documento como revisado. O corpo é `{"revisado": true}` (ou `false`); sem o campo, 400. Responde 200 sem corpo.

### DELETE /api/documentos?id=...

Remove o documento. Responde 204 sem corpo.

Nos dois últimos, um `id` que não existe dá 404 e um `id` fora do formato dá 422. Em nenhum dos casos a resposta repete o id recebido.

## Lacunas

### GET /api/lacunas

Só `EDITOR`. Lista as perguntas sobre a documentação interna que ficaram sem resposta ("Não encontrei isso na documentação disponível." ou "A documentação não detalha esse fluxo."), agrupadas pela pergunta normalizada: "Qual é o prazo?" e "qual e o prazo" contam como a mesma. A ordem é a de `vezes` decrescente e, no empate, `ultimaVez` decrescente.

```
[{"pergunta":"Como funciona a cobrança recorrente do portal-x?","ultimaVez":"2026-10-27T14:05:12Z","vezes":4},
 {"pergunta":"Qual o prazo do estorno?","ultimaVez":"2026-10-26T09:30:01Z","vezes":1}]
```

`pergunta` é o texto da ocorrência mais recente do grupo, como a pessoa escreveu. `ultimaVez` é um instante em UTC. O parâmetro opcional `limite` (padrão 100, máximo 500) corta a lista; valor acima de 500 vale 500 e abaixo de 1 vale 1. Um `limite` que não é número dá 400 com `application/problem+json`. Leitor recebe 403, sem chave 401.

O resumo cobre só as ocorrências ainda guardadas (`sage.gaps.max-entries`, padrão 5000), então `vezes` pode ficar abaixo do total histórico quando o teto é atingido. Mais detalhes em [Acervo e upload](acervo-e-upload.md#lacunas).
