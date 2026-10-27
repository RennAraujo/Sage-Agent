# Decisões

Registro das escolhas que mais pesaram no desenho, com o contexto em que foram feitas, o que ficou de fora e o que cada escolha custa. Cada entrada responde a uma pergunta que alguém vai fazer daqui a seis meses: "por que não fizeram do jeito óbvio?".

## Lucene em memória

**Contexto.** O requisito central é que nenhum trecho do acervo exista em texto puro fora da memória do processo. Um índice precisa guardar o texto dos trechos (para devolvê-los) e a lista de termos, e os dois são, na prática, o acervo em claro.

**Opções.**

- Lucene com diretório em disco. É o uso normal, rápido na subida, e o Lucene é muito bem testado nesse modo. Mas deixa arquivos legíveis no volume, e cifrar o diretório é complicado: o Lucene lê por acesso aleatório e mapeia arquivos na memória.
- Lucene em disco sobre um volume cifrado do sistema operacional. Resolve para quem tem o volume, mas faz a garantia depender de configuração fora do código, justamente a que mais falha em silêncio.
- Um serviço de busca externo (OpenSearch e similares). Mais peças, mais credenciais e o texto saindo do processo, o oposto do que se quer.
- Lucene em memória (`ByteBuffersDirectory`), reconstruído na subida a partir do acervo cifrado.

**Escolha.** Lucene em memória. O índice é derivado do acervo cifrado, que continua sendo a fonte da verdade; quando o processo cai, o índice some com ele e a subida seguinte o refaz. Há um teste que confere o diretório de dados, o temporário do sistema e o diretório de trabalho depois de indexar.

**Consequência.** Paga-se em RAM (de uma a uma vez e meia o tamanho do Markdown, com pico de até o dobro durante fusões de segmento) e em tempo de subida, durante o qual o serviço se declara DOWN. Só cabe uma instância: cada réplica reconstrói o seu índice. Para o porte esperado (equipe pequena, dezenas de MB de documentação) é barato; para centenas de MB deixaria de ser, e aí a conversa passa a ser sobre o volume cifrado.

## Embeddings multilíngues

**Contexto.** A documentação pública está em inglês e as perguntas chegam em português. O BM25 só casa palavras iguais, então "como ordenar uma lista" não encontra "How to sort a List". Os vetores resolvem isso, mas rodar o modelo tem custo, e o modelo também não pode mandar texto de documento para fora.

**Opções.**

- API de embeddings hospedada (Bedrock, por exemplo). Qualidade alta e nada a manter, mas cada trecho do acervo sairia do processo para ser vetorizado, o que contraria o requisito do item anterior.
- Traduzir as perguntas para inglês antes de buscar. Reaproveita o BM25, mas acrescenta uma chamada de modelo por pergunta, perde nuance e falha nos identificadores traduzidos sem querer.
- Modelo local multilíngue em ONNX na CPU. Os candidatos eram `multilingual-e5-small` (384 dimensões, 470 MB), `multilingual-e5-base` (768 dimensões, cerca de 1,1 GB) e o `paraphrase-multilingual-MiniLM`. O base é melhor em qualidade e pior em tudo mais: o dobro de memória por vetor e quase o triplo de latência.

**Escolha.** `intfloat/multilingual-e5-small`, na exportação ONNX fp32 que o próprio repositório publica, com a revisão fixada no `models.lock` e o sha256 conferido no download e no build da imagem. Foi o menor que cobre português e inglês com qualidade aceitável em perguntas de programação, e roda na CPU de um contêiner pequeno. A fusão com o BM25 é por Reciprocal Rank Fusion, que só usa posições e dispensa calibrar duas escalas de score diferentes.

**Consequência.** O modelo pesa 470 MB e fica fora do git; quem clona precisa rodar o script de download, e sem ele a aplicação sobe só com BM25 (com um aviso no log), o que mantém os testes e o desenvolvimento simples. Gerar vetores é lento perto do BM25: cerca de 60 ms por trecho, o que alonga o upload e a carga inicial (detalhes e números em [arquitetura.md](arquitetura.md#busca-híbrida)). A qualidade do modelo pequeno é o teto desta etapa; o reranker do desenho existe para compensar. Se um dia o modelo mudar, os vetores antigos deixam de servir, mas como o índice é refeito a cada subida a troca é só de arquivos.

## Reranker multilíngue em ONNX

**Contexto.** A busca híbrida ordena os candidatos, mas a posição não diz se o melhor deles responde à pergunta, e a decisão de seguir com contexto ou registrar uma lacuna precisa de um score com esse significado. O reranker precisa ser multilíngue (pergunta em português, documentação em inglês), rodar na CPU e não mandar texto de documento para fora.

**Opções.**

- `BAAI/bge-reranker-v2-m3`: qualidade melhor, mas é um XLM-R grande (cerca de 570 M de parâmetros) e o repositório oficial não publica ONNX. Pontuar 30 pares na CPU levaria vários segundos, mesmo com trechos curtos (estimativa pelo tamanho, não medida).
- `jinaai/jina-reranker-v2-base-multilingual`: tem ONNX, mas é um modelo base (cerca de 280 M de parâmetros) e a licença é não comercial (CC-BY-NC), ruim para um projeto que alguém pode querer usar numa empresa.
- Reranker por LLM (Bedrock): contraria o requisito de não enviar o acervo para fora e acrescenta uma chamada por pergunta.
- `cross-encoder/mmarco-mMiniLMv2-L12-H384-v1`: 12 camadas, 384 de largura, treinado no mMARCO (inclui português), licença Apache 2.0, e o repositório oficial já traz a exportação ONNX junto do `tokenizer.json`.

**Escolha.** O mMiniLM cross-encoder, no ONNX fp32 oficial, com a revisão `1427fd65` fixada no `models.lock` e sha256 conferido no download e no build da imagem (`reranker.onnx`, 470 MB, e `reranker-tokenizer.json`, 17 MB). É o único dos candidatos que junta multilíngue, ONNX pronto, licença permissiva e tamanho compatível com a CPU.

**Consequência.** Com trechos curtos, 30 pares levam cerca de 260 ms; com os 20 candidatos padrão no teto de 1500 caracteres e pares cortados em 256 tokens (`sage.rerank.max-tokens`), 0,8 a 1 s, sem o corte cerca de 2 s (números, o preço do corte em qualidade e alternativas testadas em [arquitetura.md](arquitetura.md#reranker-e-o-score-que-decide-o-caminho)). A imagem Docker cresce 490 MB. O score é `sigmoid(logit)` e não é calibrado, então o limiar `min-score` de 0,35 é um ponto de partida a validar com perguntas reais. Sem os arquivos do modelo a aplicação continua subindo, com um score de fallback lexical que mantém a ordem da busca híbrida e está documentado como substituto, não equivalente.

## Escalada pelo próprio Haiku

**Contexto.** O Sonnet responde melhor que o Haiku quando a pergunta pede cruzar fontes ou quando os trechos são fracos, e custa bem mais por token, com mais latência. Mandar tudo para o Sonnet desperdiça dinheiro na maioria das perguntas, que são consultas diretas; mandar tudo para o Haiku entrega respostas rasas justamente nas difíceis. Falta decidir quem escolhe e quando.

**Opções.**

- Regra no código: score baixo, muitos documentos diferentes no contexto, pergunta longa. É barato e previsível, mas nenhum desses sinais diz se os trechos respondem à pergunta. O score do reranker mede relevância de cada trecho, não se o conjunto basta, e um limiar a mais seria mais um número para calibrar no escuro.
- Uma chamada de classificação antes da resposta ("esta pergunta é difícil?"). Acrescenta uma ida ao Bedrock em toda pergunta, inclusive nas fáceis, para economizar em poucas.
- Deixar o Haiku responder e depois avaliar a resposta para refazer com o Sonnet. Paga as duas chamadas no caso ruim e, com streaming, o usuário já viu a primeira resposta.
- O Haiku decide, com uma ferramenta `escalate(reason)` que ele chama antes de escrever. Ele já está lendo os trechos e a pergunta; é quem tem a informação.

**Escolha.** A ferramenta. O prompt do FAST diz quando chamá-la ("se os documentos não bastarem ou a pergunta exigir análise entre várias fontes") e que tem de ser antes de qualquer texto. O código respeita isso ao pé da letra: escalada antes de texto cancela o Haiku e chama o Sonnet com o mesmo contexto; depois de texto, é ignorada e registrada no log (sem o motivo, que é texto do modelo). A ferramenta nunca executa: o Spring AI 2.0 não roda ferramentas dentro do `ChatModel`, então a chamada chega como dado e vira `ModelEvent.EscalationRequested`. As comparações não passam por essa decisão, vão direto para o Sonnet, porque são por definição o caso de análise entre fontes.

**Consequência.** Pergunta escalada paga a latência de uma chamada a mais (o Haiku até decidir, tipicamente o tempo do primeiro token) e os tokens de entrada do prompt duas vezes. A escalada vale até a primeira frase aprovada, não até o primeiro texto bruto, porque um preâmbulo sem citação ("vou passar para um modelo mais forte") é comum e o guard o descarta. Para o `metadata` sair com a rota certa, no caminho FAST ele espera essa primeira saída aprovada em vez de sair antes da chamada. A qualidade da decisão depende do Haiku: ele pode escalar demais (custo) ou de menos (resposta rasa), e nenhum dos dois casos é detectado pelo código. O número de escaladas aparece no log e na auditoria (`escalated=true`), que é o dado para revisar o texto do prompt depois.

## Comparação com busca separada por versão

**Contexto.** Requisito do usuário: perguntas de disponibilidade ("isso existe no java 25 ou é exclusivo do java 8?") e de diferença entre versões são respondidas versão a versão, e versão pedida que não está no acervo é dita explicitamente. Com uma busca só para as duas versões, os 5 trechos do contexto vão quase sempre para a versão com mais texto sobre o assunto. A outra fica sem trecho, e o modelo lê o silêncio como "não existe nessa versão".

**Opções.**

- Uma busca com as duas versões no filtro, confiando no reranker. É o que já existia, e o problema acima é exatamente o que acontece.
- Uma busca com mais candidatos e depois balancear por versão. Resolve em parte, mas uma versão pode não ter nenhum candidato entre os 20 primeiros, e aí não há o que balancear.
- Uma busca por versão, cada uma com a sua fatia do contexto.

**Escolha.** Uma busca por versão, e mais uma para a documentação interna numa pergunta mista. Um teto próprio, `sage.retrieval.comparison-max-chunks` (8), é dividido entre os grupos (4 e 4 com duas versões; 3, 3 e 2 com a interna), cada grupo fica só com o que passa no limiar de relevância, e a sobra de um grupo vai para os que encheram a sua parte. O prompt agrupa os trechos por versão, com `versao` no delimitador, e pede a resposta na ordem das versões. Quando os trechos de uma versão não falam do assunto, o modelo deve escrever no molde "A documentação do Java 8 disponível não menciona records." e citar esses trechos, nunca "o Java 8 não tem isso": a ausência no acervo não prova ausência na linguagem. Nas comparações, o guard descarta qualquer outra afirmação de ausência que nomeie versão ou linguagem. Versão fora do acervo é dita pelo código logo no começo; versão sem trecho relevante, pelo código, na posição dela na resposta. A comparação só deixa de chamar modelo quando nenhuma das versões pedidas existe.

**Consequência.** Uma comparação de duas versões custa duas buscas e dois reranks em vez de um (cerca de duas vezes o tempo de busca, algumas centenas de milissegundos com o reranker ligado), três com a interna, e uma a mais quando há sobra para redistribuir. O contexto da comparação vai até 8 trechos, contra 5 da pergunta comum, o que encarece o prompt do Sonnet. Com o piso de 2 por grupo, cabem no máximo 4 grupos: uma pergunta com seis versões compara as quatro primeiras e diz isso, em vez de diluir o contexto em um trecho por versão. Comparação entre linguagens diferentes ainda usa a busca única. O guard de ausência mira só afirmações de inexistência ou indisponibilidade ("não existe", "não está disponível", "não há suporte", "inexistente", "não possui"). Eventos que a documentação afirma, como "foi removido no Java 11 [1]" ou "foi descontinuado", passam como qualquer frase citada: são fatos lidos, não silêncio interpretado. Uma primeira versão barrava também "foi removido" e derrubava justamente as respostas mais úteis de uma pergunta de disponibilidade. A regra também só vale nas comparações e só para frases que nomeiam versão ou linguagem: "lança exceção se o arquivo não existe" é comportamento, não afirmação sobre o que uma versão tem.

## Verificação de fundamentação com NLI

**Contexto.** Marcadores de citação não provam que a frase é sustentada pelo trecho. Precisamos decidir, frase a frase, se um trecho implica o que a resposta afirma, em português sobre documentação em inglês, na CPU e sem mandar texto para fora.

**Opções.**

- Um LLM como juiz (Bedrock): contraria o requisito de não enviar o acervo para fora e acrescenta uma chamada por frase.
- `MoritzLaurer/multilingual-MiniLMv2-L6-mnli-xnli`: 6 camadas, 430 MB, ONNX oficial, MIT, rápido (cerca de 20 ms por par curto). Foi o primeiro candidato, por casar com o exemplo do plano.
- `MoritzLaurer/mDeBERTa-v3-base-xnli-multilingual-nli-2mil7`: modelo base, 1,1 GB em fp32, ONNX oficial, MIT, treinado em XNLI mais MNLI e outros conjuntos.
- O mesmo mDeBERTa quantizado (`model_quantized.onnx`, 340 MB).

**Testes.** Em 29 pares rotulados (`nli-pairs.tsv`), no limiar 0,5, o MiniLM L6 concorda com 20 (69 %) e o mDeBERTa fp32 com todos (100 %). Com a premissa "Stream.toList() returns an unmodifiable List." e a hipótese em português:

| Hipótese | MiniLM L6 | mDeBERTa fp32 | mDeBERTa int8 |
| --- | --- | --- | --- |
| "O método toList retorna uma lista imutável." | 0,76 | 0,999 | 0,88 |
| "O método toList retorna uma lista mutável." | 0,84 | 0,016 | 0,39 |
| "O Spring Boot usa Tomcat como servidor padrão." | 0,007 | 0,003 | 0,06 |

O MiniLM dá nota maior à contradição do que ao par correto: ele reage ao vocabulário em comum, não à negação, e em pares como "can be modified" contra "unmodifiable" também errou. Aprovar "lista mutável" é justamente o erro que o filtro existe para pegar. O int8 separa, mas com margem pequena (0,88 contra 0,39) e errou outros pares de negação, o que deixaria o limiar de 0,5 instável.

**Escolha.** `mDeBERTa-v3-base-xnli-multilingual-nli-2mil7` em fp32, revisão `b5113eb3` fixada no `models.lock`, com sha256 de `nli.onnx`, `nli-tokenizer.json` e `nli-config.json` conferido no download e no build da imagem. Os rótulos são lidos do `id2label` do config, não supostos.

**Consequência.** A imagem cresce 1,1 GB (a soma dos modelos passa de 2 GB). Em memória, os três modelos juntos levam o processo a cerca de 3,7 GB residentes (o NLI sozinho, uns 1,5 GB), então o contêiner precisa de 4 GB ou mais. A latência é de cerca de 200 ms por frase com dois trechos citados e de 1 a 1,6 s no pior caso (5 trechos no teto); numa resposta de 8 frases isso dá ~1,7 s no caso típico e 8 a 13 s no pior (números em [seguranca.md](seguranca.md#custo-de-latência)). Pagamos isso porque um verificador que aprova a contradição é pior do que nenhum: dá falsa sensação de segurança. Se o custo incomodar, o caminho é reduzir `sage.guard.nli-max-tokens` ou verificar só frases com afirmações técnicas, antes de trocar por um modelo menor. Sem os arquivos, a aplicação sobe com um verificador que aprova toda frase com trecho citado e o guard fica só com citações e segredos; em produção, `sage.guard.require-nli=true` (padrão da imagem Docker) impede isso e faz a partida falhar. O código de sessão, tokenizer e truncamento é um só (`OnnxPairModel`), usado pelo reranker e pelo NLI.
