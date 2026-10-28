---
id: interno/portal-demo/notificacoes.md
fonte: interno
linguagem: portal-demo
secao: Notificações
coletado_em: "2026-10-01"
revisado: false
---
# Notificações do portal-demo

O portal-demo envia e-mails transacionais e mensagens de SMS. Os modelos de e-mail ficam no repositório do portal e são renderizados no momento do envio.

## E-mail

Os e-mails saem pelo serviço de e-mail da nuvem, com o remetente nao-responder@loja.example. Se o envio falhar, o portal tenta de novo até 5 vezes, com intervalo que dobra a cada tentativa, começando em 1 minuto. Depois da quinta falha a mensagem vai para a fila de mensagens mortas e o Squad Portal recebe um alerta.

## SMS

O SMS é usado para o código de verificação do login e para o aviso de entrega. O código de verificação vale por 5 minutos. O cliente só recebe SMS de entrega se marcou essa opção no cadastro.

## Regras de envio

Mensagens iguais para o mesmo cliente são descartadas dentro de uma janela de 10 minutos, para evitar duplicidade. Mensagens de marketing não são enviadas entre 22h e 7h, horário de Brasília. Mensagens transacionais, como a confirmação de pedido, não têm essa restrição.
