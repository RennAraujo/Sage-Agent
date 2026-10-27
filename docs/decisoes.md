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
