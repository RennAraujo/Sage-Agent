---
id: interno/portal-demo/fluxo-checkout.md
fonte: interno
linguagem: portal-demo
secao: Fluxo de checkout
coletado_em: "2026-10-01"
revisado: true
---
# Fluxo de checkout do portal-demo

O checkout do portal-demo tem seis passos, sempre nesta ordem. Cada passo só começa depois que o anterior termina com sucesso.

## Passos

1. Validação do carrinho. O portal confere preço, quantidade e cupom de cada item contra o catálogo. Se um preço mudou desde que o item entrou no carrinho, o cliente vê o valor novo e precisa confirmar.
2. Reserva de estoque. O portal reserva as unidades por 15 minutos. Se o pagamento não for concluído nesse prazo, a reserva é liberada sozinha.
3. Cálculo do frete. O valor vem do serviço de logística a partir do CEP, e o prazo de entrega é mostrado ao cliente antes do pagamento.
4. Pagamento. A cobrança é feita no gateway, conforme descrito em [[pagamento]]. O pedido só passa para o estado PAGO depois da confirmação do gateway.
5. Confirmação do pedido. O portal grava o pedido, baixa o estoque reservado e gera o número do pedido.
6. Notificações. O cliente recebe o e-mail de confirmação e, se aceitou, uma mensagem de SMS. O envio está em [[notificacoes]].

## Falhas

Se o pagamento for recusado, o fluxo volta ao passo 4 e a reserva de estoque continua valendo até acabar o prazo de 15 minutos. Se a gravação do pedido falhar depois da cobrança aprovada, o portal estorna a cobrança automaticamente e registra o erro para o suporte.
