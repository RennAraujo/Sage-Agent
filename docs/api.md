# API

Este documento descreve o contrato HTTP do Sage. Por enquanto só a parte de autenticação existe; os endpoints de perguntas, upload e lacunas entram nas próximas tarefas e ganham seção aqui quando chegarem.

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

Todo erro da API é `application/problem+json` (RFC 9457) com `type`, `title` e `status`, e nada mais: sem detalhe da exceção, sem nome de classe, sem stack trace e sem trecho de documento. O `type` é um URN estável (`urn:sage:problem:unauthorized`, `forbidden`, `rate-limited`, `bad-request`, `not-found`, `internal-error`...) que o cliente pode comparar; o `title` é o texto para mostrar a uma pessoa. O 401 inclui `WWW-Authenticate: Bearer realm="sage"`.

```
HTTP/1.1 401
WWW-Authenticate: Bearer realm="sage"
Content-Type: application/problem+json

{"type":"urn:sage:problem:unauthorized","title":"Autenticação necessária","status":401}
```

Os mesmos cabeçalhos de segurança acompanham todas as respostas; estão descritos em [seguranca.md](seguranca.md).
