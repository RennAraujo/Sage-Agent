# Arquitetura

Este documento descreve como as peças do Sage-Agent se encaixam hoje e por que o índice de busca vive só na memória. O desenho completo, com o que ainda vai ser construído, está em [design.md](design.md); aqui fica o que já existe no código.

## Componentes

O código é organizado por funcionalidade, um pacote para cada. O núcleo (documento, busca) não conhece S3, KMS nem Lucene: conversa com interfaces, e o que está atrás delas é trocável.

| Pacote | O que faz |
| --- | --- |
| `crypto` | Criptografia em envelope: uma chave de dados nova para cada blob, protegida pela chave mestra (KMS na AWS, arquivo local em desenvolvimento). |
| `storage` | Guarda bytes já cifrados, em disco local ou no S3. Não sabe o que está guardando. |
| `document` | Modelo do documento (frontmatter mais corpo) e o acervo cifrado. Publica um evento a cada gravação e a cada exclusão. |
| `upload` | Recebe arquivos e zips, converte para Markdown, varre segredos e grava no acervo. |
| `auth`, `audit` | Chave de API com papéis, limite de requisições e trilha de auditoria cifrada. |
| `search` | Índice de busca em memória (BM25), descrito abaixo. |

As portas que o resto do código enxerga são `DocumentStore` (acervo), `BlobStorage` e `KeyProvider` (onde e com que chave os bytes são guardados) e `SearchIndex` (busca). Cada uma tem uma implementação de produção e é fácil de substituir em teste: os testes do índice, por exemplo, usam um `DocumentStore` falso para segurar a carga inicial no meio e provocar a corrida entre carga e eventos.

## Caminho de um documento

No upload, o `UploadService` grava o documento no `DocumentStore`. O acervo cifra o conteúdo, grava o blob e, ainda dentro do lock daquele id, publica `DocumentSavedEvent`. O `IndexBootstrapper` escuta o evento e chama `SearchIndex.upsert`. Exclusão é o mesmo caminho com `DocumentDeletedEvent` e `remove`.

Como os listeners rodam de forma síncrona dentro do lock do id, a ordem dos eventos de um documento é a ordem das operações no acervo, e o índice nunca fica com a versão antiga por cima da nova. O preço é que o upload espera a reindexação daquele documento. Para um documento de alguns KB isso são milissegundos; um documento enorme segura o lock do próprio id por mais tempo, mas não bloqueia os outros.

## O índice em memória

### Como ele é montado

O documento é dividido em trechos (`MarkdownChunker`) por título, guardando o caminho de títulos ("Streams > toList") junto com cada trecho. Parágrafos e blocos de código são agrupados até `sage.retrieval.max-chunk-chars` (1500 por padrão). Bloco de código nunca é cortado: um exemplo partido no meio não serve a ninguém, então um bloco maior que o limite vira um trecho sozinho e passa do teto. Parágrafo maior que o limite é dividido em fim de frase ou espaço. Marcadores `#` dentro de bloco de código não contam como título.

Cada trecho vira um documento Lucene com dois campos de texto sobre o mesmo conteúdo. O campo `text` usa o tokenizer padrão com minúsculas e remoção de acento, então "coleção" e "COLECAO" se encontram. O campo `exact` separa só por espaço e põe em minúsculas, o que mantém `Stream.toList()` como um termo inteiro, com ponto e parênteses. Cada campo é indexado com o seu próprio analisador (um `PerFieldAnalyzerWrapper` no writer), e a consulta usa os mesmos dois. É isso que faz o `exact` valer algo: ele casa o que o tokenizer padrão descarta, como `->`, `::` ou `@Transactional` com a arroba. A consulta procura nos dois campos e dá peso 2 ao `exact`: quem digita um identificador exato ganha o trecho que o contém antes de um trecho que apenas fala do assunto. Fonte, linguagem e versão são campos de filtro, não entram na pontuação. A linguagem e as versões restringem só os trechos públicos; trecho interno (que não tem versão) passa pelo filtro apenas por a fonte interna ter sido pedida. Em termos de consulta: (público E linguagem E versão em `versions`) OU (interno), cada ramo presente se a fonte está em `sources`, e `sources` vazio vale as duas. A pontuação é o BM25 padrão do Lucene.

O texto digitado pelo usuário nunca passa pelo parser de consulta do Lucene. Ele é analisado em termos e a consulta é montada por código, então `AND`, `*:*`, aspas abertas ou colchetes são só palavras (que quase nunca casam com nada). Uma consulta vira no máximo 64 termos distintos; o resto é descartado. Consulta em branco devolve lista vazia.

Os ids de trecho são o `logRef()` do documento (12 hex do SHA-256 do id) mais a posição do trecho. Eles se repetem entre reindexações e não expõem o caminho do documento, que pode conter nome de aplicação interna.

### Carga inicial e saúde

Na subida, depois de `ApplicationReadyEvent`, o `IndexBootstrapper` decifra o acervo numa virtual thread e indexa documento a documento. Enquanto isso o indicador de saúde `index` fica DOWN, e como `/actuator/health` agrega pelo pior estado, o agregado também fica DOWN (503) até a carga terminar. A escolha é deliberada: um serviço que ainda não consegue buscar não deve receber tráfego, e é isso que um balanceador espera de um health check. O endpoint continua público. Se a carga falhar, o índice fica DOWN com `state=failed`; o serviço não tenta de novo sozinho, é preciso reiniciar (o log traz só a classe da exceção, nunca conteúdo).

Uploads e exclusões que chegam durante a carga não se perdem nem duplicam. O upsert é idempotente por id, e cada id tocado por evento fica anotado: se a carga alcançar depois um documento que já recebeu evento, ela o pula, porque o que ela leu pode ser anterior ao evento (um documento apagado depois do snapshot ainda aparece no stream do acervo). Evento e carga agem sobre o mesmo id dentro de uma operação atômica do mapa, então não há janela em que a versão velha ganhe.

### Por que em memória

O requisito central do projeto é que nenhum trecho do acervo exista em texto puro fora da memória do processo. Um índice Lucene em disco é exatamente isso: arquivos com o texto armazenado e os termos, legíveis por qualquer um que alcance o volume. Cifrar o diretório do índice é possível, mas complica bastante (o Lucene faz leitura por acesso aleatório e mapeia arquivos) e o ganho seria apenas evitar a reconstrução na subida.

Com o `ByteBuffersDirectory`, o índice é feito de buffers na heap do JVM. Nada vai para o disco, nem arquivo de índice nem arquivo temporário (há um teste que confere o diretório de dados, o diretório temporário do sistema e o diretório de trabalho). Quando o processo cai, o índice some, e a próxima subida o reconstrói a partir do acervo cifrado. O custo é tempo de subida e RAM.

### Quanto de RAM

Estimativa por ordem de grandeza, ainda não medida com o acervo real. O índice guarda o texto de cada trecho (campos armazenados, comprimidos pelo Lucene) e duas listas de termos (`text` e `exact`). Na prática, o índice fica entre uma vez e uma vez e meia o tamanho do Markdown original. Durante uma fusão de segmentos o Lucene mantém cópia antiga e nova ao mesmo tempo, então o pico pode chegar a duas vezes o tamanho do índice.

Um acervo de 200 MB de Markdown (cerca de 130 mil trechos de 1500 caracteres) dá algo entre 200 e 300 MB de índice e perto de 600 MB no pico. Uma equipe pequena com a documentação de algumas linguagens e da aplicação costuma ficar bem abaixo disso, na casa de dezenas de MB. O acervo decifrado não fica todo em memória ao mesmo tempo: a carga lê um documento por vez e descarta o texto depois de indexar. Convém dimensionar o `-Xmx` com folga sobre o pico estimado, e o contêiner com mais do que o `-Xmx`, porque o JVM usa memória fora da heap.

O que não cabe nessa conta é a busca vetorial da Task 8, que entra depois com seus próprios vetores e vai precisar de uma nova estimativa.

### Limitações conhecidas

- Upload e exclusão fazem refresh do leitor a cada operação, para a busca enxergar o documento na hora. A carga inicial não: ela indexa tudo sem refresh e dá um único no final, antes de o índice se anunciar pronto. Um documento que falha na carga é contado e registrado (só a referência curta e a classe da exceção) e a carga segue sem ele.
- Versões são ordenadas semanticamente ("4.9" antes de "4.10"), com qualificadores de pré-lançamento (`25-ea`, `4.0-rc1`) antes da versão final. A ordem entre dois qualificadores é por texto, então `rc10` vem antes de `rc2`.
- Só há uma instância do agente: o índice em memória não é compartilhado entre processos, e cada réplica reconstrói o seu na subida.
- Identificador composto só casa inteiro: o tokenizer padrão mantém `Stream.toList` como um termo só, então buscar apenas `toList` não encontra o trecho que escreve `Stream.toList()`. Se isso incomodar, o caminho é quebrar identificadores em partes no campo `text`, mantendo o `exact` como está.
