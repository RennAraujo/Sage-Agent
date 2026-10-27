# Fluxo de uma pergunta

Este documento acompanha uma pergunta do momento em que chega até a resposta. Cada etapa ganha uma seção conforme é implementada; hoje estão aqui a análise da pergunta e o caminho até a resposta com o modelo rápido (Haiku).

## Entendendo a pergunta

Antes de buscar qualquer coisa, o Sage tenta responder quatro perguntas sobre a pergunta: de onde vem a resposta (documentação pública, interna ou as duas), de qual linguagem ou framework se fala, de quais versões, e que tipo de pergunta é. Isso é feito por regex e por um catálogo de nomes, sem chamar modelo nenhum. Custa microssegundos, é determinístico e dá para testar com tabela.

O `QuestionAnalyzer` devolve um `QuestionAnalysis` com fontes, linguagem, versões, tipo, e dois booleanos: `mixed` (casou algo público e algo interno) e `publicLanguageMatched` (a linguagem pública foi realmente reconhecida, e não herdada ou chutada).

### De onde vêm os nomes

Há dois catálogos, ambos em `AliasCatalog`.

O público vive em `aliases-publicos.yml`: `java`, `spring-boot`, `spring-framework` e `spring-security`, cada um com os nomes pelos quais o povo os chama e uma regex de versão. Para Java, `\b(?:(?:java|jdk)\s*-?|j-?)(?:1\.)?(\d{1,2})\b` aceita "Java 25", "jdk21", "j-17" e "java 1.8" (vira 8); o `j` curto só vale colado ao número, para "opção j 2" não virar Java. Para Spring Boot, `\bspring\s*boot\s*(\d+\.\d+)`. As regexes rodam sobre a pergunta normalizada, por isso não têm acento nem `(?i)`. Adicionar uma linguagem nova é editar esse arquivo.

O interno é derivado do acervo: para cada documento INTERNAL, o campo `language` (que nesses documentos guarda o nome da aplicação) e a lista `aliases` do frontmatter. O nome `portal-x` também vira `portal x`, porque ninguém digita o hífen. O catálogo é carregado quando a aplicação sobe (`ApplicationReadyEvent`) e depois de cada `DocumentSavedEvent` e `DocumentDeletedEvent` só é reconstruído na próxima pergunta; recarregar a cada evento faria um upload de zip com 200 arquivos decifrar o acervo 200 vezes. Cada evento incrementa um contador de geração, e a fotografia carrega a geração em que a leitura começou: se um evento chega no meio da reconstrução, a fotografia publicada já nasce velha e a pergunta seguinte reconstrói, em vez de o evento se perder. Só uma reconstrução roda por vez. Se a leitura do acervo falha, continua valendo a última fotografia boa (ou um catálogo interno vazio, se nunca houve uma), só a classe da exceção vai para o log e a leitura não é tentada de novo por 5 segundos, para um acervo fora do ar não receber uma tentativa por pergunta.

A comparação ignora caixa e acento (normalização NFD e remoção das marcas) e respeita fronteira de palavra: "java" não casa dentro de "javascript". Uma linguagem também é considerada citada quando só a regex de versão casa, o que cobre "jdk21" e "j17".

### Regras de fonte

Casou alias interno: INTERNAL. Casou alias público: PUBLIC. Os dois: as duas fontes e `mixed = true`. Nenhum: `{PUBLIC, INTERNAL}` e `publicLanguageMatched = false`, porque a pergunta pode ser sobre qualquer coisa.

Linguagem e versões descrevem só o lado público. Numa pergunta apenas interna os dois ficam vazios, porque o filtro de busca só aplica linguagem e versão a trechos públicos. Numa pergunta mista eles carregam o lado público: "@Transactional no faturamento do Portal X com Spring Boot 4.1" sai com `spring-boot` e `[4.1]`.

Se várias linguagens públicas aparecem, vence a que trouxe versão; empatando, a citada primeiro.

### Versões

A regex da linguagem acha a primeira versão, e o analisador aceita as encadeadas por um conector de comparação (`e`, `ou`, `vs`, `versus`, `->`, `para`, `x`, vírgula, barra): "Spring Boot 3.5 e 4.1" dá `[3.5, 4.1]`, "diferença entre Java 17 e 21 em performance" dá `[17, 21]`. Versões repetidas são removidas preservando a ordem. Como `versions` funciona como filtro na busca, perder uma versão esconde os trechos dela; por isso o inteiro depois do conector é aceito mesmo quando vem seguido de palavra. Ficam de fora só os casos que parecem quantidade: número seguido de substantivo contável (passos, vezes, itens, minutos, dias, anos, exemplos e mais alguns), de modo que "java 17 e 3 exemplos" não vira versão 3, e anos de quatro dígitos ("java 17 e 2024"). A versão principal, a que vem colada ao nome da linguagem, nunca é descartada: "Java 21 threads virtuais" fica com 21, e "java 3 passos" fica com 3, porque entregar a versão que o usuário pediu pesa mais que adivinhar que ela era uma quantidade. A lista de substantivos é pequena e escrita à mão; "java 17 e 3 coisas" ainda gera a versão 3.

### Tipo

FLOW quando aparece "como funciona", "qual o fluxo", "passo a passo", "fluxo de" ou "explique o processo". COMPARISON quando há duas ou mais versões, ou "diferença", "migrar", "migração", "comparar", "versus", "vs", ou uma pergunta de existência por versão: "existe no", "exclusivo", "disponível no/a partir", "a partir de qual versão", "foi removido", "foi introduzido", "desde o/a/qual", "em qual versão", "qual versão". Essas últimas precisam de resposta por versão, mesmo quando a pergunta cita uma só ou nenhuma ("em qual versão do java foi introduzido o record?" sai com `versions` vazio e COMPARISON). Uma pergunta que cita uma única versão explícita sem esses termos ("traga a documentação do java 21 sobre virtual threads") continua LOOKUP. Senão, LOOKUP. FLOW é testado primeiro, então "como funciona a migração" é FLOW. Todas as regexes de tipo e de versão rodam sobre o texto normalizado (minúsculas, NFD, sem marcas), então "MIGRAÇÃO", "QUAL É O FLUXO" e entrada já decomposta casam.

### Perguntas de continuação

Se a pergunta não cita nada reconhecível, ela herda do turno anterior: "e o toList?" depois de uma pergunta sobre Java 25 continua em `java` e `[25]`. Se traz só uma versão ("e na versão 21?"), mantém a linguagem e troca a versão. Se cita um alias novo, nada é herdado: um assunto novo não deve arrastar filtros do anterior.

### Limites

- Só reconhece os nomes que estão no catálogo. "Kotlin" ou "Hibernate" caem em "nenhum casou" até entrarem no YAML.
- A heurística de versão encadeada depende de uma lista curta de substantivos contáveis; quantidades fora dela viram versão.
- Uma pergunta que cita duas linguagens ("Java com Spring Boot 4.1") fica com uma só; a outra não filtra a busca.
- O tamanho da pergunta é limitado a 2000 caracteres antes de chegar aqui, e as regex foram escritas sem repetição aninhada; um teste passa entradas adversariais de 2000 caracteres e exige resposta em menos de 2 segundos.

## Da busca à resposta: o caminho Haiku

O `AnswerService` orquestra o resto. A pergunta chega pelo `POST /api/perguntas` (contrato em [api.md](api.md)), e o controller só confere três coisas antes de abrir o stream: se o índice terminou de carregar (senão 503 com `Retry-After: 5`), se há modelo configurado (senão 503) e se a chave ainda tem cota no minuto (senão 429). Daí em diante a resposta roda numa virtual thread, com um `SseEmitter` aberto por `sage.questions.preparation-timeout` (10 s) mais o prazo total do modelo (50 s), e cada passo abaixo emite eventos conforme acontece.

### 1. Versão: qual documentação vai ser usada

Logo depois da análise, o Sage decide a versão. Se a pergunta citou uma ("records no java 21"), é essa. Se citou a linguagem mas não a versão ("como usar toList no java?"), vale a mais recente do acervo, `SearchIndex.versionsOf("java").last()`, que já vem ordenada semanticamente (`4.10` depois de `4.9`). Esse chute é dito ao usuário, pelo código e não pelo modelo, como primeira linha da resposta:

```
Considerei a versão 25, a mais recente disponível.
```

Se a pergunta é só sobre documentação pública e pediu uma versão que não está no acervo, o Sage não busca nem chama o modelo. Responde "Não tenho a documentação do Java 8 no acervo." e para. A alternativa, responder com o Java 21 que está lá, parece mais útil mas é exatamente o tipo de erro que o projeto quer evitar: a resposta sairia com cara de certa sobre a versão errada. Numa pergunta mista (pública e interna) o aviso sai do mesmo jeito, logo depois do `metadata`, mas a resposta continua: a busca fica só com as versões públicas que existem e, se nenhuma existe, só com a documentação interna. As versões faltantes são calculadas uma vez e reaproveitadas na busca e na rota. Por enquanto, numa pergunta só pública, a regra vale para qualquer versão faltante, mesmo numa comparação em que a outra versão existe; a Task 12 troca isso por uma resposta versão a versão.

### 2. Busca e rota

A busca usa a versão resolvida no filtro (`SearchFilter` com fontes, linguagem e versões; versão e linguagem só restringem trechos públicos). O `Retriever` devolve até 5 trechos em ordem de score, e o `RouteDecider` escolhe entre duas rotas:

- `NOT_DOCUMENTED` se não veio trecho ou se o melhor ficou abaixo do limiar. A resposta é o texto fixo "Não encontrei isso na documentação disponível.", sem gastar um token.
- `FAST_MODEL` nos outros casos.

O limiar depende de o reranker estar ligado. Com ele, o score é a nota do cross-encoder e o corte é `sage.retrieval.min-score` (0.35, a calibrar na Task 20 com perguntas reais). Sem ele, o score vira cobertura lexical, outra escala, e o corte é `sage.retrieval.min-score-without-reranker` (0.0 por padrão, ou seja, qualquer trecho passa). Como o reranker existe ou não desde o boot, o limiar é fixado quando o `RouteDecider` é criado.

### 3. Metadata e as linhas de versão

Antes de qualquer evento, o Sage grava `QUESTION_ASKED` na auditoria (detalhes na seção 5); se a gravação falhar, a pergunta para ali. Depois sai o evento `metadata`, com as citações já numeradas: o trecho `[1]` do prompt é a citação 1, e cada citação carrega documento, seção, linguagem e versão. É assim que a interface consegue mostrar "[1] Java 21 · Stream API" ao lado do texto.

A versão usada também aparece no próprio texto, numa linha emitida pelo código. Ela sai imediatamente antes da primeira frase aprovada que cita um trecho daquela linguagem e versão, e só para fontes de fato citadas: um trecho que foi ao modelo mas não sustentou nenhuma frase não aparece. Se nada for aprovado, não sai linha de versão nenhuma. Exemplos:

```
Documentação consultada: Java 21
Documentação consultada: Spring Boot 4.1
Documentação consultada: portal-x (interna)
```

O nome legível vem do campo `nome` de `aliases-publicos.yml` ("Java", "Spring Boot"); documento interno aparece com o nome da aplicação e "(interna)". Isso é requisito do projeto: quem lê a resposta precisa saber, sem abrir as citações, de qual versão ela saiu. Deixar o modelo dizer isso seria pedir para ele acertar um fato que o código já sabe com certeza.

### 4. Prompt e stream do modelo

O `PromptBuilder` monta o prompt com um texto de sistema fixo e uma mensagem de usuário que contém só os trechos e a pergunta:

```
<documento n="1" fonte="publico/java/21/stream-api.md" secao="Stream > Terminal operations">
...texto do trecho...
</documento>
<pergunta>como usar toList no java 21?</pergunta>
```

O modelo é o FAST (`sage.models.fast-id`, Haiku no Bedrock), com temperatura 0 e no máximo 1500 tokens de saída. A chamada usa o `ChatModel` do Bedrock Converse do Spring AI em streaming, com o model id escolhido por faixa a cada chamada. Os cuidados contra injeção no prompt estão em [seguranca.md](seguranca.md#contra-delírio).

Cada pedaço de texto que chega passa pelo `AnswerGuard`, que junta os pedaços até fechar uma frase e só então decide se ela sai. A frase aprovada vira um evento `text`; a reprovada some e só aumenta a contagem de `frasesDescartadas`. Bloco de código sai inteiro, como um único `text`, se vier logo depois de uma frase aprovada, se estiver fechado e se não for prosa disfarçada; cada bloco consome a frase que o justificou. As regras completas estão em [seguranca.md](seguranca.md#contra-delírio). Se nada passar, a resposta é "A documentação disponível não cobre isso.". O modelo tem prazo total de `sage.models.timeout` (50 s), do pedido ao último token; estourou, o stream do modelo é cancelado e o cliente recebe um evento `error`. Se o cliente fechar a conexão, o emitter avisa o serviço e a chamada ao Bedrock é cancelada na hora.

Perguntas de comparação passam por este mesmo caminho por enquanto, com uma linha a mais no prompt pedindo a resposta versão a versão. A busca separada por versão é da Task 12, e o tratamento de FLOW (diagramas) é da Task 13. O prompt já tem o campo `allowEscalation`, mas aqui ele é sempre falso: o pedido de escalonamento para o modelo forte entra na Task 12.

### 5. Done, auditoria e log

A auditoria tem dois eventos por pergunta, ambos cifrados e sem o texto da pergunta ou da resposta. `QUESTION_ASKED` (desfecho `started`) é gravado antes do primeiro evento SSE, com o ator, o id da conversa, a rota, o modelo e os ids dos documentos enviados ao modelo; se falhar, nada é transmitido e o cliente recebe só um `error`. É o fail-closed: não existe resposta sem rastro. `QUESTION_ANSWERED` é gravado em qualquer desfecho, com os ids dos documentos citados nas frases aprovadas até ali e o número de frases descartadas. O desfecho é `completed` (resposta inteira), `interrupted` (o stream do modelo começou e foi cortado por erro, prazo ou cliente que saiu, ou o cliente saiu antes) ou `failed` (erro antes de o modelo começar). Na resposta completa ele é gravado antes do `done`, que carrega o mesmo formato do `metadata` com os números finais (frases descartadas e tempos `analise`, `busca`, `modelo`, `total`); se essa gravação falhar, o cliente recebe `error` em vez de `done`.

O log de aplicação tem uma linha por pergunta com rota, modelo, score do melhor trecho, número de trechos, frases descartadas, tempos e o `logRef` (hash curto) de cada documento usado. Ids de documento não vão para o log porque carregam nomes de aplicação e caminhos.

### Limites deste caminho

- O guard só sabe se a frase cita um trecho que existe, não se o trecho de fato sustenta o que a frase diz. Uma frase com `[1]` que distorce o trecho 1 passa.
- Uma frase termina em quebra de linha ou em ponto, exclamação ou interrogação seguidos de espaço e de algo que começa frase (maiúscula, dígito, crase, colchete). Isso mantém "e.g. toList()", "p. ex. collect()" e "aprox. dez" inteiros, mas "aprox. 3 ms" ainda parte a frase (dígito), e uma frase seguinte que comece em minúscula gruda na anterior. Título Markdown sem citação é descartado.
- Cada `text` é uma frase solta: a estrutura de parágrafos e listas da resposta do modelo se perde, e a interface remonta o texto como achar melhor.
- O `conversaId` ainda não carrega contexto entre perguntas; uma pergunta de continuação ("e no 17?") é analisada sozinha.
