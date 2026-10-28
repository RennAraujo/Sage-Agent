---
id: interno/portal-demo/pagamento.md
fonte: interno
linguagem: portal-demo
secao: Pagamento
coletado_em: "2026-10-01"
revisado: true
---
# Pagamento no portal-demo

O portal-demo cobra os clientes por meio de um gateway de pagamento externo. As formas aceitas são cartão de crédito, PIX e boleto.

## Chamadas ao gateway

Toda chamada de cobrança tem timeout de 8 segundos. Se o gateway não responder nesse prazo, o portal tenta de novo até 3 vezes, esperando 2, 4 e 8 segundos entre as tentativas. Cada cobrança leva uma chave de idempotência no cabeçalho Idempotency-Key, com o número do pedido, para que uma tentativa repetida nunca cobre o cliente duas vezes.

## Cartão

Compras no cartão acima de R$ 500 passam pela autenticação 3DS. Cada pedido aceita no máximo 3 tentativas de cartão; depois disso o cliente precisa escolher outra forma de pagamento.

## PIX e boleto

O código PIX expira em 30 minutos. O boleto vence em 3 dias corridos, e o pedido fica no estado AGUARDANDO_PAGAMENTO até a compensação, que pode levar até 2 dias úteis.

## Estorno

O estorno de cartão é pedido pelo suporte e leva até 10 dias úteis para aparecer na fatura. Estornos acima de R$ 2.000 exigem a aprovação de um coordenador.
