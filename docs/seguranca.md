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

Ainda não existe ferramenta para ler a auditoria. Hoje a leitura é manual: listar `audit/<dia>/`, decifrar cada blob com o `EnvelopeCipher` e interpretar o JSON.
