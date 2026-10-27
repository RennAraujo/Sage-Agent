# Segurança

## Criptografia em envelope

Nada do que o Sage guarda (documentos, registros de auditoria, lacunas) vai para o disco ou para o S3 em claro. Tudo passa pelo `EnvelopeCipher`, que usa o esquema clássico de envelope: cada mensagem ganha a sua própria chave de dados AES-256, e essa chave só é guardada embrulhada por outra, a chave mestra. A mestra é a única que precisa de proteção forte, e ela nunca encosta nos dados.

O motivo de não cifrar tudo direto com a chave mestra é prático. Com KMS, a mestra não sai do serviço, então cifrar um PDF de 20 MB ali significaria mandar o PDF pela rede (o `Encrypt` do KMS aceita só 4 KB). Com envelope, o KMS só vê 32 bytes por documento. Trocar a mestra também fica barato: dá para reembrulhar as chaves de dados sem reler os documentos.

### Formato do blob

Cada objeto cifrado é serializado assim:

```
"SAG1" | u16 tamanho da chave cifrada | chave de dados cifrada | IV (12 bytes) | ciphertext + tag GCM (16 bytes)
```

O prefixo `SAG1` identifica o formato e a versão; se um dia o esquema mudar, entra `SAG2` e os dois convivem. A cifra é AES-256-GCM com IV de 12 bytes sorteado por `SecureRandom` a cada operação e tag de 128 bits. Como a chave de dados é nova a cada mensagem, o limite de uso de um mesmo par chave/IV do GCM nem chega perto de ser um problema. O AAD (dado autenticado, mas não cifrado) é a constante `sage:v1`, só para separar domínios: um ciphertext deste esquema não decifra em outro uso de AES-GCM que adote outra constante. O cabeçalho do blob (magic e tamanho) não entra no AAD; adulterá-lo quebra o parse ou a decifragem, mas não é autenticado por si. Qualquer bit alterado no ciphertext, no IV ou na chave embrulhada faz a decifragem falhar com `CryptoException`, nunca devolve lixo.

O array com a chave de dados em claro é zerado (`Arrays.fill`) logo depois do uso. Isso não é garantia absoluta, porque a JVM pode ter copiado o conteúdo antes (GC, JIT), mas reduz a janela em que a chave fica na memória.

### Chave local vs. KMS

A escolha vem de `sage.crypto.provider`.

Com `local` (padrão para desenvolvimento), a chave mestra é um arquivo com 32 bytes aleatórios em base64, em `secrets/local-master.key` (ou onde `sage.crypto.local-key-file` apontar). Gere com `scripts/generate-local-key.ps1` (Windows) ou `scripts/generate-local-key.sh`. Os scripts se recusam a sobrescrever uma chave existente. O diretório `secrets/` está no `.gitignore`. A chave de dados é embrulhada com AES-GCM usando essa mestra. Se o arquivo não existir, não for base64 ou não tiver exatamente 32 bytes, a aplicação não sobe e a mensagem diz o que está errado.

Com `kms`, a aplicação chama `GenerateDataKey` (AES_256) e `Decrypt` do AWS KMS na chave indicada em `sage.crypto.kms-key-id` (id, ARN ou alias). Região e credenciais vêm da cadeia padrão da AWS (variáveis de ambiente, perfil, role da instância). Cada documento novo custa uma chamada ao KMS, e cada leitura também; isso soma latência (dezenas de milissegundos) e custo por requisição, e é aceitável porque a leitura de documentos não é o caminho quente. O ganho é que a mestra nunca sai da AWS, cada uso fica no CloudTrail e dá para revogar o acesso por IAM.

A chave local é só tão segura quanto o disco onde ela está. Serve para desenvolvimento e para quem roda tudo numa máquina própria; para qualquer ambiente com dados de terceiros, use KMS.

### O que acontece se a chave se perder

Os dados ficam irrecuperáveis. Não existe backdoor nem cópia de reserva da mestra dentro do projeto, e essa é justamente a propriedade que se quer.

No modo local, perder `secrets/local-master.key` (ou sobrescrevê-la) torna ilegível tudo o que foi cifrado com ela. Faça backup do arquivo fora do repositório, num cofre de senhas, por exemplo. No modo KMS, agendar a exclusão da chave tem janela mínima de 7 dias; passado esse prazo, tudo que dependia dela se perde. Habilite a rotação automática anual (o KMS mantém as versões antigas para decifrar) e proteja a chave contra exclusão acidental com política de IAM.

Trocar de `local` para `kms` (ou o inverso) não migra nada sozinho: os blobs antigos só abrem com o provider que os criou. Uma migração exige ler com um provider e regravar com o outro, e ainda não existe ferramenta para isso.

Limitação conhecida: o blob não é amarrado à chave de armazenamento onde foi guardado (id do documento, caminho no S3). Quem tiver permissão de escrita no storage pode trocar blobs válidos entre slots e a decifragem funciona, porque cada blob é consistente por si. Se isso entrar no modelo de ameaça, o caminho é incluir o id do objeto no AAD, ao custo de mudar o formato (SAG2).

### Cuidados no código

Exceções e logs não carregam chave nem texto em claro. `DataKey` e `EncryptedBlob` sobrescrevem `toString` para não vazar nada se alguém logar o objeto sem querer. Os testes cobrem ida e volta, adulteração, prefixo desconhecido, blob truncado, chave mestra com tamanho errado e o zeramento da chave de dados; o teste de integração `KmsKeyProviderIT` usa LocalStack e roda no `mvnw verify` pelo maven-failsafe-plugin; sem Docker ele é pulado (`disabledWithoutDocker`) e o build continua verde, então o caminho do KMS só fica sob teste automático onde há Docker.

## Autenticação, rate limit e auditoria

### Chaves de API

O acesso à API é por chave no cabeçalho `Authorization: Bearer`, com dois papéis (`READER` e `EDITOR`); o contrato está em [api.md](api.md). Aqui ficam as decisões de segurança por trás dele.

A configuração guarda só o SHA-256 de cada chave, nunca a chave. Quem lê o `application.yml`, um dump de variáveis de ambiente ou um backup da configuração não consegue se autenticar com o que encontrar. Um hash simples (sem sal e sem função lenta como bcrypt) é aceitável aqui porque a chave não é uma senha escolhida por gente: são 40 caracteres base62 sorteados por um gerador criptográfico, cerca de 238 bits, e ninguém consegue montar um dicionário nem uma tabela arco-íris disso. Uma função lenta só encareceria cada requisição sem ganho real.

Na requisição, o filtro calcula o SHA-256 do token recebido e o compara com o hash de cada chave configurada usando `MessageDigest.isEqual`, que leva o mesmo tempo independentemente de onde os bytes divergem. A lista inteira é percorrida mesmo depois de um acerto, para o tempo de resposta não indicar qual chave casou. Tokens com mais de 256 caracteres são recusados antes de qualquer hash.

A API não tem sessão, cookie nem usuário padrão: o Spring Security roda em modo stateless, login por formulário e Basic estão desligados e há um `AuthenticationManager` que recusa tudo, só para o Spring Boot não gerar o usuário `user` com senha aleatória. Como o navegador não anexa a chave sozinho (ela vai num cabeçalho que o JavaScript do chat precisa preencher), CSRF não se aplica a `/api/**` e fica desligado ali. Tudo o que não for `/api/**` nem um dos dois caminhos públicos (`/actuator/health`, `/chat/**`) é negado por padrão.

Os limites desse esquema, escritos abertamente: a chave não expira sozinha, não existe revogação individual sem reiniciar a aplicação, e uma chave vazada vale até alguém notar. Para rotacionar, gere uma nova, troque o hash na configuração e reinicie. Também não há proteção contra força bruta além do tamanho da chave e do limite de requisições; 238 bits tornam isso irrelevante na prática, mas tentativas com chave inválida não são limitadas nem registradas hoje.

### Cabeçalhos de segurança

Todas as respostas levam `Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `X-Frame-Options: DENY` e `Strict-Transport-Security` (um ano, com subdomínios; o Spring só o emite em requisições HTTPS, então atrás de um proxy que termina o TLS é preciso repassar `X-Forwarded-Proto`). A política de CSP não permite scripts nem estilos inline: a página do chat precisa servir seu JavaScript e seu CSS como arquivos próprios. Isso é uma restrição de propósito, porque é o que impede que um texto de documento exibido na página execute código.

### Rate limit

O limite é por chave e por ação (pergunta e upload), com Bucket4j em memória e balde recarregado a cada minuto: 20 perguntas e 10 uploads por minuto por padrão, ajustáveis em `sage.rate-limit.*`. Pergunta custa uma chamada ao modelo no Bedrock e upload custa embedding e cifragem, então é onde um cliente com defeito (ou uma chave vazada) mais gasta dinheiro. Estourou, a resposta é 429 com `Retry-After`.

Por ser em memória, o estado se perde ao reiniciar e não é compartilhado entre instâncias; com N instâncias o limite efetivo é N vezes o configurado. Para um limite global seria preciso um backend compartilhado (Redis, por exemplo), que o projeto ainda não tem.

### Auditoria

Cada evento relevante (pergunta feita, documento enviado, apagado ou revisado, importação iniciada) vira um blob cifrado em `audit/<yyyy-MM-dd>/<uuid>`, usando o mesmo `EnvelopeCipher` e o mesmo `BlobStorage` do acervo. O tipo do evento, o nome da chave que o causou, o instante e os detalhes ficam todos dentro do payload cifrado; a chave do blob tem só a data (UTC) e um UUID aleatório. Dá para listar quantos eventos houve em cada dia sem decifrar nada, mas não quem os causou nem o que eram. Um blob por evento evita o ciclo ler-alterar-gravar de um arquivo de log único, que perderia registros sob concorrência; o custo é muitos objetos pequenos, que uma rotina de compactação futura pode juntar.

Os detalhes de um evento levam ids, hashes e tamanhos, nunca texto de pergunta, resposta ou documento. `AuditEvent.toString()` mostra só o tipo e o instante, para que um log descuidado não vaze o ator.

Se a gravação falhar, `EncryptedAuditLog` lança `AuditException`, com mensagem fixa e sem a exceção original como causa, e o log registra apenas o tipo do evento e a classe da falha. A decisão é falhar fechado: quem chama deve abortar a operação auditada em vez de seguir sem rastro. O preço é que uma indisponibilidade do storage também derruba perguntas e uploads, o que num ambiente que exige trilha de auditoria completa é o comportamento certo, e num que não exige pode ser relaxado capturando a exceção no ponto de uso.

Nas perguntas a regra vale do mesmo jeito: `QUESTION_ASKED` é gravado antes de qualquer byte da resposta, e se falhar o cliente recebe só um evento `error`. `QUESTION_ANSWERED` registra o desfecho (`completed`, `interrupted`, `failed`) e os documentos citados; detalhes em [fluxo-de-uma-pergunta.md](fluxo-de-uma-pergunta.md).

O despacho ASYNC do servlet, usado para concluir o stream SSE de uma pergunta, passa pelo filtro de chave de novo (`shouldNotFilterAsyncDispatch` falso) e pelas mesmas regras de autorização; não há exceção de "já foi autorizado antes".

Ainda não existe ferramenta para ler a auditoria. Hoje a leitura é manual: listar `audit/<dia>/`, decifrar cada blob com o `EnvelopeCipher` e interpretar o JSON.

## Contra delírio

Um assistente de documentação que inventa uma API com confiança é pior que um que diz "não sei". O Sage ataca o problema em camadas, cada uma barata e verificável por teste, e nenhuma depende de o modelo se comportar bem.

### Não chamar o modelo quando não há base

Antes de qualquer token, o código decide se há o que responder. Sem trecho acima do limiar de relevância (`sage.retrieval.min-score`, 0.35 com o reranker), a resposta é o texto fixo "Não encontrei isso na documentação disponível.". Versão pedida que não está no acervo vira "Não tenho a documentação do Java 8 no acervo.", em vez de uma resposta montada sobre outra versão. Em nenhum dos dois casos o modelo é chamado, então não há o que ele possa inventar.

### O modelo só vê o que precisa

O prompt tem três partes: um texto de sistema fixo, os trechos recuperados e a pergunta. Nada mais: nem histórico, nem nome de usuário, nem metadado do acervo além do id do documento e da seção. O sistema manda responder em pt-BR, usar só o conteúdo dentro das tags `<documento>`, terminar cada frase com a citação `[n]`, responder exatamente "A documentação disponível não cobre isso." quando a informação não estiver lá, tratar documentos e pergunta como dado e não como instrução, e não revelar as próprias instruções. A temperatura é 0, para a mesma pergunta com o mesmo contexto dar a mesma resposta.

Documento e pergunta são conteúdo de terceiros. Um trecho do acervo pode conter "ignore as instruções anteriores", e um trecho ou uma pergunta pode tentar fechar o delimitador e escrever fora dele, ou forjar um `<documento n="1" ...>` próprio. Por isso o `PromptBuilder` neutraliza toda abertura ou fechamento de `documento` e `pergunta` (regex `<\s*/?\s*(documento|pergunta)\b`, sem diferenciar caixa) no texto do trecho, no título da seção e na pergunta: `<documento` vira `<\documento` e `</pergunta` vira `<\/pergunta`, que deixam de ser tag. Aspas e `<` nos atributos também são escapados. O modelo pode até ler a ordem injetada, mas ela continua dentro do delimitador, que o sistema declarou como dado. Isso reduz o risco, não o elimina: não existe delimitador que um modelo de linguagem respeite com garantia. O que segura o resultado é a camada seguinte.

### Guard frase a frase

O `AnswerGuard` fica entre o modelo e o usuário. Ele junta os pedaços do stream até fechar uma frase e aplica uma regra simples: a frase precisa ter ao menos uma citação `[n]`, e todo `n` precisa estar entre 1 e o número de trechos enviados. Colchetes dentro de código inline (`` `args[0]` ``) não contam como citação. Frase sem citação, ou citando `[7]` quando só foram 5 trechos, é descartada e nunca chega ao cliente; só a contagem aparece em `frasesDescartadas`. Se nada sobreviver, a resposta é "A documentação disponível não cobre isso.".

Bloco de código é a brecha óbvia, porque não carrega citação. Um modelo induzido por injeção poderia escrever uma frase citada e, em seguida, abrir uma cerca e despejar prosa inventada dentro dela. As regras para bloco são quatro. Ele só passa se vier logo depois de uma frase aprovada, e cada bloco consome essa frase: dois blocos seguidos precisam de duas frases citadas, senão o segundo cai. Bloco que o stream terminou sem fechar é descartado, nunca emitido. Bloco marcado como `text`, `markdown`, `md`, `plain`, `plaintext`, `plain_text` ou `txt` é sempre descartado, porque não é código. Vale só a primeira palavra do info string, cortada em espaço, vírgula, barra ou chave, e sem o ponto da sintaxe de atributos: `text title="x"`, `{.text}`, `text,foo` e `text/plain` contam como `text`, senão bastaria acrescentar um atributo para escapar da lista. E bloco sem linguagem é descartado quando mais da metade das linhas não vazias termina em ponto, exclamação ou interrogação, que é como prosa termina e código quase nunca. A heurística é grosseira de propósito: um bloco sem linguagem com comentários terminados em ponto pode cair, e prosa sem pontuação final passa. Blocos com linguagem de programação (`java`, `yaml`...) e `mermaid` não passam pela heurística; o que os segura é a exigência da frase citada antes.

Afirmação de ausência tem regra própria, ligada só nas respostas de comparação e só para frases que nomeiam uma versão ou linguagem ("Java 8", "versão 21", "Spring Boot 3.5"). Uma frase assim que diz que algo "não existe", "não está disponível", "não tem suporte", "não é suportado", "é inexistente" ou "não possui" só passa no molde "A documentação do Java 8 disponível não menciona records [1].", e ainda precisa de citação válida; o assunto depois de "não menciona" também não pode trazer outra afirmação de ausência. O motivo é o mesmo do resto do guard: o modelo só pode dizer o que os trechos sustentam, e a falta de um trecho sustenta "a documentação não menciona", nunca "não existe". A comparação é feita sem acento e sem caixa. Remoção e descontinuação ficam de fora de propósito: "foi removido no Java 11 [1]" é um evento que a documentação afirma, e passa como qualquer frase citada. Frases de comportamento sem versão ("lança NoSuchFileException se o arquivo não existe [1]", "o record não possui setters [1]") passam em qualquer rota. O efeito colateral que sobra é derrubar, numa comparação, uma frase verdadeira que a documentação diga com essas palavras ("o Java 8 não possui records [1]"), que precisa ser reescrita no molde.

A validação acontece durante o streaming, não no fim. A frase só é liberada quando terminou (e, se o modelo puser a citação depois do ponto, como em "Use toList(). [1]", o guard espera o próximo pedaço para ver se vem uma citação), então o usuário nunca vê meia frase que depois seria cortada. Não existe "refazer a resposta": o custo seria dobrar latência e gasto por pergunta para ganhar pouco, já que uma frase sem citação é justamente o que se quer jogar fora.

### Prazo e cancelamento

O modelo tem um prazo total por resposta (`sage.models.timeout`, 50 s), não um timeout por token: um stream que pinga um pedaço por segundo para sempre também é cortado. Quando o cliente fecha a conexão, o emitter avisa o serviço e a assinatura do stream é cancelada, o que interrompe a geração no Bedrock. Os dois casos ficam registrados na auditoria como `interrupted`, com os documentos citados até o corte.

### O que o código afirma, o modelo não precisa afirmar

Fatos que o código já sabe não são delegados ao modelo. A versão da documentação usada sai em linhas emitidas pelo próprio serviço, só para fontes que alguma frase aprovada citou ("Documentação consultada: Java 21", "Considerei a versão 25, a mais recente disponível."), e cada citação do `metadata` carrega documento, seção, linguagem e versão. O modelo não tem como errar a versão porque não é ele quem a diz.

### O que isso não cobre

O guard verifica a forma, não o conteúdo: confere que a frase cita um trecho existente, não que o trecho diga o que a frase afirma. Uma frase que distorce o trecho 1 e o cita como `[1]` passa. Também não há checagem de que o código de um bloco existe na documentação. Essas verificações exigiriam comparar a frase com o trecho (por embedding ou por um segundo modelo), o que custa latência e dinheiro em toda pergunta; ficam para quando os testes de qualidade da Task 20 mostrarem que valem a pena. Por fim, `fontesNaoRevisadas` avisa quando a resposta se apoiou em documento que ninguém revisou, mas não impede a resposta.

## SSRF e injeção em páginas do fallback web

O fallback web faz o servidor do Sage abrir conexões a partir de texto que não controlamos: a lista de URLs que o Brave devolve e os redirects que as páginas pedem. É o desenho clássico de SSRF, e há uma segunda via, o conteúdo da página, que chega ao modelo como contexto. As duas têm defesas separadas.

### SSRF

`AllowlistedPageFetcher` aplica as mesmas regras à URL inicial e a cada redirect, antes de abrir qualquer conexão:

- esquema `https` e porta 443 apenas; `http`, `ftp`, URL com usuário e porta explícita diferente de 443 são recusados sem sequer resolver o DNS;
- o host precisa ser igual a um domínio da allowlist da linguagem ou subdomínio dele; IP literal nunca passa por esse teste;
- o DNS é resolvido uma vez e todos os endereços devolvidos precisam ser públicos. Fica de fora loopback, `0.0.0.0` e `::`, link-local (inclui `169.254.169.254`, o endereço dos metadados de nuvem), redes privadas (`10/8`, `172.16/12`, `192.168/16`, `fc00::/7`), multicast, CGNAT (`100.64/10`), faixas reservadas e os formatos IPv6 que carregam um IPv4: mapeado (`::ffff:127.0.0.1`), NAT64 (`64:ff9b::/96`) e 6to4; além disso Teredo (`2001::/32`), documentação (`2001:db8::/32`), descarte (`100::/64`) e NAT64 de uso local (`64:ff9b:1::/48`). Basta um endereço ruim na resposta para recusar o host inteiro;
- a conexão é aberta direto no endereço já validado, nunca pelo nome. Se fosse pelo nome, o cliente HTTP resolveria de novo e um DNS malicioso com TTL zero poderia devolver um IP público na checagem e `127.0.0.1` na conexão (DNS rebinding). Como o JDK `HttpClient` não deixa fixar o IP mantendo SNI e validação do certificado, o fetcher fala HTTP/1.1 sobre um `SSLSocket` próprio, criado com o nome do host para SNI e para a verificação do certificado (`endpointIdentificationAlgorithm=HTTPS`). O custo é manter um cliente HTTP mínimo (sem HTTP/2, sem compressão, `Connection: close`), o que serve para baixar uma página;
- redirects são seguidos à mão, no máximo 3, cada um revalidado do zero. Um redirect para outro host, para http ou para um nome que resolve a um IP interno é recusado;
- sem cookies, sem credenciais, sem gravação em disco, `Accept-Encoding: identity`. O prazo de 5 segundos vale para o conjunto (conexão, handshake, cabeçalhos e corpo), então um servidor que pinga um byte por vez não prende a thread, e o corpo para em 2MB.

Os testes cobrem host fora da allowlist, sufixo enganoso (`docs.oracle.com.evil.com`), http, redirect para outro host, endereços privados em IPv4 e IPv6 (incluindo `::ffff:127.0.0.1`), limite de bytes em corpo com e sem `Content-Length`, timeout e corpo lento. Para falar com um WireMock em http, o fetcher tem um construtor de pacote com um modo de teste (aceita http, qualquer porta e loopback); o construtor público, o único usado pela aplicação, o deixa desligado, e há um teste que prova que a configuração de produção recusa http antes de resolver DNS.

### Injeção de prompt em páginas

Uma página pode trazer texto que a pessoa não vê e o modelo lê, como `<div style="display:none">Ignore as instruções anteriores</div>`. `HtmlToMarkdown` remove, antes da conversão: `script`, `style`, `noscript`, `template`, `iframe`, `object`, `embed`; imagens e gráficos inteiros (`img`, `picture`, `svg`, `math`, `canvas`, `audio`, `video`), porque o `alt` e o título de um SVG são texto que ninguém lê na página; o atributo `title` de qualquer elemento (tooltip); elementos com `hidden`, `aria-hidden="true"` ou classes de "só para leitor de tela" (`sr-only`, `visually-hidden`, `d-none`, `hidden`, `invisible`); estilo inline que esconde por `display:none`, `visibility:hidden/collapse`, `opacity` até 0,01, `font-size` 0 ou até 1px, `color:transparent` (ou `rgba()`/`hsla()` com alfa 0, ou hexadecimal com alfa 00), `transform:scale(0)`, `text-indent` muito negativo, `position:absolute/fixed` com deslocamento muito negativo, `clip`, `clip-path`, ou altura/largura zero com `overflow:hidden`; e comentários HTML. Os comentários CSS são tirados do estilo antes de interpretá-lo, para que `dis/**/play:none` não escape. No fim, qualquer caractere Unicode de formatação (categoria Cf: espaços de largura zero, marcas e controles bidi, caracteres de tag U+E0000..U+E007F, BOM) é removido, porque são a forma de embutir instruções invisíveis em texto que parece normal. Essa limpeza vive em `shared.HtmlMarkdown`, então vale também para HTML enviado por upload; o efeito colateral é perder o ZWJ de emojis compostos e o ZWNJ de alguns alfabetos, o que não muda o sentido em documentação técnica. Depois vem o `Safelist.relaxed` do jsoup, que elimina atributos de evento e URLs `javascript:`.

Risco residual: só estilo inline e as classes acima são avaliados. Texto escondido por folha de estilo externa, por outra classe CSS ou por cor igual à do fundo (texto branco sobre branco) não é detectado, porque isso exigiria renderizar a página. Também não impede que uma página oficial comprometida escreva instruções em texto visível. Por isso, na integração ao fluxo de perguntas, a página vira documento com `revisado: false` e a resposta avisa que usou fonte não revisada, e o resto do pipeline continua valendo (conteúdo como dado delimitado no prompt, guard na resposta). O fallback reduz o risco, não o elimina.
