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

`linguagem` e `secao` são sempre obrigatórios; `id` também, a menos que quem faz o upload informe o id por fora (o upload deriva o id dos campos do envio e do nome do arquivo, como descrito em "Como enviar"). Campos ausentes podem ser preenchidos por valores padrão da requisição (`DocumentDefaults`), o que permite subir uma pasta inteira de arquivos sem repetir o mesmo cabeçalho em cada um. O campo `url_origem` só aceita `http` e `https`; `file:` e afins são recusados, porque essa URL depois aparece como fonte clicável nas respostas. Num documento público, a linguagem e a versão do caminho do id (`publico/java/25/...`) precisam ser as mesmas de `linguagem` e `versao` (sem diferenciar caixa); se não forem, a resposta é 422 com "Campo versao contradiz o id" (ou `linguagem`), sem repetir os valores. A busca filtra pelos campos e a citação mostra o id: se os dois divergissem, uma resposta citaria um documento do Java 21 dizendo que é do 25.

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

## Lacunas

Quando alguém pergunta algo sobre a documentação interna e o Sage responde "Não encontrei isso na documentação disponível." (ou, numa pergunta de fluxo, "A documentação não detalha esse fluxo."), a pergunta vira uma lacuna: um sinal de que falta documento, ou de que o documento existe e não cobre aquele ponto. O registro vale para perguntas internas e mistas, e também para as que não citam nenhum nome reconhecido: sem linguagem nem aplicação no texto, o Sage não sabe se a pergunta é pública, e "Qual a capital da França?" vira lacuna do mesmo jeito (observado numa execução real, veja [fluxo-de-uma-pergunta.md](fluxo-de-uma-pergunta.md#exemplos-por-caminho-e-tempos)). Quem lê a lista de lacunas precisa saber filtrar esse ruído. Pergunta só sobre documentação pública que não achou resposta não entra aqui, porque o remédio é outro (o fallback web), e resposta vinda do cache também não, para não contar duas vezes a mesma ocorrência. Numa pergunta de fluxo sobre documentação interna, também conta quando o modelo recebe contexto e responde a frase fixa "A documentação não detalha esse fluxo." sem nenhum passo aprovado pelo guard: o contexto existia, mas não cobria o fluxo. Se pelo menos um passo foi aprovado, a frase não aparece e nada é registrado.

Cada ocorrência é um blob cifrado em `gaps/<uuid>`, com o mesmo envelope do resto do acervo. Dentro dele ficam a pergunta, o instante, a pergunta normalizada (minúsculas, sem acento nem pontuação, usada para agrupar) e um HMAC-SHA256 (16 caracteres hexadecimais) do nome da chave que perguntou. O segredo do HMAC é sorteado na primeira gravação e fica cifrado em `keys/gap-hmac`; um SHA-256 simples seria quebrado por dicionário, já que os nomes das chaves são poucos e previsíveis. O nome em si não é gravado, e a pergunta nunca vai para log. Como a chave do blob é só um UUID, quem olha o bucket não descobre o que as pessoas andam perguntando.

Uma linha por ocorrência, em vez de um contador por pergunta, evita ler-modificar-gravar: duas perguntas iguais ao mesmo tempo não perdem uma a contagem da outra. O custo é crescimento, então há um teto, `sage.gaps.max-entries` (padrão 5000). Passou do teto, os registros mais antigos são apagados na própria gravação; se o armazém recusar o delete, o registro continua contado e a poda é refeita na gravação seguinte. Na prática isso significa que `vezes` conta só as ocorrências que ainda cabem no teto; para uma instalação pequena, 5000 blobs de poucas centenas de bytes são desprezíveis, e quem quiser histórico longo pode aumentar o valor.

A lista (`GET /api/lacunas`, só `EDITOR`) agrupa pela pergunta normalizada e ordena por número de vezes e, no empate, pela mais recente. Ao subir, o Sage decifra todos os blobs de `gaps/` em segundo plano (e, se isso falhar, na primeira chamada que precisar); depois mantém o resultado em memória e só o refaz quando chega uma lacuna nova. Blob que não decifra é ignorado, com um aviso no log com a contagem.

Se a gravação da lacuna falhar, a resposta ao usuário não muda: ele já recebeu o "não encontrei" e não há o que desfazer. O log registra só a classe da falha. É uma diferença deliberada em relação à auditoria, que é fail-closed: perder uma lacuna custa uma linha a menos na lista, perder um evento de auditoria custa o rastro de quem fez o quê. Como o acervo, o registro pressupõe uma única instância da aplicação.

## Configuração

```yaml
sage:
  storage:
    type: local        # local | s3
    local-dir: data    # usado com type=local
    s3-bucket: ""      # obrigatório com type=s3 (variável SAGE_S3_BUCKET)
    s3-kms-key-id: ""  # chave KMS do SSE-KMS de cada objeto; vazio usa a padrão do bucket (SAGE_S3_KMS_KEY_ID)
  gaps:
    max-entries: 5000  # teto de ocorrências de lacuna guardadas; as mais antigas saem primeiro
```

Com `type: s3`, região e credenciais vêm da cadeia padrão da AWS, como no KMS. O bucket precisa existir; a aplicação não o cria. A escrita local é atômica (arquivo temporário no mesmo diretório e `Files.move` com `ATOMIC_MOVE`), então um corte de energia deixa o blob antigo ou o novo, nunca um arquivo pela metade.

Limitação conhecida: o catálogo em memória pressupõe uma única instância da aplicação por bucket. Duas instâncias escrevendo no mesmo acervo não enxergam as mudanças uma da outra até reiniciar.

## Como enviar

O envio é um `POST /api/documentos` com o arquivo em `file` (contrato completo em [api.md](api.md)). O que entra: Markdown, texto puro, PDF, DOCX, HTML e zip de `.md`/`.txt`. Tudo vira Markdown em UTF-8 antes de ser guardado, e daí em diante o documento é tratado como qualquer outro do acervo.

O id sai do frontmatter, se ele tiver o campo `id`. Sem `id`, o upload monta um a partir de `fonte`, `linguagem` (e `versao`, para público) e do nome do arquivo. Esses três campos podem estar no frontmatter ou no formulário (os dois, desde que concordem; se divergirem, a resposta é 422 citando só o nome do campo). O resultado: `publico/<linguagem>/<versao>/<slug>.md` ou `interno/<linguagem>/<slug>.md`, onde o slug é o nome do arquivo sem extensão, em minúsculas, sem acento e só com `[a-z0-9-]`. `Guia de Instalação.md` com `fonte=publico`, `linguagem=java`, `versao=25` vira `publico/java/25/guia-de-instalacao.md`. Para documentos internos, `linguagem` faz o papel do nome da aplicação no id (`interno/portal-x/...`), então use o nome da aplicação ali. Um PDF ou DOCX nunca tem frontmatter, então para eles os campos do formulário são o único jeito de informar fonte, linguagem e versão. Um documento público sem frontmatter também precisa de `url_origem` no formulário, porque o frontmatter que o upload monta passa pelo mesmo parser, e `url_origem` é obrigatório para fonte pública. A `secao`, se faltar em tudo, é o nome do arquivo sem extensão. Os campos do formulário também servem de padrão para um frontmatter que não os traga.

Num zip, cada entrada `.md` ou `.txt` é um documento: o id vem do frontmatter da entrada ou dos campos do formulário mais o nome do arquivo da entrada (a pasta não entra no id). Duas entradas que resultem no mesmo id derrubam o envio inteiro com 422, para um não sobrescrever o outro sem ninguém ver. Diretórios, a pasta `__MACOSX` e arquivos ocultos (nome começando com ponto, como o `.obsidian/` de um vault) são ignorados.

Todo documento enviado entra com `revisado: false`, a menos que o frontmatter diga outra coisa. O `PATCH` marca como revisado depois que alguém leu.

Enviar um id que já existe é um conflito: a resposta é 409 e nada é gravado (num zip, basta uma entrada em conflito para o lote inteiro ser recusado). Para sobrescrever de propósito, envie o campo `substituir=true`. Nesse caso o documento antigo é trocado, o `revisado` volta ao valor do novo arquivo, a resposta lista os ids trocados em `substituidos` e a auditoria marca o evento com `substituido=true`. Sem `substituir`, a gravação é `DocumentStore.saveIfAbsent`, que confere e grava sob o mesmo lock do id: dois envios simultâneos do mesmo id novo podem os dois passar pela conferência inicial, mas só um grava; o outro recebe 409. Num zip, as demais entradas do envio que perdeu a corrida ainda são gravadas (a gravação, uma a uma, não é transacional) e o 409 avisa que pelo menos uma não entrou.

## O que é recusado e por quê

O tipo do arquivo vem do conteúdo, não do nome. O detector do Tika olha os primeiros bytes, e um executável renomeado para `leia-me.md` continua sendo um executável: 415. O nome só serve de dica para desempatar entre tipos de texto. Texto com cara de código ou YAML só passa se o nome terminar em `.md`, `.markdown` ou `.txt`; sem isso é 415. Um PDF que só tem imagem (escaneado) dá 422, porque não há OCR: ele está desligado de propósito, já que chamaria um programa externo (Tesseract) sobre arquivo enviado por terceiro.

Texto fora de UTF-8 é decodificado com o detector de charset do Tika (um `.txt` em ISO-8859-1 ou Windows-1252 chega com "configuração" intacto) e gravado em UTF-8. A tentativa é sempre UTF-8 estrito primeiro; o detector só entra quando o arquivo não é UTF-8 válido. Em textos muito curtos o detector pode errar, e o fallback é Windows-1252.

O HTML passa pelo `Safelist.relaxed` do jsoup (sem script, sem `style`, sem atributos de evento, sem `javascript:` em link) e depois pelo flexmark, que o converte em Markdown com títulos `#`. Links relativos perdem o `href`, porque não há base para resolvê-los. O PDF passa pelo Tika (que usa o PDFBox por baixo) com um limite de caracteres igual ao `max-file-bytes`, sem processar anexos nem objetos embutidos, e o que sai é texto simples: listas viram parágrafos. O DOCX é lido direto pelo POI, em memória, e os estilos `Heading1` a `Heading6` viram `#`; tabelas viram linhas com células separadas por tabulação. É uma perda aceita; a alternativa seria converter o XHTML do Tika, o que dobra a superfície de parsing.

Os limites, em `sage.upload`:

```yaml
sage:
  upload:
    max-file-bytes: 10MB            # arquivo comum e também o Markdown convertido
    max-zip-bytes: 50MB             # o zip em si
    max-zip-entries: 500
    max-zip-uncompressed-bytes: 200MB
```

O limite vale também para o Markdown depois da conversão, porque o store de documentos não tem teto próprio e um PDF de 2 MB pode virar bem mais texto. Para zip bomb, os bytes descompactados são contados enquanto a entrada é lida; o tamanho declarado no cabeçalho do zip não é confiável. O teto do multipart do Spring é derivado de `max-zip-bytes` pelo `UploadConfig` (arquivo = `max-zip-bytes`, requisição = isso mais 1MB para os campos do formulário); passou disso, a resposta é 413 em `problem+json` como as demais. O mesmo bean fixa o limiar de gravação em disco do multipart no teto da requisição. O padrão do Spring é 0, e com ele o Tomcat grava cada parte, em claro, num `upload_*.tmp` do diretório temporário; com o limiar alto, o upload fica em memória do começo ao fim. O mesmo cuidado vale para o Tika: o detector de contêiner OLE2 e o caminho OOXML do Tika copiavam o upload para `apache-tika-*.tmp`, então esses detectores ficam de fora (um `.doc` antigo sai como tipo genérico do Office e dá 415) e o DOCX é lido pelo POI em memória. Testes vigiam o diretório do multipart e o `java.io.tmpdir` durante os envios. Entradas de zip com caminho absoluto, `..`, barra invertida ou prefixo de unidade (`C:`) derrubam o envio (422), mesmo que apareçam numa pasta que seria ignorada. Entrada que não seja `.md` ou `.txt` (uma imagem de um vault, por exemplo) dá 415; escolhi recusar em vez de ignorar em silêncio para ninguém achar que a imagem entrou.

Antes de gravar qualquer coisa, o texto convertido passa pelo `SecretScanner`, que procura chave de acesso da AWS, chave privada, token do GitHub, JWT, token do Slack, chave do próprio Sage e atribuições do tipo `senha: valor` (o padrão `password-assignment`: as palavras password, senha, secret ou token, seguidas de `:` ou `=` e de pelo menos 8 caracteres sem espaço). O achado volta como tipo e linha, nunca o valor. Os padrões são simples e pendem para recusar demais: `senha: ${SENHA_DO_BANCO}` também é recusado, porque a regra não distingue um valor de um nome de variável. Se isso incomodar num documento legítimo, a saída é reescrever o trecho (por exemplo, descrever a variável sem a atribuição). A varredura pega só o que os padrões conhecem; um segredo em formato próprio passa, então ela é uma rede de segurança e não substitui revisão.

Cada gravação, revisão e remoção gera um evento de auditoria (`DOCUMENT_UPLOADED`, `DOCUMENT_REVIEWED`, `DOCUMENT_DELETED`) com o id do documento e, no upload, o tamanho do texto. O evento é gravado antes da mudança: se a auditoria falhar, a resposta é 500 e o acervo fica como estava (o contrário deixaria um documento salvo, e indexado, sem registro). No upload ele vem em duas fases, como na importação e no fallback web: `DOCUMENT_UPLOADED` com `resultado=tentativa` antes de gravar e, depois, `salvo`, `existente` (outro envio gravou o mesmo id antes; este recebe 409) ou `falhou` (a gravação lançou exceção; se a auditoria do desfecho também falhar, essa falha vai junto, como exceção suprimida, e a original é a que sobe). Se o documento foi gravado e quem lançou foi um ouvinte da mudança, como o índice, o desfecho é `salvo` com `aviso=indice`: o acervo tem o documento, e registrar `falhou` mentiria sobre ele. A resposta continua sendo erro, e a importação e o fallback web seguem a mesma regra. Num zip, todas as tentativas são gravadas antes da primeira gravação. O id pode ir para a auditoria porque ela é cifrada; o conteúdo nunca vai. Nos logs da aplicação o id não aparece (só o `logRef`) e nenhum texto de documento é registrado, nem quando o parser falha.

Limitações conhecidas: o Tika (para PDF e detecção de tipo) e o POI (para DOCX) rodam dentro do processo da aplicação, sem isolamento. Um PDF ou DOCX malicioso que explore uma falha do parser afeta a aplicação; o upload é restrito a `EDITOR` e há limite de tamanho, mas se o upload for aberto a gente de fora vale mover a extração para um processo separado. O padrão do JWT exige que `eyJ` não venha colado a outro caractere de palavra (`(?<![\w-])eyJ...`): sem isso, uma linha só de `eyJ` repetido custava tempo quadrático (13 s com 120 KB, horas com 10 MB), um jeito barato de prender threads. É um desvio deliberado do padrão original, com teste de tempo (1 MB de linha adversária em menos de 2 s para cada padrão). O efeito colateral é não pegar um JWT colado em texto sem separador, como `xeyJ...`.

## Semeando com importação

Subir a documentação oficial arquivo por arquivo não escala. `POST /api/importar` recebe a URL de uma página raiz (a home do manual, por exemplo `https://docs.spring.io/spring-boot/reference/`), a linguagem, a versão e a profundidade, e deixa um job em segundo plano baixar as páginas, converter para Markdown e gravar no acervo. A resposta é 202 com o id do job; o andamento sai em `GET /api/importar/{id}`. O contrato está em `docs/api.md`.

O que o job faz, nessa ordem:

- Baixa só pelo mesmo `AllowlistedPageFetcher` do fallback web, então valem as mesmas travas contra SSRF: https na porta 443, host da allowlist da linguagem (`sage.web.allowlist`), DNS resolvido uma vez e endereços públicos, redirects conferidos a cada salto, tamanho e prazo limitados. A raiz que não passa nisso dá 422, sem repetir a URL na resposta. Nos domínios oficiais em que a versão faz parte do caminho (`docs.oracle.com`, `openjdk.org`, `docs.spring.io`, com as mesmas regras do fallback web), a raiz também precisa ser da versão pedida: importar `https://docs.oracle.com/en/java/javase/21/` como versão 25 dá 422, e cada página baixada passa pela mesma conferência (a que não passa conta em `ignoradas`). Sem isso, um erro de digitação gravaria o manual inteiro de uma versão com o rótulo de outra.
- Percorre em largura, só no mesmo host e dentro do prefixo de path da raiz. Raiz terminada em `/` usa o próprio caminho como prefixo; raiz cujo último trecho termina em `.html` ou `.htm` (qualquer caixa) é uma página e usa o diretório dela (`/guia/index.html` vira `/guia/`); qualquer outra raiz sem barra final é um diretório e vale ela mesma e tudo sob a barra (`/guia`, ou `/spring-boot/4.1`, onde o `.1` não faz da versão uma extensão e `/spring-boot/4.0` fica de fora). A comparação é por segmento: `/guia-extra` não entra no prefixo `/guia`. Os links vêm do HTML baixado (jsoup), são resolvidos contra a página e só seguem os que ficam em https no mesmo host e porta. Fragmento e query são descartados para deduplicar, então `a.html#x` e `a.html?v=2` são a mesma página.
- Respeita `sage.import.max-depth` (padrão 2; a raiz é a profundidade 0) e `sage.import.max-pages` (padrão 200 páginas baixadas). O pedido pode pedir menos profundidade; pedir mais que o máximo dá 422. Sem `profundidade` no corpo vale o máximo.
- Converte com o `HtmlToMarkdown` do fallback (que arranca texto escondido, script e afins) e passa o resultado no `SecretScanner`. Página com segredo detectado é ignorada e contada em `ignoradas`; o valor encontrado não é registrado em lugar nenhum.
- Grava `publico/<linguagem>/<versao>/<slug>.md` com `revisado: false`, `url_origem` e `coletado_em`. O slug sai do caminho da página relativo à raiz: sem extensão, `/` vira `-`, minúsculas e sem acento, como no upload (a raiz vira `index`). Os documentos importados entram como não revisados de propósito: ninguém leu o conteúdo antes de entrar, e a marca só muda quando um editor chama `PATCH /api/documentos`.

Documento que já existe no acervo é ignorado, não sobrescrito, e conta em `ignoradas`. A gravação usa `DocumentStore.saveIfAbsent`, que confere e grava sob o mesmo lock do id, então dois jobs simultâneos não se atropelam. (O upload sem `substituir` grava do mesmo jeito.) Não há como pedir substituição aqui: para atualizar uma versão já importada, apague os documentos e importe de novo. Duas páginas do mesmo job cujos caminhos viram o mesmo slug (`a/b.html` e `a-b.html`) são contadas à parte, em `colisoes`: a primeira entra, a segunda não. É raro em documentação oficial e fica visível.

Cada POST consome uma permissão do limite de `UPLOAD` da chave (`sage.rate-limit.uploads-per-minute`), não uma por página: o limite protege o início do job, e o custo de cada job é limitado por `max-pages` e pelo prazo de cada download. A auditoria registra `IMPORT_STARTED` antes de qualquer download (se falhar, a resposta é 500 e nenhum job nasce) e `DOCUMENT_UPLOADED` em duas fases por página, como no fallback web: `resultado=tentativa` antes de gravar e `salvo`, `existente` ou `falhou` depois. Se a auditoria ou o armazenamento falharem no meio, o job para com status `FAILED` e o que já foi gravado fica. Também termina em `FAILED` (com as contagens até ali) o job cuja página raiz não baixa ou termina, depois de um redirect, fora do escopo; o que estoura `sage.import.max-duration`; e o que ainda roda quando a aplicação para. O cancelamento só é visto entre páginas, nunca entre a auditoria e a gravação de uma página, e um erro inesperado na thread do job também resulta em `FAILED`, nunca num job preso em `RUNNING`.

Limites de execução. No máximo `sage.import.max-concurrent-jobs` (2) jobs rodam ao mesmo tempo; o POST seguinte recebe 429 com `Retry-After`, sem auditar nem criar job. Cada job tem prazo total de `sage.import.max-duration` (10 minutos), conferido entre páginas: o prazo pode passar em até o tempo de um download (`sage.web.fetch-timeout`). O registro guarda os últimos `sage.import.jobs-kept` jobs (50); quando enche, sai o mais antigo já terminado, e job em andamento nunca é descartado (se todos estão rodando, o POST recebe 429).

Limitações. Os jobs vivem na memória do processo: reiniciar a aplicação apaga a lista; numa parada limpa os jobs em andamento são cancelados (espera de até 2 s) e marcados `FAILED`, e o que já foi gravado continua. Todo `EDITOR` enxerga os jobs de todos os editores, porque o id é um UUID aleatório e o status só traz contagens, sem conteúdo nem URL. Não há renderização de JavaScript: página montada no navegador (SPA) chega vazia e é ignorada. Os links são seguidos como estão, então menus laterais enormes podem encher o limite de páginas com navegação em vez de conteúdo; nesse caso aponte a raiz para uma seção mais específica.

```yaml
sage:
  import:
    max-depth: 2
    max-pages: 200
    jobs-kept: 50
    max-concurrent-jobs: 2
    max-duration: 10m
```

## Template do Obsidian

Quem mantém a documentação no Obsidian pode copiar um destes modelos para o cofre e trocar os valores. Ficam em `src/main/resources/templates/obsidian/`:

- `documento-publico.md`: documentação de linguagem ou framework, com `versao` e `url_origem`.
- `documento-interno.md`: documentação de um sistema da empresa, sem versão.

O modelo interno:

```markdown
---
id: interno/portal-x/faturamento.md
fonte: interno
linguagem: java
secao: Faturamento
aliases: [Cobrança, "módulo de faturamento"]
coletado_em: 2026-10-01
revisado: false
---

# Faturamento do Portal X

Descreva como o sistema funciona hoje...
```

Com o `id` no frontmatter, o arquivo se basta: dá para subir uma pasta inteira do cofre em zip, sem preencher nada no formulário. Os testes carregam os dois modelos e passam pelo mesmo parser do upload, então um modelo que deixasse de ser válido quebra o build. O `.obsidian/` do cofre não precisa ser removido do zip: pastas e arquivos ocultos são ignorados.
