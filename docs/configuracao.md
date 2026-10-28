# Configuração

Toda propriedade `sage.*` é lida por um record `@ConfigurationProperties` validado, um por funcionalidade. Se um valor for inválido (um limiar fora da faixa, um prazo zero, `provider=kms` sem `kms-key-id`), a aplicação não sobe, e a mensagem diz qual propriedade, sem repetir o valor quando ele pode ser segredo. Esta página foi gerada a partir desses records e do `src/main/resources/application.yml`, não escrita de memória: a coluna "Padrão" é o valor que vale com o `application.yml` do repositório.

## Como configurar

Os valores vêm, em ordem crescente de prioridade, do `application.yml` embutido no jar, de um `application.yml` ao lado do jar ou em `config/`, e de variáveis de ambiente. Qualquer propriedade vira variável de ambiente trocando `.` e `-` por `_` e pondo em maiúsculas: `sage.rate-limit.questions-per-minute` é `SAGE_RATE_LIMIT_QUESTIONS_PER_MINUTE`. Seis têm um nome curto já ligado no `application.yml` (`SAGE_KMS_KEY_ID`, `SAGE_S3_BUCKET`, `SAGE_S3_KMS_KEY_ID`, `SAGE_MODEL_FAST_ID`, `SAGE_MODEL_STRONG_ID`, `SAGE_BRAVE_API_KEY`), e as chaves de API usam o índice da lista (`SAGE_AUTH_KEYS_0_NAME`, `SAGE_AUTH_KEYS_0_ROLE`, `SAGE_AUTH_KEYS_0_SHA256`, depois `_1_`...). Durações aceitam `500ms`, `50s`, `20s`, `6h`, `30m`; tamanhos aceitam `10MB`, `2MB`.

Há uma armadilha nos valores calibrados. Três propriedades têm no `application.yml` um valor diferente do padrão do record Java: `sage.retrieval.candidates` (8 no yml, 12 no record), `sage.retrieval.min-score` (0.10 contra 0.35) e `sage.rerank.max-tokens` (128 contra 256). Com o `application.yml` do repositório valem os da tabela abaixo. Quem substituir o arquivo inteiro (por exemplo com `--spring.config.location`) e não repetir essas chaves volta aos padrões do record, que são os do começo do projeto, antes da calibração.

Fora do prefixo `sage.`, o `application.yml` liga duas coisas que importam: `spring.ai.model.chat` (variável `SAGE_CHAT_MODEL`, padrão `bedrock-converse`; com `none` o Sage sobe sem modelo de linguagem e `/api/perguntas` responde 503) e a região do Bedrock, `spring.ai.bedrock.aws.region` (variável `AWS_REGION`, padrão `us-east-1`). O cliente do S3 e o do KMS leem a região e as credenciais da cadeia padrão da AWS, não de propriedades do Sage. A imagem Docker define `SAGE_MODELS_DIR=/app/models` e `SAGE_GUARD_REQUIRE_NLI=true`. A porta é `server.port` (8080), e o único endpoint do Actuator exposto é `health`.

## Autenticação e limites

As chaves de API são descritas em [api.md](api.md#autenticação-e-papéis).

| Propriedade | Variável de ambiente | Padrão | Efeito |
| --- | --- | --- | --- |
| `sage.auth.keys` | `SAGE_AUTH_KEYS_0_NAME, _ROLE, _SHA256` | `[]` (nenhuma) | Lista de chaves de API: `name`, `role` (`READER` ou `EDITOR`) e `sha256` (hash da chave, 64 hexadecimais). Sem nenhuma, a aplicação sobe e todo `/api/**` responde 401. Nome e hash não podem se repetir; hash malformado impede a subida sem repetir o valor na mensagem. Gere com `scripts/new-api-key`. Uma chave nova ou removida só vale depois de reiniciar. |
| `sage.rate-limit.questions-per-minute` | `SAGE_RATE_LIMIT_QUESTIONS_PER_MINUTE` | `20` | Perguntas por minuto, por chave. Cada pergunta custa uma chamada ao Bedrock, então este é o freio de gasto. Vale por instância: com N instâncias o limite real é N vezes isso. |
| `sage.rate-limit.uploads-per-minute` | `SAGE_RATE_LIMIT_UPLOADS_PER_MINUTE` | `10` | Envios de documento (e inícios de importação) por minuto, por chave. Cada envio custa embeddings e cifragem. |

## Criptografia e armazenamento

Detalhes de formato e de operação em [seguranca.md](seguranca.md#criptografia-em-envelope) e [acervo-e-upload.md](acervo-e-upload.md#como-o-documento-é-guardado).

| Propriedade | Variável de ambiente | Padrão | Efeito |
| --- | --- | --- | --- |
| `sage.crypto.provider` | `SAGE_CRYPTO_PROVIDER` | `local` | `local`: chave mestra em arquivo, só para desenvolvimento. `kms`: AWS KMS, para qualquer ambiente com dados reais. Trocar não migra os blobs existentes: eles só abrem com o provider que os criou. |
| `sage.crypto.local-key-file` | `SAGE_CRYPTO_LOCAL_KEY_FILE` | `secrets/local-master.key` | Caminho do arquivo com a chave mestra local (32 bytes em base64). Gere com `scripts/generate-local-key`. Arquivo ausente ou malformado impede a subida. Perder o arquivo torna tudo ilegível. |
| `sage.crypto.kms-key-id` | `SAGE_KMS_KEY_ID` | vazio | Id, ARN ou alias da chave KMS. Obrigatório com `provider=kms`. Na subida o Sage chama `kms:DescribeKey` nela e falha se não conseguir, em vez de descobrir no primeiro upload. |
| `sage.storage.type` | `SAGE_STORAGE_TYPE` | `local` | `local`: blobs cifrados em arquivos sob `local-dir`. `s3`: bucket em `s3-bucket`. Trocar não migra o acervo. |
| `sage.storage.local-dir` | `SAGE_STORAGE_LOCAL_DIR` | `data` | Diretório dos blobs com `type=local`. No Docker é `/app/data`, montado de `./data`; no Linux o diretório precisa ser gravável pelo uid 10001. |
| `sage.storage.s3-bucket` | `SAGE_S3_BUCKET` | vazio | Nome do bucket com `type=s3`; obrigatório nesse caso. O bucket precisa existir. Cada escrita pede SSE-KMS. Região e credenciais vêm da cadeia padrão da AWS. |
| `sage.storage.s3-kms-key-id` | `SAGE_S3_KMS_KEY_ID` | vazio | Id, ARN ou alias da chave KMS que o S3 usa no SSE-KMS de cada objeto (vai como `SSEKMSKeyId` no `PutObject`). Vazio deixa a escolha com a criptografia padrão do bucket. Em produção, aponte para a chave gerenciada pelo cliente e confira com `aws s3api head-object` (detalhes em [deploy-aws.md](deploy-aws.md#bucket-s3)). A role precisa de `kms:GenerateDataKey` e `kms:Decrypt` nessa chave. |

## Upload

O que é aceito e recusado está em [acervo-e-upload.md](acervo-e-upload.md#o-que-é-recusado-e-por-quê).

| Propriedade | Variável de ambiente | Padrão | Efeito |
| --- | --- | --- | --- |
| `sage.upload.max-file-bytes` | `SAGE_UPLOAD_MAX_FILE_BYTES` | `10MB` | Tamanho máximo de um arquivo enviado, e também do Markdown depois da conversão (um PDF pequeno pode virar texto grande). Acima disso: 413. |
| `sage.upload.max-zip-bytes` | `SAGE_UPLOAD_MAX_ZIP_BYTES` | `50MB` | Tamanho máximo do zip em si. O teto do multipart do Spring é derivado deste valor, e o multipart fica em memória, nunca em disco. |
| `sage.upload.max-zip-entries` | `SAGE_UPLOAD_MAX_ZIP_ENTRIES` | `500` | Número máximo de entradas num zip. Protege contra zip com milhões de arquivos minúsculos. |
| `sage.upload.max-zip-uncompressed-bytes` | `SAGE_UPLOAD_MAX_ZIP_UNCOMPRESSED_BYTES` | `200MB` | Total máximo descompactado, contado enquanto se lê (o tamanho declarado no cabeçalho não é confiável). Protege contra zip bomb. Lembre que o zip inteiro é lido em memória: este valor é também um teto de uso de heap por envio. |

## Busca e relevância

Os números por trás de cada valor estão em [decisoes.md](decisoes.md#calibração-com-as-perguntas-gabarito); os quatro marcados como provisórios foram calibrados num acervo de avaliação pequeno.

| Propriedade | Variável de ambiente | Padrão | Efeito |
| --- | --- | --- | --- |
| `sage.models.dir` | `SAGE_MODELS_DIR` | `models` | Diretório com os arquivos ONNX (embeddings, reranker e NLI). Baixe com `scripts/download-models`. Arquivo ausente desliga a funcionalidade correspondente: sem embeddings a busca é só BM25; sem reranker o score vira cobertura lexical; sem NLI o guard confere só citações e segredos (a menos que `sage.guard.require-nli=true`). No Docker, `SAGE_MODELS_DIR=/app/models`. |
| `sage.retrieval.max-chunk-chars` | `SAGE_RETRIEVAL_MAX_CHUNK_CHARS` | `1500` | Teto de caracteres de um trecho do índice (um bloco de código único pode passar). Trechos maiores dão mais contexto por trecho e custam mais no reranker e no prompt; mudar exige reindexar, o que acontece a cada subida. |
| `sage.retrieval.search-depth` | `SAGE_RETRIEVAL_SEARCH_DEPTH` | `30` | Profundidade de cada lista da primeira etapa (BM25 e vetores) antes da fusão por RRF. Barata (alguns ms), então fica funda. Aumentar melhora a recuperação em acervo grande quase sem custo de latência. Provisório. |
| `sage.retrieval.candidates` | `SAGE_RETRIEVAL_CANDIDATES` | `8` | Quantos trechos já fundidos seguem para o reranker. O custo do reranker é linear nisto: 8 mantém busca mais reranker em ~130 ms; 12 passa de 200 ms. Provisório. O padrão do record, se o `application.yml` for substituído, é 12. |
| `sage.retrieval.context-chunks` | `SAGE_RETRIEVAL_CONTEXT_CHUNKS` | `5` | Quantos trechos, depois do reranker, vão ao prompt do modelo. Mais trechos custam tokens de entrada e latência do NLI (que olha os trechos citados). |
| `sage.retrieval.comparison-max-chunks` | `SAGE_RETRIEVAL_COMPARISON_MAX_CHUNKS` | `8` | Teto de trechos somados numa comparação com busca por versão, dividido entre as versões (e a interna). Mínimo 4: cada grupo tem ao menos 2 trechos, então cabem `teto / 2` grupos. |
| `sage.retrieval.min-score` | `SAGE_RETRIEVAL_MIN_SCORE` | `0.10` | Score mínimo do melhor trecho (nota do reranker, de 0 a 1) para a pergunta ir ao modelo. Abaixo disso: "Não encontrei isso na documentação disponível.", sem gastar token. Subir recusa mais; descer deixa mais perguntas chegarem ao modelo com trecho fraco. Também filtra os grupos de uma comparação e decide o fallback web. Calibrado com 106 perguntas; refaça no acervo real. O padrão do record é 0.35. |
| `sage.retrieval.min-score-without-reranker` | `SAGE_RETRIEVAL_MIN_SCORE_WITHOUT_RERANKER` | `0.0` | O mesmo limiar, usado só quando os arquivos do reranker não existem (o score vira cobertura lexical, outra escala). 0.0: qualquer candidato passa. |
| `sage.rerank.max-tokens` | `SAGE_RERANK_MAX_TOKENS` | `128` | Teto de tokens do par (pergunta + trecho) no reranker; o custo é linear nele e o modelo aceita até 512. 128 mantém a meta de latência e vê só uns 100 tokens de um trecho de 1500 caracteres; 256 dobra o custo do reranker. Provisório, pendente de decisão do dono do projeto. O padrão do record é 256. |

## Modelo de linguagem e prazos

A autenticação com a AWS não tem propriedade `sage.*`: vem da cadeia padrão (variáveis de ambiente, perfil, role da task).

| Propriedade | Variável de ambiente | Padrão | Efeito |
| --- | --- | --- | --- |
| `sage.models.fast-id` | `SAGE_MODEL_FAST_ID` | vazio | Model id ou inference profile do Bedrock para o modelo rápido (Haiku). Sem padrão. Sem este e o `strong-id`, `/api/perguntas` responde 503 "modelo não configurado" e o resto da aplicação funciona. |
| `sage.models.strong-id` | `SAGE_MODEL_STRONG_ID` | vazio | Model id ou inference profile do modelo forte (Sonnet), usado em comparações, perguntas de fluxo e escaladas. Sem padrão. |
| `sage.models.max-tokens` | `SAGE_MODELS_MAX_TOKENS` | `1500` | Tokens máximos de saída por chamada. Resposta que bate no teto é cortada no meio, e o guard descarta a frase incompleta. |
| `sage.models.temperature` | `SAGE_MODELS_TEMPERATURE` | `0` | Temperatura do modelo (0 a 1). 0 faz a mesma pergunta com o mesmo contexto dar a mesma resposta. Subir aumenta a variação e o risco de frase sem base. |
| `sage.models.timeout` | `SAGE_MODELS_TIMEOUT` | `50s` | Prazo total de uma resposta do modelo, do pedido ao último token (não por token); vale para as duas chamadas somadas quando há escalada. Estourou: o stream é cancelado e o cliente recebe `error`. |
| `sage.questions.preparation-timeout` | `SAGE_QUESTIONS_PREPARATION_TIMEOUT` | `20s` | Orçamento de análise, busca, fallback web e auditoria antes do modelo. O SSE fica aberto por este valor mais `sage.models.timeout`. Precisa passar `sage.web.fallback-timeout` em pelo menos 5 s, senão a aplicação não sobe. |
| `sage.questions.index-retry-after` | `SAGE_QUESTIONS_INDEX_RETRY_AFTER` | `5s` | Valor do `Retry-After` do 503 devolvido enquanto o índice ainda carrega. |

## Verificação de fundamentação

O funcionamento está em [seguranca.md](seguranca.md#verificação-de-fundamentação).

| Propriedade | Variável de ambiente | Padrão | Efeito |
| --- | --- | --- | --- |
| `sage.guard.min-entailment` | `SAGE_GUARD_MIN_ENTAILMENT` | `0.5` | Probabilidade mínima de entailment (NLI) para uma frase contar como sustentada pelo trecho citado. De 0,4 a 0,7 o resultado na avaliação é o mesmo; abaixo de 0,4 a rejeição de frases erradas cai (92,3% em 0,3, 84,6% em 0,1). |
| `sage.guard.nli-max-tokens` | `SAGE_GUARD_NLI_MAX_TOKENS` | `256` | Teto de tokens do par (trecho + frase) no NLI, de 64 a 512. O custo é linear nele; reduzir acelera e faz o modelo olhar menos de cada trecho. |
| `sage.guard.require-nli` | `SAGE_GUARD_REQUIRE_NLI` | `false` | `true`: sem os arquivos do NLI a aplicação não sobe. `false`: o guard degrada para só citações e segredos, com um aviso na subida. A imagem Docker liga (`SAGE_GUARD_REQUIRE_NLI=true`); em produção deixe ligado. |
| `sage.guard.nli-timeout-per-sentence` | `SAGE_GUARD_NLI_TIMEOUT_PER_SENTENCE` | `1500ms` | Prazo da verificação de uma frase. Estourou: a frase é descartada (falha fechada). Numa CPU lenta, frases que citam muitos trechos grandes podem cair por tempo, não por mérito. |
| `sage.guard.nli-max-concurrency` | `SAGE_GUARD_NLI_MAX_CONCURRENCY` | `0` | Inferências do NLI em curso ao mesmo tempo, contando as que estouraram o prazo e ainda não terminaram. 0 = metade dos núcleos, ao menos 1. Sem permissão dentro do prazo, a frase é descartada. |

## Cache, conversa, fluxo e lacunas

Cache e conversa vivem na memória do processo. Veja [fluxo-de-uma-pergunta.md](fluxo-de-uma-pergunta.md#cache-e-conversa).

| Propriedade | Variável de ambiente | Padrão | Efeito |
| --- | --- | --- | --- |
| `sage.cache.ttl` | `SAGE_CACHE_TTL` | `6h` | Validade de uma resposta no cache. Qualquer documento salvo ou apagado esvazia o cache inteiro antes disso. Só em memória, nunca em disco. |
| `sage.cache.max-entries` | `SAGE_CACHE_MAX_ENTRIES` | `1000` | Teto de respostas guardadas; passando dele o Caffeine descarta as menos usadas. |
| `sage.conversation.ttl` | `SAGE_CONVERSATION_TTL` | `30m` | Validade de uma conversa, contada da última pergunta. Passou, "e no 17?" é analisada sozinha. |
| `sage.conversation.max-turns` | `SAGE_CONVERSATION_MAX_TURNS` | `5` | Quantas análises recentes ficam por conversa. |
| `sage.conversation.max-conversations` | `SAGE_CONVERSATION_MAX_CONVERSATIONS` | `10000` | Teto de conversas ao mesmo tempo; quem abre conversas sem parar expulsa as mais antigas em vez de encher a memória. |
| `sage.flow.max-doc-chars` | `SAGE_FLOW_MAX_DOC_CHARS` | `12000` | Em pergunta de fluxo, documento interno até este tamanho entra inteiro; maior que isso, entra a seção de nível 2 do melhor trecho. Também é o teto de cada documento ligado por wiki-link. |
| `sage.flow.max-linked-docs` | `SAGE_FLOW_MAX_LINKED_DOCS` | `3` | Quantos documentos apontados por `[[wiki-link]]` (profundidade 1, mesma aplicação) entram. 0 desliga a expansão por links. |
| `sage.flow.max-context-chars` | `SAGE_FLOW_MAX_CONTEXT_CHARS` | `24000` | Teto do texto somado de todo o contexto do modelo numa pergunta de fluxo; o que não cabe é cortado entre trechos. Aumentar dá mais contexto ao Sonnet e encarece o prompt. |
| `sage.gaps.max-entries` | `SAGE_GAPS_MAX_ENTRIES` | `5000` | Teto de ocorrências de lacuna guardadas (um blob cifrado por ocorrência); as mais antigas são apagadas na gravação. `vezes` em `/api/lacunas` conta só o que cabe aqui. |

## Fallback web e importação

Segurança e comportamento em [fallback-web.md](fallback-web.md) e [acervo-e-upload.md](acervo-e-upload.md#semeando-com-importação).

| Propriedade | Variável de ambiente | Padrão | Efeito |
| --- | --- | --- | --- |
| `sage.web.allowlist` | `(só pelo arquivo)` | ver efeito | Mapa de id de linguagem (o de `aliases-publicos.yml`) para domínios aceitos (o domínio e seus subdomínios, sempre por https na porta 443). Linguagem fora do mapa não tem fallback nem importação. Não pode ficar vazio, e cada domínio precisa ser um nome válido com ponto. Padrão: `java` (`docs.oracle.com`, `openjdk.org`) e `spring-boot`, `spring-framework`, `spring-security` (`docs.spring.io`). Configure pelo arquivo; variável de ambiente não é prática para mapa de listas. |
| `sage.web.brave-api-key` | `SAGE_BRAVE_API_KEY` | vazio | Chave do plano gratuito do Brave Search. Sem ela o fallback fica desligado (aviso na subida) e a importação por URL continua funcionando. Nunca aparece em log nem em `toString`. |
| `sage.web.brave-endpoint` | `SAGE_WEB_BRAVE_ENDPOINT` | `https://api.search.brave.com/res/v1/web/search` | Endpoint da API do Brave. Precisa ser https. Só muda para testes ou para um proxy. |
| `sage.web.max-pages-per-question` | `SAGE_WEB_MAX_PAGES_PER_QUESTION` | `3` | Teto de páginas que o fallback baixa por pergunta, dividido entre as versões ausentes. |
| `sage.web.fetch-timeout` | `SAGE_WEB_FETCH_TIMEOUT` | `5s` | Prazo de cada download (conexão, handshake, cabeçalhos e corpo juntos). A resolução de DNS não é coberta por ele. |
| `sage.web.max-page-bytes` | `SAGE_WEB_MAX_PAGE_BYTES` | `2MB` | Tamanho máximo do corpo de uma página baixada. Acima disso o download é abortado. |
| `sage.web.max-redirects` | `SAGE_WEB_MAX_REDIRECTS` | `3` | Redirects seguidos à mão, cada um revalidado do zero (esquema, porta, host na allowlist, IP público). |
| `sage.web.fallback-timeout` | `SAGE_WEB_FALLBACK_TIMEOUT` | `8s` | Prazo do fallback inteiro de uma pergunta (busca no Brave e até 3 páginas, todas as versões somadas). Estourou, valem as páginas já gravadas. Precisa ser menor que `sage.questions.preparation-timeout` menos 5 s. |
| `sage.import.max-depth` | `SAGE_IMPORT_MAX_DEPTH` | `2` | Cliques a partir da página raiz numa importação (0 = só a raiz). O pedido pode pedir menos, nunca mais. |
| `sage.import.max-pages` | `SAGE_IMPORT_MAX_PAGES` | `200` | Teto de páginas baixadas por importação. |
| `sage.import.jobs-kept` | `SAGE_IMPORT_JOBS_KEPT` | `50` | Quantas importações ficam consultáveis em memória; sai o mais antigo já terminado, e job em andamento nunca sai. |
| `sage.import.max-concurrent-jobs` | `SAGE_IMPORT_MAX_CONCURRENT_JOBS` | `2` | Importações simultâneas; a seguinte recebe 429. |
| `sage.import.max-duration` | `SAGE_IMPORT_MAX_DURATION` | `10m` | Prazo total de um job; estourou, o job termina como `FAILED` com o que já entrou. |

## Propriedades só de teste

`sage.latency.strict` é uma propriedade de sistema da JVM que a aplicação não lê: os testes de latência a leem para decidir se cobram os orçamentos de relógio de parede. O perfil `-Peval` a liga (`.\mvnw.cmd verify -Peval`); sem ele os orçamentos viram um teto de sanidade de 10 s. `SAGE_BEDROCK_IT=true` é uma variável do shell que habilita os testes que chamam o Bedrock real e não deve ir para o `.env`.
