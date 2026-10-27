# Acervo e upload

## Como o acervo é organizado

O acervo tem dois tipos de documento, e a diferença aparece já no id. Documentação pública (a de uma linguagem ou framework) vive em `publico/<linguagem>/<versao>/<slug>.md`, por exemplo `publico/java/25/stream-api.md`. Documentação interna de um sistema da empresa vive em `interno/<app>/<slug>.md`, por exemplo `interno/portal-x/faturamento.md`. Os segmentos seguem `[a-z0-9][a-z0-9._-]*` e o id é normalizado para minúsculas, então `Publico/Java/25/A.md` e `publico/java/25/a.md` são o mesmo documento. Qualquer coisa fora desse formato (`..`, segmento vazio, extensão diferente de `.md`, prefixo desconhecido) é rejeitada com `InvalidDocumentException`.

A versão fica no caminho do documento público porque a busca vai precisar separar "como era no Java 17" de "como é no Java 25". Para documentos internos não há versão: o que vale é o que está no acervo hoje.

## Frontmatter

Todo documento é um Markdown com um bloco YAML no topo, delimitado por `---`. Os campos:

```yaml
---
id: publico/java/25/stream-api.md
fonte: publico            # publico | interno, precisa bater com o prefixo do id
linguagem: java
versao: "25"              # obrigatório para fonte publico
secao: Streams
aliases: [Stream, "java.util.stream"]
url_origem: https://docs.oracle.com/en/java/javase/25/docs/api/   # obrigatório para fonte publico
coletado_em: 2026-10-01   # padrão: a data do upload
revisado: true            # padrão: false
---
```

`linguagem` e `secao` são sempre obrigatórios; `id` também, a menos que quem faz o upload informe o id por fora (a Task de upload vai derivá-lo do caminho). Campos ausentes podem ser preenchidos por valores padrão da requisição (`DocumentDefaults`), o que permite subir uma pasta inteira de arquivos sem repetir o mesmo cabeçalho em cada um. O campo `url_origem` só aceita `http` e `https`; `file:` e afins são recusados, porque essa URL depois aparece como fonte clicável nas respostas.

O parser tira o BOM, converte CRLF para LF (arquivos exportados no Windows chegam assim, e um `\r` sobrando no corpo quebraria o chunking mais adiante) e carrega o YAML com `SafeConstructor`. Isso significa que tags como `!!javax.script.ScriptEngineManager` não constroem objeto nenhum: o documento é recusado. Um detalhe que custou uma decisão: o parser também desliga a resolução implícita de tipos do YAML, então todo valor é lido como texto. Com a resolução padrão, `versao: 3.20` viraria o número `3.2` e a versão certa se perderia sem aviso. A contrapartida é que `revisado` e `coletado_em` são convertidos à mão e só aceitam `true`/`false` e `AAAA-MM-DD`.

As mensagens de erro dizem qual campo está errado, nunca repetem o conteúdo enviado. O erro de sintaxe do SnakeYAML cita trechos do texto, então ele é traduzido para uma mensagem fixa; a consequência é que o aviso de sintaxe não aponta a linha.

## Como o documento é guardado

Cada documento vira um único blob cifrado. O conteúdo do blob é o próprio Markdown com o frontmatter (o mesmo texto que `FrontmatterParser.render` produz), cifrado pelo `EnvelopeCipher` descrito em `seguranca.md`. A chave do blob é `docs/<uuid aleatório>` e nada mais.

Na subida, o `EncryptedDocumentStore` lista `docs/`, decifra todos os blobs e monta um catálogo em memória: id do documento, chave do blob e metadados. Dá para fazer isso porque o acervo é pequeno (centenas de documentos, não milhões); cada subida custa uma decifragem por documento, e com KMS isso é uma chamada de rede por documento. Se o acervo crescer a ponto de a subida demorar, o caminho é um índice cifrado à parte, mas hoje isso seria complexidade sem necessidade. Se algum blob não decifrar, a aplicação não sobe: servir um acervo pela metade seria pior do que não servir.

Salvar um id que já existe reaproveita a chave do blob e sobrescreve o objeto. Assim a troca é um único `put` atômico e não sobra blob órfão se a aplicação cair no meio. `save` e `delete` são serializados por id; ids diferentes não esperam um pelo outro. Cada operação publica um `DocumentSavedEvent` ou `DocumentDeletedEvent` para o índice de busca se manter em dia; apagar um id que não existe não publica nada.

## Por que as chaves no S3 são opacas

O bucket do S3 e o disco local guardam só bytes cifrados, mas o nome do objeto não é cifrado: aparece em listagens, em logs de acesso do S3, no CloudTrail e em qualquer backup. Uma chave como `interno/portal-x/faturamento.md` entregaria quais sistemas existem e quais assuntos estão documentados para quem tivesse só acesso de leitura ao bucket ou aos logs, sem precisar de uma chave de decifragem. Com `docs/<uuid>`, quem olha de fora vê quantos documentos existem e o tamanho aproximado de cada um, e mais nada.

O que continua vazando: o número de documentos, o tamanho de cada blob (o AES-GCM não esconde o comprimento) e os momentos de escrita. Se o tamanho for um problema para o seu modelo de ameaça, seria preciso preencher (padding) o payload antes de cifrar, o que ainda não foi feito.

O mesmo vale para o armazenamento local: `sage.storage.local-dir` (padrão `data/`) tem os mesmos arquivos `docs/<uuid>`, e os testes varrem o diretório procurando um texto-canário e pedaços de id para garantir que nada apareça em claro. As chaves de blob só aceitam `[a-z0-9/-]`, sem barra inicial, o que também fecha a porta para path traversal no armazenamento em disco.

## Configuração

```yaml
sage:
  storage:
    type: local        # local | s3
    local-dir: data    # usado com type=local
    s3-bucket: ""      # obrigatório com type=s3 (variável SAGE_S3_BUCKET)
```

Com `type: s3`, região e credenciais vêm da cadeia padrão da AWS, como no KMS. O bucket precisa existir; a aplicação não o cria. A escrita local é atômica (arquivo temporário no mesmo diretório e `Files.move` com `ATOMIC_MOVE`), então um corte de energia deixa o blob antigo ou o novo, nunca um arquivo pela metade.

Limitação conhecida: o catálogo em memória pressupõe uma única instância da aplicação por bucket. Duas instâncias escrevendo no mesmo acervo não enxergam as mudanças uma da outra até reiniciar.
