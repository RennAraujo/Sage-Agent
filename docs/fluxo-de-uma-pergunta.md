# Fluxo de uma pergunta

Este documento acompanha uma pergunta do momento em que chega até a resposta. Cada etapa ganha uma seção conforme é implementada; por enquanto, a primeira.

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
