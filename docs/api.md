# API

Este documento descreve o contrato HTTP do Sage. Por enquanto existem a autenticação e os endpoints de documentos; os de perguntas e lacunas entram nas próximas tarefas e ganham seção aqui quando chegarem.

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

Todo erro da API é `application/problem+json` (RFC 9457) com `type`, `title` e `status`. Sem detalhe da exceção, sem nome de classe, sem stack trace e sem trecho de documento. A exceção são os erros de documento (415, 413, 422 e 404 do upload), que acrescentam um `detail` com uma frase fixa em português ("Campo obrigatório ausente no frontmatter: secao"); essas frases citam o nome do campo e nunca o valor enviado. O `type` é um URN estável (`urn:sage:problem:unauthorized`, `forbidden`, `rate-limited`, `bad-request`, `not-found`, `internal-error`...) que o cliente pode comparar; o `title` é o texto para mostrar a uma pessoa. O 401 inclui `WWW-Authenticate: Bearer realm="sage"`.

```
HTTP/1.1 401
WWW-Authenticate: Bearer realm="sage"
Content-Type: application/problem+json

{"type":"urn:sage:problem:unauthorized","title":"Autenticação necessária","status":401}
```

Os mesmos cabeçalhos de segurança acompanham todas as respostas; estão descritos em [seguranca.md](seguranca.md).

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
