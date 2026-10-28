---
id: interno/portal-demo/integracao-legado.md
fonte: interno
linguagem: portal-demo
secao: Integração com o ERP legado
coletado_em: "2026-10-01"
revisado: false
---
# Integração com o ERP legado

O portal-demo conversa com o ERP legado da loja por troca de arquivos. O ERP não tem API, então tudo passa por um bucket de armazenamento de objetos.

## Janela de sincronização

A sincronização de pedidos roda todo dia às 02:00, horário de Brasília, e leva em média 20 minutos. O portal-demo-batch grava um arquivo CSV com os pedidos do dia anterior, separado por ponto e vírgula, e o ERP lê esse arquivo e responde com o arquivo de retorno com os números das notas.

## Acesso ao bucket

O bucket se chama portal-demo-legado. A chave de acesso usada pelo ERP para ler os arquivos é AKIAIOSFODNN7EXAMPLE, e não deve ser compartilhada fora do Squad Portal. A rotação da chave é feita a cada 90 dias.

## Quando falha

Se o arquivo de retorno não aparecer até as 06:00, o batch abre um chamado para o time do ERP e os pedidos ficam sem número de nota até o dia seguinte.
