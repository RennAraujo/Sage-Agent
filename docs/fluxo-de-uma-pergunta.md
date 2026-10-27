# Fluxo de uma pergunta

Este documento acompanha uma pergunta do momento em que chega até a resposta. Cada etapa ganha uma seção conforme é implementada; hoje estão aqui a análise da pergunta, o caminho até a resposta com o modelo rápido (Haiku) e os dois jeitos de chegar ao modelo forte (Sonnet): a escalada pedida pelo próprio Haiku e a comparação entre versões.

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

O `AnswerService` orquestra o resto. A pergunta chega pelo `POST /api/perguntas` (contrato em [api.md](api.md)), e o controller só confere três coisas antes de abrir o stream: se o índice terminou de carregar (senão 503 com `Retry-After: 5`), se há modelo configurado (senão 503) e se a chave ainda tem cota no minuto (senão 429). Daí em diante a resposta roda numa virtual thread, com um `SseEmitter` aberto por `sage.questions.preparation-timeout` (20 s) mais o prazo total do modelo (50 s), e cada passo abaixo emite eventos conforme acontece.

### 1. Versão: qual documentação vai ser usada

Logo depois da análise, o Sage decide a versão. Se a pergunta citou uma ("records no java 21"), é essa. Se citou a linguagem mas não a versão ("como usar toList no java?"), vale a mais recente do acervo, `SearchIndex.versionsOf("java").last()`, que já vem ordenada semanticamente (`4.10` depois de `4.9`). Esse chute é dito ao usuário, pelo código e não pelo modelo, como primeira linha da resposta:

```
Considerei a versão 25, a mais recente disponível.
```

Se a pergunta é só sobre documentação pública e pediu uma versão que não está no acervo, o Sage não chama o modelo (e só busca na web se o fallback estiver configurado; veja [Fallback web](#fallback-web)). Responde "Não tenho a documentação do Java 8 no acervo." e para. A alternativa, responder com o Java 21 que está lá, parece mais útil mas é exatamente o tipo de erro que o projeto quer evitar: a resposta sairia com cara de certa sobre a versão errada. Numa pergunta mista (pública e interna) o aviso sai do mesmo jeito, logo depois do `metadata`, mas a resposta continua: a busca fica só com as versões públicas que existem e, se nenhuma existe, só com a documentação interna. As versões faltantes são calculadas uma vez e reaproveitadas na busca e na rota. A exceção é a comparação entre versões: "isso existe no java 25 ou é exclusivo do java 8?" com o 8 fora do acervo diz que não tem o 8 e responde sobre o 25 (detalhes em [Comparação entre versões](#comparação-entre-versões)). Só quando nenhuma das versões pedidas existe a pergunta para ali.

Comparação não ganha versão inferida. "Em qual versão do java foi introduzido o record?" precisa olhar todas as versões, e filtrar pela mais recente responderia outra pergunta. Pelo mesmo motivo, uma comparação que cita uma versão só ("isso existe no java 25?") busca em todas as versões da linguagem: a resposta pode estar na nota de uma versão anterior.

### 2. Busca e rota

A busca usa a versão resolvida no filtro (`SearchFilter` com fontes, linguagem e versões; versão e linguagem só restringem trechos públicos). O `Retriever` devolve até 5 trechos em ordem de score, e o `RouteDecider` escolhe entre três rotas:

- `NOT_DOCUMENTED` se não veio trecho ou se o melhor ficou abaixo do limiar. A resposta é o texto fixo "Não encontrei isso na documentação disponível.", sem gastar um token.
- `STRONG_MODEL` se a pergunta é COMPARISON. Comparar versões é justamente "análise entre várias fontes", o caso em que o Haiku escalaria de qualquer jeito; mandar direto economiza a primeira chamada.
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

Cada pedaço de texto que chega passa pelo `AnswerGuard`, que junta os pedaços até fechar uma frase e só então decide se ela sai. A frase aprovada vira um evento `text`; a reprovada some e só aumenta a contagem de `frasesDescartadas`. Depois das regras de citação, a frase ainda passa pela verificação de fundamentação: um modelo de NLI confere a frase (sem os marcadores) contra só os trechos que ela cita, e a que eles não sustentam, ou que estoura o prazo de `sage.guard.nli-timeout-per-sentence`, é descartada e contada. Todo segmento que seria mostrado, frase ou bloco, passa também pelo filtro de segredos; o que tem credencial vira "[conteúdo removido: possível segredo]" e entra em `segredosRemovidos`. Blocos de código e os textos fixos do código não passam pelo NLI. A verificação roda fora da thread do SDK do modelo e conta contra o prazo total de `sage.models.timeout` (detalhes em [seguranca.md](seguranca.md#verificação-de-fundamentação)). Bloco de código sai inteiro, como um único `text`, se vier logo depois de uma frase aprovada, se estiver fechado e se não for prosa disfarçada; cada bloco consome a frase que o justificou. As regras completas estão em [seguranca.md](seguranca.md#contra-delírio). Se nada passar, a resposta é "A documentação disponível não cobre isso.". O modelo tem prazo total de `sage.models.timeout` (50 s), do pedido ao último token; estourou, o stream do modelo é cancelado e o cliente recebe um evento `error`. Se o cliente fechar a conexão, o emitter avisa o serviço e a chamada ao Bedrock é cancelada na hora.

No caminho FAST o prompt de sistema ganha uma linha a mais, "Se os documentos não bastarem ou a pergunta exigir análise entre várias fontes, chame `escalate` antes de escrever qualquer texto.", e a chamada declara a ferramenta `escalate`. O que acontece quando ele a chama está na seção seguinte. O tratamento de FLOW (diagramas) é da Task 13.

### 4a. Escalada para o Sonnet

O Haiku resolve a maioria das perguntas, mas às vezes os trechos não bastam ou a pergunta pede cruzar várias fontes. Quem melhor sabe disso é o próprio Haiku, lendo os trechos; por isso a decisão é dele, pela ferramenta `escalate(reason)` (o porquê está em [decisoes.md](decisoes.md#escalada-pelo-próprio-haiku)).

A ferramenta só é declarada quando o prompt permite escalada, ou seja, só no FAST. Ela nunca executa: o `BedrockLanguageModel` transforma a chamada num `ModelEvent.EscalationRequested` e quem decide é o `AnswerService`. O Spring AI 2.0 não executa ferramentas dentro do `ChatModel` (isso ficou com o `ChatClient`, que o Sage não usa), então não há `internalToolExecutionEnabled(false)` para ligar: chamar o `ChatModel` direto já é o modo desligado. O `call` da ferramenta lança exceção, para o caso de alguém um dia plugá-la num `ChatClient`.

A regra é uma só: escalada vale se chegar antes de qualquer saída aprovada do Haiku. Nesse caso o stream do Haiku é cancelado (o Bedrock para de gerar) e o Sonnet (`sage.models.strong-id`) recebe o mesmo contexto, com o prompt sem a linha de `escalate` e sem a ferramenta. Pedido que chega depois de uma frase aprovada é ignorado, com um aviso no log que diz só o modelo, sem o motivo (o motivo é texto do modelo). Ignorar é o menos ruim: a essa altura já há frases na tela do usuário, e trocar de modelo no meio deixaria uma resposta com duas vozes e citações que não se conversam. O que conta é a saída aprovada, não o texto bruto: o Bedrock entrega o bloco de texto antes da chamada da ferramenta, e um preâmbulo sem citação como "Vou passar para um modelo mais forte." é descartado pelo guard. Se ele bloqueasse a escalada, o usuário receberia "A documentação disponível não cobre isso." sem que o Sonnet fosse chamado.

Para o `metadata` dizer a verdade, no FAST ele não sai na hora: o `metadata` e os avisos fixos ("Considerei a versão 25...") ficam guardados até a primeira saída aprovada, a escalada ou o fim do stream. Se vier a escalada, saem com `rota=STRONG_MODEL` e `modelo=STRONG`; se vier frase aprovada, com `FAST_MODEL` e `FAST`. O custo é o `metadata` chegar junto da primeira frase completa do Haiku, e não antes da chamada. Se o modelo falhar antes disso, o `metadata` sai mesmo assim, antes do `error`, para o cliente ter o `conversaId`. O prazo total `sage.models.timeout` vale para as duas chamadas somadas, contado desde a primeira: uma escalada não ganha 50 s novos.

Um teste com `ChatModel` falso confere a tradução da ferramenta (chamada de `escalate` vira `EscalationRequested`, ferramenta com outro nome é ignorada, argumento que não é JSON vira motivo vazio) e que a ferramenta só vai no pedido do FAST. Os testes do `AnswerService` conferem a troca (Haiku cancelado, Sonnet chamado, `metadata` e `done` com STRONG, auditoria com `escalated=true`) e o pedido tardio ignorado. As frases que o guard do Haiku já tinha descartado antes da escalada entram na conta de `frasesDescartadas`, somadas às do Sonnet.

### Comparação entre versões

"Isso existe no java 25 ou é exclusivo do java 8?" e "diferença entre Spring Boot 3.5 e 4.1" são COMPARISON com duas versões de uma linguagem pública. Buscar as duas juntas tem um defeito sério: os 5 trechos vão para a versão que tem mais texto sobre o assunto, e a outra parece não ter nada. O modelo então conclui "só existe no 25", quando na verdade o trecho do 8 só perdeu a disputa por espaço.

Por isso, numa comparação com duas ou mais versões, cada versão que existe no acervo tem a sua busca, com o filtro restrito a ela. Numa pergunta mista ("isso mudou do java 17 para o 21 na nossa app X?") a documentação interna é mais um grupo, com busca própria só em INTERNAL, e vem por último. O teto `sage.retrieval.comparison-max-chunks` (8) é dividido entre os grupos, o resto indo para os primeiros: duas versões ficam com 4 e 4, duas versões e a interna com 3, 3 e 2. Cada grupo buscado tem pelo menos 2, e para isso caber no teto são buscados no máximo `teto / 2` grupos (4 com o padrão), contando o interno. Numa pergunta com mais versões que isso ("diferença entre java 8, 11, 17, 21, 25 e 26"), as quatro primeiras são buscadas e o código avisa logo depois do `metadata`: "Comparei só as versões Java 8, Java 11, Java 17 e Java 21; peça as demais em outra pergunta." As que ficaram de fora não foram buscadas, então nunca recebem "não menciona": o vazio delas não diz nada sobre o acervo. Por isso o teto não pode ser menor que 4 (`@Min(4)`): com menos, nem duas versões caberiam com o piso. Cada grupo só fica com os trechos no limiar de relevância (`min-score`, ou `min-score-without-reranker` sem reranker): uma versão que só trouxe ruído conta como vazia. O que um grupo não usou vai para os grupos que encheram a sua parte, numa segunda busca com limite maior (uma passada só); no exemplo de duas versões, se o 17 trouxe 1 trecho, o 21 pode ficar com 7. A rota é decidida pelo melhor score entre todas as buscas.

O prompt agrupa os trechos por versão, na ordem em que a pergunta as citou, com a versão no próprio delimitador:

```
<documento n="1" fonte="publico/java/25/records.md" secao="Records" versao="25">
...
</documento>
<documento n="2" fonte="publico/java/17/records.md" secao="Records" versao="17">
...
</documento>
```

O texto de sistema pede a resposta versão a versão, na ordem dada, e diz o que fazer quando os trechos de uma versão não tratam do assunto: escrever no molde "A documentação do <Nome> <versão> disponível não menciona <assunto>.", citando os documentos daquela versão, e não concluir que o recurso não existe nela. A citação é necessária porque o guard descarta frase sem citação, e é honesta: são aqueles documentos que não trouxeram o assunto. A distinção entre "não está na documentação disponível" e "não existe" é o ponto: o acervo pode estar incompleto, e o Sage só afirma o que leu. O guard reforça isso nas respostas de comparação: frase que nomeia uma versão ou linguagem ("Java 8", "versão 21", "Spring Boot 3.5") e afirma inexistência ou indisponibilidade ("não existe", "não está disponível", "não há suporte", "não é suportado", "inexistente", "não possui") só passa no molde; fora dele é descartada, mesmo com citação. Frases de comportamento, que não nomeiam versão ("lança NoSuchFileException se o arquivo não existe [1]", "o record não possui setters [1]"), passam, e fora das comparações a regra nem roda: lá não há versão a comparar, e o risco de concluir ausência pelo silêncio é bem menor. Eventos que a documentação afirma ("foi removido no Java 11 [1]", "deixou de existir", "foi descontinuado", "foi introduzido no Java 16 [2]") passam normalmente: são exatamente o que uma pergunta de disponibilidade quer ouvir, e vêm de um trecho que diz isso, não do silêncio. Os trechos do grupo interno levam `origem="interna"` no delimitador, e o prompt pede que a parte interna venha depois das versões.

As outras situações saem do código, não do modelo:

- Versão pedida fora do acervo: "Não tenho a documentação do Java 8 no acervo.", logo depois do `metadata`, e a resposta segue com as versões que existem. Se nenhuma existe, `NOT_DOCUMENTED`.
- Versão que existe mas cuja busca não devolveu nenhum trecho acima do limiar: "A documentação do Java 17 disponível não menciona isso.", e o grupo dela nem entra no prompt. A linha sai na posição da versão: logo antes da primeira frase que cita uma versão posterior, ou no fim, antes do `done`, se nenhuma posterior for citada. Ela não vai junto dos avisos do começo porque a resposta segue a ordem das versões, e um "não menciona" solto no topo pareceria a conclusão da resposta. Para o grupo interno vazio não sai linha nenhuma.
- Cada versão citada ganha a sua linha "Documentação consultada: Java 17" antes da primeira frase que a cita.

Comparação com uma versão só ou nenhuma ("em qual versão do java foi introduzido o record?") não tem como dividir o contexto. Ela faz a busca normal, sem filtro de versão, vai para o Sonnet, e o prompt também leva a versão de cada trecho, para o modelo responder citando de qual versão tirou cada coisa.

Fica de fora, por enquanto, a comparação entre duas linguagens diferentes ("Java 21 com Spring Boot 4.1"): fica com uma só, como já acontece na análise. Para a análise reconhecer "do java 17 para o 21", o conector de versões passou a aceitar artigo depois de "e", "ou" e "para".

### 5. Done, auditoria e log

A auditoria tem dois eventos por pergunta, ambos cifrados e sem o texto da pergunta ou da resposta. `QUESTION_ASKED` (desfecho `started`) é gravado antes do primeiro evento SSE, com o ator, o id da conversa, a rota, o modelo e os ids dos documentos enviados ao modelo; se falhar, nada é transmitido e o cliente recebe só um `error`. É o fail-closed: não existe resposta sem rastro. `QUESTION_ANSWERED` é gravado em qualquer desfecho, com a rota e o modelo finais (`escalated=true` quando o Haiku passou a pergunta para o Sonnet), os ids dos documentos citados nas frases aprovadas até ali e o número de frases descartadas. O desfecho é `completed` (resposta inteira), `interrupted` (o stream do modelo começou e foi cortado por erro, prazo ou cliente que saiu, ou o cliente saiu antes) ou `failed` (erro antes de o modelo começar). Na resposta completa ele é gravado antes do `done`, que carrega o mesmo formato do `metadata` com os números finais (frases descartadas e tempos `analise`, `busca`, `modelo`, `total`) e a lista `documentacaoConsultada`, a versão estruturada das linhas "Documentação consultada"; se essa gravação falhar, o cliente recebe `error` em vez de `done`.

O log de aplicação tem uma linha por pergunta com rota, modelo, se houve escalada, score do melhor trecho, número de trechos, frases descartadas, segredos removidos, tempos e o `logRef` (hash curto) de cada documento usado. Ids de documento não vão para o log porque carregam nomes de aplicação e caminhos.

### Limites deste caminho

- A fundamentação é conferida por um modelo de NLI com amostra de teste pequena (29 pares) e limiar 0,5 não calibrado: ele pode barrar uma frase correta que o trecho só sugere e deixar passar uma distorção sutil. Sem os arquivos do modelo a conferência fica desligada e o guard volta a validar só a forma (a imagem Docker não sobe nesse caso). O custo é de 0,2 a 0,3 s por frase no caso típico, até mais de 1 s com muitos trechos grandes citados.
- Uma frase termina em quebra de linha ou em ponto, exclamação ou interrogação seguidos de espaço e de algo que começa frase (maiúscula, dígito, crase, colchete). Isso mantém "e.g. toList()", "p. ex. collect()" e "aprox. dez" inteiros, mas "aprox. 3 ms" ainda parte a frase (dígito), e uma frase seguinte que comece em minúscula gruda na anterior. Título Markdown sem citação é descartado.
- Cada `text` é uma frase solta: a estrutura de parágrafos e listas da resposta do modelo se perde, e a interface remonta o texto como achar melhor.
- O contexto da conversa (cache e continuação) vive na memória de cada instância. Com mais de uma instância atrás de um balanceador sem afinidade de sessão, "e no 17?" pode cair numa instância que não conhece o `conversaId` e ser analisada sozinha.
- A escalada depende de o Haiku perceber que não dá conta. Se ele achar que dá e escrever uma resposta rasa, ela sai assim; o guard só garante que cada frase cita um trecho.
- Na comparação por versão, o Sonnet pode errar a ordem ou misturar versões numa frase. A linha "Documentação consultada" e a versão em cada citação deixam isso visível, mas não impedem.

## Fallback web

A mensagem "Não tenho a documentação do Java 21 no acervo." tem uma alternativa quando a pergunta é só pública, a linguagem tem fontes oficiais configuradas (`sage.web.allowlist`) e há chave do Brave (`SAGE_BRAVE_API_KEY`). Nesse caso o `AnswerService` tenta trazer a documentação antes de desistir. Os detalhes de segurança e de configuração estão em [fallback-web.md](fallback-web.md).

O gatilho (rota `WEB_FALLBACK`) é: fontes `{PUBLIC}`, pergunta não mista, linguagem identificada, e a versão pedida ausente do acervo ou o melhor trecho abaixo do limiar ativo. Perguntas internas ou mistas nunca chegam aqui, e uma pergunta com alias interno no texto tem a consulta descartada em vez de enviada. O fluxo, na ordem:

1. Depois da primeira busca e da decisão de rota, `RouteDecider.webFallbackNeeded` diz se vale tentar. Quando a versão falta, busca-se a versão pedida; quando a versão existe mas o trecho não serve, a versão em uso. Sem uma versão certa para rotular o documento (pergunta sem versão e acervo vazio para a linguagem) não se busca nada, porque gravar uma página sob a versão errada seria pior que não ter a página.
2. `WebFallbackService.fetchAndStore` monta a consulta (sem alias interno), grava `WEB_SEARCH` na auditoria e só então pede ao Brave os resultados restritos aos domínios da linguagem. Descarta URLs cuja estrutura de caminho não traz exatamente a versão pedida (`VersionedUrls`), baixa até 3 páginas pelo fetcher com proteção contra SSRF, converte para Markdown sem texto escondido, descarta página com segredo, audita `DOCUMENT_UPLOADED` com `origem=web` (a chave que perguntou é o ator) e grava com `saveIfAbsent` como `publico/<linguagem>/<versao>/<slug>.md`, `revisado: false`, `url_origem` e `coletado_em`.
3. Cada gravação publica o evento de documento salvo, e o índice e o catálogo de aliases atualizam na hora. O `AnswerService` refaz análise, busca e decisão e segue o caminho normal, rápido ou forte. A resposta sai com `fontesNaoRevisadas` verdadeiro, as citações marcadas como não revisadas e a linha "Documentação consultada: Java 21" como sempre. Como a gravação limpou o cache durante a pergunta, a época do cache é relida depois do fallback, e a resposta pode ser cacheada.
4. A segunda pergunta igual encontra a versão no acervo e responde sem tocar na web. Se a web não trouxe nada útil, vale o mesmo texto de antes ("Não tenho a documentação do Java 21 no acervo." ou "Não encontrei isso na documentação disponível."), sem lacuna, porque a pergunta é pública. Uma mesma pergunta (linguagem, versão e texto) só aciona a busca uma vez por hora, ache algo ou não.

Numa comparação entre versões só as versões ausentes são buscadas, dividindo entre elas o teto de 3 páginas por pergunta; depois cada versão é recuperada separadamente, como sempre.

Linguagem pública reconhecida mas sem fontes na allowlist (Rust, Python) e com a versão pedida fora do acervo responde, sem modelo e sem web, "Não tenho a documentação do Rust 1.80 no acervo." seguida de "Não há fontes oficiais configuradas para essa linguagem.". Com a versão no acervo e um trecho fraco, vale o "Não encontrei isso na documentação disponível." de sempre.

O fallback inteiro tem um prazo só, `sage.web.fallback-timeout` (8 s), que cobre todas as versões da pergunta somadas e conta dentro do orçamento de preparação (`sage.questions.preparation-timeout`, 20 s). A aplicação não sobe se o orçamento não passar o prazo do fallback em pelo menos 5 s. Antes de chamar o Brave o serviço grava `WEB_SEARCH` na auditoria; se isso falha, a pergunta falha sem nada sair da máquina.

## Perguntas de fluxo

"Como funciona o fluxo de faturamento no Portal X?" é o tipo de pergunta que o trecho solto responde mal. A busca devolve cinco pedaços de 1500 caracteres, e um fluxo quase nunca cabe num deles: o passo 1 está numa seção, o passo 3 em outra, e o "depois a cobrança acontece" aponta para um documento que a busca nem trouxe. O `QuestionAnalyzer` reconhece o tipo `FLOW` por expressões como "como funciona", "qual o fluxo", "passo a passo" e "fluxo de", e a rota vai direto para o Sonnet, como na comparação: explicar um processo exige juntar partes.

### Expandindo o contexto

Depois da busca e da rota, o `FlowContextExpander` troca os trechos por documentos. Para cada documento interno entre os melhores trechos:

- se o documento tem até `sage.flow.max-doc-chars` (12000), ele entra inteiro, como uma entrada só;
- se for maior, entra a seção de nível 2 do melhor trecho dele ("Faturamento > Emissão", com tudo que vem abaixo), e, se nem a seção couber no limite, o melhor trecho e os vizinhos que couberem.

Os wiki-links do texto que o modelo vai ver são seguidos com profundidade 1: `[[cobranca]]` resolve para `interno/<app>/cobranca.md` na mesma aplicação, `[[interno/portal-x/cobranca]]` é a forma explícita, e `[[cobranca|como cobrar]]` e `[[cobranca#prazos]]` valem como `[[cobranca]]` (apelido e título são ignorados). No máximo `sage.flow.max-linked-docs` (3) documentos entram, na ordem em que os links aparecem. Link que não resolve para um documento do índice é ignorado, e link nunca atravessa fonte (documento interno não puxa público nem o contrário) nem aplicação. Os documentos ligados não são varridos atrás de novos links.

O texto somado fica sempre abaixo de `sage.flow.max-context-chars` (24000). Os documentos que a busca achou têm prioridade; um documento ligado maior que o orçamento que sobrou entra com o começo dele, cortado entre trechos (o chunker já separa por título e parágrafo, então o corte cai numa fronteira natural). A alternativa seria escolher a melhor seção do documento ligado, mas a pergunta não chega ao expander, e eu preferi não inventar um segundo ranking só para isso. Cada documento expandido é uma entrada do prompt, com o seu `[n]`, e o guard valida as citações contra esse contexto maior. A expansão só mexe no prompt: o limite de 2000 caracteres da pergunta, a busca e o reranker não a enxergam, e o `scoreMaximo` do `metadata` continua sendo o da busca. Os ids dos documentos ligados vão para `contextDocIds` na auditoria, porque foram enviados ao modelo.

### Passos numerados e diagrama

O prompt pede passos numerados, um por linha, cada um terminado em citação. Para o guard cada item é uma frase e precisa da sua citação: "2. Emite a nota fiscal." sem `[n]` é descartado, e a numeração segue o que sobrou (os passos 1 e 3 chegam, o 2 não). O splitter deixou de tratar o "1." do começo da linha como fim de frase; antes dessa mudança o marcador virava uma frase sem citação, que era descartada junto com a numeração.

Se a documentação listar três ou mais etapas em ordem, o modelo pode acrescentar um bloco `mermaid` com `flowchart TD` logo depois do último passo. A regra do bloco de código é a de sempre: só passa se a frase imediatamente anterior foi aprovada, e cada bloco consome essa frase. Como a interface pode renderizar o diagrama, o guard recusa bloco `mermaid` que tenha uma linha começando com `click`, a palavra `href`, `javascript:`, uma diretiva `%%{...}` (que muda o nível de segurança do renderizador) ou qualquer tag HTML. Seta como `<-->` não conta como tag. Isso é uma lista de negação, não uma prova de que o diagrama é inofensivo; o renderizador ainda precisa rodar com `securityLevel: strict`.

### Quando a documentação não detalha

Se nenhum trecho passa do limiar numa pergunta de fluxo sobre a documentação interna, o código responde "A documentação não detalha esse fluxo." sem chamar o modelo, no lugar do texto genérico de "não encontrei". O prompt pede a mesma frase quando os documentos não descrevem o processo, e o guard a deixa passar sem citação, do mesmo jeito que faz com o "não cobre".

### Exemplo

Pergunta, com a documentação interna do `portal-x` indexada (`faturamento.md` aponta para `[[cobranca]]`):

```
POST /api/perguntas
{ "pergunta": "Como funciona o fluxo de faturamento no Portal X?" }
```

O prompt recebe `faturamento.md` inteiro como `[1]` e `cobranca.md` como `[2]`. A resposta chega frase a frase:

```
metadata   rota=STRONG_MODEL, modelo=STRONG, citacoes=[1, 2]
text       Documentação consultada: portal-x (interna)
text       1. O fechamento acontece no dia 5 de cada mês [1].
text       2. A nota fiscal é emitida para cada contrato ativo [1].
text       3. A cobrança sai no dia seguinte, por boleto [2].
text       ```mermaid
           flowchart TD
             A[Fechamento] --> B[Nota fiscal]
             B --> C[Cobrança]
           ```
done       frasesDescartadas=0
```

Um quarto passo sem `[n]` teria sido descartado e contado em `frasesDescartadas`.

### Limites

- Só documentos internos são expandidos. Pergunta de fluxo sobre documentação pública continua com os trechos soltos, no Sonnet.
- Documento ligado maior que o orçamento entra pelo começo, não pela parte mais relevante.
- Os links são lidos do texto indexado; um link dentro de uma parte que ficou de fora (seção de nível 2 de documento grande) não é seguido.
- O guard confere que o passo cita um trecho, não que a ordem dos passos é a da documentação.

## Cache e conversa

Duas perguntas iguais em sequência não precisam pagar duas vezes a busca, o reranker e o Haiku. E uma pergunta de continuação precisa saber de que se está falando. As duas coisas moram em memória, em `AnswerCache` e `ConversationStore`, e as duas são bem menos espertas do que poderiam ser de propósito.

### Conversa

O servidor é quem dá o `conversaId`: sem ele na requisição, gera um UUID e devolve no `metadata`. A cada pergunta, o `ConversationStore` guarda a análise (fonte, linguagem, versões, tipo) das últimas 5 perguntas daquela conversa, com validade de 30 minutos contados da última pergunta (`sage.conversation.*`). Quando a requisição traz um `conversaId` conhecido, a análise anterior vai como `previous` para o `QuestionAnalyzer`, e "e o collect?" depois de "toList no java 21" continua em Java 21 (as regras de herança estão em "Perguntas de continuação", acima).

Um `conversaId` que o servidor não conhece, porque expirou, porque o processo reiniciou ou porque o cliente inventou, não vira erro: a pergunta é tratada como a primeira de uma conversa nova e o `metadata` volta com um id novo. Quem manda o id não escolhe o id; o cliente sempre deve usar o que veio na última resposta. O custo dessa escolha é que uma continuação que perdeu o contexto é respondida sem a linguagem herdada, em vez de falhar, e o usuário só percebe pela resposta. Uma conversa também pertence a quem a começou: o `ConversationStore` guarda o nome da chave de API junto das análises, e um `conversaId` apresentado por outra chave é tratado como desconhecido (mesmo comportamento, id novo). A análise só entra na conversa depois que a resposta termina (e a auditoria de `QUESTION_ANSWERED` foi gravada); uma pergunta que falhou antes disso, por índice carregando, auditoria ou limite, não deixa rastro no contexto.

O estado é por processo. Com uma instância só (o caso de hoje) não há problema; com várias atrás de um balanceador sem afinidade de sessão, a continuação cai em outra instância, não acha a conversa e é analisada sozinha. Quando isso importar, o caminho é afinidade de sessão ou mover o `ConversationStore` para um Redis; nenhum dos dois está feito. O número de conversas também tem teto (`sage.conversation.max-conversations`, 10000), então um cliente que abre conversas sem parar expulsa as mais antigas em vez de encher a memória.

### Cache de respostas

Só a primeira pergunta de uma conversa, a que chega sem `conversaId`, passa pelo cache. Uma continuação depende do que veio antes, e a mesma frase ("e o collect?") significa coisas diferentes em conversas diferentes; ela sempre vai à busca e ao modelo.

A chave é a pergunta normalizada (minúsculas, espaços colapsados, sem `?` ou `!` no fim) mais as fontes, a linguagem, as versões efetivamente usadas e o tipo da pergunta. Quando a pergunta não traz versão, a inferida (a mais recente do acervo) entra na chave no lugar dela. Isso não faz "toList no java" e "toList no java 25" compartilharem resposta, porque o texto normalizado das duas é diferente; serve para que a mesma pergunta sem versão não reaproveite uma resposta dada quando a versão mais recente era outra (e, de qualquer forma, subir a documentação de uma versão nova limpa o cache). A análise roda antes da consulta ao cache (é barata, não usa índice nem modelo); a busca, o reranker e o modelo não rodam num acerto.

O que fica guardado é a sequência inteira de eventos da resposta: o `metadata` final (citações, versão usada, `documentacaoConsultada`) e todos os `text` na ordem em que saíram, inclusive os que o código emite, como "Considerei a versão 25, a mais recente disponível.", "Não tenho a documentação do Java 8 no acervo." e as linhas "Documentação consultada: ...". Isso importa por causa da regra de versão: um acerto tem de mostrar a mesma versão consultada que a resposta ao vivo mostraria, não uma frase do modelo copiada sem o aviso. A reemissão põe `rota: CACHE`, sem `modelo`, um `conversaId` novo (a conversa de quem perguntou agora) e tempos novos (`analise` e `total`; um acerto leva milissegundos). O `conversaId` do acerto também abre a conversa: "e o collect?" logo depois de uma resposta vinda do cache herda linguagem e versão normalmente.

Não entram no cache:

- `NOT_DOCUMENTED`: é uma resposta fixa, barata, e muda no momento em que o acervo ganha o documento que faltava.
- Resposta que falhou ou foi interrompida (erro do modelo, prazo, cliente que saiu): não há resposta completa para repetir.
- Resposta em que o guard descartou alguma frase. Foi uma decisão de projeto, não uma exigência: uma resposta podada tem buraco, e repeti-la por até 6 horas tira do usuário a chance de a segunda tentativa vir inteira. O custo é que perguntas em que o modelo tende a escorregar nunca ficam em cache.

O cache vale 6 horas e guarda no máximo 1000 respostas (`sage.cache.*`); passando disso, o Caffeine descarta as menos usadas. Qualquer `DocumentSavedEvent` ou `DocumentDeletedEvent` esvazia o cache inteiro: invalidar só as respostas que citaram aquele documento exigiria saber, para cada entrada, de que documentos ela depende, e o ganho não paga a complexidade (um upload é raro perto de perguntas). A limpeza acontece dentro do lock do armazém de documentos e é só um `invalidateAll`. Uma resposta que começou antes do upload e terminou depois não entra no cache: o serviço lê a "época" do cache antes de buscar e o `put` descarta a gravação se ela mudou.

Como o conteúdo da resposta é derivado de documentos, o cache existe só na memória do processo. Nada é serializado, nada vai para o `BlobStorage`, e nem a pergunta nem a resposta aparecem em log (a chave e a resposta têm `toString` que mostram só tamanhos). Reiniciar o Sage zera o cache; é o comportamento desejado.

### Auditoria e limite de uso

Um acerto de cache audita como qualquer pergunta: `QUESTION_ASKED` antes de emitir qualquer evento (com rota `CACHE` e os documentos das citações) e `QUESTION_ANSWERED` com desfecho `completed`, os documentos efetivamente citados no texto e `droppedSentences=0`. Se `QUESTION_ASKED` falhar, nada é reemitido, como no caminho normal. O limite de perguntas por minuto também vale: o controller o aplica antes de saber se haverá acerto, então repetir a mesma pergunta 21 vezes num minuto leva 429 igual.

### Limites

- Duas perguntas com o mesmo sentido e palavras diferentes ("como usar toList" e "uso do toList") não compartilham resposta; a normalização é só de forma, sem semântica.
- Ao salvar ou apagar qualquer documento, todo o cache vai embora, inclusive respostas que nada tinham a ver com ele.
- Conversa e cache são por instância e se perdem num restart.
