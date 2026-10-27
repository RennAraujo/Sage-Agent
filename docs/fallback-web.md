# Fallback web

O Sage responde primeiro com o que tem no acervo. O fallback web existe para um caso específico: a pergunta é só sobre uma linguagem pública (Java, Spring Boot, Spring Framework, Spring Security), a versão pedida não está no índice (ou o melhor trecho é fraco demais) e o Sage prefere buscar na documentação oficial a responder de memória. Esta página descreve a configuração, o que é buscado, o que é gravado e as limitações; o lugar do fallback no caminho da pergunta está em [fluxo-de-uma-pergunta.md](fluxo-de-uma-pergunta.md#fallback-web).

## Quem pode ser consultado

A allowlist fica em `sage.web.allowlist` no `application.yml`, uma lista de domínios por linguagem:

```yaml
sage:
  web:
    allowlist:
      java: [docs.oracle.com, openjdk.org]
      spring-boot: [docs.spring.io]
      spring-framework: [docs.spring.io]
      spring-security: [docs.spring.io]
```

A chave é o mesmo id de `aliases-publicos.yml`. Um host vale se for exatamente o domínio ou um subdomínio dele (`docs.oracle.com` e `download.docs.oracle.com` passam; `docs.oracle.com.evil.com` e `notdocs.oracle.com` não). A comparação é por rótulo, nunca por sufixo de texto. Linguagem que não está na lista não tem fallback: adicionar uma é editar o YAML e, se for nova, o `aliases-publicos.yml`.

Os demais limites também são configuráveis: `max-pages-per-question` (3), `fetch-timeout` (5s, vale para a busca inteira, redirects incluídos), `max-page-bytes` (2MB) e `max-redirects` (3).

## Busca

`BraveWebSearch` chama `GET https://api.search.brave.com/res/v1/web/search?q=...&count=10` com o cabeçalho `X-Subscription-Token`. A consulta ganha, no fim, um `site:` por domínio da allowlist ligados por OR, por exemplo `Java 25 streams (site:docs.oracle.com OR site:openjdk.org)`. Mesmo assim, o resultado é filtrado de novo no código: só URL https cujo host está na allowlist sobrevive. Não dá para confiar que o buscador respeita o operador `site:`, e a lista vinda de um serviço de terceiros é entrada não confiável.

O endpoint (`sage.web.brave-endpoint`) precisa ser https; o construtor recusa outro esquema. A busca tem prazo total de `fetch-timeout`, corpo incluído, e a resposta é lida até `max-page-bytes`. Resultado malformado é pulado sem descartar os demais. Domínios da allowlist são validados na subida (ao menos um ponto, sem ponto no começo ou no fim). A chave vem de `SAGE_BRAVE_API_KEY`. Sem ela, o Sage sobe normalmente com uma `NoopWebSearch` que nunca acha nada e registra um aviso na subida; o efeito é o fallback ficar desligado, não um erro. Qualquer falha do Brave (status diferente de 200, JSON inválido, timeout) vira lista vazia e um log com a classe da exceção, sem a URL, porque a consulta pode conter texto da pergunta.

## O que sai para o Brave

A consulta é montada por `WebQueryBuilder`: nome da linguagem, versão e as palavras da pergunta, menos stopwords em português, menos qualquer alias de aplicação interna, e limitada a 150 caracteres. A remoção de alias usa o `AliasCatalog`, a mesma fonte que o roteador usa para decidir se a pergunta é interna. Cobre alias de uma palavra, de várias palavras (`billing core`), variações de caixa e acento e formas compostas como `billing_core_api`, `api.billing.core` e `BillingCoreApi` (o `AliasCatalog.containsInternalAlias` compara o texto sem separadores). Depois de montada, a consulta passa por uma segunda conferência; se ainda houver alias, `build` devolve `Optional.empty()` e a busca web não acontece. Alias com menos de 4 caracteres é pego como palavra inteira, também depois de separar camelCase e letra/dígito (`ErpClient`, `erp2` e `ERPService` casam o alias `erp`; `interpreter` não), e o roteador usa a mesma regra, então essa pergunta já é classificada como interna. Alias de 4 caracteres ou mais é pego também como trecho dentro de uma palavra, e isso falha fechado: com um alias `auth`, uma pergunta que contém "authentication" tem a consulta descartada e o fallback web não roda. É o preço de nunca enviar nome interno a terceiros; para um alias curto e comum, prefira um nome mais específico. É a única coisa dessa pergunta que sai da máquina, então o objetivo é que o nome de um sistema interno nunca chegue a um terceiro. Perguntas misturadas (pública e interna ao mesmo tempo) nem chegam aqui, pela regra da rota.

Uma consequência prática: uma pergunta como "como o billing core usa virtual threads no Java 21" vira `Java 21 usa virtual threads`. Perde um pouco de contexto, o que é o preço certo.

## Download e conversão

`AllowlistedPageFetcher` baixa as páginas. Os detalhes de segurança estão em [seguranca.md](seguranca.md#ssrf-e-injeção-em-páginas-do-fallback-web). Por escolha, a página que passa do limite de bytes é recusada em vez de truncada: HTML cortado ao meio gera Markdown torto, e uma página de documentação com mais de 2MB é rara o bastante para não valer o risco. Só `text/html` e `application/xhtml+xml` com status 200 são aceitos; o charset vem do cabeçalho `Content-Type`; sem ele, vale a detecção do jsoup (`<meta charset>` ou BOM) e, na falta de ambos, UTF-8.

`HtmlToMarkdown` tira o que o leitor humano não veria (veja a seção de injeção) e depois reaproveita o mesmo caminho do upload de HTML: `Safelist.relaxed` do jsoup e flexmark com títulos `#`. Esse caminho agora vive em `shared.HtmlMarkdown`, usado pelos dois lados.

## Limitações

O texto escondido é detectado só por atributos, estilo inline e um conjunto de classes conhecidas (veja a lista em seguranca.md). Folha de estilo externa, outras classes CSS e texto da mesma cor do fundo passam, porque detectar isso exige renderizar a página. Em fontes oficiais o risco é pequeno, mas existe, e a defesa em profundidade fica no resto do pipeline (a página vira documento não revisado, e o guard valida a resposta).

A resolução de DNS usa o resolvedor do sistema e não é interrompida pelo `fetch-timeout`: um DNS lento pode demorar mais que os 5 segundos antes de a conexão começar. Os sockets têm prazo, o `getAllByName` não.

## Quando roda e o que é gravado

A rota `WEB_FALLBACK` vale para pergunta só pública (nunca mista nem interna), com linguagem identificada, de linguagem presente na allowlist, quando a versão pedida não existe no acervo ou o melhor trecho fica abaixo do limiar de relevância ativo, e só se `SAGE_BRAVE_API_KEY` estiver configurada. Sem chave, tudo continua como antes: "Não tenho a documentação do Java 21 no acervo." Perguntas de linguagem pública sem fontes na allowlist recebem "Não há fontes oficiais configuradas para essa linguagem.", sem modelo e sem web.

O `WebFallbackService` busca a versão pedida (ou, se a versão existe mas o trecho não serve, a versão em uso). Em comparação entre versões só as ausentes são buscadas, e o teto de `max-pages-per-question` (3) vale para o total da pergunta, dividido entre elas. Cada página vira um documento `publico/<linguagem>/<versao>/<slug do caminho da URL>.md` com `revisado: false`, `url_origem` e `coletado_em`. Páginas com segredo detectado são descartadas, e cada gravação é auditada como `DOCUMENT_UPLOADED` com `origem=web`, tendo como ator a chave que fez a pergunta. A gravação usa `DocumentStore.saveIfAbsent`: uma página que já existe não é sobrescrita, e duas perguntas simultâneas não duplicam. A resposta sai com `fontesNaoRevisadas` verdadeiro e citações marcadas como não revisadas, porque ninguém olhou aquele texto ainda; o editor pode revisá-lo depois, como qualquer documento.

A versão de uma página é a do pedido, não a que a página diz ter, e é aí que o cuidado aperta. Procurar o número "em qualquer lugar do caminho" erra feio: `jls-8.html` está na SE 21, não no Java 8, e um JEP não diz de que versão é. Por isso cada domínio oficial tem uma estrutura de caminho conhecida (`VersionedUrls`), e o segmento de versão dessa estrutura tem de ser exatamente o pedido: em `docs.oracle.com`, `/[en/][java/]javase/<v>/...` e `/javase/specs/(jls|jvms)/se<v>/...`; em `openjdk.org`, `/projects/jdk/<v>/...`; em `docs.spring.io`, `/spring-(boot|framework|security)/[docs|reference|api/]<maior.menor>[.x|.patch]/...`, com o projeto igual ao da linguagem (`3.50` não é `3.5`, e `/current/` não tem versão). O que não casa com nenhuma estrutura, como JEPs, `jdk21` colado, hosts sem regra e subdomínios, é descartado, e a pergunta fica sem a página em vez de ganhar uma página de outra versão. É conservador de propósito: rotular uma página do Java 21 como Java 17 faria o Sage afirmar a versão errada, o que o projeto trata como pior erro. O custo é perder páginas legítimas fora desses padrões; acrescentar um domínio à allowlist exige acrescentar a regra dele em `VersionedUrls`.

## Prazos e cota

O fallback inteiro de uma pergunta (busca no Brave e até 3 downloads, todas as versões somadas) tem um prazo só, `sage.web.fallback-timeout` (8 s). O `AnswerService` conta esse prazo uma vez e entrega a cada versão o tempo que sobrou da anterior; estourou, a thread é interrompida e valem as páginas já gravadas. O prazo conta dentro de `sage.questions.preparation-timeout` (20 s), que precisa passar o do fallback em pelo menos 5 s (análise, busca e auditoria também cabem ali); a aplicação recusa subir se não passar. Duas perguntas simultâneas pela mesma linguagem e versão não buscam duas vezes: a segunda espera a primeira, dentro do prazo dela, e aproveita o que foi gravado. Para poupar a cota do Brave, a mesma pergunta (mesma linguagem, versão e texto normalizado) só aciona a busca uma vez por hora, tenha achado algo ou não; esse controle e o das buscas simultâneas ficam em memória e se perdem no restart. Não há limite global de chamadas ao Brave além do rate limit por chave de API que já protege `/api/perguntas`.

## Auditoria da saída para a internet

Antes de chamar o Brave, o serviço grava `WEB_SEARCH` na auditoria: a chave que perguntou, a linguagem, a versão e o SHA-256 da consulta, nunca o texto dela. Se a gravação falha, a pergunta falha com erro e nada sai da máquina (fail-closed, como o resto da auditoria). Cada página gera dois `DOCUMENT_UPLOADED` com `origem=web`: a intenção (`resultado=tentativa`), gravada antes de salvar, e o desfecho depois (`salvo`, `existente` se outra pergunta gravou o mesmo documento no meio do caminho, ou `falhou` se a gravação lançou). A versão da página é conferida duas vezes: na URL que o Brave devolveu (já com `.` e `..` resolvidos, então `/javase/21/../25/x` conta como 25) e na URL final depois dos redirects, que é a que rotula o documento; uma página do 8 que redireciona para o 21 é descartada quando se pediu o 8.

## Linguagens reconhecidas sem fontes

Rust e Python estão no `aliases-publicos.yml` para a pergunta ser classificada como pública, mas não têm entrada em `sage.web.allowlist`. Uma pergunta como "Rust 1.80 como funciona o borrow checker?" responde "Não tenho a documentação do Rust 1.80 no acervo." seguida de "Não há fontes oficiais configuradas para essa linguagem.", sem modelo, sem web e sem lacuna. Se a versão existe no acervo mas o trecho é fraco, vale o texto normal "Não encontrei isso na documentação disponível.".
