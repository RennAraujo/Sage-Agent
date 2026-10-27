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
